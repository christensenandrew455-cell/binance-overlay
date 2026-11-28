# Kalshi Trader — permanent owner access key (Android + Vercel)

**The original Kalshi API credentials stay only in the Vercel Production environment.**

- `KALSHI_API_ID`: Kalshi API Key ID
- `KALSHI_API_KEY`: Kalshi private PEM
- `KALSHI_OWNER_ACCESS_CODE`: **permanent** separate owner access key

The owner access key is **not a single-use code**. Vercel checks it on
**every signed request** from the Android app. The Android app must know it
to prove ownership; to avoid shipping the key in a public APK, the user
enters it once after fingerprint unlock. It is encrypted in Android Keystore
and reused whenever the service connects, without prompting on each request.
The key does not expire after first use. A fresh timestamp and HMAC signature
are used for each request so the actual key is never sent in network messages.

## Setup

1. Install the latest successful APK from GitHub Releases, and verify the
   updated version number. Do not uninstall the current app first.
2. Unlock with a fingerprint. On the first opening, paste the **owner access
   key**, **not** the Kalshi private key, when the secure connection dialog
   appears. Tap SAVE once. The code is thereafter stored encrypted.
3. Tap CHECK VERCEL CONNECTION & BALANCE. Check that the live Kalshi balance
   is accurate.
4. Only after verifying live API behavior and settlement reconciliation,
   press START. This can submit real-money orders. STOP halts future orders.

There is no pairing screen, no phone public-key registration, and no
Kalshi private-key import in the APK. The phone stays powered on and connected
to the internet for the foreground monitoring service to work, although
Android or the network can still interrupt it.

## Trading schedule

Eastern time: 10 AM–12 PM and 5 PM–6 PM.
In a 15-minute market: 12, 10, 9, 8, 7 or 5 full minutes remaining.
UP/YES or DOWN/NO executable best ask moves from 50c or less to exactly
60c inside 120 seconds; higher ask prices are not chased. The account must
have at least $100 equity; starting wager is 30% of equity, fixed until the
anchor doubles, and capped at $600. A persistent 50% equity drawdown
locks trading. Orders, fills, settlement fees and P&L are logged locally.

**Important:** This is real-money order code. A green APK build and offline
unit tests do not verify a live fill or settlement. Confirm authorization,
balance, market selection, fee accounting, stop behavior, and settlement logs
before relying on unattended operation. Vercel deployments can be delayed by
the hosting plan's daily deployment quota. Do not place the owner key or
Kalshi secrets in the APK or GitHub.
