package com.decisionoverlay.checker

/**
 * Manual multi-timeframe point system. It never sends orders.
 *
 * 1h is the primary trend filter; 15m confirms momentum. These two must agree.
 * 5m is a fast confirmation: it may be neutral without blocking a signal, but a
 * clear 5m move against the higher-timeframe direction blocks the signal. This
 * avoids requiring three unusually strict filters to line up on every update.
 */
object Scorer {
    const val MIN_CANDLES = 40

    fun score5m(candles: List<Candle>, feed: FeedStatus): TimeframeScore =
        score(candles, feed, "5-minute", "5m", ::components5m)

    fun score15m(candles: List<Candle>, feed: FeedStatus): TimeframeScore =
        score(candles, feed, "15-minute", "15m", ::components15m)

    fun score1h(candles: List<Candle>, feed: FeedStatus): TimeframeScore =
        score(candles, feed, "1-hour", "1h", ::components1h)

    /** Direction for one chart by itself, before the other two charts are considered. */
    fun timeframeBias(score: TimeframeScore): Verdict {
        if (score.connection != ConnectionState.CONNECTED || score.candleCount < MIN_CANDLES) {
            return Verdict.DNT
        }
        val direction = when (score.interval) {
            "5m" -> executionDirection(score)
            "15m" -> momentumDirection(score)
            "1h" -> trendDirection(score)
            else -> 0
        }
        return when (direction) {
            1 -> Verdict.UP
            -1 -> Verdict.DOWN
            else -> Verdict.DNT
        }
    }

    fun decide(five: TimeframeScore, fifteen: TimeframeScore, hour: TimeframeScore): Decision {
        val blocked = mutableListOf<String>()
        if (five.candleCount < MIN_CANDLES || fifteen.candleCount < MIN_CANDLES || hour.candleCount < MIN_CANDLES) {
            blocked += "Not enough candles yet (need $MIN_CANDLES on each timeframe)."
        }
        val broken = listOf(five, fifteen, hour).filter { it.connection != ConnectionState.CONNECTED }
        if (broken.isNotEmpty()) {
            blocked += broken.joinToString("; ") { "${it.label} is Not Working" } + "."
        }

        val hourDir = trendDirection(hour)
        val midDir = momentumDirection(fifteen)
        val execDir = executionDirection(five)

        if (hourDir == 0) {
            blocked += "1h trend is neutral or lacks EMA confirmation (need score ±30 plus a stacked EMA)."
        }
        if (midDir == 0) {
            blocked += "15m momentum is neutral (need score ±25 and at least one RSI/candle-net confirmation)."
        } else if (hourDir != 0 && midDir != hourDir) {
            blocked += "15m momentum disagrees with the 1h trend."
        }
        if (execDir != 0 && hourDir != 0 && execDir != hourDir) {
            blocked += "5m momentum is moving against the 1h trend."
        }

        // Higher-timeframe trend + momentum agreement is the core signal.
        // 5m may be neutral; it only vetoes when it gives a clear opposite signal.
        val verdict = when {
            blocked.isNotEmpty() -> Verdict.DNT
            hourDir == 1 && midDir == 1 -> Verdict.UP
            hourDir == -1 && midDir == -1 -> Verdict.DOWN
            else -> Verdict.DNT
        }
        val reason = when (verdict) {
            Verdict.UP -> if (execDir == 1) "UP. 1h trend and 15m momentum agree; 5m confirms." else "UP. 1h trend and 15m momentum agree; 5m is neutral."
            Verdict.DOWN -> if (execDir == -1) "DOWN. 1h trend and 15m momentum agree; 5m confirms." else "DOWN. 1h trend and 15m momentum agree; 5m is neutral."
            Verdict.DNT -> blocked.joinToString(" ").ifBlank { "No clean alignment. Do not trade." }
        }
        return Decision(verdict, reason, five, fifteen, hour, blocked.toList())
    }

