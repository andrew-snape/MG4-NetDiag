package com.mg4.netdiag

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

enum class Status(val color: Int, val word: String) {
    OK(0xFF5BD98A.toInt(), "OK"),
    WARN(0xFFFFC857.toInt(), "WARN"),
    BAD(0xFFFF6B6B.toInt(), "FAIL"),
    INFO(0xFF3D8BFF.toInt(), ""),
    PENDING(0xFF4A5468.toInt(), "…"),
}

private val main = Handler(Looper.getMainLooper())
fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

/**
 * A scrolling list of sections of result rows. Safe to call from any thread: the model updates
 * at once (for the saved report) and the views follow on the main thread, in order.
 */
class Panel(private val context: Context, val name: String) {

    val view: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val sections = mutableListOf<Section>()
    @Volatile var generation = 0; private set
    @Volatile var ran = false

    fun clear(): Int {
        synchronized(sections) { sections.clear() }
        ui { view.removeAllViews() }
        ran = true
        Journal.log(name, "==== run ${generation + 1} started ====")
        return ++generation
    }

    fun section(title: String): Section {
        val s = Section(title)
        Journal.log(name, "-- $title --")
        synchronized(sections) { sections += s }
        ui { view.addView(s.build()) }
        return s
    }

    fun text(): String = buildString {
        appendLine("==== $name ====")
        val copy = synchronized(sections) { sections.toList() }
        for (s in copy) {
            appendLine()
            appendLine("-- ${s.title} --")
            for (line in s.lines()) appendLine(line)
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    inner class Section(val title: String) {
        private val rows = mutableListOf<Any>() // Row or note String
        private lateinit var box: LinearLayout
        private var built = false
        private val pending = mutableListOf<View>()

        fun build(): View {
            box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                background = GradientDrawable().apply { setColor(0xFF1A2130.toInt()); cornerRadius = dp(14).toFloat() }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            }
            box.addView(TextView(context).apply {
                text = title
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF7FB2FF.toInt())
                setPadding(0, 0, 0, dp(6))
            })
            built = true
            pending.forEach { box.addView(it) }
            pending.clear()
            return box
        }

        private fun add(v: View) { if (built) box.addView(v) else pending += v }

        fun row(label: String, value: String = "…", status: Status = Status.PENDING): Row {
            val r = Row(title, label, value, status)
            if (status != Status.PENDING || value != "…") Journal.log(name, r.line())
            synchronized(rows) { rows += r }
            ui { add(r.build()) }
            return r
        }

        fun note(text: String, color: Int = 0xFFA9B3C6.toInt()) {
            synchronized(rows) { rows += "  $text" }
            Journal.log(name, "[$title] note: $text")
            ui {
                add(TextView(context).apply {
                    this.text = text
                    textSize = 19f
                    setTextColor(color)
                    setPadding(dp(30), dp(4), 0, dp(4))
                })
            }
        }

        fun lines(): List<String> = synchronized(rows) {
            rows.map { if (it is Row) it.line() else it as String }
        }
    }

    inner class Row(private val section: String, private val label: String, @Volatile var value: String, @Volatile var status: Status) {
        private var dot: View? = null
        private var valueView: TextView? = null

        fun build(): View {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(6), 0, dp(6))
            }
            dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { topMargin = dp(8); marginEnd = dp(14) }
            }
            line.addView(dot)
            line.addView(TextView(context).apply {
                text = label
                textSize = 21f
                setTextColor(0xFFA9B3C6.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(330), -2)
            })
            valueView = TextView(context).apply {
                textSize = 21f
                setTextColor(0xFFFFFFFF.toInt())
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            line.addView(valueView)
            render()
            return line
        }

        fun set(value: String, status: Status) {
            this.value = value
            this.status = status
            Journal.log(name, "[$section] " + line())
            ui { render() }
        }

        private fun render() {
            valueView?.text = value
            dot?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(status.color) }
        }

        fun line(): String {
            val tag = if (status.word.isEmpty()) "" else "[${status.word}] "
            return "$tag$label: ${value.replace("\n", "\n    ")}"
        }
    }
}
