package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.rikkahub.service.SshDevService
import me.rerere.rikkahub.ssh.SshDevConfig
import me.rerere.rikkahub.ssh.SshDevManager
import me.rerere.rikkahub.ssh.SshDevPhase
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingSshPage(
    manager: SshDevManager = koinInject(),
    repository: WorkspaceRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by manager.state.collectAsStateWithLifecycle()
    val saved by manager.config.collectAsStateWithLifecycle()
    val diagnostics by manager.diagnostics.collectAsStateWithLifecycle()
    val workspaces by repository.listFlow().collectAsStateWithLifecycle(emptyList())
    val local = workspaces.filter { it.type == "LOCAL" && !it.isSsh() }
    var selected by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("2222") }
    var keys by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val busy = state.phase in setOf(SshDevPhase.STARTING, SshDevPhase.RUNNING, SshDevPhase.STOPPING) || state.ownsProcess
    LaunchedEffect(saved) {
        saved?.let { selected = it.workspaceId; port = it.port.toString(); keys = it.publicKeys.joinToString("\n") }
    }
    fun startService() {
        try { SshDevService.start(context) }
        catch (_: Exception) { error = "系统拒绝启动前台服务；未开放 SSH，请保持 App 在前台并检查通知权限" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startService() else error = "请先允许通知，才能在连接期间显示状态与停止入口"
    }

    Scaffold(topBar = { LargeFlexibleTopAppBar(title = { Text("SSH 开发连接") }, navigationIcon = { BackButton() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CardGroup {
                item(headlineContent = { Text("安全边界") }, supportingContent = {
                    Text("默认关闭，仅监听 127.0.0.1。电脑通过 USB ADB 转发访问。完整 Shell 可访问该 Rootfs 和 /workspace、/skills 等全部现有挂载，不是路径沙箱。不要导入电脑私钥；首版仅接受 Ed25519 公钥。不会开机自启或自动恢复。")
                })
            }
            if (BuildConfig.IS_PLAY_STORE) {
                Text("此功能仅在 GitHub 隔离测试构建中开放；Play 前台服务用途尚未审核。")
                return@Column
            }
            Text("本地工作区", style = MaterialTheme.typography.titleMedium)
            if (local.isEmpty()) Text("请先新建本地工作区并安装 Linux Rootfs。")
            CardGroup {
                local.forEach { workspace ->
                    item(
                        headlineContent = { Text((if (selected == workspace.id) "✓ " else "") + workspace.name) },
                        supportingContent = { Text(if (workspace.shellStatus == WorkspaceShellStatus.READY.name) "Rootfs READY" else "Rootfs 尚未就绪") },
                        onClick = { if (!busy && !saving) selected = workspace.id },
                    )
                }
            }
            OutlinedTextField(port, { if (it.length <= 5) port = it.filter(Char::isDigit) }, label = { Text("回环端口（1024–65535）") },
                enabled = !busy && !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(keys, { if (it.length <= 82_000) keys = it }, label = { Text("电脑 Ed25519 公钥（每行一个，最多 20 个）") },
                enabled = !busy && !saving, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
            Text("缺少依赖时，请在此工作区终端手动安装 openssh-server、ssh-keygen、bash（使用国内软件源）；不会自动安装软件或启动系统 ssh 服务。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy && !saving && selected.isNotBlank(), onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val config = SshDevConfig(selected, port.toIntOrNull() ?: throw IllegalArgumentException("端口无效"), keys.lineSequence().filter { it.isNotBlank() }.toList()).validated()
                            manager.save(config)
                            error = null
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else startService()
                        } catch (e: Exception) { error = e.message ?: "配置保存失败" }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "保存中" else "保存并启动") }
                OutlinedButton(enabled = !saving && (busy || state.phase == SshDevPhase.ERROR), onClick = {
                    error = null
                    manager.stop()
                }) { Text(if (state.phase == SshDevPhase.ERROR) "重试清理 / 停止" else "停止") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CardGroup {
                item(headlineContent = { Text("状态：${state.phase}") }, supportingContent = { Text(state.message) })
            }
            OutlinedButton(onClick = { manager.refreshDiagnostics() }) { Text("刷新本次运行日志尾部") }
            if (diagnostics.isNotBlank()) SelectionContainer { Text(diagnostics, style = MaterialTheme.typography.bodySmall) }
            if (state.fingerprint.isNotBlank()) {
                Text("服务器主机公钥指纹", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(state.fingerprint) }
            }
            if (state.phase == SshDevPhase.RUNNING) {
                Text("Windows 连接示例（请先核对上面的指纹）", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text("adb forward --no-rebind tcp:${state.port} tcp:${state.port}\nssh -i YOUR_TEST_PRIVATE_KEY -p ${state.port} -o UserKnownHostsFile=./rikka-ssh-known-hosts root@127.0.0.1")
                }
            }
            Text("停止后保留配置与主机密钥，但不会自动重开。系统强制停止/异常退出后请重新检查状态；前台服务不等于永久保活。VS Code Server 兼容性尚未验证。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
