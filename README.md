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

Tap **BACKTEST** on the BTC chart, then **RUN BACKTEST (500)**.
The scan runs in its own foreground service, even with the screen off.
**COPY RESULTS** and **SHARE REPORT** include the complete raw tables.

Unlike the chart's custom synthetic percentage lines, the scanner queries
**real Kalshi public 1-minute YES best bid and ask closing quotes** and
**actual settled YES/NO outcomes** for KXBTC15M. It uses Kalshi's recent and
archived market/candlestick APIs and does not need a Kalshi account or API key.
It never simulates missing prices or calls synthetic lines "Kalshi odds".

It analyzes each side's first observed attainment of 60%, 70%, 80%, and 90%,
then whether observed minute-close quotes stayed at/above or fell below that
line by expiration. Unknown quote gaps are counted separately. It also records
first pullbacks (e.g. 70 -> 60 and 70 -> 50), entry asks, final outcomes,
minute-left bins from 15 to 0, tentative one-contract net returns after an
approximate general Kalshi taker fee, and a newest-30% chronological holdout.
Low sample sizes and results not validated on unseen data are identified.

**Limitations:** Each recorded point is a 1-minute closing quote, not the
intraminute trading path. Crossings could occur between snapshots; the actual
price available to execute, liquidity, fees and slippage can differ from the
historical closing ask. A quoted midpoint is not a calibrated probability.
No trade is placed and no profit is promised. Failed or empty API data
produces an error or a skip count, not fabricated results.
