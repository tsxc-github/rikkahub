package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.memory.MemoryArchiveDocument
import me.rerere.rikkahub.data.sync.memory.MemorySyncManager
import me.rerere.rikkahub.ui.pages.memory.MemoryExchangeDialog

/** Non-exported debug UI driven by instrumentation; every memory lives in its own in-memory Room DB. */
class MemorySyncProbeActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    lateinit var repository: MemoryRepository
        private set
    private var ready by mutableStateOf(false)
    private var scope by mutableStateOf<String?>(null)
    private var connectedRepository by mutableStateOf<MemoryRepository?>(null)
    private var connectedManager by mutableStateOf<MemorySyncManager?>(null)
    private var connectedWorkspaces by mutableStateOf<WorkspaceRepository?>(null)
    private var imports by mutableStateOf<List<MemoryArchiveDocument>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && packageName == "me.rerere.rikkahub.memoryprobe") {
            "Memory probe requires the isolated debug package"
        }
        database = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .build()
        repository = MemoryRepository(database.memoryDao())
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.writeAtGeneration(MemoryRepository.GLOBAL_MEMORY_ID, "/profile.md", GLOBAL_CONTENT, 0)
                repository.writeAtGeneration(ASSISTANT_ID, "/profile.md", ASSISTANT_CONTENT, 0)
            }
            ready = true
        }
        setContent {
            MaterialExpressiveTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp).testTag("memory_probe"),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Memory sync probe")
                    Text("Disposable in-memory Room fixture; no user memories, SSH, or workspaces.")
                    if (ready) {
                        Text("Fixture ready", Modifier.testTag("memory_probe_ready"))
                        Button(onClick = { showExchange(MemoryRepository.GLOBAL_MEMORY_ID) }) { Text("Open global") }
                        Button(onClick = { showExchange(ASSISTANT_ID) }) { Text("Open assistant") }
                    }
                    scope?.let { memoryId ->
                        MemoryExchangeDialog(
                            memoryId = memoryId,
                            onDismiss = { scope = null },
                            repository = connectedRepository ?: repository,
                            syncManager = connectedManager,
                            workspaceRepository = connectedWorkspaces,
                            initialDocuments = imports,
                        )
                    }
                }
            }
        }
    }

    fun showExchange(memoryId: String, documents: List<MemoryArchiveDocument>? = null) {
        check(ready)
        imports = documents
        scope = memoryId
    }
    fun showConnectedExchange(
        memoryId: String,
        repository: MemoryRepository,
        manager: MemorySyncManager,
        workspaces: WorkspaceRepository,
    ) {
        connectedRepository = repository
        connectedManager = manager
        connectedWorkspaces = workspaces
        showExchange(memoryId)
    }


    override fun onDestroy() {
        super.onDestroy()
        if (::database.isInitialized) database.close()
    }

    companion object {
        const val ASSISTANT_ID = "memory-probe-assistant"
        const val GLOBAL_CONTENT = "---\r\ntitle: Global fixture\r\nunknown: preserved\r\n---\r\n- [stated] 喜欢茶 ☕\r\n"
        const val ASSISTANT_CONTENT = "# Assistant fixture\nIsolated assistant memory.\n"
    }
}
