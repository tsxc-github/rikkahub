package me.rerere.rikkahub

import android.content.Intent
import android.graphics.Rect
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.memory.MemoryArchive
import me.rerere.rikkahub.data.sync.memory.MemoryArchiveDocument
import me.rerere.rikkahub.data.sync.memory.MemoryArchiveFormat
import me.rerere.rikkahub.data.sync.memory.MemoryImportKind
import me.rerere.rikkahub.data.sync.memory.MemoryImportStatus
import me.rerere.rikkahub.data.sync.memory.MemorySyncManager
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshHostKey
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real Android Room, repository, archive streams and production exchange dialog; no network/user DB. */
@RunWith(AndroidJUnit4::class)
class MemorySyncDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository
    private lateinit var archive: MemoryArchive
    private var scenario: ActivityScenario<MemorySyncProbeActivity>? = null

    @Before
    fun setUp() {
        check(context.packageName == "me.rerere.rikkahub.memoryprobe") { "Refusing to test a nonisolated package" }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        repository = MemoryRepository(database.memoryDao())
        archive = MemoryArchive(repository)
    }

    @After
    fun tearDown() {
        scenario?.close()
        database.close()
    }

    @Test
    fun zipRoundTripPreservesRawMarkdownAndScopeIsolation() = runBlocking {
        val source = MemoryRepository.GLOBAL_MEMORY_ID
        val target = "archive-target"
        val raw = MemorySyncProbeActivity.GLOBAL_CONTENT
        assertSuccess(repository.writeAtGeneration(source, "/topics/tea.md", raw, 0))
        assertSuccess(repository.writeAtGeneration(target, "/kept.md", "Keep unrelated memory", 0))
        val bytes = ByteArrayOutputStream().also { MemoryArchiveFormat.writeZip(it, archive.documents(source), "global") }.toByteArray()
        val documents = MemoryArchiveFormat.readZip(ByteArrayInputStream(bytes))
        assertEquals(listOf(MemoryArchiveDocument("/topics/tea.md", raw)), documents)
        assertEquals(MemoryImportStatus.WRITTEN, archive.apply(target, archive.preview(target, documents)).single().status)
        assertEquals(raw, repository.getFile(target, "/topics/tea.md")!!.content)
        assertEquals("Keep unrelated memory", repository.getFile(target, "/kept.md")!!.content)
        assertEquals(raw, repository.getFile(source, "/topics/tea.md")!!.content)
        val generation = repository.snapshot(target, "/topics/tea.md").generation
        assertEquals(MemoryImportStatus.UNCHANGED, archive.apply(target, archive.preview(target, documents)).single().status)
        assertEquals(generation, repository.snapshot(target, "/topics/tea.md").generation)
    }

    @Test
    fun markdownStreamUsesExplicitTargetAndStrictUtf8() = runBlocking {
        val raw = "---\r\ncustom: 保留\r\n---\r\n# 原文 🫖\r\n"
        val documents = MemoryArchiveFormat.readMarkdown(ByteArrayInputStream(raw.toByteArray()), "/people/fixture.md")
        assertEquals(MemoryImportStatus.WRITTEN, archive.apply("target", archive.preview("target", documents)).single().status)
        assertEquals(raw, repository.getFile("target", "/people/fixture.md")!!.content)
        expectRejected { MemoryArchiveFormat.readMarkdown(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), "/bad.md") }
        assertNull(repository.getFile("target", "/bad.md"))
    }

    @Test
    fun previewRequiresConsentAndDoesNotOverwriteConcurrentEdits() = runBlocking {
        assertSuccess(repository.writeAtGeneration("target", "/a.md", "original", 0))
        val documents = listOf(MemoryArchiveDocument("/a.md", "imported"))
        val preview = archive.preview("target", documents)
        assertEquals(MemoryImportKind.CHANGED, preview.single().kind)
        assertEquals(MemoryImportStatus.SKIPPED, archive.apply("target", preview).single().status)
        assertSuccess(repository.writeAtGeneration("target", "/a.md", "concurrent", preview.single().expectedGeneration))
        assertEquals(MemoryImportStatus.CONFLICT, archive.apply("target", preview, setOf("/a.md")).single().status)
        assertEquals("concurrent", repository.getFile("target", "/a.md")!!.content)
        assertEquals(MemoryImportStatus.WRITTEN, archive.apply("target", archive.preview("target", documents), setOf("/a.md")).single().status)
        assertEquals("imported", repository.getFile("target", "/a.md")!!.content)
    }

    @Test
    fun deletionTombstonesPreventAbaAcrossRepositoryInstances() = runBlocking {
        val second = MemoryRepository(database.memoryDao())
        assertSuccess(repository.writeAtGeneration("scope", "/a.md", "same", 0))
        val original = repository.getFile("scope", "/a.md")!!
        val generation = repository.snapshot("scope", "/a.md").generation
        assertSuccess(second.writeAtGeneration("scope", "/a.md", null, generation))
        val deleted = second.snapshot("scope", "/a.md")
        assertNull(deleted.content)
        assertTrue(deleted.generation > generation)
        assertSuccess(second.writeAtGeneration("scope", "/a.md", "same", deleted.generation))
        assertEquals(original.version, second.getFile("scope", "/a.md")!!.version)
        assertTrue(repository.writeAtGeneration("scope", "/a.md", "stale", generation) is MemoryWriteResult.Conflict)
        assertEquals("same", repository.getFile("scope", "/a.md")!!.content)
    }

    @Test
    fun concurrentRoomWritersCommitExactlyOneGeneration() = runBlocking {
        val second = MemoryRepository(database.memoryDao())
        val results = listOf(repository, second).mapIndexed { index, repo ->
            async(Dispatchers.IO) { repo.writeAtGeneration("scope", "/race.md", "writer-$index", 0) }
        }.awaitAll()
        assertEquals(1, results.count { it is MemoryWriteResult.Success })
        assertEquals(1, results.count { it is MemoryWriteResult.Conflict })
        assertEquals(1L, repository.snapshot("scope", "/race.md").generation)
    }

    @Test
    fun invalidArchivePreflightNeverWritesAnyDocument() = runBlocking {
        expectRejectedSuspend {
            archive.preview("scope", listOf(MemoryArchiveDocument("/good.md", "good"), MemoryArchiveDocument("/../escape.md", "bad")))
        }
        expectRejectedSuspend {
            archive.preview("scope", listOf(MemoryArchiveDocument("/A.md", "one"), MemoryArchiveDocument("/a.md", "two")))
        }
        val zip = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { stream ->
                stream.putNextEntry(ZipEntry("../escape.md")); stream.write("bad".toByteArray()); stream.closeEntry()
            }
        }.toByteArray()
        expectRejected { MemoryArchiveFormat.readZip(ByteArrayInputStream(zip)) }
        assertTrue(repository.snapshots("scope").isEmpty())
    }

    @Test
    fun importReportsPartialFailuresAndRepositoryEnforcesContentGuard() = runBlocking {
        val documents = listOf(
            MemoryArchiveDocument("/good.md", "ordinary memory"),
            MemoryArchiveDocument("/too-large.md", "x".repeat(MemoryFile.MAX_BYTES + 1)),
            MemoryArchiveDocument("/blocked.md", "Test-only payment card fixture: 4111 1111 1111 1111"),
        )
        val result = archive.apply("scope", archive.preview("scope", documents)).associateBy { it.path }
        assertEquals(MemoryImportStatus.WRITTEN, result.getValue("/good.md").status)
        assertEquals(MemoryImportStatus.FAILED, result.getValue("/too-large.md").status)
        assertEquals(MemoryImportStatus.FAILED, result.getValue("/blocked.md").status)
        assertEquals(listOf("/good.md"), repository.getFiles("scope").map { it.path })
        assertEquals(0L, repository.snapshot("scope", "/blocked.md").generation)
    }

    @Test
    fun legacyOversizeExportsLosslesslyAndCanShrinkButCannotGrow() = runBlocking {
        val legacy = "l".repeat(MemoryFile.MAX_BYTES + 100)
        // Historical fixture, deliberately bypassing today's guarded writer in this disposable DB only.
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO memory_file(assistant_id,path,content,updated_at) VALUES(?,?,?,?)",
            arrayOf<Any>("legacy", "/large.md", legacy, 1L),
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO memory_revision(assistant_id,path,generation,deleted) VALUES(?,?,?,?)",
            arrayOf<Any>("legacy", "/large.md", 1L, 0),
        )
        val exported = ByteArrayOutputStream().also { MemoryArchiveFormat.writeZip(it, archive.documents("legacy"), "assistant") }.toByteArray()
        assertEquals(legacy, MemoryArchiveFormat.readZip(ByteArrayInputStream(exported)).single().content)
        assertSuccess(repository.writeAtGeneration("legacy", "/large.md", legacy.dropLast(1), 1))
        val generation = repository.snapshot("legacy", "/large.md").generation
        assertTrue(repository.writeAtGeneration("legacy", "/large.md", legacy + "growth", generation) is MemoryWriteResult.Rejected)
        assertEquals(legacy.dropLast(1), repository.getFile("legacy", "/large.md")!!.content)
    }

    @Test
    fun diskSnapshotsAreFreshAndContainExactBytesAndManifest() = runBlocking {
        val root = File(context.cacheDir, "memory-sync-snapshot-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val documents = listOf(MemoryArchiveDocument("/topics/raw.md", MemorySyncProbeActivity.GLOBAL_CONTENT))
            val first = MemoryArchiveFormat.writeSnapshot(root, documents, "global")
            val second = MemoryArchiveFormat.writeSnapshot(root, documents, "global")
            assertTrue(first != second)
            assertEquals(documents.single().content, File(first, "memories/topics/raw.md").readText(Charsets.UTF_8))
            val files = first.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(first).invariantSeparatorsPath to it.readBytes()
            }
            assertEquals(documents, MemoryArchiveFormat.readManifestFiles(files))
            assertTrue(File(second, "manifest.json").isFile)
        } finally { assertTrue(root.deleteRecursively()) }
    }

    @Test
    fun roomReopenRetainsContentGenerationAndTombstones() = runBlocking {
        val name = "memory-sync-device-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        try {
            val firstDb = open()
            try {
                val first = MemoryRepository(firstDb.memoryDao())
                assertSuccess(first.writeAtGeneration("scope", "/gone.md", "delete me", 0))
                assertSuccess(first.writeAtGeneration("scope", "/gone.md", null, 1))
                assertSuccess(first.writeAtGeneration("scope", "/kept.md", "keep me", 0))
            } finally { firstDb.close() }
            val secondDb = open()
            try {
                val second = MemoryRepository(secondDb.memoryDao())
                assertNull(second.snapshot("scope", "/gone.md").content)
                assertEquals(2L, second.snapshot("scope", "/gone.md").generation)
                assertEquals("keep me", second.snapshot("scope", "/kept.md").content)
                assertTrue(second.writeAtGeneration("scope", "/gone.md", "stale", 0) is MemoryWriteResult.Conflict)
            } finally { secondDb.close() }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }
    @Test
    fun realSshManagerBindsPushesPullsResolvesConflictAndPropagatesDeletion() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("memorySyncRunId") ?: "manual-${UUID.randomUUID()}"
        require(runId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val fixtureFile = File(context.filesDir, "memory-sync-fixture-$runId.json")
        val expected = arguments.getString("memorySyncSshExpected") == "true"
        if (expected) check(fixtureFile.isFile) { "Expected private SSH fixture was not provisioned" }
        assumeTrue("Real SSH fixture NOT_RUN; requires Linux host -Device -InstallTestApp", fixtureFile.isFile)
        val fixture = JSONObject(fixtureFile.readText(Charsets.UTF_8))
        check(fixture.getString("runId") == runId)
        val koin = GlobalContext.get()
        val liveRepository = koin.get<MemoryRepository>()
        val manager = koin.get<MemorySyncManager>()
        val workspaces = koin.get<WorkspaceRepository>()
        val scopeId = "memory-probe-$runId"
        val root = fixture.getString("remoteRoot")
        val spaceId = fixture.getString("spaceId")
        var workspaceId: String? = null
        suspend fun rpc(op: String, path: String? = null, content: String? = null, token: String? = null): JSONObject {
            val request = JSONObject().put("protocol", 1).put("op", op).put("spaceId", spaceId)
            path?.let { request.put("path", it) }
            if (op == "put") request.put("content", content)
            token?.let { request.put("expectedToken", it) }
            if (op == "put" || op == "delete") request.put("operationId", UUID.randomUUID().toString())
            val response = JSONObject(workspaces.memorySyncRpc(checkNotNull(workspaceId), root, request.toString()))
            check(response.getBoolean("ok")) { "Real SSH helper rejected fixture request" }
            return response
        }
        try {
            val workspace = workspaces.saveSsh(null, SshWorkspaceDraft(
                name = "Memory sync disposable $runId",
                host = fixture.getString("host"),
                port = fixture.getInt("port"),
                username = fixture.getString("username"),
                authType = SshAuthType.PRIVATE_KEY,
                privateKey = fixture.getString("privateKey"),
                remoteDir = root,
                hostKey = SshHostKey(fixture.getString("hostKeyType"), fixture.getString("hostKey")),
            ))
            workspaceId = workspace.id
            manager.bind(scopeId, workspace.id, root, spaceId, createSpace = false)
            assertEquals("server-initial-fixture", liveRepository.getFile(scopeId, "/profile.md")!!.content)
            manager.setEnabled(scopeId, false)
            assertSuccess(liveRepository.writeAtGeneration(scopeId, "/device-created.md", "device-push-fixture", 0))
            manager.setEnabled(scopeId, true)
            manager.sync(scopeId)
            assertEquals("device-push-fixture", rpc("read", "/device-created.md").getJSONObject("document").getString("content"))
            rpc("put", "/remote.md", "remote-pull-fixture", "0")
            manager.sync(scopeId)
            assertEquals("remote-pull-fixture", liveRepository.getFile(scopeId, "/remote.md")!!.content)

            manager.setEnabled(scopeId, false)
            val local = liveRepository.snapshot(scopeId, "/profile.md")
            val remote = rpc("read", "/profile.md").getJSONObject("document")
            assertSuccess(liveRepository.writeAtGeneration(scopeId, "/profile.md", "device-conflict-fixture", local.generation))
            rpc("put", "/profile.md", "server-conflict-fixture", remote.getString("token"))
            manager.setEnabled(scopeId, true)
            manager.sync(scopeId)
            assertEquals(1, manager.conflicts(scopeId).size)
            assertEquals("device-conflict-fixture", liveRepository.getFile(scopeId, "/profile.md")!!.content)
            launchProbe()
            scenario!!.onActivity { it.showConnectedExchange(scopeId, liveRepository, manager, workspaces) }
            compose.onNodeWithTag("memory_sync_settings").performScrollTo().performClick()
            compose.onNodeWithTag("memory_sync_dialog").assertIsDisplayed()
            capture("sync-settings")
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("memory_sync_conflict_/profile.md")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("memory_sync_conflict_/profile.md").performScrollTo().performClick()
            compose.onNodeWithTag("memory_sync_conflict_dialog").assertIsDisplayed()
            capture("sync-conflict")
            compose.onNodeWithTag("memory_conflict_merge").performScrollTo().performTextReplacement("resolved-device-fixture")
            compose.onNodeWithTag("memory_conflict_save_merge").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("memory_sync_conflict_dialog")).fetchSemanticsNodes().isEmpty() }
            assertTrue(manager.conflicts(scopeId).isEmpty())
            assertEquals("resolved-device-fixture", rpc("read", "/profile.md").getJSONObject("document").getString("content"))
            assertEquals("resolved-device-fixture", liveRepository.getFile(scopeId, "/profile.md")!!.content)

            manager.setEnabled(scopeId, false)
            val created = liveRepository.snapshot(scopeId, "/device-created.md")
            assertSuccess(liveRepository.writeAtGeneration(scopeId, "/device-created.md", null, created.generation))
            manager.setEnabled(scopeId, true)
            manager.sync(scopeId)
            val deleted = rpc("read", "/device-created.md").getJSONObject("document")
            assertTrue(deleted.isNull("content"))
            assertTrue(deleted.getString("token").toLong() >= 2)
        } finally {
            scenario?.close(); scenario = null
            try { manager.unbind(scopeId) } finally {
                try {
                    liveRepository.deleteFilesOfAssistant(scopeId)
                    // Delete only this run's disposable revision rows after its binding is removed.
                    koin.get<AppDatabase>().openHelper.writableDatabase.execSQL("DELETE FROM memory_revision WHERE assistant_id = ?", arrayOf(scopeId))
                } finally {
                    try { workspaceId?.let { workspaces.delete(it) } } finally { fixtureFile.delete() }
                }
            }
        }
    }


    @Test
    fun productionDialogShowsGlobalAssistantAndImportsUsingRoom() {
        launchProbe()
        compose.onNodeWithText("Open global").performClick()
        compose.onNodeWithTag("memory_exchange").assertIsDisplayed()
        capture("global")
        closeDialog()
        compose.onNodeWithText("Open assistant").performClick()
        compose.onNodeWithTag("memory_exchange").assertIsDisplayed()
        capture("assistant")
        closeDialog()
        val docs = MemoryArchiveFormat.readMarkdown(ByteArrayInputStream("# Imported via actual dialog\r\n".toByteArray()), "/imported.md")
        scenario!!.onActivity { it.showExchange(MemorySyncProbeActivity.ASSISTANT_ID, docs) }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("memory_import_preview")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("memory_import_preview").performScrollTo()
        capture("import")
        compose.onNodeWithTag("memory_import_apply").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("memory_import_result_/imported.md")).fetchSemanticsNodes().isNotEmpty() }
        scenario!!.onActivity { activity ->
            runBlocking(Dispatchers.IO) {
                assertEquals("# Imported via actual dialog\r\n", activity.repository.getFile(MemorySyncProbeActivity.ASSISTANT_ID, "/imported.md")!!.content)
                assertNull(activity.repository.getFile(MemoryRepository.GLOBAL_MEMORY_ID, "/imported.md"))
            }
        }
        compose.onNodeWithTag("memory_import_result_/imported.md").performScrollTo()
        capture("import-result")
        closeDialog()
        compose.onNodeWithText("Open global").performClick()
        capture("export")
        launchAndCancelPicker("memory_export_zip")
        launchAndCancelPicker("memory_import_markdown")
        launchAndCancelPicker("memory_import_zip")
        launchAndCancelPicker("memory_import_directory")
    }

    @Test
    fun productionDialogReportsConcurrentEditRatherThanOverwriting() {
        launchProbe()
        scenario!!.onActivity { activity ->
            activity.showExchange(MemorySyncProbeActivity.ASSISTANT_ID, listOf(MemoryArchiveDocument("/profile.md", "import candidate")))
        }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("memory_import_preview")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("memory_import_confirm_/profile.md").performScrollTo().performClick()
        scenario!!.onActivity { activity ->
            runBlocking(Dispatchers.IO) {
                val snapshot = activity.repository.snapshot(MemorySyncProbeActivity.ASSISTANT_ID, "/profile.md")
                assertSuccess(activity.repository.writeAtGeneration(MemorySyncProbeActivity.ASSISTANT_ID, "/profile.md", "concurrent edit", snapshot.generation))
            }
        }
        compose.onNodeWithTag("memory_import_apply").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("memory_import_result_/profile.md")).fetchSemanticsNodes().isNotEmpty() }
        scenario!!.onActivity { activity ->
            runBlocking(Dispatchers.IO) { assertEquals("concurrent edit", activity.repository.getFile(MemorySyncProbeActivity.ASSISTANT_ID, "/profile.md")!!.content) }
        }
        compose.onNodeWithTag("memory_import_result_/profile.md").performScrollTo()
        capture("conflict")
    }

    private fun launchProbe() {
        scenario = ActivityScenario.launch(Intent(context, MemorySyncProbeActivity::class.java))
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("memory_probe_ready")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun closeDialog() {
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("memory_exchange")).fetchSemanticsNodes().isEmpty() }
    }

    /** Exercise real OS contracts without selecting, reading, or screenshotting personal documents. */
    private fun launchAndCancelPicker(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        var external = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            external = root?.packageName?.let { it.toString() != context.packageName } ?: false
            if (external) break
            android.os.SystemClock.sleep(100)
        }
        assertTrue("System document picker did not launch for $tag", external)
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(10_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
        }
        compose.onNodeWithTag("memory_exchange").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val runId = InstrumentationRegistry.getArguments().getString("memorySyncRunId") ?: "manual-${UUID.randomUUID()}"
        require(runId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val directory = File(checkNotNull(context.getExternalFilesDir("memory-sync-tests")), runId).apply { check(mkdirs() || isDirectory) }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "No device screenshot available" }
        try { File(directory, "$name.png").outputStream().use { check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
        File(directory, "$name.xml").outputStream().use { output ->
            val xml = Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true); startTag(null, "hierarchy") }
            val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow) { "No active accessibility root" }
            check(root.packageName?.toString() == context.packageName) { "Refusing to archive nonfixture UI" }
            writeNode(xml, root, 0)
            xml.endTag(null, "hierarchy"); xml.endDocument()
        }
    }

    private fun writeNode(xml: XmlSerializer, node: AccessibilityNodeInfo, depth: Int) {
        check(depth < 100) { "Accessibility hierarchy exceeds test budget" }
        xml.startTag(null, "node")
        xml.attribute(null, "package", node.packageName?.toString().orEmpty())
        xml.attribute(null, "class", node.className?.toString().orEmpty())
        xml.attribute(null, "text", node.text?.toString().orEmpty())
        xml.attribute(null, "content-desc", node.contentDescription?.toString().orEmpty())
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        xml.attribute(null, "bounds", bounds.toShortString())
        for (index in 0 until node.childCount) node.getChild(index)?.let { writeNode(xml, it, depth + 1) }
        xml.endTag(null, "node")
    }

    private fun assertSuccess(result: MemoryWriteResult) = assertTrue("Expected successful real repository write: $result", result is MemoryWriteResult.Success)
    private fun expectRejected(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Malformed archive must be rejected", rejected)
    }
    private suspend fun expectRejectedSuspend(block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Malformed import must be rejected before writes", rejected)
    }
}
