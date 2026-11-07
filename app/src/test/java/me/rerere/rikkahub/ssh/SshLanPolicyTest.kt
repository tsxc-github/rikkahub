package me.rerere.rikkahub.ssh

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class SshLanPolicyTest {
    private val id = "f7f0dbfb-11aa-4fbb-afbc-e43d433e131f"
    private val key = "ssh-ed25519 " + Base64.getEncoder().encodeToString(ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".toByteArray()).putInt(32).put(ByteArray(32) { 1 }).array())
    private fun config(ip: String = "192.168.43.1", network: String = "ap0") = SshDevConfig(id, 2222, listOf(key), SshListenMode.LAN, ip, network)
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }

    @Test fun allPrivateRangesAccepted() { listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.43.1").forEach { assertTrue(it, isPrivateLanIpv4(it)) } }
    @Test fun wildcardsAndPublicRejected() { listOf("0.0.0.0", "::", "::1", "127.0.0.1", "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "169.254.1.1", "100.64.0.1", "224.0.0.1", "255.255.255.255").forEach { assertFalse(it, isPrivateLanIpv4(it)) } }
    @Test fun parserNeverAcceptsDnsOrInjection() { listOf("localhost", "example.com", "192.168.1.1\nListenAddress 0.0.0.0", "192.168.1.1;id", "192.168.1.1:2222", "192.168.01.1", "192.168.1", "192.168.1.256", "192.168.-1.1", "192.168.1.999999999999999999999", " 192.168.1.1").forEach { assertFalse(it, isPrivateLanIpv4(it)) } }
    @Test fun phoneWifiInterfacesAccepted() { listOf("wlan0", "wlan1", "swlan0", "wifi0").forEach { assertTrue(it, isLanInterfaceName(it)) } }
    @Test fun phoneHotspotAndBridgesAccepted() { listOf("ap0", "ap1", "ap_br_wlan2", "softap0", "br0", "bridge0").forEach { assertTrue(it, isLanInterfaceName(it)) } }
    @Test fun ethernetAccepted() { listOf("eth0", "enp0s1", "enx1234", "eno1").forEach { assertTrue(it, isLanInterfaceName(it)) } }
    @Test fun cellularInterfacesRejected() { listOf("rmnet0", "rmnet_data0", "v4-rmnet_data0", "r_rmnet0", "ccmni0", "pdp0", "wwan0", "clat4").forEach { assertFalse(it, isLanInterfaceName(it)) } }
    @Test fun vpnLoopbackAndUnknownRejected() { listOf("tun0", "tap0", "wg0", "ppp0", "utun0", "ipsec0", "lo", "wlan_vpn0", "unknown0", "", "wlan0\n", "wlan0;id").forEach { assertFalse(it, isLanInterfaceName(it)) } }
    @Test fun legacyConstructorStillLoopback() { val old = SshDevConfig(id, 2222, listOf(key)).validated(); assertEquals(SshListenMode.LOOPBACK, old.listenMode); assertEquals("127.0.0.1", old.bindAddress) }
    @Test fun loopbackDoesNotSilentlyAcceptLanFields() { rejects { SshDevConfig(id, 2222, listOf(key), SshListenMode.LOOPBACK, "192.168.1.2", "wlan0").validated() } }
    @Test fun lanConfigValidated() { config().validated() }
    @Test fun lanRequiresSpecificPrivateAddress() { listOf("0.0.0.0", "127.0.0.1", "8.8.8.8", "example.com").forEach { ip -> rejects { config(ip).validated() } } }
    @Test fun lanRequiresApprovedInterface() { listOf("", "tun0", "rmnet_data0").forEach { iface -> rejects { config(network = iface).validated() } } }
    @Test fun sameWifiAddressMatches() { assertTrue(lanAddressAvailable(config("192.168.1.2", "wlan0"), listOf(SshLanAddress("wlan0", "192.168.1.2")))) }
    @Test fun phoneHotspotAddressMatches() { assertTrue(lanAddressAvailable(config(), listOf(SshLanAddress("ap0", "192.168.43.1")))) }
    @Test fun sameIpOnDifferentInterfaceNotAReplacement() { assertFalse(lanAddressAvailable(config(), listOf(SshLanAddress("tun0", "192.168.43.1")))) }
    @Test fun changedIpDoesNotMigrateAutomatically() { assertFalse(lanAddressAvailable(config(), listOf(SshLanAddress("ap0", "192.168.43.2")))) }
    @Test fun lostNetworkInvalidatesLan() { assertFalse(lanAddressAvailable(config(), emptyList())) }
    @Test fun loopbackDoesNotNeedWifi() { assertTrue(lanAddressAvailable(SshDevConfig(id, 2222, listOf(key)), emptyList())) }
    @Test fun wifiAndHotspotMayCoexistWithoutBindingBoth() {
        val both = listOf(SshLanAddress("wlan0", "192.168.1.2"), SshLanAddress("ap0", "192.168.43.1"))
        assertTrue(lanAddressAvailable(config(), both))
        assertFalse(lanAddressAvailable(config("192.168.1.2", "ap0"), both))
    }
}
