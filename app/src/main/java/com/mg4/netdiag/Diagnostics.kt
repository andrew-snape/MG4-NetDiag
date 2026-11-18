package com.mg4.netdiag

import android.content.Context
import android.net.Network
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/** What each tab runs. Every function blocks; call from a background thread. */
class Diagnostics(private val context: Context) {

    val publicIps = listOf("1.1.1.1" to "Cloudflare", "8.8.8.8" to "Google DNS", "9.9.9.9" to "Quad9")
    val publicIpv6 = "2606:4700:4700::1111" to "Cloudflare IPv6"
    val hostnames = listOf("google.com", "cloudflare.com", "abc.net.au", "github.com", "apple.com")

    /** Which networks a test runs on. */
    enum class Scope(val label: String) { DEFAULT("Default network"), ALL("All networks"), WIFI("Wi-Fi only"), CAR("Car 4G only") }

    fun networks(scope: Scope): List<NetSnapshot> {
        val all = NetInfo.snapshots(context).filter { it.kind != Kind.VPN || it.isDefault }
        return when (scope) {
            Scope.DEFAULT -> all.filter { it.isDefault }
            Scope.ALL -> all
            Scope.WIFI -> all.filter { it.kind == Kind.WIFI }
            Scope.CAR -> all.filter { it.kind == Kind.CAR || it.kind == Kind.CELL || it.kind == Kind.OTHER }
        }
    }

    // ------------------------------------------------------------------ shared pieces

    private fun pingStatus(r: Probes.PingResult, tcpOk: Boolean): Status = when {
        r.received == 0 -> if (tcpOk) Status.WARN else Status.BAD
        r.lossPercent > 20 || (r.avg ?: 0.0) > 250 -> Status.WARN
        r.lossPercent > 0 || (r.avg ?: 0.0) > 120 -> Status.WARN
        else -> Status.OK
    }

    fun formatPing(r: Probes.PingResult): String {
        val via = if (r.method == "ICMP") "" else " · ${r.method}"
        if (r.received == 0) return "no reply (${r.sent} sent${r.error?.let { ", $it" } ?: ""})$via"
        val loss = String.format(Locale.US, "%.0f", r.lossPercent)
        val jit = r.jitter?.let { " · jitter ${Probes.ms(it)}" }.orEmpty()
        return "${r.received}/${r.sent} replies · $loss% loss · min/avg/max ${Probes.ms(r.min)}/${Probes.ms(r.avg)}/${Probes.ms(r.max)} ms$jit$via"
    }

    /**
     * Pings [target] (an IP or hostname) over [net], then tries a TCP connect so "ICMP filtered"
     * and "unreachable" read differently. Fills [row].
     */
    fun pingInto(row: Panel.Row, net: NetSnapshot?, target: String, count: Int = 5, tcpPorts: List<Int> = listOf(443)) {
        val network = net?.network
        val isDefault = net?.isDefault ?: true
        val (addr, err) = Probes.parseTarget(network, target)
        if (addr == null) { row.set("DNS failed: $err", Status.BAD); return }
        val prefix = if (Parsers.isIpLiteral(target)) "" else "${addr.hostAddress} · "
        row.set("${prefix}pinging…", Status.PENDING)
        val r = Probes.ping(network, addr, count, isDefault = isDefault)
        var tcpText = ""
        var tcpOk = false
        for (port in tcpPorts) {
            val t = Probes.tcpConnect(network, addr, port, 2500)
            if (t.isSuccess) { tcpOk = true; tcpText = " · TCP $port ${Probes.ms(t.getOrNull())} ms"; break }
            else if (r.received == 0) tcpText = " · TCP $port ${Probes.describe(t.exceptionOrNull()!!)}"
        }
        val hint = if (r.received == 0 && tcpOk) "\n(no ICMP reply but TCP works: ping is filtered, the host is reachable)" else ""
        row.set(prefix + formatPing(r) + tcpText + hint, pingStatus(r, tcpOk))
    }

    /** Fills [row] with HTTP 204 from Google's connectivity check, bound to [net]. */
    fun internetInto(row: Panel.Row, net: NetSnapshot) {
        val r = Probes.http(net.network, "http://connectivitycheck.gstatic.com/generate_204", 1024)
        when {
            r.error != null -> row.set("failed: ${r.error}", Status.BAD)
            r.code == 204 -> row.set("online (HTTP 204 in ${r.ms} ms)", Status.OK)
            r.code in 300..399 -> row.set("HTTP ${r.code}: redirected, likely a login page (captive portal)", Status.WARN)
            else -> row.set("HTTP ${r.code} in ${r.ms} ms: something in the way answers instead", Status.WARN)
        }
    }