    private fun trendDirection(score: TimeframeScore): Int {
        val stack = points(score, "EMA stack")
        return when {
            score.score >= 30 && stack > 0 -> 1
            score.score <= -30 && stack < 0 -> -1
            else -> 0
        }
    }

    private fun momentumDirection(score: TimeframeScore): Int {
        val rsi = points(score, "RSI momentum")
        val net = points(score, "Candle net")
        if (rsi == 0 && net == 0) return 0
        return when {
            score.score >= 25 -> 1
            score.score <= -25 -> -1
            else -> 0
        }
    }

    private fun executionDirection(score: TimeframeScore): Int {
        val stack = points(score, "EMA stack")
        val candles = points(score, "Candles")
        val rsi = points(score, "RSI zone")
        val macd = points(score, "MACD expand")
        // A 5m confirmation is less restrictive: require an EMA stack and at least
        // one supporting momentum component. Candle confirmation adds confidence.
        val longOk = stack > 0 && (candles > 0 || rsi > 0 || macd > 0) && score.score >= 50
        val shortOk = stack < 0 && (candles < 0 || rsi < 0 || macd < 0) && score.score <= -50
        return when {
            longOk -> 1
            shortOk -> -1
            else -> 0
        }
    }

    private fun points(score: TimeframeScore, name: String): Int =
        score.components.firstOrNull { it.name == name }?.points ?: 0

    private fun score(
        candles: List<Candle>,
        feed: FeedStatus,
        label: String,
        interval: String,
        componentsFor: (List<Candle>, Double, Double?, Double?, Indicators.Macd?) -> List<ComponentScore>,
    ): TimeframeScore {
        val data = candles.sortedBy { it.openTime }
        val closes = data.map { it.close }
        val price = closes.lastOrNull()
        val ema9 = if (price == null) null else Indicators.ema(closes, 9)
        val ema21 = if (price == null) null else Indicators.ema(closes, 21)
        val rsi = if (price == null) null else Indicators.rsi(closes)
        val macd = if (price == null) null else Indicators.macd(closes)
        val components = if (data.size < MIN_CANDLES || price == null) {
            listOf(ComponentScore("Data", 0, "Need $MIN_CANDLES candles, have ${data.size}."))
        } else {
            componentsFor(data, price, ema9, ema21, macd)
        }
        return TimeframeScore(
            label = label,
            interval = interval,
            score = components.sumOf { it.points },
            components = components,
            price = price,
            ema9 = ema9,
            ema21 = ema21,
            rsi = rsi,
            macdLine = macd?.line,
            macdSignal = macd?.signal,
            macdHist = macd?.histogram,
            candleBias = candleBias(data),
            volumeRatio = Indicators.volumeRatio(data),
            connection = feed.state,
            connectionDetail = feed.detail,
            updatedAt = feed.updatedAt,
            lastError = feed.lastError,
            candleCount = data.size,
        )
    }

