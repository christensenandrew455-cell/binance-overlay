package com.decisionoverlay.checker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onClose: () -> Unit,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val width = minOf(dp(270), (context.resources.displayMetrics.widthPixels * .78f).toInt())
    private val params = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(90) }

    private fun label(size: Float, color: Int, bold: Boolean) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private val heading = label(11f, MUTED, true)
    private val price = label(18f, TEXT, true)
    private val emaPrice = label(11f, TEXT, false)
    private val emaVolume = label(11f, TEXT, false)
    private val emaMomentum = label(11f, TEXT, false)
    private val anomalies = label(10f, ALERT, true)
    private val close = label(20f, MUTED, true).apply {
        text = "×"
        gravity = Gravity.CENTER
        minWidth = dp(30)
        minHeight = dp(28)
        contentDescription = "Hide floating monitor"
        isClickable = true
        setOnClickListener { onClose() }
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(Color.argb(238, 12, 17, 27))
            setStroke(dp(1), Color.argb(150, 91, 125, 182))
        }
        setPadding(dp(10), dp(5), dp(8), dp(8))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close)
        })
        addView(price)
        addView(emaPrice)
        addView(emaVolume)
        addView(emaMomentum)
        addView(anomalies)
    }

    init { heading.setOnTouchListener { _, event -> drag(event) } }
    val root: View = card
    private var attached = false

    fun attach() {
        if (!attached) {
            windowManager.addView(root, params)
            attached = true
        }
    }

    fun detach() {
        if (attached) {
            try { windowManager.removeView(root) } catch (_: Exception) {}
            attached = false
        }
    }

    fun render(snapshot: EngineSnapshot) {
        val m = snapshot.metrics
        heading.text = "${snapshot.symbol} • 1M TREND ${m.trend}"
        price.text = fmtPrice(m.currentPrice)
        emaPrice.text = "EMA7: ${fmtPrice(m.ema7Price)}  |  EMA99: ${fmtPrice(m.ema99Price)}"
        emaVolume.text = "Volume EMA2: ${fmtNum(m.ema2Volume)}"
        emaMomentum.text = "Momentum: ${fmtNum(m.currentMomentum)}"
        val flags = listOfNotNull(
            if (m.priceAnomaly == true) "PRICE" else null,
            if (m.volumeAnomaly == true) "VOLUME" else null,
            if (m.momentumAnomaly == true) "MOMENTUM" else null,
        )
        anomalies.text = if (flags.isEmpty()) "OUTLIERS: NONE" else "OUTLIER: " + flags.joinToString(" • ")
        anomalies.setTextColor(if (flags.isEmpty()) MUTED else ALERT)
    }

    private var startX = 0
    private var startY = 0
    private var rawX = 0f
    private var rawY = 0f

    private fun drag(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                rawX = event.rawX
                rawY = event.rawY
                startX = params.x
                startY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - rawX).toInt()
                val dy = (event.rawY - rawY).toInt()
                if (abs(dx) + abs(dy) > dp(3)) {
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    if (attached) windowManager.updateViewLayout(root, params)
                }
                return true
            }
            MotionEvent.ACTION_UP -> return true
        }
        return false
    }

    companion object {
        private val TEXT = Color.parseColor("#F4F7FB")
        private val MUTED = Color.parseColor("#9AA4B5")
        private val ALERT = Color.parseColor("#FFC45C")
    }
}
