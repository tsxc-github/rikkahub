package me.rerere.rikkahub.data.sync.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class MemoryArchiveDocument(val path: String, val content: String)
enum class MemoryImportKind { NEW, IDENTICAL, CHANGED, REJECTED }
data class MemoryImportPreview(
    val path: String,
    val content: String,
    val currentContent: String?,
    val expectedGeneration: Long,
    val kind: MemoryImportKind,
    val error: String? = null,
)
enum class MemoryImportStatus { WRITTEN, UNCHANGED, SKIPPED, CONFLICT, FAILED }
data class MemoryImportResult(val path: String, val status: MemoryImportStatus, val error: String? = null)

@Serializable
data class MemoryArchiveEntry(val path: String, val file: String, val sha256: String, val bytes: Long)
@Serializable
data class MemoryArchiveManifest(
    val formatVersion: Int = 1,
    val exportId: String,
    val exportedAt: Long,
    val sourceScopeKind: String,
    val entries: List<MemoryArchiveEntry>,
)

/** No extraction: every name, byte and manifest member is checked before an import can start. */
object MemoryArchiveFormat {
    const val MAX_DOCUMENTS = 2000
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_ZIP_BYTES = MAX_BYTES + 4 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = false }

    fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun strictPath(path: String): String {
        require(path.startsWith('/') && MemoryFile.normalizePath(path) == path) { "非法记忆路径：$path" }
        require(!path.contains(':')) { "非法记忆路径：$path" }
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(path))
        return path
    }

    internal fun collisionKey(path: String) = Normalizer.normalize(
        path.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFC
    )
    private fun validatePaths(documents: List<MemoryArchiveDocument>) {
        val names = HashSet<String>()
        documents.forEach {
            strictPath(it.path)
            require(names.add(collisionKey(it.path))) { "重复记忆路径：${it.path}" }
        }
    }

    fun validateDocuments(documents: List<MemoryArchiveDocument>) {
        require(documents.size <= MAX_DOCUMENTS) { "最多导入 2000 篇" }
        validatePaths(documents)
        var bytes = 0L
        documents.forEach {
            bytes += it.content.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_BYTES) { "记忆总量超过 64 MiB" }
        }
    }

    fun readMarkdown(input: InputStream, path: String): List<MemoryArchiveDocument> =
        listOf(MemoryArchiveDocument(strictPath(path), strictUtf8(readBounded(input, MAX_BYTES))))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun manifest(documents: List<MemoryArchiveDocument>, scopeKind: String): MemoryArchiveManifest {
        // Import budgets must not silently omit legacy documents from an export.
        validatePaths(documents)
        require(scopeKind == "global" || scopeKind == "assistant")
        return MemoryArchiveManifest(
            exportId = UUID.randomUUID().toString(), exportedAt = System.currentTimeMillis(),
            sourceScopeKind = scopeKind,
            entries = documents.map {
                val bytes = it.content.toByteArray(Charsets.UTF_8)
                MemoryArchiveEntry(it.path, "memories${it.path}", sha256(bytes), bytes.size.toLong())
            },
        )
    }

    fun writeZip(output: OutputStream, documents: List<MemoryArchiveDocument>, scopeKind: String) {
        val manifest = manifest(documents, scopeKind)
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            documents.forEach {
                zip.putNextEntry(ZipEntry("memories${it.path}"))
                zip.write(it.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun readZip(input: InputStream): List<MemoryArchiveDocument> {
        val bytes = readBounded(input, MAX_ZIP_BYTES)
        val centralNames = checkCentralDirectory(bytes)
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            var index = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(index < centralNames.size && entry.name == centralNames[index++]) { "ZIP 本地与中央目录不一致" }
                require(!files.containsKey(entry.name)) { "ZIP 重复成员：${entry.name}" }
                val limit = if (entry.name == "manifest.json") MAX_MANIFEST_BYTES else MAX_BYTES - total.toInt()
                val content = readBounded(zip, limit)
                if (entry.isDirectory) {
                    require(content.isEmpty()) { "ZIP 目录包含正文" }
                } else {
                    files[entry.name] = content
                    if (entry.name != "manifest.json") total += content.size
                }
                require(total <= MAX_BYTES) { "解压总量超过 64 MiB" }
                zip.closeEntry()
            }
            require(index == centralNames.size) { "ZIP 不完整" }
        }
        return readManifestFiles(files)
    }

    fun readManifestFiles(source: Map<String, ByteArray>): List<MemoryArchiveDocument> {
        val files = source.toMutableMap()
        val manifest = json.decodeFromString<MemoryArchiveManifest>(
            strictUtf8(files.remove("manifest.json") ?: error("缺少 manifest.json"))
        )
        require(manifest.formatVersion == 1) { "不支持的包版本：${manifest.formatVersion}" }
        require(UUID.fromString(manifest.exportId).toString() == manifest.exportId) { "非法快照 UUID" }
        require(manifest.sourceScopeKind in setOf("global", "assistant")) { "非法来源类型" }
        require(manifest.entries.size <= MAX_DOCUMENTS) { "最多导入 2000 篇" }
        val documents = manifest.entries.map { entry ->
            strictPath(entry.path)
            require(entry.file == "memories${entry.path}") { "manifest 路径映射不一致" }
            val content = files.remove(entry.file) ?: error("缺少或重复文件：${entry.file}")
            require(entry.bytes == content.size.toLong() && entry.sha256 == sha256(content)) { "文件长度或 SHA-256 不匹配：${entry.path}" }
            MemoryArchiveDocument(entry.path, strictUtf8(content))
        }
        require(files.isEmpty()) { "ZIP 包含 manifest 未声明的文件" }
        validateDocuments(documents)
        return documents
    }

    /** Inspect UNIX external attributes; ZipInputStream alone silently follows symlink payloads. */
    private fun checkCentralDirectory(bytes: ByteArray): List<String> {
        fun u16(p: Int): Int {
            require(p >= 0 && p + 2 <= bytes.size) { "ZIP 目录越界" }
            return (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8)
        }
        fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        val eocd = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: error("ZIP 缺少完整中央目录")
        require(u16(eocd + 4) == 0 && u16(eocd + 6) == 0 && u16(eocd + 8) == u16(eocd + 10)) { "不支持多卷 ZIP" }
        val count = u16(eocd + 10)
        require(count <= MAX_DOCUMENTS * 2 + 1) { "ZIP 成员过多" }
        val start = u32(eocd + 16)
        require(start + u32(eocd + 12) == eocd.toLong()) { "ZIP 目录长度异常或 ZIP64 不受支持" }
        var position = start.toInt()
        val names = ArrayList<String>()
        val unique = HashSet<String>()
        var previousLocalOffset = -1L
        repeat(count) {
            require(u32(position) == 0x02014b50L) { "ZIP 中央目录损坏" }
            require(u16(position + 8) and 1 == 0) { "不支持加密 ZIP" }
            val mode = ((u32(position + 38) shr 16) and 0xf000).toInt()
            require(mode == 0 || mode == 0x8000 || mode == 0x4000) { "禁止 ZIP 软链接或特殊文件" }
            val length = u16(position + 28)
            val end = position.toLong() + 46 + length
            require(end <= eocd) { "ZIP 文件名越界" }
            val name = strictUtf8(bytes.copyOfRange(position + 46, end.toInt()))
            require(unique.add(collisionKey(name))) { "ZIP 重复成员：$name" }
            if (name != "manifest.json") {
                require(name.startsWith("memories/")) { "ZIP 只允许 memories/ 与 manifest.json" }
                if (name.endsWith('/')) {
                    val segments = name.removeSuffix("/").split('/')
                    require(segments.all { it.isNotBlank() && !it.startsWith('.') && it == it.trim() && it.none { c -> c.isISOControl() || c == '\\' || c == ':' } }) { "非法 ZIP 目录" }
                } else strictPath(name.removePrefix("memories"))
            }
            val localOffset = u32(position + 42)
            require(localOffset > previousLocalOffset && localOffset < start) { "ZIP 成员重叠或顺序异常" }
            require(u32(localOffset.toInt()) == 0x04034b50L) { "ZIP 本地目录损坏" }
            if (previousLocalOffset == -1L) require(localOffset == 0L) { "不支持带前缀的 ZIP" }
            previousLocalOffset = localOffset
            names += name
            position += 46 + length + u16(position + 30) + u16(position + 32)
            require(position <= eocd) { "ZIP 目录越界" }
        }
        require(position == eocd) { "ZIP 目录包含额外数据" }
        return names
    }

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            total += size
            require(total <= limit) { "文件超过导入预算（$limit 字节）" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }

    /** A fresh directory only; a failed export never publishes a complete-looking snapshot. */
    fun writeSnapshot(root: File, documents: List<MemoryArchiveDocument>, scopeKind: String): File {
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "本地工作区目录不可用" }
        val manifest = manifest(documents, scopeKind)
        val directory = File(root, "memory-export-${manifest.exportedAt}-${manifest.exportId}")
        require(directory.mkdir()) { "无法创建新快照目录" }
        try {
            documents.forEach {
                val target = File(directory, "memories${it.path}")
                require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { out ->
                    out.write(it.content.toByteArray(Charsets.UTF_8))
                }
            }
            File(directory, "manifest.json").writeText(json.encodeToString(manifest), Charsets.UTF_8)
            return directory
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }
}

