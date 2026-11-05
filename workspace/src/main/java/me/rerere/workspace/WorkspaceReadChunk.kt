package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** A bounded raw range. Fingerprints detect changes, not adversarial same-mtime rewrites. */
data class WorkspaceReadChunk(val bytes: ByteArray, val size: Long, val fingerprint: String)

fun InputStream.readWorkspaceBytes(maxBytes: Int): ByteArray {
    require(maxBytes in 1..65536)
    val bytes = ByteArray(maxBytes)
    var used = 0
    while (used < bytes.size) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Read cancelled")
        val n = read(bytes, used, bytes.size - used)
        if (n < 0) break
        if (n == 0) continue
        used += n
    }
    return bytes.copyOf(used)
}

fun File.readWorkspaceChunk(offset: Long, maxBytes: Int): WorkspaceReadChunk {
    require(offset >= 0 && maxBytes in 1..65536)
    require(isFile && canRead()) { "Not a readable regular file: $path" }
    fun stamp() = "${canonicalPath}:${length()}:${lastModified()}"
    val before = stamp()
    val size = length()
    val bytes = RandomAccessFile(this, "r").use { file ->
        file.seek(offset)
        val buffer = ByteArray(maxBytes)
        var count = 0
        while (count < maxBytes) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Read cancelled")
            val n = file.read(buffer, count, maxBytes - count)
            if (n < 0) break
            count += n
        }
        buffer.copyOf(count)
    }
    check(before == stamp()) { "source_changed: retry from the beginning" }
    return WorkspaceReadChunk(bytes, size, before)
}
