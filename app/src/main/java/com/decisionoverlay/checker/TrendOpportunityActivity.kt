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

/**
 * Exploratory, no-lookahead scan. Signals use only candles closed at entry.
 * Results are NOT executable returns: fees, spreads, slippage, funding and liquidation are excluded.
 */
class TrendOpportunityActivity : AppCompatActivity() {
    private data class Bar(val time: Long, val close: Double)
    private data class Stat(val symbol: String, val horizon: Int, val filter: String,
                            val n: Int, val wins: Int, val mean: Double, val gain: Double,
                            val loss: Double, val maxAdverse: Double) {
        val hit get() = if (n == 0) 0.0 else wins * 100.0 / n
    }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).build()
    private val symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT")
    private val horizons = listOf(15, 30, 60, 120, 240)
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var run: Button
    private lateinit var copy: Button
    private var report = ""
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF10151D.toInt())
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(0xFFE5EDF5.toInt())
            setPadding(0, 10, 0, 10)
        }
        root.addView(label("TREND OPPORTUNITY SCANNER", 22f))
        root.addView(label("Find the largest predictable percentage move per hour. Compare 15m, 30m, 1h, 2h and 4h holds using closed 5m Binance.US candles. Research only; no orders.", 14f))
        run = Button(this).apply { text = "CHECK BINANCE TRENDS"; setOnClickListener { scan() } }
        copy = Button(this).apply {
            text = "COPY RESULTS"; isEnabled = false
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Trend scan", report))
            }
        }
        status = label("Ready", 14f)
        output = label("", 13f)
        root.addView(run); root.addView(copy); root.addView(status)
        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "BACK"; setOnClickListener { finish() } })
        setContentView(root)
    }

    private fun scan() {
        if (busy) return
        busy = true; run.isEnabled = false; copy.isEnabled = false
        status.text = "Downloading closed 5-minute candles..."
        output.text = ""; report = ""
        Thread {
            val rows = mutableListOf<Stat>()
            val errors = mutableListOf<String>()
            symbols.forEachIndexed { index, symbol ->
                runOnUiThread { status.text = "Checking ${index + 1}/${symbols.size}: $symbol" }
                try {
                    val bars = fetchBars(symbol)
                    if (bars.size < 200) throw IllegalStateException("Only ${bars.size} candles")
                    for (h in horizons) {
                        for (filter in listOf("5m", "5m+15m", "5m+15m+30m")) {
                            evaluate(symbol, bars, h, filter)?.let(rows::add)
                        }
                    }
                } catch (e: Exception) { errors.add("$symbol: ${e.message}") }
            }
            val sorted = rows.filter { it.n >= 30 }.sortedWith(
                compareByDescending<Stat> { it.mean / (it.horizon / 60.0) }
                    .thenByDescending { it.hit })
            val text = buildString {
                appendLine("BINANCE TREND OPPORTUNITY TEST")
                appendLine("Signal: direction of previous 5m close-to-close move; optional confirmation by 15m and 30m returns.")
                appendLine("Entry: signal candle close. Exit: close after stated horizon.")
                appendLine("Non-overlapping samples per configuration; no future candles used for signals.")
                appendLine("Rank: average signed return per holding hour, NOT guaranteed profit.")
                appendLine("Returns are unleveraged percentages; NO fees, spread, slippage, funding, or liquidation.")
                appendLine("Backtest uses recent completed 5m candles; results can overfit. Validate out-of-sample.")
                appendLine()
                appendLine("COIN | HOLD | CONFIRM | N | HIT% | AVG% | AVG WIN% | AVG LOSS% | AVG ADVERSE% | AVG%/HR")
                sorted.forEach {
                    appendLine(String.format(Locale.US,
                        "%s | %dm | %s | %d | %.1f | %+.3f | +%.3f | %.3f | %.3f | %+.3f",
                        it.symbol, it.horizon, it.filter, it.n, it.hit, it.mean, it.gain,
                        it.loss, it.maxAdverse, it.mean * 60.0 / it.horizon))
                }
                if (sorted.isEmpty()) appendLine("Not enough qualifying samples.")
                if (errors.isNotEmpty()) { appendLine(); appendLine("ERRORS:"); errors.forEach { appendLine(it) } }
            }
            runOnUiThread {
                report = text; output.text = text
                status.text = "Finished: ${sorted.size} combinations, ${errors.size} coin errors"
                copy.isEnabled = true; run.isEnabled = true; busy = false
            }
        }.start()
    }

    private fun fetchBars(symbol: String): List<Bar> {
        val interval = 300_000L
        val end = (System.currentTimeMillis() / interval) * interval - 1L
        val start = end - 3000L * interval
        val hosts = listOf("https://api.binance.us", "https://api1.binance.us", "https://api2.binance.us")
        val out = sortedMapOf<Long, Bar>()
        var cursor = start
        while (cursor <= end) {
            val path = "/api/v3/klines?symbol=$symbol&interval=5m&startTime=$cursor&endTime=$end&limit=1000"
            var page: JSONArray? = null
            val failures = mutableListOf<String>()
            for (host in hosts) {
                try {
                    client.newCall(Request.Builder().url(host + path).build()).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (response.isSuccessful) page = JSONArray(body)
                        else failures.add("${response.code} at $host: ${body.take(100)}")
                    }
                    if (page != null) break
                } catch (e: Exception) { failures.add("${e.message}") }
            }
            val data = page ?: throw IllegalStateException(failures.joinToString(" / "))
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val row = data.getJSONArray(i)
                val t = row.getLong(0)
                val closeTime = row.getLong(6)
                if (closeTime > System.currentTimeMillis()) continue
                val price = row.getDouble(4)
                if (price > 0 && price.isFinite()) out[t] = Bar(t, price)
            }
            val next = data.getJSONArray(data.length() - 1).getLong(0) + interval
            if (next <= cursor) break
            cursor = next
            if (data.length() < 1000) break
        }
        return out.values.toList()
    }

    private fun evaluate(symbol: String, bars: List<Bar>, horizon: Int, filter: String): Stat? {
        val steps = horizon / 5
        var n = 0; var wins = 0; var sum = 0.0; var winSum = 0.0
        var lossSum = 0.0; var winN = 0; var lossN = 0; var adverseSum = 0.0
        // Advance by full holding period to avoid counting overlapping trades as independent.
        var i = 7
        while (i + steps < bars.size) {
            val p = bars[i].close
            val dir = when {
                p > bars[i - 1].close -> 1
                p < bars[i - 1].close -> -1
                else -> 0
            }
            val confirm15 = filter == "5m" || dir * (p - bars[i - 3].close) > 0
            val confirm30 = filter != "5m+15m+30m" || dir * (p - bars[i - 6].close) > 0
            if (dir != 0 && confirm15 && confirm30 &&
                (i until i + steps).all { bars[it + 1].time - bars[it].time == 300_000L }) {
                val ret = dir * (bars[i + steps].close / p - 1.0) * 100.0
                val adverse = (1..steps).minOf { dir * (bars[i + it].close / p - 1.0) * 100.0 }
                n++; sum += ret; adverseSum += adverse.coerceAtMost(0.0)
                if (ret > 0) { wins++; winN++; winSum += ret }
                else { lossN++; lossSum += ret }
                i += steps
            } else i++
        }
        if (n == 0) return null
        return Stat(symbol.removeSuffix("USDT"), horizon, filter, n, wins, sum / n,
            if (winN == 0) 0.0 else winSum / winN,
            if (lossN == 0) 0.0 else lossSum / lossN, adverseSum / n)
    }
}
