package me.rerere.rikkahub.ui.pages.memory

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.memory.MemorySyncConflict
import me.rerere.rikkahub.data.sync.memory.MemorySyncManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import java.util.UUID

@Composable
fun MemorySyncSummary(memoryId: String, manager: MemorySyncManager) {
    val bindings by manager.bindings.collectAsStateWithLifecycle()
    val statuses by manager.statuses.collectAsStateWithLifecycle()
    val binding = bindings.firstOrNull { it.memoryId == memoryId }
    val status = statuses[memoryId]
    fun time(value: Long?): String = if (value == null || value == 0L) "尚无" else DateUtils.getRelativeTimeSpanString(value).toString()
    Column(modifier = Modifier.testTag("memory_sync_status")) {
        Text(when {
            binding == null -> "同步：未绑定（默认关闭）"
            status?.running == true -> "同步：正在处理"
            (status?.conflicts ?: 0) > 0 -> "同步：${status?.conflicts} 篇冲突待处理"
            status?.error != null -> "同步：需要处理错误"
            !binding.enabled -> "同步：已暂停"
            (status?.lastSuccess ?: 0) > 0 -> "同步：上次已完成"
            else -> "同步：等待连接"
        })
        Text("上次尝试：${time(status?.lastAttempt)}\n上次成功：${time(status?.lastSuccess)}")
        status?.error?.let { Text(it, modifier = Modifier.testTag("memory_sync_error")) }
    }
}

