package me.rerere.rikkahub.ssh

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

class SshIpcPathsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun cache() = File(temp.root, "c").apply { mkdirs() }
    private fun owners() = File(temp.root, "o").apply { mkdirs() }
    private fun seed(root: String): String = MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).take(2).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun realAndroidTestPackagePathFitsKnownSocketNames() {
        val host = "/data/user/0/me.rerere.rikkahub.sshprobe/cache/v-abcd"
        requireShortIpcDirectory(host)
        assertTrue(ipcSocketPathBytes(host, "code-00000000-0000-0000-0000-000000000000") <= 107)
        assertTrue(ipcSocketPathBytes(host, "vscode-ipc-00000000-0000-0000-0000-000000000000.sock") <= 107)
    }
    @Test fun boundaryUsesBytesNotCharacterCount() {
        requireShortIpcDirectory("/" + "x".repeat(53))
        assertThrows(IllegalArgumentException::class.java) { requireShortIpcDirectory("/" + "x".repeat(54)) }
        assertThrows(IllegalArgumentException::class.java) { requireShortIpcDirectory("/" + "中".repeat(18)) }
    }
    @Test fun nonAbsoluteAndNulRejected() {
        assertThrows(IllegalArgumentException::class.java) { requireShortIpcDirectory("relative") }
        assertThrows(IllegalArgumentException::class.java) { requireShortIpcDirectory("/abc\u0000x") }
    }
    @Test fun guestPathIsDedicatedNotGlobalTmp() { assertEquals("/vscode-tmp", SSH_IPC_GUEST); assertNotEquals("/tmp", SSH_IPC_GUEST) }
    @Test fun sameWorkspaceReusesItsSlotAcrossInstances() {
        val c=cache(); val o=owners()
        val first=SshIpcStorage(c,o).allocate("workspace-a")
        assertEquals(first, SshIpcStorage(c,o).allocate("workspace-a"))
    }
    @Test fun differentWorkspacesHaveDifferentSlots() {
        val storage=SshIpcStorage(cache(),owners())
        assertNotEquals(storage.allocate("a").hostDirectory, storage.allocate("b").hostDirectory)
    }
    @Test fun unownedExistingDirectoryIsNotClaimedOrDeleted() {
        val c=cache(); val o=owners(); val occupied=File(c,"v-"+seed("root")); occupied.mkdirs()
        val sentinel=File(occupied,"keep").apply { writeText("do not delete") }
        val allocated=SshIpcStorage(c,o).allocate("root")
        assertNotEquals(occupied, allocated.hostDirectory)
        assertEquals("do not delete", sentinel.readText())
    }
    @Test fun hashSlotCollisionDoesNotShareWithAnotherOwner() {
        val c=cache(); val o=owners(); val slot="v-"+seed("root")
        File(o,"$slot.owner").writeText("f".repeat(64))
        val occupied=File(c,slot).apply { mkdirs() }
        assertNotEquals(occupied,SshIpcStorage(c,o).allocate("root").hostDirectory)
        assertEquals("f".repeat(64),File(o,"$slot.owner").readText())
    }
    @Test fun corruptOwnerRecordDoesNotBecomeAnOwnedSlot() {
        val c=cache(); val o=owners(); val slot="v-"+seed("root")
        File(o,"$slot.owner").writeText("")
        assertNotEquals(File(c,slot),SshIpcStorage(c,o).allocate("root").hostDirectory)
    }
    @Test fun privatePermissionsAndOwnerRecordOutsideBind() {
        val o=owners(); val location=SshIpcStorage(cache(),o).allocate("root")
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(location.hostDirectory.toPath()))
        val record=o.listFiles()!!.single()
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(record.toPath()))
        assertEquals(0,location.hostDirectory.list()!!.size)
    }
    @Test fun reusedSlotDoesNotRecursivelyDeleteAnything() {
        val storage=SshIpcStorage(cache(),owners()); val dir=storage.allocate("root").hostDirectory
        val outside=File(temp.root,"precious").apply { writeText("keep") }
        val link=File(dir,"link")
        Files.createSymbolicLink(link.toPath(),outside.toPath())
        assertEquals(dir,storage.allocate("root").hostDirectory)
        assertEquals("keep",outside.readText()); assertTrue(Files.isSymbolicLink(link.toPath()))
    }
    @Test fun replacedOwnedDirectorySymlinkRejected() {
        val storage=SshIpcStorage(cache(),owners()); val dir=storage.allocate("root").hostDirectory
        assertTrue(dir.delete()); val outside=temp.newFolder("outside")
        Files.createSymbolicLink(dir.toPath(),outside.toPath())
        assertThrows(IllegalStateException::class.java) { storage.allocate("root") }
    }
    @Test fun symlinkOwnerRecordRejected() {
        val c=cache();val o=owners();val outside=File(temp.root,"metadata").apply { writeText("keep") }
        Files.createSymbolicLink(File(o,"v-"+seed("root")+".owner").toPath(),outside.toPath())
        assertThrows(IllegalStateException::class.java) { SshIpcStorage(c,o).allocate("root") }
        assertEquals("keep",outside.readText())
    }
    @Test fun cacheSymlinkRejected() {
        val target=temp.newFolder("real-cache"); val link=File(temp.root,"link-cache")
        Files.createSymbolicLink(link.toPath(),target.toPath())
        assertThrows(IllegalStateException::class.java) { SshIpcStorage(link,owners()).allocate("root") }
    }
    @Test fun ownerDirectorySymlinkRejected() {
        val target=temp.newFolder("real-owners");val link=File(temp.root,"link-owners")
        Files.createSymbolicLink(link.toPath(),target.toPath())
        assertThrows(IllegalStateException::class.java) { SshIpcStorage(cache(),link).allocate("root") }
    }
    @Test fun generatedProbeRejectsScriptInjection() {
        for (path in listOf("relative", "/tmp/a';process.exit(1)//", "/tmp/a\n", "/tmp/../x")) {
            assertThrows(IllegalArgumentException::class.java) { ipcNodeProbeProgram(path) }
        }
    }
    @Test fun generatedProbeTestsBothListenAndConnect() {
        val program = ipcNodeProbeProgram()
        assertTrue(program.contains("net.createConnection"))
        assertTrue(program.contains("socket.end('OK')"))
        assertTrue(program.contains("UNIX_DEFAULT_TMP"))
        assertTrue(program.contains("UNIX_VSCODE_BIND"))
    }
    @Test fun generatedProbeUsesOnlyTheExplicitTestDirectory() {
        val program = ipcNodeProbeProgram("/tmp/fixture")
        assertTrue(program.contains("/tmp/fixture/code-"))
        assertFalse(program.contains("/vscode-tmp/code-"))
    }
    @Test fun generatedProbeDoesNotInstallOrDeleteUserFiles() {
        val program = ipcNodeProbeProgram()
        assertFalse(program.contains("unlink"))
        assertFalse(program.contains("rm -"))
        assertFalse(program.contains("0.0.0.0"))
        assertTrue(program.contains("127.0.0.1"))
    }
    @Test fun blankWorkspaceRejected() { assertThrows(IllegalArgumentException::class.java) { SshIpcStorage(cache(),owners()).allocate("") } }
}
