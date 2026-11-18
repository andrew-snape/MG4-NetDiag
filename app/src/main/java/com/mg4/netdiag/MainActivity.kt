package com.mg4.netdiag

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One screen: a rail of tabs on the left, the selected tab's controls and results on the right.
 * Built in code, sized for the MG4's 1778 × 720 screen (density 160, so 1 dp = 1 px).
 */
class MainActivity : Activity() {

    private enum class Tab(val title: String) {
        OVERVIEW("Overview"), WIFI("Wi-Fi"), CAR("4G modem"), DNS("DNS"),
        PING("Ping"), TRACE("Traceroute"), EXTERNAL("External IP"), REPORT("Report");
    }

    private val pool = Executors.newCachedThreadPool()
    private lateinit var diag: Diagnostics
    private val panels = HashMap<Tab, Panel>()
    private val busy = Collections.synchronizedSet(HashSet<Tab>())
    private val tabButtons = HashMap<Tab, TextView>()
    private var current = Tab.OVERVIEW

    private lateinit var titleView: TextView
    private lateinit var controls: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private var scope = Diagnostics.Scope.ALL
    private var pingCount = 5
    private val inputs = HashMap<Tab, String>().apply { put(Tab.TRACE, "1.1.1.1") }
    @Volatile private var liveRunning = false

