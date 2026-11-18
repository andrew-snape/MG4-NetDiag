package com.mg4.netdiag

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** The kinds of connection the head unit can have. The car's 4G is the TBOX, seen as Ethernet. */
enum class Kind(val label: String) {
    WIFI("Wi-Fi"),
    CAR("Car 4G modem"),
    CELL("Mobile data"),
    VPN("VPN"),
    OTHER("Other");
}

/** One network Android knows about, with its addresses, gateway and DNS servers. */
class NetSnapshot(
    val network: Network,
    val kind: Kind,
    val iface: String?,
    val addresses: List<LinkAddress>,
    val gateways: List<InetAddress>,
    val dns: List<InetAddress>,
    val domains: String?,
    val validated: Boolean,
    val internet: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    val isDefault: Boolean,
    val downKbps: Int,
    val upKbps: Int,
) {
    val ipv4: List<String> get() = addresses.filter { it.address is Inet4Address }.map { "${it.address.hostAddress}/${it.prefixLength}" }
    val ipv6: List<String> get() = addresses.filter { it.address is Inet6Address && !it.address.isLinkLocalAddress }
        .map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" }
    val ipv4Gateway: InetAddress? get() = gateways.firstOrNull { it is Inet4Address } ?: gateways.firstOrNull()

    val title: String get() = kind.label + (iface?.let { " ($it)" } ?: "")

    val mtu: Int? get() = iface?.let { runCatching { NetworkInterface.getByName(it)?.mtu }.getOrNull() }
}

object NetInfo {

