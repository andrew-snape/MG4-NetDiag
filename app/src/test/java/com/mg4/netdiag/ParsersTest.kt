package com.mg4.netdiag

import com.mg4.netdiag.Parsers.PingLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    @Test
    fun replyLinePlainAndNamed() {
        val a = Parsers.classify("64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=5.83 ms") as PingLine.Reply
        assertEquals("8.8.8.8", a.from)
        assertEquals(1, a.seq)
        assertEquals(117, a.ttl)
        assertEquals(5.83, a.ms, 0.001)
        val b = Parsers.classify("64 bytes from dns.google (8.8.4.4): icmp_seq=3 ttl=60 time=12.1 ms") as PingLine.Reply
        assertEquals("8.8.4.4", b.from)
        assertEquals(3, b.seq)
    }

    @Test
    fun replyLineIpv6() {
        val r = Parsers.classify("64 bytes from 2606:4700:4700::1111: icmp_seq=2 ttl=57 time=9.40 ms") as PingLine.Reply
        assertEquals("2606:4700:4700::1111", r.from)
        assertEquals(9.40, r.ms, 0.001)
    }

    @Test
    fun ttlExceededVariants() {
        assertEquals("10.0.0.1", (Parsers.classify("From 10.0.0.1 icmp_seq=1 Time to live exceeded") as PingLine.TtlExceeded).from)
        assertEquals("100.64.0.1", (Parsers.classify("From 100.64.0.1: icmp_seq=1 Time to live exceeded") as PingLine.TtlExceeded).from)
        assertEquals("203.0.113.9", (Parsers.classify("From router.isp.net (203.0.113.9): icmp_seq=1 Time to live exceeded") as PingLine.TtlExceeded).from)
    }

    @Test
    fun unreachable() {
        val u = Parsers.classify("From 192.168.0.30 icmp_seq=1 Destination Host Unreachable") as PingLine.Unreachable
        assertEquals("192.168.0.30", u.from)
        assertTrue(Parsers.classify("connect: Network is unreachable") is PingLine.Unreachable)
    }

    @Test
    fun summaryFromRealCarOutput() {
        val out = """
            PING 160.79.104.10 (160.79.104.10) 56(84) bytes of data.
            64 bytes from 160.79.104.10: icmp_seq=1 ttl=56 time=5.83 ms

            --- 160.79.104.10 ping statistics ---
            3 packets transmitted, 1 received, 66% packet loss, time 2011ms
            rtt min/avg/max/mdev = 5.832/5.832/5.832/0.000 ms
        """.trimIndent()
        val s = Parsers.pingSummary(out)!!
        assertEquals(3, s.sent)
        assertEquals(1, s.received)
        assertEquals(66.0, s.lossPercent, 0.01)
        assertEquals(5.832, s.avg!!, 0.0001)
    }

    @Test
    fun summaryTotalLoss() {
        val s = Parsers.pingSummary("3 packets transmitted, 0 received, 100% packet loss, time 2027ms")!!
        assertEquals(0, s.received)
        assertEquals(100.0, s.lossPercent, 0.01)
        assertNull(s.avg)
    }

    @Test
    fun summaryWithErrors() {
        val s = Parsers.pingSummary("1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms")!!
        assertEquals(100.0, s.lossPercent, 0.01)
    }

    @Test
    fun hopProbes() {
        val mid = Parsers.hopProbe(
            "PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.\nFrom 192.168.0.1 icmp_seq=1 Time to live exceeded\n\n" +
                "--- 1.1.1.1 ping statistics ---\n1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms"
        )
        assertEquals("192.168.0.1", mid.address)
        assertFalse(mid.reached)
        val end = Parsers.hopProbe("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=8.1 ms")
        assertTrue(end.reached)
        assertEquals(8.1, end.ms!!, 0.001)
        val none = Parsers.hopProbe("1 packets transmitted, 0 received, 100% packet loss, time 0ms")
        assertNull(none.address)
    }

    @Test
    fun cloudflareTrace() {
        val m = Parsers.cloudflareTrace("fl=123\nh=1.1.1.1\nip=203.0.113.5\nts=1.2\nloc=AU\ncolo=SYD\nwarp=off\n")
        assertEquals("203.0.113.5", m["ip"])
        assertEquals("AU", m["loc"])
        assertEquals("SYD", m["colo"])
    }

    @Test
    fun doh() {
        val json = """{"Status":0,"Answer":[{"name":"google.com","type":1,"TTL":60,"data":"142.250.1.1"},""" +
            """{"name":"google.com","type":28,"TTL":60,"data":"2404:6800::200e"},{"name":"x","type":5,"TTL":1,"data":"alias.example."}]}"""
        assertEquals(listOf("142.250.1.1", "2404:6800::200e"), Parsers.dohAddresses(json))
    }

    @Test
    fun jsonField() {
        assertEquals("AS1221 Telstra", Parsers.jsonField("""{"ip":"1.2.3.4","org":"AS1221 Telstra"}""", "org"))
        assertNull(Parsers.jsonField("{}", "org"))
    }

    @Test
    fun wifi() {
        assertEquals(6, Parsers.wifiChannel(2437))
        assertEquals(36, Parsers.wifiChannel(5180))
        assertEquals("5 GHz", Parsers.wifiBand(5180))
        assertEquals("good", Parsers.rssiVerdict(-60))
    }

    @Test
    fun ipLiteral() {
        assertTrue(Parsers.isIpLiteral("8.8.8.8"))
        assertTrue(Parsers.isIpLiteral("2606:4700::1111"))
        assertTrue(Parsers.isIpLiteral("fe80::1%wlan0"))
        assertFalse(Parsers.isIpLiteral("google.com"))
    }
}