    /** Public address and country seen through [net] (Cloudflare trace). Returns the country code. */
    fun externalInto(row: Panel.Row, net: NetSnapshot, url: String = "https://1.1.1.1/cdn-cgi/trace"): String? {
        var r = Probes.http(net.network, url, 4096)
        var note = ""
        if (r.error?.startsWith("TLS failed") == true) {
            // A head unit that booted with the wrong clock fails every TLS check: try plain HTTP.
            r = Probes.http(net.network, url.replaceFirst("https://", "http://"), 4096)
            note = " (over HTTP; TLS failed, check the head unit's clock)"
        }
        if (r.error != null || r.code != 200) {
            row.set("failed: ${r.error ?: "HTTP ${r.code}"}", Status.BAD)
            return null
        }
        val f = Parsers.cloudflareTrace(r.body)
        val loc = f["loc"]
        val status = if (loc == null || loc == "AU") Status.OK else Status.WARN
        row.set("${f["ip"] ?: "?"} · country ${loc ?: "?"} · Cloudflare ${f["colo"] ?: "?"} · ${r.ms} ms$note", if (note.isEmpty()) status else Status.WARN)
        return loc
    }

    private fun addrList(list: List<InetAddress>): String =
        list.joinToString(", ") { it.hostAddress?.substringBefore('%').orEmpty() }.ifEmpty { "none" }

    /** The static facts about one network as rows. */
    fun describeInto(s: Panel.Section, n: NetSnapshot) {
        s.row("Default network", if (n.isDefault) "yes: apps use this one" else "no", if (n.isDefault) Status.INFO else Status.PENDING)
        s.row("Interface", n.iface ?: "?", Status.INFO)
        s.row("Host IPv4", n.ipv4.joinToString(", ").ifEmpty { "none" }, if (n.ipv4.isEmpty()) Status.WARN else Status.INFO)
        s.row("Host IPv6", n.ipv6.joinToString("\n").ifEmpty { "none" }, Status.INFO)
        s.row("Gateway", addrList(n.gateways), if (n.gateways.isEmpty()) Status.WARN else Status.INFO)
        s.row("DNS servers", addrList(n.dns), if (n.dns.isEmpty()) Status.WARN else Status.INFO)
        n.domains?.takeIf { it.isNotBlank() }?.let { s.row("Search domain", it, Status.INFO) }
        n.mtu?.let { s.row("MTU", "$it", Status.INFO) }
        s.row(
            "Android says",
            buildList {
                add(if (n.validated) "internet validated" else "NOT validated")
                if (n.captivePortal) add("captive portal")
                add(if (n.metered) "metered" else "unmetered")
                if (n.downKbps > 0) add("link est. ↓${n.downKbps / 1000} ↑${n.upKbps / 1000} Mbit/s")
            }.joinToString(" · "),
            if (n.validated) Status.OK else Status.WARN,
        )
    }

    /** The standard live checks for one network. Returns the country seen, if any. */
    fun quickChecksInto(s: Panel.Section, n: NetSnapshot): String? {
        val gw = n.ipv4Gateway
        val gwRow = s.row("Ping gateway")
        val dnsRow = s.row("DNS lookup google.com")
        val pingRow = s.row("Ping 1.1.1.1")
        val webRow = s.row("Internet (HTTP)")
        val extRow = s.row("External IP")
        if (gw == null) gwRow.set("no gateway on this network", Status.WARN)
        else pingInto(gwRow, n, gw.hostAddress!!, 4, listOf(53, 80))
        val l = Probes.resolve(n.network, "google.com")
        if (l.error != null) dnsRow.set("failed: ${l.error} (${l.ms} ms)", Status.BAD)
        else dnsRow.set("${addrList(l.addresses.take(3))} (${l.ms} ms)", if (l.ms > 1000) Status.WARN else Status.OK)
        pingInto(pingRow, n, "1.1.1.1", 4)
        internetInto(webRow, n)
        return externalInto(extRow, n)
    }

