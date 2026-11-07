# Binance Decision Checker

A native Android floating overlay that reads **Binance.US spot-market candles** and displays one overall manual verdict:

- **UP** — the short-horizon 5-minute trigger is bullish, optionally strengthened by 15-minute structure and the 1-hour filter.
- **DOWN** — the short-horizon 5-minute trigger is bearish, optionally strengthened by 15-minute structure and the 1-hour filter.
- **DNT** — do not trade: no fresh 5-minute trigger, neutral score, insufficient, stale, or disconnected data.

The app never places orders, connects to a wallet, or needs access to a Binance account. It is a manual decision aid, not financial advice.

## Download the Android APK

**Direct download:** [Download the latest app-debug.apk](https://github.com/christensenandrew455-cell/binance-overlay/releases/latest/download/app-debug.apk)

Every push to `main` runs the tests, builds the Android APK, and publishes it as a GitHub Release asset. The APK is also attached as an Actions artifact.

On a phone, open the direct download link above, download `app-debug.apk`, allow your browser or file manager to install unknown apps when Android asks, and install it.

## What the app shows

- Small floating UP / DOWN / DNT decision badge.
- Main app with collapsible 5-minute, 15-minute, and 1-hour chart breakdowns.
- Each timeframe shows its weighted points, Binance connection state, last fetch time, and the indicators used for that timeframe.
- Combined score from -10 to +10 and an adjustable DNT threshold from ±2 to ±6.
- Problems/blockers and connection/error logs.

## Scoring rules

The combined score ranges from -10 to +10. The 5-minute chart is the primary trade driver; the longer timeframes can confirm or filter it but cannot outweigh it.

| Timeframe | Bullish | Bearish | Role |
| --- | ---: | ---: | --- |
| 5-minute: price vs EMA8 plus a fresh MACD crossover | +6 | -6 | Primary trigger |
| 15-minute: recent higher highs and higher lows, or lower highs and lower lows, aligned with 5m | +3 | -3 | Confirmation |
| 1-hour: clear trend supports or actively opposes the 5m direction | +1 | -1 | Weak filter |
| 1-hour: flat/ranging or mixed | 0 | 0 | Neutral |

The 5-minute crossover must be current or up to three completed 5-minute candles old. A stale crossover, price on the wrong side of EMA8, or no crossover means the 5-minute trigger scores 0. The 15-minute and 1-hour components are evaluated relative to the 5-minute direction; when the 5-minute direction is neutral, they add 0.

Default decision threshold is ±3:
- Score ≥ threshold: **UP**
- Score ≤ negative threshold: **DOWN**
- Score between the thresholds: **DNT**

The in-app slider adjusts the threshold from ±2 to ±6 and updates the floating service while it is running. Missing, insufficient, stale, or disconnected candle data always forces DNT regardless of the numeric score.

## Binance.US connection — no API key

The app uses Binance.US public market data:

- `https://api.binance.us`
- `https://api1.binance.us`
- `https://api2.binance.us`

It calls the public `GET /api/v3/klines` endpoint. **No Binance API key, Vercel environment variable, Gemini key, or trading permission is needed.** Do not paste an account key into the app.

Official documentation: https://docs.binance.us/

## Refresh schedule

| Feed | Refresh |
| --- | --- |
| 5-minute candles | Initial load, then after each 5-minute candle closes |
| 15-minute candles | Initial load, then after each 15-minute candle closes |
| 1-hour candles | Initial load, then after each 1-hour candle closes |

This version uses REST polling, not a WebSocket. The 5-minute chart is refreshed more often to make the execution check responsive. Each request loads up to 150 candles.

## Timeframe horizons

- **5-minute (±6):** the trade driver. It checks price against EMA8 and requires a fresh MACD crossover that is current or up to three completed candles old. This is a short execution window, not a multi-hour trend forecast.
- **15-minute (±3):** confirms the 5-minute direction using recent higher highs/higher lows or lower highs/lower lows.
- **1-hour (±1):** a weak filter only. A clear opposing hourly trend subtracts one point; a supportive trend adds one; a flat/ranging hour adds zero.

The feeds refresh at each candle close. The 5-minute signal is recalculated on each completed 5-minute candle, so its crossover freshness naturally expires after three candles.
 
## Overall verdict

UP or DOWN is driven primarily by the fresh 5-minute trigger; the 15-minute structure can add confirmation or leave it neutral, and the 1-hour chart can add or subtract only one point. Every feed must be connected and current. This is a rule-based indicator, not a guarantee of future price movement or profitable trades. This is a rule-based indicator, not a guarantee of future price movement or profitable trades.

## Build the APK yourself

Requirements:

- Android Studio with Android SDK Platform 35
- JDK 17

From the project folder:

```bash
chmod +x ./gradlew
./gradlew testDebugUnitTest assembleDebug
```

APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Install and start

1. Install and open **Trading**.
2. Enter a Binance.US spot pair such as `BTCUSDT` and tap **START CHECKING**. This fetches the three candle sets immediately and starts checking each timeframe at its next candle close.
3. Expand any timeframe row to inspect points, indicators, Binance connectivity, and the last fetch time.
4. Adjust the **DNT THRESHOLD** slider from ±2 to ±6; the setting is saved and applies to the floating service too.
5. Tap **SHOW FLOATING DECISION** to display the compact overlay. Its × button hides only the floating badge; it does not stop the service. Use the app's stop control to stop the floating service.

If Android or your phone manufacturer stops the service in the background, allow the app to run without battery optimization.

## Project layout

```
app/src/main/java/com/decisionoverlay/checker/
  MainActivity.kt      permission screen and start/stop controls
  OverlayService.kt    foreground service
  OverlayPanel.kt      floating panel and expandable sections
  BinanceClient.kt     Binance.US public REST client
  MarketEngine.kt      polling, connection state, and error log
  Indicators.kt        EMA, Wilder RSI, MACD
  Scorer.kt            per-timeframe scoring and overall UP/DOWN/DNT
  Models.kt            candles, metrics, scores, and decision data
```
