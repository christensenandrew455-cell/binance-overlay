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

## BACKTEST button — 100-market minute data collector (current)

**Only phase one is active.** In the Android app, tap **BACKTEST** then
**COLLECT 100 MARKETS**. The foreground service pulls the **100 latest distinct
settled BTC 15-minute Kalshi markets** with each market starting at or after
10:00 AM and ending by 11:00 PM America/New_York. The rule uses actual Eastern
clock time, including daylight saving, and no markets outside the session.

For each market, the report outputs one CSV row with a market ticker, exact
Eastern market start and end times, the final settlement **UP** or **DOWN**,
and **15 chronological one-minute columns** M01 through M15. M01 is the
earliest completed 1-minute candle and M15 is the latest completed minute.
All 15 minutes are observation data for pattern *discovery*, not executable
trading signals.

The report contains TWO complete matrix versions:
- **Ten-band matrix:** 10-point YES probability buckets (0 means 0–9.999%,
  10 means 10–19.999%, ... 90 means 90–100%). The complementary NO
  percentage is 100 minus the observed YES percentage.
- **One-decimal matrix:** The source average YES bid/ask price for that
  minute, before rounding to the 10-point bucket. When available, the
  reported minute value is the mean of bid/ask **open, high, low and close**
  (an OHLC approximation, **not** a time-weighted average). If incomplete,
  the recorded minute bid/ask close midpoint is used as a fallback.
  The report counts close-only fallback cells.

Unavailable or invalid minute observations appear as **NA**, never as
interpolated or invented quotes. A quality header shows how many markets
have every minute present. The collector stops at 100 usable markets (at
least two actual observations per market); when fewer are obtained the
report explicitly says **INCOMPLETE**.

Use **SHARE REPORT**, **COPY FULL**, or the **COPY PART** button to provide the
entire dataset for pattern discovery and comparison. This version does
**not** search patterns, apply any win-/hit-rate rules, display hypothetical
purchase prices or fees, execute trades, or run the 1,000-market backtest.
That validation is a **separate future phase**, after promising patterns
have been defined without seeing those 1,000 markets.

Older research-only pattern scanners remain in source control but are
no longer called by the BACKTEST button. Live alerts are unchanged.

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