    private fun carHint(s: Panel.Section, country: String?) {
        if (country != null && country != "AU") {
            s.note("This connection reaches the internet from outside Australia ($country). The car modem's traffic exits in Europe, so Australian streaming sites can refuse it.", 0xFFFFC857.toInt())
        }
    }

    // ------------------------------------------------------------------ tabs

    fun overview(p: Panel) {
        val gen = p.clear()
        val nets = NetInfo.snapshots(context)
        val summary = p.section("Summary")
        val def = nets.firstOrNull { it.isDefault }
        summary.row("Default connection", def?.title ?: "none: offline", if (def == null) Status.BAD else Status.INFO)
        summary.row("Networks up", nets.joinToString(", ") { it.title }.ifEmpty { "none" }, if (nets.isEmpty()) Status.BAD else Status.INFO)
        val verdicts = nets.associateWith { summary.row(it.title) }
        for (n in nets) {
            if (p.generation != gen) return
            val s = p.section(n.title)
            describeInto(s, n)
            val country = quickChecksInto(s, n)
            if (n.kind == Kind.CAR) carHint(s, country)
            val rows = s.lines()
            val failed = rows.count { it.startsWith("[FAIL]") }
            val warned = rows.count { it.startsWith("[WARN]") }
            verdicts[n]?.set(
                when {
                    failed > 0 -> "$failed check(s) failed, $warned warning(s)"
                    warned > 0 -> "working, $warned warning(s)"
                    else -> "all checks passed"
                } + (country?.let { " · exits in $it" } ?: ""),
                when { failed > 0 -> Status.BAD; warned > 0 -> Status.WARN; else -> Status.OK },
            )
        }
        val ifs = p.section("All interfaces (kernel)")
        NetInfo.interfaces().forEach { line ->
            val name = line.substringBefore(':')
            ifs.row(name, line.substringAfter(": "), if (line.contains(": up")) Status.INFO else Status.PENDING)
        }
        val dev = p.section("Head unit")
        dev.row("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})", Status.INFO)
        dev.row("Firmware", NetInfo.sysprop("ro.build.mt2712.version").ifEmpty { "?" }, Status.INFO)
        dev.row("Model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", Status.INFO)
    }

    fun wifi(p: Panel, hasLocation: Boolean) {
        p.clear()
        val w = NetInfo.wifi(context)
        val net = NetInfo.snapshots(context).firstOrNull { it.kind == Kind.WIFI }
        val s = p.section("Wi-Fi connection")
        if (w == null) { s.row("Wi-Fi", "not available on this unit", Status.BAD); return }
        s.row("Wi-Fi radio", if (w.enabled) "on" else "off", if (w.enabled) Status.OK else Status.BAD)
        if (w.ip == null && net == null) {
            s.row("Connected", "no", Status.BAD)
        } else {
            s.row("Network name (SSID)", w.ssid ?: if (w.ssidHidden) {
                if (hasLocation) "hidden by Android: turn Location on in the head unit's settings" else "hidden: allow Location for this app to see it"
            } else "?", if (w.ssid != null) Status.INFO else Status.WARN)
            w.bssid?.let { s.row("Access point (BSSID)", it, Status.INFO) }
            w.rssi?.let { s.row("Signal", "$it dBm · ${Parsers.rssiVerdict(it)}", if (it >= -67) Status.OK else if (it >= -80) Status.WARN else Status.BAD) }
            w.linkMbps?.let { s.row("Link speed", "$it Mbit/s", if (it >= 50) Status.OK else Status.WARN) }
            w.freqMhz?.let { s.row("Band / channel", "${Parsers.wifiBand(it)} · channel ${Parsers.wifiChannel(it) ?: "?"} ($it MHz)", Status.INFO) }
            w.ip?.let { s.row("Host IP", "$it${w.netmask?.let { m -> " / $m" } ?: ""}", Status.INFO) }
            w.dhcpGateway?.let { s.row("Gateway (DHCP)", it, Status.INFO) }
            if (w.dhcpDns.isNotEmpty()) s.row("DNS (DHCP)", w.dhcpDns.joinToString(", "), Status.INFO)
            w.dhcpLeaseSec?.let { s.row("DHCP lease", "${it / 60} min", Status.INFO) }
            if (w.ip?.startsWith("192.168.43.") == true || w.ip?.startsWith("172.20.10.") == true) {
                s.note("Looks like a phone hotspot (Android 192.168.43.x / iPhone 172.20.10.x).")
            }
        }
        if (net != null) {
            val t = p.section("Tests over Wi-Fi")
            describeInto(t, net)
            val gwRow = t.row("Ping gateway")
            net.ipv4Gateway?.let { pingInto(gwRow, net, it.hostAddress!!, 5, listOf(53, 80)) }
                ?: gwRow.set("no gateway on this network", Status.WARN)
            net.dns.take(2).forEach { pingInto(t.row("Ping DNS ${it.hostAddress}"), net, it.hostAddress!!, 4, listOf(53)) }
            pingInto(t.row("Ping 1.1.1.1"), net, "1.1.1.1", 5)
            pingInto(t.row("Ping google.com"), net, "google.com", 5)
            internetInto(t.row("Internet (HTTP)"), net)
            externalInto(t.row("External IP"), net)
        }
        val scan = NetInfo.wifiScan(context)
        val near = p.section("Nearby Wi-Fi (${scan.size})")
        if (scan.isEmpty()) {
            near.row("Scan", if (hasLocation) "no results yet (Android scans every few minutes)" else "needs Location permission", Status.PENDING)
        }
        scan.take(15).forEach {
            near.row(it.ssid, "${it.rssi} dBm · ${Parsers.wifiBand(it.freq)} ch ${Parsers.wifiChannel(it.freq) ?: "?"} · ${it.security} · ${it.bssid}",
                if (it.rssi >= -67) Status.OK else if (it.rssi >= -80) Status.WARN else Status.BAD)
        }
    }

