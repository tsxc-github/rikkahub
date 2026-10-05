package me.rerere.rikkahub.data.workspace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceMountPoints
import java.io.File

/**
 * 一个可挂载的存储位置: 内部共享存储, 或一张可移动卷 (SD 卡 / U 盘)。
 *
 * [id] 用于配置持久化, 与路径解耦 —— 可移动卷的挂载点会随插拔、重新挂载而变化, 把路径写进
 * 配置会导致"同一个 U 盘插回来以后挂载点失效"。
 */
data class StorageVolumeOption(
    val id: String,
    val label: String,
    val path: String,
    val removable: Boolean,
    val accessible: Boolean,
) {
    val directory: File get() = File(path)
}

@Serializable
data class WorkspaceMountEntry(
    val id: String,
    val target: String,
    val enabled: Boolean = false,
)

@Serializable
data class WorkspaceMountConfig(
    val entries: List<WorkspaceMountEntry> = emptyList(),
)

/**
 * 枚举 App 视角下可挂载的存储位置。
 *
 * 不要硬编码 `/mnt/media_rw/<UUID>`: 那是 root 视角的原始挂载点。普通 App (以及与之同 uid 的
 * PRoot 子进程) 用的路径是 `/storage/<UUID>`, 也就是 [StorageVolume.getDirectory]。
 */
class StorageVolumeCatalog(private val context: Context) {
    companion object {
        const val ID_PRIMARY = "primary"
        const val ID_REMOVABLE_PREFIX = "vol:"
    }

    fun volumes(): List<StorageVolumeOption> {
        val options = storageVolumes().mapNotNull { volume ->
            val directory = directoryOf(volume) ?: return@mapNotNull null
            if (!directory.isDirectory) return@mapNotNull null
            if (!volume.isPrimary && volume.state != Environment.MEDIA_MOUNTED) return@mapNotNull null

            StorageVolumeOption(
                id = idOf(volume, directory),
                label = labelOf(volume, directory),
                path = directory.absolutePath,
                removable = volume.isRemovable,
                accessible = canListRead(directory),
            )
        }

        if (options.isEmpty()) {
            // 少数 ROM 上 StorageManager 不给卷列表, 至少让内部共享存储还能用
            val directory = Environment.getExternalStorageDirectory() ?: return emptyList()
            if (directory.isDirectory) {
                options += StorageVolumeOption(
                    id = ID_PRIMARY,
                    label = directory.name,
                    path = directory.absolutePath,
                    removable = false,
                    accessible = canListRead(directory),
                )
            }
        }

        return options
    }