    private val bg = 0xFF0B0F17.toInt()
    private val card = 0xFF1A2130.toInt()
    private val cardLight = 0xFF2A3347.toInt()
    private val accent = 0xFF3D8BFF.toInt()
    private val dim = 0xFFA9B3C6.toInt()

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Journal.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Journal.error("crash", "Uncaught on ${t.name}", e)
            Journal.flush()
            previous?.uncaughtException(t, e)
        }
        Journal.log("app", "onCreate · version ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")} · " +
            "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL} · firmware ${NetInfo.sysprop("ro.build.mt2712.version")} · " +
            "permissions: location=${hasLocation()}, phone=${checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED}, " +
            "logs=${checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED}, dump=${checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED}")
        watchNetworks()
        diag = Diagnostics(this)
        Tab.values().forEach { panels[it] = Panel(this, it.title) }
        setContentView(buildUi())
        askPermissions()
        select(Tab.OVERVIEW)
        run(Tab.OVERVIEW)
    }

    override fun onResume() { super.onResume(); Journal.log("app", "onResume") }
    override fun onPause() { super.onPause(); Journal.log("app", "onPause"); Journal.flush() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Journal.log("app", "Permissions: " + permissions.indices.joinToString { "${permissions[it]}=${if (grantResults.getOrNull(it) == 0) "granted" else "denied"}" })
    }

    /** Logs every network appearing, changing and going away, with Android's full details. */
    private fun watchNetworks() {
        val cm = NetInfo.cm(this)
        val req = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { NetInfo.snapshots(this@MainActivity); Journal.log("netevent", "available: ${Journal.net(network)}") }
            override fun onLost(network: Network) = Journal.log("netevent", "LOST: ${Journal.net(network)}")
            override fun onLosing(network: Network, maxMsToLive: Int) = Journal.log("netevent", "losing: ${Journal.net(network)} in $maxMsToLive ms")
            override fun onUnavailable() = Journal.log("netevent", "unavailable")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                Journal.log("netevent", "capabilities ${Journal.net(network)}: $caps")
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                Journal.log("netevent", "link ${Journal.net(network)}: $lp")
        }
        runCatching { cm.registerNetworkCallback(req, cb); netCallback = cb }
            .onFailure { Journal.error("netevent", "registerNetworkCallback", it) }
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { NetInfo.snapshots(this@MainActivity); Journal.log("netevent", "DEFAULT network is now ${Journal.net(network)}") }
                override fun onLost(network: Network) = Journal.log("netevent", "DEFAULT network lost: ${Journal.net(network)}")
            })
        }
    }

    override fun onDestroy() {
        Journal.log("app", "onDestroy")
        runCatching { netCallback?.let { NetInfo.cm(this).unregisterNetworkCallback(it) } }
        Journal.flush()
        liveRunning = false
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun askPermissions() {
        val want = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- layout

    private fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun pressable(normal: Int, pressed: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed))
        addState(intArrayOf(), rounded(normal))
    }

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(26), 0, dp(26), 0)
        minWidth = dp(120)
        background = if (primary) pressable(accent, 0xFF2C6AD1.toInt()) else pressable(cardLight, 0xFF45526E.toInt())
        layoutParams = LinearLayout.LayoutParams(-2, dp(72)).apply { marginEnd = dp(12) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = button(text) { onClick() }.apply {
        textSize = 20f
        background = if (selected) pressable(0xFF24467A.toInt(), 0xFF2C6AD1.toInt()) else pressable(card, 0xFF45526E.toInt())
        if (selected) setTextColor(0xFFFFFFFF.toInt()) else setTextColor(dim)
        layoutParams = LinearLayout.LayoutParams(-2, dp(64)).apply { marginEnd = dp(10) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        // Rail
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(250), -1).apply { marginEnd = dp(18) }
        }
        rail.addView(TextView(this).apply {
            text = "NetDiag"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), 0, 0, dp(10))
        })
        val tabsScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Tab.values().forEach { t ->
            val b = TextView(this).apply {
                text = t.title
                textSize = 23f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(12), 0)
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(8) }
                isClickable = true
                setOnClickListener { select(t) }
            }
            tabButtons[t] = b
            tabs.addView(b)
        }
        tabsScroll.addView(tabs)
        rail.addView(tabsScroll)
        root.addView(rail)

        // Main column
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        titleView = TextView(this).apply {
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(10))
        }
        col.addView(titleView)
        val controlsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controlsScroll.addView(controls)
        col.addView(controlsScroll)
        scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        col.addView(scroll)
        root.addView(col)
        return root
    }

    private fun select(t: Tab) {
        Journal.log("ui", "Tab ${t.title} selected")
        if (t != Tab.PING) liveRunning = false
        current = t
        tabButtons.forEach { (tab, b) ->
            b.background = if (tab == t) rounded(accent) else pressable(card, 0xFF45526E.toInt())
            b.setTextColor(if (tab == t) 0xFFFFFFFF.toInt() else dim)
        }
        titleView.text = when (t) {
            Tab.OVERVIEW -> "Overview: every network, host IP, gateway, DNS, quick checks"
            Tab.WIFI -> "Wi-Fi: signal, access point, tests over Wi-Fi"
            Tab.CAR -> "Car 4G modem (TBOX): addresses and tests over the car's internet"
            Tab.DNS -> "DNS: servers and lookups, compared with DNS over HTTPS"
            Tab.PING -> "Ping: gateway, DNS servers, internet IPs and hostnames"
            Tab.TRACE -> "Traceroute, then pings to each hop"
            Tab.EXTERNAL -> "External IP: what the internet sees, per connection"
            Tab.REPORT -> "Report: run everything, save to a USB stick"
        }
        buildControls()
        content.removeAllViews()
        val pv = panels[t]!!.view
        (pv.parent as? LinearLayout)?.removeView(pv)
        content.addView(pv)
        scroll.scrollTo(0, 0)
        if (!panels[t]!!.ran && t != Tab.PING && t != Tab.REPORT && t != Tab.TRACE) run(t)
        else if (!panels[t]!!.ran) hint(t)
    }

    private fun hint(t: Tab) {
        val p = panels[t]!!
        p.clear()
        p.ran = false
        val s = p.section("Ready")
        s.note(when (t) {
            Tab.PING -> "Run pings every target once (×$pingCount), on the networks chosen above. Live pings the gateway, 1.1.1.1 and google.com every second on the default network. Type an IP or hostname to add your own target."
            Tab.TRACE -> "Type an IP or hostname (or pick one) and press Run. Each hop is found, named, then pinged."
            Tab.REPORT -> "Run all runs every tab one after another (about a minute). Save to USB writes one file with everything: the latest results, the full timestamped log of every probe, packet, command output, result change and network event (this session and all earlier ones), plus a raw system dump (routes, interfaces, properties, connectivity and Wi-Fi dumps, system log).\n\nLog stored on the head unit: ${"%.1f".format(Journal.totalBytes() / 1048576.0)} MB in ${Journal.sessions().size} session(s), ${Journal.dir?.absolutePath ?: "?"}"
            else -> ""
        })
    }

    private fun buildControls() {
        controls.removeAllViews()
        val t = current
        val running = t in busy
        if (t == Tab.REPORT) {
            controls.addView(button(if (running) "Running…" else "Run all", primary = true) { runAll() })
            controls.addView(button("Save to USB") { save() })
            controls.addView(button("Delete old logs") {
                pool.execute {
                    val n = Journal.clearOld()
                    ui { Toast.makeText(this, "Deleted $n old session log(s)", Toast.LENGTH_LONG).show(); if (!panels[Tab.REPORT]!!.ran) hint(Tab.REPORT) }
                }
            })
            return
        }
        controls.addView(button(if (running) "Running…" else "Run", primary = true) { run(t) })
        if (t == Tab.PING) {
            controls.addView(button(if (liveRunning) "Stop live" else "Live") { toggleLive() })
        }
        if (t == Tab.PING || t == Tab.DNS || t == Tab.TRACE) controls.addView(inputField(t))
        if (t == Tab.TRACE) {
            listOf("1.1.1.1", "8.8.8.8", "google.com", "abc.net.au").forEach { target ->
                controls.addView(chip(target, inputs[Tab.TRACE] == target) { inputs[Tab.TRACE] = target; buildControls() })
            }
        }
        if (t == Tab.PING || t == Tab.DNS || t == Tab.EXTERNAL) {
            Diagnostics.Scope.values().forEach { s ->
                controls.addView(chip(s.label, scope == s) { scope = s; buildControls() })
            }
        }
        if (t == Tab.PING || t == Tab.TRACE) {
            listOf(5, 20).forEach { n -> controls.addView(chip("×$n", pingCount == n) { pingCount = n; buildControls() }) }
        }
        controls.addView(button("Save") { save() })
    }

    private fun inputField(t: Tab) = EditText(this).apply {
        hint = if (t == Tab.DNS) "hostname to look up" else "IP or hostname"
        setText(inputs[t].orEmpty())
        textSize = 22f
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO
        setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0xFF6B7690.toInt())
        background = rounded(card)
        setPadding(dp(18), 0, dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(dp(300), dp(72)).apply { marginEnd = dp(12) }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { inputs[t] = s?.toString().orEmpty() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        setOnEditorActionListener { v, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            run(t); true
        }
    }

    // ---------------------------------------------------------------- running

    private fun run(t: Tab) {
        Journal.log("ui", "Run ${t.title} (scope ${scope.label}, ×$pingCount, input '${inputs[t].orEmpty()}')")
        if (t == Tab.REPORT) { runAll(); return }
        if (t == Tab.PING) liveRunning = false
        if (!busy.add(t)) return
        refreshControls(t)
        pool.execute {
            try {
                runBlocking(t)
            } catch (e: Exception) {
                Journal.error(t.title, "Run stopped", e)
                panels[t]!!.section("Error").row("Stopped", Probes.describe(e), Status.BAD)
            } finally {
                busy.remove(t)
                refreshControls(t)
            }
        }
    }

    private fun runBlocking(t: Tab) {
        val p = panels[t]!!
        when (t) {
            Tab.OVERVIEW -> diag.overview(p)
            Tab.WIFI -> diag.wifi(p, hasLocation())
            Tab.CAR -> diag.car(p)
            Tab.DNS -> diag.dns(p, scope, inputs[Tab.DNS])
            Tab.PING -> diag.ping(p, scope, inputs[Tab.PING], pingCount)
            Tab.TRACE -> diag.traceroute(p, inputs[Tab.TRACE]?.trim()?.ifEmpty { null } ?: "1.1.1.1", pingCount)
            Tab.EXTERNAL -> diag.external(p, scope)
            Tab.REPORT -> {}
        }
    }

    private fun refreshControls(t: Tab) = ui { if (current == t) buildControls() }

    private fun toggleLive() {
        if (liveRunning) { liveRunning = false; buildControls(); return }
        if (Tab.PING in busy) return
        val net = NetInfo.default(this) ?: run {
            Toast.makeText(this, "Offline: no default network", Toast.LENGTH_LONG).show(); return
        }
        liveRunning = true
        busy.add(Tab.PING)
        buildControls()
        val extra = inputs[Tab.PING]
        pool.execute {
            try {
                diag.livePing(panels[Tab.PING]!!, net, extra) { liveRunning && !pool.isShutdown }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                panels[Tab.PING]!!.section("Error").row("Stopped", Probes.describe(e), Status.BAD)
            } finally {
                liveRunning = false
                busy.remove(Tab.PING)
                refreshControls(Tab.PING)
            }
        }
    }

    private fun runAll() {
        if (!busy.add(Tab.REPORT)) return
        liveRunning = false
        refreshControls(Tab.REPORT)
        val p = panels[Tab.REPORT]!!
        p.clear()
        val s = p.section("Run all")
        val order = listOf(Tab.OVERVIEW, Tab.WIFI, Tab.CAR, Tab.DNS, Tab.PING, Tab.TRACE, Tab.EXTERNAL)
        val rows = order.associateWith { s.row(it.title, "waiting", Status.PENDING) }
        pool.execute {
            try {
                for (t in order) {
                    val row = rows[t]!!
                    while (t in busy) Thread.sleep(200)
                    busy.add(t)
                    row.set("running…", Status.PENDING)
                    val start = System.currentTimeMillis()
                    try {
                        runBlocking(t)
                        val lines = panels[t]!!.text().lines()
                        val fail = lines.count { it.startsWith("[FAIL]") }
                        val warn = lines.count { it.startsWith("[WARN]") }
                        row.set("done in ${(System.currentTimeMillis() - start) / 1000} s · $fail failed · $warn warnings",
                            when { fail > 0 -> Status.BAD; warn > 0 -> Status.WARN; else -> Status.OK })
                    } catch (e: Exception) {
                        Journal.error(t.title, "Run all step stopped", e)
                        row.set("stopped: ${Probes.describe(e)}", Status.BAD)
                    } finally {
                        busy.remove(t)
                    }
                }
                s.note("Done. Press Save to USB to keep the report.", 0xFF5BD98A.toInt())
            } finally {
                busy.remove(Tab.REPORT)
                refreshControls(Tab.REPORT)
            }
        }
    }

    // ---------------------------------------------------------------- saving

    private fun reportText(): String = buildString {
        appendLine("MG4 NetDiag: everything")
        appendLine("Contents: 1) latest results of each tab  2) raw system dump  3) full timestamped log of every session")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("App: ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")}")
        appendLine("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL} · firmware ${NetInfo.sysprop("ro.build.mt2712.version").ifEmpty { "?" }}")
        appendLine()
        appendLine("######## Latest results ########")
        Tab.values().filter { it != Tab.REPORT && panels[it]!!.ran }.forEach { appendLine(panels[it]!!.text()) }
    }

    private fun save() {
        val name = "mg4-netdiag-everything-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(i, SAVE_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker on this unit: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SAVE_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = reportText()
        Journal.log("app", "Saving everything to $uri")
        Toast.makeText(this, "Saving everything… (can take a minute)", Toast.LENGTH_LONG).show()
        pool.execute {
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "wt")!!.buffered(256 * 1024).use { out ->
                    out.write(text.toByteArray())
                    SystemDump.writeTo(this, out)
                    out.write("\n\n######## Full log: every session stored on the head unit ########\n".toByteArray())
                    Journal.copyAllTo(out)
                }
            }
            ok.exceptionOrNull()?.let { Journal.error("app", "Save failed", it) } ?: Journal.log("app", "Saved")
            ui {
                Toast.makeText(this, if (ok.isSuccess) "Report saved" else "Save failed: ${ok.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val SAVE_REQUEST = 42
    }
}
