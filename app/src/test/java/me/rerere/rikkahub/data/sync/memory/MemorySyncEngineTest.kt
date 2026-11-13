package me.rerere.rikkahub.data.sync.memory

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

/** Real Python RPC subprocesses exercise the same wire contract as the SSH client. */
class MemorySyncEngineTest {
    @get:Rule val temporary = TemporaryFolder()

    private class PythonRemote(private val root: File) : MemorySyncRemote {
        private val helper = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "tools/memory-sync/memory_sync.py") }.first { it.isFile }
        override suspend fun request(binding: MemorySyncBinding, request: MemoryRpcRequest): MemoryRpcResponse {
            val process = ProcessBuilder("python3", helper.absolutePath, "rpc", "--root", root.absolutePath).start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(MemorySyncJson.encodeToString(request)); it.newLine() }
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val error = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
            check(process.waitFor() == 0) { "RPC failed: $error" }
            return MemorySyncJson.decodeFromString(output)
        }
    }

    private inner class Fixture(wrap: ((MemorySyncRemote, MemoryRepository) -> MemorySyncRemote)? = null) {
        val repository = MemoryRepository(FakeMemoryDAO())
        val directory = temporary.newFolder()
        val store = MemorySyncStore(directory)
        val python = PythonRemote(temporary.newFolder())
        val remote = wrap?.invoke(python, repository) ?: python
        val binding = MemorySyncBinding("test-library", "ssh-fixture", temporary.newFolder().absolutePath, UUID.randomUUID().toString())
        val engine = MemorySyncEngine(repository, store, remote)
        suspend fun connect() = engine.bind(binding, true)
        suspend fun remoteDocument(path: String = "/profile.md"): MemoryRemoteDocument = requireNotNull(python.request(
            binding, MemoryRpcRequest(op = "read", spaceId = binding.spaceId, path = path),
        ).document)
        suspend fun remoteWrite(content: String?, path: String = "/profile.md") {
            val old = remoteDocument(path)
            val response = python.request(binding, MemoryRpcRequest(
                op = if (content == null) "delete" else "put", spaceId = binding.spaceId, path = path,
                expectedToken = old.token, content = content, operationId = UUID.randomUUID().toString(),
            ))
            assertTrue(response.error?.message, response.ok)
        }
    }

    @Test fun markdownRoundTripPreservesRawContentAndDoesNotEcho() = runBlocking {
        val f = Fixture()
        val original = "---\r\ntitle: 用户\r\ncustom: retained\r\n---\r\n- [stated] 原始内容\r\n"
        f.repository.writeFile(f.binding.memoryId, "/profile.md", original)
        f.connect(); f.engine.sync(f.binding.memoryId)
        assertEquals(original, f.remoteDocument().content)
        val edited = original + "服务器修改\r\n"
        f.remoteWrite(edited)
        f.engine.sync(f.binding.memoryId)
        assertEquals(edited, f.repository.snapshot(f.binding.memoryId, "/profile.md").content)
        val generation = f.repository.snapshot(f.binding.memoryId, "/profile.md").generation
        val token = f.remoteDocument().token
        f.engine.sync(f.binding.memoryId)
        assertEquals(generation, f.repository.snapshot(f.binding.memoryId, "/profile.md").generation)
        assertEquals(token, f.remoteDocument().token)
    }

    @Test fun divergentEditsKeepBothSidesUntilExplicitVersionedResolution() = runBlocking {
        val f = Fixture(); val id = f.binding.memoryId
        f.repository.writeFile(id, "/profile.md", "base")
        f.connect(); f.engine.sync(id)
        f.repository.writeFile(id, "/profile.md", "app edit")
        f.remoteWrite("server edit")
        f.engine.sync(id)
        val conflict = f.store.snapshot().libraries.getValue(id).conflicts.getValue("/profile.md")
        assertEquals("base", conflict.baseContent)
        assertEquals("app edit", conflict.localContent)
        assertEquals("server edit", conflict.remoteContent)
        assertEquals("app edit", f.repository.snapshot(id, "/profile.md").content)
        assertEquals("server edit", f.remoteDocument().content)
        f.engine.resolve(id, "/profile.md", "both edits merged", conflict.localGeneration, conflict.remoteToken)
        assertEquals("both edits merged", f.remoteDocument().content)
        assertEquals("both edits merged", f.repository.snapshot(id, "/profile.md").content)
        assertTrue(f.store.snapshot().libraries.getValue(id).conflicts.isEmpty())
    }

    @Test fun pausedAutomaticBindingStillAllowsAnExplicitOneShotSync() = runBlocking {
        val f = Fixture(); val id = f.binding.memoryId
        f.repository.writeFile(id, "/profile.md", "explicitly publish")
        f.connect()
        f.engine.setEnabled(id, false)
        f.engine.sync(id)
        assertNull(f.remoteDocument().content)
        f.engine.sync(id, force = true)
        assertEquals("explicitly publish", f.remoteDocument().content)
        assertFalse(f.store.snapshot().libraries.getValue(id).binding.enabled)
    }

    @Test fun resolvingAnOldVisibleConflictCannotUseABackgroundRefreshedToken() = runBlocking {
        val f = Fixture(); val id = f.binding.memoryId
        f.repository.writeFile(id, "/profile.md", "base")
        f.connect(); f.engine.sync(id)
        f.repository.writeFile(id, "/profile.md", "local")
        f.remoteWrite("first remote edit")
        f.engine.sync(id)
        val viewed = f.store.snapshot().libraries.getValue(id).conflicts.getValue("/profile.md")
        f.remoteWrite("second remote edit")
        f.engine.sync(id)
        try {
            f.engine.resolve(id, "/profile.md", "stale resolution", viewed.localGeneration, viewed.remoteToken)
            fail("Expected stale visible conflict rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals("local", f.repository.snapshot(id, "/profile.md").content)
        assertEquals("second remote edit", f.remoteDocument().content)
    }

    @Test fun deletingOneSideProducesTombstoneAndDoesNotResurrect() = runBlocking {
        val f = Fixture(); val id = f.binding.memoryId
        f.repository.writeFile(id, "/profile.md", "base")
        f.connect(); f.engine.sync(id)
        f.repository.deleteFile(id, "/profile.md")
        f.engine.sync(id)
        assertNull(f.remoteDocument().content)
        val deletedToken = f.remoteDocument().token
        f.engine.sync(id)
        assertNull(f.repository.snapshot(id, "/profile.md").content)
        assertEquals(deletedToken, f.remoteDocument().token)
        f.remoteWrite("recreated")
        f.engine.sync(id)
        assertEquals("recreated", f.repository.snapshot(id, "/profile.md").content)
    }

    @Test fun lostCommitResponseRecoversReceiptWithoutDuplicateWrite() = runBlocking {
        var loseReply = true
        val f = Fixture { delegate, _ -> MemorySyncRemote { binding, request ->
            val response = delegate.request(binding, request)
            if (request.op == "put" && loseReply && response.ok) {
                loseReply = false
                throw IOException("response lost after commit")
            }
            response
        } }
        val id = f.binding.memoryId
        f.repository.writeFile(id, "/profile.md", "durable content")
        f.connect()
        try { f.engine.sync(id); fail("Expected disconnected response") } catch (_: IOException) { }
        assertEquals(1, f.store.snapshot().libraries.getValue(id).pending.size)
        val token = f.remoteDocument().token
        val restarted = MemorySyncEngine(f.repository, MemorySyncStore(f.directory), f.remote)
        restarted.sync(id)
        assertEquals(token, f.remoteDocument().token)
        assertTrue(MemorySyncStore(f.directory).snapshot().libraries.getValue(id).pending.isEmpty())
    }

    @Test fun localEditDuringUploadRemainsPendingForNextPass() = runBlocking {
        var editDuringUpload = true
        val f = Fixture { delegate, repository -> MemorySyncRemote { binding, request ->
            val response = delegate.request(binding, request)
            if (request.op == "put" && editDuringUpload && response.ok) {
                editDuringUpload = false
                repository.writeFile(binding.memoryId, "/profile.md", "newer app content")
            }
            response
        } }
        val id = f.binding.memoryId
        f.repository.writeFile(id, "/profile.md", "first content")
        f.connect(); f.engine.sync(id)
        assertEquals("newer app content", f.repository.snapshot(id, "/profile.md").content)
        f.engine.sync(id)
        assertEquals("newer app content", f.remoteDocument().content)
    }

    @Test fun restoredBackupPausesBindingAndNeverReplaysOldOperation() = runBlocking {
        val f = Fixture(); f.connect()
        MemorySyncStore.markRestore(f.directory)
        val restored = MemorySyncStore(f.directory).snapshot().libraries.getValue(f.binding.memoryId)
        assertFalse(restored.binding.enabled)
        assertTrue(restored.baselines.isEmpty())
        assertTrue(restored.pending.isEmpty())
        assertTrue(restored.status.error!!.contains("备份"))
    }

    @Test fun missingKnownRemoteTombstoneIsNotInterpretedAsDelete() = runBlocking {
        var hideDocuments = false
        val f = Fixture { delegate, _ -> MemorySyncRemote { binding, request ->
            val response = delegate.request(binding, request)
            if (hideDocuments && request.op == "list") response.copy(documents = emptyList()) else response
        } }
        val id = f.binding.memoryId
        f.repository.writeFile(id, "/profile.md", "keep me")
        f.connect(); f.engine.sync(id)
        hideDocuments = true
        try { f.engine.sync(id); fail("Expected incomplete remote state failure") } catch (_: IllegalArgumentException) { }
        assertEquals("keep me", f.repository.snapshot(id, "/profile.md").content)
    }
}