    fun car(p: Panel) {
        p.clear()
        val nets = NetInfo.snapshots(context).filter { it.kind == Kind.CAR || it.kind == Kind.CELL || it.kind == Kind.OTHER }
        val intro = p.section("Car 4G modem (TBOX)")
        intro.note("The MG4's 4G modem is in the TBOX, not the head unit. Android sees it as a wired (Ethernet) network, so signal strength and the SIM aren't visible here.")
        if (nets.isEmpty()) {
            intro.row("Car internet", "no car modem network is up", Status.BAD)
            intro.note("The TBOX may still be starting, asleep, out of coverage, or the car's connected services may be off.")
        }
        for (n in nets) {
            val s = p.section(n.title)
            describeInto(s, n)
            val country = quickChecksInto(s, n)
            pingInto(s.row("Ping google.com"), n, "google.com", 5)
            n.dns.take(2).forEach { pingInto(s.row("Ping DNS ${it.hostAddress}"), n, it.hostAddress!!, 4, listOf(53)) }
            carHint(s, country)
        }
        val t = p.section("Head unit telephony")
        NetInfo.telephony(context).forEach { (k, v) -> t.row(k, v, Status.INFO) }
    }

    fun dns(p: Panel, scope: Scope, extra: String?) {
        p.clear()
        val hosts = (listOfNotNull(extra?.trim()?.takeIf { it.isNotEmpty() }) + hostnames).distinct()
        val nets = networks(scope)
        if (nets.isEmpty()) { p.section("DNS").row("Networks", "none for: ${scope.label}", Status.BAD); return }
        for (n in nets) {
            val s = p.section("DNS servers · ${n.title}")
            if (n.dns.isEmpty()) s.row("Servers", "none set", Status.BAD)
            n.dns.forEach { d ->
                val r = s.row(d.hostAddress?.substringBefore('%') ?: "?")
                val tcp = Probes.tcpConnect(n.network, d, 53, 2500)
                val ping = Probes.ping(n.network, d, 3, isDefault = n.isDefault)
                val ok = tcp.isSuccess || ping.received > 0
                r.set(
                    (if (tcp.isSuccess) "TCP 53 ${Probes.ms(tcp.getOrNull())} ms" else "TCP 53 ${Probes.describe(tcp.exceptionOrNull()!!)}") +
                        " · ping " + (if (ping.received > 0) "${Probes.ms(ping.avg)} ms" else "no reply") +
                        (Probes.reverse(d)?.let { " · $it" } ?: ""),
                    if (ok) Status.OK else Status.WARN,
                )
            }
            val l = p.section("Lookups · ${n.title}")
            for (h in hosts) {
                val row = l.row(h)
                val sys = Probes.resolve(n.network, h)
                val doh = Probes.resolveDoh(n.network, h)
                val v4 = sys.addresses.filterIsInstance<Inet4Address>()
                val v6 = sys.addresses.filterIsInstance<Inet6Address>()
                val sysText = if (sys.error != null) "network DNS failed: ${sys.error}"
                else "${addrList(v4.take(3))}${if (v6.isNotEmpty()) " + ${v6.size} IPv6" else ""} · ${sys.ms} ms"
                val dohText = if (doh.error != null) "DoH failed: ${doh.error}" else "DoH (1.1.1.1) ${doh.ms} ms"
                val status = when {
                    sys.error != null && doh.error == null -> Status.BAD // DNS broken, internet fine
                    sys.error != null -> Status.BAD
                    sys.ms > 1500 -> Status.WARN
                    else -> Status.OK
                }
                row.set("$sysText\n$dohText", status)
                if (sys.error != null && doh.error == null) l.note("$h: the network's DNS fails but DNS over HTTPS works, so the DNS server is the problem, not the internet.", 0xFFFFC857.toInt())
            }
        }
    }

