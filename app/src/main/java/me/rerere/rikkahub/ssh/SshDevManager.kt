package me.rerere.rikkahub.ssh

import android.content.Context
import android.os.FileObserver
import android.system.Os
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceBackgroundCommand
import me.rerere.workspace.WorkspaceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.resume

class SshDevManager(context: Context, repository: WorkspaceRepository, workspaceManager: WorkspaceManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = File(context.filesDir, "ssh-dev").apply { mkdirs(); Os.chmod(absolutePath, 448) }
    private val settingsFile = File(directory, "config.json")
    private val mutableConfig = MutableStateFlow<SshDevConfig?>(null)
    val config: StateFlow<SshDevConfig?> = mutableConfig
    private val runtime = AndroidSshRuntime(directory, repository, workspaceManager)
    private val controller = SshDevController(runtime, scope)
    private val mutableDiagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = mutableDiagnostics
    fun refreshDiagnostics(): Job = scope.launch { mutableDiagnostics.value = runtime.logTail() }
    val state: StateFlow<SshDevState> = controller.state
    private val initialized = scope.async {
        controller.initialize()
        if (settingsFile.exists()) {
            runCatching {
                val json = JSONObject(settingsFile.readText())
                val keys = json.getJSONArray("publicKeys")
                SshDevConfig(json.getString("workspaceId"), json.getInt("port"), (0 until keys.length()).map { keys.getString(it) }).validated()
            }.onSuccess { mutableConfig.value = it }
                .onFailure { controller.reportServiceFailure("保存的 SSH 配置无效，请重新填写并保存") }
        }
    }
    private var startJob: Job? = null

    init {
        scope.launch {
            initialized.await()
            repository.listFlow().collect { entries ->
                val id = state.value.workspaceId ?: return@collect
                val workspace = entries.firstOrNull { it.id == id }
                if (state.value.phase in setOf(SshDevPhase.STARTING, SshDevPhase.RUNNING) &&
                    (workspace == null || workspace.isSsh() || workspace.shellStatus != WorkspaceShellStatus.READY.name)) {
                    controller.stop("工作区已删除或 Rootfs 不再就绪，SSH 已停止")
                }
            }
        }
    }

    suspend fun save(config: SshDevConfig) = withContext(Dispatchers.IO) {
        initialized.await()
        check(state.value.phase !in setOf(SshDevPhase.STARTING, SshDevPhase.RUNNING, SshDevPhase.STOPPING) && !state.value.ownsProcess) { "请先停止 SSH 再修改配置" }
        val valid = config.validated()
        val json = JSONObject().put("workspaceId", valid.workspaceId).put("port", valid.port).put("publicKeys", JSONArray(valid.publicKeys))
        atomicPrivateWrite(settingsFile, json.toString())
        mutableConfig.value = valid
    }

    @Synchronized
    fun start(): Job {
        startJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            initialized.await()
            if (BuildConfig.IS_PLAY_STORE) {
                controller.reportServiceFailure("SSH 开发连接仅在本次 GitHub 隔离构建中开放，Play 发布尚未审核")
                return@launch
            }
            val saved = config.value
            if (saved == null) controller.reportServiceFailure("请先保存工作区、端口和电脑公钥")
            else controller.start(saved)
        }.also { startJob = it }
    }

    fun stop(reason: String = "已停止"): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        initialized.await()
        controller.stop(reason)
    }

    fun serviceFailure(message: String): Job = scope.launch {
        initialized.await()
        controller.reportServiceFailure(message)
    }
}

private fun atomicPrivateWrite(file: File, text: String) {
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try {
        listOf(file, File(file.path + ".new")).filter { it.exists() }.forEach { Os.chmod(it.absolutePath, 384) }
        output.write(text.toByteArray())
        atomic.finishWrite(output)
        Os.chmod(file.absolutePath, 384)
    } catch (e: Exception) {
        atomic.failWrite(output)
        throw e
    }
}

private const val SSH_PRIVATE_DIR = "/root/.rikkahub-ssh"

