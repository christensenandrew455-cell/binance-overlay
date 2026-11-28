# Kalshi Trader: Android foreground service + Vercel credential bridge

**The Kalshi private key stays exclusively in the existing Vercel Production
environment variables. The APK does not request, store or bundle that key.**

## Credential architecture

The `binance-overlay` Vercel project already contains the sensitive variables:
- `KALSHI_API_ID` (Kalshi API Key ID)
- `KALSHI_API_KEY` (private PEM)

The production Vercel function `api/kalshi.js` signs Kalshi API requests
server-side. The Android app sends only device-signed HTTPS requests to
`https://binance-overlay.vercel.app/api/kalshi`. The device has its own
RSA key pair generated in Android Keystore; its private key never leaves
the phone, and it is **not** the Kalshi private key.

The server will return `PHONE_NOT_PAIRED` until the **public** half of that
phone key is registered in a third Production Vercel variable:
`KALSHI_DEVICE_PUBLIC_KEY`. This is not a secret and is safe to send in chat.

## One-time pairing

1. Install the newest successful Android APK built from `main`.
2. Open the app and unlock it using your fingerprint.
3. Tap **PAIR PHONE WITH VERCEL** → **COPY PUBLIC KEY**.
4. Send the public key string to ChatGPT so it can register the value in
   Vercel `KALSHI_DEVICE_PUBLIC_KEY`. Never send the Kalshi private key.
5. Redeploy Production after registration so the function reads the new env.
6. Tap **CHECK VERCEL CONNECTION & BALANCE**. The real Kalshi balance must
   appear before START is enabled.
7. Confirm no HTML/SSO interstitial blocks the production endpoint. Vercel
   deployment protection currently may be active; configure access so the
   phone's signed requests reach the function, and the function rejects
   everyone else. The blank landing page is NOT authentication.
8. Only after verifying correct balances, market data and settlement behavior,
   use **START** to initiate real-money orders.

If the app is installed on a different phone or Android Keystore key is reset,
register the new public key. Old devices then lose access on next deployment.

## Background operation

The Android foreground service can continue monitoring and trading when the
app is closed and the phone screen is locked. The phone must stay powered on,
online, with notification permission granted and unrestricted battery usage.
Android or network conditions may still interrupt service. Vercel executes
individual API requests; no always-on Railway or Vercel polling worker is
required in this setup.

## Trade guardrails

- Eastern trading windows 10 AM–12 PM and 5–6 PM.
- Qualifying fifteen-minute countdown minutes: 12, 10, 9, 8, 7, 5.
- YES or NO executable ask moves from ≤50c to exactly 60c in ≤120s.
- $100 minimum equity. Fixed wager equals 30% of initial session equity,
  stepping up on account balance doubling and capped at $600.
- Persistent 50% drawdown lock versus the current step anchor.
- One order attempt per market, log before submission, no blind retry.
- Vercel independently rejects orders outside the trading hours, over 60c
  or over its account-based maximum, and uses a deterministic order ID
  to reduce duplicate submission risk.
- Per-trade records and audit log persist in local SQLite.
- Notifications display realized positive/negative settlement P&L when
  an actual settlement and all fills are verified.

## Remaining practical validation

This is production trade code. **A passing compilation or unit test does not
prove that the live account can place or settle an order.** Validate a signed
balance request, orderbook, order placement, fill reconciliation, auth failures,
and the ability to stop during network errors before relying on unattended live
trading. Live results can differ from Binance-candle backtests.

The older Python worker remains separate; do not run it simultaneously with
the Android phone worker on one Kalshi account.
