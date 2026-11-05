package me.rerere.rikkahub.data.ai.tools.opsv2

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceReadChunk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercise the actual presentation pipeline, not only the unrendered page body. */
class OpsPagingIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val nextPattern = Regex("\\[next cursor=([^\\]]+)\\]")
    private fun message(id: String, output: UIMessagePart.Text, tool: String = "workspace_read_file") =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(id, tool, "{}", output = listOf(output))))
    private fun text(message: UIMessage) = ((message.parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text
    private fun markPage(body: String, source: String): UIMessagePart.Text {
        val part = managedText(body, source)
        val info = part.metadata!![OpsBudget.META]!!.jsonObject
        return part.copy(metadata = buildJsonObject { put(OpsBudget.META, JsonObject(info + ("paged" to JsonPrimitive(true)))) })
    }
    private fun fetch(bytes: ByteArray, stamp: String = "fixed"): suspend (Long, Int) -> WorkspaceReadChunk = { pos, max ->
        val from = pos.toInt().coerceAtMost(bytes.size)
        WorkspaceReadChunk(bytes.copyOfRange(from, minOf(from + max, bytes.size)), bytes.size.toLong(), stamp)
    }
    private fun oldHistory() = (1..14).map { i -> message("old$i", markPage("old$i\n" + "o".repeat(5500), "/old$i.txt")) }

    @Test fun `twenty KiB line round trips through rendered final requests`() = runBlocking {
        val original = "long line 文🚀 ".repeat(1600)
        val bytes = original.toByteArray()
        val store = ToolResultStore(tmp.newFolder())
        val projector = WorkspaceContextProjector(store)
        val history = mutableListOf<UIMessage>()
        val restored = StringBuilder()
        var cursor: String? = null
        var count = 0
        do {
            val page = OpsPages.read("/long.txt", cursor, fetch = fetch(bytes))
            val rendered = page.render()
            history += message("read${count++}", markPage(rendered, "/long.txt"))
            val visible = text(projector.project(history, "chat").last())
            assertEquals("The final projection must retain the entire rendered page", rendered, visible)
            assertFalse(visible.contains("[limited; ref="))
            cursor = nextPattern.find(visible)?.groupValues?.get(1)
            if (page.next != null) assertNotNull("Cursor must survive the final request", cursor)
            restored.append(page.body)
            assertTrue(count < 30)
        } while (cursor != null)
        assertEquals(original, restored.toString())
    }

    @Test fun `full multiline page plus labels and cursor is not previewed`() = runBlocking {
        val original = (1..500).joinToString("\n") { "row $it " + "x".repeat(70) }
        val page = OpsPages.read("/many.txt", fetch = fetch(original.toByteArray()))
        val rendered = page.render()
        assertTrue(rendered.utf8Size() > OpsBudget.PAGE)
        val input = message("read", markPage(rendered, "/many.txt"))
        val visible = text(WorkspaceContextProjector(ToolResultStore(tmp.newFolder())).project(listOf(input), "chat").single())
        assertEquals(rendered, visible)
        assertNotNull(nextPattern.find(visible))
    }

    @Test fun `legacy v2 page metadata without paged flag remains compatible`() = runBlocking {
        val page = OpsPages.read("/legacy.txt", fetch = fetch("x".repeat(20000).toByteArray()))
        val rendered = page.render()
        val input = message("read", managedText(rendered, "/legacy.txt"))
        val visible = text(WorkspaceContextProjector(ToolResultStore(tmp.newFolder())).project(listOf(input), "chat").single())
        assertEquals(rendered, visible)
    }

    @Test fun `native result tool restores every byte without reference nesting under history pressure`() = runBlocking {
        val store = ToolResultStore(tmp.newFolder())
        val original = "x".repeat(20000) + "TAIL_END"
        val ref = store.put("chat", "/source", original)
        val tool = createV2ResultTool(store, "chat", tmp.newFolder())
        val projector = WorkspaceContextProjector(store)
        val history = oldHistory().toMutableList()
        val restored = StringBuilder()
        var cursor: String? = null
        var count = 0
        do {
            val args = buildJsonObject { put("ref", ref); cursor?.let { put("cursor", it) } }
            val part = tool.execute(args).single() as UIMessagePart.Text
            history += message("restore${count++}", part, "workspace_result")
            val visible = text(projector.project(history, "chat").last())
            assertEquals("Freshly requested result must be visible", part.text, visible)
            assertFalse(visible.startsWith("[archived"))
            assertFalse(visible.contains("[limited; ref="))
            assertFalse(visible.startsWith("[same body"))
            cursor = nextPattern.find(visible)?.groupValues?.get(1)
            val afterHeaders = visible.substringAfter('\n').substringAfter('\n')
            val body = if (cursor == null) afterHeaders else afterHeaders.substringBeforeLast("\n[next cursor=")
            restored.append(body.replace(Regex("^\\d+\\| "), ""))
            assertTrue(count < 10)
        } while (cursor != null)
        assertEquals(original, restored.toString())
    }

    @Test fun `repeated explicit restore is not replaced by dedup receipt`() = runBlocking {
        val store = ToolResultStore(tmp.newFolder())
        val ref = store.put("chat", "/source", "x".repeat(20000))
        val tool = createV2ResultTool(store, "chat", tmp.newFolder())
        val args = buildJsonObject { put("ref", ref) }
        val first = tool.execute(args).single() as UIMessagePart.Text
        val again = tool.execute(args).single() as UIMessagePart.Text
        val output = WorkspaceContextProjector(store).project(listOf(message("first", first, "workspace_result"), message("again", again, "workspace_result")), "chat")
        assertEquals(again.text, text(output.last()))
    }

    @Test fun `large control header page is reserved before history budget allocation`() = runBlocking {
        val path = "/" + "p".repeat(3500)
        val page = OpsPages.read(path, fetch = fetch("x".repeat(20000).toByteArray(), "s".repeat(3500)))
        val rendered = page.render()
        assertTrue(rendered.utf8Size() > OpsBudget.HISTORY / 2)
        val history = oldHistory() + message("latest", markPage(rendered, path))
        val output = WorkspaceContextProjector(ToolResultStore(tmp.newFolder())).project(history, "chat")
        assertEquals(rendered, text(output.last()))
        assertTrue(output.dropLast(1).any { text(it).startsWith("[archived") })
        val retained = output.map(::text).filterNot { it.startsWith("[archived") || it.startsWith("[same body") }
        assertTrue(retained.sumOf { it.utf8Size() } <= OpsBudget.HISTORY)
    }

    @Test fun `result labels refer to snapshot lines rather than source lines`() = runBlocking {
        val store = ToolResultStore(tmp.newFolder())
        val ref = store.put("chat", "/source", "captured source row 29997")
        val output = createV2ResultTool(store, "chat", tmp.newFolder()).execute(buildJsonObject { put("ref", ref) }).single() as UIMessagePart.Text
        assertTrue(output.text.contains("snapshot line 1"))
        assertFalse(output.text.contains("source line 1"))
    }
}