private class AndroidSshRuntime(
    directory: File,
    private val repository: WorkspaceRepository,
    private val manager: WorkspaceManager,
) : SshDevRuntime {
    private val lease = File(directory, "active.json")
    private var workspace: WorkspaceEntity? = null
    private var lastHandle: WorkspaceBackgroundCommand? = null
    fun logTail(): String = runCatching {
        val handle = lastHandle ?: return "尚无本次运行日志"
        val size = handle.outputSize()
        String(handle.readOutput((size - 6000).coerceAtLeast(0), 6000)).filter { it == '\n' || it == '\t' || !it.isISOControl() }
    }.getOrElse { "无法读取运行日志" }

    override suspend fun recover() = withContext(Dispatchers.IO) {
        if (!lease.exists()) return@withContext
        val data = runCatching { JSONObject(lease.readText()) }.getOrElse { error("上次 SSH 运行记录损坏；请保留诊断，不要自动重开服务") }
        val root = data.getString("root")
        val id = data.getString("task")
        require(id.matches(Regex("ssh-dev-[0-9a-f-]{36}"))) { "SSH 运行记录无效" }
        val linux = manager.linuxDir(root)
        val pidFile = File(linux, "tmp/rikkahub/background/$id.pid")
        val prootPid = runCatching { pidFile.readText().trim().split(' ')[1].toInt() }.getOrNull()
        // Never signal an unowned/reused PID. A surviving prior PRoot is a fail-closed condition.
        val prior = prootPid?.let { File("/proc/$it") }
        if (prior?.exists() == true) {
            val cmd = runCatching { File(prior, "cmdline").readText() }.getOrElse { error("无法确认上次 SSH 进程已结束，请在 Android 设置中强制停止此测试 App 后重试") }
            check(!(cmd.contains(linux.absolutePath) || cmd.contains(id))) { "检测到上次遗留 PRoot；请在 Android 设置中强制停止此测试 App 后重试，不会自动开放 SSH" }
        }
        // Also cover a crash between Process.start and the PID-file write.
        val live = File("/proc").listFiles().orEmpty().any { p ->
            p.name.toIntOrNull() != null && runCatching { File(p, "cmdline").readText() }.getOrDefault("").let {
                it.contains(id) && it.contains(linux.absolutePath)
            }
        }
        check(!live) { "检测到上次遗留 SSH 进程，请强制停止此测试 App 后重试" }
        requirePortFree(data.getInt("port"))
        check(lease.delete()) { "无法清理旧 SSH 运行记录" }
    }

    override suspend fun prepare(config: SshDevConfig): PreparedSsh = withContext(Dispatchers.IO) {
        val item = requireNotNull(repository.getById(config.workspaceId)) { "工作区不存在" }
        check(item.type == "LOCAL" && !item.isSsh()) { "仅支持手机本地工作区" }
        check(item.shellStatus == WorkspaceShellStatus.READY.name && manager.hasRootfs(item.root)) { "请先安装 Rootfs 并等待 READY" }
        requirePortFree(config.port)
        val linux = manager.linuxDir(item.root)
        val dir = File(linux, SSH_PRIVATE_DIR.removePrefix("/"))
        check(!Files.isSymbolicLink(dir.toPath())) { "SSH 配置目录不能是符号链接" }
        dir.mkdirs()
        check(dir.canonicalPath.startsWith(linux.canonicalPath + File.separator)) { "SSH 配置路径越界" }
        Os.chmod(dir.absolutePath, 448)
        fun command(script: String): String {
            val result = manager.executeCommand(root = item.root, command = "set -eu\numask 077\n$script", timeoutMillis = 20_000, shellCompatibilityMode = item.shellCompatibilityMode)
            check(result.exitCode == 0 && !result.timedOut) { "SSH 环境检查失败：${result.stderr.takeLast(1500)}。请确认已安装 openssh-server、ssh-keygen、bash，且 sshd 配置可用" }
            return result.stdout
        }
        command("command -v sshd ssh-keygen bash >/dev/null; mkdir -p /run/sshd; chmod 755 /run/sshd")
        atomicPrivateWrite(File(dir, "authorized_keys"), config.publicKeys.joinToString("\n", postfix = "\n"))
        command("""
            cd $SSH_PRIVATE_DIR
            if [ ! -f host ]; then
              rm -f host.new host.new.pub
              ssh-keygen -q -t ed25519 -N '' -f host.new
              mv host.new host
              mv host.new.pub host.pub
            fi
            chmod 600 host
            ssh-keygen -y -P '' -f host > host.pub.new
            mv host.pub.new host.pub
            chmod 600 host.pub
        """.trimIndent())
        // No Include: distro service defaults/password/PAM settings must not leak into this server.
        val text = """
            Port ${config.port}
            ListenAddress 127.0.0.1
            HostKey $SSH_PRIVATE_DIR/host
            PidFile $SSH_PRIVATE_DIR/sshd.pid
            PermitRootLogin prohibit-password
            PubkeyAuthentication yes
            AuthenticationMethods publickey
            PasswordAuthentication no
            KbdInteractiveAuthentication no
            PermitEmptyPasswords no
            AuthorizedKeysFile $SSH_PRIVATE_DIR/authorized_keys
            StrictModes yes
            AllowTcpForwarding yes
            AllowStreamLocalForwarding yes
            AllowAgentForwarding no
            GatewayPorts no
            X11Forwarding no
            PermitTunnel no
            Subsystem sftp internal-sftp
            LogLevel VERBOSE
        """.trimIndent() + "\n"
        atomicPrivateWrite(File(dir, "sshd_config"), text)
        command("sshd -t -f $SSH_PRIVATE_DIR/sshd_config")
        val fingerprint = command("ssh-keygen -lf $SSH_PRIVATE_DIR/host.pub").trim()
        check(fingerprint.contains("SHA256:")) { "未取得有效主机公钥指纹" }
        workspace = item
        PreparedSsh(item.name, fingerprint, "ssh-dev-${UUID.randomUUID()}")
    }

    override suspend fun launch(config: SshDevConfig, prepared: PreparedSsh): RunningSsh {
        // Called NonCancellable by the controller. Assign and return ownership before any cancellation.
        val item = requireNotNull(workspace)
        atomicPrivateWrite(lease, JSONObject().put("root", item.root).put("task", prepared.token).put("port", config.port).toString())
        val handle = manager.startBackgroundCommand(root = item.root, command = "exec /bin/sh -c 'exec \"\$(command -v sshd)\" -D -e -f $SSH_PRIVATE_DIR/sshd_config'",
            id = prepared.token, cwd = "", shellCompatibilityMode = item.shellCompatibilityMode)
        lastHandle = handle
        return AndroidRunningSsh(handle, File(manager.linuxDir(item.root), handle.logPath.removePrefix("/")), config.port)
    }

    override suspend fun clearLease() {
        check(!lease.exists() || lease.delete()) { "无法清理 SSH 运行记录" }
    }
}

