package me.rerere.rikkahub.data.sync.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.MemoryDocumentSnapshot
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** All network work is outside Room transactions; both sides use conditional commits. */
internal class MemorySyncEngine(
    private val repository: MemoryRepository,
    private val store: MemorySyncStore,
    private val remote: MemorySyncRemote,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(id: String) = locks.computeIfAbsent(id) { Mutex() }

    suspend fun bind(binding: MemorySyncBinding, createSpace: Boolean) = lock(binding.memoryId).withLock {
        require(binding.memoryId.isNotBlank() && binding.workspaceId.isNotBlank())
        require(UUID.fromString(binding.spaceId).toString() == binding.spaceId) { "空间必须是标准 UUID" }
        require(binding.remoteRoot.startsWith('/') && binding.remoteRoot != "/" &&
            binding.remoteRoot.none { it.isISOControl() } &&
            binding.remoteRoot.split('/').none { it == "." || it == ".." }) { "请指定独立的远端绝对目录" }
        val current = store.snapshot()
        require(current.libraries.values.none {
            it.binding.memoryId != binding.memoryId &&
                (it.binding.workspaceId == binding.workspaceId ||
                    (binding.endpointIdentity.isNotEmpty() && it.binding.endpointIdentity == binding.endpointIdentity)) &&
                it.binding.remoteRoot == binding.remoteRoot && it.binding.spaceId == binding.spaceId
        }) { "该远端空间已绑定其他记忆库" }
        require(current.libraries[binding.memoryId] == null) { "请先解除原绑定，再连接新空间" }
        rpc(binding, MemoryRpcRequest(op = if (createSpace) "create" else "list", spaceId = binding.spaceId))
        store.update { state ->
            require(state.libraries[binding.memoryId] == null) { "记忆库已绑定" }
            require(state.libraries.values.none {
                (it.binding.workspaceId == binding.workspaceId ||
                    (binding.endpointIdentity.isNotEmpty() && it.binding.endpointIdentity == binding.endpointIdentity)) &&
                    it.binding.remoteRoot == binding.remoteRoot && it.binding.spaceId == binding.spaceId
            }) { "该远端空间已绑定其他记忆库" }
            state.copy(libraries = state.libraries + (binding.memoryId to MemoryLibraryState(binding)))
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = lock(id).withLock {
        updateLibrary(id) { it.copy(binding = it.binding.copy(enabled = enabled), status = it.status.copy(error = null)) }
    }

    suspend fun unbind(id: String) = lock(id).withLock {
        store.update { it.copy(libraries = it.libraries - id) }
    }

    suspend fun sync(id: String, force: Boolean = false) = lock(id).withLock {
        var lib = library(id)
        if (!force && !lib.binding.enabled) return@withLock
        updateLibrary(id) { it.copy(status = it.status.copy(running = true, lastAttempt = System.currentTimeMillis(), error = null)) }
        try {
            // A lost response must be resolved before comparing heads or issuing a new operation.
            for (pending in lib.pending.values) resumePending(id, pending)
            lib = library(id)
            val response = rpc(lib.binding, MemoryRpcRequest(op = "list", spaceId = lib.binding.spaceId))
            val documents = requireNotNull(response.documents) { "远端未返回完整文档列表" }
            require(documents.count { it.content != null } <= 2000) { "远端活动文档数量超限" }
            val folded = HashSet<String>()
            var bytes = 0L
            for (doc in documents) {
                require(MemoryFile.normalizePath(doc.path) == doc.path && doc.token.toLongOrNull()?.let { it > 0 } == true) {
                    "远端文档路径或版本无效"
                }
                require(folded.add(doc.path.lowercase(Locale.ROOT))) { "远端包含重复路径" }
                bytes += doc.content?.toByteArray(Charsets.UTF_8)?.size ?: 0
                require(bytes <= 64L * 1024 * 1024) { "远端记忆总量超限" }
            }
            val heads = documents.associateBy { it.path }
            val locals = repository.snapshots(id).associateBy { it.path }
            val paths = (heads.keys + locals.keys + lib.baselines.keys + lib.conflicts.keys).sorted()
            for (path in paths) {
                val local = locals[path] ?: MemoryDocumentSnapshot(path, null, 0)
                val remoteDoc = heads[path] ?: MemoryRemoteDocument(path, "0", null)
                val base = library(id).baselines[path]
                require(base == null || base.remoteToken == "0" || heads.containsKey(path)) {
                    "远端缺少已知路径的删除记录；同步已停止，未传播删除"
                }
                val localChanged = base?.let { local.generation != it.localGeneration || local.content != it.content }
                    ?: (local.generation != 0L || local.content != null)
                val remoteChanged = base?.let { remoteDoc.token != it.remoteToken || remoteDoc.content != it.content }
                    ?: (remoteDoc.token != "0" || remoteDoc.content != null)
                when {
                    local.content == remoteDoc.content -> acknowledge(id, path, local.content, local.generation, remoteDoc.token)
                    !localChanged && !remoteChanged -> Unit
                    localChanged && remoteChanged -> conflict(id, local, remoteDoc, base?.content)
                    localChanged -> push(id, local, remoteDoc.token)
                    else -> pull(id, local, remoteDoc)
                }
            }
            updateLibrary(id) { it.copy(status = it.status.copy(
                running = false, lastSuccess = if (it.conflicts.isEmpty()) System.currentTimeMillis() else it.status.lastSuccess,
                conflicts = it.conflicts.size,
            )) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateLibrary(id) { it.copy(status = it.status.copy(running = false)) } }
            throw e
        } catch (e: Exception) {
            updateLibrary(id) { it.copy(
                binding = if (e is MemorySyncException && e.code in setOf("permission", "unsupported", "drift", "invalid", "connection_rejected"))
                    it.binding.copy(enabled = false) else it.binding,
                status = it.status.copy(running = false, error = e.message ?: e.javaClass.simpleName, conflicts = it.conflicts.size),
            ) }
            throw e
        }
    }

    suspend fun resolve(id: String, path: String, content: String?, expectedLocalGeneration: Long, expectedRemoteToken: String) = lock(id).withLock {
        val lib = library(id)
        val conflict = requireNotNull(lib.conflicts[path]) { "冲突已更新，请刷新" }
        require(conflict.localGeneration == expectedLocalGeneration && conflict.remoteToken == expectedRemoteToken) {
            "显示的冲突已过期，请刷新后重新选择"
        }
        val current = repository.snapshot(id, path)
        require(current.generation == conflict.localGeneration) { "App 内容已改变，请重新同步后解决冲突" }
        val docs = requireNotNull(rpc(lib.binding, MemoryRpcRequest(op = "list", spaceId = lib.binding.spaceId)).documents)
        val remoteDoc = docs.singleOrNull { it.path == path } ?: MemoryRemoteDocument(path, "0", null)
        require(remoteDoc.token == conflict.remoteToken) { "远端内容已改变，请重新同步后解决冲突" }
        // First commit the user's choice locally. If the remote changes meanwhile the pending CAS
        // will fail and retain a new conflict; never overwrite the new remote head.
        when (val result = repository.writeAtGeneration(id, path, content, current.generation)) {
            is MemoryWriteResult.Success -> {
                val accepted = repository.snapshot(id, path)
                if (accepted.content != content) error("App 内容再次改变，请重新同步")
                push(id, accepted, remoteDoc.token)
            }
            is MemoryWriteResult.Conflict -> error("App 内容再次改变，请重新同步")
            is MemoryWriteResult.Rejected -> error(result.reason)
        }
    }

    private suspend fun pull(id: String, local: MemoryDocumentSnapshot, document: MemoryRemoteDocument) {
        when (val result = repository.writeAtGeneration(id, local.path, document.content, local.generation)) {
            is MemoryWriteResult.Success -> acknowledge(id, local.path, document.content,
                local.generation + if (local.content == document.content) 0 else 1, document.token)
            is MemoryWriteResult.Conflict -> Unit // A new local generation will be compared next pass.
            is MemoryWriteResult.Rejected -> error("${local.path}: ${result.reason}")
        }
    }

    private suspend fun push(id: String, local: MemoryDocumentSnapshot, expectedToken: String) {
        val pending = MemoryPending(local.path, UUID.randomUUID().toString(), expectedToken, local.content, local.generation)
        updateLibrary(id) { it.copy(pending = it.pending + (local.path to pending)) }
        submitPending(id, pending)
    }

    private suspend fun resumePending(id: String, pending: MemoryPending) {
        val binding = library(id).binding
        val response = rpc(binding, MemoryRpcRequest(op = "operationStatus", spaceId = binding.spaceId, operationId = pending.operationId))
        if (response.found == true) {
            val document = requireNotNull(response.document) { "远端操作回执缺少结果" }
            require(document.path == pending.path && document.content == pending.content) { "远端操作回执不一致" }
            acknowledge(id, pending.path, pending.content, pending.localGeneration, document.token)
        } else {
            require(response.found == false) { "远端操作回执状态无效" }
            submitPending(id, pending)
        }
    }

    private suspend fun submitPending(id: String, pending: MemoryPending) {
        val lib = library(id)
        val response = remote.request(lib.binding, MemoryRpcRequest(
            op = if (pending.content == null) "delete" else "put", spaceId = lib.binding.spaceId,
            path = pending.path, expectedToken = pending.expectedToken, content = pending.content, operationId = pending.operationId,
        ))
        require(response.protocol == 1) { "不支持的远端协议版本" }
        if (!response.ok && response.error?.code == "conflict") {
            val current = requireNotNull(response.document) { "冲突响应缺少最新内容" }
            require(current.path == pending.path)
            conflict(id, repository.snapshot(id, pending.path), current, lib.baselines[pending.path]?.content)
            updateLibrary(id) { it.copy(pending = it.pending - pending.path) }
            return
        }
        ensureSuccess(response)
        val accepted = requireNotNull(response.document) { "远端写入缺少回执" }
        require(accepted.path == pending.path && accepted.content == pending.content) { "远端写入回执不一致" }
        acknowledge(id, pending.path, pending.content, pending.localGeneration, accepted.token)
    }

    private suspend fun acknowledge(id: String, path: String, content: String?, localGeneration: Long, remoteToken: String) {
        require(remoteToken.toLongOrNull()?.let { it >= 0 } == true) { "远端版本无效" }
        val current = library(id)
        val base = current.baselines[path]
        if (base != null && base.localGeneration == localGeneration && base.remoteToken == remoteToken &&
            base.content == content && path !in current.pending && path !in current.conflicts) return
        updateLibrary(id) { it.copy(
            baselines = it.baselines + (path to MemoryBaseline(content, localGeneration, remoteToken)),
            pending = it.pending - path, conflicts = it.conflicts - path,
        ) }
    }

    private suspend fun conflict(id: String, local: MemoryDocumentSnapshot, remoteDoc: MemoryRemoteDocument, base: String?) {
        updateLibrary(id) { it.copy(conflicts = it.conflicts + (local.path to MemorySyncConflict(
            local.path, base, local.content, remoteDoc.content, local.generation, remoteDoc.token,
        ))) }
    }

    private suspend fun rpc(binding: MemorySyncBinding, request: MemoryRpcRequest): MemoryRpcResponse =
        remote.request(binding, request).also(::ensureSuccess)

    private fun ensureSuccess(response: MemoryRpcResponse) {
        require(response.protocol == 1) { "不支持的远端协议版本" }
        if (!response.ok) throw MemorySyncException(response.error?.code ?: "protocol", response.error?.message ?: "远端同步失败")
    }

    private suspend fun library(id: String) = requireNotNull(store.snapshot().libraries[id]) { "记忆库尚未绑定" }
    private suspend fun updateLibrary(id: String, change: (MemoryLibraryState) -> MemoryLibraryState) {
        store.update { state -> state.copy(libraries = state.libraries + (id to change(requireNotNull(state.libraries[id])))) }
    }
}