    /**
     * 是否拥有"任意路径"级别的存储访问。
     *
     * Android 11 起, 没有 MANAGE_EXTERNAL_STORAGE 时 App 依然能用路径访问**媒体文件**,
     * 但 Download / Documents / 整张可移动卷都不行 —— 也就是说"能挂哪些目录"取决于这里。
     */
    fun hasBroadStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 媒体目录的受限访问权限, 用于不申请"所有文件访问"时仍然能整理相册 */
    fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** "所有文件访问"设置页; 返回 null 表示该平台不支持 */
    fun broadAccessIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            )
        } else {
            null
        }

    /** 部分 ROM 不认带包名的 action, 退回到列表页 */
    fun broadAccessFallbackIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        } else {
            null
        }

    private fun storageVolumes(): List<StorageVolume> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return runCatching { manager.storageVolumes }.getOrDefault(emptyList())
    }

    private fun directoryOf(volume: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            val uuid = volume.uuid
            if (uuid.isNullOrBlank()) Environment.getExternalStorageDirectory() else File("/storage/$uuid")
        }

    private fun idOf(volume: StorageVolume, directory: File): String =
        if (volume.isPrimary) ID_PRIMARY else ID_REMOVABLE_PREFIX + (volume.uuid ?: directory.name)

    private fun labelOf(volume: StorageVolume, directory: File): String =
        runCatching { volume.getDescription(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: directory.name

    /**
     * best-effort 的可读探测。
     *
     * Scoped storage 下"路径权限"和"能否 open()"并不完全一致, 所以这里只用于 UI 提示。
     * 真正的判据是 PRoot 启动后沙箱内能否读到内容; 读不到时内核返回 EACCES, 不会静默写坏。
     */
    private fun canListRead(directory: File): Boolean =
        directory.isDirectory && directory.canRead() && runCatching { directory.list() }.getOrNull() != null
}

/**
 * 外部存储挂载的配置与解析。
 *
 * 配置刻意放在 workspace 自己的文件里, 而不是 SettingsStore:
 * 1. 挂载表需要在启动 PRoot 前**同步**读取, 走设置流会引入"设置还没收集到, 挂载为空"的竞态;
 * 2. 挂载点归属 workspace 域, 与聊天/模型的设置无关。
 */
class WorkspaceMountRepository(private val context: Context) {
    private val catalog = StorageVolumeCatalog(context)
    private val configFile = File(context.filesDir, CONFIG_FILE_NAME)
    private val lock = Any()

    fun volumes(): List<StorageVolumeOption> = catalog.volumes()

    fun hasBroadStorageAccess(): Boolean = catalog.hasBroadStorageAccess()

    fun broadAccessIntent(): Intent? = catalog.broadAccessIntent()

    fun broadAccessFallbackIntent(): Intent? = catalog.broadAccessFallbackIntent()

    fun mediaPermissions(): Array<String> = catalog.mediaPermissions()

    fun defaultTargetFor(volume: StorageVolumeOption): String =
        if (volume.id == StorageVolumeCatalog.ID_PRIMARY) {
            WorkspaceMountPoints.SHARED_STORAGE_TARGET
        } else {
            "/udisk-" + volume.id.removePrefix(StorageVolumeCatalog.ID_REMOVABLE_PREFIX)
        }

    fun isValidTarget(target: String): Boolean =
        runCatching { WorkspaceMountPoints.normalizeUserTarget(target) }.isSuccess

    fun config(): WorkspaceMountConfig = synchronized(lock) { loadLocked() }

    fun setEnabled(
        volume: StorageVolumeOption,
        enabled: Boolean,
        target: String = defaultTargetFor(volume),
    ): WorkspaceMountConfig = update { current ->
        current.upsert(WorkspaceMountEntry(id = volume.id, target = target, enabled = enabled))
    }

    fun setTarget(id: String, target: String): WorkspaceMountConfig = update { current ->
        val existing = current.entries.firstOrNull { it.id == id } ?: return@update current
        current.upsert(existing.copy(target = target))
    }

    /**
     * 当前真正可以交给 PRoot 的挂载表。
     *
     * 每次启动都重新求值: 卷的插拔、权限的回收、用户在设置里的改动都必须即时反映,
     * 不能缓存 (缓存会在 App 运行期间产生"设置改了但沙箱里还是旧的"这种漂移)。
     */
    fun resolveMounts(): List<WorkspaceBindMount> {
        val available = catalog.volumes().associateBy { it.id }
        val requested = config().entries.mapNotNull { entry ->
            if (!entry.enabled) return@mapNotNull null
            val volume = available[entry.id] ?: return@mapNotNull null
            if (!volume.accessible) return@mapNotNull null
            WorkspaceBindMount(source = volume.directory, target = entry.target)
        }
        return WorkspaceMountPoints.filterUsable(requested)
    }

    private fun update(transform: (WorkspaceMountConfig) -> WorkspaceMountConfig): WorkspaceMountConfig =
        synchronized(lock) {
            val next = transform(loadLocked())
            runCatching { configFile.writeText(JsonInstant.encodeToString(next)) }
            cached = next
            cachedStamp = stampLocked()
            next
        }

    private fun loadLocked(): WorkspaceMountConfig {
        val stamp = stampLocked()
        cached?.let { if (cachedStamp == stamp) return it }

        val loaded = runCatching {
            if (configFile.isFile) {
                JsonInstant.decodeFromString<WorkspaceMountConfig>(configFile.readText())
            } else {
                WorkspaceMountConfig()
            }
        }.getOrElse { WorkspaceMountConfig() }

        cached = loaded
        cachedStamp = stamp
        return loaded
    }

    private fun stampLocked(): Long = if (configFile.isFile) configFile.lastModified() else -1L

    private fun WorkspaceMountConfig.upsert(entry: WorkspaceMountEntry): WorkspaceMountConfig =
        copy(entries = entries.filterNot { it.id == entry.id } + entry)

    @Volatile
    private var cached: WorkspaceMountConfig? = null

    @Volatile
    private var cachedStamp: Long = -1L

    private companion object {
        const val CONFIG_FILE_NAME = "workspace-mounts.json"
    }
}
