# BTC 15-minute BINANCE pattern backtest — 27 locked rules + 7 chart paths

Tap **BACKTEST** then **RUN 100 BINANCE MARKETS** on the Android app. Only
that button launches the historical scan. This version **does not request
Kalshi data or run a backtest automatically on GitHub**. GitHub Actions builds
and tests the Android APK; the old automated Kalshi research workflow was
disabled (manual only).

Source: historical Binance.US BTCUSDT **one-minute OHLC candles**.
The phone paginates backwards until it has up to **100 complete, distinct
15-minute sections**. A section is eligible only if its local **Eastern**
start is at or after **10:00 AM** and its end is no later than **11:00 PM**.
The service requires all 60 prior warmup candles, all 15 section candles,
and no tie between the starting target and final BTC close. Missing data
are not fabricated. A scan with fewer than 100 eligible sections reports
**INCOMPLETE**.

The target is the BTC close of the candle immediately preceding each
15-minute section. At the end of each completed section, **UP** means BTC
closed ABOVE the target; **DOWN** means BTC closed BELOW it.

The same **27 locked patterns** from the earlier reports are used. The report
also tests the **seven path shapes** from the supplied chart, using the
target-centered scale: 50 is the BTC target, above-target thresholds are
60/70/80/90 and symmetric below-target thresholds are 40/30/20/10. Only the
path shapes transfer; the source chart's percentages and results do not.
The existing 27 rules' named 10-point labels are **remapped from Kalshi YES quote bands onto
the app's own Binance signed distance-to-target chart**, since the two
price scales are not interchangeable:

- 90 = above-target chart +90; 80 = +80; 70 = +70; 60 = +60
- 50 = near the target (signed chart magnitude 50–59)
- 40 = below-target chart -60; 30 = -70; 20 = -80; 10 = -90

These are **custom chart levels, not calibrated win probabilities**.
For each closed one-minute candle, the custom level uses its BTC close,
the target price, the latest **60 completed one-minute high-low ranges**,
and the minutes remaining. The calculation never uses future BTC data to
produce an earlier chart observation. The sequences must occur in order on
different minute closes; intermediate levels are allowed. First completed
match per pattern/section only. Patterns may start in the first 3 minutes,
but may only **complete between 11 and 1 minutes left**.

The report shows **all 27** existing patterns and a separate seven-path table,
including low-frequency and zero-occurrence
rules, sorted by hit count. Fields: hits, **hit rate** (matched sections /
all eligible markets), **win rate** (correct BUY UP or BUY DOWN outcomes / matches),
most frequent match timeframe, and a separate breakdown of hits and wins
in the **11–9, 8–5, and 4–1 minutes-left** windows. No entry price, fees,
Kalshi quote analysis, or automatic orders are included.

The earlier Kalshi-derived rule discovery and any historical win rates from
that source **do not carry over** to Binance chart levels. This Binance test
produces fresh measurements under the remapped definition. Live alerts are
separate and unchanged.

## Live multi-pattern Kalshi notifications (v1.1.0)

Open the app and tap **ALERTS** to see, stop or restart the background
Kalshi quote monitor. It automatically starts with the BTC chart unless you
have turned it off. This is **separate from the Binance chart's old 70% alerts**.
Android notification permission must be allowed.

The monitor reads the public **Kalshi KXBTC15M** market listing (current real YES
best bid/ask) and its one-minute quote candles (for the sequential price paths).
Only markets starting 10 AM through 10:45 PM America/New_York are monitored.
Each step of a path must be observed in a **later one-minute candle** and must
match the historical report's first-completion definition. Signals are for
paths whose first completion is observed with **8–6 minutes remaining**.

Six selected research rules use YES midpoint thresholds:
- `40>60`, buy NO; older 49 samples +18.8c, newest 21 +11.3c.
- `50>60`, buy NO; older 65 samples +12.3c, newest 26 +6.0c.
- `40>20`, buy YES; older 92 samples +7.9c, newest 54 +3.8c.
- `50>20`, buy YES; older 69 samples +6.2c, newest 37 +4.9c.
- `30>20`, buy YES; older 102 samples +5.6c, newest 54 +2.1c.
- `60>20`, buy YES; older 37 samples +10.5c, newest 22 +5.9c.

All net values are retrospective estimates **per qualifying 1-contract entry**
using one-minute historical quote closes, an approximate taker fee and
hypothetical settlement. They do not prove that these orders can be filled or
that the estimates will repeat. The rules were chosen while inspecting the
newest 300 markets, so this is **not an untouched independent validation**.

A notification requires the live ask + approximate one-contract fee to be
at least **3 cents below the newest-sample historical win rate** (treated as an
exploratory estimate), fresh complete one-minute quote history, a live spread
no wider than 8 cents and the latest live midpoint still meeting the final
threshold. No signal is emitted from stale or missing quotes. **Only one alert
per market** is sent, choosing the largest fee-adjusted margin with a small
penalty for sample uncertainty. These overlapping rules' rates cannot be added
together to estimate the number of opportunities.

Alerts only — **no actual orders placed**. For real trading, test against
future independent markets and account for live fill availability, slippage,
fees and statistical uncertainty.
