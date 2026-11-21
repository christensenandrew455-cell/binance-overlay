package com.decisionoverlay.checker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.ContextCompat

/** Separate 1,000-market indicator and exhaustive two-point pattern scans. */
class KalshiPatternResultsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var test: Button
    private lateinit var patterns: Button
    private lateinit var focused: Button
    private var showingPatterns = false
    private var showingFocused = false
    private lateinit var copy: Button
    private lateinit var save: Button
    private var pendingExportKey: String? = null
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var shownReport = ""

    // Document picker allows saving directly to Downloads or cloud storage
    // without needing Android's broad file-access permissions.
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val key = pendingExportKey
        pendingExportKey = null
        if (uri == null || key == null) return@registerForActivityResult

        val contents = getSharedPreferences(
            KalshiPatternBacktestService.PREFS, Context.MODE_PRIVATE
        ).getString(key, null)
        if (contents.isNullOrEmpty()) {
            Toast.makeText(this, "No scan results to save. Run the scan first.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // Save every row, not just the shortened on-screen preview.
        Thread {
            val saved = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(contents)
                } ?: error("Could not open the selected file")
            }
            runOnUiThread {
                saved.fold(
                    onSuccess = {
                        Toast.makeText(this,
                            "Saved full report. Attach the .txt file in ChatGPT.",
                            Toast.LENGTH_LONG).show()
                    },
                    onFailure = {
                        Toast.makeText(this,
                            "Could not save results: " + (it.message ?: "storage error"),
                            Toast.LENGTH_LONG).show()
                    }
                )
            }
        }.start()
    }
    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(KalshiPatternBacktestService.PREFS, Context.MODE_PRIVATE)
            val running = KalshiPatternBacktestService.isScanning()
            val reportKey = when {
                showingFocused -> KalshiPatternBacktestService.FOCUSED_RESULT
                showingPatterns -> KalshiPatternBacktestService.EXHAUSTIVE_RESULT
                else -> KalshiPatternBacktestService.EDGE_RESULT
            }
            val statusKey = when {
                showingFocused -> KalshiPatternBacktestService.FOCUSED_STATUS
                showingPatterns -> KalshiPatternBacktestService.EXHAUSTIVE_STATUS
                else -> KalshiPatternBacktestService.INDICATOR_STATUS
            }
            val report = prefs.getString(reportKey, "").orEmpty()
            status.text = prefs.getString(statusKey, "Tap a scan button to test 1,000 BTC markets.")
            test.isEnabled = !running
            patterns.isEnabled = !running
            focused.isEnabled = !running
            copy.isEnabled = !running && report.isNotEmpty()
            save.isEnabled = !running && report.isNotEmpty()
            if (report != shownReport) {
                shownReport = report
                // Rendering thousands of CSV rows in one TextView can freeze Android.
                // The downloadable report is complete regardless of this preview limit.
                output.text = if (report.length > 30_000) {
                    report.take(30_000) +
                        "\n\n[Preview shortened. Tap Download full .txt for every row.]"
                } else report
            }
            handler.postDelayed(this, 500L)
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
        pendingExportKey = savedInstanceState?.getString("pending_export_key")
        supportActionBar?.hide()
        window.statusBarColor = Color.rgb(7, 10, 16)
        window.navigationBarColor = Color.rgb(7, 10, 16)
        val root = LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 10, 16))
        }
        val heading = TextView(this).apply {
            text = "BTC historical tests"
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(5))
        }
        root.addView(heading)
        val commands = row(root)
        test = button("Indicators") {
            showingPatterns = false
            showingFocused = false
            shownReport = ""
            test.isEnabled = false
            copy.isEnabled = false
            ContextCompat.startForegroundService(this, Intent(this, KalshiPatternBacktestService::class.java)
                .setAction(KalshiPatternBacktestService.EDGE))
        }
        patterns = button("1,400 patterns") {
            showingPatterns = true
            showingFocused = false
            shownReport = ""
            patterns.isEnabled = false
            copy.isEnabled = false
            ContextCompat.startForegroundService(this,
                Intent(this, KalshiPatternBacktestService::class.java)
                    .setAction(KalshiPatternBacktestService.EXHAUSTIVE))
        }
        focused = button("8 chosen patterns") {
            showingPatterns = false
            showingFocused = true
            shownReport = ""
            focused.isEnabled = false
            copy.isEnabled = false
            ContextCompat.startForegroundService(this,
                Intent(this, KalshiPatternBacktestService::class.java)
                    .setAction(KalshiPatternBacktestService.FOCUSED))
        }
        copy = button("Copy") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("BTC historical results", shownReport))
            Toast.makeText(this, "Results copied", Toast.LENGTH_SHORT).show()
        }
        addButton(commands, test)
        addButton(commands, patterns)
        addButton(commands, focused)
        val exportCommands = row(root)
        addButton(exportCommands, copy)
        save = button("Download full .txt") {
            val key = when {
                showingFocused -> KalshiPatternBacktestService.FOCUSED_RESULT
                showingPatterns -> KalshiPatternBacktestService.EXHAUSTIVE_RESULT
                else -> KalshiPatternBacktestService.EDGE_RESULT
            }
            val hasReport = getSharedPreferences(
                KalshiPatternBacktestService.PREFS, Context.MODE_PRIVATE
            ).getString(key, null)?.isNotEmpty() == true
            if (!hasReport) {
                Toast.makeText(this, "Run the scan first to create results.", Toast.LENGTH_LONG).show()
            } else {
                pendingExportKey = key
                val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val name = when {
                    showingFocused -> "BTC-8-patterns-$time.txt"
                    showingPatterns -> "BTC-1400-patterns-$time.txt"
                    else -> "BTC-indicators-$time.txt"
                }
                exportPicker.launch(name)
            }
        }
        addButton(exportCommands, save)
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_export_key", pendingExportKey)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
