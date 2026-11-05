package me.rerere.rikkahub.data.ai.tools.opsv2

import kotlinx.serialization.json.*

internal object WorkspaceQueries {
    val script: String by lazy {
        requireNotNull(WorkspaceQueries::class.java.getResourceAsStream("/ops-v2/query.sh")) { "Missing query helper" }
            .bufferedReader().use { it.readText() }
    }

    fun validateGlob(glob: String) {
        require(glob.isNotEmpty() && '/' !in glob && "**" !in glob && !glob.startsWith('!') &&
            glob.none { it in "{}\\\n\r\u0000" }) { "Unsupported glob: use basename *, ?, [abc], [!abc]; narrow with path" }
        var open = false
        for (c in glob) when (c) {
            '[' -> { require(!open) { "Invalid glob" }; open = true }
            ']' -> { require(open) { "Invalid glob" }; open = false }
        }
        require(!open) { "Invalid glob" }
    }

    fun command(op: String, path: String, pattern: String, glob: String, regex: Boolean, ignore: Boolean, max: Int, filesOnly: Boolean): String {
        validateGlob(glob)
        require(path.startsWith('/') && '\u0000' !in path) { "path must be absolute" }
        require(pattern.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Only single-line patterns are supported" }
        return "bash -c " + script.shellQuote() + " -- " +
            listOf(op, path, pattern, glob, regex.toString(), ignore.toString(), max.toString(), filesOnly.toString()).joinToString(" ") { it.shellQuote() }
    }

    fun render(stdout: String, failed: Boolean, stderr: String): Pair<String, String> {
        val fields = stdout.split('\u0000')
        var index = 0
        var terminal = false
        var status = "partial_error"
        var detail = "Missing completion frame"
        val lines = mutableListOf<String>()
        while (index < fields.size && fields[index].isNotEmpty()) {
            when (fields[index++]) {
                "F" -> {
                    require(index < fields.size) { "Invalid path frame" }
                    lines += JsonPrimitive(fields[index++]).toString()
                }
                "H" -> {
                    require(index + 2 < fields.size) { "Invalid hit frame" }
                    val file = fields[index++]; val number = fields[index++]; val text = fields[index++]
                    require(number.toIntOrNull()?.let { it > 0 } == true) { "Invalid line number" }
                    lines += "${JsonPrimitive(file)}:$number:$text"
                }
                "S" -> {
                    require(index + 1 < fields.size) { "Invalid status frame" }
                    status = fields[index++]; detail = fields[index++]; terminal = true
                    break
                }
                else -> error("Invalid query frame")
            }
        }
        if (failed || !terminal) {
            status = "partial_error"
            detail = "Capture incomplete or helper failed. " + stderr.take(300)
        }
        require(status in setOf("complete", "limited", "partial_error", "error")) { "Unknown query status" }
        val text = buildString {
            append("status=").append(status).append(" shown=").append(lines.size)
            append(" (text-file excerpts; not a total count)\n")
            if (lines.isEmpty() && status == "complete") append("no match\n")
            lines.forEach { append(it).append('\n') }
            if (status == "limited") append("[more exist; narrow path/glob/pattern]\n")
            if (detail.isNotBlank()) append("detail=").append(JsonPrimitive(detail.take(500))).append('\n')
        }
        return text to status
    }
}
