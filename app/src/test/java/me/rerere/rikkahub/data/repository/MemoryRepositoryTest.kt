package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.MemoryDocumentSnapshot
import me.rerere.rikkahub.data.model.groupByDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    private val dao = FakeMemoryDAO()
    private val repository = MemoryRepository(dao)
    private val assistant = "assistant"

    private suspend fun create(path: String, content: String): MemoryFile {
        val result = repository.writeFile(assistant, path, content, MemoryRepository.NEW_FILE_VERSION)
        return (result as MemoryWriteResult.Success).file!!
    }

    @Test
    fun `creating a file that already exists returns the current content`() = runBlocking {
        val file = create("/topics/food.md", "- [stated] Likes noodles\n")

        val result = repository.writeFile(assistant, "/topics/food.md", "other", MemoryRepository.NEW_FILE_VERSION)

        assertEquals(MemoryWriteResult.Conflict(file), result)
        assertEquals(file.content, repository.getFile(assistant, "/topics/food.md")?.content)
    }

    @Test
    fun `writing with a stale version is rejected and a fresh version succeeds`() = runBlocking {
        val first = create("/profile.md", "- [stated] Lives in Shenzhen\n")
        val second = (repository.appendToFile(assistant, "/profile.md", "- [stated] Works as a designer", first.version)
            as MemoryWriteResult.Success).file!!

        val stale = repository.appendToFile(assistant, "/profile.md", "- [stated] Stale", first.version)
        assertEquals(MemoryWriteResult.Conflict(second), stale)

        val fresh = repository.writeFile(assistant, "/profile.md", "rewritten", second.version)
        assertEquals("rewritten", (fresh as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `editing a missing file reports that it does not exist`() = runBlocking {
        val result = repository.appendToFile(assistant, "/topics/none.md", "line", "abc")

        assertEquals(MemoryWriteResult.Conflict(null), result)
    }

    @Test
    fun `append adds a line break when the file does not end with one`() = runBlocking {
        val file = create("/topics/food.md", "- first")

        val result = repository.appendToFile(assistant, "/topics/food.md", "- second\n", file.version)

        assertEquals("- first\n- second\n", (result as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `replacing a whole line with nothing removes the line`() = runBlocking {
        val file = create("/topics/food.md", "- keep one\n- forget me\n- keep two\n")

        val result = repository.replaceInFile(assistant, "/topics/food.md", "- forget me", "", file.version)

        assertEquals("- keep one\n- keep two\n", (result as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `replacing part of a line keeps the rest of the line`() = runBlocking {
        val file = create("/topics/work.md", "- [stated] On the search team\n")

        val result = repository.replaceInFile(
            assistant,
            "/topics/work.md",
            "On the search team",
            "On the infra team (previously on search)",
            file.version,
        )

        assertEquals(
            "- [stated] On the infra team (previously on search)\n",
            (result as MemoryWriteResult.Success).file?.content,
        )
    }

    @Test
    fun `replace is rejected when old_str matches nothing or several places`() = runBlocking {
        val file = create("/topics/food.md", "- tea\n- tea\n")

        val several = repository.replaceInFile(assistant, "/topics/food.md", "- tea", "- coffee", file.version)
        val none = repository.replaceInFile(assistant, "/topics/food.md", "- milk", "- coffee", file.version)

        assertTrue(several is MemoryWriteResult.Rejected)
        assertTrue(none is MemoryWriteResult.Rejected)
        assertEquals(file.content, repository.getFile(assistant, "/topics/food.md")?.content)
    }

    @Test
    fun `delete checks the version and removes the file`() = runBlocking {
        val file = create("/people/mom.md", "- [stated] Lives in Chengdu\n")

        assertEquals(MemoryWriteResult.Conflict(file), repository.deleteFile(assistant, "/people/mom.md", "stale"))
        assertEquals(MemoryWriteResult.Success(null), repository.deleteFile(assistant, "/people/mom.md", file.version))
        assertNull(repository.getFile(assistant, "/people/mom.md"))
    }

    @Test
    fun `oversized content and invalid paths are rejected`() = runBlocking {
        val tooLarge = repository.writeFile(assistant, "/topics/big.md", "a".repeat(MemoryFile.MAX_BYTES + 1))
        val notMarkdown = repository.writeFile(assistant, "/topics/food.txt", "content")
        val traversal = repository.writeFile(assistant, "/topics/../profile.md", "content")

        assertTrue(tooLarge is MemoryWriteResult.Rejected)
        assertTrue(notMarkdown is MemoryWriteResult.Rejected)
        assertTrue(traversal is MemoryWriteResult.Rejected)
        assertTrue(repository.getFiles(assistant).isEmpty())
    }

    @Test
    fun `an oversized file can shrink but not grow`() = runBlocking {
        // 迁移直接写库，不经过大小检查
        val lines = List(MemoryFile.MAX_BYTES / 1000 + 10) { "- [stated] Fact $it ${"x".repeat(1000)}\n" }
        dao.seedLegacyFile(MemoryFileEntity(assistant, "/profile.md", lines.joinToString(""), updatedAt = 0))
        val file = repository.getFile(assistant, "/profile.md")!!

        val append = repository.appendToFile(assistant, "/profile.md", "- [stated] More", file.version)
        assertTrue(append is MemoryWriteResult.Rejected)

        val trimmed = lines.drop(1).joinToString("")
        val removed = repository.replaceInFile(assistant, "/profile.md", lines.first().trimEnd(), "", file.version)
        assertEquals(trimmed, (removed as MemoryWriteResult.Success).file?.content)
        assertTrue(trimmed.toByteArray().size > MemoryFile.MAX_BYTES)

        // 用户在编辑页删掉一部分后保存，同样走 writeFile
        val edited = lines.drop(2).joinToString("")
        val saved = repository.writeFile(assistant, "/profile.md", edited)
        assertEquals(edited, (saved as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `paths without a leading slash are normalized`() = runBlocking {
        create("topics/food.md", "content")

        assertEquals(listOf("/topics/food.md"), repository.getFiles(assistant).map { it.path })
        assertEquals("content", repository.getFile(assistant, "topics/food.md")?.content)
    }

    @Test
    fun `memory stores are isolated and can be copied`() = runBlocking {
        create("/profile.md", "- [stated] Name is Lin\n")

        assertTrue(repository.getFiles("other").isEmpty())
        repository.copyFiles(assistant, "other")
        assertEquals("- [stated] Name is Lin\n", repository.getFile("other", "/profile.md")?.content)

        repository.deleteFilesOfAssistant(assistant)
        assertTrue(repository.getFiles(assistant).isEmpty())
        assertEquals(1, repository.getFiles("other").size)
    }

    @Test
    fun `description and aliases come from the frontmatter`() = runBlocking {
        val file = create(
            "/people/mom.md",
            """
                ---
                name: mom
                description: The user's mother and her move to Chengdu
                aliases: [妈妈, mother]
                ---

                - [stated] Lives in Chengdu
            """.trimIndent(),
        )

        assertEquals("The user's mother and her move to Chengdu", file.description)
        assertEquals(listOf("妈妈", "mother"), file.aliases)
    }

    @Test
    fun `files are grouped by directory with profile and preferences first`() = runBlocking {
        listOf(
            "/people/mom.md",
            "/topics/food.md",
            "/work/notes.md",
            "/preferences.md",
            "/areas/house-hunt.md",
            "/profile.md",
            "/topics/books.md",
        ).forEach { create(it, "content") }

        val groups = repository.getFiles(assistant).groupByDirectory()

        assertEquals(
            listOf(
                "/" to listOf("profile", "preferences"),
                "/topics" to listOf("books", "food"),
                "/areas" to listOf("house-hunt"),
                "/people" to listOf("mom"),
                "/work" to listOf("notes"),
            ),
            groups.map { (directory, files) -> directory to files.map { it.name } },
        )
    }

    @Test
    fun `titles are derived from the file name`() = runBlocking {
        assertEquals("Frontend Tooling", create("/topics/frontend-tooling.md", "content").title)
        assertEquals("Profile", create("/profile.md", "content").title)
        assertEquals("妈妈", create("/people/妈妈.md", "content").title)
    }

    @Test
    fun `a title in the frontmatter takes precedence over the file name`() = runBlocking {
        val food = create("/topics/food.md", "---\nname: food\ntitle: 饮食\n---\n\n- [stated] Likes noodles\n")
        assertEquals("饮食", food.title)
        assertEquals("Books", create("/topics/books.md", "---\nname: books\ntitle: \"\"\n---\n").title)
    }

    @Test
    fun `the stated marker is hidden from the displayed body`() = runBlocking {
        val file = create(
            "/topics/work.md",
            """
                ---
                name: work
                ---

                ## Work

                - [stated] On the infra team (previously on search)
                - [Stated] Works with [[alex]]
                - A line without the marker
            """.trimIndent(),
        )

        assertEquals(
            """
                ## Work

                - On the infra team (previously on search)
                - Works with [[alex]]
                - A line without the marker
            """.trimIndent(),
            file.displayBody.trim(),
        )
    }

    @Test
    fun `generation CAS serializes independent repositories`() = runBlocking {
        val writers = List(24) { MemoryRepository(dao) }
        val results = writers.mapIndexed { index, writer ->
            async(Dispatchers.Default) { writer.writeAtGeneration(assistant, "/profile.md", "writer $index", 0) }
        }.awaitAll()
        assertEquals(1, results.count { it is MemoryWriteResult.Success })
        assertEquals(23, results.count { it is MemoryWriteResult.Conflict })
        assertEquals(1L, repository.snapshot(assistant, "/profile.md").generation)
    }

    @Test
    fun `delete recreate preserves monotonic revision despite identical hash`() = runBlocking {
        val original = create("/profile.md", "same")
        assertEquals(1L, repository.snapshot(assistant, "/profile.md").generation)
        assertTrue(repository.writeAtGeneration(assistant, "/profile.md", null, 1) is MemoryWriteResult.Success)
        assertEquals(MemoryDocumentSnapshot("/profile.md", null, 2), repository.snapshot(assistant, "/profile.md"))
        assertTrue(repository.writeAtGeneration(assistant, "/profile.md", "same", 2) is MemoryWriteResult.Success)
        assertEquals(original.version, repository.getFile(assistant, "/profile.md")!!.version)
        assertTrue(repository.writeAtGeneration(assistant, "/profile.md", "stale", 1) is MemoryWriteResult.Conflict)
        assertTrue(repository.writeAtGeneration(assistant, "/profile.md", null, 2) is MemoryWriteResult.Conflict)
        assertEquals(MemoryDocumentSnapshot("/profile.md", "same", 3), repository.snapshot(assistant, "/profile.md"))
    }

    @Test
    fun `no ops do not advance and never existed is generation zero`() = runBlocking {
        assertEquals(MemoryDocumentSnapshot("/new.md", null, 0), repository.snapshot(assistant, "/new.md"))
        repository.writeAtGeneration(assistant, "/new.md", null, 0)
        assertTrue(repository.snapshots(assistant).isEmpty())
        create("/profile.md", "same")
        repository.writeFile(assistant, "/profile.md", "same")
        repository.writeAtGeneration(assistant, "/profile.md", "same", 1)
        assertEquals(1L, repository.snapshot(assistant, "/profile.md").generation)
        repository.writeAtGeneration(assistant, "/profile.md", null, 1)
        repository.writeAtGeneration(assistant, "/profile.md", null, 2)
        assertEquals(2L, repository.snapshot(assistant, "/profile.md").generation)
    }

    @Test
    fun `all direct DAO mutations copy and bulk deletion keep revisions`() = runBlocking {
        dao.upsertFile(MemoryFileEntity(assistant, "/a.md", "one", 1))
        dao.upsertFiles(listOf(MemoryFileEntity(assistant, "/b.md", "two", 2)))
        repository.copyFiles(assistant, "target")
        assertEquals(listOf(1L, 1L), repository.snapshots("target").map { it.generation })
        dao.upsertFile(MemoryFileEntity(assistant, "/a.md", "changed", 3))
        repository.copyFiles(assistant, "target")
        assertEquals(listOf(2L, 1L), repository.snapshots("target").map { it.generation })
        dao.deleteFilesOfAssistant("target")
        assertEquals(
            listOf(MemoryDocumentSnapshot("/a.md", null, 3), MemoryDocumentSnapshot("/b.md", null, 2)),
            repository.snapshots("target"),
        )
        repository.deleteFilesOfAssistant("target")
        assertEquals(listOf(3L, 2L), repository.snapshots("target").map { it.generation })
        assertEquals(2, repository.getFiles(assistant).size)
    }

    @Test
    fun `sensitive content policy cannot be bypassed by partial edits or DAO batches`() = runBlocking {
        create("/profile.md", "123-45-")
        assertTrue(repository.appendToFile(assistant, "/profile.md", "6789", null) is MemoryWriteResult.Success)
        // Replacement can form a sensitive value even when the replacement fragment itself is harmless.
        create("/ssn.md", "123-45-XXXX")
        assertTrue(repository.replaceInFile(assistant, "/ssn.md", "XXXX", "6789", null) is MemoryWriteResult.Rejected)
        assertTrue(repository.writeAtGeneration(assistant, "/new.md", "4111 1111 1111 1111", 0) is MemoryWriteResult.Rejected)
        try {
            dao.upsertFiles(
                listOf(
                    MemoryFileEntity(assistant, "/ok.md", "valid", 1),
                    MemoryFileEntity(assistant, "/bad.md", "123-45-6789", 1),
                )
            )
            throw AssertionError("Sensitive batch must be rejected")
        } catch (_: IllegalArgumentException) {
            assertNull(repository.getFile(assistant, "/ok.md"))
            assertEquals(0L, repository.snapshot(assistant, "/ok.md").generation)
        }
    }

    @Test
    fun `generation writes shrink legacy oversized UTF8 without truncation`() = runBlocking {
        val legacy = "记".repeat(MemoryFile.MAX_BYTES)
        dao.seedLegacyFile(MemoryFileEntity(assistant, "/profile.md", legacy, 0))
        val smaller = legacy.dropLast(1)
        assertTrue(repository.writeAtGeneration(assistant, "/profile.md", legacy + "a", 1) is MemoryWriteResult.Rejected)
        assertEquals(1L, repository.snapshot(assistant, "/profile.md").generation)
        assertTrue(repository.writeAtGeneration(assistant, "/profile.md", smaller, 1) is MemoryWriteResult.Success)
        assertEquals(MemoryDocumentSnapshot("/profile.md", smaller, 2), repository.snapshot(assistant, "/profile.md"))
    }
}
