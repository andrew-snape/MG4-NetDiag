package com.mg4.netdiag

import android.content.Context
import android.net.Network
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The full log: every probe, every packet, every raw command output, every result row change,
 * every network event, every error, timestamped to the millisecond. Nothing is replaced.
 *
 * Written as it happens to the app's own folder on the head unit
 * (Android/data/com.mg4.netdiag/files/logs/session-*.log), so nothing is lost if the app closes.
 * Save to USB copies every stored session. Old sessions are deleted only past [MAX_TOTAL_BYTES].
 */
object Journal {

    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    private val writerThread = Executors.newSingleThreadExecutor { r -> Thread(r, "journal").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    @Volatile var dir: File? = null; private set
    @Volatile var current: File? = null; private set

    /** netId → "Wi-Fi (wlan0)", so log lines name the network, not a number. */
    private val netNames = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (current != null) return
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val d = File(base, "logs").apply { mkdirs() }
        dir = d
        prune(d)
        val f = File(d, "session-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log")
        current = f
        writerThread.execute {
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
        }
        log("app", "Session started. Log file: ${f.absolutePath}")
    }

    fun nameNetwork(network: Network, title: String) { netNames[network.toString()] = title }

    fun net(network: Network?): String = when (network) {
        null -> "default network"
        else -> netNames[network.toString()]?.let { "$it, net $network" } ?: "net $network"
    }

    /** One entry. Multi-line text is indented so each entry stays readable. */
    fun log(category: String, text: String) {
        val t = stamp.format(Date())
        val thread = Thread.currentThread().name
        val body = text.replace("\r", "").trimEnd().replace("\n", "\n                              | ")
        val line = "$t [$category] ($thread) $body\n"
        writerThread.execute {
            runCatching {
                writer?.write(line)
                writer?.flush()
            }
        }
    }

    fun error(category: String, what: String, t: Throwable) =
        log(category, "$what: ${t.javaClass.name}: ${t.message}\n${t.stackTraceToString()}")

    /** Waits until everything logged so far is on disk. */
    fun flush() {
        runCatching { writerThread.submit { writer?.flush() }.get(5, TimeUnit.SECONDS) }
    }

    /** All stored sessions, oldest first. */
    fun sessions(): List<File> =
        dir?.listFiles { f -> f.name.startsWith("session-") && f.name.endsWith(".log") }?.sortedBy { it.name }.orEmpty()

    fun copyAllTo(out: OutputStream) {
        flush()
        for (f in sessions()) {
            out.write("\n\n######## ${f.name} (${f.length() / 1024} KB)${if (f == current) " — this session" else ""} ########\n\n".toByteArray())
            f.inputStream().use { it.copyTo(out, 64 * 1024) }
        }
    }

    /** Deletes every stored session except the current one. */
    fun clearOld(): Int {
        flush()
        var n = 0
        sessions().filter { it != current }.forEach { if (it.delete()) n++ }
        log("app", "Deleted $n old session log(s)")
        return n
    }

    fun totalBytes(): Long = sessions().sumOf { it.length() }

    private fun prune(d: File) {
        val files = d.listFiles { f -> f.name.startsWith("session-") }?.sortedBy { it.name }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (total > MAX_TOTAL_BYTES && files.isNotEmpty()) {
            val f = files.removeAt(0)
            total -= f.length()
            f.delete()
        }
    }
}
