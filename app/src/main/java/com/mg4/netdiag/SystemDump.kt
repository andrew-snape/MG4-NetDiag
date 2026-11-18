package com.mg4.netdiag

import android.content.Context
import java.io.File
import java.io.OutputStream

/**
 * Raw system state for the saved file: kernel interfaces, routes and policy rules, DNS and Wi-Fi
 * properties, /proc network tables, Android's connectivity and Wi-Fi dumps, and the system log.
 * dumpsys and the full system log need the platform-signed build (DUMP / READ_LOGS); the debug
 * build gets "Permission denial" there and only its own log lines, which is recorded as is.
 */
object SystemDump {

    private val commands: List<List<String>> = listOf(
        listOf("ip", "addr"),
        listOf("ip", "-s", "link"),
        listOf("ip", "route", "show", "table", "all"),
        listOf("ip", "-6", "route", "show", "table", "all"),
        listOf("ip", "rule"),
        listOf("ip", "-6", "rule"),
        listOf("ip", "neigh"),
        listOf("ifconfig", "-a"),
        listOf("netstat", "-anp"),
        listOf("getprop"),
        listOf("dumpsys", "connectivity"),
        listOf("dumpsys", "netd"),
        listOf("dumpsys", "wifi"),
        listOf("dumpsys", "netstats"),
        listOf("dumpsys", "ethernet"),
        listOf("dumpsys", "telephony.registry"),
        listOf("dumpsys", "netpolicy"),
        listOf("logcat", "-d", "-v", "threadtime", "-b", "all"),
    )

    private val files = listOf(
        "/proc/net/dev", "/proc/net/route", "/proc/net/ipv6_route", "/proc/net/if_inet6", "/proc/net/arp",
        "/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6", "/proc/net/snmp", "/proc/net/netstat",
        "/proc/net/wireless", "/proc/sys/net/ipv4/ping_group_range", "/etc/resolv.conf", "/system/etc/hosts",
        "/proc/version", "/proc/uptime",
    )

    fun writeTo(context: Context, out: OutputStream) {
        fun w(s: String) = out.write(s.toByteArray())
        Journal.log("dump", "System dump started")
        w("\n\n######## Network state from Android (now) ########\n\n")
        NetInfo.snapshots(context).forEach { n ->
            val cm = NetInfo.cm(context)
            w("${n.title}${if (n.isDefault) " [default]" else ""}\n")
            w("  capabilities: ${cm.getNetworkCapabilities(n.network)}\n")
            w("  link: ${cm.getLinkProperties(n.network)}\n\n")
        }
        w("Interfaces:\n" + NetInfo.interfaces().joinToString("\n") + "\n")
        w("\nTelephony:\n" + NetInfo.telephony(context).joinToString("\n") { "  ${it.first}: ${it.second}" } + "\n")
        NetInfo.wifi(context)?.let { wi ->
            w("\nWi-Fi: ssid=${wi.ssid} bssid=${wi.bssid} rssi=${wi.rssi} link=${wi.linkMbps} freq=${wi.freqMhz} ip=${wi.ip} gw=${wi.dhcpGateway} dns=${wi.dhcpDns} lease=${wi.dhcpLeaseSec}\n")
        }
        w("\nWi-Fi scan:\n" + NetInfo.wifiScan(context).joinToString("\n") { "  ${it.ssid} ${it.bssid} ${it.rssi} dBm ${it.freq} MHz ${it.security}" } + "\n")

        for (f in files) {
            w("\n\n######## cat $f ########\n")
            w(runCatching { File(f).readText() }.getOrElse { "(can't read: ${it.message})" })
        }
        for (cmd in commands) {
            w("\n\n######## $ ${cmd.joinToString(" ")} ########\n")
            // Not through Probes.runShell: these are big and already go into this file.
            val text = runCatching {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val f = pool.submit(java.util.concurrent.Callable { p.inputStream.use { String(it.readBytes()) } })
                    if (!p.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroy()
                        (runCatching { f.get(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() ?: "") + "\n(stopped after 45 s)"
                    } else f.get(10, java.util.concurrent.TimeUnit.SECONDS)
                } finally {
                    pool.shutdownNow()
                }
            }.getOrElse { "(failed: ${it.message})" }
            w(text)
            Journal.log("dump", "$ ${cmd.joinToString(" ")}: ${text.length} chars")
        }
        Journal.log("dump", "System dump finished")
    }
}
