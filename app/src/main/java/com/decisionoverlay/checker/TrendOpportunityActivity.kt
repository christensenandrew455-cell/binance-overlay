package com.decisionoverlay.checker

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Research-only intraday swing scanner.
 * A swing is measured from its intraday turning point, not daily open-to-close.
 * The entry signal uses ONLY completed candles; subsequent extrema are outcomes.
 */
class TrendOpportunityActivity : AppCompatActivity() {
    private data class Candle(val time: Long, val high: Double, val low: Double, val close: Double)
    private data class Result(val symbol: String, val timeframe: Int, val direction: String,
                              val signals: Int, val reached10: Int, val avgBest: Double,
                              val avgAtExit: Double, val avgWorst: Double, val maxBest: Double)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).build()
    private val symbols = listOf("BTCUSDT", "BNBUSDT", "XRPUSDT", "ETHUSDT",
        "HYPEUSDT", "DOGEUSDT", "SOLUSDT", "NEARUSDT", "ZECUSDT")
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var check: Button
    private lateinit var copy: Button
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF10151D.toInt())
        }
        fun label(s: String, size: Float) = TextView(this).apply {
            text = s; textSize = size; setTextColor(0xFFE5EDF5.toInt())
            setPadding(0, 10, 0, 10)
        }
        root.addView(label("INTRADAY TREND CATCHER", 22f))
        root.addView(label("Scan 15m and 30m signals for BOTH up and down moves. " +
            "Measure how much could be captured in the next 24h, even when the day closes flat. " +
            "10% is the target, not a prerequisite. Historical research only.", 14f))
        check = Button(this).apply { text = "CHECK ALL NINE COINS"; setOnClickListener { scan() } }
        copy = Button(this).apply {
            text = "COPY RESULTS"; isEnabled = false
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Intraday trend scan", report))
            }
        }
        status = label("Ready", 14f)
        results = label("", 13f)
        root.addView(check); root.addView(copy); root.addView(status)
        root.addView(ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "BACK"; setOnClickListener { finish() } })
        setContentView(root)
    }

    private fun scan() {
        check.isEnabled = false; copy.isEnabled = false; results.text = ""
        Thread {
            val stats = mutableListOf<Result>()
            val errors = mutableListOf<String>()
            symbols.forEachIndexed { index, symbol ->
                runOnUiThread { status.text = "Checking ${index + 1}/${symbols.size}: $symbol" }
                try {
                    val bars = fetch(symbol)
                    if (bars.size < 150) error("Only ${bars.size} completed candles available")
                    stats.addAll(listOf(15, 30).flatMap { minutes ->
                        listOf(1, -1).map { direction -> evaluate(symbol, bars, minutes, direction) }
                    })
                } catch (e: Exception) { errors.add("$symbol: ${e.message}") }
            }
            val ranked = stats.sortedWith(compareByDescending<Result> { it.avgAtExit }
                .thenByDescending { it.reached10 })
            val output = buildString {
                appendLine("INTRADAY TREND CATCHER — 15m/30m")
                appendLine("All nine requested coins; Binance.US 15m candles; UP and DOWN separately.")
                appendLine("Entry: 3 consecutive closed 15m candles trending in signal direction;")
                appendLine("30m confirmation also requires the prior 30m direction to agree.")
                appendLine("Exit: first opposite 15m close-to-close move, or after 24h.")
                appendLine("Each signal starts after the previous trade's exit; no overlapping trades per configuration.")
                appendLine("BEST% = largest favorable high/low excursion before exit (not executable profit).")
                appendLine("EXIT% = price return at simulated exit; WORST% = deepest intratrade adverse high/low.")
                appendLine("10% COUNT = trades touching +10% favorably before exit.")
                appendLine("No lookahead in entry/exit decisions; 15m close exits ignore spread and slippage.")
                appendLine("All figures UNLEVERAGED and before fees/funding. Historical signals are not predictions.")
                appendLine()
                appendLine("COIN | SIGNAL | SIDE | N | 10% COUNT | AVG BEST% | AVG EXIT% | AVG WORST% | MAX BEST%")
                ranked.forEach {
                    appendLine(String.format(Locale.US,
                        "%s | %dm | %s | %d | %d | +%.3f | %+.3f | %.3f | +%.3f",
                        it.symbol, it.timeframe, it.direction, it.signals, it.reached10,
                        it.avgBest, it.avgAtExit, it.avgWorst, it.maxBest))
                }
                if (errors.isNotEmpty()) {
                    appendLine(); appendLine("UNAVAILABLE / ERRORS:")
                    errors.forEach { appendLine(it) }
                }
            }
            runOnUiThread {
                report = output; results.text = output
                status.text = "Done: ${ranked.size} configurations; ${errors.size} coin errors"
                check.isEnabled = true; copy.isEnabled = true
            }
        }.start()
    }

    private fun fetch(symbol: String): List<Candle> {
        val interval = 900_000L
        val end = (System.currentTimeMillis() / interval) * interval - 1L
        val start = end - 3000L * interval
        val out = sortedMapOf<Long, Candle>()
        var cursor = start
        while (cursor <= end) {
            val url = "https://api.binance.us/api/v3/klines?symbol=$symbol&interval=15m" +
                "&startTime=$cursor&endTime=$end&limit=1000"
            val array = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Binance HTTP ${response.code}: ${body.take(120)}")
                JSONArray(body)
            }
            if (array.length() == 0) break
            for (i in 0 until array.length()) {
                val item = array.getJSONArray(i)
                if (item.getLong(6) <= System.currentTimeMillis()) {
                    val c = Candle(item.getLong(0), item.getDouble(2),
                        item.getDouble(3), item.getDouble(4))
                    if (c.close > 0) out[c.time] = c
                }
            }
            val next = array.getJSONArray(array.length() - 1).getLong(0) + interval
            if (next <= cursor) break
            cursor = next
            if (array.length() < 1000) break
        }
        return out.values.toList()
    }

    private fun evaluate(symbol: String, bars: List<Candle>, timeframe: Int, dir: Int): Result {
        var n = 0; var reached10 = 0
        var totalBest = 0.0; var totalExit = 0.0; var totalWorst = 0.0; var maxBest = 0.0
        var i = 4
        while (i < bars.size - 1) {
            if (bars[i].time - bars[i - 3].time != 3 * 900_000L) { i++; continue }
            val trend = (i - 2..i).all { k ->
                dir * (bars[k].close - bars[k - 1].close) > 0
            }
            val confirmed = timeframe == 15 ||
                dir * (bars[i].close - bars[i - 2].close) > 0 &&
                dir * (bars[i - 2].close - bars[i - 4].close) > 0
            if (!trend || !confirmed) { i++; continue }
            val entry = bars[i].close
            var best = 0.0; var worst = 0.0; var exit = 0.0
            var j = i + 1
            while (j < bars.size && j <= i + 96) {
                if (bars[j].time - bars[j - 1].time != 900_000L) break
                val favorable = if (dir == 1) bars[j].high else bars[j].low
                val adverse = if (dir == 1) bars[j].low else bars[j].high
                best = max(best, dir * (favorable / entry - 1) * 100)
                worst = kotlin.math.min(worst, dir * (adverse / entry - 1) * 100)
                exit = dir * (bars[j].close / entry - 1) * 100
                if (dir * (bars[j].close - bars[j - 1].close) < 0) break
                j++
            }
            if (j >= bars.size) break
            n++; if (best >= 10) reached10++
            totalBest += best; totalWorst += worst; totalExit += exit
            maxBest = max(maxBest, best)
            i = max(i + 1, j + 1)
        }
        return Result(symbol.removeSuffix("USDT"), timeframe,
            if (dir == 1) "UP" else "DOWN", n, reached10,
            if (n == 0) 0.0 else totalBest / n,
            if (n == 0) 0.0 else totalExit / n,
            if (n == 0) 0.0 else totalWorst / n, maxBest)
    }
}
