package me.rerere.rikkahub.data.repository

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileWithGeneration
import me.rerere.rikkahub.data.db.entity.MemoryRevisionEntity
import me.rerere.rikkahub.data.model.MemoryDocumentSnapshot

/** Executes the production DAO mutations with rollback and a shared, reentrant transaction lock. */
internal class FakeMemoryDAO : MemoryDAO() {
    private data class State(
        val files: List<MemoryFileEntity> = emptyList(),
        val revisions: List<MemoryRevisionEntity> = emptyList(),
    )

    private class Transaction(val dao: FakeMemoryDAO, var state: State) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Transaction>
    }

    private val committed = MutableStateFlow(State())
    private val lock = Mutex()

    private suspend fun state(): State = coroutineContext[Transaction]?.takeIf { it.dao === this }?.state ?: committed.value

    private suspend fun <T> atomic(block: suspend () -> T): T {
        if (coroutineContext[Transaction]?.dao === this) return block()
        return lock.withLock {
            val transaction = Transaction(this, committed.value)
            withContext(transaction) {
                val result = block()
                committed.value = transaction.state
                result
            }
        }
    }

    override fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>> =
        committed.map { state -> state.files.filter { it.assistantId == assistantId }.sortedBy { it.path } }

    override fun getFilesWithGenerationFlow(assistantId: String): Flow<List<MemoryFileWithGeneration>> =
        committed.map { state ->
            state.files.filter { it.assistantId == assistantId }.sortedBy { it.path }.map { file ->
                MemoryFileWithGeneration(file, state.revisions.single { it.assistantId == assistantId && it.path == file.path }.generation)
            }
        }

    override suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity> =
        state().files.filter { it.assistantId == assistantId }.sortedBy { it.path }

    override suspend fun getFile(assistantId: String, path: String): MemoryFileEntity? =
        state().files.find { it.assistantId == assistantId && it.path == path }

    override suspend fun getRevision(assistantId: String, path: String): MemoryRevisionEntity? =
        state().revisions.find { it.assistantId == assistantId && it.path == path }

    private fun State.snapshots(assistantId: String): List<MemoryDocumentSnapshot> =
        revisions.filter { it.assistantId == assistantId }.sortedBy { it.path }.map { revision ->
            MemoryDocumentSnapshot(
                revision.path,
                files.find { it.assistantId == assistantId && it.path == revision.path }?.content,
                revision.generation,
            )
        }

    override suspend fun snapshots(assistantId: String): List<MemoryDocumentSnapshot> = state().snapshots(assistantId)

    override suspend fun snapshot(assistantId: String, path: String): MemoryDocumentSnapshot? {
        val state = state()
        val revision = state.revisions.find { it.assistantId == assistantId && it.path == path } ?: return null
        return MemoryDocumentSnapshot(
            path,
            state.files.find { it.assistantId == assistantId && it.path == path }?.content,
            revision.generation,
        )
    }

    override fun snapshotsFlow(assistantId: String): Flow<List<MemoryDocumentSnapshot>> =
        committed.map { it.snapshots(assistantId) }

    override suspend fun writeTransaction(block: suspend () -> MemoryWriteResult): MemoryWriteResult = atomic(block)

    override suspend fun upsertFile(file: MemoryFileEntity) = atomic { super.upsertFile(file) }

    override suspend fun upsertFiles(files: List<MemoryFileEntity>) = atomic { super.upsertFiles(files) }

    override suspend fun deleteFile(assistantId: String, path: String) = atomic { super.deleteFile(assistantId, path) }

    override suspend fun deleteFilesOfAssistant(assistantId: String) = atomic { super.deleteFilesOfAssistant(assistantId) }

    override suspend fun copyFiles(fromAssistantId: String, toAssistantId: String) =
        atomic { super.copyFiles(fromAssistantId, toAssistantId) }

    override suspend fun storeFile(file: MemoryFileEntity) {
        val transaction = checkNotNull(coroutineContext[Transaction])
        transaction.state = transaction.state.copy(
            files = transaction.state.files.filterNot { it.assistantId == file.assistantId && it.path == file.path } + file,
        )
    }

    override suspend fun storeRevision(revision: MemoryRevisionEntity) {
        val transaction = checkNotNull(coroutineContext[Transaction])
        transaction.state = transaction.state.copy(
            revisions = transaction.state.revisions.filterNot {
                it.assistantId == revision.assistantId && it.path == revision.path
            } + revision,
        )
    }

    override suspend fun removeFile(assistantId: String, path: String) {
        val transaction = checkNotNull(coroutineContext[Transaction])
        transaction.state = transaction.state.copy(
            files = transaction.state.files.filterNot { it.assistantId == assistantId && it.path == path },
        )
    }

    /** Models old migrations which predate the policy boundary, not a production bypass. */
    suspend fun seedLegacyFile(file: MemoryFileEntity) = atomic {
        storeFile(file)
        storeRevision(MemoryRevisionEntity(file.assistantId, file.path, 1, deleted = false))
    }
}
