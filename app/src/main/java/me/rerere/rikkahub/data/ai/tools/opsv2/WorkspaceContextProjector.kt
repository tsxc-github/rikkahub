package me.rerere.rikkahub.data.ai.tools.opsv2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal fun managedText(text: String, source: String, markdown: Boolean = false, status: String = "complete") =
    UIMessagePart.Text(text, buildJsonObject {
        put(OpsBudget.META, buildJsonObject {
            put("v", 2); put("source", source); put("markdown", markdown); put("status", status)
        })
    })

internal fun utf8Prefix(text: String, bytes: Int): String {
    val raw = text.toByteArray(Charsets.UTF_8)
    if (raw.size <= bytes) return text
    var end = bytes.coerceAtLeast(0)
    while (end > 0 && (raw[end].toInt() and 0xc0) == 0x80) end--
    return String(raw, 0, end, Charsets.UTF_8)
}

/** Only application-tagged workspace text is managed. All edits affect copies, never history. */
internal class WorkspaceContextProjector(private val store: ToolResultStore) {
    private data class Key(val message: Int, val part: Int, val output: Int)
    private data class Entry(
        val key: Key, val callId: String, val source: String, val status: String,
        val markdown: Boolean, val full: String, var view: String,
        var ref: String? = null, var recoverable: Boolean = true,
    )

    suspend fun project(messages: List<UIMessage>, scope: String): List<UIMessage> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<Entry>()
        messages.forEachIndexed { m, message -> message.parts.forEachIndexed { p, part ->
            if (part is UIMessagePart.Tool && part.toolName in setOf("workspace_read_file", "workspace_result", "workspace_grep", "workspace_glob", "workspace_shell", "workspace_write_file", "workspace_edit_file")) part.output.forEachIndexed { o, output ->
                if (output is UIMessagePart.Text) {
                    val info = output.metadata?.get(OpsBudget.META) as? JsonObject
                    if (info?.int("v", 0) == 2) {
                        val full = if (part.toolName == "workspace_shell") shellView(output.text) else output.text
                        entries += Entry(Key(m, p, o), part.toolCallId, info.str("source").orEmpty(),
                            info.str("status") ?: "complete", info.flag("markdown"), full, full)
                    }
                }
            }
        } }
        fun ensureRef(e: Entry): String? {
            e.ref?.takeIf { store.contains(scope, it) }?.let { return it }
            return try { store.put(scope, e.source, e.full, e.status, e.markdown).also { e.ref = it } }
            catch (_: Exception) { e.recoverable = false; null }
        }
        // Bound generic first screens, but NEVER apply this budget to Markdown.
        for (e in entries) if (!e.markdown && e.view.utf8Size() > OpsBudget.PAGE) {
            val ref = ensureRef(e)
            if (ref != null) e.view = utf8Prefix(e.full, 4096) + "\n[limited; ref=$ref; use workspace_result]"
            else e.view = e.full + "\n[archive unavailable; original retained; budget degraded]"
        }
        // Deterministic 16 KiB buckets: adding a result doesn't slide the whole prefix each step.
        val normal = entries.filter { !it.markdown && it.recoverable }
        val total = normal.sumOf { it.view.utf8Size().toLong() }
        val step = OpsBudget.HISTORY / 2
        val cutoff = if (total <= OpsBudget.HISTORY) 0L else ((total - OpsBudget.HISTORY) / step + 1) * step
        var prefix = 0L
        val keep = mutableSetOf<Key>()
        for (e in normal) {
            if (prefix >= cutoff) keep += e.key
            prefix += e.view.utf8Size()
        }
        entries.filter { it.markdown || !it.recoverable }.forEach { keep += it.key }
        // Budget first, dedup second. A dedup anchor can never be folded later in this projection.
        val anchors = mutableMapOf<String, Entry>()
        for (e in entries) {
            if (e.key !in keep) {
                val ref = ensureRef(e)
                if (ref != null) e.view = "[archived ${JsonPrimitive(e.source)}; status=${e.status}; ref=$ref]"
                // Fail open on storage failure. Never delete the only recoverable copy for a byte target.
                else e.view = e.full + "\n[archive unavailable; original retained; budget degraded]"
                continue
            }
            if (e.full.utf8Size() < 1024) continue
            val hash = sha256((e.source + "\u0000" + e.full).toByteArray(Charsets.UTF_8))
            val anchor = anchors[hash]
            if (anchor != null && anchor.view == anchor.full) {
                val ref = ensureRef(e)
                if (ref != null) e.view = "[same body as tool ${JsonPrimitive(anchor.callId)} in THIS request; ref=$ref; status=${e.status}]"
            } else if (e.view == e.full) anchors[hash] = e
        }
        val replacement = entries.associate { it.key to it.view }
        messages.mapIndexed { m, message -> message.copy(parts = message.parts.mapIndexed { p, part ->
            if (part !is UIMessagePart.Tool) part else part.copy(output = part.output.mapIndexed { o, output ->
                if (output !is UIMessagePart.Text || Key(m, p, o) !in replacement) output else output.copy(
                    text = replacement[Key(m, p, o)] ?: output.text,
                    metadata = output.metadata?.let { JsonObject(it.filterKeys { key -> key != OpsBudget.META }) },
                )
            })
        }) }
    }

    /** This adapter is only called for the known native workspace_shell tool, never arbitrary JSON. */
    private fun shellView(text: String): String {
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return text
        if (obj["exitCode"] !is JsonPrimitive || obj["stdout"] !is JsonPrimitive || obj["stderr"] !is JsonPrimitive) return text
        return buildString {
            append("exitCode=").append(obj.str("exitCode"))
            append(" timedOut=").append(obj.str("timedOut") ?: "false")
            if (obj.flag("truncated")) append(" source_truncated=true (ref contains captured output only)")
            val error = obj.str("stderr").orEmpty()
            if (error.isNotEmpty()) append("\n[stderr]\n").append(error)
            append("\n[stdout]\n").append(obj.str("stdout").orEmpty())
        }
    }
}
