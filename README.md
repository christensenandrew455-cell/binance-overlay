# BTC Range Check

Android historical backtester for BTC 15-minute sections, 10 AM to 11 PM
America/New_York. **CHECK** scans 100 complete markets; the scan can continue
with the screen off (foreground service), and **COPY RESULTS** copies the
two-number answer: the percentage threshold and continuous minute range.

Uses the existing Binance.US BTCUSDT 1-minute OHLC connection and the average
60-candle high-low movement. Ceiling = ACM60 times remaining whole minutes.
The user-defined rule maps target to 50% and ceiling to 90%. Every threshold
is recorded at its **first crossing only per market**, even after a reversal.

The Kalshi comparison is an **illustrative synthetic price projection** using
a 60-candle log-return volatility and a driftless normal model. Synthetic quote
is rounded to whole cents and compared in the 60–79c zone with the general
per-contract taker-fee approximation. **These are not observed Kalshi asks and
cannot demonstrate actual historic arbitrage or profit.** Actual quotes are
determined by Kalshi trading and are not recoverable from Binance candles alone.

No trading, live chart, alerts, subscriptions, credentials or Kalshi API required.
GitHub Actions runs tests and builds an APK on push to main.

## Frequent two-point Kalshi pattern backtest (current)

Tap **BACKTEST** on the BTC chart, then **RUN BACKTEST (1000)**. The
existing foreground service and historical downloader still search up to 1,000
distinct settled BTC 15-minute Kalshi markets beginning between 10 AM and 11 PM
Eastern. Nothing about downloading, time-zone filtering, or live alerts changed.

Instead of dumping hundreds of rare combinations, the results only display
**two-point patterns seen at least 100 times AND in at least 10% of all scanned
markets**, with a *fresh second-point completion* at **8 to 4 minutes remaining**.
Both stages must appear in separate completed one-minute quote candles. A
completion before 8 minutes left is not re-counted later without first
reaching the first level again. Each matching path counts at most once per
market. Three-point patterns are not on the shortlist.

The source is actual historical Kalshi YES quote bid/ask closes and final
YES/NO settlements for KXBTC15M. The quote path's percentages are midpoints,
not the custom BTC-distance chart percentages. A **NO 80->60** path is
equivalent to a **YES 20->40** quote path: they refer to the *same market
observation*, not two independent trading opportunities.

Results are ranked by *historical total estimated one-contract net return*
across the scanned markets, rather than solely by win percentage. Each entry
shows unique-market hit frequency, observed settlement win rate, actual
historical buy-side ask, an approximate taker fee, net cents per hypothetical
entry, ROI, payout/cost multiplier, and both older 70% and newest 30%
subsamples. To qualify for display after the frequency screen, a setup must
have positive net in both subsamples (at least 20 older and 10 newer entries)
and its unadjusted Wilson 95% lower win-rate must exceed mean buy cost
including estimated fees. The report shows exact-minute counts (8,7,6,5,4)
and the best exploratory adjacent two-minute window selected from the older
sample, checked against the newer sample.

It also shows **distinct combined coverage**, without adding overlapping
pattern counts or mirrored YES/NO counts. If nothing meets the strict
requirements it explicitly reports **NONE QUALIFIED**, not fabricated wins.
The report is short, scrollable and copyable.

**Caveats:** Minute-close quotes are not a record of an executable order or
intraminute path. The aggregate fees are estimates, slippage is excluded, and
searching many candidate rules can generate spurious historical edges.
The newest sample helps check consistency but is not untouched independent
validation. Test against future markets before relying on any pattern.
No live order or alert strategy is automatically changed by this report.

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
