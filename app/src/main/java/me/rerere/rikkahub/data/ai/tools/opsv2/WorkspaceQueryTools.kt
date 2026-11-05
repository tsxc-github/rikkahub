package me.rerere.rikkahub.data.ai.tools.opsv2

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.repository.WorkspaceRepository

internal fun createV2QueryTools(workspaceId: String, repo: WorkspaceRepository, root: String, approval: (String) -> Boolean): List<Tool> =
    listOf(false, true).map { globOnly ->
        val name = if (globOnly) "workspace_glob" else "workspace_grep"
        val fields = listOf(
            Triple("pattern", "string", if (globOnly) "Basename glob (*, ?, character classes)." else "Literal single-line search text; regex=true requires rg."),
            Triple("path", "string", "Absolute scope; defaults to $root."),
            Triple("max", "integer", "Maximum results, default ${if (globOnly) 80 else 40}, hard max 80; byte budget also applies."),
        ) + if (globOnly) emptyList() else listOf(
            Triple("glob", "string", "Optional basename glob; no **, !prefix, or /; filters before search."),
            Triple("regex", "boolean", "Use rg regex rather than literal (default false)."),
            Triple("ignoreCase", "boolean", "ASCII case folding (default true)."),
            Triple("filesOnly", "boolean", "Only matching paths; use to locate files cheaply."),
        )
        Tool(name = name,
            description = if (globOnly) "List bounded sorted paths. Limited/partial results are explicit." else "Search text files with bounded excerpts and honest completion status. Prefer filesOnly for locating files; read exact source for details.",
            parameters = { schema(fields, listOf("pattern")) },
            needsApproval = { approval(name) },
            execute = { args -> guarded(name) {
                val json = args.jsonObject
                val pattern = json.str("pattern") ?: error("pattern is required")
                require(pattern.isNotEmpty()) { "pattern cannot be empty" }
                val path = json.str("path") ?: root
                val glob = if (globOnly) pattern else json.str("glob") ?: "*"
                val command = WorkspaceQueries.command(if (globOnly) "glob" else "grep", path,
                    if (globOnly) "" else pattern, glob, json.flag("regex"), json.flag("ignoreCase", true),
                    json.int("max", if (globOnly) 80 else 40).coerceIn(1, 80), json.flag("filesOnly"))
                val result = repo.executeCommand(workspaceId, command, timeoutMillis = 60000)
                val (text, status) = if (result.timedOut) "status=timeout; search is incomplete" to "timeout"
                    else WorkspaceQueries.render(result.stdout, result.truncated || result.exitCode != 0, result.stderr)
                listOf(managedText(text, "$name:${sha256(command.toByteArray()).take(16)}", status = status))
            } },
        )
    }
