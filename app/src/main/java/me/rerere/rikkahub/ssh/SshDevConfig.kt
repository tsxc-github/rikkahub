package me.rerere.rikkahub.ssh

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Never bind wildcard/cellular/VPN interfaces. Ed25519 public-key authentication only. */
enum class SshListenMode { LOOPBACK, LAN }

data class SshDevConfig(
    val workspaceId: String,
    val port: Int,
    val publicKeys: List<String>,
    val listenMode: SshListenMode = SshListenMode.LOOPBACK,
    val bindAddress: String = "127.0.0.1",
    val bindInterface: String = "",
) {
    fun validated(): SshDevConfig {
        require(UUID.fromString(workspaceId).toString() == workspaceId.lowercase()) { "工作区 UUID 无效" }
        require(port in 1024..65535) { "端口必须在 1024–65535 之间" }
        require(publicKeys.isNotEmpty() && publicKeys.size <= 20) { "请添加 1–20 个电脑公钥" }
        if (listenMode == SshListenMode.LOOPBACK) {
            require(bindAddress == "127.0.0.1" && bindInterface.isEmpty()) { "本机模式只能绑定 127.0.0.1" }
        } else {
            require(isPrivateLanIpv4(bindAddress)) { "局域网必须选择具体的私有 IPv4 地址，不能是通配、公网或主机名" }
            require(isLanInterfaceName(bindInterface)) { "请选择 Wi-Fi、热点或以太网接口，不支持蜂窝/VPN" }
        }
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
    val listenMode: SshListenMode = SshListenMode.LOOPBACK,
    val bindAddress: String = "127.0.0.1",
    val bindInterface: String = "",
)

/** Syntax-only; Android must additionally confirm this exact address is currently on this interface. */
fun isPrivateLanIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
    val numbers = parts.map { it.toInt() }
    if (numbers.any { it !in 0..255 }) return false
    return numbers[0] == 10 || (numbers[0] == 172 && numbers[1] in 16..31) || (numbers[0] == 192 && numbers[1] == 168)
}

fun isLanInterfaceName(name: String): Boolean {
    val lower = name.lowercase()
    if (lower.contains("vpn") ||
        Regex("^(v4-)?(rmnet|r_rmnet|ccmni|pdp|wwan|clat|tun|tap|wg|ppp|utun|ipsec)").containsMatchIn(lower)) {
        return false
    }
    return Regex("^(wlan|swlan|wifi|ap|softap|br|bridge|eth|enp|enx|eno)[a-z0-9_.:-]{0,24}$").matches(lower)
}

data class SshLanAddress(val interfaceName: String, val address: String) {
    val label: String get() = "$interfaceName · $address"
}

fun lanAddressAvailable(config: SshDevConfig, addresses: List<SshLanAddress>): Boolean =
    config.listenMode == SshListenMode.LOOPBACK ||
        addresses.any { it.interfaceName == config.bindInterface && it.address == config.bindAddress }
