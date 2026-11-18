package com.mg4.netdiag

/**
 * Pure text parsers, kept free of Android classes so they run in plain JUnit tests.
 * The ping formats are iputils (Android's /system/bin/ping) and toybox-style variants.
 */
object Parsers {

    /** Summary of a ping run. Times are milliseconds; null when there was no reply. */
    data class PingSummary(
        val sent: Int,
        val received: Int,
        val lossPercent: Double,
        val min: Double?,
        val avg: Double?,
        val max: Double?,
    )

    /** One line of shell ping output, classified. */
    sealed class PingLine {
        data class Reply(val from: String, val seq: Int?, val ttl: Int?, val ms: Double) : PingLine()
        /** An intermediate router answered: "From 10.0.0.1 icmp_seq=1 Time to live exceeded". */
        data class TtlExceeded(val from: String) : PingLine()
        data class Unreachable(val from: String?, val reason: String) : PingLine()
        object Other : PingLine()
    }

    private val ipv4 = """\d{1,3}(?:\.\d{1,3}){3}"""
    private val ipv6 = """[0-9a-fA-F:]*:[0-9a-fA-F:.]+"""
    private val ip = "(?:$ipv4|$ipv6)"

    // "64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=5.83 ms"
    // "64 bytes from dns.google (8.8.8.8): icmp_seq=1 ttl=117 time=5.83 ms"
    private val replyRe = Regex(
        """bytes from (?:[^\s(]+ \()?($ip)\)?.*?time[=<]\s*([\d.]+)\s*ms""",
        RegexOption.IGNORE_CASE,
    )
    private val seqRe = Regex("""icmp_[rs]eq=(\d+)""")
    private val ttlFieldRe = Regex("""ttl=(\d+)""", RegexOption.IGNORE_CASE)

    // "From 10.0.0.1 icmp_seq=1 Time to live exceeded" / "From host (10.0.0.1): icmp_seq=1 Time to live exceeded"
    private val ttlRe = Regex(
        """From (?:[^\s(]+ \()?($ip)\)?:?.*(?:Time to live exceeded|Hop limit|TTL expired|time exceeded)""",
        RegexOption.IGNORE_CASE,
    )

    private val unreachRe = Regex(
        """(?:From (?:[^\s(]+ \()?($ip)\)?:?.*?)?(Destination (?:Host|Net|Port|Protocol) Unreachable|Network is unreachable|Packet filtered|Destination unreachable[^\n]*)""",
        RegexOption.IGNORE_CASE,
    )

    private val summaryRe = Regex(
        """(\d+) packets transmitted, (\d+) (?:packets )?received.*?([\d.]+)% packet loss""",
        RegexOption.IGNORE_CASE,
    )

    // "rtt min/avg/max/mdev = 5.832/5.832/5.832/0.000 ms" or "round-trip min/avg/max = 1/2/3 ms"
    private val rttRe = Regex("""min/avg/max(?:/\w+)?\s*=\s*([\d.]+)/([\d.]+)/([\d.]+)""")

    fun classify(line: String): PingLine {
        ttlRe.find(line)?.let { return PingLine.TtlExceeded(it.groupValues[1].trimEnd(':')) }
        replyRe.find(line)?.let { m ->
            return PingLine.Reply(
                from = m.groupValues[1].trimEnd(':'),
                seq = seqRe.find(line)?.groupValues?.get(1)?.toIntOrNull(),
                ttl = ttlFieldRe.find(line)?.groupValues?.get(1)?.toIntOrNull(),
                ms = m.groupValues[2].toDouble(),
            )
        }
        unreachRe.find(line)?.let { m ->
            return PingLine.Unreachable(m.groupValues[1].trimEnd(':').ifEmpty { null }, m.groupValues[2])
        }
        return PingLine.Other
    }

    /** Parses the closing statistics. Falls back to counting reply lines if the summary is missing. */
    fun pingSummary(output: String): PingSummary? {
        val s = summaryRe.find(output)
        val r = rttRe.find(output)
        if (s != null) {
            val sent = s.groupValues[1].toInt()
            val received = s.groupValues[2].toInt()
            return PingSummary(
                sent, received, s.groupValues[3].toDouble(),
                r?.groupValues?.get(1)?.toDouble(),
                r?.groupValues?.get(2)?.toDouble(),
                r?.groupValues?.get(3)?.toDouble(),
            )
        }
        val replies = output.lines().map { classify(it) }.filterIsInstance<PingLine.Reply>()
        if (replies.isEmpty()) return null
        val times = replies.map { it.ms }
        return PingSummary(replies.size, replies.size, 0.0, times.min(), times.average(), times.max())
    }

    /**
     * Result of one traceroute probe (`ping -c1 -t TTL`): the router that answered,
     * whether it is the destination itself, and the round trip if shown.
     */
    data class HopProbe(val address: String?, val reached: Boolean, val ms: Double?)

    fun hopProbe(output: String): HopProbe {
        var unreachableFrom: String? = null
        for (line in output.lines()) {
            when (val c = classify(line)) {
                is PingLine.TtlExceeded -> return HopProbe(c.from, false, null)
                is PingLine.Reply -> return HopProbe(c.from, true, c.ms)
                is PingLine.Unreachable -> if (c.from != null) unreachableFrom = c.from
                PingLine.Other -> {}
            }
        }
        // A router that says "unreachable" still tells us where the path ends.
        return HopProbe(unreachableFrom, false, null)
    }

    /** "key=value" lines from Cloudflare's /cdn-cgi/trace. */
    fun cloudflareTrace(text: String): Map<String, String> =
        text.lines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }
                ?.let { it[0].trim() to it[1].trim() }
        }.toMap()

    /** Addresses (A = type 1, AAAA = type 28) in a DNS-over-HTTPS JSON answer. */
    fun dohAddresses(json: String): List<String> =
        Regex(""""type"\s*:\s*(?:1|28)\s*,[^}]*?"data"\s*:\s*"([0-9a-fA-F.:]+)"""").findAll(json)
            .map { it.groupValues[1] }.toList()

    /** A flat JSON string field, e.g. "org" from ipinfo.io. Enough for simple, known replies. */
    fun jsonField(json: String, name: String): String? =
        Regex(""""${Regex.escape(name)}"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)

    /** Wi-Fi channel number from a frequency in MHz (2.4, 5 and 6 GHz bands). */
    fun wifiChannel(mhz: Int): Int? = when (mhz) {
        2484 -> 14
        in 2412..2472 -> (mhz - 2407) / 5
        in 5160..5885 -> (mhz - 5000) / 5
        in 5955..7115 -> (mhz - 5950) / 5
        else -> null
    }

    fun wifiBand(mhz: Int): String = when {
        mhz in 2400..2500 -> "2.4 GHz"
        mhz in 4900..5900 -> "5 GHz"
        mhz in 5925..7125 -> "6 GHz"
        else -> "$mhz MHz"
    }

    /** Plain-words Wi-Fi signal from RSSI. */
    fun rssiVerdict(dbm: Int): String = when {
        dbm >= -55 -> "excellent"
        dbm >= -67 -> "good"
        dbm >= -75 -> "fair"
        dbm >= -85 -> "weak"
        else -> "very weak"
    }

    /** A looks-like-an-IP-literal check, so hostnames can be resolved and labelled separately. */
    fun isIpLiteral(s: String): Boolean =
        Regex("^$ipv4$").matches(s) || (s.contains(':') && Regex("""^[0-9a-fA-F:.]+(%[\w.]+)?$""").matches(s))
}
