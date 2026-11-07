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
    private val panelWidth = minOf(
        dp(360),
        (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
    )

    private val params = WindowManager.LayoutParams(
        panelWidth,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(72)
    }

    private val headerTitle = label(13f, COLOR_MUTED, bold = true).apply {
        text = "BINANCE DECISION"
    }
    private val verdictView = label(36f, COLOR_DNT, bold = true).apply {
        gravity = Gravity.CENTER_VERTICAL
    }
    private val priceView = label(11f, COLOR_MUTED, bold = true).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private val alignmentView = label(11f, COLOR_TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = rounded(COLOR_SECTION, 9)
    }
    private val reasonView = label(12f, COLOR_MUTED).apply {
        setLineSpacing(dp(2).toFloat(), 1f)
        maxLines = 2
    }

    private val verdictHero = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_HERO_DNT, 14)
        setPadding(dp(14), dp(10), dp(14), dp(11))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(verdictView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(priceView)
        }
        addView(row, matchWrap())
        addView(alignmentView, matchWrap(top = 6))
        addView(reasonView, matchWrap(top = 7))
    }

    private val symbolInput = EditText(context).apply {
        setText("BTCUSDT")
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
        hint = "BTCUSDT"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSingleLine(true)
        background = rounded(COLOR_INPUT, 9)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setOnTouchListener { _, event ->
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

    private val five = Section("5 MINUTE CHART")
    private val fifteen = Section("15 MINUTE CHART")
    private val hour = Section("1 HOUR CHART")
    private val problemsSection = Section("PROBLEMS")
    private val logSection = Section("CONNECTION / ERROR LOG")

    private val scrollBody = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(12))
        addView(symbolRow(), matchWrap(bottom = 10))
        addView(five.root)
        addView(fifteen.root)
        addView(hour.root)
        addView(problemsSection.root)
        addView(logSection.root)
        addView(actionRow(), matchWrap(top = 2))
    }

    private val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }.apply {
        isFillViewport = false
        addView(scrollBody)
        isVerticalScrollBarEnabled = false
    }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        minimumWidth = panelWidth
        addView(header())
        addView(verdictHero, matchWrap(start = 10, end = 10, bottom = 4))
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
        headerTitle.text = "BINANCE  •  " + snapshot.symbol
        if (!symbolInput.hasFocus() && symbolInput.text.toString() != snapshot.symbol) {
            symbolInput.setText(snapshot.symbol)
        }

        val decision = snapshot.decision
        verdictView.text = decision.verdict.name
        verdictView.setTextColor(verdictColor(decision.verdict))
        verdictHero.background = rounded(heroColor(decision.verdict), 14)
        priceView.text = "LAST PRICE\n$" + fmtPrice(decision.five.price)

        alignmentView.text = listOf(decision.hour, decision.fifteen, decision.five)
            .joinToString("     ") { summary(it) }

        reasonView.text = when (decision.verdict) {
            Verdict.UP -> "All three timeframe checks agree UP."
            Verdict.DOWN -> "All three timeframe checks agree DOWN."
            Verdict.DNT -> "DNT — open Problems below to see exactly what is blocking a direction."
        }

        five.bind(decision.five)
        fifteen.bind(decision.fifteen)
        hour.bind(decision.hour)

        val problems = decision.problems
        val problemText = if (problems.isEmpty()) {
            "No blocking problems. The 1h trend, 15m momentum, and 5m confirmation agree."
        } else {
            problems.joinToString("\n\n") { "• " + it }
        }
        problemsSection.bindText(
            if (problems.isEmpty()) "CLEAR" else problems.size.toString() + " ISSUE(S)",
            problemText,
            if (problems.isEmpty()) COLOR_UP else COLOR_DNT,
        )

        logSection.bindLog(snapshot.logText.ifBlank { "No connection log entries yet." })
    }

    private fun summary(score: TimeframeScore): String {
        return score.interval.uppercase() + " " +
            Scorer.timeframeBias(score).name + " " + signed(score.score)
    }

    private fun header(): View {
        val min = clickable("–") { setMinimized(!minimized) }
        val close = clickable("×") { onClose() }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(8), dp(7))
            addView(headerTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(min)
            addView(close)
            setOnTouchListener { _, event -> drag(event) }
        }
    }

    private fun symbolRow(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(symbolInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(space(7), LinearLayout.LayoutParams(dp(7), 1))
            addView(button("SET") { submitSymbol() })
        }
    }

    private fun actionRow(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button("REFRESH DATA") { onRefresh() })
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
                params.x = (dragStartX + dx).coerceAtLeast(0)
                params.y = (dragStartY + dy).coerceAtLeast(0)
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
        reasonView.visibility = if (value) View.GONE else View.VISIBLE
        card.minimumWidth = if (value) dp(116) else panelWidth
        verdictView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (value) 23f else 36f)
    }

    private inner class Section(title: String) {
        private val chevron = label(11f, COLOR_ACCENT, bold = true).apply { text = "▸" }
        private val titleView = label(12f, COLOR_TEXT, bold = true).apply { text = title }
        private val statusView = label(10f, COLOR_MUTED, bold = true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        private val body = label(11f, COLOR_TEXT).apply {
            visibility = View.GONE
            setLineSpacing(dp(2).toFloat(), 1.02f)
            setPadding(dp(4), dp(8), dp(4), dp(3))
            setTextIsSelectable(true)
        }
        private var open = false

        val root: View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_SECTION, 11)
            setPadding(dp(10), dp(9), dp(10), dp(8))
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(chevron)
                addView(space(7), LinearLayout.LayoutParams(dp(7), 1))
                addView(titleView)
                addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { toggle() }
            }
            addView(head)
            addView(body, matchWrap(top = 4))
            layoutParams = matchWrap(bottom = 7)
        }

        fun bind(score: TimeframeScore) {
            val bias = Scorer.timeframeBias(score)
            statusView.text = if (score.connection == ConnectionState.CONNECTED) {
                bias.name + "  " + signed(score.score)
            } else {
                "NOT WORKING"
            }
            statusView.setTextColor(
                if (score.connection != ConnectionState.CONNECTED) COLOR_DOWN else verdictColor(bias),
            )
            body.text = score.metricsText()
        }

        fun bindText(status: String, text: String, color: Int) {
            statusView.text = status
            statusView.setTextColor(color)
            body.text = text
        }

        fun bindLog(text: String) {
            val hasProblems = text.contains("FAIL", ignoreCase = true) ||
                text.contains("fallback", ignoreCase = true) ||
                text.contains("Rejected", ignoreCase = true)
            bindText(
                if (hasProblems) "CHECK" else "OK",
                text,
                if (hasProblems) COLOR_DNT else COLOR_MUTED,
            )
        }

        private fun toggle() {
            open = !open
            chevron.text = if (open) "▾" else "▸"
            body.visibility = if (open) View.VISIBLE else View.GONE
        }
    }

    private fun button(text: String, onClick: () -> Unit): TextView =
        label(11f, COLOR_BG, bold = true).apply {
            this.text = text
            gravity = Gravity.CENTER
            background = rounded(COLOR_ACCENT, 9)
            setPadding(dp(13), dp(9), dp(13), dp(9))
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

    private fun space(value: Int) = View(context)

    private fun matchWrap(
        top: Int = 0,
        bottom: Int = 0,
        start: Int = 0,
        end: Int = 0,
    ) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
        leftMargin = dp(start)
        rightMargin = dp(end)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.argb(247, 12, 17, 27))
        setStroke(dp(1), Color.argb(120, 91, 125, 182))
    }

    private fun heroColor(verdict: Verdict) = when (verdict) {
        Verdict.UP -> COLOR_HERO_UP
        Verdict.DOWN -> COLOR_HERO_DOWN
        Verdict.DNT -> COLOR_HERO_DNT
    }

    private fun verdictColor(verdict: Verdict) = when (verdict) {
        Verdict.UP -> COLOR_UP
        Verdict.DOWN -> COLOR_DOWN
        Verdict.DNT -> COLOR_DNT
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private val COLOR_TEXT = Color.parseColor("#F4F7FB")
        private val COLOR_MUTED = Color.parseColor("#9AA4B5")
        private val COLOR_UP = Color.parseColor("#3DDC97")
        private val COLOR_DOWN = Color.parseColor("#FF647C")
        private val COLOR_DNT = Color.parseColor("#FFC45C")
        private val COLOR_ACCENT = Color.parseColor("#86A9FF")
        private val COLOR_BG = Color.parseColor("#101726")
        private val COLOR_INPUT = Color.parseColor("#252F42")
        private val COLOR_SECTION = Color.parseColor("#1A2435")
        private val COLOR_HERO_UP = Color.parseColor("#17352E")
        private val COLOR_HERO_DOWN = Color.parseColor("#3A202B")
        private val COLOR_HERO_DNT = Color.parseColor("#352D1C")
    }
}
