package me.rerere.rikkahub.data.ai.tools.opsv2

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.workspace.readWorkspaceChunk
import me.rerere.workspace.WorkspaceReadChunk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpsV2Test {
    @get:Rule val tmp = TemporaryFolder()
    private fun store(name: String = "store") = ToolResultStore(tmp.newFolder(name))
    private fun message(id: String, text: String, source: String = "/code.kt", markdown: Boolean = false) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool(id, "workspace_read_file", "{}", output = listOf(managedText(text, source, markdown)))),
    )
    private fun body(message: UIMessage) = ((message.parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text
    private fun source(bytes: ByteArray, chunkSize: Int = 65536): suspend (Long, Int) -> WorkspaceReadChunk = { offset, max ->
        val start = offset.toInt().coerceAtMost(bytes.size)
        WorkspaceReadChunk(bytes.copyOfRange(start, minOf(start + minOf(max, chunkSize), bytes.size)), bytes.size.toLong(), "fixed")
    }

    @Test fun `utf8 long lines round trip through exact cursors`() = runBlocking {
        val original = "纸航🚀".repeat(2000) + "\r\nlast line without newline"
        val bytes = original.toByteArray()
        val pages = StringBuilder()
        var cursor: String? = null
        var calls = 0
        do {
            val page = OpsPages.read("/file", cursor, budget = 401, fetch = source(bytes, 7))
            pages.append(page.body); cursor = page.next?.encode(); calls++
            assertTrue(page.body.utf8Size() <= 401)
            assertTrue(calls < 200)
        } while (cursor != null)
        assertEquals(original, pages.toString())
        assertTrue(calls > 2)
    }

    @Test fun `line offset handles CRLF and final unterminated line`() = runBlocking {
        val page = OpsPages.read("/file", offset = 2, limit = 1, fetch = source("a\r\nb\r\nc".toByteArray()))
        assertEquals("b\r\n", page.body)
        val last = OpsPages.read("/file", page.next!!.encode(), limit = 2, fetch = source("a\r\nb\r\nc".toByteArray()))
        assertEquals("c", last.body)
        assertNull(last.next)
    }

    @Test fun `empty file is complete`() = runBlocking {
        val page = OpsPages.read("/file", fetch = source(byteArrayOf()))
        assertEquals("", page.body); assertNull(page.next)
    }

    @Test fun `resume detects changed source`() = runBlocking {
        val page = OpsPages.read("/file", budget = 4, fetch = source("abcdefgh".toByteArray()))
        try {
            OpsPages.read("/file", page.next!!.encode()) { _, _ -> WorkspaceReadChunk("rest".toByteArray(), 8, "changed") }
            fail("Expected source_changed")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("source_changed")) }
    }

    @Test fun `cursor cannot address another source`() {
        try { PageCursor.decode(PageCursor("/a", 0, 1, "x").encode(), "/b"); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `markdown keeps full body beyond ordinary history budget`() = runBlocking {
        val text = "# long Markdown\n" + "文档内容 abc\n".repeat(9000)
        assertTrue(text.utf8Size() > OpsBudget.HISTORY)
        val input = listOf(message("md", text, "/Long.MD", true))
        val projected = WorkspaceContextProjector(store()).project(input, "chat")
        assertEquals(text, body(projected.single()))
        assertEquals(text, body(input.single()))
        assertTrue("/Long.MD".isMarkdown())
        assertTrue("/Long.markdown".isMarkdown())
    }

    @Test fun `markdown sized page is not constrained to 300 lines`() = runBlocking {
        val text = (1..20000).joinToString("\n") { "md line $it" }
        val page = OpsPages.read("/doc.md", limit = Int.MAX_VALUE, budget = OpsBudget.MARKDOWN, fetch = source(text.toByteArray()))
        assertEquals(text, page.body); assertNull(page.next)
    }

    @Test fun `ordinary history is bounded but UI original is unchanged`() = runBlocking {
        val input = (1..20).map { message("call$it", "result-$it\n" + "x".repeat(5900), "/file$it.kt") }
        val output = WorkspaceContextProjector(store()).project(input, "chat")
        val retained = output.map(::body).filterNot { it.startsWith("[archived") }
        assertTrue(retained.sumOf { it.utf8Size() } <= OpsBudget.HISTORY)
        assertTrue(output.any { body(it).startsWith("[archived") })
        assertTrue(input.none { body(it).startsWith("[archived") })
        assertEquals(body(input.last()), body(output.last()))
    }

    @Test fun `duplicate anchor is present in the exact projected request`() = runBlocking {
        val text = "body ".repeat(500)
        val input = listOf(message("anchor", text), message("again", text))
        val output = WorkspaceContextProjector(store()).project(input, "chat")
        assertEquals(text, body(output.first()))
        assertTrue(body(output.last()).contains("anchor"))
        assertTrue(body(output.last()).contains("THIS request"))
    }

    @Test fun `after context trimming body cannot refer to absent anchor`() = runBlocking {
        val text = "body ".repeat(500)
        val input = listOf(message("old", text), UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("next"))), message("new", text))
        val visible = input.limitContext(1)
        val output = WorkspaceContextProjector(store()).project(visible, "chat")
        assertTrue(output.any { message -> message.parts.filterIsInstance<UIMessagePart.Tool>().any {
            it.output.filterIsInstance<UIMessagePart.Text>().any { p -> p.text == text }
        } })
        assertFalse(output.any { message -> message.parts.filterIsInstance<UIMessagePart.Tool>().any {
            it.output.filterIsInstance<UIMessagePart.Text>().any { p -> p.text.contains("same body as tool \"old\"") }
        } })
    }

    @Test fun `unmanaged JSON and provider metadata remain unchanged`() = runBlocking {
        val metadata = buildJsonObject { put("signature", "provider-signature") }
        val part = UIMessagePart.Text("{\"text\":\"ordinary JSON\"}", metadata)
        val input = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("id", "mcp__X__test", "{}", output = listOf(part)))))
        assertEquals(input, WorkspaceContextProjector(store()).project(input, "chat"))
    }

    @Test fun `projection is deterministic and strips only private metadata`() = runBlocking {
        val input = (1..20).map { message("id$it", "$it:" + "y".repeat(4000), "/$it.kt") }
        val projector = WorkspaceContextProjector(store())
        val first = projector.project(input, "chat")
        assertEquals(first, projector.project(input, "chat"))
        first.forEach { val out = (it.parts.single() as UIMessagePart.Tool).output.single(); assertFalse(out.metadata?.containsKey(OpsBudget.META) == true) }
    }

    @Test fun `store evicts oldest not newly created ref`() {
        var clock = 100L
        val s = ToolResultStore(tmp.newFolder("bounded"), maxBytes = 200, leaseMillis = 10, now = { clock })
        val old = s.put("chat", "old", "a".repeat(120))
        clock += 20
        val fresh = s.put("chat", "fresh", "b".repeat(100))
        assertFalse(s.contains("chat", old)); assertTrue(s.contains("chat", fresh))
    }

    @Test fun `active reference is protected and failed put does not return dead ref`() {
        val s = ToolResultStore(tmp.newFolder("protected"), maxFiles = 1)
        val first = s.put("chat", "a", "first")
        try { s.put("chat", "b", "second"); fail("Expected storage full") } catch (_: java.io.IOException) { }
        assertTrue(s.contains("chat", first))
    }

    @Test fun `references are scoped and preserve Markdown policy`() {
        val s = store()
        val ref = s.put("chatA", "/notes.md", "# Document", markdown = true)
        assertTrue(s.manifest("chatA", ref).flag("markdown"))
        assertFalse(s.contains("chatB", ref)); assertFalse(s.contains("chatA", "../escape"))
    }

    @Test fun `stored raw long line can be fully restored`() = runBlocking {
        val s = store(); val text = "x".repeat(1192) + "TAIL_END"
        val ref = s.put("chat", "/source", text)
        val page = OpsPages.read("ref:$ref") { pos, max -> s.chunk("chat", ref, pos, max) }
        assertEquals(text, page.body); assertTrue(page.body.endsWith("TAIL_END"))
    }

    @Test fun `storage failure retains original rather than fake reference`() = runBlocking {
        val s = ToolResultStore(tmp.newFolder("too-small"), maxBytes = 10)
        val text = "x".repeat(9000)
        val output = WorkspaceContextProjector(s).project(listOf(message("big", text)), "chat")
        assertTrue(body(output.single()).startsWith(text))
        assertTrue(body(output.single()).contains("budget degraded"))
    }
    @Test fun `compression checkpoint cannot leave a missing dedup anchor`() = runBlocking {
        val text = "body ".repeat(500)
        val checkpoint = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("summary")), isContextCheckpoint = true)
        val visible = listOf(message("before", text), checkpoint, message("after", text)).limitContext(0)
        assertFalse(visible.any { it.parts.filterIsInstance<UIMessagePart.Tool>().any { t -> t.toolCallId == "before" } })
        val output = WorkspaceContextProjector(store()).project(visible, "chat")
        assertEquals(text, body(output.last()))
    }

    @Test fun `Markdown over large budget remains precisely resumable`() = runBlocking {
        val text = "文档🚀".repeat(90000)
        val bytes = text.toByteArray()
        val first = OpsPages.read("/large.md", limit = Int.MAX_VALUE, budget = OpsBudget.MARKDOWN, fetch = source(bytes))
        assertNotNull(first.next)
        assertTrue(first.body.utf8Size() <= OpsBudget.MARKDOWN)
        val second = OpsPages.read("/large.md", first.next!!.encode(), limit = Int.MAX_VALUE, budget = OpsBudget.MARKDOWN, fetch = source(bytes))
        val third = second.next?.let { OpsPages.read("/large.md", it.encode(), limit = Int.MAX_VALUE, budget = OpsBudget.MARKDOWN, fetch = source(bytes)).body }.orEmpty()
        assertEquals(text, first.body + second.body + third)
    }

    @Test fun `native file range has no upstream eight MiB whole-file limit`() {
        val file = tmp.newFile("sparse.log")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(9L*1024*1024); it.seek(9L*1024*1024); it.write("tail".toByteArray()) }
        val page = file.readWorkspaceChunk(9L*1024*1024, 64)
        assertEquals("tail", String(page.bytes))
        assertTrue(page.size > 8L*1024*1024)
    }

}
