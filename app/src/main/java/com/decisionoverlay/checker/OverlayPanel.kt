package com.decisionoverlay.checker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class OverlayPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onSymbol: (String) -> Unit,
    private val onRefresh: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(80)
    }

    private val headerTitle = label(13f, COLOR_MUTED, bold = true).apply { text = "BTCUSDT" }
    private val verdictView = label(40f, COLOR_DNT, bold = true).apply { gravity = Gravity.CENTER }
    private val alignmentView = label(12f, COLOR_MUTED).apply { gravity = Gravity.CENTER }
    private val reasonView = label(12f, COLOR_TEXT).apply { setLineSpacing(dp(2).toFloat(), 1f) }
    private val symbolInput = EditText(context).apply {
        setText("BTCUSDT")
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
        hint = "BTCUSDT"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = rounded(COLOR_INPUT, 10)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) enableTyping()
            false
        }
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitSymbol()
                true
            } else {
                false
            }
        }
    }
    private val five = Section("5-minute")
    private val fifteen = Section("15-minute")
    private val hour = Section("1-hour")
    private val logSection = Section("Error log")
    private val scrollBody = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(12))
        addView(reasonView, matchWrap())
        addView(spacer(8))
        addView(symbolRow())
        addView(spacer(8))
        addView(five.root)
        addView(fifteen.root)
        addView(hour.root)
        addView(logSection.root)
        addView(button("Refresh now") { onRefresh() })
    }
    private val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * 0.58f).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }.apply { addView(scrollBody) }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        minimumWidth = dp(280)
        addView(header())
        addView(verdictView, matchWrap(top = 2))
        addView(alignmentView, matchWrap(bottom = 8))
        addView(scroll)
    }

    val root: View = card
    private var minimized = false
    private var attached = false

    fun attach() {
        if (attached) return
        windowManager.addView(root, params)
        attached = true
    }

    fun detach() {
        if (!attached) return
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
        }
        attached = false
    }

    fun render(snapshot: EngineSnapshot) {
        headerTitle.text = snapshot.symbol
        if (!symbolInput.hasFocus() && symbolInput.text.toString() != snapshot.symbol) {
            symbolInput.setText(snapshot.symbol)
        }
        val decision = snapshot.decision
        verdictView.text = decision.verdict.name
        verdictView.setTextColor(verdictColor(decision.verdict))
        alignmentView.text = listOf(decision.hour, decision.fifteen, decision.five).joinToString("   ") {
            "${it.interval.uppercase()} ${it.headerStatus().substringBefore("   ")}"
        }
        reasonView.text = decision.reason
        five.bind(decision.five)
        fifteen.bind(decision.fifteen)
        hour.bind(decision.hour)
        logSection.bindLog(snapshot.logText.ifBlank { "No log lines yet." })
    }

    private fun header(): View {
        val title = headerTitle
        val min = clickable("–") { setMinimized(!minimized) }
        val close = clickable("×") { onClose() }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(min)
            addView(close)
            setOnTouchListener { _, event -> drag(event) }
        }
    }

    private fun symbolRow(): View {
        val apply = button("Set") { submitSymbol() }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(symbolInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
            addView(apply)
        }
    }

    private fun submitSymbol() {
        val value = symbolInput.text?.toString().orEmpty()
        disableTyping()
        onSymbol(value)
    }

    private fun enableTyping() {
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        if (attached) windowManager.updateViewLayout(root, params)
    }

    private fun disableTyping() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
        symbolInput.clearFocus()
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (attached) windowManager.updateViewLayout(root, params)
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false

    private fun drag(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                moved = false
                downRawX = event.rawX
                downRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (abs(dx) + abs(dy) > dp(4)) moved = true
                params.x = dragStartX + dx
                params.y = dragStartY + dy
                if (attached) windowManager.updateViewLayout(root, params)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) setMinimized(!minimized)
                return true
            }
        }
        return false
    }

    private fun setMinimized(value: Boolean) {
        minimized = value
        scroll.visibility = if (value) View.GONE else View.VISIBLE
        alignmentView.visibility = if (value) View.GONE else View.VISIBLE
        card.minimumWidth = if (value) dp(88) else dp(280)
        verdictView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (value) 22f else 40f)
    }

    private inner class Section(title: String) {
        private val chevron = label(12f, COLOR_ACCENT, bold = true).apply { text = "▶" }
        private val titleView = label(14f, COLOR_TEXT, bold = true).apply { text = title }
        private val statusView = label(11f, COLOR_MUTED).apply { gravity = Gravity.END }
        private val body = label(12f, COLOR_TEXT).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setLineSpacing(dp(1).toFloat(), 1.05f)
        }
        private var open = false
        val root: View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_SECTION, 12)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(chevron)
                addView(space(6), LinearLayout.LayoutParams(dp(6), 1))
                addView(titleView)
                addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { toggle() }
            }
            addView(head)
            addView(body, matchWrap(top = 6))
            layoutParams = matchWrap(bottom = 8)
        }

        fun bind(score: TimeframeScore) {
            statusView.text = score.headerStatus()
            statusView.setTextColor(
                when (score.connection) {
                    ConnectionState.CONNECTED -> if (score.score > 0) COLOR_UP else if (score.score < 0) COLOR_DOWN else COLOR_DNT
                    ConnectionState.NOT_WORKING -> COLOR_DOWN
                },
            )
            body.text = score.metricsText()
        }

        fun bindLog(text: String) {
            statusView.text = if (text.contains("FAIL") || text.contains("failure") || text.contains("Rejected")) "Check" else "Log"
            statusView.setTextColor(COLOR_MUTED)
            body.text = text
        }

        private fun toggle() {
            open = !open
            chevron.text = if (open) "▼" else "▶"
            body.visibility = if (open) View.VISIBLE else View.GONE
        }
    }

    private fun button(text: String, onClick: () -> Unit): TextView =
        label(13f, COLOR_BG, bold = true).apply {
            this.text = text
            gravity = Gravity.CENTER
            background = rounded(COLOR_ACCENT, 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }

    private fun clickable(text: String, onClick: () -> Unit): TextView =
        label(18f, COLOR_TEXT, bold = true).apply {
            this.text = text
            gravity = Gravity.CENTER
            val size = dp(32)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }

    private fun label(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun spacer(value: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(value))
    }

    private fun space(value: Int) = View(context)

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.argb(236, 14, 18, 28))
        setStroke(dp(1), Color.argb(90, 122, 162, 255))
    }

    private fun verdictColor(verdict: Verdict) = when (verdict) {
        Verdict.UP -> COLOR_UP
        Verdict.DOWN -> COLOR_DOWN
        Verdict.DNT -> COLOR_DNT
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private val COLOR_TEXT = Color.parseColor("#F4F7FB")
        private val COLOR_MUTED = Color.parseColor("#9AA4B5")
        private val COLOR_UP = Color.parseColor("#3DDC97")
        private val COLOR_DOWN = Color.parseColor("#FF5D73")
        private val COLOR_DNT = Color.parseColor("#FFC14D")
        private val COLOR_ACCENT = Color.parseColor("#7AA2FF")
        private val COLOR_BG = Color.parseColor("#0E121C")
        private val COLOR_INPUT = Color.parseColor("#242B3D")
        private val COLOR_SECTION = Color.parseColor("#1A2130")
    }
}