private fun requirePortFree(port: Int) {
    try { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close() }
    catch (_: Exception) { error("回环端口 $port 被占用；若上次异常退出，请先强制停止测试 App") }
}

private class AndroidRunningSsh(private val handle: WorkspaceBackgroundCommand, private val log: File, private val port: Int) : RunningSsh {
    private val waiter = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exited = waiter.async { handle.waitFor() }
    override val alive: Boolean get() = !exited.isCompleted
    override suspend fun awaitReady() {
        withTimeout(8_000) {
            suspendCancellableCoroutine { continuation ->
                val signaled = java.util.concurrent.atomic.AtomicBoolean(false)
                @Suppress("DEPRECATION")
                val observer = object : FileObserver(log.parentFile!!.absolutePath, MODIFY or CLOSE_WRITE or CREATE) {
                    override fun onEvent(event: Int, path: String?) { if (path == log.name) checkReady() }
                    fun checkReady() {
                        if (!continuation.isActive) return
                        val ready = runCatching {
                            log.inputStream().use { input ->
                                val bytes = ByteArray(8192)
                                val count = input.read(bytes)
                                count > 0 && String(bytes, 0, count).contains("Server listening on 127.0.0.1 port $port.")
                            }
                        }.getOrDefault(false)
                        if (ready && alive && signaled.compareAndSet(false, true)) continuation.resume(Unit)
                    }
                }
                pendingObserver = observer
                observer.startWatching()
                continuation.invokeOnCancellation { observer.stopWatching() }
                observer.checkReady()
            }
        }
        try {
            Socket().use { socket ->
                socket.soTimeout = 2_000
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
                val banner = ByteArray(8)
                java.io.DataInputStream(socket.getInputStream()).readFully(banner)
                check(String(banner) == "SSH-2.0-" && alive) { "SSH 握手失败" }
            }
        } finally { pendingObserver?.stopWatching(); pendingObserver = null }
    }
    private var pendingObserver: FileObserver? = null
    override suspend fun awaitExit(): Int = exited.await()
    override suspend fun stopAndWait() = withContext(Dispatchers.IO) {
        pendingObserver?.stopWatching(); pendingObserver = null
        if (alive) handle.kill()
        withTimeout(8_000) { exited.await() }
        requirePortFree(port)
        waiter.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
