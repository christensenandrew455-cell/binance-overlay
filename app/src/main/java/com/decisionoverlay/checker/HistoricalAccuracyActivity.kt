package com.decisionoverlay.checker

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class HourStats(
    val hour: Int,
    var up: Int = 0,
    var down: Int = 0,
    var ties: Int = 0,
    var quoteVolumeUsdt: Double = 0.0,
    var tradeCount: Long = 0L
) {
    val total: Int get() = up + down + ties
    val directional: Int get() = up + down
    val upExcludingTies: Double get() = if (directional == 0) 0.0 else up * 100.0 / directional
    val downExcludingTies: Double get() = if (directional == 0) 0.0 else down * 100.0 / directional
    val tiePercent: Double get() = if (total == 0) 0.0 else ties * 100.0 / total
    // Kalshi-style settlement mapping: an unchanged candle is YES, not NO.
    val yesIncludingTies: Double get() = if (total == 0) 0.0 else (up + ties) * 100.0 / total
    val noIncludingTies: Double get() = if (total == 0) 0.0 else down * 100.0 / total
    val averageQuoteVolumePerCandle: Double get() = if (total == 0) 0.0 else quoteVolumeUsdt / total
    val averageTradesPerCandle: Double get() = if (total == 0) 0.0 else tradeCount.toDouble() / total
}

private data class CoinResult(
    val symbol: String,
    val hours: List<HourStats>,
    val totalCandles: Int
)

class HistoricalAccuracyActivity : AppCompatActivity() {
    private val symbols = listOf("DOGEUSDT", "HYPEUSDT", "ZECUSDT", "SOLUSDT", "NEARUSDT", "XRPUSDT", "ETHUSDT", "BNBUSDT", "BTCUSDT")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private lateinit var runButton: Button
    private lateinit var copyButton: Button
    private lateinit var status: TextView
    private lateinit var resultsBox: LinearLayout
    private var running = false
    private var lastResults: List<CoinResult> = emptyList()

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
            "9 coins • Last 100 completed days • All 24 hours, split into four 15-minute quarters per hour. " +
                "UP + TIES count as YES; DOWN counts as NO. Binance quote volume and trade counts measure market activity. No time inputs.",
            14f, 0xFFB9C7D8.toInt()
        ))
        runButton = Button(this).apply {
            text = "CHECK ALL 24 HOURS"
            setOnClickListener { startTest() }
        }
        copyButton = Button(this).apply {
            text = "COPY ALL RESULTS"
            isEnabled = false
            setOnClickListener { copyAllResults() }
        }
        status = label("Press CHECK ALL 24 HOURS to begin.", 14f, 0xFFB9C7D8.toInt())
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(runButton)
        content.addView(copyButton)
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
        copyButton.isEnabled = false
        lastResults = emptyList()
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
                    lastResults = all
                    showResults(all)
                    copyButton.isEnabled = all.isNotEmpty()
                    running = false
                    runButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Test failed: ${e.message ?: "Unknown error"}\nCheck your connection and try again."
                    running = false
                    runButton.isEnabled = true
                    copyButton.isEnabled = lastResults.isNotEmpty()
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
                // Binance kline fields: index 7 = quote-asset volume (USDT), index 8 = trade count.
                val quoteVolumeUsdt = candle.getString(7).toDouble()
                val tradeCount = candle.getLong(8)
                require(quoteVolumeUsdt.isFinite() && quoteVolumeUsdt >= 0.0 && tradeCount >= 0L) {
                    "Invalid Binance activity data for $symbol at $openTime"
                }
                val calendar = Calendar.getInstance().apply { timeInMillis = openTime }
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val stats = hours[hour]
                when {
                    closePrice > openPrice -> stats.up++
                    closePrice < openPrice -> stats.down++
                    else -> stats.ties++
                }
                stats.quoteVolumeUsdt += quoteVolumeUsdt
                stats.tradeCount += tradeCount
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
            "YES = UP + TIES; NO = DOWN. Hourly Binance USDT quote volume and trade counts show participation. " +
            "Times are shown in your phone's local time zone."
        for (coin in results) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundColor(0xFF1A2430.toInt())
            }
            panel.addView(label("${coin.symbol} • ${coin.totalCandles} quarters", 19f, 0xFFFFFFFF.toInt(), true))
            panel.addView(label(
                "HOUR (LOCAL) | UP / DOWN / TIE counts | YES (UP+TIE) / NO (DOWN) | USDT quote volume | trades | n",
                11f, 0xFFB9C7D8.toInt(), true
            ))
            for (hour in coin.hours) {
                panel.addView(label(formatDisplayRow(hour), 12f, 0xFFD4DCE6.toInt()))
            }
            val params = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) }
            resultsBox.addView(panel, params)
        }
    }

    private fun formatDisplayRow(hour: HourStats): String {
        val nextHour = (hour.hour + 1) % 24
        return String.format(
            Locale.US,
            "%02d:00–%02d:00 | UP %d / DOWN %d / TIE %d | YES %.1f%% / NO %.1f%% | QV $%.0f (avg $%.0f/15m) | trades %d (avg %.1f/15m) | n=%d",
            hour.hour, nextHour,
            hour.up, hour.down, hour.ties,
            hour.yesIncludingTies, hour.noIncludingTies,
            hour.quoteVolumeUsdt, hour.averageQuoteVolumePerCandle,
            hour.tradeCount, hour.averageTradesPerCandle,
            hour.total
        )
    }

    private fun copyAllResults() {
        if (lastResults.isEmpty()) {
            Toast.makeText(this, "Run the historical test first.", Toast.LENGTH_SHORT).show()
            return
        }

        val report = buildString {
            appendLine("BINANCE HISTORICAL 24-HOUR TEST")
            appendLine("Assets: DOGE, HYPE, ZEC, SOL, NEAR, XRP, ETH, BNB, BTC")
            appendLine("Period: Last 100 completed days")
            appendLine("Candle interval: 15 minutes")
            appendLine("Timezone: Phone local time")
            appendLine("Settlement mapping: YES = UP + TIES; NO = DOWN")
            appendLine("Activity: Binance.US USDT quote-asset volume and trade count")
            appendLine()
            appendLine("Coin\tHour\tUP count\tDOWN count\tTIE count\tYES count (UP+TIE)\tNO count (DOWN)\tTotal n\tUP % excluding ties\tDOWN % excluding ties\tTIE %\tYES %\tNO %\tUSDT quote volume\tAvg USDT quote volume per 15m\tTrade count\tAvg trades per 15m")
            for (coin in lastResults) {
                for (hour in coin.hours) {
                    val nextHour = (hour.hour + 1) % 24
                    appendLine(
                        String.format(
                            Locale.US,
                            "%s\t%02d:00-%02d:00\t%d\t%d\t%d\t%d\t%d\t%d\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.2f\t%.2f\t%d\t%.2f",
                            coin.symbol, hour.hour, nextHour,
                            hour.up, hour.down, hour.ties, hour.up + hour.ties, hour.down, hour.total,
                            hour.upExcludingTies, hour.downExcludingTies,
                            hour.tiePercent, hour.yesIncludingTies, hour.noIncludingTies,
                            hour.quoteVolumeUsdt, hour.averageQuoteVolumePerCandle,
                            hour.tradeCount, hour.averageTradesPerCandle
                        )
                    )
                }
            }
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Binance historical results", report))
        Toast.makeText(this, "All ${lastResults.size} coins copied. Paste into Sheets, Excel, or a text file.", Toast.LENGTH_LONG).show()
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