    private fun components5m(
        data: List<Candle>,
        price: Double,
        ema9: Double?,
        ema21: Double?,
        macd: Indicators.Macd?,
    ): List<ComponentScore> {
        val stack = stackPoints(price, ema9, ema21, minSeparation = 0.0004, points = 30)
        val candle = candlePoints(data, price)
        val rsiValue = Indicators.rsi(data.map { it.close })
        val rsiPoints = when {
            rsiValue == null -> 0
            rsiValue in 52.0..70.0 -> 20
            rsiValue in 30.0..48.0 -> -20
            else -> 0
        }
        val rsiDetail = when {
            rsiValue == null -> "RSI unavailable"
            rsiPoints > 0 -> "${fmtNum(rsiValue)} is inside the long execution zone 52..70"
            rsiPoints < 0 -> "${fmtNum(rsiValue)} is inside the short execution zone 30..48"
            rsiValue > 70 -> "${fmtNum(rsiValue)} is extended above 70 — not an exact long"
            rsiValue < 30 -> "${fmtNum(rsiValue)} is extended below 30 — not an exact short"
            else -> "${fmtNum(rsiValue)} is a 50/50 band"
        }
        val hist = macd?.histogram
        val prev = macd?.previousHistogram
        val mag = if (hist == null || price == 0.0) 0.0 else kotlin.math.abs(hist) / price
        val macdPoints = when {
            hist == null || prev == null || mag < 0.00012 -> 0
            hist > 0 && hist > prev -> 25
            hist < 0 && hist < prev -> -25
            else -> 0
        }
        val macdDetail = "hist ${fmtNum(hist)} prev ${fmtNum(prev)} size ${fmtNum(mag * 100)}% of price"
        return listOf(
            ComponentScore("EMA stack", stack, stackDetail(price, ema9, ema21, stack)),
            ComponentScore("Candles", candle.first, candle.second),
            ComponentScore("RSI zone", rsiPoints, rsiDetail),
            ComponentScore("MACD expand", macdPoints, macdDetail),
            volumeNote(data),
        )
    }

    private fun components15m(
        data: List<Candle>,
        price: Double,
        ema9: Double?,
        ema21: Double?,
        macd: Indicators.Macd?,
    ): List<ComponentScore> {
        val ema = stackPoints(price, ema9, ema21, minSeparation = 0.0003, points = 25)
        val rsiValue = Indicators.rsi(data.map { it.close })
        val rsiPoints = when {
            rsiValue == null -> 0
            rsiValue > 52.0 -> 25
            rsiValue < 48.0 -> -25
            else -> 0
        }
        val hist = macd?.histogram
        val mag = if (hist == null || price == 0.0) 0.0 else kotlin.math.abs(hist) / price
        val macdPoints = when {
            hist == null || mag < 0.00012 -> 0
            hist > 0 -> 25
            hist < 0 -> -25
            else -> 0
        }
        val last6 = data.takeLast(6)
        val net = last6.sumOf { it.close - it.open }
        val netPct = if (price == 0.0) 0.0 else net / price
        val netPoints = when {
            netPct > 0.0015 -> 25
            netPct < -0.0015 -> -25
            else -> 0
        }
        return listOf(
            ComponentScore("EMA direction", ema, stackDetail(price, ema9, ema21, ema)),
            ComponentScore(
                "RSI momentum",
                rsiPoints,
                if (rsiPoints == 0) "RSI ${fmtNum(rsiValue)} is inside 48..52 (50/50)" else "RSI ${fmtNum(rsiValue)}",
            ),
            ComponentScore("MACD sign", macdPoints, "hist ${fmtNum(hist)} size ${fmtNum(mag * 100)}% of price"),
            ComponentScore(
                "Candle net",
                netPoints,
                "6-candle body net ${fmtNum(netPct * 100)}% (weak inside ±0.15%)",
            ),
            volumeNote(data),
        )
    }

