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

## BTC two-point 20-percentage-point pattern backtest (current)

Tap **BACKTEST** and start the historical 1,000-market scan. The existing
downloader, 10 AM–11 PM Eastern filter, quote source, settlements and live
alerts remain unchanged. The test uses real Kalshi YES bid/ask **midpoints**
(not the chart's custom BTC-distance percentages) and settled YES/NO outcomes.

The report now shows only patterns meeting **BOTH**:

- **Hit rate strictly ABOVE 10% of all scanned markets**, AND **at least
  100 distinct markets**. For exactly 1,000 scanned markets, the minimum is
  **101**, not 100. One first fresh two-point completion per path and market
  is counted; YES/NO mirrors are not counted as independent occurrences.
- **At least 20 percentage points (absolute difference)** between the
  selected buy side's observed settlement win rate and the **last YES
  midpoint threshold** in the two-point pattern. This is the user's
  requested raw gap definition, **not** a buy-price or trading-edge test.

The completion must occur between **8 and 4 minutes remaining**. The report
searches contiguous time windows of 2–5 minutes inside that span and picks
the qualifying window with the highest historical winning percentage,
favoring more hits on ties. Each displayed pattern includes precisely:

    50% - 40%
    Buy: DOWN
    WR: 70.0% (140/200)
    Hit rate: 20.0% (200/1000)
    Best timeframe: 8 to 4 mins left

This is a **format example, not a measured result**. UP means buying YES;
DOWN means buying NO. Candidate sides are compared using retrospective ROI
from actual historical **buy asks** and estimated taker fees, then just the
best side is displayed per unique two-point YES path. Timing is selected
retrospectively, and high historical win rates can overfit.

**Important:** A path ending at **YES 40%** implies approximately
**NO 60%**, so a **70% NO win rate** is only about 10 points above the NO
midpoint, NOT 30 points above the effective purchase price. Actual ask,
spread, fees, and fills can make a qualifying 20-point *raw* gap pattern
unprofitable. These filters are for exploration, not validated live
signals. Nothing automatically places trades or changes alerts.

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
