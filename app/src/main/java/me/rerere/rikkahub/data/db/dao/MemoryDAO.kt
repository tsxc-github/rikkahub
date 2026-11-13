package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.ai.tools.findForbiddenMemoryContent
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileWithGeneration
import me.rerere.rikkahub.data.db.entity.MemoryRevisionEntity
import me.rerere.rikkahub.data.model.MemoryDocumentSnapshot
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryWriteResult

@Dao
abstract class MemoryDAO {
    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId ORDER BY path")
    abstract fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT f.*, r.generation FROM memory_file f JOIN memory_revision r ON f.assistant_id = r.assistant_id AND f.path = r.path WHERE f.assistant_id = :assistantId ORDER BY f.path")
    abstract fun getFilesWithGenerationFlow(assistantId: String): Flow<List<MemoryFileWithGeneration>>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId ORDER BY path")
    abstract suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    abstract suspend fun getFile(assistantId: String, path: String): MemoryFileEntity?

    @Query("SELECT * FROM memory_revision WHERE assistant_id = :assistantId AND path = :path")
    abstract suspend fun getRevision(assistantId: String, path: String): MemoryRevisionEntity?

    @Query("SELECT r.path, f.content, r.generation FROM memory_revision r LEFT JOIN memory_file f ON f.assistant_id = r.assistant_id AND f.path = r.path WHERE r.assistant_id = :assistantId ORDER BY r.path")
    abstract suspend fun snapshots(assistantId: String): List<MemoryDocumentSnapshot>

    @Query("SELECT r.path, f.content, r.generation FROM memory_revision r LEFT JOIN memory_file f ON f.assistant_id = r.assistant_id AND f.path = r.path WHERE r.assistant_id = :assistantId AND r.path = :path")
    abstract suspend fun snapshot(assistantId: String, path: String): MemoryDocumentSnapshot?

    @Query("SELECT r.path, f.content, r.generation FROM memory_revision r LEFT JOIN memory_file f ON f.assistant_id = r.assistant_id AND f.path = r.path WHERE r.assistant_id = :assistantId ORDER BY r.path")
    abstract fun snapshotsFlow(assistantId: String): Flow<List<MemoryDocumentSnapshot>>

    /** Room serializes this whole read/check/write across repository instances. */
    @Transaction
    open suspend fun writeTransaction(block: suspend () -> MemoryWriteResult): MemoryWriteResult = block()

    @Upsert
    protected abstract suspend fun storeFile(file: MemoryFileEntity)

    @Upsert
    protected abstract suspend fun storeRevision(revision: MemoryRevisionEntity)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    protected abstract suspend fun removeFile(assistantId: String, path: String)

    /** All DAO callers, including bulk operations, use the same validation and revisions. */
    @Transaction
    open suspend fun upsertFile(file: MemoryFileEntity) {
        val current = getFile(file.assistantId, file.path)
        requireValidContent(file, current)
        if (current?.content == file.content) return
        val generation = nextGeneration(file.assistantId, file.path)
        storeFile(file)
        storeRevision(MemoryRevisionEntity(file.assistantId, file.path, generation, deleted = false))
    }

    @Transaction
    open suspend fun upsertFiles(files: List<MemoryFileEntity>) {
        files.forEach { upsertFile(it) }
    }

    @Transaction
    open suspend fun deleteFile(assistantId: String, path: String) {
        if (getFile(assistantId, path) == null) return
        val generation = nextGeneration(assistantId, path)
        removeFile(assistantId, path)
        storeRevision(MemoryRevisionEntity(assistantId, path, generation, deleted = true))
    }

    @Transaction
    open suspend fun deleteFilesOfAssistant(assistantId: String) {
        getFilesOfAssistant(assistantId).forEach { deleteFile(assistantId, it.path) }
    }

    @Transaction
    open suspend fun copyFiles(fromAssistantId: String, toAssistantId: String) {
        upsertFiles(getFilesOfAssistant(fromAssistantId).map { it.copy(assistantId = toAssistantId) })
    }

    private suspend fun nextGeneration(assistantId: String, path: String): Long {
        val generation = getRevision(assistantId, path)?.generation ?: 0L
        check(generation < Long.MAX_VALUE) { "Memory generation exhausted." }
        return generation + 1
    }

    private fun requireValidContent(file: MemoryFileEntity, current: MemoryFileEntity?) {
        require(MemoryFile.normalizePath(file.path) == file.path) { "Invalid memory path." }
        memoryContentRejection(file.path, file.content, current?.content)?.let { throw IllegalArgumentException(it) }
    }
}

/** Applied to the complete resulting document, including replacements and appends. */
private fun memoryContentRejection(path: String, content: String, previous: String?): String? {
    // An exact no-op must remain possible for legacy documents without rewriting them.
    if (content == previous) return null
    findForbiddenMemoryContent(content)?.let { return "Memory cannot contain $it." }
    val size = content.toByteArray(Charsets.UTF_8).size
    if (size > MemoryFile.MAX_BYTES && size > (previous?.toByteArray(Charsets.UTF_8)?.size ?: 0)) {
        return "$path would exceed the ${MemoryFile.MAX_BYTES / 1024}KB limit. " +
            "Merge duplicates and drop outdated details instead of adding more."
    }
    return null
}
