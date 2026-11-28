# Kalshi BTC15M API starter (demo only)

This is a **separate Python companion** to the Android overlay. It does **not**
change the installed Android APK or automatically trade real money.

## What is implemented

- Signed Kalshi V2 REST requests (Ed25519 or RSA private keys).
- Check the demo account balance and fetch an explicit BTC15M market's best
  executable YES/NO asks from the **real Kalshi order book**.
- Paper-only by default: arm when the *Kalshi contract's best ask* is at or
  below $0.50, detect >=$0.60 within 120 seconds, and log one attempted entry.
- Optional `--demo-order` mode: send **one** immediate-or-cancel (IOC) order
  to the **demo** endpoint, maximum buy cost $0.60 per contract. For YES this
  is a `bid` with a $0.60 YES price; for NO this is an `ask` with a $0.40
  YES price (economically buying NO for at most $0.60).
- JSON-lines logs store signal time, quote-fetch latency, API submission
  time, processing timestamp, fill count/price, and fees when returned.
- Never retry an order automatically after a network error (ambiguous fill).
  Never send production orders.

**Critical:** The existing Android research app's signed chart/index levels
`50` and `60` are *not* Kalshi 50/60-cent contracts. This separate smoke test
uses executable Kalshi prices. Do NOT infer the app's claimed historical chart
win rates apply to $0.60 contract buys.

## Production credential names used in Vercel

The production credential mapping uses these **exact user-selected variable names**:

- `KEY_API_ID`: the Kalshi production API Key ID.
- `KALSHI_API_KEY`: the **private PEM key content** (not the Key ID), including its BEGIN and END lines. Both actual line breaks and literal `\\n` line breaks are supported.

Store them as protected/sensitive environment variables, never in the repository,
Android APK, logs, or chat. Production authentication is available only for
read-only actions such as `--balance`; **production order submission remains
disabled** in this starter. Setting Vercel variables does not automatically run
this Python client or activate a 24/7 worker.

To check credentials locally after securely injecting those variables into the
process, use `python tools/kalshi_api.py --environment production --ticker KXBTC15M-REPLACE --balance`.
This authenticates but does not place an order.

## One-time setup (do not paste your key into chat or GitHub)

1. Create a **demo** account at https://demo.kalshi.co, then open
   Account & security > API Keys and generate an API key and its private PEM.
   Demo and production API keys are not interchangeable.
2. Install Python 3.10+ on a PC or always-on server, then from the repo root:

   ```sh
   python -m pip install -r tools/requirements-kalshi.txt
   ```

3. Store the private PEM *outside this Git repository*. Set local
   environment variables (examples for Linux/macOS):

   ```sh
   export KALSHI_DEMO_API_KEY_ID='YOUR_DEMO_KEY_ID'
   export KALSHI_DEMO_PRIVATE_KEY_FILE='/private/path/to/kalshi-demo.pem'
   ```

4. Get the exact current open BTC15M market ticker from Kalshi's public market
   listing. For example:

   ```text
   https://external-api.demo.kalshi.co/trade-api/v2/markets?series_ticker=KXBTC15M&status=open
   ```

   Demo markets might not mirror production or be available on every tick.

5. Authentication smoke test:

   ```sh
   python tools/kalshi_api.py --ticker KXBTC15M-REPLACE --balance
   ```

6. Check executable asks without placing an order:

   ```sh
   python tools/kalshi_api.py --ticker KXBTC15M-REPLACE --quote
   ```

7. Paper-watch one explicit 15-minute market, one direction at a time:

   ```sh
   python tools/kalshi_api.py --ticker KXBTC15M-REPLACE --outcome YES
   ```

8. After you inspect the paper logs, optionally test **one demo order**
   if the specified market produces the real 50->60-cent ask transition:

   ```sh
   python tools/kalshi_api.py --ticker KXBTC15M-REPLACE --outcome NO --count 1 --demo-order
   ```

The record file defaults to `~/.kalshi-btc15m/orders.jsonl` (outside Git).
This smoke test must be restarted for each new ticker; it is not yet a
24-hour automatic market discovery/execution service. Use the dedicated
REST order response's `fill_count` plus order/fill records to distinguish
accepted orders from executed fills. The demo environment uses mock funds
and its liquidity can differ from production.

## Guardrails and next integration steps

Before connecting the Android signal stream, separately verify out-of-sample
win rates using **actual Kalshi executable quote data**, with real fees and
latency. The current Android 50/60 levels are custom BTC index values.

To achieve 24/7 unattended trading safely, add: server-side live orderbook
WebSockets, automatic identification of each new BTC15M market, a durable
deduplication store, timestamped signal forwarding from the app, stale-price
guards, reconnection/recovery, a daily spend/position cap, 50% drawdown
kill switch, restart lockout, and independently verified fills. This demo
client deliberately does not enable live order submission.

Run offline tests:

```sh
python -m unittest discover -s tools -p 'test_kalshi_api.py' -v
```

Documentation verified November 27, 2026:
- https://docs.kalshi.com/getting_started/api_environments
- https://docs.kalshi.com/getting_started/quick_start_authenticated_requests
- https://docs.kalshi.com/api-reference/orders/create-order-v2
- https://docs.kalshi.com/getting_started/orderbook_responses
