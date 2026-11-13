package me.rerere.rikkahub.ui.pages.memory

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.memory.MemoryArchive
import me.rerere.rikkahub.data.sync.memory.MemoryArchiveDocument
import me.rerere.rikkahub.data.sync.memory.MemoryArchiveFormat
import me.rerere.rikkahub.data.sync.memory.MemoryArchiveSaf
import me.rerere.rikkahub.data.sync.memory.MemoryImportKind
import me.rerere.rikkahub.data.sync.memory.MemoryImportPreview
import me.rerere.rikkahub.data.sync.memory.MemoryImportResult
import me.rerere.rikkahub.data.sync.memory.MemorySyncManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/** Production entry; keeping injection here also permits an isolated database in device tests. */
@Composable
fun MemoryExchangeButton(memoryId: String) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = Modifier.testTag("memory_exchange_open")) {
        Text("导入导出与同步")
    }
    if (show) MemoryExchangeDialog(memoryId, { show = false }, syncManager = koinInject(), workspaceRepository = koinInject())
}

@Composable
fun MemoryExchangeDialog(
    memoryId: String,
    onDismiss: () -> Unit,
    repository: MemoryRepository = koinInject(),
    syncManager: MemorySyncManager? = null,
    workspaceRepository: WorkspaceRepository? = null,
    initialDocuments: List<MemoryArchiveDocument>? = null,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val archive = remember(repository) { MemoryArchive(repository) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<MemoryImportPreview>>(emptyList()) }
    var results by remember { mutableStateOf<List<MemoryImportResult>>(emptyList()) }
    var confirmed by remember { mutableStateOf(setOf<String>()) }
    var markdownPath by remember { mutableStateOf("/imported.md") }
    var showWorkspace by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var exportDocuments by remember { mutableStateOf<List<MemoryArchiveDocument>?>(null) }
    val scopeKind = if (memoryId == MemoryRepository.GLOBAL_MEMORY_ID) "global" else "assistant"
    fun execute(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    suspend fun load(documents: List<MemoryArchiveDocument>) {
        preview = archive.preview(memoryId, documents)
        confirmed = emptySet()
        results = emptyList()
        message = "已完整校验 ${documents.size} 篇，${documents.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }} 字节；尚未写入。"
    }
    LaunchedEffect(initialDocuments, memoryId) {
        if (initialDocuments != null) {
            try { load(initialDocuments) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message }
        }
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val documents = exportDocuments
        exportDocuments = null
        if (uri != null && documents != null) execute {
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "wt")?.use { MemoryArchiveFormat.writeZip(it, documents, scopeKind) }
                    ?: error("无法写入所选位置")
            }
            message = "已导出 ${documents.size} 篇，${documents.sumOf { it.content.toByteArray().size.toLong() }} 字节\n$uri"
        }
    }
    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) execute {
            val documents = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use(MemoryArchiveFormat::readZip) ?: error("无法读取 ZIP")
            }
            load(documents)
        }
    }
    val openMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) execute {
            val documents = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { MemoryArchiveFormat.readMarkdown(it, markdownPath) }
                    ?: error("无法读取 Markdown")
            }
            load(documents)
        }
    }
    val openDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) execute {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            load(MemoryArchiveSaf.readDirectory(resolver, uri))
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("memory_exchange"),
        title = { Text("导入导出与同步") },
        text = {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (scopeKind == "global") "目标：全局记忆（所有共用助手）" else "目标：当前助手记忆")
                Text("仅操作此记忆库；导入不会删除缺少的篇目。最多 2000 篇、64 MiB。", style = MaterialTheme.typography.bodySmall)
                CardGroup(title = { Text("无损 Markdown 交换") }) {
                    item(onClick = { if (!busy) execute { exportDocuments = archive.documents(memoryId); saveZip.launch("memory-export-${System.currentTimeMillis()}.zip") } },
                        modifier = Modifier.testTag("memory_export_zip"), headlineContent = { Text("导出整库 ZIP") }, supportingContent = { Text("保留原文、目录和 manifest SHA-256") })
                    formItem {
                        OutlinedTextField(markdownPath, { markdownPath = it }, label = { Text("单篇导入的目标路径") }, modifier = Modifier.fillMaxWidth().testTag("memory_import_path"), singleLine = true)
                        TextButton(enabled = !busy, onClick = { openMarkdown.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream")) }, modifier = Modifier.testTag("memory_import_markdown")) { Text("选择 Markdown") }
                    }
                    item(onClick = { if (!busy) openZip.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.testTag("memory_import_zip"), headlineContent = { Text("导入 ZIP（先校验与预览）") })
                    item(onClick = { if (!busy) openDirectory.launch(null) }, modifier = Modifier.testTag("memory_import_directory"), headlineContent = { Text("导入 SAF 目录") }, supportingContent = { Text("持久只读授权；支持原始 Markdown 目录或快照目录") })
                    if (workspaceRepository != null) item(onClick = { showWorkspace = true }, modifier = Modifier.testTag("memory_export_workspace"), headlineContent = { Text("导出到本地工作区新快照") })
                }
                if (preview.isNotEmpty()) {
                    CardGroup(title = { Text("导入预览：${preview.size} 篇") }, modifier = Modifier.testTag("memory_import_preview")) {
                        preview.forEach { item ->
                            formItem(headlineContent = { Text(item.path) }) {
                                Text(when (item.kind) {
                                    MemoryImportKind.NEW -> "新增"
                                    MemoryImportKind.IDENTICAL -> "相同（跳过正文写入）"
                                    MemoryImportKind.CHANGED -> "修改：需逐篇确认"
                                    MemoryImportKind.REJECTED -> "拒绝：${item.error}"
                                })
                                if (item.kind == MemoryImportKind.CHANGED) {
                                    Row {
                                        Checkbox(item.path in confirmed, { checked -> confirmed = if (checked) confirmed + item.path else confirmed - item.path }, modifier = Modifier.testTag("memory_import_confirm_${item.path}"))
                                        Text("确认用导入原文替换", Modifier.padding(top = 12.dp))
                                    }
                                    Text("当前原文：\n${item.currentContent}", modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()))
                                    Text("导入原文：\n${item.content}", modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()))
                                }
                            }
                        }
                    }
                    FilledTonalButton(enabled = !busy && results.isEmpty(), onClick = { execute { results = archive.apply(memoryId, preview, confirmed) } }, modifier = Modifier.testTag("memory_import_apply")) { Text("应用新增与已确认篇目") }
                    TextButton(enabled = !busy, onClick = { execute { load(preview.map { MemoryArchiveDocument(it.path, it.content) }) } }, modifier = Modifier.testTag("memory_import_refresh")) { Text("重新预览（保留并发修改）") }
                }
                results.forEach { result ->
                    Text("${result.path}：${when (result.status) {
                        me.rerere.rikkahub.data.sync.memory.MemoryImportStatus.WRITTEN -> "已写入"
                        me.rerere.rikkahub.data.sync.memory.MemoryImportStatus.UNCHANGED -> "相同，未改动"
                        me.rerere.rikkahub.data.sync.memory.MemoryImportStatus.SKIPPED -> "未确认，已跳过"
                        me.rerere.rikkahub.data.sync.memory.MemoryImportStatus.CONFLICT -> "版本冲突，未覆盖"
                        me.rerere.rikkahub.data.sync.memory.MemoryImportStatus.FAILED -> "失败"
                    }}${result.error?.let { "\n$it" }.orEmpty()}", modifier = Modifier.testTag("memory_import_result_${result.path}"))
                }
                if (syncManager != null && workspaceRepository != null) {
                    MemorySyncSummary(memoryId, syncManager)
                    FilledTonalButton(onClick = { showSync = true }, modifier = Modifier.testTag("memory_sync_settings")) { Text("SSH 双向同步设置与冲突") }
                }
                if (busy) Text("正在处理，请稍候…", modifier = Modifier.testTag("memory_exchange_busy"))
                message?.let { Text(it, modifier = Modifier.testTag("memory_exchange_message")) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } },
    )
    if (showWorkspace && workspaceRepository != null) MemoryWorkspaceExportDialog(workspaceRepository, { showWorkspace = false }) { workspace ->
        execute {
            val documents = archive.documents(memoryId)
            val root = workspaceRepository.resolveFile(workspace, WorkspaceStorageArea.FILES, "") ?: error("必须选择实际本地工作区")
            val directory = withContext(Dispatchers.IO) { MemoryArchiveFormat.writeSnapshot(root, documents, scopeKind) }
            message = "已导出 ${documents.size} 篇，${documents.sumOf { it.content.toByteArray().size.toLong() }} 字节\n${directory.name}"
        }
    }
    if (showSync && syncManager != null && workspaceRepository != null) MemorySyncSettingsDialog(memoryId, syncManager, workspaceRepository) { showSync = false }
}

@Composable
private fun MemoryWorkspaceExportDialog(repository: WorkspaceRepository, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val workspaces by repository.listFlow().collectAsStateWithLifecycle(emptyList())
    val local = workspaces.filter { it.capabilities().localFiles }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择实际本地工作区") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text("将在所选工作区创建独立新快照，不覆盖已有文件。")
            if (local.isEmpty()) Text("没有本地工作区，请先在扩展中创建。")
            local.forEach { workspace ->
                TextButton(onClick = { onDismiss(); onChoose(workspace.id) }, modifier = Modifier.testTag("memory_workspace_${workspace.id}")) { Text(workspace.name) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
