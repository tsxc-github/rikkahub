package me.rerere.workspace.ssh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Separate from shell output collectors: JSON must never be silently truncated. */
internal class RpcCollector(input: InputStream, private val maximum: Int) {
    private val bytes = ByteArrayOutputStream(minOf(maximum, 8192))
    @Volatile private var failure: Exception? = null
    private val thread = Thread {
        try {
            input.use { source ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (bytes.size().toLong() + n > maximum) {
                        failure = IOException("SSH RPC output exceeds budget")
                    } else if (failure == null) bytes.write(buffer, 0, n)
                }
            }
        } catch (e: IOException) { failure = e }
    }.apply { isDaemon = true; name = "ssh-memory-rpc-reader"; start() }

    fun finish(timeoutMillis: Long): String {
        thread.join(timeoutMillis.coerceAtLeast(1))
        if (thread.isAlive) throw IOException("SSH RPC output did not finish")
        failure?.let { throw it }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}

/** A request is not replayed after an ambiguous disconnect; callers persist operation IDs. */
fun SshConnectionPool.exchangeJson(
    id: String,
    config: SshConnectionConfig,
    command: String,
    request: ByteArray,
    timeoutMillis: Long = 60_000,
): String {
    require(request.size <= 512 * 1024) { "SSH RPC request exceeds budget" }
    return use(id, config, retry = false) { it.exchangeJson(command, request, timeoutMillis) }
}
