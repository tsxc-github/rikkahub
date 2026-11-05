package me.rerere.rikkahub.data.ai.tools.opsv2

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceQueryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun query(root: File, pattern: String, glob: String = "*", max: Int = 40, fallback: Boolean = false, filesOnly: Boolean = false): Pair<String, String> {
        val command = WorkspaceQueries.command("grep", root.absolutePath, pattern, glob, false, true, max, filesOnly)
        val builder = ProcessBuilder("bash", "-c", command)
        if (fallback) builder.environment()["OPS_FORCE_AWK"] = "1"
        val process = builder.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return WorkspaceQueries.render(stdout, process.waitFor() != 0, stderr)
    }
    @Test fun `single file has more than eight matches and honest cap`() {
        val file = tmp.newFile("many.kt").apply { writeText("needle\n".repeat(30)) }
        val all = query(file, "needle", max = 40)
        assertEquals("complete", all.second); assertTrue(all.first.contains("shown=30"))
        val small = query(file, "needle", max = 5)
        assertEquals("limited", small.second); assertTrue(small.first.contains("shown=5"))
    }
    @Test fun `fallback filters glob before searching and limits`() {
        val dir = tmp.newFolder("scope")
        repeat(60) { File(dir, "a$it.md").writeText("needle\n".repeat(8)) }
        File(dir, "z.kt").writeText("needle\n")
        val result = query(dir, "needle", "*.kt", fallback = true)
        assertEquals("complete", result.second); assertTrue(result.first.contains("z.kt")); assertTrue(result.first.contains("shown=1"))
    }
    @Test fun `literal fallback preserves backslash and surrounding spaces`() {
        val file = tmp.newFile("literal.txt").apply { writeText("prefix  \\needle  suffix\nnot matched\n") }
        assertTrue(query(file, " \\needle ", fallback = true).first.contains("shown=1"))
    }
    @Test fun `unusual filenames do not break framing`() {
        val dir = tmp.newFolder("scope")
        File(dir, "-a:b\n' weird.kt").writeText("needle: text\n")
        val result = query(dir, "needle", fallback = true)
        assertEquals("complete", result.second); assertTrue(result.first.contains("shown=1")); assertTrue(result.first.contains("\\n"))
    }
    @Test fun `missing path is error rather than no match`() {
        val result = query(File(tmp.root, "missing"), "needle")
        assertEquals("error", result.second); assertFalse(result.first.contains("no match"))
    }
    @Test fun `files only is small and reports matching paths`() {
        val dir = tmp.newFolder("scope")
        File(dir, "a.kt").writeText("needle\n".repeat(100))
        val result = query(dir, "needle", filesOnly = true)
        assertEquals("complete", result.second); assertTrue(result.first.contains("shown=1")); assertFalse(result.first.contains(":1:needle"))
    }
    @Test fun `unsupported globs are explicitly rejected`() {
        for (glob in listOf("!*.md", "**/*.kt", "{a,b}.kt", "src/*.kt", "[abc")) {
            try { WorkspaceQueries.validateGlob(glob); fail("Accepted $glob") } catch (_: IllegalArgumentException) { }
        }
        for (glob in listOf("*.kt", "a?.md", "[abc]*", "[!a]*")) WorkspaceQueries.validateGlob(glob)
    }
    @Test fun `missing completion frame cannot become no match`() {
        val result = WorkspaceQueries.render("", false, "")
        assertEquals("partial_error", result.second); assertFalse(result.first.contains("no match"))
    }
    @Test fun `ordinary paths keep conventional grep display`() {
        val frame = listOf("H", "/workspace/many.txt", "1", "needle", "S", "complete", "", "").joinToString("\u0000")
        val (text, status) = WorkspaceQueries.render(frame, false, "")
        assertEquals("complete", status)
        assertTrue(text.contains("/workspace/many.txt:1:needle"))
        assertFalse(text.contains("\"/workspace/many.txt\""))
    }
    @Test fun `special paths use explicit escaped fields not ambiguous colons`() {
        val frame = listOf("H", "/workspace/a:b\nweird.txt", "12", "needle", "S", "complete", "", "").joinToString("\u0000")
        val (text, status) = WorkspaceQueries.render(frame, false, "")
        assertEquals("complete", status)
        assertTrue(text.contains("path=\"/workspace/a:b\\nweird.txt\" line=12 text=\"needle\""))
    }

}
