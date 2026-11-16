package com.decisionoverlay.checker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    val correct: Boolean?,
    val predictionTie: Boolean,
    val actualTie: Boolean
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
    private lateinit var startTimeInput: EditText
    private lateinit var endTimeInput: EditText
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
            "8 coins • Goal: 100+ time windows per coin • 15-minute tests\nThe lookback automatically expands based on your selected hours to aim for 100 windows per coin. It uses all available matching history if fewer exist. Exact ties count as UP (YES), matching the market rule.",
            14f, 0xFFB9C7D8.toInt()
        )
        val timeLabel = label("TEST WINDOW (24-HOUR LOCAL TIME)", 15f, 0xFFFFFFFF.toInt(), true)
        startTimeInput = EditText(this).apply {
            setSingleLine(true)
            setText("23:00")
            hint = "Start, e.g. 23:00"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB9C7D8.toInt())
        }
        endTimeInput = EditText(this).apply {
            setSingleLine(true)
            setText("10:00")
            hint = "End, e.g. 10:00"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB9C7D8.toInt())
        }
        runButton = Button(this).apply {
            text = "CHECK ACCURACY"
            setOnClickListener { startTest() }
        }
        status = label("Enter a time window and press CHECK ACCURACY.", 14f, 0xFFB9C7D8.toInt())
        chartSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title)
        content.addView(description)
        content.addView(timeLabel)
        content.addView(startTimeInput)
        content.addView(endTimeInput)
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
        val startMinutes = parseTime(startTimeInput.text.toString())
        val endMinutes = parseTime(endTimeInput.text.toString())
        if (startMinutes == null || endMinutes == null || startMinutes == endMinutes) {
            status.text = "Enter valid, different times in 24-hour HH:mm format, for example 23:00 and 10:00."
            return
        }
        val anchor = latestCompletedQuarterHour()
        if (anchor <= 0L) {
            status.text = "Could not determine the latest completed 15-minute mark. Try again."
            return
        }
        running = true
        runButton.isEnabled = false
        val durationMinutes = (endMinutes - startMinutes + 1440) % 1440
        val windowsPerDay = maxOf(1, durationMinutes / 15)
        status.text = "Starting download • maximum 300 one-minute candles per coin. Progress will appear here."
        resultsBox.removeAllViews()
        chartSlot.removeAllViews()
        Thread {
            try {
                val all = symbols.mapIndexed { index, symbol ->
                    fetchCoin(symbol, anchor, startMinutes, endMinutes) { detail ->
                        runOnUiThread {
                            status.text = "Downloading coin ${index + 1}/${symbols.size} • $symbol\n$detail"
                        }
                    }
                }
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

    private fun fetchCoin(
        symbol: String,
        anchor: Long,
        startMinutes: Int,
        endMinutes: Int,
        onProgress: (String) -> Unit
    ): CoinResult {
        val minute = 60_000L
        val quarter = 15L * minute
        // Keep downloads small: only fetch the latest 300 one-minute candles per coin.
        val lastOpen = anchor - minute
        val firstOpen = lastOpen - 299L * minute
        val prices = HashMap<Long, Double>()
        var cursor = firstOpen
        var batchNumber = 0
        val totalBatches = 1
        onProgress("Preparing up to 300 one-minute candles...")
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
                        batchNumber++
                        parsed = true
                        onProgress(
                            "Downloaded ${prices.size} candles • batch $batchNumber (about $totalBatches) • " +
                                "${prices.size / 60} hours of price history"
                        )
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

        val sections = ArrayList<SectionResult>()
        var correct = 0
        var incorrect = 0
        var flatInvolved = 0
        var candidate = anchor - quarter
        val calendar = Calendar.getInstance()
        while (candidate >= firstOpen + minute) {
            calendar.timeInMillis = candidate
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minuteOfHour = calendar.get(Calendar.MINUTE)
            val candidateMinutes = hour * 60 + minuteOfHour
            val offset = (candidateMinutes - startMinutes + 1440) % 1440
            // The entire 15-minute result must fit inside the selected time range.
            val inAllowedWindow = minuteOfHour in listOf(0, 15, 30, 45) &&
                offset < duration && offset + 15 <= duration
            if (inAllowedWindow) {
                val target = prices[candidate - minute]
                val minutePrice = prices[candidate]
                val finishPrice = prices[candidate + 14L * minute]
                if (target != null && minutePrice != null && finishPrice != null) {
                    val prediction = direction(minutePrice, target)
                    val actual = direction(finishPrice, target)
                    // Kalshi YES wins at or above the target, so exact ties are UP.
                    val isCorrect = prediction == actual
                    if (isCorrect) correct++ else incorrect++
                    sections.add(
                        SectionResult(
                            sections.size + 1, candidate, target, minutePrice, finishPrice,
                            prediction, actual, isCorrect,
                            minutePrice == target, finishPrice == target
                        )
                    )
                }
            }
            candidate -= quarter
        }
        onProgress("Finished downloading • analyzed ${sections.size} eligible 15-minute windows.")
        return CoinResult(symbol.removeSuffix("USDT"), sections, correct, incorrect, flatInvolved)
    }

    // Exact target ties are treated as UP/YES for Kalshi-style settlement.
    private fun direction(price: Double, target: Double): String =
        if (price >= target) "UP" else "DOWN"

    private fun showResults(results: List<CoinResult>, anchor: Long) {
        val totalCorrect = results.sumOf { it.correct }
        val totalIncorrect = results.sumOf { it.incorrect }
        val totalScored = results.sumOf { it.scored }
        val overall = if (totalScored == 0) 0.0 else totalCorrect * 100.0 / totalScored
        val totalWindows = results.sumOf { it.sections.size }
        status.text = "Completed • " + totalWindows + " total windows checked across " +
            results.size + " coins • Anchor: " +
            SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date(anchor)) +
            "\nOverall directional accuracy: " + String.format(Locale.US, "%.1f%%", overall) +
            " (" + totalCorrect + " correct / " + totalIncorrect + " incorrect; " + totalScored + " scored)" +
            "\nTies: " + results.sumOf { r -> r.sections.count { it.predictionTie } } +
            " at +1m prediction, " + results.sumOf { r -> r.sections.count { it.actualTie } } + " at final result. Ties count as UP."

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
                    " incorrect • " + coin.neutral.toString() + " neutral • " +
                    coin.sections.size + " total windows • " +
                    coin.sections.count { it.predictionTie } + " prediction ties • " +
                    coin.sections.count { it.actualTie } + " result ties (ties count as UP)",
                13f, 0xFFB9C7D8.toInt()
            )
            val detailButton = Button(this).apply { text = "SHOW " + coin.sections.size + " SECTION RESULTS" }
            val detail = label(sectionDetails(coin), 12f, 0xFFD4DCE6.toInt())
            detail.visibility = View.GONE
            detailButton.setOnClickListener {
                val opening = detail.visibility != View.VISIBLE
                detail.visibility = if (opening) View.VISIBLE else View.GONE
                detailButton.text = if (opening) "HIDE SECTION RESULTS" else "SHOW " + coin.sections.size + " SECTION RESULTS"
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
            val mark = (if (s.correct == true) "CORRECT" else "WRONG") +
                (if (s.predictionTie) " • PREDICTION TIE" else "") +
                (if (s.actualTie) " • RESULT TIE" else "")
            out.append(s.number).append(" | ")
                .append(fmt.format(Date(s.targetTime))).append(" | ")
                .append(s.prediction).append(" | ")
                .append(s.actual).append(" | ")
                .append(mark).append("\n")
        }
        return out.toString()
    }

    private fun parseTime(value: String): Int? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
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
