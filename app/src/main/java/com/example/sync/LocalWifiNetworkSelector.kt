package com.example.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import java.net.Inet4Address
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

interface NetworkBoundOpener {
    fun selectWifiNetworkForLocalHost(destAddress: Inet4Address): Result<BoundWifiNetwork>
}

data class BoundWifiNetwork(
    val network: Network?,
    val openConnection: (URL) -> HttpsURLConnection,
    val registerLossCallback: ((onLost: () -> Unit) -> AutoCloseable)
)

/**
 * Selects a connected non-VPN Wi-Fi [Network] with a directly connected IPv4 subnet route to
 * [destAddress] and opens requests scoped specifically to that [Network] with [Proxy.NO_PROXY].
 */
class AndroidWifiNetworkSelector(private val context: Context) : NetworkBoundOpener {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun selectWifiNetworkForLocalHost(destAddress: Inet4Address): Result<BoundWifiNetwork> {
        val candidates = collectNetworkCandidates()
        var foundWifi = false

        for (network in candidates) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!isEligibleWifiCapabilities(caps)) continue
            foundWifi = true

            val props = connectivityManager.getLinkProperties(network) ?: continue
            if (isDirectlyConnectedLocalIpv4(props, destAddress)) {
                return Result.success(
                    BoundWifiNetwork(
                        network = network,
                        openConnection = { url ->
                            (network.openConnection(url, Proxy.NO_PROXY) as HttpsURLConnection)
                        },
                        registerLossCallback = { onLost ->
                            val callback = object : ConnectivityManager.NetworkCallback() {
                                override fun onLost(lostNetwork: Network) {
                                    if (lostNetwork == network) {
                                        onLost()
                                    }
                                }
                            }
                            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                            val specificRequest = android.net.NetworkRequest.Builder()
                                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                .build()
                            val specificCallback = object : ConnectivityManager.NetworkCallback() {
                                override fun onLost(lostNetwork: Network) {
                                    if (lostNetwork == network) {
                                        onLost()
                                    }
                                }
                            }
                            val registeredSpecific = runCatching {
                                connectivityManager.registerNetworkCallback(specificRequest, specificCallback)
                                true
                            }.getOrDefault(false)
                            AutoCloseable {
                                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                                if (registeredSpecific) {
                                    runCatching { connectivityManager.unregisterNetworkCallback(specificCallback) }
                                }
                            }
                        }
                    )
                )
            }
        }

        val reason = if (!foundWifi) {
            "Connect your Pixel to the same Wi-Fi network as your Mac (cellular and VPN connections are not used for local sync)."
        } else {
            "Mac address ${destAddress.hostAddress} is not on a directly connected local Wi-Fi subnet."
        }
        return Result.failure(SyncNetworkException(reason))
    }

    @Suppress("DEPRECATION")
    private fun collectNetworkCandidates(): List<Network> {
        val active = connectivityManager.activeNetwork
        val all = connectivityManager.allNetworks?.toList().orEmpty()
        return buildList {
            if (active != null) add(active)
            all.forEach { if (it != active) add(it) }
        }
    }

    companion object {
        fun isEligibleWifiCapabilities(caps: NetworkCapabilities): Boolean {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return false
            }
            return true
        }

        fun isDirectlyConnectedLocalIpv4(
            linkProperties: LinkProperties,
            destAddress: Inet4Address
        ): Boolean {
            val destInt = ipv4BytesToInt(destAddress.address)

            val matchesInterfaceSubnet = linkProperties.linkAddresses.any { la ->
                val addr = la.address as? Inet4Address ?: return@any false
                val prefix = la.prefixLength
                prefix in 1..32 && sameIpv4Subnet(ipv4BytesToInt(addr.address), destInt, prefix)
            }
            if (matchesInterfaceSubnet) return true

            val matchesDirectRoute = linkProperties.routes.any { route ->
                isDirectNonGatewayIpv4Route(route, destInt)
            }
            return matchesDirectRoute
        }

        private fun isDirectNonGatewayIpv4Route(route: RouteInfo, destInt: Int): Boolean {
            val destPrefix = route.destination
            val routeAddr = destPrefix.address as? Inet4Address ?: return false
            val prefixLen = destPrefix.prefixLength
            if (prefixLen !in 1..32) return false
            val hasGateway = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                route.hasGateway()
            } else {
                val gw = route.gateway
                gw != null && !gw.isAnyLocalAddress
            }
            if (hasGateway) return false
            return sameIpv4Subnet(ipv4BytesToInt(routeAddr.address), destInt, prefixLen)
        }

        fun sameIpv4Subnet(addrA: Int, addrB: Int, prefixLength: Int): Boolean {
            if (prefixLength <= 0 || prefixLength > 32) return false
            val mask = (-1 shl (32 - prefixLength))
            return (addrA and mask) == (addrB and mask)
        }

        fun ipv4BytesToInt(bytes: ByteArray): Int {
            return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        }
    }
}
