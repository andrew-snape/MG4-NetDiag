package com.mg4.netdiag

import android.net.Network
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Network probes. All of them block: call from a background thread.
 *
 * Android routes unbound traffic through the default network only. With Wi-Fi and the car's
 * modem both up, a ping to the modem's gateway would leave over Wi-Fi and fail. So pings, DNS,
 * TCP and HTTP here take a [Network] and bind to it. Pings use an unprivileged ICMP "ping socket"
 * bound with [Network.bindSocket]; if the head unit refuses those, they fall back to the shell
 * ping binary, which only uses the default network.
 */
object Probes {

    // ---------------- Ping ----------------

    class PingResult(
        val target: InetAddress,
        val sent: Int,
        val times: List<Double>,
        val method: String,
        val error: String? = null,
    ) {
        val received get() = times.size
        val lossPercent get() = if (sent == 0) 100.0 else (sent - received) * 100.0 / sent
        val min get() = times.minOrNull()
        val avg get() = times.takeIf { it.isNotEmpty() }?.average()
        val max get() = times.maxOrNull()
        /** Jitter: mean difference between consecutive round trips. */
        val jitter get() = if (times.size < 2) null else times.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()
    }

    @Volatile
    private var icmpSocketsWork: Boolean? = null

    /**
     * [count] echo requests to [target] over [network] (null = default), [intervalMs] apart, each
     * waiting [timeoutMs]. Uses a bound ICMP socket; falls back to shell ping on the default network.
     */
    fun ping(network: Network?, target: InetAddress, count: Int = 4, timeoutMs: Int = 1500, intervalMs: Long = 250, isDefault: Boolean = true): PingResult =
        pingInner(network, target, count, timeoutMs, intervalMs, isDefault).also { r ->
            Journal.log("ping", "${target.hostAddress} over ${Journal.net(network)} via ${r.method}: ${r.received}/${r.sent} replies, " +
                "times ${r.times.joinToString(", ") { ms(it) }} ms${r.error?.let { ", error: $it" } ?: ""}")
        }

