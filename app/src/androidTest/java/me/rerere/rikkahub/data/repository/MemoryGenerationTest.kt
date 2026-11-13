package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.model.MemoryDocumentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the generated Room DAO, not a mock or an in-memory implementation of its methods. */
@RunWith(AndroidJUnit4::class)
class MemoryGenerationTest {
    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()

    @Test
    fun concurrentRepositoriesAllowExactlyOneCASAndRejectABA() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            val repository = MemoryRepository(dao)
            val results = (1..24).map { writer ->
                async(Dispatchers.IO) {
                    MemoryRepository(db.memoryDao()).writeAtGeneration("test", "/profile.md", "writer $writer", 0)
                }
            }.awaitAll()
            assertEquals(1, results.count { it is MemoryWriteResult.Success })
            assertEquals(23, results.count { it is MemoryWriteResult.Conflict })
            val original = repository.snapshot("test", "/profile.md")
            assertEquals(1L, original.generation)
            assertTrue(repository.writeAtGeneration("test", "/profile.md", null, 1) is MemoryWriteResult.Success)
            assertTrue(repository.writeAtGeneration("test", "/profile.md", original.content, 2) is MemoryWriteResult.Success)
            assertTrue(repository.writeAtGeneration("test", "/profile.md", "stale", 1) is MemoryWriteResult.Conflict)
            assertEquals(original.copy(generation = 3), repository.snapshot("test", "/profile.md"))
        } finally {
            db.close()
        }
    }

    @Test
    fun directDAOBulkWritesCopyAndDeleteAreRevisionedAndRollbackTogether() = runBlocking {
        val db = database()
        try {
            val dao = db.memoryDao()
            val repository = MemoryRepository(dao)
            dao.upsertFiles(
                listOf(MemoryFileEntity("source", "/a.md", "a", 1), MemoryFileEntity("source", "/b.md", "b", 1))
            )
            dao.copyFiles("source", "target")
            assertEquals(listOf(1L, 1L), repository.snapshots("target").map { it.generation })
            dao.deleteFilesOfAssistant("target")
            assertEquals(
                listOf(MemoryDocumentSnapshot("/a.md", null, 2), MemoryDocumentSnapshot("/b.md", null, 2)),
                repository.snapshots("target"),
            )
            dao.copyFiles("source", "target")
            assertEquals(listOf(3L, 3L), repository.snapshots("target").map { it.generation })
            try {
                dao.writeTransaction {
                    dao.upsertFile(MemoryFileEntity("target", "/a.md", "uncommitted", 2))
                    throw IllegalStateException("rollback fixture")
                }
                throw AssertionError("Expected rollback")
            } catch (_: IllegalStateException) {
                assertEquals(MemoryDocumentSnapshot("/a.md", "a", 3), repository.snapshot("target", "/a.md"))
            }
            assertEquals(listOf(1L, 1L), repository.snapshots("source").map { it.generation })
        } finally {
            db.close()
        }
    }
}
