package me.rerere.rikkahub.ssh

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

const val SSH_IPC_GUEST = "/vscode-tmp"
private const val MAX_UNIX_PATH_BYTES = 107
private const val RESERVED_SOCKET_NAME_BYTES = 52

fun ipcSocketPathBytes(hostDirectory: String, name: String): Int =
    hostDirectory.toByteArray(Charsets.UTF_8).size + 1 + name.toByteArray(Charsets.UTF_8).size

fun requireShortIpcDirectory(hostDirectory: String) {
    require(hostDirectory.startsWith('/') && !hostDirectory.contains('\u0000')) { "IPC 宿主路径无效" }
    require(hostDirectory.toByteArray(Charsets.UTF_8).size + 1 + RESERVED_SOCKET_NAME_BYTES <= MAX_UNIX_PATH_BYTES) {
        "IPC 宿主路径过长，不能为 Unix socket 保留足够空间；不会冒称已修复"
    }
}

data class SshIpcLocation(val hostDirectory: File, val guestPath: String = SSH_IPC_GUEST)

/** Short per-workspace cache slots. Owner records stay outside the guest bind, never follow symlinks. */
class SshIpcStorage(private val cache: File, private val ownerDirectory: File) {
    @Synchronized
    fun allocate(workspaceRoot: String): SshIpcLocation {
        require(workspaceRoot.isNotBlank()) { "IPC 工作区无效" }
        check(
            !Files.isSymbolicLink(cache.toPath()) && !Files.isSymbolicLink(ownerDirectory.toPath())
        ) { "IPC 根目录不能是符号链接" }
        val root = cache.canonicalFile
        root.mkdirs()
        ownerDirectory.mkdirs()
        Files.setPosixFilePermissions(ownerDirectory.toPath(), PosixFilePermissions.fromString("rwx------"))
        val owner = MessageDigest.getInstance(
            "SHA-256"
        ).digest(workspaceRoot.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val seed = owner.take(4).toInt(16)
        requireShortIpcDirectory(File(root, "v-0000").absolutePath)
        for (step in 0..65535) {
            val slot = "v-%04x".format((seed + step) and 65535)
            val directory = File(root, slot)
            val record = File(ownerDirectory, "$slot.owner")
            check(!Files.isSymbolicLink(record.toPath())) { "IPC owner 记录不能是符号链接" }
            if (record.exists()) {
                if (record.length() != 64L || record.readText() != owner) continue
                check(!Files.isSymbolicLink(directory.toPath())) { "IPC 目录不能是符号链接" }
            } else {
                // Do not claim or delete an unknown existing cache entry.
                if (directory.exists() || Files.isSymbolicLink(directory.toPath())) continue
                try {
                    Files.write(
                        record.toPath(),
                        owner.toByteArray(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                    )
                    Files.setPosixFilePermissions(record.toPath(), PosixFilePermissions.fromString("rw-------"))
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    continue
                }
            }
            directory.mkdirs()
            check(
                directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
                    directory.canonicalFile.parentFile == root
            ) { "IPC 缓存目录不可用" }
            Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
            requireShortIpcDirectory(directory.absolutePath)
            return SshIpcLocation(directory)
        }
        error("IPC 短路径槽已用完，请人工检查应用缓存；不会删除活跃或未知目录")
    }
}
