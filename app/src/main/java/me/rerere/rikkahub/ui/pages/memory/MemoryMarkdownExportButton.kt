package me.rerere.rikkahub.ui.pages.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType

@Composable
fun MemoryMarkdownExportButton(file: MemoryFile) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var pendingContent by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val content = pendingContent
        pendingContent = null
        if (uri != null && content != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                            ?: error("无法写入所选位置")
                    }
                    toaster.show(message = "Markdown 原文已导出：${content.toByteArray(Charsets.UTF_8).size} 字节", type = ToastType.Success)
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) { toaster.show(message = error.message ?: "导出失败", type = ToastType.Error)
                } finally { busy = false }
            }
        }
    }
    TextButton(enabled = !busy, onClick = {
        pendingContent = file.content
        launcher.launch(file.path.substringAfterLast('/'))
    }, modifier = Modifier.testTag("memory_export_markdown")) { Text("导出原文") }
}
