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

The report includes both YES and NO, every minute-left bucket **15 through 0**,
first hits of **60/70/80/90**, subsequent observed pullbacks to lower 10%-step
levels, and three-stage paths such as **80 -> 60 -> 70**. For each path it
reports market count, settlement wins, empirical win rate, observed entry
ask, approximate taker fee, estimated net return, and the newest-30% subset.
Minute-pair and minute-triple tables preserve when each event first happened.
It also includes every current midpoint 10%-bin for each minute. 0 minutes
is settlement, not an executable entry. Copy/share includes ALL tables.
Historical paths indicate conditional outcomes, not guaranteed profitable
fills or probability estimates.
The scan runs in its own foreground service, even with the screen off.
**COPY RESULTS** and **SHARE REPORT** include the complete raw tables.

Unlike the chart's custom synthetic percentage lines, the scanner queries
**real Kalshi public 1-minute YES best bid and ask closing quotes** and
**actual settled YES/NO outcomes** for KXBTC15M. It uses Kalshi's recent and
archived market/candlestick APIs and does not need a Kalshi account or API key.
It never simulates missing prices or calls synthetic lines "Kalshi odds".

It records original first reaches and observed dips, plus the detailed path
and minute-matrix analysis described above.
Low sample sizes and results not validated on unseen data are identified.

**Limitations:** Each recorded point is a 1-minute closing quote, not the
intraminute trading path. Crossings could occur between snapshots; the actual
price available to execute, liquidity, fees and slippage can differ from the
historical closing ask. A quoted midpoint is not a calibrated probability.
No trade is placed and no profit is promised. Failed or empty API data
produces an error or a skip count, not fabricated results.
