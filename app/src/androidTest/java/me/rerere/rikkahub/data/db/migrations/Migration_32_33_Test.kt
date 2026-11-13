package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_32_33_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun backfillsEveryScopeWithoutChangingRawOrOversizedMarkdown() {
        val name = "memory-revision-migration"
        val legacy = "---\r\nunknown: preserved\r\n---\r\n- [stated] " + "旧".repeat(MemoryFile.MAX_BYTES)
        helper.createDatabase(name, 32).apply {
            execSQL(
                "INSERT INTO memory_file (assistant_id,path,content,updated_at) VALUES (?,?,?,?)",
                arrayOf<Any>("assistant", "/profile.md", legacy, 123L),
            )
            execSQL(
                "INSERT INTO memory_file (assistant_id,path,content,updated_at) VALUES (?,?,?,?)",
                arrayOf<Any>("__global__", "/profile.md", "global", 456L),
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 33, true, Migration_32_33).use { db ->
            db.query(
                "SELECT f.assistant_id,f.content,f.updated_at,r.generation,r.deleted " +
                    "FROM memory_file f JOIN memory_revision r " +
                    "ON f.assistant_id=r.assistant_id AND f.path=r.path ORDER BY f.assistant_id"
            ).use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToFirst()
                assertEquals("__global__", cursor.getString(0))
                assertEquals("global", cursor.getString(1))
                assertEquals(456L, cursor.getLong(2))
                assertEquals(1L, cursor.getLong(3))
                assertEquals(0, cursor.getInt(4))
                cursor.moveToNext()
                assertEquals(legacy, cursor.getString(1))
                assertEquals(123L, cursor.getLong(2))
                assertEquals(1L, cursor.getLong(3))
                assertEquals(0, cursor.getInt(4))
            }
        }
    }

    @Test
    fun emptyLegacyLibraryDoesNotInventDocumentsOrTombstones() {
        val name = "memory-revision-empty-migration"
        helper.createDatabase(name, 32).close()
        helper.runMigrationsAndValidate(name, 33, true, Migration_32_33).use { db ->
            db.query("SELECT COUNT(*) FROM memory_revision").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
