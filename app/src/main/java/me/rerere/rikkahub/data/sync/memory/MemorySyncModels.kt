package me.rerere.rikkahub.data.sync.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemorySyncBinding(
    val memoryId: String,
    val workspaceId: String,
    val remoteRoot: String,
    val spaceId: String,
    val enabled: Boolean = true,
    val endpointIdentity: String = "",
)

@Serializable
data class MemorySyncStatus(
    val running: Boolean = false,
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val error: String? = null,
    val conflicts: Int = 0,
)

@Serializable
data class MemorySyncConflict(
    val path: String,
    val baseContent: String?,
    val localContent: String?,
    val remoteContent: String?,
    val localGeneration: Long,
    val remoteToken: String,
)

@Serializable
internal data class MemoryBaseline(
    val content: String?,
    val localGeneration: Long,
    val remoteToken: String,
)

@Serializable
internal data class MemoryPending(
    val path: String,
    val operationId: String,
    val expectedToken: String,
    val content: String?,
    val localGeneration: Long,
)

@Serializable
internal data class MemoryLibraryState(
    val binding: MemorySyncBinding,
    val baselines: Map<String, MemoryBaseline> = emptyMap(),
    val pending: Map<String, MemoryPending> = emptyMap(),
    val conflicts: Map<String, MemorySyncConflict> = emptyMap(),
    val status: MemorySyncStatus = MemorySyncStatus(),
)

@Serializable
internal data class MemoryPersistentState(
    val formatVersion: Int = 1,
    val libraries: Map<String, MemoryLibraryState> = emptyMap(),
)

@Serializable
data class MemoryRemoteDocument(val path: String, val token: String, val content: String? = null)

@Serializable
data class MemoryRpcRequest(
    val protocol: Int = 1,
    val op: String,
    val spaceId: String,
    val path: String? = null,
    val expectedToken: String? = null,
    val content: String? = null,
    val operationId: String? = null,
)

@Serializable
data class MemoryRpcError(val code: String, val message: String)

@Serializable
data class MemoryRpcResponse(
    val protocol: Int = 1,
    val ok: Boolean,
    val documents: List<MemoryRemoteDocument>? = null,
    val document: MemoryRemoteDocument? = null,
    val found: Boolean? = null,
    val error: MemoryRpcError? = null,
)

fun interface MemorySyncRemote {
    suspend fun request(binding: MemorySyncBinding, request: MemoryRpcRequest): MemoryRpcResponse
}

class MemorySyncException(val code: String, message: String) : java.io.IOException(message)