@Composable
fun MemorySyncSettingsDialog(memoryId: String, manager: MemorySyncManager, workspaces: WorkspaceRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bindings by manager.bindings.collectAsStateWithLifecycle()
    val statuses by manager.statuses.collectAsStateWithLifecycle()
    val list by workspaces.listFlow().collectAsStateWithLifecycle(emptyList())
    val ssh = list.filter { it.isSsh() }
    val binding = bindings.firstOrNull { it.memoryId == memoryId }
    var workspaceId by remember(binding?.workspaceId) { mutableStateOf(binding?.workspaceId.orEmpty()) }
    var root by remember(binding?.remoteRoot) { mutableStateOf(binding?.remoteRoot.orEmpty()) }
    var spaceId by remember(binding?.spaceId) { mutableStateOf(binding?.spaceId ?: UUID.randomUUID().toString()) }
    var conflicts by remember { mutableStateOf<List<MemorySyncConflict>>(emptyList()) }
    var selected by remember { mutableStateOf<MemorySyncConflict?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmUnbind by remember { mutableStateOf(false) }
    suspend fun refresh() { conflicts = manager.conflicts(memoryId) }
    fun execute(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block(); refresh() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = failure.message ?: "同步操作失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(statuses[memoryId]?.conflicts) {
        try { refresh() } catch (failure: Exception) { error = failure.message }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.testTag("memory_sync_dialog"), title = { Text("SSH 双向同步") }, text = {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MemorySyncSummary(memoryId, manager)
            Text("仅复用已测试且已确认主机指纹的 SSH 工作区。先在扩展中配置 SSH；凭据不导出到记忆文件，不使用覆盖式 rsync。")
            if (binding == null) {
                CardGroup(title = { Text("选择 SSH 连接（必须显式选择）") }) {
                    if (ssh.isEmpty()) item(headlineContent = { Text("没有 SSH 工作区，请先在扩展中创建并测试连接。") })
                    ssh.forEach { workspace ->
                        item(onClick = { workspaceId = workspace.id }, modifier = Modifier.testTag("memory_sync_workspace_${workspace.id}"), headlineContent = { Text((if (workspaceId == workspace.id) "✓ " else "") + workspace.name) })
                    }
                    formItem {
                        OutlinedTextField(root, { root = it }, label = { Text("服务器数据根目录（绝对路径）") }, modifier = Modifier.fillMaxWidth().testTag("memory_sync_root"), singleLine = true)
                        Text("必须位于普通工作区镜像之外；helper 需预先安装。")
                        OutlinedTextField(spaceId, { spaceId = it }, label = { Text("远端空间 UUID") }, modifier = Modifier.fillMaxWidth().testTag("memory_sync_space"), singleLine = true)
                        TextButton(onClick = { spaceId = UUID.randomUUID().toString() }, enabled = !busy) { Text("生成新空间 UUID") }
                    }
                }
                val valid = workspaceId.isNotBlank() && root.startsWith('/') && runCatching { UUID.fromString(spaceId).toString() == spaceId }.getOrDefault(false)
                FilledTonalButton(enabled = !busy && valid, modifier = Modifier.testTag("memory_sync_create"), onClick = { execute { manager.bind(memoryId, workspaceId, root, spaceId, true) } }) { Text("发布为新空间（已存在则拒绝）") }
                FilledTonalButton(enabled = !busy && valid, modifier = Modifier.testTag("memory_sync_connect"), onClick = { execute { manager.bind(memoryId, workspaceId, root, spaceId, false) } }) { Text("连接已有空间（逐篇比较）") }
            } else {
                CardGroup(title = { Text("当前绑定") }) {
                    item(headlineContent = { Text(list.firstOrNull { it.id == binding.workspaceId }?.name ?: "SSH 连接已失效") }, supportingContent = { Text("${binding.remoteRoot}\n${binding.spaceId}") })
                    item(onClick = { if (!busy) execute { manager.sync(memoryId) } }, modifier = Modifier.testTag("memory_sync_now"), headlineContent = { Text("立即同步") })
                    item(onClick = { if (!busy) execute { manager.setEnabled(memoryId, !binding.enabled) } }, modifier = Modifier.testTag("memory_sync_toggle"), headlineContent = { Text(if (binding.enabled) "暂停自动同步" else "恢复自动同步") })
                    item(onClick = { confirmUnbind = true }, modifier = Modifier.testTag("memory_sync_unbind"), headlineContent = { Text("解除绑定（不删除任何记忆）") })
                }
            }
            CardGroup(title = { Text("冲突：${conflicts.size} 篇") }) {
                if (conflicts.isEmpty()) item(headlineContent = { Text("暂无冲突") })
                conflicts.forEach { conflict ->
                    item(onClick = { selected = conflict }, modifier = Modifier.testTag("memory_sync_conflict_${conflict.path}"), headlineContent = { Text(conflict.path) }, supportingContent = { Text("查看基线 / App / 远端并解决") })
                }
            }
            TextButton(enabled = !busy, onClick = { execute { refresh() } }, modifier = Modifier.testTag("memory_sync_refresh")) { Text("刷新冲突") }
            if (busy) Text("正在同步，请稍候…")
            error?.let { Text(it, modifier = Modifier.testTag("memory_sync_operation_error")) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } })
    if (confirmUnbind) AlertDialog(onDismissRequest = { confirmUnbind = false }, title = { Text("解除绑定？") }, text = { Text("只停止双向同步，不删除 App 或服务器上的任何记忆。") }, confirmButton = {
        TextButton(enabled = !busy, onClick = { confirmUnbind = false; execute { manager.unbind(memoryId) } }, modifier = Modifier.testTag("memory_sync_unbind_confirm")) { Text("解除绑定") }
    }, dismissButton = { TextButton(onClick = { confirmUnbind = false }) { Text("取消") } })
    selected?.let { conflict ->
        MemoryConflictDialog(conflict, busy, { selected = null }) { content ->
            execute { manager.resolve(memoryId, conflict.path, content, conflict.localGeneration, conflict.remoteToken); selected = null }
        }
    }
}

@Composable
private fun MemoryConflictDialog(conflict: MemorySyncConflict, busy: Boolean, onDismiss: () -> Unit, onResolve: (String?) -> Unit) {
    var merged by remember(conflict) { mutableStateOf(conflict.localContent ?: conflict.remoteContent.orEmpty()) }
    var delete by remember(conflict) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.testTag("memory_sync_conflict_dialog"), title = { Text("解决冲突：${conflict.path}") }, text = {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("删除是独立状态，不等于空正文。提交仍检查双方版本，期间再次变更不会静默覆盖。")
            CardGroup {
                formItem(headlineContent = { Text("基线原文") }) { Text(conflict.baseContent ?: "〈不存在 / 已删除〉", modifier = Modifier.testTag("memory_conflict_baseline")) }
                formItem(headlineContent = { Text("App 原文（generation ${conflict.localGeneration}）") }) { Text(conflict.localContent ?: "〈不存在 / 已删除〉", modifier = Modifier.testTag("memory_conflict_local")) }
                formItem(headlineContent = { Text("远端原文（token ${conflict.remoteToken}）") }) { Text(conflict.remoteContent ?: "〈不存在 / 已删除〉", modifier = Modifier.testTag("memory_conflict_remote")) }
            }
            FilledTonalButton(enabled = !busy, onClick = { onResolve(conflict.localContent) }, modifier = Modifier.testTag("memory_conflict_keep_local")) { Text("保留 App 版本") }
            FilledTonalButton(enabled = !busy, onClick = { onResolve(conflict.remoteContent) }, modifier = Modifier.testTag("memory_conflict_keep_remote")) { Text("保留远端版本") }
            OutlinedTextField(merged, { merged = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("memory_conflict_merge"), label = { Text("手工合并完整 Markdown 原文") }, enabled = !busy && !delete)
            Text("手工解决为删除")
            Checkbox(delete, { delete = it }, enabled = !busy, modifier = Modifier.testTag("memory_conflict_delete"))
            FilledTonalButton(enabled = !busy, onClick = { onResolve(if (delete) null else merged) }, modifier = Modifier.testTag("memory_conflict_save_merge")) { Text("提交手工解决") }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("暂不处理") } })
}