    private fun pingInner(network: Network?, target: InetAddress, count: Int, timeoutMs: Int, intervalMs: Long, isDefault: Boolean): PingResult {
        if (icmpSocketsWork != false) {
            try {
                return icmpPing(network, target, count, timeoutMs, intervalMs).also { icmpSocketsWork = true }
            } catch (e: ErrnoException) {
                Journal.error("ping", "ICMP socket to ${target.hostAddress} over ${Journal.net(network)}", e)
                if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM || e.errno == OsConstants.EPROTONOSUPPORT) {
                    Journal.log("ping", "ICMP ping sockets refused (errno ${e.errno}); using shell ping from now on")
                    icmpSocketsWork = false
                } else {
                    return PingResult(target, count, emptyList(), "ICMP", describe(e))
                }
            } catch (e: Exception) {
                Journal.error("ping", "ICMP socket to ${target.hostAddress} over ${Journal.net(network)}", e)
                return PingResult(target, count, emptyList(), "ICMP", describe(e))
            }
        }
        if (!isDefault) {
            return PingResult(target, 0, emptyList(), "none",
                "ICMP sockets refused and shell ping can't use a non-default network; see the TCP result")
        }
        return shellPing(target, count, timeoutMs)
    }

    private fun icmpPing(network: Network?, target: InetAddress, count: Int, timeoutMs: Int, intervalMs: Long): PingResult {
        val v6 = target is Inet6Address
        val fd: FileDescriptor = Os.socket(
            if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET,
            OsConstants.SOCK_DGRAM,
            if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP,
        )
        try {
            network?.bindSocket(fd)
            val times = ArrayList<Double>()
            val rbuf = ByteArray(1500)
            val ident = (System.nanoTime() and 0xffff).toInt()
            for (seq in 1..count) {
                val before = times.size
                val packet = echoPacket(v6, ident, seq)
                val start = System.nanoTime()
                Os.sendto(fd, packet, 0, packet.size, 0, target, 0)
                val deadline = start + timeoutMs * 1_000_000L
                while (true) {
                    val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                    if (left <= 0) break
                    val pfd = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                    if (Os.poll(arrayOf(pfd), left) <= 0) break
                    val n = Os.recvfrom(fd, rbuf, 0, rbuf.size, 0, null)
                    if (n < 8) continue
                    val type = rbuf[0].toInt() and 0xff
                    val rseq = ((rbuf[6].toInt() and 0xff) shl 8) or (rbuf[7].toInt() and 0xff)
                    if (type == (if (v6) 129 else 0) && rseq == seq) {
                        times += (System.nanoTime() - start) / 1_000_000.0
                        break
                    }
                    Journal.log("icmp", "${target.hostAddress}: ignored packet type $type seq $rseq ($n bytes)")
                }
                Journal.log("icmp", "${target.hostAddress} seq=$seq over ${Journal.net(network)}: " +
                    (if (times.size > before) "reply ${ms(times.last())} ms" else "timeout after $timeoutMs ms"))
                if (seq < count) {
                    val spent = (System.nanoTime() - start) / 1_000_000L
                    if (spent < intervalMs) Thread.sleep(intervalMs - spent)
                }
            }
            return PingResult(target, count, times, "ICMP")
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    private fun echoPacket(v6: Boolean, ident: Int, seq: Int): ByteArray {
        val p = ByteArray(8 + 32)
        p[0] = (if (v6) 128 else 8).toByte()
        p[4] = (ident shr 8).toByte(); p[5] = ident.toByte()
        p[6] = (seq shr 8).toByte(); p[7] = seq.toByte()
        for (i in 8 until p.size) p[i] = (0x20 + i).toByte()
        if (!v6) { // kernel fills ICMPv6 checksums; ICMPv4 ping sockets also do, but set it anyway
            val sum = checksum(p)
            p[2] = (sum shr 8).toByte(); p[3] = sum.toByte()
        }
        return p
    }

    private fun checksum(b: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < b.size) { sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); i += 2 }
        if (i < b.size) sum += (b[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv() and 0xffff
    }

    private fun shellPing(target: InetAddress, count: Int, timeoutMs: Int): PingResult {
        val bin = if (target is Inet6Address) "ping6" else "ping"
        val waitSec = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        val out = runShell(listOf(bin, "-n", "-c", "$count", "-i", "0.3", "-W", "$waitSec", target.hostAddress!!),
            (count * (waitSec + 1) + 3) * 1000L)
            ?: return PingResult(target, count, emptyList(), "shell ping", "ping didn't finish")
        val replies = out.lines().map { Parsers.classify(it) }.filterIsInstance<Parsers.PingLine.Reply>()
        val summary = Parsers.pingSummary(out)
        val error = if (replies.isEmpty()) out.lines().map { it.trim() }.firstOrNull {
            it.isNotEmpty() && !it.startsWith("PING") && !it.startsWith("---") && !it.contains("packets transmitted")
        } else null
        return PingResult(target, summary?.sent ?: count, replies.map { it.ms }, "shell ping", error)
    }

    /** Runs a command, returning stdout+stderr, or null if it didn't finish in [timeoutMs]. */
    fun runShell(cmd: List<String>, timeoutMs: Long): String? {
        val start = System.currentTimeMillis()
        val out = runShellInner(cmd, timeoutMs)
        Journal.log("shell", "$ ${cmd.joinToString(" ")}  (${System.currentTimeMillis() - start} ms)\n${out ?: "(did not finish in $timeoutMs ms)"}")
        return out
    }

    private fun runShellInner(cmd: List<String>, timeoutMs: Long): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val text = reader.submit(Callable { p.inputStream.bufferedReader().readText() })
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                return@runCatching runCatching { text.get(500, TimeUnit.MILLISECONDS) }.getOrNull()
            }
            text.get(2, TimeUnit.SECONDS)
        } finally {
            reader.shutdownNow()
        }
    }.getOrElse { "error: ${it.message}" }

    // ---------------- TCP ----------------

    /** Milliseconds to open a TCP connection over [network], or the failure. */
    fun tcpConnect(network: Network?, addr: InetAddress, port: Int, timeoutMs: Int = 3000): Result<Double> = runCatching {
        val s: Socket = network?.socketFactory?.createSocket() ?: Socket()
        s.use {
            val start = System.nanoTime()
            it.connect(InetSocketAddress(addr, port), timeoutMs)
            (System.nanoTime() - start) / 1_000_000.0
        }
    }.also { r ->
        Journal.log("tcp", "${addr.hostAddress}:$port over ${Journal.net(network)}: " +
            (r.getOrNull()?.let { "connected in ${ms(it)} ms" } ?: "failed: ${r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }}"))
    }

    // ---------------- DNS ----------------

    class Lookup(val addresses: List<InetAddress>, val ms: Long, val error: String?)

    /** Resolves [host] with the DNS servers of [network] (null = default), with a time limit. */
    fun resolve(network: Network?, host: String, timeoutMs: Long = 6000): Lookup =
        resolveInner(network, host, timeoutMs).also { l ->
            Journal.log("dns", "$host over ${Journal.net(network)}: " +
                (l.error?.let { "failed: $it" } ?: l.addresses.joinToString(", ") { it.hostAddress.orEmpty() }) + " (${l.ms} ms)")
        }

    private fun resolveInner(network: Network?, host: String, timeoutMs: Long): Lookup {
        val start = System.currentTimeMillis()
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val f = pool.submit(Callable { network?.getAllByName(host)?.toList() ?: InetAddress.getAllByName(host).toList() })
            val list = f.get(timeoutMs, TimeUnit.MILLISECONDS)
            Lookup(list, System.currentTimeMillis() - start, null)
        } catch (e: java.util.concurrent.TimeoutException) {
            Lookup(emptyList(), System.currentTimeMillis() - start, "timed out after ${timeoutMs / 1000} s")
        } catch (e: java.util.concurrent.ExecutionException) {
            Journal.error("dns", "lookup $host", e.cause ?: e)
            Lookup(emptyList(), System.currentTimeMillis() - start, describe(e.cause ?: e))
        } finally {
            pool.shutdownNow()
        }
    }

    /** Reverse DNS name for [ip], or null. Bounded, since PTR lookups can hang. */
    fun reverse(ip: InetAddress, timeoutMs: Long = 2500): String? =
        reverseInner(ip, timeoutMs).also { Journal.log("dns", "reverse ${ip.hostAddress}: ${it ?: "no name"}") }

    private fun reverseInner(ip: InetAddress, timeoutMs: Long): String? {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            pool.submit(Callable {
                InetAddress.getByAddress(ip.address).canonicalHostName.takeIf { it != ip.hostAddress }
            }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        } finally {
            pool.shutdownNow()
        }
    }

    /** DNS over HTTPS through Cloudflare (by IP, so it doesn't depend on DNS itself). */
    fun resolveDoh(network: Network?, host: String, type: String = "A"): Lookup {
        val r = http(network, "https://1.1.1.1/dns-query?name=$host&type=$type", 16 * 1024, accept = "application/dns-json")
        if (r.error != null) return Lookup(emptyList(), r.ms, r.error)
        if (r.code != 200) return Lookup(emptyList(), r.ms, "HTTP ${r.code}")
        val addrs = Parsers.dohAddresses(r.body).mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        return Lookup(addrs, r.ms, null)
    }

    // ---------------- HTTP ----------------

    class Http(val code: Int, val body: String, val ms: Long, val error: String?)

    fun http(network: Network?, url: String, maxBytes: Int = 16 * 1024, accept: String? = null, timeoutMs: Int = 8000): Http =
        httpInner(network, url, maxBytes, accept, timeoutMs).also { r ->
            Journal.log("http", "GET $url over ${Journal.net(network)}: " +
                (r.error?.let { "failed: $it" } ?: "HTTP ${r.code}, ${r.body.length} chars") + " (${r.ms} ms)" +
                (if (r.body.isNotEmpty()) "\n" + r.body.take(8192) else ""))
        }

    private fun httpInner(network: Network?, url: String, maxBytes: Int, accept: String?, timeoutMs: Int): Http {
        val start = System.currentTimeMillis()
        return try {
            val u = URL(url)
            val c = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "MG4NetDiag")
            accept?.let { c.setRequestProperty("Accept", it) }
            try {
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val body = stream?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (buf.size() < maxBytes) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toString("UTF-8")
                }.orEmpty()
                val headers = c.headerFields.filterKeys { it != null }.entries.joinToString("\n") { (k, v) -> "$k: ${v.joinToString(", ")}" }
                Journal.log("http", "$url response headers:\n$headers")
                Http(code, body, System.currentTimeMillis() - start, null)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Journal.error("http", "GET $url", e)
            Http(-1, "", System.currentTimeMillis() - start, describe(e))
        }
    }

    // ---------------- Traceroute ----------------

    class Hop(val ttl: Int, val address: String?, val ms: Double?, val reached: Boolean)

    /**
     * Traceroute with shell `ping -t TTL` (the ICMP "time exceeded" replies need the socket error
     * queue, which only the ping binary reads). Default network only. Probes [batch] TTLs at a
     * time and stops at the destination. [onHop] is called for each hop as it's known, in order.
     */
    fun traceroute(target: InetAddress, maxHops: Int = 30, batch: Int = 5, onHop: (Hop) -> Unit): List<Hop> {
        val hops = ArrayList<Hop>()
        val pool = Executors.newFixedThreadPool(batch)
        try {
            var ttl = 1
            while (ttl <= maxHops) {
                val range = ttl until minOf(ttl + batch, maxHops + 1)
                val futures = range.map { t -> t to pool.submit(Callable { probe(target, t) }) }
                var done = false
                for ((t, f) in futures) {
                    val p = runCatching { f.get(8, TimeUnit.SECONDS) }.getOrNull()
                        ?: Parsers.HopProbe(null, false, null)
                    val hop = Hop(t, p.address, p.ms, p.reached || p.address == target.hostAddress)
                    hops += hop
                    Journal.log("trace", "${target.hostAddress} hop $t: ${hop.address ?: "*"}${if (hop.reached) " (destination)" else ""}")
                    onHop(hop)
                    if (hop.reached) { done = true; break }
                }
                if (done) break
                // Many routers in a row with no answer at all: likely ICMP filtered; stop early.
                if (hops.size >= 8 && hops.takeLast(8).all { it.address == null }) break
                ttl += batch
            }
        } finally {
            pool.shutdownNow()
        }
        return hops
    }

    private fun probe(target: InetAddress, ttl: Int): Parsers.HopProbe {
        // Two tries, since routers rate-limit "time exceeded" replies.
        // Time-exceeded lines carry no round trip; hop times come from "Pings to hops" afterwards.
        repeat(2) {
            val out = runShell(listOf("ping", "-n", "-c", "1", "-W", "2", "-t", "$ttl", target.hostAddress!!), 5000)
                ?: return@repeat
            val p = Parsers.hopProbe(out)
            if (p.address != null) return p
        }
        return Parsers.HopProbe(null, false, null)
    }

    // ---------------- Helpers ----------------

    fun parseTarget(network: Network?, text: String): Pair<InetAddress?, String?> {
        val t = text.trim()
        if (t.isEmpty()) return null to "empty"
        if (Parsers.isIpLiteral(t)) return runCatching { InetAddress.getByName(t) to null }.getOrElse { null to describe(it) }
        val l = resolve(network, t)
        val pick = l.addresses.firstOrNull { it is Inet4Address } ?: l.addresses.firstOrNull()
        return pick to (l.error ?: if (pick == null) "no address" else null)
    }

    fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "can't resolve"
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.ConnectException -> "refused or unreachable"
        is java.net.NoRouteToHostException -> "no route to host"
        is javax.net.ssl.SSLException -> "TLS failed (${t.message?.take(80)})"
        is ErrnoException -> t.message ?: "errno ${t.errno}"
        else -> t.message?.take(120) ?: t.javaClass.simpleName
    }

    fun ms(v: Double?): String = when {
        v == null -> "–"
        v < 10 -> String.format(java.util.Locale.US, "%.1f", v)
        else -> v.toInt().toString()
    }
}