    fun cm(context: Context) = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    fun snapshots(context: Context): List<NetSnapshot> {
        val cm = cm(context)
        val active = cm.activeNetwork
        return cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val link = cm.getLinkProperties(n) ?: return@mapNotNull null
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Kind.VPN
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Kind.CAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Kind.CELL
                else -> Kind.OTHER
            }
            NetSnapshot(
                network = n,
                kind = kind,
                iface = link.interfaceName,
                addresses = link.linkAddresses,
                gateways = link.routes.filter { it.isDefaultRoute && it.gateway != null && !it.gateway!!.isAnyLocalAddress }
                    .mapNotNull { it.gateway }.distinct(),
                dns = link.dnsServers,
                domains = link.domains,
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                isDefault = n == active,
                downKbps = caps.linkDownstreamBandwidthKbps,
                upKbps = caps.linkUpstreamBandwidthKbps,
            )
        }.sortedWith(compareBy({ !it.isDefault }, { it.kind.ordinal })).also { list ->
            list.forEach { Journal.nameNetwork(it.network, it.title) }
        }
    }

    fun default(context: Context): NetSnapshot? = snapshots(context).firstOrNull { it.isDefault }

    /** Every interface the kernel has, including ones Android doesn't treat as a network. */
    fun interfaces(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }.map { ni ->
            val addrs = ni.interfaceAddresses.mapNotNull { a ->
                a.address?.hostAddress?.substringBefore('%')?.let { "$it/${a.networkPrefixLength}" }
            }
            val state = if (ni.isUp) "up" else "down"
            val extra = buildList {
                if (ni.isLoopback) add("loopback")
                if (ni.isPointToPoint) add("point-to-point")
                runCatching { ni.mtu }.getOrNull()?.let { add("MTU $it") }
            }.joinToString(", ")
            "${ni.name}: $state${if (extra.isNotEmpty()) ", $extra" else ""}" +
                if (addrs.isNotEmpty()) "\n  " + addrs.joinToString("\n  ") else ""
        }
    }.getOrElse { listOf("unavailable (${it.message})") }

    // ---- Wi-Fi ----

    class WifiDetails(
        val enabled: Boolean,
        val ssid: String?,
        val bssid: String?,
        val rssi: Int?,
        val linkMbps: Int?,
        val freqMhz: Int?,
        val ip: String?,
        val dhcpGateway: String?,
        val dhcpDns: List<String>,
        val dhcpLeaseSec: Int?,
        val netmask: String?,
        val ssidHidden: Boolean,
    )

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun wifi(context: Context): WifiDetails? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val dhcp = wm.dhcpInfo
        val connected = info != null && info.networkId != -1
        val rawSsid = info?.ssid
        val hidden = rawSsid == null || rawSsid == "<unknown ssid>"
        WifiDetails(
            enabled = wm.isWifiEnabled,
            ssid = if (!connected || hidden) null else rawSsid?.removeSurrounding("\""),
            bssid = info?.bssid?.takeIf { connected && it != "02:00:00:00:00:00" },
            rssi = info?.rssi?.takeIf { connected && it > -127 },
            linkMbps = info?.linkSpeed?.takeIf { connected && it > 0 },
            freqMhz = info?.frequency?.takeIf { connected && it > 0 },
            ip = if (connected) intToIp(info!!.ipAddress) else null,
            dhcpGateway = dhcp?.gateway?.takeIf { connected && it != 0 }?.let(::intToIp),
            dhcpDns = if (connected) listOfNotNull(dhcp?.dns1, dhcp?.dns2).filter { it != 0 }.map(::intToIp) else emptyList(),
            dhcpLeaseSec = dhcp?.leaseDuration?.takeIf { connected && it > 0 },
            netmask = dhcp?.netmask?.takeIf { connected && it != 0 }?.let(::intToIp),
            ssidHidden = connected && hidden,
        )
    }.getOrNull()

    class ScanEntry(val ssid: String, val bssid: String, val rssi: Int, val freq: Int, val security: String)

    @SuppressLint("MissingPermission")
    fun wifiScan(context: Context): List<ScanEntry> = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.scanResults.orEmpty().map {
            val caps = it.capabilities.orEmpty()
            val sec = when {
                caps.contains("SAE") -> "WPA3"
                caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"
                caps.contains("WPA") -> "WPA"
                caps.contains("WEP") -> "WEP"
                else -> "open"
            }
            ScanEntry(it.SSID.orEmpty().ifEmpty { "(hidden)" }, it.BSSID.orEmpty(), it.level, it.frequency, sec)
        }.sortedByDescending { it.rssi }
    }.getOrElse { emptyList() }

    /** WifiManager hands back IPv4 addresses as little-endian ints. */
    fun intToIp(v: Int): String = "${v and 0xff}.${v shr 8 and 0xff}.${v shr 16 and 0xff}.${v shr 24 and 0xff}"

    // ---- Telephony ----

    /** Whatever the head unit's own telephony stack reports. Usually nothing: the 4G lives in the TBOX. */
    @SuppressLint("MissingPermission")
    fun telephony(context: Context): List<Pair<String, String>> {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return listOf("Telephony" to "not present on this head unit")
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Phone type" to when (tm.phoneType) {
            TelephonyManager.PHONE_TYPE_NONE -> "none (no modem in the head unit itself)"
            TelephonyManager.PHONE_TYPE_GSM -> "GSM/LTE"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            else -> tm.phoneType.toString()
        }
        rows += "SIM" to when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "absent"
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
            TelephonyManager.SIM_STATE_UNKNOWN -> "unknown"
            else -> "state ${tm.simState}"
        }
        tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { rows += "Operator" to it }
        tm.simOperatorName?.takeIf { it.isNotBlank() }?.let { rows += "SIM operator" to it }
        tm.networkCountryIso?.takeIf { it.isNotBlank() }?.let { rows += "Network country" to it.uppercase() }
        runCatching { tm.dataNetworkType }.getOrNull()?.let { rows += "Data network type" to networkTypeName(it) }
        runCatching { tm.signalStrength }.getOrNull()?.let { rows += "Signal level" to "${it.level}/4" }
        runCatching { tm.allCellInfo }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cells ->
            rows += "Cells seen" to cells.take(4).joinToString("\n") { c ->
                (if (c.isRegistered) "registered: " else "") + c.toString().take(160)
            }
        }
        return rows
    }

    private fun networkTypeName(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "none"
        20 -> "5G NR"
        else -> "type $t"
    }

    /** Hidden android.os.SystemProperties, read by reflection. Empty if unavailable. */
    fun sysprop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")
}
