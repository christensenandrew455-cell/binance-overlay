package com.decisionoverlay.checker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Screen for starting/stopping the phone-safe scan and copying the raw results. */
class KalshiPatternResultsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var copy: Button
    private lateinit var copyFull: Button
    private lateinit var share: Button
    private lateinit var status: TextView
    private lateinit var output: TextView
    private val saveCsv = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val data = getSharedPreferences(KalshiPatternBacktestService.PREFS, MODE_PRIVATE)
                .getString(KalshiPatternBacktestService.REPORT, "").orEmpty()
            runCatching {
                require(data.isNotEmpty())
                requireNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(data) }
            }.onSuccess { Toast.makeText(this, "CSV saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "Could not save CSV", Toast.LENGTH_SHORT).show() }
        }
    }
    private var shownReport = ""
    private var partIndex = 0

    // Large reports are copied in manageable line-aligned pieces so chat/paste
    // targets do not choke on the whole historical analysis.
    private fun reportParts(report: String): List<String> {
        if (report.isEmpty()) return emptyList()
        val chunks = ArrayList<String>()
        var start = 0
        while (start < report.length) {
            val limit = (start + 24000).coerceAtMost(report.length)
            val newline = if (limit == report.length) limit
                else report.lastIndexOf('\n', limit).takeIf { it > start + 1000 }
                    ?.plus(1) ?: limit
            chunks.add(report.substring(start, newline))
            start = newline
        }
        return chunks
    }
    private fun partLabel(report: String): String {
        val count = reportParts(report).size
        return if (count == 0) "COPY PART" else
            "COPY PART " + (partIndex + 1) + "/" + count
    }
    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(KalshiPatternBacktestService.PREFS,
                Context.MODE_PRIVATE)
            val running = prefs.getBoolean(KalshiPatternBacktestService.RUNNING, false)
            val report = prefs.getString(KalshiPatternBacktestService.REPORT, "").orEmpty()
            status.text = prefs.getString(KalshiPatternBacktestService.STATUS,
                "Tap RUN 1000 MARKETS to test your six patterns.")
            start.isEnabled = !running
            stop.isEnabled = running
            copy.isEnabled = report.isNotEmpty()
            copyFull.isEnabled = report.length in 1..200000
            share.isEnabled = report.isNotEmpty()
            if (report != shownReport) {
                shownReport = report
                partIndex = 0
                copy.text = partLabel(report)
                output.text = if (report.isEmpty())
                    "See hit rate, win rate and best minutes remaining for each pattern."
                else if (report.length > 100000)
                    report.take(100000) + "\n\n[PREVIEW ONLY: " + report.length +
                        " characters. Use COPY PART to send the complete analysis in " +
                        reportParts(report).size + " smaller chunks, or COPY FULL.]"
                else prefs.getString("six_summary", report)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun button(label: String, handler: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setOnClickListener { handler() }
        setAllCaps(false)
    }
    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        parent.addView(r)
        return r
    }
    private fun addButton(parent: LinearLayout, button: Button) {
        parent.addView(button, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.rgb(7, 10, 16)
        window.navigationBarColor = Color.rgb(7, 10, 16)
        val root = LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 10, 16))
        }
        val heading = TextView(this).apply {
            text = "BTC six-pattern test"
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(5))
        }
        root.addView(heading)
        val commands = row(root)
        start = button("RUN 1000 MARKETS") {
            ContextCompat.startForegroundService(this,
                Intent(this, KalshiPatternBacktestService::class.java)
                    .setAction(KalshiPatternBacktestService.START))
            start.isEnabled = false
        }
        stop = button("STOP") {
            startService(Intent(this, KalshiPatternBacktestService::class.java)
                .setAction(KalshiPatternBacktestService.STOP))
        }
        addButton(commands, start)
        addButton(commands, stop)
        val actions = row(root)
        copy = button("COPY PART") {
            val parts = reportParts(shownReport)
            if (parts.isNotEmpty()) {
                val index = partIndex.coerceIn(0, parts.lastIndex)
                val mgr = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                mgr.setPrimaryClip(ClipData.newPlainText("Binance part", "PART " +
                    (index + 1) + "/" + parts.size + "\n" + parts[index]))
                Toast.makeText(this, "Copied part " + (index + 1) +
                    " of " + parts.size, Toast.LENGTH_SHORT).show()
                partIndex = (index + 1) % parts.size
                copy.text = partLabel(shownReport)
            }
        }
        copyFull = button("COPY FULL") {
            if (shownReport.isNotEmpty()) {
                val mgr = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                mgr.setPrimaryClip(ClipData.newPlainText("Binance minute percentages", shownReport))
                Toast.makeText(this, "Full report copied", Toast.LENGTH_SHORT).show()
            }
        }
        share = button("SAVE CSV") { saveCsv.launch("btc-sequence-outcomes.csv") }
        addButton(actions, copy)
        addButton(actions, copyFull)
        addButton(actions, share)
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(240, 190, 80))
            setPadding(0, dp(6), 0, dp(5))
        }
        root.addView(status)
        val scroller = ScrollView(this).apply { isFillViewport = true }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(214, 224, 232))
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        scroller.addView(output)
        root.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