    fun ping(p: Panel, scope: Scope, extra: String?, count: Int) {
        p.clear()
        val nets = networks(scope)
        if (nets.isEmpty()) { p.section("Ping").row("Networks", "none for: ${scope.label}", Status.BAD); return }
        for (n in nets) {
            val suffix = if (nets.size > 1 || scope != Scope.DEFAULT) " · ${n.title}" else ""
            extra?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
                pingInto(p.section("Your target$suffix").row(t), n, t, count, listOf(443, 80))
            }
            val g = p.section("Gateway$suffix")
            if (n.gateways.isEmpty()) g.row("Gateway", "none", Status.WARN)
            n.gateways.forEach { pingInto(g.row(it.hostAddress!!.substringBefore('%')), n, it.hostAddress!!, count, listOf(53, 80)) }
            val d = p.section("DNS servers$suffix")
            n.dns.forEach { pingInto(d.row(it.hostAddress!!.substringBefore('%')), n, it.hostAddress!!, count, listOf(53)) }
            val ips = p.section("Internet IPs$suffix")
            publicIps.forEach { (ip, name) -> pingInto(ips.row("$ip ($name)"), n, ip, count) }
            if (n.ipv6.isNotEmpty()) pingInto(ips.row("${publicIpv6.first} (${publicIpv6.second})"), n, publicIpv6.first, count)
            val hs = p.section("Hostnames$suffix")
            hostnames.forEach { pingInto(hs.row(it), n, it, count) }
        }
    }

    /**
     * Pings the gateway, 1.1.1.1 and google.com once a second on [net] until [keepGoing] is false,
     * showing the last reply and stats over the last 60 seconds.
     */
    fun livePing(p: Panel, net: NetSnapshot, extra: String?, keepGoing: () -> Boolean) {
        p.clear()
        val s = p.section("Live ping · ${net.title} · once a second")
        val targets = buildList {
            net.ipv4Gateway?.let { add("Gateway ${it.hostAddress}" to it.hostAddress!!) }
            extra?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it to it) }
            add("1.1.1.1" to "1.1.1.1")
            add("google.com" to "google.com")
        }.map { (label, t) -> Triple(s.row(label, "resolving…"), t, ArrayDeque<Double?>()) }
        val resolved = targets.associate { (_, t, _) -> t to Probes.parseTarget(net.network, t).first }
        while (keepGoing()) {
            val start = System.currentTimeMillis()
            for ((row, t, hist) in targets) {
                if (!keepGoing()) return
                val addr = resolved[t]
                if (addr == null) { row.set("can't resolve", Status.BAD); continue }
                val r = Probes.ping(net.network, addr, 1, 900, 0, net.isDefault)
                hist.addLast(r.times.firstOrNull())
                while (hist.size > 60) hist.removeFirst()
                val got = hist.filterNotNull()
                val loss = (hist.size - got.size) * 100 / hist.size
                val last = r.times.firstOrNull()?.let { "${Probes.ms(it)} ms" } ?: "timeout"
                val status = when { r.times.isEmpty() -> Status.BAD; loss > 10 || (got.average()) > 200 -> Status.WARN; else -> Status.OK }
                row.set("last $last · avg ${Probes.ms(got.takeIf { it.isNotEmpty() }?.average())} ms · max ${Probes.ms(got.maxOrNull())} ms · loss $loss% of ${hist.size}", status)
            }
            val wait = 1000 - (System.currentTimeMillis() - start)
            if (wait > 0) Thread.sleep(wait)
        }
    }

    fun traceroute(p: Panel, target: String, pingCount: Int) {
        val gen = p.clear()
        val def = NetInfo.default(context)
        val head = p.section("Traceroute to $target")
        if (def == null) { head.row("Network", "offline", Status.BAD); return }
        head.row("Runs on", "${def.title} (traceroute can only use the default network)", Status.INFO)
        val (addr, err) = Probes.parseTarget(def.network, target)
        val v4 = if (addr is Inet6Address) {
            Probes.resolve(def.network, target).addresses.firstOrNull { it is Inet4Address }
        } else addr
        if (v4 == null) { head.row("Target", "can't resolve: ${err ?: "no IPv4 address"}", Status.BAD); return }
        head.row("Target address", v4.hostAddress!!, Status.INFO)
        val hopsSection = p.section("Route")
        val hopRows = HashMap<Int, Panel.Row>()
        val hops = Probes.traceroute(v4) { hop ->
            val label = "Hop ${hop.ttl}"
            val text = hop.address ?: "*  (no answer)"
            hopRows[hop.ttl] = hopsSection.row(label, text, when {
                hop.reached -> Status.OK
                hop.address == null -> Status.PENDING
                else -> Status.INFO
            })
        }
        if (p.generation != gen) return
        val reached = hops.any { it.reached }
        head.row("Result", if (reached) "reached in ${hops.size} hops" else "destination didn't answer within ${hops.size} hops",
            if (reached) Status.OK else Status.WARN)
        if (hops.all { it.address == null }) {
            head.note("No router answered. The head unit's ping may not report 'time exceeded', or the network filters it.", 0xFFFFC857.toInt())
            return
        }
        // Names for the hops, then "pings to hops" (mtr-style).
        val ps = p.section("Pings to hops ($pingCount each)")
        for (hop in hops) {
            if (p.generation != gen) return
            val ip = hop.address ?: continue
            val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: continue
            val name = Probes.reverse(a)
            if (name != null) hopRows[hop.ttl]?.let { it.set("$ip  ($name)", it.status) }
            val row = ps.row("Hop ${hop.ttl}  $ip")
            val r = Probes.ping(def.network, a, pingCount, 1500, 200, true)
            row.set(formatPing(r) + if (r.received == 0) "\n(routers often ignore pings to themselves: not a fault on its own)" else "",
                when {
                    r.received == 0 -> Status.WARN
                    r.lossPercent > 20 || (r.avg ?: 0.0) > 250 -> Status.WARN
                    else -> Status.OK
                })
        }
    }

    fun external(p: Panel, scope: Scope) {
        p.clear()
        val nets = networks(scope)
        if (nets.isEmpty()) { p.section("External IP").row("Networks", "none for: ${scope.label}", Status.BAD); return }
        for (n in nets) {
            val s = p.section(n.title)
            s.row("Host IP (local)", n.ipv4.joinToString(", ").ifEmpty { "none" }, Status.INFO)
            val country = externalInto(s.row("Public IPv4"), n, "https://1.1.1.1/cdn-cgi/trace")
            val v6 = s.row("Public IPv6")
            if (n.ipv6.isEmpty()) v6.set("no IPv6 on this network", Status.PENDING)
            else externalInto(v6, n, "https://[2606:4700:4700::1111]/cdn-cgi/trace")
            val info = s.row("Provider / location")
            val r = Probes.http(n.network, "https://ipinfo.io/json", 4096)
            if (r.error != null || r.code != 200) info.set("lookup failed: ${r.error ?: "HTTP ${r.code}"}", Status.WARN)
            else {
                val org = Parsers.jsonField(r.body, "org")
                val place = listOfNotNull(Parsers.jsonField(r.body, "city"), Parsers.jsonField(r.body, "region"), Parsers.jsonField(r.body, "country")).joinToString(", ")
                info.set(listOfNotNull(org, place.ifEmpty { null }).joinToString(" · ").ifEmpty { "?" }, Status.INFO)
            }
            val local = n.ipv4.firstOrNull()?.substringBefore('/')
            val cgnat = local != null && local.startsWith("100.") && local.split('.').getOrNull(1)?.toIntOrNull() in 64..127
            if (cgnat) s.note("The local address is in 100.64.0.0/10: the provider uses carrier-grade NAT.")
            if (n.kind == Kind.CAR) carHint(s, country)
        }
    }
}
