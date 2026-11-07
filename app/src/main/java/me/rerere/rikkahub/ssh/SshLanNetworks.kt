package me.rerere.rikkahub.ssh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/** The hotspot can exist without being Android's active/default Network. Enumerate actual interfaces. */
internal fun discoverSshLanAddresses(): List<SshLanAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { network ->
        if (!network.isUp || network.isLoopback || network.isPointToPoint ||
            !isLanInterfaceName(network.name)) {
            emptyList()
        } else {
            network.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { address ->
                address.hostAddress?.takeIf(::isPrivateLanIpv4)?.let { SshLanAddress(network.name, it) }
            }
        }
    }.distinct().sortedWith(compareBy({ it.interfaceName }, { it.address }))
}.getOrDefault(emptyList())

/** App-scoped event sources. The service additionally checks while LAN is active, for OEM hotspot gaps. */
internal class SshLanNetworks(context: Context, private val scope: CoroutineScope) {
    private val mutableAddresses = MutableStateFlow<List<SshLanAddress>>(emptyList())
    val addresses: StateFlow<List<SshLanAddress>> = mutableAddresses
    fun refresh() {
        mutableAddresses.value = discoverSshLanAddresses()
    }

    init {
        scope.launch {
            refresh()
            runCatching {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        scope.launch { refresh() }
                    }
                    override fun onLost(network: Network) {
                        scope.launch { refresh() }
                    }
                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties
                    ) {
                        scope.launch { refresh() }
                    }
                })
            }
            // Event only: no hidden API call, tethering control, location or SSID access.
            runCatching {
                ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        scope.launch { refresh() }
                    }
                }, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"), ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
    }
}
