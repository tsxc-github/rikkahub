package me.rerere.rikkahub.data.sync.memory

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.TimeUnit

class MemorySyncManager(
    private val context: Context,
    private val repository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val scope: CoroutineScope,
) {
    private val store = MemorySyncStore(File(context.noBackupFilesDir, "memory-sync"))
    private val engine = MemorySyncEngine(repository, store, MemorySyncRemote { binding, request ->
        if (binding.endpointIdentity != endpointIdentity(binding.workspaceId)) {
            throw MemorySyncException("invalid", "SSH 端点已改变，请解除绑定并重新核对远端空间")
        }
        val json = workspaceRepository.memorySyncRpc(binding.workspaceId, binding.remoteRoot, MemorySyncJson.encodeToString(request))
        MemorySyncJson.decodeFromString<MemoryRpcResponse>(json)
    })
    private val mutableBindings = MutableStateFlow<List<MemorySyncBinding>>(emptyList())
    val bindings: StateFlow<List<MemorySyncBinding>> = mutableBindings.asStateFlow()
    private val mutableStatuses = MutableStateFlow<Map<String, MemorySyncStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, MemorySyncStatus>> = mutableStatuses.asStateFlow()
    private var started = false
    @Volatile private var foreground = false
    private var poller: Job? = null
    private val observers = HashMap<String, Job>()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            var workEnabled: Boolean? = null
            store.state.collect { state ->
                mutableBindings.value = state.libraries.values.map { it.binding }
                mutableStatuses.value = state.libraries.mapValues { (_, lib) -> lib.status.copy(conflicts = lib.conflicts.size) }
                val enabled = state.libraries.filterValues { it.binding.enabled }.keys
                synchronized(observers) {
                    (observers.keys - enabled).forEach { observers.remove(it)?.cancel() }
                    for (id in enabled - observers.keys) {
                        observers[id] = scope.launch(Dispatchers.IO) {
                            repository.snapshotsFlow(id).map { files -> files.map { it.path to it.generation } }
                                .distinctUntilChanged().debounce(2_000).collect { if (foreground) safeSync(id) }
                        }
                    }
                }
                if (workEnabled != enabled.isNotEmpty()) {
                    val work = WorkManager.getInstance(context)
                    if (enabled.isEmpty()) work.cancelUniqueWork(WORK_NAME)
                    else work.enqueueUniquePeriodicWork(
                        WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<MemorySyncWorker>(15, TimeUnit.MINUTES)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
                    )
                    workEnabled = enabled.isNotEmpty()
                }
            }
        }
        scope.launch(Dispatchers.IO) { store.snapshot() }
    }

    @Synchronized
    fun setForeground(value: Boolean) {
        start()
        foreground = value
        poller?.cancel()
        poller = if (value) scope.launch(Dispatchers.IO) {
            while (foreground) {
                syncAll()
                delay(30_000)
            }
        } else null
    }

    suspend fun bind(memoryId: String, workspaceId: String, remoteRoot: String, spaceId: String, createSpace: Boolean) {
        val workspace = requireNotNull(workspaceRepository.getById(workspaceId)) { "SSH 工作区不存在" }
        require(workspace.isSsh()) { "请选择已配置并确认主机公钥的 SSH 工作区" }
        val root = remoteRoot.trimEnd('/').replace(Regex("/{2,}"), "/")
        engine.bind(MemorySyncBinding(memoryId, workspaceId, root, spaceId,
            endpointIdentity = endpointIdentity(workspaceId)), createSpace)
        start()
        sync(memoryId)
    }

    private suspend fun endpointIdentity(workspaceId: String): String {
        val workspace = workspaceRepository.getById(workspaceId)
            ?: throw MemorySyncException("invalid", "SSH 工作区已删除")
        val settings = workspace.sshSettings()
            ?: throw MemorySyncException("invalid", "SSH 工作区配置不可用")
        val key = settings.hostKey ?: throw MemorySyncException("connection_rejected", "请先确认 SSH 主机公钥")
        return "${settings.host.lowercase(java.util.Locale.ROOT)}:${settings.port}|${settings.username}|${key.fingerprint}"
    }

    suspend fun setEnabled(memoryId: String, enabled: Boolean) { engine.setEnabled(memoryId, enabled) }
    suspend fun unbind(memoryId: String) { engine.unbind(memoryId) }
    suspend fun sync(memoryId: String) { engine.sync(memoryId, force = true) }
    suspend fun conflicts(memoryId: String): List<MemorySyncConflict> = store.snapshot().libraries[memoryId]?.conflicts?.values?.toList().orEmpty()
    suspend fun resolve(memoryId: String, path: String, content: String?, expectedLocalGeneration: Long, expectedRemoteToken: String) {
        engine.resolve(memoryId, path, content, expectedLocalGeneration, expectedRemoteToken)
        engine.sync(memoryId, force = true)
    }

    /** Returns false only for transient failures; paused/invalid bindings need user action. */
    suspend fun syncAll(): Boolean {
        val state = store.snapshot()
        var complete = true
        for ((id, lib) in state.libraries) {
            if (!lib.binding.enabled) continue
            if (!safeSync(id)) complete = false
        }
        return complete
    }

    private suspend fun safeSync(id: String): Boolean = try {
        engine.sync(id)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Engine persists and exposes the failure. A disabled binding must not trigger retries.
        store.snapshot().libraries[id]?.binding?.enabled != true
    }

    companion object { private const val WORK_NAME = "memory-markdown-sync" }
}

class MemorySyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters), KoinComponent {
    override suspend fun doWork(): Result = try {
        if (get<MemorySyncManager>().syncAll()) Result.success()
        else if (runAttemptCount < 5) Result.retry() else Result.failure()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { Result.failure() }
}