    private fun components1h(
        data: List<Candle>,
        price: Double,
        ema9: Double?,
        ema21: Double?,
        macd: Indicators.Macd?,
    ): List<ComponentScore> {
        val stack = stackPoints(price, ema9, ema21, minSeparation = 0.001, points = 30)
        val recent = data.takeLast(8)
        var ups = 0
        var downs = 0
        for (index in 1 until recent.size) {
            when {
                recent[index].close > recent[index - 1].close -> ups++
                recent[index].close < recent[index - 1].close -> downs++
            }
        }
        val structure = when {
            ups >= 5 -> 25
            downs >= 5 -> -25
            else -> 0
        }
        val line = macd?.line
        val hist = macd?.histogram
        val signal = macd?.signal
        val lineMag = if (line == null || price == 0.0) 0.0 else kotlin.math.abs(line) / price
        val macdPoints = when {
            line == null || hist == null || signal == null || lineMag < 0.0008 -> 0
            hist > 0 && line > signal && line > 0 -> 25
            hist < 0 && line < signal && line < 0 -> -25
            else -> 0
        }
        val rsiValue = Indicators.rsi(data.map { it.close })
        val rsiPoints = when {
            rsiValue == null -> 0
            rsiValue in 55.0..75.0 -> 20
            rsiValue in 25.0..45.0 -> -20
            else -> 0
        }
        return listOf(
            ComponentScore("EMA stack", stack, stackDetail(price, ema9, ema21, stack) + " (1h separation floor 0.10%)"),
            ComponentScore("Structure", structure, "$ups up-closes / $downs down-closes over the last ${recent.size - 1} steps (need 5)"),
            ComponentScore(
                "MACD regime",
                macdPoints,
                "line ${fmtNum(line)} signal ${fmtNum(signal)} hist ${fmtNum(hist)} |line| ${fmtNum(lineMag * 100)}% of price",
            ),
            ComponentScore(
                "RSI regime",
                rsiPoints,
                "RSI ${fmtNum(rsiValue)} (long 55..75, short 25..45, extended or neutral scores 0)",
            ),
            volumeNote(data),
        )
    }

    private fun stackPoints(price: Double, ema9: Double?, ema21: Double?, minSeparation: Double, points: Int): Int {
        if (ema9 == null || ema21 == null || price == 0.0) return 0
        val separation = kotlin.math.abs(ema9 - ema21) / price
        if (separation < minSeparation) return 0
        return when {
            price > ema9 && ema9 > ema21 -> points
            price < ema9 && ema9 < ema21 -> -points
            else -> 0
        }
    }

    private fun stackDetail(price: Double, ema9: Double?, ema21: Double?, points: Int): String {
        if (ema9 == null || ema21 == null || price == 0.0) return "EMA unavailable"
        val separation = kotlin.math.abs(ema9 - ema21) / price * 100.0
        val order = when {
            price > ema9 && ema9 > ema21 -> "price > EMA9 > EMA21"
            price < ema9 && ema9 < ema21 -> "price < EMA9 < EMA21"
            else -> "EMAs are mixed"
        }
        val awarded = if (points == 0) "no award (mixed or too tight)" else "awarded"
        return "$order, separation ${fmtNum(separation)}% — $awarded"
    }

    private fun candlePoints(data: List<Candle>, price: Double): Pair<Int, String> {
        val last3 = data.takeLast(3)
        if (last3.size < 3 || price == 0.0) return 0 to "Not enough candles"
        val ups = last3.count { it.close > it.open }
        val downs = last3.count { it.close < it.open }
        val last = last3.last()
        val previous = last3[last3.size - 2]
        val body = kotlin.math.abs(last.close - last.open) / price
        val longOk = ups >= 2 && last.close > last.open && last.close > previous.close && body >= 0.0002
        val shortOk = downs >= 2 && last.close < last.open && last.close < previous.close && body >= 0.0002
        val points = when {
            longOk -> 25
            shortOk -> -25
            else -> 0
        }
        val detail = "$ups/3 up, $downs/3 down, last body ${fmtNum(body * 100)}%"
        return points to detail
    }

    private fun volumeNote(data: List<Candle>): ComponentScore {
        val ratio = Indicators.volumeRatio(data)
        val detail = if (ratio == null) {
            "Volume baseline not ready"
        } else {
            "Last candle is ${fmtNum(ratio)}x the prior 20-candle average. Diagnostic only, not a gate."
        }
        return ComponentScore("Volume", 0, detail)
    }

    private fun candleBias(data: List<Candle>): String {
        if (data.isEmpty()) return "n/a"
        val last3 = data.takeLast(3)
        val ups = last3.count { it.close > it.open }
        val downs = last3.count { it.close < it.open }
        val last = if (data.last().close >= data.last().open) "up" else "down"
        return "$ups/3 up, $downs/3 down, last $last"
    }
}
