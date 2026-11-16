package com.decisionoverlay.checker

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private data class HourStats(
    val hour: Int,
    var up: Int = 0,
    var down: Int = 0,
    var ties: Int = 0
) {
    val total: Int get() = up + down + ties
    val directional: Int get() = up + down
    val upExcludingTies: Double get() = if (directional == 0) 0.0 else up * 100.0 / directional
    val downExcludingTies: Double get() = if (directional == 0) 0.0 else down * 100.0 / directional
    val tiePercent: Double get() = if (total == 0) 0.0 else ties * 100.0 / total
    val upIncludingTies: Double get() = if (total == 0) 0.0 else up * 100.0 / total
    val downIncludingTies: Double get() = if (total == 0) 0.0 else down * 100.0 / total
}

private data class CoinResult(
    val symbol: String,
    val hours: List<HourStats>,
    val totalCandles: Int
)

class HistoricalAccuracyActivity : AppCompatActivity() {
    private val symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT", "DOGEUSDT", "ADAUSDT", "LINKUSDT")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private lateinit var runButton: Button
    private lateinit var status: TextView
    private lateinit var resultsBox: LinearLayout
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF10151D.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("HISTORICAL 24-HOUR TEST", 23f, 0xFFFFFFFF.toInt(), true))
        content.addView(label(
            "8 coins • Last 100 completed days • All 24 hours, split into four 15-minute quarters per hour. " +
                "Each Binance 15-minute candle supplies only its opening and closing price. " +
                "UP, DOWN, and exact TIES are counted separately. No time inputs.",
            14f, 0xFFB9C7D8.toInt()
        ))
        runButton = Button(this).apply {
            text = "CHECK ALL 24 HOURS"
            setOnClickListener { startTest() }
        }
        status = label("Press CHECK ALL 24 HOURS to begin.", 14f, 0xFFB9C7D8.toInt())
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(runButton)
        content.addView(status)
        content.addView(resultsBox)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "BACK"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    private fun startTest() {
        if (running) return
        running = true
        runButton.isEnabled = false
        resultsBox.removeAllViews()
        status.text = "Starting 100-day Binance download for all 24 hours..."
        Thread {
            try {
                val all = symbols.mapIndexed { index, symbol ->
                    fetchCoin(symbol) { detail ->
                        runOnUiThread {
                            status.text = "Coin ${index + 1}/${symbols.size} • $symbol\n$detail"
                        }
                    }
                }
                runOnUiThread {
                    showResults(all)
                    running = false
                    runButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Test failed: ${e.message ?: "Unknown error"}\nCheck your connection and try again."
                    running = false
                    runButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun fetchCoin(symbol: String, onProgress: (String) -> Unit): CoinResult {
        val interval = 15L * 60L * 1000L
        val endTime = (System.currentTimeMillis() / interval) * interval - 1L
        val startTime = endTime - 100L * 24L * 60L * 60L * 1000L
        val hours = (0 until 24).map { HourStats(it) }
        var cursor = startTime
        var fetched = 0
        val hosts = listOf("https://api.binance.us", "https://api1.binance.us", "https://api2.binance.us")

        while (cursor <= endTime) {
            val query = "/api/v3/klines?symbol=$symbol&interval=15m&startTime=$cursor&endTime=$endTime&limit=1000"
            var page: JSONArray? = null
            val failures = ArrayList<String>()
            for (host in hosts) {
                try {
                    val request = Request.Builder().url(host + query).get().build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            failures.add("$host HTTP ${response.code}: ${body.take(120)}")
                        } else {
                            page = JSONArray(body)
                        }
                    }
                    if (page != null) break
                } catch (e: Exception) {
                    failures.add("$host: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            val rows = page ?: throw IllegalStateException("Binance request failed for $symbol: ${failures.joinToString(" | ")}")
            if (rows.length() == 0) break

            for (i in 0 until rows.length()) {
                val candle = rows.getJSONArray(i)
                val openTime = candle.getLong(0)
                if (openTime < startTime || openTime > endTime) continue
                val openPrice = candle.getString(1).toDouble()
                val closePrice = candle.getString(4).toDouble()
                val calendar = Calendar.getInstance().apply { timeInMillis = openTime }
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val stats = hours[hour]
                when {
                    closePrice > openPrice -> stats.up++
                    closePrice < openPrice -> stats.down++
                    else -> stats.ties++
                }
                fetched++
            }

            val lastOpen = rows.getJSONArray(rows.length() - 1).getLong(0)
            val next = lastOpen + interval
            if (next <= cursor) break
            cursor = next
            onProgress("Downloaded $fetched of about 9,600 fifteen-minute quarters...")
            if (rows.length() < 1000) break
        }
        onProgress("Finished: $fetched quarter candles • 100 days • all 24 hours.")
        return CoinResult(symbol.removeSuffix("USDT"), hours, fetched)
    }

    private fun showResults(results: List<CoinResult>) {
        val completed = results.sumOf { it.totalCandles }
        status.text = "Completed • $completed quarter candles across ${results.size} coins. " +
            "Each hour below combines up to 400 quarters (4 per day × 100 days). " +
            "Times are shown in your phone's local time zone."
        for (coin in results) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundColor(0xFF1A2430.toInt())
            }
            panel.addView(label("${coin.symbol} • ${coin.totalCandles} quarters", 19f, 0xFFFFFFFF.toInt(), true))
            panel.addView(label(
                "HOUR (LOCAL) | UP / DOWN excluding ties | TIES | UP / DOWN including ties",
                11f, 0xFFB9C7D8.toInt(), true
            ))
            for (hour in coin.hours) {
                val nextHour = (hour.hour + 1) % 24
                val row = String.format(
                    Locale.US,
                    "%02d:00–%02d:00 | UP %.1f%% / DOWN %.1f%% | TIES %d (%.1f%%) | UP %.1f%% / DOWN %.1f%% | n=%d",
                    hour.hour, nextHour,
                    hour.upExcludingTies, hour.downExcludingTies,
                    hour.ties, hour.tiePercent,
                    hour.upIncludingTies, hour.downIncludingTies,
                    hour.total
                )
                panel.addView(label(row, 12f, 0xFFD4DCE6.toInt()))
            }
            val params = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) }
            resultsBox.addView(panel, params)
        }
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 8)
        }
}
