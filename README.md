# Decision Overlay

Android floating overlay that scores **BTCUSDT** (or any other Binance spot symbol) and shows one manual verdict:

- **UP** — 1-hour trend, 15-minute momentum, and strict 5-minute confirmation all agree long
- **DOWN** — the same three agree short
- **DNT** — do not trade: neutral, 50/50, conflicting, thin data, or a dead feed

This is a decision aid. It does **not** place orders, hold API keys, or connect to a Binance account. It only reads public market data.

## What you see

On launch the app asks for **Draw over other apps** (`SYSTEM_ALERT_WINDOW`). After you grant it, **Show overlay** starts a foreground service and a small draggable card:

- Big live verdict: `UP`, `DOWN`, or `DNT`
- One-line scores for 1h / 15m / 5m
- Collapsible **5-minute**, **15-minute**, and **1-hour** sections
- Each section shows raw values (price, EMA9, EMA21, RSI, MACD, histogram, candle bias, volume versus its 20-candle average), the point breakdown, and **Connected** or **Not Working**
- Collapsible **Error log** with timestamps for HTTP failures, bad payloads, rejected symbols, and host fallback
- Drag the header. Tap the header to shrink the card to the verdict. `–` does the same. `×` stops the overlay.

## Data

The environment variable that connects to Binance is **`BINANCE_API_KEY`**. That value is your Binance API key. The app sends it as the `X-MBX-APIKEY` header on `GET /api/v3/klines`. There is no websocket.

Optional: **`BINANCE_API_BASE_URL`** if you need a different `https://` host. When it is unset, the app uses `https://data-api.binance.vision` first, because `https://api.binance.com` often returns HTTP 451.

Set either variable in the environment or in `local.properties` before building. `local.properties` is not committed. The key is also kept on the phone after you enter it once.

| Feed | When it updates |
| --- | --- |
| 5-minute | Binance REST `GET /api/v3/klines` every 15 seconds |
| 15-minute | Same REST call immediately, then every 15 minutes |
| 1-hour | Same REST call immediately, then every 15 minutes |

There is no websocket and no webhook. Every request is the Binance REST API with `BINANCE_API_KEY`.

If `BINANCE_API_BASE_URL` is an `https://` host at build time, that host is tried first. Otherwise:

1. `https://data-api.binance.vision`
2. `https://api.binance.com`
3. `https://api1.binance.com`
4. `https://api2.binance.com`
5. `https://api3.binance.com`

A failed host is written into the error log even when the fallback succeeds. A timeframe that is stale or failing is **Not Working**, and the verdict is forced to **DNT**.

## Point system

Scores run from -100 to +100. Positive is long, negative is short. Volume is shown and is **not** a gate.

### 1-hour — strict trend filter (primary bias)

| Component | Long | Short | Otherwise |
| --- | --- | --- | --- |
| EMA stack | +30 if price > EMA9 > EMA21 and the EMA gap is at least 0.10% of price | -30 for the mirror | 0 |
| Structure | +25 if at least 5 of the last 7 closes are higher | -25 for the mirror | 0 |
| MACD regime | +25 if histogram > 0, MACD > signal, MACD > 0, and \|MACD\| is at least 0.08% of price | -25 for the mirror | 0 |
| RSI regime | +20 if RSI is 55..75 | -20 if RSI is 25..45 | 0 (neutral or extended) |

The hour is a usable bias only at **score ≥ +50 with a positive EMA stack**, or **≤ -50 with a negative stack**. Anything else is neutral and the verdict is DNT.

### 15-minute — generalized momentum

| Component | Long | Short | Otherwise |
| --- | --- | --- | --- |
| EMA direction | +25 when the stack is separated by at least 0.03% | -25 | 0 if the EMAs are mixed or too tight |
| RSI momentum | +25 if RSI > 52 | -25 if RSI < 48 | 0 inside 48..52 |
| MACD sign | +25 / -25 from the histogram sign, ignored when the histogram is smaller than 0.012% of price | | 0 |
| Candle net | +25 if the last 6 candle bodies sum to more than +0.15% of price | -25 under -0.15% | 0 |

Momentum confirms only at **±30**. If RSI momentum and candle net are **both** 0, that is treated as 50/50 and does not confirm, even if EMA and MACD lean the same way.

### 5-minute — strict execution

| Component | Long | Short | Otherwise |
| --- | --- | --- | --- |
| EMA stack | +30, gap at least 0.04% of price | -30 | 0 |
| Candles | +25 when at least 2 of the last 3 candles agree, the latest candle agrees, it closes beyond the prior close, and its body is at least 0.02% of price | -25 | 0 |
| RSI zone | +20 only inside 52..70 | -20 only inside 30..48 | 0 above 70, below 30, or around 50 |
| MACD expand | +25 when the histogram is positive, larger than the previous histogram, and at least 0.012% of price | -25 for the mirror | 0 if it is flat or shrinking |

Execution confirms only when the stack and the candles agree, **either** RSI zone **or** MACD expansion agrees, and the total is **±75 or more**.

### Final verdict

| Result | Rule |
| --- | --- |
| UP | 1h long bias, 15m long momentum, 5m long execution, every feed Connected |
| DOWN | the same, all short |
| DNT | anything else, including a Not Working feed or fewer than 40 candles |

## Project layout

```
app/src/main/java/com/decisionoverlay/checker/
  MainActivity.kt      permission screen and start/stop
  OverlayService.kt    foreground service, notification
  OverlayPanel.kt      floating card
  MarketEngine.kt      Binance REST polling and log
  BinanceClient.kt     public REST + kline message parser
  Indicators.kt        EMA, Wilder RSI, MACD
  Scorer.kt            point system and UP / DOWN / DNT
app/src/test/java/.../ScorerTest.kt
```

Min SDK 26. Target SDK 35. Kotlin. No account and no third-party chart library.

## Build the APK

### GitHub Actions

Push to `main`. The **Build debug APK** workflow runs the unit tests and uploads `app-debug.apk` as the `decision-overlay-debug` artifact. Open the latest Actions run and download the artifact. That APK is signed with the debug key and can be installed on a phone.

### On a computer

Install JDK 17 and Android SDK 35 (Android Studio is enough).

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or the SDK path Android Studio shows
chmod +x ./gradlew
./gradlew test assembleDebug
```

APK path:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Install

1. Copy `app-debug.apk` to the phone.
2. Allow install from that file manager if Android asks.
3. Open **Decision Overlay**.
4. Tap **Grant draw-over-apps permission** and enable it for this app.
5. Enter a symbol such as `BTCUSDT` and tap **Show overlay**.
6. Allow notifications if you want the persistent “overlay is running” notice. The overlay still runs if you deny it, but the system is more likely to stop the service.

Leave the notification in place. Swiping the app away can kill a normal activity; the foreground service is what keeps the card up. Some phone skins still force-stop apps that are not exempted from battery optimization — if the card vanishes, disable battery optimization for Decision Overlay.

## Tests

`./gradlew test` checks an impulse (UP), a selloff (DOWN), chop and flat markets (DNT), a dead 5-minute feed (DNT), and a neutral 1-hour chart blocking a strong lower timeframe (DNT).
