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

## Historical Kalshi Pattern Backtest (new)

Tap **BACKTEST** on the BTC chart, then **RUN BACKTEST (1000)**.
The scanner looks back through **up to 1,000 usable settled markets** whose
15-minute session START falls between **10:00 AM and 11:00 PM Eastern** (10:00
through 22:45 starts; DST handled). It may examine up to 1,800 candidates to
replace missing/invalid quote histories.

The report now analyzes the same **up to 1,000 settled markets** but displays a
**compact, copyable aggregate summary**, rather than printing thousands of
empty minute combinations. It includes observed first reaches (60/70/80/90),
stays/dips/unknowns, the 10 most observed pullbacks, 12 most observed
three-stage recoveries, and focused **80 -> 50 -> 60** and **80 -> 60 -> 70**
paths on both YES and NO. Each path includes sample count, actual settlement
win rate, historical observed ask, estimated fee and net cents per contract,
average minutes of each step, and newest-30% results. Focused recoveries also
show a four-column timeline of recovery minutes (15-11, 10-6, 5-4, 3-1 left).
Research candidates require adequate older/newer sample counts; this is
exploration rather than a proven strategy.

**COPY RESULTS** and **SHARE REPORT** now output the short summary, keeping
long raw tables out of the phone UI and clipboard. Full event analysis is
performed internally; collapsing into aggregates intentionally does not
preserve each individual market's chronological quote path in the export.
The scan still runs in its foreground service with the screen off.

Unlike the chart's custom synthetic percentage lines, the scanner queries
**real Kalshi public 1-minute YES best bid and ask closing quotes** and
**actual settled YES/NO outcomes** for KXBTC15M. It uses Kalshi's recent and
archived market/candlestick APIs and does not need a Kalshi account or API key.
It never simulates missing prices or calls synthetic lines "Kalshi odds".

It records original first reaches and observed dips, then summarizes the full path data.
Low sample sizes and results not validated on unseen data are identified.

**Limitations:** Each recorded point is a 1-minute closing quote, not the
intraminute trading path. Crossings could occur between snapshots; the actual
price available to execute, liquidity, fees and slippage can differ from the
historical closing ask. A quoted midpoint is not a calibrated probability.
No trade is placed and no profit is promised. Failed or empty API data
produces an error or a skip count, not fabricated results.
