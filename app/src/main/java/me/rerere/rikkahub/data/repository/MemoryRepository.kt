package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryDocumentSnapshot
import me.rerere.rikkahub.data.model.MemoryFile

sealed interface MemoryWriteResult {
    /** [file] 为 null 表示文件已被删除 */
    data class Success(val file: MemoryFile?) : MemoryWriteResult

    /** 版本对不上；[current] 是最新内容，为 null 表示文件不存在 */
    data class Conflict(val current: MemoryFile?) : MemoryWriteResult

    data class Rejected(val reason: String) : MemoryWriteResult
}

/**
 * 记忆库：每个助手（或全局）一组 Markdown 文件。
 *
 * 模型工具保留内容 hash 乐观锁；同步与界面编辑使用单调 generation，防止删除重建 ABA。
 * 所有读取、版本校验、内容和 revision 更新均在同一个 Room 事务中完成。
 */
class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val NEW_FILE_VERSION = "new"
    }

    fun getFilesFlow(assistantId: String): Flow<List<MemoryFile>> =
        memoryDAO.getFilesOfAssistantFlow(assistantId).map { entities -> entities.map { it.toMemoryFile() } }

    suspend fun getFiles(assistantId: String): List<MemoryFile> =
        memoryDAO.getFilesOfAssistant(assistantId).map { it.toMemoryFile() }

    suspend fun getFile(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
    }

    fun snapshotsFlow(memoryId: String): Flow<List<MemoryDocumentSnapshot>> = memoryDAO.snapshotsFlow(memoryId)

    fun getFilesWithGenerationFlow(memoryId: String): Flow<List<Pair<MemoryFile, Long>>> =
        memoryDAO.getFilesWithGenerationFlow(memoryId).map { rows ->
            rows.map { it.file.toMemoryFile() to it.generation }
        }

    suspend fun snapshots(memoryId: String): List<MemoryDocumentSnapshot> = memoryDAO.snapshots(memoryId)

    suspend fun snapshot(memoryId: String, path: String): MemoryDocumentSnapshot {
        val normalized = requireNotNull(MemoryFile.normalizePath(path)) { "Invalid memory path." }
        return memoryDAO.snapshot(memoryId, normalized) ?: MemoryDocumentSnapshot(normalized, null, 0)
    }

    suspend fun writeAtGeneration(
        memoryId: String,
        path: String,
        content: String?,
        expectedGeneration: Long,
    ): MemoryWriteResult = memoryDAO.writeTransaction {
        val normalized = MemoryFile.normalizePath(path) ?: return@writeTransaction invalidPath(path)
        if (expectedGeneration < 0) return@writeTransaction MemoryWriteResult.Rejected("Invalid generation.")
        val current = memoryDAO.getFile(memoryId, normalized)
        val generation = memoryDAO.getRevision(memoryId, normalized)?.generation ?: 0L
        if (generation != expectedGeneration) {
            return@writeTransaction MemoryWriteResult.Conflict(current?.toMemoryFile())
        }
        if (content == null) {
            memoryDAO.deleteFile(memoryId, normalized)
            MemoryWriteResult.Success(null)
        } else {
            persist(memoryId, normalized, content, current?.toMemoryFile())
        }
    }

    /** 新建或整体覆盖 */
    suspend fun writeFile(
        assistantId: String,
        path: String,
        content: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = update(assistantId, path, ifVersion, allowCreate = true) { Edit.Content(content) }

    /** 把唯一匹配的 [oldStr] 换成 [newStr]，[newStr] 为空即删除 */
    suspend fun replaceInFile(
        assistantId: String,
        path: String,
        oldStr: String,
        newStr: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        if (oldStr.isEmpty()) return@update Edit.Reject("old_str must not be empty.")
        val index = current.indexOf(oldStr)
        when {
            index < 0 -> Edit.Reject("old_str was not found in $path.")
            current.indexOf(oldStr, index + 1) >= 0 ->
                Edit.Reject(
                    "old_str matches more than one place in $path; include more surrounding text to make it unique."
                )

            else -> Edit.Content(current.replaceAt(index, oldStr.length, newStr))
        }
    }

    /** 在文件末尾追加一行 */
    suspend fun appendToFile(
        assistantId: String,
        path: String,
        line: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        Edit.Content(current + separator + line.trimEnd('\n', '\r') + "\n")
    }

    suspend fun deleteFile(
        assistantId: String,
        path: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = memoryDAO.writeTransaction {
        val normalized = MemoryFile.normalizePath(path) ?: return@writeTransaction invalidPath(path)
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        if (current == null || (ifVersion != null && ifVersion != current.version)) {
            return@writeTransaction MemoryWriteResult.Conflict(current)
        }
        memoryDAO.deleteFile(assistantId, normalized)
        MemoryWriteResult.Success(null)
    }

    suspend fun deleteFilesOfAssistant(assistantId: String) {
        memoryDAO.deleteFilesOfAssistant(assistantId)
    }

    suspend fun copyFiles(fromAssistantId: String, toAssistantId: String) {
        memoryDAO.copyFiles(fromAssistantId, toAssistantId)
    }

    private sealed interface Edit {
        data class Content(val content: String) : Edit
        data class Reject(val reason: String) : Edit
    }

    private suspend fun update(
        assistantId: String,
        path: String,
        ifVersion: String?,
        allowCreate: Boolean = false,
        edit: (current: String) -> Edit,
    ): MemoryWriteResult = memoryDAO.writeTransaction {
        val normalized = MemoryFile.normalizePath(path) ?: return@writeTransaction invalidPath(path)
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        val versionMatches = when (ifVersion) {
            null -> current != null || allowCreate
            NEW_FILE_VERSION -> current == null && allowCreate
            else -> current?.version == ifVersion
        }
        if (!versionMatches) return@writeTransaction MemoryWriteResult.Conflict(current)

        val content = when (val result = edit(current?.content.orEmpty())) {
            is Edit.Reject -> return@writeTransaction MemoryWriteResult.Rejected(result.reason)
            is Edit.Content -> result.content
        }
        persist(assistantId, normalized, content, current)
    }

    private suspend fun persist(
        assistantId: String,
        normalized: String,
        content: String,
        current: MemoryFile?,
    ): MemoryWriteResult {
        if (content == current?.content) return MemoryWriteResult.Success(current)
        val entity = MemoryFileEntity(
            assistantId = assistantId,
            path = normalized,
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        try {
            memoryDAO.upsertFile(entity)
        } catch (exception: IllegalArgumentException) {
            return MemoryWriteResult.Rejected(exception.message ?: "Invalid memory content.")
        }
        return MemoryWriteResult.Success(entity.toMemoryFile())
    }

    private fun invalidPath(path: String) = MemoryWriteResult.Rejected(
        "Invalid path \"$path\". Use an absolute path ending in .md, e.g. /topics/food.md."
    )
}

/** 助手读写的记忆库：开启全局记忆后所有助手共用一份 */
val Assistant.memoryId: String
    get() = if (useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else id.toString()

private fun MemoryFileEntity.toMemoryFile() = MemoryFile(path = path, content = content, updatedAt = updatedAt)

// 删掉的是完整一行时连换行一起去掉，不留空行
private fun String.replaceAt(index: Int, length: Int, replacement: String): String {
    var start = index
    var end = index + length
    if (replacement.isEmpty()) {
        val atLineStart = start == 0 || this[start - 1] == '\n'
        val atLineEnd = end == this.length || this[end] == '\n'
        if (atLineStart && atLineEnd) {
            if (end < this.length) {
                end++
            } else if (start > 0) {
                start--
            }
        }
    }
    return substring(0, start) + replacement + substring(end)
}