class MemoryArchive(private val repository: MemoryRepository) {
    suspend fun documents(memoryId: String): List<MemoryArchiveDocument> = withContext(Dispatchers.IO) {
        repository.snapshots(memoryId).mapNotNull { document ->
            document.content?.let { MemoryArchiveDocument(document.path, it) }
        }
    }

    suspend fun preview(memoryId: String, documents: List<MemoryArchiveDocument>): List<MemoryImportPreview> = withContext(Dispatchers.IO) {
        MemoryArchiveFormat.validateDocuments(documents)
        val current = repository.snapshots(memoryId).associateBy { it.path }
        documents.map { document ->
            val previous = current[document.path]
            val size = document.content.toByteArray(Charsets.UTF_8).size
            val previousSize = previous?.content?.toByteArray(Charsets.UTF_8)?.size ?: 0
            val kind = when {
                size > MemoryFile.MAX_BYTES && size > previousSize -> MemoryImportKind.REJECTED
                previous?.content == document.content -> MemoryImportKind.IDENTICAL
                previous?.content == null -> MemoryImportKind.NEW
                else -> MemoryImportKind.CHANGED
            }
            MemoryImportPreview(document.path, document.content, previous?.content, previous?.generation ?: 0,
                kind, if (kind == MemoryImportKind.REJECTED) "新增或增大的记忆不得超过 48 KiB" else null)
        }
    }

