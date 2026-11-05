package me.rerere.rikkahub.data.ai.tools.opsv2

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import me.rerere.workspace.WorkspaceReadChunk

internal object OpsBudget {
    const val PAGE = 6 * 1024
    const val MARKDOWN = 512 * 1024
    const val HISTORY = 32 * 1024
    const val SEARCH = 4 * 1024
    const val MAX_LINES = 2000
    const val META = "ops_v2"
}

internal fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
internal fun String.isMarkdown() = substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in setOf("md", "markdown")
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
internal fun String.shellQuote() = "'" + replace("'", "'\\''") + "'"
internal fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String, fallback: Int) = str(key)?.toIntOrNull() ?: fallback
internal fun JsonObject.flag(key: String, fallback: Boolean = false) = str(key)?.toBooleanStrictOrNull() ?: fallback

internal data class PageCursor(
    val source: String, val position: Long, val line: Int, val fingerprint: String,
    val continuation: Boolean = false,
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(buildJsonObject {
        put("v", 2); put("s", source); put("p", position); put("l", line)
        put("f", fingerprint); put("c", continuation)
    }.toString().toByteArray(Charsets.UTF_8))

    companion object {
        fun decode(value: String, source: String): PageCursor {
            require(value.length <= 16384) { "Invalid cursor" }
            val obj = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)).jsonObject
            val pos = obj.str("p")?.toLongOrNull() ?: -1L
            val line = obj.int("l", 0)
            require(obj.int("v", 0) == 2 && obj.str("s") == source && pos >= 0 && line > 0) { "Cursor does not match source" }
            return PageCursor(source, pos, line, obj.str("f") ?: error("Invalid cursor"), obj.flag("c"))
        }
    }
}

internal data class TextPage(
    val source: String, val body: String, val startLine: Int, val continuation: Boolean,
    val fingerprint: String, val next: PageCursor?, val size: Long,
) {
    fun render(numbered: Boolean = true): String = buildString {
        append("file ").append(JsonPrimitive(source)).append(" | from line ").append(startLine)
        if (continuation) append(" (continued)")
        append(" | ").append(if (next == null) "complete" else "limited").append('\n')
        if (!numbered) append(body) else {
            var line = startLine
            val lines = body.split('\n')
            for ((i, text) in lines.withIndex()) {
                if (i == lines.lastIndex && text.isEmpty()) break
                append(line++).append("| ").append(text)
                if (i < lines.lastIndex) append('\n')
            }
        }
        if (next != null) append("\n[next cursor=").append(next.encode()).append(']')
    }
}

/** Bounded UTF-8 reader shared by local/SSH file ranges and immutable refs. */
internal object OpsPages {
    suspend fun read(
        source: String, cursor: String? = null, offset: Int = 1, limit: Int = 300,
        budget: Int = OpsBudget.PAGE, fetch: suspend (Long, Int) -> WorkspaceReadChunk,
    ): TextPage {
        require(offset > 0 && limit > 0 && budget in 4..OpsBudget.MARKDOWN) { "Invalid page range" }
        require(cursor == null || offset == 1) { "Use cursor OR offset, not both" }
        val resume = cursor?.let { PageCursor.decode(it, source) }
        var position = resume?.position ?: 0L
        var line = resume?.line ?: 1
        var stamp: String? = resume?.fingerprint
        var size = 0L
        var buffer = ByteArray(0)
        var index = 0
        suspend fun peek(): Int {
            if (index == buffer.size) {
                val chunk = fetch(position, 65536)
                if (stamp != null && stamp != chunk.fingerprint) error("source_changed: retry from the beginning")
                stamp = chunk.fingerprint
                size = chunk.size
                buffer = chunk.bytes
                index = 0
                if (position > size) error("source_changed: cursor is beyond EOF")
            }
            return if (index < buffer.size) buffer[index].toInt() and 255 else -1
        }
        suspend fun take(): Int {
            val value = peek()
            if (value >= 0) { index++; position++ }
            return value
        }
        if (resume == null) while (line < offset) {
            val b = take()
            if (b < 0) break
            if (b == 10) line++
        }
        val startLine = line
        val out = java.io.ByteArrayOutputStream(minOf(budget, 65536))
        var last = -1
        while (line.toLong() - startLine < limit.toLong()) {
            val first = peek()
            if (first < 0) break
            val width = when {
                first < 128 -> 1
                first in 0xc2..0xdf -> 2
                first in 0xe0..0xef -> 3
                first in 0xf0..0xf4 -> 4
                else -> error("File is not valid UTF-8; use workspace_shell for binary data")
            }
            if (first == 0) error("Binary data is not supported by text pages")
            if (out.size() + width > budget) break
            repeat(width) { n ->
                val b = take()
                require(b >= 0 && (n == 0 || b in 0x80..0xbf)) { "Invalid UTF-8 sequence" }
                out.write(b); last = b
            }
            if (first == 10) line++
        }
        val more = peek() >= 0
        val bytes = out.toByteArray()
        val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return TextPage(source, body, startLine, resume?.continuation ?: false, stamp.orEmpty(),
            if (more) PageCursor(source, position, line, stamp.orEmpty(), if (last < 0) resume?.continuation ?: false else last != 10) else null, size)
    }
}
