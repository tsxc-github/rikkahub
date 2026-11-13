package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity

/** Retained after deletion, so a delete/recreate cannot reuse an old revision. */
@Entity(tableName = "memory_revision", primaryKeys = ["assistant_id", "path"])
data class MemoryRevisionEntity(
    @ColumnInfo(name = "assistant_id") val assistantId: String,
    val path: String,
    val generation: Long,
    val deleted: Boolean,
)

/** Room projection used by the editor to retain the original display timestamp. */
data class MemoryFileWithGeneration(
    @Embedded val file: MemoryFileEntity,
    val generation: Long,
)