    suspend fun apply(memoryId: String, preview: List<MemoryImportPreview>, overwritePaths: Set<String> = emptySet()): List<MemoryImportResult> = withContext(Dispatchers.IO) {
        preview.map { item ->
            if (item.kind == MemoryImportKind.REJECTED) return@map MemoryImportResult(item.path, MemoryImportStatus.FAILED, item.error)
            if (item.kind == MemoryImportKind.CHANGED && item.path !in overwritePaths) return@map MemoryImportResult(item.path, MemoryImportStatus.SKIPPED, "未确认覆盖")
            try {
                when (val result = repository.writeAtGeneration(memoryId, item.path, item.content, item.expectedGeneration)) {
                    is MemoryWriteResult.Success -> MemoryImportResult(item.path, if (item.kind == MemoryImportKind.IDENTICAL) MemoryImportStatus.UNCHANGED else MemoryImportStatus.WRITTEN)
                    is MemoryWriteResult.Conflict -> MemoryImportResult(item.path, MemoryImportStatus.CONFLICT, "预览后此篇已变化；未覆盖，请重新预览")
                    is MemoryWriteResult.Rejected -> MemoryImportResult(item.path, MemoryImportStatus.FAILED, result.reason)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                MemoryImportResult(item.path, MemoryImportStatus.FAILED, error.message ?: "写入失败")
            }
        }
    }
}
