package me.rerere.rikkahub.data.sync.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption

internal val MemorySyncJson = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

/** Kept in noBackupFilesDir: restoring user data must never replay a network operation. */
internal class MemorySyncStore(private val directory: File) {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableState = MutableStateFlow(MemoryPersistentState())
    val state = mutableState.asStateFlow()

    suspend fun snapshot(): MemoryPersistentState = withContext(Dispatchers.IO) {
        mutex.withLock { load(); mutableState.value }
    }

    suspend fun update(change: (MemoryPersistentState) -> MemoryPersistentState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val next = change(mutableState.value)
            if (next == mutableState.value) return@withLock
            persist(next)
            mutableState.value = next
        }
    }

    private fun load() {
        if (loaded) return
        val file = File(directory, "state.json")
        var next = if (file.exists()) {
            require(file.length() <= 256L * 1024 * 1024) { "Memory sync state exceeds budget" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.readBytes())).toString()
            MemorySyncJson.decodeFromString<MemoryPersistentState>(text)
        } else MemoryPersistentState()
        require(next.formatVersion == 1) { "Unsupported memory sync state version" }
        val reset = File(directory, RESTORE_MARKER)
        next = next.copy(libraries = next.libraries.mapValues { (_, lib) ->
            if (reset.exists()) lib.copy(
                binding = lib.binding.copy(enabled = false), baselines = emptyMap(), pending = emptyMap(),
                status = lib.status.copy(running = false, error = "备份已恢复；请核对远端后重新启用同步"),
            ) else lib.copy(status = lib.status.copy(running = false))
        })
        if (reset.exists()) {
            persist(next)
            check(reset.delete()) { "Cannot acknowledge memory sync restore marker" }
        }
        mutableState.value = next
        loaded = true
    }

    private fun persist(next: MemoryPersistentState) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private memory sync state" }
        val target = File(directory, "state.json")
        val temp = File(directory, "state.json.tmp")
        val bytes = MemorySyncJson.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024 * 1024) { "Memory sync state exceeds budget" }
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    companion object {
        private const val RESTORE_MARKER = "restore-required"

        /** Called before a pending backup is installed, including interrupted restore recovery. */
        fun markRestore(directory: File) {
            check(directory.isDirectory || directory.mkdirs())
            FileOutputStream(File(directory, RESTORE_MARKER)).use { it.write(1); it.fd.sync() }
        }
    }
}
