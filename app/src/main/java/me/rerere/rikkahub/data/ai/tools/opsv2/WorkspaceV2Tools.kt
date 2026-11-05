package me.rerere.rikkahub.data.ai.tools.opsv2

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.readWorkspaceChunk

internal suspend fun guarded(source: String, action: suspend () -> List<UIMessagePart>): List<UIMessagePart> = try {
    action()
} catch (e: CancellationException) { throw e
} catch (e: InterruptedException) { throw e
} catch (e: Exception) {
    listOf(managedText("error: ${e.message.orEmpty().take(500)}", source, status = "error"))
}

internal fun schema(fields: List<Triple<String, String, String>>, required: List<String>) = InputSchema.Obj(
    properties = buildJsonObject {
        fields.forEach { (key, type, description) -> put(key, buildJsonObject {
            put("type", type); put("description", description)
        }) }
    }, required = required,
)

private val pageFields = listOf(
    Triple("offset", "integer", "First original line, 1-based."),
    Triple("limit", "integer", "Maximum original lines; page byte budget also applies."),
    Triple("cursor", "string", "Exact continuation returned by this tool; do not combine with offset/limit."),
)

internal fun createV2ReadTool(original: Tool, workspaceId: String, repo: WorkspaceRepository): Tool = original.copy(
    description = "Read UTF-8 text or images. Markdown defaults to full text up to 512 KiB, exempt from ordinary history folding. Other text is paged. Follow next cursor, including inside long lines.",
    parameters = { schema(listOf(Triple("path", "string", "Absolute workspace path.")) + pageFields, listOf("path")) },
    execute = { args -> guarded("workspace_read_file") {
        val json = args.jsonObject
        val path = json.str("path") ?: error("path is required")
        require(path.startsWith('/') && '\u0000' !in path) { "path must be absolute" }
        if (path.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico")) {
            original.execute(args)
        } else {
            val cursor = json.str("cursor")
            require(cursor == null || (json["offset"] == null && json["limit"] == null)) { "Use cursor OR offset/limit" }
            val markdown = path.isMarkdown()
            val full = markdown && json["offset"] == null && json["limit"] == null
            val page = OpsPages.read("file:$workspaceId:$path", cursor, json.int("offset", 1),
                if (full) Int.MAX_VALUE else json.int("limit", 300).coerceAtMost(OpsBudget.MAX_LINES),
                if (full) OpsBudget.MARKDOWN else OpsBudget.PAGE,
            ) { pos, count -> repo.readAbsoluteChunk(workspaceId, path, pos, count) }
            listOf(managedText(page.copy(source = path).render(numbered = !full), path, markdown,
                if (page.next == null) "complete" else "limited", paged = true))
        }
    } },
)

/** Application provenance, not a marker parsed out of untrusted tool text. */
internal fun manageWorkspaceTool(tool: Tool): Tool {
    if (!tool.name.startsWith("workspace_") || tool.name == "workspace_result") return tool
    return tool.copy(execute = { args ->
        val result = guarded(tool.name) { tool.execute(args) }
        result.mapIndexed { index, part ->
            if (part !is UIMessagePart.Text || part.metadata?.containsKey(OpsBudget.META) == true) part else {
                val json = args as? JsonObject
                val source = json?.str("path") ?: "${tool.name}:${sha256(args.toString().toByteArray()).take(16)}:$index"
                val shell = if (tool.name == "workspace_shell") runCatching { Json.parseToJsonElement(part.text).jsonObject }.getOrNull() else null
                val status = when {
                    shell?.flag("timedOut") == true -> "timeout"
                    shell?.flag("truncated") == true -> "source_truncated"
                    shell != null && shell.int("exitCode", -1) != 0 -> "error"
                    else -> "complete"
                }
                val marked = managedText(part.text, source, tool.name == "workspace_read_file" && source.isMarkdown(), status)
                part.copy(metadata = JsonObject(part.metadata.orEmpty() + marked.metadata.orEmpty()))
            }
        }
    })
}

internal fun createV2ResultTool(store: ToolResultStore, scope: String, legacy: File): Tool = Tool(
    name = "workspace_result",
    description = "Restore captured output by ref. No command is rerun. Follow next cursor; expired refs are explicit. Markdown snapshots use a large page budget.",
    parameters = { schema(listOf(Triple("ref", "string", "Reference id, not a path.")) + pageFields, listOf("ref")) },
    needsApproval = { false },
    execute = { args -> guarded("workspace_result") {
        val json = args.jsonObject
        val ref = json.str("ref") ?: error("ref is required")
        require(ref.matches(Regex("[0-9a-f]{32}"))) { "Invalid ref" }
        val cursor = json.str("cursor")
        require(cursor == null || (json["offset"] == null && json["limit"] == null)) { "Use cursor OR offset/limit" }
        val v1 = File(legacy, "$ref.txt")
        // v2 references are scoped; v1 support is read-only and uses its own physical path.
        val meta = if (store.contains(scope, ref)) store.manifest(scope, ref) else null
        require(meta != null || v1.isFile) { "expired: ref $ref is unavailable; re-read the source" }
        val markdown = meta?.flag("markdown") ?: false
        val full = markdown && json["offset"] == null && json["limit"] == null
        val page = OpsPages.read("ref:$ref", cursor, json.int("offset", 1),
            if (full) Int.MAX_VALUE else json.int("limit", 64).coerceAtMost(OpsBudget.MAX_LINES),
            if (full) OpsBudget.MARKDOWN else OpsBudget.PAGE,
        ) { pos, count -> withContext(Dispatchers.IO) {
            if (meta != null) store.chunk(scope, ref, pos, count) else v1.readWorkspaceChunk(pos, count)
        } }
        val status = meta?.str("status") ?: "legacy_capture_completeness_unknown"
        listOf(managedText("capture_status=$status\n" + page.render(numbered = !full, lineKind = "snapshot"),
            "ref:$ref", markdown, if (page.next == null) status else "limited", paged = true))
    } },
)
