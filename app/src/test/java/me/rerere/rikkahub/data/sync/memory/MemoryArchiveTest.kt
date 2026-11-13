package me.rerere.rikkahub.data.sync.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MemoryArchiveTest {
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun export(documents: List<MemoryArchiveDocument>): ByteArray = ByteArrayOutputStream().also {
        MemoryArchiveFormat.writeZip(it, documents, "assistant")
    }.toByteArray()

    private fun reject(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Invalid archive must be rejected", rejected)
    }

    private fun read(bytes: ByteArray) = MemoryArchiveFormat.readZip(ByteArrayInputStream(bytes))

    @Test fun `roundtrip preserves frontmatter unknown fields stated and CRLF`() {
        val documents = listOf(MemoryArchiveDocument("/topics/原文.md", "---\r\nunknown: [one, two]\r\n---\r\n- [stated] 原文\r\n"), MemoryArchiveDocument("/empty.md", ""))
        assertEquals(documents, read(export(documents)))
    }

    @Test fun `path mapping and complete sha256 describe actual bytes`() {
        val document = MemoryArchiveDocument("/topics/a.md", "你好\r\n")
        val entry = MemoryArchiveFormat.manifest(listOf(document), "global").entries.single()
        assertEquals("memories/topics/a.md", entry.file)
        assertEquals(document.content.toByteArray().size.toLong(), entry.bytes)
        assertEquals(64, entry.sha256.length)
        assertEquals(MemoryArchiveFormat.sha256(document.content.toByteArray()), entry.sha256)
    }

    @Test fun `zip rejects traversal absolute names hidden names and unknown members`() {
        listOf("../escape.md", "/escape.md", "memories/../a.md", "memories/.hidden.md", "memories/a\\b.md", "other/a.md", "memories/a.txt").forEach { path ->
            reject { read(zip(listOf(path to "x".toByteArray()))) }
        }
    }

    @Test fun `strict UTF8 rejects malformed markdown and names`() {
        reject { MemoryArchiveFormat.readMarkdown(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), "/a.md") }
        val bytes = zip(listOf("memories/a.md" to byteArrayOf()))
        val badName = bytes.copyOf()
        for (index in 0 until badName.size - 12) {
            if (badName.copyOfRange(index, index + 12).contentEquals("memories/a.m".toByteArray())) badName[index + 9] = 0xff.toByte()
        }
        reject { read(badName) }
    }

    @Test fun `manifest hashes lengths mappings versions missing and extra files are checked`() {
        val documents = listOf(MemoryArchiveDocument("/a.md", "raw"))
        val manifest = MemoryArchiveFormat.manifest(documents, "assistant")
        fun entries(value: MemoryArchiveManifest, payload: ByteArray = "raw".toByteArray()) = listOf(
            "manifest.json" to Json.encodeToString(value).toByteArray(), "memories/a.md" to payload)
        reject { read(zip(entries(manifest, "tampered".toByteArray()))) }
        reject { read(zip(entries(manifest.copy(formatVersion = 2)))) }
        reject { read(zip(entries(manifest.copy(entries = manifest.entries.map { it.copy(file = "memories/b.md") })))) }
        reject { read(zip(entries(manifest.copy(entries = manifest.entries.map { it.copy(bytes = 99) })))) }
        reject { read(zip(entries(manifest) + ("memories/extra.md" to byteArrayOf()))) }
        reject { read(zip(entries(manifest).take(1))) }
        reject { read(zip(entries(manifest.copy(entries = manifest.entries + manifest.entries)))) }
        val badUtf8 = byteArrayOf(0xc0.toByte(), 0xaf.toByte())
        val invalidManifest = manifest.copy(entries = listOf(MemoryArchiveEntry("/a.md", "memories/a.md", MemoryArchiveFormat.sha256(badUtf8), 2)))
        reject { read(zip(entries(invalidManifest, badUtf8))) }
    }

    @Test fun `casefold unicode and normalized path collisions are rejected`() {
        reject { export(listOf(MemoryArchiveDocument("/A.md", "a"), MemoryArchiveDocument("/a.md", "b"))) }
        reject { export(listOf(MemoryArchiveDocument("/ß.md", "a"), MemoryArchiveDocument("/ss.md", "b"))) }
        reject { export(listOf(MemoryArchiveDocument("/é.md", "a"), MemoryArchiveDocument("/e\u0301.md", "b"))) }
        listOf("a.md", "/ a.md", "/a/../b.md", "/a//b.md", "/a:b.md").forEach { reject { MemoryArchiveFormat.strictPath(it) } }
    }

    @Test fun `zip duplicate members and Unix symlinks are rejected before importing`() {
        val bytes = zip(listOf("memories/a.md" to byteArrayOf(), "memories/b.md" to byteArrayOf()))
        val duplicate = bytes.copyOf()
        val needle = "memories/b.md".toByteArray()
        for (index in 0..duplicate.size - needle.size) {
            if (duplicate.copyOfRange(index, index + needle.size).contentEquals(needle)) duplicate[index + 9] = 'a'.code.toByte()
        }
        reject { read(duplicate) }
        val symlink = bytes.copyOf()
        val central = (0 until symlink.size - 46).first { index ->
            symlink[index] == 0x50.toByte() && symlink[index + 1] == 0x4b.toByte() && symlink[index + 2] == 1.toByte() && symlink[index + 3] == 2.toByte()
        }
        symlink[central + 5] = 3
        symlink[central + 40] = 0xff.toByte()
        symlink[central + 41] = 0xa1.toByte()
        reject { read(symlink) }
    }

    @Test fun `budgets reject too many documents oversized streams and incomplete zip`() {
        reject { read(export((0..MemoryArchiveFormat.MAX_DOCUMENTS).map { MemoryArchiveDocument("/$it.md", "") })) }
        reject { MemoryArchiveFormat.readBounded(ByteArrayInputStream(ByteArray(9)), 8) }
        reject { read(export(listOf(MemoryArchiveDocument("/a.md", "a"))).dropLast(1).toByteArray()) }
        val compressedBomb = zip(listOf("memories/a.md" to ByteArray(MemoryArchiveFormat.MAX_BYTES + 1)))
        reject { read(compressedBomb) }
    }

    @Test fun `snapshot always creates distinct directories and preserves raw contents`() {
        val root = Files.createTempDirectory("memory-archive-test").toFile()
        try {
            val documents = listOf(MemoryArchiveDocument("/topics/a.md", "raw\r\n[stated]"))
            val first = MemoryArchiveFormat.writeSnapshot(root, documents, "global")
            val second = MemoryArchiveFormat.writeSnapshot(root, documents, "global")
            assertFalse(first == second)
            assertEquals(documents.single().content, first.resolve("memories/topics/a.md").readText())
            assertTrue(first.resolve("manifest.json").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun `preview then ABA race cannot overwrite and unrelated paths continue`() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDAO())
        val archive = MemoryArchive(repository)
        repository.writeAtGeneration("scope", "/a.md", "old", 0)
        val preview = archive.preview("scope", listOf(MemoryArchiveDocument("/a.md", "import"), MemoryArchiveDocument("/b.md", "new")))
        val before = repository.snapshot("scope", "/a.md")
        repository.writeAtGeneration("scope", "/a.md", null, before.generation)
        repository.writeAtGeneration("scope", "/a.md", "old", repository.snapshot("scope", "/a.md").generation)
        val results = archive.apply("scope", preview, setOf("/a.md"))
        assertEquals(listOf(MemoryImportStatus.CONFLICT, MemoryImportStatus.WRITTEN), results.map { it.status })
        assertEquals("old", repository.snapshot("scope", "/a.md").content)
        assertEquals("new", repository.snapshot("scope", "/b.md").content)
        assertEquals(null, repository.snapshot("other", "/b.md").content)
    }

    @Test fun `default import skips changes retains absent paths and rerun is idempotent`() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDAO())
        val archive = MemoryArchive(repository)
        repository.writeAtGeneration("scope", "/a.md", "existing", 0)
        repository.writeAtGeneration("scope", "/keep.md", "keep", 0)
        val documents = listOf(MemoryArchiveDocument("/a.md", "different"), MemoryArchiveDocument("/b.md", "new"))
        val first = archive.apply("scope", archive.preview("scope", documents))
        assertEquals(listOf(MemoryImportStatus.SKIPPED, MemoryImportStatus.WRITTEN), first.map { it.status })
        val generation = repository.snapshot("scope", "/b.md").generation
        val second = archive.apply("scope", archive.preview("scope", documents))
        assertEquals(MemoryImportStatus.UNCHANGED, second.last().status)
        assertEquals(generation, repository.snapshot("scope", "/b.md").generation)
        assertEquals("keep", repository.snapshot("scope", "/keep.md").content)
    }

    @Test fun `legacy oversized raw memory exports but new oversized import reports per path failure`() = runBlocking {
        val dao = FakeMemoryDAO()
        val legacy = "x".repeat(48 * 1024 + 10)
        dao.seedLegacyFile(MemoryFileEntity("scope", "/old.md", legacy, 0))
        val repository = MemoryRepository(dao)
        val archive = MemoryArchive(repository)
        assertEquals(legacy, read(export(archive.documents("scope"))).single().content)
        val preview = archive.preview("other", archive.documents("scope"))
        assertEquals(MemoryImportKind.REJECTED, preview.single().kind)
        assertEquals(MemoryImportStatus.FAILED, archive.apply("other", preview).single().status)
        assertEquals(null, repository.snapshot("other", "/old.md").content)
        val shrink = archive.preview("scope", listOf(MemoryArchiveDocument("/old.md", legacy.dropLast(1))))
        assertEquals(MemoryImportKind.CHANGED, shrink.single().kind)
        assertEquals(MemoryImportStatus.WRITTEN, archive.apply("scope", shrink, setOf("/old.md")).single().status)
    }
}
