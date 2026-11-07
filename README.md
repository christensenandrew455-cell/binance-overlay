# Binance Decision Checker

A native Android floating overlay that reads **Binance.US spot-market candles** and displays one overall manual verdict:

- **UP** — the 1-hour trend, 15-minute momentum, and strict 5-minute confirmation agree upward.
- **DOWN** — all three agree downward.
- **DNT** — do not trade: neutral, weak, conflicting, insufficient, stale, or disconnected data.

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

The total score is the sum of all three timeframe scores, bounded from -10 to +10.

| Timeframe / component | Bullish | Bearish |
| --- | ---: | ---: |
| 1-hour: price above/below EMA20 and RSI above/below 50 | +4 | -4 |
| 15-minute: MACD line above/below signal | +2 | -2 |
| 15-minute: volume above its previous 20-candle average, confirming MACD direction | +2 | -2 |
| 5-minute: EMA8 above/below EMA21 | +2 | -2 |

The 1-hour trend receives 0 points when price is within 0.05% of EMA20 or RSI is between 45 and 55, or when price and RSI do not agree. The 15-minute volume bonus is awarded only when volume exceeds the previous 20-candle average and MACD has a direction; volume alone does not create a direction.

Default decision threshold is ±4:
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

## Scoring rules

Scores range from -100 to +100. Positive is bullish; negative is bearish. Volume is displayed as context and does not add points.

### 1-hour — primary trend filter

| Component | Bullish | Bearish |
| --- | ---: | ---: |
| EMA stack | +30 | -30 |
| Recent close structure | +25 | -25 |
| MACD regime | +25 | -25 |
| RSI regime | +20 | -20 |

The 1-hour chart calls UP only at +50 or higher with a bullish EMA stack, or DOWN at -50 or lower with a bearish EMA stack.

### 15-minute — momentum check

| Component | Bullish | Bearish |
| --- | ---: | ---: |
| EMA direction | +25 | -25 |
| RSI momentum | +25 | -25 |
| MACD sign | +25 | -25 |
| Six-candle net | +25 | -25 |

Momentum must reach at least +30 or -30. When both RSI momentum and candle net are neutral, the 15-minute chart remains DNT even if other components lean one way.

### 5-minute — strict execution check

| Component | Bullish | Bearish |
| --- | ---: | ---: |
| EMA stack | +30 | -30 |
| Last three candles | +25 | -25 |
| RSI zone | +20 | -20 |
| Expanding MACD histogram | +25 | -25 |

Execution requires the EMA stack and candle confirmation to agree, plus either an RSI-zone or expanding-MACD confirmation. The total must reach at least +75 or -75.

### Overall verdict

UP or DOWN is shown only when all three timeframe checks agree in the same direction and every feed is connected and current. Anything else is DNT. This is a rule-based indicator, not a guarantee of future price movement or profitable trades.

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
