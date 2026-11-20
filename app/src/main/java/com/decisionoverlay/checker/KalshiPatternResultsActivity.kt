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
    private lateinit var charts: LinearLayout
    private fun showCharts(threshold: String, path: String) {
        charts.removeAllViews()
        val windows = shownReport.lineSequence().drop(1).filter { it.isNotBlank() }
            .map { it.split(',') }.filter { it[0]==threshold && it[1]=="window" && it[2]==path }
            .associateBy { it[4] }
        val down = threshold.toInt()>0
        fun label(s: String) { charts.addView(TextView(this).apply { text=s; setTextColor(Color.WHITE) }) }
        label(path.replace(">"," → ") + "  |  " + threshold + " touch")
        for (win in listOf(false,true)) {
            label(if (win) "Final " + (if (down) "DOWN" else "UP") + " among matches" else "Hit rate among scanned markets")
            for (w in listOf("14–12","11–9","8–5","4–1")) {
                val r = windows[w]
                val value = r?.get(if (win) (if (down) 10 else 11) else 13)?.toDoubleOrNull() ?: 0.0
                label("$w min left   " + (if (r==null && win) "—" else "$value%") + "   (" + (r?.get(7) ?: "0") + " matches)")
                charts.addView(android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                    max=1000; progress=(10*value).toInt()
                })
            }
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
                "Tap RUN 1000 MARKETS to test your ±70 and ±80 touches.")
            start.isEnabled = !running
            stop.isEnabled = running
            copy.isEnabled = report.isNotEmpty()
            copyFull.isEnabled = report.length in 1..200000
            share.isEnabled = report.isNotEmpty()
            if (report != shownReport) {
                charts.removeAllViews()
                shownReport = report
                partIndex = 0
                copy.text = partLabel(report)
                output.text = if (report.isEmpty())
                    "Compare paths before the BTC target by final DOWN or UP close."
                else prefs.getString("touch_summary", report)
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
            text = "BTC target-return patterns"
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
        val timing = button("PATTERN TIMING") {
            if (shownReport.isEmpty()) {
                Toast.makeText(this, "Run the scan first", Toast.LENGTH_SHORT).show()
            } else {
                val choices = BtcSequenceOutcomes.choices(shownReport)
                val labels = choices.map { (threshold,path) -> "${if (threshold.startsWith("-")) "" else "+"}$threshold touch · " + path.replace(">", " → ") }
                android.app.AlertDialog.Builder(this).setTitle("Choose a path")
                    .setItems(labels.toTypedArray()) { _, index ->
                        val (threshold,path) = choices[index]
                        showCharts(threshold,path)
                        output.text = BtcSequenceOutcomes.detail(shownReport,threshold,path)
                        (charts.parent.parent as? ScrollView)?.scrollTo(0,0)
                    }.setNeutralButton("Overview") { _,_ ->
                        charts.removeAllViews()
                        output.text = BtcSequenceOutcomes.summary(shownReport)
                        (charts.parent.parent as? ScrollView)?.scrollTo(0,0)
                    }.show()
            }
        }
        root.addView(timing)
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
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        charts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(charts)
        panel.addView(output)
        scroller.addView(panel)
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
