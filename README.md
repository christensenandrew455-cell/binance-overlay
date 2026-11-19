# BTC 15-minute Kalshi pattern validation

The Android **BACKTEST** screen now runs the independent **1,000-market
validation** for the **same 27 ordered price-band paths** discovered in the
separate 100-market Nov 17-19, 2026 sample.

Tap **BACKTEST** then **RUN 1000-MARKET VALIDATION**. The foreground service
retrieves up to 1,000 *distinct settled* KXBTC15M markets, between 10 AM and
11 PM Eastern, strictly **before Nov 17, 2026**. This cutoff prevents the
100-market discovery period from leaking into the validation results. It
does not retroactively alter the 27 fixed pattern definitions.

Each pattern is matched on historical one-minute Kalshi YES bid/ask OHLC
averages (midpoints). Price labels represent ten-point bands:
40 means 40.0%-49.999...%, 50 means 50.0%-59.999...%.
All stages must occur in **later completed one-minute observations**;
other bands may occur between stages. Count only the FIRST full completion
for each rule and market. A full completion at M01-M03 (first 3 minutes
elapsed) is excluded; M04-M14 are eligible, meaning **11-1 minutes left**.
M15 at settlement is not an eligible entry. Missing candles are never
interpolated. Markets must have at least two usable minute observations.

**Full CSV report for all 27 paths:** hit count, market hit rate, settlement
win count, buy-UP/buy-DOWN win rate, most common 11-9 / 8-5 / 4-1 minutes
left window, and the highest win-rate window with at least 10 hits.
A separate section flags rules with strictly more than 10% hit rate; no
low-frequency rule is silently removed from the full report. It also
prints all 27 by each of the three windows to allow comparing timing.
The timeframe metrics are retrospective and not prospective guarantees.

A scan that collects fewer than 1,000 eligible markets is labeled
**INCOMPLETE**, never reported as a finished 1,000-market test. Press
**COPY FULL** or **SHARE REPORT** to send the output. Data collection runs
in a foreground service while the phone screen is off. Historical candles
are not guaranteed executable entry prices. No trading or buying occurs.

Existing live alerts are separate and unchanged. The older 100-market
discovery collector and its tests remain in source for auditability.

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
