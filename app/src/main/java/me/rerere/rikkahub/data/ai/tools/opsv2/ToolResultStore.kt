package me.rerere.rikkahub.data.ai.tools.opsv2

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.*
import me.rerere.workspace.WorkspaceReadChunk
import me.rerere.workspace.readWorkspaceChunk

/** Private immutable snapshots. Locks cover publication, lookup, ranges and pruning. */
internal class ToolResultStore(
    private val dir: File,
    private val maxBytes: Long = 200L * 1024 * 1024,
    private val maxFiles: Int = 200,
    private val leaseMillis: Long = 5 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val valid = Regex("[0-9a-f]{32}")
    private val leases = mutableMapOf<String, Long>()

    @Synchronized
    fun put(scope: String, source: String, text: String, status: String = "complete", markdown: Boolean = false): String {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create result store" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= maxBytes && maxFiles > 0) { "Result exceeds storage capacity" }
        val hash = sha256(bytes)
        manifests().firstOrNull {
            it.second.str("scope") == scope && it.second.str("hash") == hash &&
                it.second.str("source") == source && it.second.str("status") == status && it.second.flag("markdown") == markdown &&
                File(dir, "${it.first}.bin").isFile
        }?.let { (ref, _) -> lease(ref); return ref }
        val ref = UUID.randomUUID().toString().replace("-", "")
        val data = File(dir, "$ref.bin")
        val meta = File(dir, "$ref.json")
        val dataTmp = File(dir, "$ref.bin.part")
        val metaTmp = File(dir, "$ref.json.part")
        try {
            dataTmp.outputStream().use { it.write(bytes) }
            val manifest = buildJsonObject {
                put("v", 2); put("scope", scope); put("source", source)
                put("hash", hash); put("status", status); put("bytes", bytes.size); put("markdown", markdown)
                put("created", now())
            }
            metaTmp.writeText(manifest.toString())
            check(dataTmp.renameTo(data)) { "Cannot publish result data" }
            check(metaTmp.renameTo(meta)) { "Cannot publish result manifest" }
            lease(ref)
            prune()
            if (usage().let { it.first > maxFiles || it.second > maxBytes }) {
                throw IOException("Store full: active references are protected")
            }
            return ref
        } catch (e: Exception) {
            dataTmp.delete(); metaTmp.delete(); data.delete(); meta.delete(); leases.remove(ref)
            throw e
        }
    }

    @Synchronized
    fun manifest(scope: String, ref: String): JsonObject {
        require(valid.matches(ref)) { "Invalid ref" }
        val meta = File(dir, "$ref.json")
        require(meta.isFile && File(dir, "$ref.bin").isFile) { "expired: ref $ref is unavailable" }
        val json = Json.parseToJsonElement(meta.readText()).jsonObject
        require(json.str("scope") == scope && json.int("v", 0) == 2) { "Ref belongs to another conversation" }
        lease(ref)
        return json
    }

    @Synchronized
    fun chunk(scope: String, ref: String, offset: Long, length: Int): WorkspaceReadChunk {
        val meta = manifest(scope, ref)
        val chunk = File(dir, "$ref.bin").readWorkspaceChunk(offset, length)
        return chunk.copy(fingerprint = meta.str("hash") ?: error("Invalid result manifest"))
    }

    @Synchronized
    fun contains(scope: String, ref: String): Boolean = try { manifest(scope, ref); true } catch (_: Exception) { false }

    private fun lease(ref: String) {
        leases[ref] = now() + leaseMillis
        File(dir, "$ref.json").setLastModified(now())
    }

    private fun manifests(): List<Pair<String, JsonObject>> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("[0-9a-f]{32}\\.json")) }
        .mapNotNull { file -> runCatching { file.nameWithoutExtension to Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() }

    private fun usage(): Pair<Int, Long> {
        val data = dir.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{32}\\.bin")) }
        return data.size to data.sumOf { it.length() }
    }

    @Synchronized
    fun prune() {
        val time = now()
        leases.entries.removeAll { it.value <= time }
        // Oldest first. New/actively used references cannot be removed by another tool.
        val ordered = manifests().sortedBy { File(dir, "${it.first}.json").lastModified() }
        var (count, total) = usage()
        for ((ref, _) in ordered) {
            if (count <= maxFiles && total <= maxBytes) break
            if ((leases[ref] ?: 0L) > time) continue
            val data = File(dir, "$ref.bin")
            val length = data.length()
            if (data.delete()) {
                File(dir, "$ref.json").delete(); count--; total -= length
            }
        }
        // Only stale unfinished writes/orphans. Never inspect or delete the v1 directory.
        dir.listFiles().orEmpty().filter { time - it.lastModified() > 3600000 }.forEach { file ->
            if (file.name.endsWith(".part") ||
                (file.name.endsWith(".bin") && !File(dir, file.nameWithoutExtension + ".json").exists())) file.delete()
        }
    }
}
