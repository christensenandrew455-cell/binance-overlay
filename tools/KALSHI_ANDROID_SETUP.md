# Kalshi Trader: Android background service (production)

The Android app now includes an **on-device** Kalshi trading service. It is
separate from the optional Python cloud/server runner. **Railway and Vercel are
not required to operate the phone worker.** Running an Android foreground
service requires the phone to stay powered on, connected to the internet and
allowed by Android to perform background activity. Android battery management,
network interruptions and system updates can still pause or stop execution;
uninterrupted execution is not guaranteed.

## First use

1. Install the newest GitHub Actions APK (the build must be green).
2. Launch **Kalshi Trader**. The screen stays black until your Android
   fingerprint verification succeeds.
3. Select **IMPORT PRIVATE KEY ON THIS PHONE**, choose the original Kalshi PEM
   / `.key` file from phone storage, and enter your corresponding API **Key ID**
   in the app. Keep the secret out of chat and GitHub.
4. The app encrypts the key and ID under Android Keystore with app-private
   local storage. It supports PKCS8 and PKCS1 RSA PEM, and PKCS8 Ed25519.
   The private key is not bundled in the APK or sent to Vercel.
5. Tap **CHECK CONNECTION & REFRESH BALANCE**. Verify that a genuine Kalshi
   account balance appears. If the API returns 401, verify the key ID matches
   that PEM and that the key has the necessary account permissions.
6. When balance is at least $100, tap **START** and confirm the real-money
   prompt. The **Trading active** notification stays visible while the
   foreground worker is functioning. **STOP** disables new orders.
7. On Samsung, open **Settings > Apps > Kalshi Trader > Battery** and select
   **Unrestricted**, if the option is available. Enable notifications too.
   Avoid force-stopping the app; Android may still stop services for other reasons.

The **old Vercel environment variables** `KALSHI_API_ID` and
`KALSHI_API_KEY` remain unchanged but are **not read by the Android APK**.
Vercel server environment variables cannot securely and directly be retrieved
from the phone. If the downloaded private key file is gone, create a new key
in Kalshi and import it on the phone.

## Rules that block orders

- Local time (Eastern): **10 AM–noon** and **5–6 PM**.
- A trade may execute only with **12, 10, 9, 8, 7 or 5** full minutes
  remaining in the current Kalshi BTC 15-minute market.
- The executable ask for either YES/UP or NO/DOWN must first be **50c or
  lower**, then reach **exactly 60c** within **120 seconds**. Quotes over 60c
  are skipped. Neither the Binance chart percent nor old Kalshi alert
  thresholds are used as a contract price.
- Account equity must be at least $100 and enough free cash must exist to
  cover the complete wager. The fixed wager is initially 30% of the
  START balance, increases only when the reference balance doubles, and
  never exceeds $600. Contract size reserves 10c per 60c contract for fees.
- At **50% or less of the current reference balance**, a persistent
  force-stop prevents START until the user reviews logs and explicitly resets.
- One durable order attempt per market. An ambiguous HTTP response,
  missing fill data or unknown reconciliation forces a lockout.
- The app **never submits orders automatically upon installing,
  importing a key or reopening the dashboard**: only after START.

## Logs and notifications

All session state, attempts, API order IDs, fill summaries, settlement amounts,
fees and P&L remain in app-private SQLite storage (no key material).
The screen shows Kalshi-derived balance, fixed wager, Eastern-day wins/losses,
money gained/lost bars and the latest audit entries. When settlement is
confirmed from real fills and final market result, the app sends a notification
with a signed profit or loss such as `+$12.00` or `-$30.00`.
A trade that has not settled is **not** counted as a win.

**Important validation:** This phone implementation has not yet been proven
with a production API key or filled order; a green build proves compilation,
not that real-money execution and settlement behave as intended. Verify balance,
market selection, small order execution, fill reconciliation, stop behavior
and notifications before relying on unattended trading. Historical win rates
do not guarantee future performance.

The desktop Python worker in `tools/kalshi_live_runner.py` is still present
as an optional alternative, but it is not started by the Android app and
should **never be running with this phone worker simultaneously**.
