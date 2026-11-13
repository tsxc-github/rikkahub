package me.rerere.rikkahub.data.model

/** A coherent document revision. Null content is absence; generation zero means never created. */
data class MemoryDocumentSnapshot(
    val path: String,
    val content: String?,
    val generation: Long,
)
