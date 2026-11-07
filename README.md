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

## What the floating panel shows

- Large overall **UP / DOWN / DNT** verdict.
- Quick 1h / 15m / 5m direction and point summaries.
- Expandable **5 MINUTE CHART**, **15 MINUTE CHART**, and **1 HOUR CHART** sections.
- Each timeframe shows its own independent chart direction, score, connection state, last update, candle count, price, EMA9, EMA21, RSI, MACD, candle bias, volume ratio, and component-by-component point explanation.
- Expandable **PROBLEMS** section describing every reason the overall result is DNT.
- Expandable **CONNECTION / ERROR LOG** with request failures and host fallback details.
- Draggable floating panel, minimize control, pair selector, and manual refresh button.

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
| 5-minute candles | Initial load, then every 15 seconds |
| 15-minute candles | Initial load, then every 15 minutes |
| 1-hour candles | Initial load, then every 15 minutes |

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

1. Install and open **Binance Decision Checker**.
2. On first launch, accept the prompt to open Android's **Display over other apps** settings and enable it for this app.
3. Enter a Binance.US spot pair such as `BTCUSDT` and tap **Start floating checker**.
4. Android may ask for notification permission. This allows the foreground-service notification to remain visible while the overlay is running.
5. The floating panel appears over other apps. Drag its top bar to move it, tap the timeframe rows to expand them, and use **×** to stop it.

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
