package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/**
 * 外部目录挂载的校验、冲突消解与挂载点准备。
 *
 * 这里兜住的三件事都来自 PRoot 自身的实现约束, 而不是 RikkaHub 的选择:
 *
 * 1. `-b host:guest` 只做路径替换, **不创建 guest 挂载点**。挂载点在 rootfs 内不存在时,
 *    沙箱里那个路径依旧按普通目录查找, 挂载会静默失效 (表现为"挂了但看不到文件")。
 *    依据: termux/proot `src/path/binding.c` 全文没有 mkdir, `new_binding(..., must_exist)`
 *    的 must_exist 只约束 host 侧。
 * 2. host 路径必须在 PRoot 启动的那一瞬间存在, 否则 PRoot 报错 (可用环境变量
 *    `PROOT_IGNORE_MISSING_BINDINGS` 让它跳过而不是中断)。外置存储随时会被拔掉, 所以要在
 *    启动前判定, 而不是在配置写入时判定。
 * 3. **PRoot 没有只读绑定** (binding 结构体里没有 readonly 字段)。挂载一旦生效, 沙箱内的
 *    任何命令对该目录都是可写的 —— 因此外部目录必须由用户显式开启, 且默认关闭。
 */
object WorkspaceMountPoints {
    /** 共享存储约定使用的挂载点 */
    const val SHARED_STORAGE_TARGET = "/sdcard"

    private const val MAX_TARGET_LENGTH = 64

    /**
     * 不允许被外部目录占用的挂载点。
     *
     * 这些路径要么是 rootfs 的组成部分, 要么由 App 自己挂载, 要么是内核伪文件系统;
     * 被外部目录遮蔽后, 沙箱内的行为会变得难以解释。
     */
    private val RESERVED_TARGETS = setOf(
        "/dev",
        "/proc",
        "/sys",
        "/etc",
        "/usr",
        "/bin",
        "/sbin",
        "/lib",
        "/var",
        "/tmp",
        "/root",
        WorkspaceManager.ROOTFS_WORKSPACE_DIR,
        "/skills",
        "/builtin_skills",
        "/tool_outputs",
        "/upload",
    )

    /**
     * 结构校验: 只保证这个字符串可以作为挂载点使用。
     *
     * 拒绝相对路径、`..`、NUL 与根目录本身。返回值不带尾随斜杠 —— 它同时被用于 PRoot 的
     * `-b` 参数和宿主侧的文件路径解析, 两处必须完全一致, 否则文件工具会去读空挂载点。
     */
    fun normalizeTarget(raw: String): String {
        val trimmed = raw.trim().replace('\\', '/')
        require(trimmed.startsWith("/")) { "Mount target must be absolute: $raw" }
        require(!trimmed.contains('\u0000')) { "Mount target contains NUL: $raw" }
        require(trimmed.length <= MAX_TARGET_LENGTH) { "Mount target is too long: $raw" }

        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "Mount target must not contain . or ..: $raw" }

        val normalized = "/" + segments.joinToString("/")
        require(normalized != "/") { "Mount target must not be the rootfs root" }
        return normalized
    }

    /**
     * 用户可配置的挂载点: 在 [normalizeTarget] 之上再拒绝保留路径。
     *
     * App 内置的挂载点 (比如 /skills) 本身就在保留列表里, 所以它们走 [normalizeTarget],
     * 不走这个函数。
     */
    fun normalizeUserTarget(raw: String): String {
        val normalized = normalizeTarget(raw)
        require(!isReserved(normalized)) { "Mount target is reserved by RikkaHub: $normalized" }
        return normalized
    }

    /** [target] 是否等于或位于某个保留路径之下 */
    fun isReserved(target: String): Boolean {
        val normalized = target.trim().trimEnd('/').ifBlank { "/" }
        if (normalized == "/") return true
        return RESERVED_TARGETS.any { reserved ->
            normalized == reserved || normalized.startsWith("$reserved/")
        }
    }

    /**
     * 过滤掉不可用的挂载, 结果可以直接交给 PRoot。
     *
     * - host 必须是存在且可读的目录 (卷可能已卸载, 权限可能被回收)
     * - target 必须能通过 [normalizeTarget]
     * - target 重复时保留第一个
     * - target 互相嵌套时, 含内层 target 的那条被丢弃: 宿主侧按最长前缀解析, 内层挂载会
     *   遮蔽外层, 让"同一个目录出现两个路径"这种状态无法解释
     */
    fun filterUsable(mounts: List<WorkspaceBindMount>): List<WorkspaceBindMount> {
        val accepted = mutableListOf<WorkspaceBindMount>()
        val seenTargets = mutableSetOf<String>()

        mounts.forEach { mount ->
            val target = runCatching { normalizeTarget(mount.target) }.getOrNull() ?: return@forEach
            if (!seenTargets.add(target)) return@forEach
            if (accepted.any { isAncestorOf(it.target, target) || isAncestorOf(target, it.target) }) return@forEach
            if (!mount.source.isDirectory) return@forEach
            accepted += WorkspaceBindMount(source = mount.source, target = target)
        }

        return accepted
    }

    /**
     * 在 rootfs 内创建 [target] 对应的挂载点目录。
     *
     * 若该位置已存在指向别处的符号链接, 先删除再建目录: rootfs 来自第三方 tarball, 一个
     * 预先埋好的软链会让挂载点落到预期之外的位置。
     */
    fun ensureGuestMountPoint(linuxDir: File, target: String): File {
        val relative = normalizeTarget(target).trimStart('/')
        val mountPoint = File(linuxDir, relative)

        if (Files.isSymbolicLink(mountPoint.toPath()) || (mountPoint.exists() && !mountPoint.isDirectory)) {
            mountPoint.delete()
        }
        mountPoint.mkdirs()
        return mountPoint
    }

    /**
     * 启动 PRoot 前的最后一道闸: host 可用且挂载点已就绪, 才允许写进 `-b`。
     *
     * @return false 表示这个挂载当前不可用, 应当跳过 (而不是让 PRoot 去报错)
     */
    fun prepare(linuxDir: File, mount: WorkspaceBindMount): Boolean {
        if (!mount.source.isDirectory) return false
        val target = runCatching { normalizeTarget(mount.target) }.getOrNull() ?: return false
        return runCatching { ensureGuestMountPoint(linuxDir, target) }.isSuccess
    }

    private fun isAncestorOf(ancestor: String, candidate: String): Boolean {
        val a = ancestor.trimEnd('/')
        val b = candidate.trimEnd('/')
        return a != b && b.startsWith("$a/")
    }
}
