# Production Kalshi BTC 15-minute runner — current state

**Do not confuse committed code with an active deployment.** Live orders are
possible through `tools/kalshi_live_runner.py`, but no trade is submitted
unless a private, persistent Python process runs and its session is explicitly
started. The Android START/STOP controls are **disabled** until authenticated
phone-to-worker control and real balance/settlement sync are installed.

## Exact hard-coded entry windows

- Eastern (America/New_York): **10:00:00–11:59:59 AM** and
  **5:00:00–5:59:59 PM** (DST handled automatically).
- 15-minute market countdown: **12, 10, 9, 8, 7, or 5 full minutes left**
  (integer minute displayed in MM:SS, e.g. 10:59 counts as minute 10).
- Either YES/UP or NO/DOWN **executable best ask** must move from
  **50c or less to exactly 60c** within **120 seconds**. A jump over 60c is
  logged as missed and never chased. At most one order per market.
- Fresh quotes are required. When discovery is ambiguous or the API fails,
  the bot does not place orders.

## Hard risk rules

- Starting account equity must be **at least $100**.
- Initial wager is **30% of starting equity**. It is fixed through interim
  profits. It only increases when equity reaches **2x the current anchor**;
  it increases stepwise for additional doublings.
- The wager never exceeds **$600**. Order count uses a 60c ceiling plus a
  conservative 10c per-contract fee reserve and rounds down.
- Each entry rechecks authenticated equity and available cash.
- Under $100: no new orders. At **50% or less** of the current doubled
  anchor: hard-stop and lock the session. A new START cannot override that
  lock without an explicit **manual reviewed reset**.
- An ambiguous order response causes a lockout, not a blind retry.
- All events and attempts persist in SQLite. An order ID is generated before
  submitting; duplicate markets are rejected at the database level.
- Winners, losers, and plus/minus P&L are counted **only after actual fills and
  a finalized market result**, never from guessed settlement or screenshots.

## Credentials

On the private worker process use exact environment names already selected:

- `KEY_API_ID`: production Kalshi API key ID
- `KALSHI_API_KEY`: private PEM (full BEGIN/END block)

Never store these in GitHub, the APK, or a public Vercel function response.
The program reads environment variables; **Vercel variables are not visible
to a separate always-on worker automatically**.

## Running on an always-on private host

Install Python 3.11+ with dependencies
`python -m pip install -r tools/requirements-kalshi.txt`.
Place a writable persistent SQLite directory outside Git. Use the same DB for
all commands (default `~/.kalshi-btc15m/live.sqlite3`).

```sh
# Start the always-on process first, under a process supervisor:
python tools/kalshi_live_runner.py --run

# In another shell, after checking credentials/balance:
python tools/kalshi_live_runner.py --start

# Stop new orders, without discarding settled-trade logs:
python tools/kalshi_live_runner.py --stop

# Status and verified Eastern-day P&L counts:
python tools/kalshi_live_runner.py --status
```

Only after investigating a drawdown or uncertain order:
`python tools/kalshi_live_runner.py --reset-after-review`.
Reset clears the stake anchor but does not erase trade/audit records.
Run **only one process** for a given account and database.

## Not yet connected — do not claim to be live

1. No always-on private host/deployment is configured or verified. A Vercel
   serverless function is **not** a substitute for a continuous worker.
2. No authenticated phone-to-worker API exists. Android's native biometric
   lock protects local UI only, not remote account access. Its START/STOP
   controls are intentionally disabled until properly paired.
3. No private push notification channel is configured. P&L events are
   journaled, with notification-ready + / − values, but not pushed to Android.
4. No actual Kalshi account authentication/order-fill smoke test has been
   run from a private server in this conversation.
5. Do not use the historical backtest win rate to infer guaranteed
   profitable 60c contract fills. Actual quotes, fees, fills, and drift matter.

The repository's public `index.html` is blank, but privacy must come from
server-side authorization and secret isolation, **not** that black screen.

## Tests

```sh
python -m unittest discover -s tools -p 'test_kalshi*.py' -v
```
