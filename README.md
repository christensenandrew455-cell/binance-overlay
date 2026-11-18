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
