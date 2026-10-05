package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.workspace.StorageVolumeOption
import me.rerere.rikkahub.data.workspace.WorkspaceMountConfig
import me.rerere.rikkahub.data.workspace.WorkspaceMountRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

/**
 * 把外部存储 (内部共享存储 / U 盘) 挂载进 Agent 工作区。
 *
 * 默认全部关闭: PRoot 没有只读绑定, 挂载一旦生效, 沙箱内任何命令对该目录都可写。
 *
 * 文案先内联中文, 没有走 stringResource, 避免为这个个人分支改动 values/strings.xml。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWorkspaceStoragePage() {
    val repository = koinInject<WorkspaceMountRepository>()
    val scope = rememberCoroutineScope()

    var volumes by remember { mutableStateOf(emptyList<StorageVolumeOption>()) }
    var config by remember { mutableStateOf(WorkspaceMountConfig()) }
    var hasBroadAccess by remember { mutableStateOf(false) }
    var targetDrafts by remember { mutableStateOf(emptyMap<String, String>()) }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            volumes = repository.volumes()
            config = repository.config()
            hasBroadAccess = repository.hasBroadStorageAccess()
        }
        // 用户改过的挂载点优先展示, 其余用默认值填充
        targetDrafts = volumes.associate { volume ->
            val saved = config.entries.firstOrNull { it.id == volume.id }?.target
            volume.id to (saved ?: repository.defaultTargetFor(volume))
        }
    }

    suspend fun persist(volume: StorageVolumeOption, enabled: Boolean) {
        val target = targetDrafts[volume.id] ?: repository.defaultTargetFor(volume)
        // 读写都放在 IO 线程: config() 内部会 stat 文件取 mtime
        config = withContext(Dispatchers.IO) {
            repository.setEnabled(volume, enabled, target)
            repository.config()
        }
    }

    LaunchedEffect(Unit) { reload() }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        scope.launch { reload() }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scope.launch { reload() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作区外部存储") },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("挂载进来的目录是可写的", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "PRoot 没有只读绑定: 目录一旦挂进来, 沙箱里运行的任何命令" +
                            "(包括模型自己生成的) 都能读取、修改、删除里面的文件。" +
                            "只挂你真正需要的目录, 重要数据先备份。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!hasBroadAccess) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("当前是受限访问", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "没有「所有文件访问」权限时, Android 只允许按路径读取媒体文件。" +
                                "Download、Documents 以及整张 U 盘都读不到, 挂上去也不会生效。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val broadIntent = repository.broadAccessIntent()
                            if (broadIntent != null) {
                                Button(onClick = {
                                    runCatching { settingsLauncher.launch(broadIntent) }
                                        .onFailure {
                                            repository.broadAccessFallbackIntent()
                                                ?.let(settingsLauncher::launch)
                                        }
                                }) {
                                    Text("申请所有文件访问")
                                }
                            }
                            TextButton(onClick = {
                                permissionLauncher.launch(repository.mediaPermissions())
                            }) {
                                Text("只授权媒体")
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("可挂载的存储位置", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { scope.launch { reload() } }) { Text("重新扫描") }
            }

            if (volumes.isEmpty()) {
                Text("没有检测到存储卷。", style = MaterialTheme.typography.bodySmall)
            }

            volumes.forEach { volume ->
                val entry = config.entries.firstOrNull { it.id == volume.id }
                val enabled = entry?.enabled == true
                val draft = targetDrafts[volume.id] ?: repository.defaultTargetFor(volume)
                val targetValid = repository.isValidTarget(draft)

                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = volume.label + if (volume.removable) "（可移动）" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = volume.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Switch(
                                checked = enabled,
                                enabled = volume.accessible && targetValid,
                                onCheckedChange = { checked -> scope.launch { persist(volume, checked) } },
                            )
                        }

                        OutlinedTextField(
                            value = draft,
                            onValueChange = { value -> targetDrafts = targetDrafts + (volume.id to value) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("沙箱内的挂载点") },
                            isError = !targetValid,
                            supportingText = {
                                Text(
                                    if (targetValid) {
                                        "这个卷会出现在沙箱里的这个路径。"
                                    } else {
                                        "必须是绝对路径、不能含 . 或 .., 也不能占用保留路径" +
                                            "(如 /skills、/proc)。"
                                    },
                                )
                            },
                            singleLine = true,
                        )

                        if (!volume.accessible) {
                            Text(
                                text = "当前读不到: 请授予存储权限, 或者这个卷已经卸载。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
