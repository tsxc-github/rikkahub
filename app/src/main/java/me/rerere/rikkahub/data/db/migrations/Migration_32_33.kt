package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/** Existing raw Markdown is preserved, including legacy oversized documents. */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_revision` (`assistant_id` TEXT NOT NULL, " +
                    "`path` TEXT NOT NULL, `generation` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`assistant_id`, `path`))"
            )
            db.execSQL(
                "INSERT INTO memory_revision (assistant_id, path, generation, deleted) " +
                    "SELECT assistant_id, path, 1, 0 FROM memory_file"
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
