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

## BTC two- and three-point actual-price edge backtest (current)

Tap **BACKTEST** then **START 1000 PAST MARKETS**. The download remains
research-only; it does not place orders or change live notifications. The app
reads settled KXBTC15M markets, observed one-minute YES bid/ask quotes and
settlement outcomes (10 AM–11 PM Eastern). The scanner uses **Kalshi YES
midpoint** levels, not the Binance chart's custom percentages.

The scanner independently evaluates all ordered **two- and three-point paths**
at 10-point levels from 10 through 90, buying either **UP (YES)** or
**DOWN (NO)**. Each stage must be OBSERVED within 5 percentage points of
its named level on a different valid one-minute close. Jumping over a level
does not count as touching it. The first three minutes (15–13 minutes
remaining) are research only; the final stage must close between 12 and 1
minutes remaining. There is at most one qualifying entry per path per market.

A reported setup must meet **all** these requirements:
- Discovery historical BUY-side win rate minus average observed **BUY-side
  ask price** is at least **+20 percentage points** (not the absolute
  difference and not the opposing side's quote).
- Positive modeled one-contract net after approximate taker fees, with
  observed buy ask in the 1–99c range.
- Strictly **more than 10% market hit rate**, both across the oldest 70%
  discovery period and across the full scan, with at least 30 discovery
  examples. A pattern seen in exactly 10% is rejected.

For a YES path of 70% -> 60% and a **DOWN** trade, the cost is the observed
NO ask (1 − YES bid), not 60c. If the real NO ask is 41c, a 60% historical
NO win rate is only a **19-point** advantage, so this candidate **fails**
the +20-point screen. This avoids the old scanner's false positives, which
compared the win rate to the last YES level using an absolute difference.

Eight predefined entry-time ranges are compared using discovery data, with
the best window chosen by average fee-adjusted profit * opportunity
frequency. The report shows for each qualifying path:

- Two vs. three stages, buy UP or DOWN, and best timeframe.
- Discovery, newest 30%, and full-period sample counts, unique-market hit
  rate, settlement win rate, observed buy ask, modeled fees, and net result.
- A separately scored mirror (YES stages become 100 − each stage, and the
  purchase side flips). **A mirror is not assumed to be profitable** simply
  because the original qualifies; mirrored pairs are listed first.
- For three-point paths, the two-point prefix at the **same timeframe**, so
  you can see whether the additional stage helps the win rate enough to
  justify any reduction in frequency.
- Combined distinct-market coverage (all qualifying paths, each market
  counted once) plus separately mirror-paired coverage. **50% is a target,
  not an artificially forced output**.

The historical outcomes of the newest 30% are shown separately; however,
the full-scan frequency floor uses newest market counts, and exploring
hundreds of paths still causes selection bias. This is **not independent
proof of a profitable strategy**. Observed minute-close asks are not
guaranteed executable fills, and estimates exclude slippage. Forward-test
against new settled markets before staking real funds.

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
