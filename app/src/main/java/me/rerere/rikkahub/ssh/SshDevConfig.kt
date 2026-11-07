package me.rerere.rikkahub.ssh

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** This first UI deliberately supports loopback and Ed25519 only. */
data class SshDevConfig(val workspaceId: String, val port: Int, val publicKeys: List<String>) {
    fun validated(): SshDevConfig {
        require(UUID.fromString(workspaceId).toString() == workspaceId.lowercase()) { "工作区 UUID 无效" }
        require(port in 1024..65535) { "端口必须在 1024–65535 之间" }
        require(publicKeys.isNotEmpty() && publicKeys.size <= 20) { "请添加 1–20 个电脑公钥" }
        val normalized = publicKeys.map(::normalizePublicKey)
        require(normalized.distinct().size == normalized.size) { "存在重复公钥" }
        return copy(publicKeys = normalized)
    }
}

/** Reject key options, private keys, multiple lines, controls and malformed wire-format blobs. */
fun normalizePublicKey(input: String): String {
    require(input.length <= 4096 && input.none { it.isISOControl() }) { "公钥必须是单行，不能包含控制字符或私钥" }
    val fields = input.trim().split(Regex(" +"), limit = 3)
    require(fields.size >= 2 && fields[0] == "ssh-ed25519") { "首版仅接受 ssh-ed25519 公钥，不接受私钥或 authorized_keys 选项" }
    val blob = runCatching {
        Base64.getDecoder().decode(
            fields[1]
        )
    }.getOrElse { throw IllegalArgumentException("公钥 Base64 无效") }
    require(blob.size == 51) { "Ed25519 公钥长度无效" }
    val data = ByteBuffer.wrap(blob)
    require(data.int == 11) { "公钥类型无效" }
    val algorithm = ByteArray(11).also(data::get).toString(StandardCharsets.US_ASCII)
    require(algorithm == "ssh-ed25519" && data.int == 32 && data.remaining() == 32) { "Ed25519 公钥格式无效" }
    // Comments have no authentication meaning. Discard them so deduplication is by actual key.
    return "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)}"
}

enum class SshDevPhase { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class SshDevState(
    val phase: SshDevPhase = SshDevPhase.STOPPED,
    val workspaceId: String? = null,
    val workspaceName: String = "",
    val port: Int = 2222,
    val fingerprint: String = "",
    val message: String = "尚未启动",
    val startedAt: Long? = null,
    val ownsProcess: Boolean = false,
)
