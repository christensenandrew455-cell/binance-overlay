package com.decisionoverlay.checker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
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

private data class SectionResult(
    val number: Int,
    val targetTime: Long,
    val target: Double,
    val minutePrice: Double,
    val finishPrice: Double,
    val prediction: String,
    val actual: String,
    val correct: Boolean?
)

private data class CoinResult(
    val symbol: String,
    val sections: List<SectionResult>,
    val correct: Int,
    val incorrect: Int,
    val neutral: Int
) {
    val scored: Int get() = correct + incorrect
    val accuracy: Double get() = if (scored == 0) 0.0 else correct * 100.0 / scored
}

class HistoricalAccuracyActivity : AppCompatActivity() {
    private val symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT", "DOGEUSDT", "ADAUSDT", "LINKUSDT")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private lateinit var runButton: Button
    private lateinit var status: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var chartSlot: LinearLayout
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF10151D.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = label("HISTORICAL ACCURACY TEST", 23f, 0xFFFFFFFF.toInt(), true)
        val description = label(
            "8 coins • 100 time windows each • 15-minute tests\nOnly tests from 10:00 AM through 11:00 PM local time. Flat/tied sections are included and reported separately; they do not count as correct or incorrect directional predictions.",
            14f, 0xFFB9C7D8.toInt()
        )
        runButton = Button(this).apply {
            text = "CHECK ACCURACY"
            setOnClickListener { startTest() }
        }
        status = label("Press CHECK ACCURACY after 5:00 PM local time.", 14f, 0xFFB9C7D8.toInt())
        chartSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title)
        content.addView(description)
        content.addView(runButton)
        content.addView(status)
        content.addView(chartSlot)
        content.addView(resultsBox)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val back = Button(this).apply {
            text = "BACK"
            setOnClickListener { finish() }
        }
        root.addView(back)
        setContentView(root)
    }

    private fun startTest() {
        if (running) return
        val anchor = latestCompletedQuarterHour()
        if (anchor <= 0L) {
            status.text = "Could not determine the latest completed 15-minute mark. Try again."
            return
        }
        running = true
        runButton.isEnabled = false
        status.text = "Downloading historical 1-minute prices from Binance..."
        resultsBox.removeAllViews()
        chartSlot.removeAllViews()
        Thread {
            try {
                val all = symbols.map { symbol -> fetchCoin(symbol, anchor) }
                runOnUiThread {
                    showResults(all, anchor)
                    running = false
                    runButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Could not complete the test: " + (e.message ?: "Unknown error") + "\nCheck your connection and try again."
                    running = false
                    runButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun fetchCoin(symbol: String, anchor: Long): CoinResult {
        val minute = 60_000L
        val quarter = 15L * minute
        // Pull up to 7 days to collect 100 eligible 15-minute windows
        // inside the user's 10:00 AM–11:00 PM local-time trading window.
        val firstOpen = anchor - 7L * 24L * 60L * minute
        val lastOpen = anchor - minute
        val prices = HashMap<Long, Double>()
        var cursor = firstOpen
        while (cursor <= lastOpen) {
            val query = "/api/v3/klines?symbol=" + symbol +
                "&interval=1m&startTime=" + cursor + "&endTime=" + lastOpen + "&limit=1000"
            val hosts = listOf("https://api.binance.us", "https://api1.binance.us", "https://api2.binance.us")
            var parsed = false
            val failures = ArrayList<String>()
            for (host in hosts) {
                val url = host + query
                try {
                    val request = Request.Builder().url(url).get().build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val detail = body.take(240).ifBlank { "no response body" }
                            failures.add(host + " returned HTTP " + response.code + ": " + detail)
                            if (response.code in 400..499) {
                                throw IllegalStateException(
                                    "Binance.US rejected " + symbol + " (HTTP " + response.code + "). " +
                                        detail + " Endpoint: " + host +
                                        ". Verify that the pair is supported on Binance.US."
                                )
                            }
                            return@use
                        }
                        val rows = JSONArray(body)
                        if (rows.length() == 0) {
                            failures.add(host + " returned an empty candle list")
                            return@use
                        }
                        var newest = cursor
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONArray(i)
                            val openTime = row.getLong(0)
                            prices[openTime] = row.getString(4).toDouble()
                            if (openTime > newest) newest = openTime
                        }
                        cursor = newest + minute
                        parsed = true
                    }
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: Exception) {
                    failures.add(host + ": " + (e.message ?: e.javaClass.simpleName))
                }
                if (parsed) break
            }
            if (!parsed) {
                throw IllegalStateException(
                    "Unable to download " + symbol + " candles from Binance.US. Tried api.binance.us, " +
                        "api1.binance.us, and api2.binance.us. " + failures.joinToString(" | ")
                )
            }
        }

        val sections = ArrayList<SectionResult>(100)
        var correct = 0
        var incorrect = 0
        var flatInvolved = 0
        var candidate = anchor - quarter
        val calendar = Calendar.getInstance()
        while (candidate >= firstOpen + minute && sections.size < 100) {
            calendar.timeInMillis = candidate
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minuteOfHour = calendar.get(Calendar.MINUTE)
            // A section starts on a 15-minute boundary between 10:00 AM and
            // 10:45 PM; its 15-minute result must finish no later than 11:00 PM.
            val inAllowedWindow = hour in 10..22 && minuteOfHour in listOf(0, 15, 30, 45)
            if (inAllowedWindow) {
                val target = prices[candidate - minute]
                val minutePrice = prices[candidate]
                val finishPrice = prices[candidate + 14L * minute]
                if (target != null && minutePrice != null && finishPrice != null) {
                    val prediction = direction(minutePrice, target)
                    val actual = direction(finishPrice, target)
                    // Keep every eligible time window, including flat/tied outcomes.
                    // Flat-involved windows are reported but are not directional wins/losses.
                    val isFlatInvolved = prediction == "FLAT" || actual == "FLAT"
                    val isCorrect = if (isFlatInvolved) null else prediction == actual
                    if (isFlatInvolved) {
                        flatInvolved++
                    } else if (isCorrect == true) {
                        correct++
                    } else {
                        incorrect++
                    }
                    sections.add(
                        SectionResult(
                            sections.size + 1, candidate, target, minutePrice, finishPrice,
                            prediction, actual, isCorrect
                        )
                    )
                }
            }
            candidate -= quarter
        }
        if (sections.size < 100) {
            throw IllegalStateException(
                "Only found " + sections.size + " eligible time windows for " + symbol +
                    " in the last 7 days between 10:00 AM and 11:00 PM. Need 100."
            )
        }
        return CoinResult(symbol.removeSuffix("USDT"), sections, correct, incorrect, flatInvolved)
    }

    private fun direction(price: Double, target: Double): String = when {
        price > target -> "UP"
        price < target -> "DOWN"
        else -> "FLAT"
    }

    private fun showResults(results: List<CoinResult>, anchor: Long) {
        val totalCorrect = results.sumOf { it.correct }
        val totalIncorrect = results.sumOf { it.incorrect }
        val totalScored = results.sumOf { it.sections.size }
        val overall = if (totalScored == 0) 0.0 else totalCorrect * 100.0 / totalScored
        status.text = "Completed • 800 time windows checked • Anchor: " +
            SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date(anchor)) +
            "\nOverall accuracy: " + String.format(Locale.US, "%.1f%%", overall) +
            " (" + totalCorrect + " correct / " + totalIncorrect + " incorrect directional predictions; " +
            results.sumOf { it.neutral } + " flat-involved windows out of " + totalScored + ")"

        chartSlot.addView(label("ACCURACY BY COIN", 18f, 0xFFFFFFFF.toInt(), true))
        chartSlot.addView(AccuracyBarChart(this, results), LinearLayout.LayoutParams(-1, 240))
        val sorted = results.sortedByDescending { it.accuracy }
        resultsBox.addView(label("COIN RESULTS (highest to lowest)", 18f, 0xFFFFFFFF.toInt(), true))
        for (coin in sorted) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundColor(0xFF1A2430.toInt())
            }
            val heading = label(
                coin.symbol + "   " + String.format(Locale.US, "%.1f%%", coin.accuracy),
                19f, 0xFFFFFFFF.toInt(), true
            )
            val counts = label(
                coin.correct.toString() + " correct • " + coin.incorrect.toString() +
                    " incorrect • " + coin.neutral.toString() + " flat-involved • 100 windows",
                13f, 0xFFB9C7D8.toInt()
            )
            val detailButton = Button(this).apply { text = "SHOW 100 SECTION RESULTS" }
            val detail = label(sectionDetails(coin), 12f, 0xFFD4DCE6.toInt())
            detail.visibility = View.GONE
            detailButton.setOnClickListener {
                val opening = detail.visibility != View.VISIBLE
                detail.visibility = if (opening) View.VISIBLE else View.GONE
                detailButton.text = if (opening) "HIDE SECTION RESULTS" else "SHOW 100 SECTION RESULTS"
            }
            panel.addView(heading)
            panel.addView(counts)
            panel.addView(detailButton)
            panel.addView(detail)
            val params = LinearLayout.LayoutParams(-1, -2)
            params.setMargins(0, 8, 0, 8)
            resultsBox.addView(panel, params)
        }
    }

    private fun sectionDetails(coin: CoinResult): String {
        val fmt = SimpleDateFormat("MMM d h:mm a", Locale.US)
        val out = StringBuilder()
        out.append("Section | target time | +1m guess | +15m result | result\n")
        for (s in coin.sections) {
            val mark = when (s.correct) {
                true -> "CORRECT"
                false -> "WRONG"
                null -> "FLAT / TIE — NOT SCORED"
            }
            out.append(s.number).append(" | ")
                .append(fmt.format(Date(s.targetTime))).append(" | ")
                .append(s.prediction).append(" | ")
                .append(s.actual).append(" | ")
                .append(mark).append("\n")
        }
        return out.toString()
    }

    private fun latestCompletedQuarterHour(): Long {
        val now = Calendar.getInstance()
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        val minute = now.get(Calendar.MINUTE)
        now.set(Calendar.MINUTE, (minute / 15) * 15)
        return now.timeInMillis
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 10)
        }
}

private class AccuracyBarChart(
    context: android.content.Context,
    private val values: List<CoinResult>
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 82f
        val right = width - 18f
        val top = 18f
        val rowHeight = (height - 30f) / 8f
        paint.textSize = 30f
        paint.color = 0xFFFFFFFF.toInt()
        paint.typeface = Typeface.DEFAULT_BOLD
        for ((index, item) in values.sortedByDescending { it.accuracy }.withIndex()) {
            val y = top + index * rowHeight
            paint.textSize = 12f
            paint.typeface = Typeface.DEFAULT
            paint.color = 0xFFD4DCE6.toInt()
            canvas.drawText(item.symbol, 4f, y + 17f, paint)
            paint.color = 0xFF334155.toInt()
            canvas.drawRoundRect(left, y + 3f, right, y + 23f, 8f, 8f, paint)
            paint.color = 0xFF39B980.toInt()
            val barRight = left + (right - left) * (item.accuracy / 100.0).toFloat()
            canvas.drawRoundRect(left, y + 3f, barRight, y + 23f, 8f, 8f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = 12f
            canvas.drawText(String.format(Locale.US, "%.1f%%", item.accuracy), right - 48f, y + 17f, paint)
        }
    }
}
