package me.rerere.rikkahub.data.sync.memory

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read through the provider, never turn an SAF URI into a guessed filesystem path. */
object MemoryArchiveSaf {
    suspend fun readDirectory(resolver: ContentResolver, tree: Uri): List<MemoryArchiveDocument> = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, ByteArray>()
        val ids = HashSet<String>()
        val paths = HashSet<String>()
        var bytes = 0L
        var members = 0
        fun walk(id: String, prefix: String, depth: Int) {
            require(depth <= 32) { "目录层数超过 32" }
            require(ids.add(id)) { "目录包含循环或重复文档" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    require(++members <= MemoryArchiveFormat.MAX_DOCUMENTS * 2 + 1) { "目录成员过多" }
                    val childId = cursor.getString(0) ?: error("缺少文档标识")
                    val name = cursor.getString(1) ?: error("缺少文件名")
                    require(name.isNotBlank() && name == name.trim() && !name.startsWith('.') &&
                        name.none { it.isISOControl() || it == '/' || it == '\\' || it == ':' }) { "非法目录成员：$name" }
                    val path = prefix + name
                    require(paths.add(MemoryArchiveFormat.collisionKey(path))) { "重复目录路径：$path" }
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(childId, "$path/", depth + 1)
                    } else {
                        require(ids.add(childId)) { "重复文档或链接" }
                        require(path == "manifest.json" || path.endsWith(".md")) { "目录包含非 Markdown 文件：$path" }
                        val limit = if (path == "manifest.json") MemoryArchiveFormat.MAX_MANIFEST_BYTES else MemoryArchiveFormat.MAX_BYTES - bytes.toInt()
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                        val content = resolver.openInputStream(uri)?.use { MemoryArchiveFormat.readBounded(it, limit) }
                            ?: error("无法读取：$path")
                        files[path] = content
                        if (path != "manifest.json") bytes += content.size
                    }
                }
            } ?: error("无法完整枚举目录；没有导入任何记忆")
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        if (files.containsKey("manifest.json")) MemoryArchiveFormat.readManifestFiles(files) else {
            files.map { (path, content) -> MemoryArchiveDocument(
                MemoryArchiveFormat.strictPath("/$path"), MemoryArchiveFormat.strictUtf8(content)
            ) }.also(MemoryArchiveFormat::validateDocuments)
        }
    }
}
