package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceMountsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun dir(name: String): File = tempFolder.newFolder(name)

    // region normalizeTarget

    @Test
    fun `normalize strips trailing slash and keeps absolute path`() {
        assertEquals("/sdcard", WorkspaceMountPoints.normalizeTarget("/sdcard/"))
        assertEquals("/mnt/udisk", WorkspaceMountPoints.normalizeTarget("  /mnt//udisk/  "))
    }

    @Test
    fun `normalize rejects relative, root and traversal targets`() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceMountPoints.normalizeTarget("sdcard") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceMountPoints.normalizeTarget("/") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceMountPoints.normalizeTarget("/sdcard/../etc") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceMountPoints.normalizeTarget("/sdcard/./x") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceMountPoints.normalizeTarget("/sd\u0000card") }
    }

    @Test
    fun `user target rejects reserved paths but built-in style target does not`() {
        // /sdcard 是新挂载点, 允许
        assertEquals("/sdcard", WorkspaceMountPoints.normalizeUserTarget("/sdcard"))

        // 保留路径必须被拒: 否则会遮蔽 App 自己的挂载点或内核伪文件系统
        listOf("/skills", "/skills/nested", "/upload", "/workspace", "/proc", "/dev", "/etc/ssl", "/").forEach {
            assertThrows("expected $it to be reserved", IllegalArgumentException::class.java) {
                WorkspaceMountPoints.normalizeUserTarget(it)
            }
        }

        // 内置挂载点用 normalizeTarget, 同一个路径必须通过
        assertEquals("/skills", WorkspaceMountPoints.normalizeTarget("/skills"))
    }

    // endregion

    // region filterUsable

    @Test
    fun `filter drops mounts whose host is missing or not a directory`() {
        val file = tempFolder.newFile("not-a-dir.txt")
        val usable = dir("usable")

        val result = WorkspaceMountPoints.filterUsable(
            listOf(
                WorkspaceBindMount(File(tempFolder.root, "ghost"), "/sdcard"),
                WorkspaceBindMount(file, "/sdcard-file"),
                WorkspaceBindMount(usable, "/sdcard"),
            )
        )

        assertEquals(1, result.size)
        assertEquals("/sdcard", result.single().target)
        assertEquals(usable.canonicalPath, result.single().source.canonicalPath)
    }

    @Test
    fun `filter keeps the first of duplicate targets`() {
        val first = dir("first")
        val second = dir("second")

        val result = WorkspaceMountPoints.filterUsable(
            listOf(
                WorkspaceBindMount(first, "/sdcard"),
                WorkspaceBindMount(second, "/sdcard/"),
            )
        )

        assertEquals(1, result.size)
        assertEquals(first.canonicalPath, result.single().source.canonicalPath)
    }

    @Test
    fun `filter drops nested targets so longest prefix resolution stays unambiguous`() {
        val outer = dir("outer")
        val inner = dir("inner")

        val result = WorkspaceMountPoints.filterUsable(
            listOf(
                WorkspaceBindMount(outer, "/sdcard"),
                WorkspaceBindMount(inner, "/sdcard/DCIM"),
            )
        )

        assertEquals(1, result.size)
        assertEquals("/sdcard", result.single().target)
    }

    @Test
    fun `filter normalizes targets handed to proot`() {
        val usable = dir("usable")
        val result = WorkspaceMountPoints.filterUsable(listOf(WorkspaceBindMount(usable, "/sdcard/")))
        assertEquals("/sdcard", result.single().target)
    }

    // endregion

    // region ensureGuestMountPoint / prepare

    @Test
    fun `ensureGuestMountPoint creates the mount point inside the rootfs`() {
        val linuxDir = dir("linux")
        val mountPoint = WorkspaceMountPoints.ensureGuestMountPoint(linuxDir, "/sdcard")

        assertTrue(mountPoint.isDirectory)
        assertEquals(File(linuxDir, "sdcard").canonicalPath, mountPoint.canonicalPath)

        // 嵌套挂载点也要能建出来
        val nested = WorkspaceMountPoints.ensureGuestMountPoint(linuxDir, "/mnt/udisk")
        assertTrue(nested.isDirectory)
    }

    @Test
    fun `ensureGuestMountPoint replaces a pre-existing symlink`() {
        val linuxDir = dir("linux")
        val outside = dir("outside")
        val link = File(linuxDir, "sdcard")

        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            return // 文件系统不支持符号链接时跳过
        }

        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        WorkspaceMountPoints.ensureGuestMountPoint(linuxDir, "/sdcard")

        assertFalse(java.nio.file.Files.isSymbolicLink(link.toPath()))
        assertTrue(link.isDirectory)
    }

    @Test
    fun `prepare reports false for unusable host and true otherwise`() {
        val linuxDir = dir("linux")
        val host = dir("host")

        assertTrue(WorkspaceMountPoints.prepare(linuxDir, WorkspaceBindMount(host, "/sdcard")))
        assertTrue(File(linuxDir, "sdcard").isDirectory)

        assertFalse(
            WorkspaceMountPoints.prepare(
                linuxDir,
                WorkspaceBindMount(File(tempFolder.root, "ghost"), "/sdcard"),
            )
        )
        assertFalse(
            WorkspaceMountPoints.prepare(
                linuxDir,
                WorkspaceBindMount(host, "/skills/../etc"),
            )
        )
    }

    // endregion
}
