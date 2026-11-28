#!/usr/bin/env python3
"""Kalshi V2 demo trading smoke test for BTC 15-minute contracts.

Production execution is intentionally DISABLED. Never bundle API keys in the APK.
The Android signed chart index 50/60 is NOT a Kalshi 50c/60c contract price.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import time
from typing import Any
from uuid import uuid4

import requests

BASE_URLS = {
    "demo": "https://external-api.demo.kalshi.co/trade-api/v2",
    "production": "https://external-api.kalshi.com/trade-api/v2",
}
CENT = Decimal("0.01")
ONE = Decimal("1.00")
START = Decimal("0.50")
FINISH = Decimal("0.60")
MAX_WINDOW_SECONDS = 120


def format_price(value: Decimal) -> str:
    return str(value.quantize(Decimal("0.0001")))


def best_ask(orderbook: dict[str, Any], outcome: str) -> Decimal | None:
    """Kalshi publishes bids on both outcome sides; asks are complementary."""
    opposite = "no_dollars" if outcome == "YES" else "yes_dollars"
    levels = orderbook["orderbook_fp"].get(opposite, [])
    return ONE - max(Decimal(row[0]) for row in levels) if levels else None


def make_order(ticker: str, outcome: str, max_price: Decimal, count: int, order_id: str | None = None) -> dict[str, Any]:
    """V2 quotes the YES leg: buy NO is an ask on YES at 1 - NO limit."""
    if not ticker.startswith("KXBTC15M-"):
        raise ValueError("Only explicitly selected KXBTC15M tickers are supported")
    if outcome not in ("YES", "NO") or not (Decimal("0.01") <= max_price <= FINISH):
        raise ValueError("Outcome must be YES/NO; maximum entry price must be 1-60 cents")
    if not 1 <= count <= 10:
        raise ValueError("Demo contract count must be 1-10")
    yes_book_price = max_price if outcome == "YES" else ONE - max_price
    return {
        "ticker": ticker,
        "side": "bid" if outcome == "YES" else "ask",
        "count": str(count),
        "price": format_price(yes_book_price),
        "time_in_force": "immediate_or_cancel",
        "self_trade_prevention_type": "taker_at_cross",
        "post_only": False,
        "cancel_order_on_pause": True,
        "client_order_id": order_id or str(uuid4()),
    }


class FiftyToSixty:
    """Observe an executable Kalshi best ask <=50c followed by >=60c within 2m."""
    def __init__(self) -> None:
        self.anchor: float | None = None
        self.triggered = False

    def observe(self, price: Decimal | None, now: float) -> str | None:
        if self.triggered:
            return None
        if price is None or not (Decimal("0") < price < ONE):
            self.anchor = None
            return None
        if self.anchor is not None and now - self.anchor > MAX_WINDOW_SECONDS:
            self.anchor = None
        if price <= START:
            if self.anchor is None:
                self.anchor = now
                return "armed"
            return None
        if self.anchor is not None and price >= FINISH:
            self.triggered = True
            return "trigger"
        return None


def credentials_from_env(environment: str) -> tuple[str, str, str]:
    """Return (key ID, inline PEM, PEM file path) without exposing credential values."""
    if environment == "production":
        return os.getenv("KEY_API_ID", ""), os.getenv("KALSHI_API_KEY", ""), ""
    if environment == "demo":
        return os.getenv("KALSHI_DEMO_API_KEY_ID", ""), "", os.getenv("KALSHI_DEMO_PRIVATE_KEY_FILE", "")
    raise ValueError("Unknown Kalshi environment")


class Kalshi:
    def __init__(self, environment: str, api_key_id: str = "",
                 private_key_file: str = "", private_key_pem: str = ""):
        self.environment = environment
        self.base_url = BASE_URLS[environment]
        self.api_key_id = api_key_id
        self.private_key_file = private_key_file
        self.private_key_pem = private_key_pem
        self.session = requests.Session()
        self._private_key: Any = None

    def _auth_headers(self, method: str, path: str) -> dict[str, str]:
        if not self.api_key_id or not (self.private_key_file or self.private_key_pem):
            needed = ("KEY_API_ID and KALSHI_API_KEY" if self.environment == "production"
                      else "KALSHI_DEMO_API_KEY_ID and KALSHI_DEMO_PRIVATE_KEY_FILE")
            raise ValueError("Missing Kalshi credentials: set " + needed)
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding, rsa, ed25519

        if self._private_key is None:
            pem_bytes = (self.private_key_pem.replace("\\n", "\n").encode("utf-8")
                         if self.private_key_pem else Path(self.private_key_file).read_bytes())
            self._private_key = serialization.load_pem_private_key(pem_bytes, password=None)
        stamp = str(int(time.time() * 1000))
        payload = (stamp + method.upper() + "/trade-api/v2" + path.split("?")[0]).encode()
        if isinstance(self._private_key, ed25519.Ed25519PrivateKey):
            signed = self._private_key.sign(payload)
        elif isinstance(self._private_key, rsa.RSAPrivateKey):
            signed = self._private_key.sign(
                payload, padding.PSS(mgf=padding.MGF1(hashes.SHA256()),
                                     salt_length=padding.PSS.DIGEST_LENGTH), hashes.SHA256()
            )
        else:
            raise TypeError("Unsupported Kalshi private key")
        return {
            "KALSHI-ACCESS-KEY": self.api_key_id,
            "KALSHI-ACCESS-TIMESTAMP": stamp,
            "KALSHI-ACCESS-SIGNATURE": base64.b64encode(signed).decode(),
        }

    def _request(self, method: str, path: str, *, authenticated: bool = False, **kwargs: Any) -> dict[str, Any]:
        headers = self._auth_headers(method, path) if authenticated else {}
        response = self.session.request(
            method, self.base_url + path, headers=headers, timeout=8, **kwargs
        )
        response.raise_for_status()
        return response.json()

    def market(self, ticker: str) -> dict[str, Any]:
        return self._request("GET", "/markets/" + ticker)["market"]

    def orderbook(self, ticker: str) -> dict[str, Any]:
        return self._request("GET", "/markets/" + ticker + "/orderbook")

    def balance(self) -> dict[str, Any]:
        return self._request("GET", "/portfolio/balance", authenticated=True)

    def fills(self, order_id: str) -> dict[str, Any]:
        return self._request("GET", "/portfolio/fills", authenticated=True, params={"order_id": order_id})

    def submit_demo_order(self, order: dict[str, Any]) -> dict[str, Any]:
        if self.environment != "demo":
            raise PermissionError("Production order submission is disabled in this starter")
        return self._request("POST", "/portfolio/events/orders",
                             authenticated=True, json=order)


def record(path: Path, event: str, **data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {"at_utc": datetime.now(timezone.utc).isoformat(), "event": event, **data}
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(row, default=str) + "\n")
    print(json.dumps(row, default=str))


def watch(k: Kalshi, ticker: str, outcome: str, count: int,
          demo_order: bool, poll_seconds: float, log: Path) -> None:
    market = k.market(ticker)
    if ticker.startswith("KXBTC15M-") is False or market.get("status") != "open":
        raise ValueError("Select an OPEN KXBTC15M market ticker")
    if demo_order:
        balance = k.balance()
        record(log, "demo_authenticated", balance_cents=balance.get("balance"))
    detector = FiftyToSixty()
    record(log, "watch_started", ticker=ticker, outcome=outcome, mode="DEMO_ORDER" if demo_order else "PAPER",
           note="Only contract best ASK prices, never chart index levels")
    while True:
        started = time.monotonic()
        if k.market(ticker).get("status") != "open":
            record(log, "market_closed", ticker=ticker)
            return
        try:
            request_at = time.time()
            quote = best_ask(k.orderbook(ticker), outcome)
            observation_lag_ms = round((time.time() - request_at) * 1000)
            action = detector.observe(quote, time.monotonic())
            if action:
                record(log, action, ticker=ticker, outcome=outcome, ask_dollars=quote,
                       quote_http_ms=observation_lag_ms)
            if action == "trigger":
                if quote is None or quote > FINISH:
                    record(log, "skipped_price_over_limit", ask_dollars=quote, max_dollars=FINISH)
                    return
                order = make_order(ticker, outcome, FINISH, count)
                if not demo_order:
                    record(log, "paper_order", client_order_id=order["client_order_id"],
                           request=order, estimated_ask=quote)
                    return
                sent = time.time()
                try:
                    result = k.submit_demo_order(order)
                except Exception as error:
                    record(log, "order_submission_unknown_or_failed",
                           client_order_id=order["client_order_id"], detail=str(error))
                    # Never automatically retry a potentially accepted order.
                    return
                record(log, "demo_order_response", ticker=ticker, outcome=outcome,
                       client_order_id=order["client_order_id"],
                       sent_at_utc=datetime.fromtimestamp(sent, timezone.utc).isoformat(),
                       response_latency_ms=round((time.time() - sent) * 1000),
                       server_ts_ms=result.get("ts_ms"), order_id=result.get("order_id"),
                       fill_count=result.get("fill_count"), remaining_count=result.get("remaining_count"),
                       avg_yes_price=result.get("average_fill_price"), average_fee=result.get("average_fee_paid"))
                # The API's fill_count, not HTTP 201, confirms an immediate execution.
                try:
                    fills = k.fills(result["order_id"]).get("fills", [])
                    record(log, "demo_fills", order_id=result["order_id"],
                           fills=[{"fill_id": f.get("fill_id"), "count": f.get("count_fp"),
                                   "outcome": f.get("outcome_side"), "yes_price": f.get("yes_price_dollars"),
                                   "no_price": f.get("no_price_dollars"), "fee": f.get("fee_cost"),
                                   "created_time": f.get("created_time")} for f in fills])
                except Exception as error:
                    record(log, "fills_not_yet_available", detail=str(error))
                return
        except requests.RequestException as error:
            record(log, "market_data_error", detail=str(error))
            detector.anchor = None  # Never trade on stale quotes.
        time.sleep(max(0.0, poll_seconds - (time.monotonic() - started)))


def main() -> None:
    p = argparse.ArgumentParser(description="Kalshi BTC15M paper/demo API smoke test")
    p.add_argument("--environment", choices=BASE_URLS, default="demo")
    p.add_argument("--ticker", required=True, help="Exact OPEN KXBTC15M market ticker")
    p.add_argument("--outcome", choices=["YES", "NO"], default="YES")
    p.add_argument("--balance", action="store_true", help="Test Kalshi API authentication, no orders")
    p.add_argument("--quote", action="store_true", help="Print current best executable contract asks")
    p.add_argument("--demo-order", action="store_true", help="Allow ONE demo IOC order on a 50->60 signal")
    p.add_argument("--count", type=int, default=1, help="1-10 demo contracts")
    p.add_argument("--poll-seconds", type=float, default=2.0)
    p.add_argument("--log", type=Path, default=Path.home() / ".kalshi-btc15m" / "orders.jsonl")
    args = p.parse_args()
    if args.demo_order and args.environment != "demo":
        p.error("Production orders are disabled; use demo environment")
    if not (1 <= args.count <= 10) or args.poll_seconds < 1:
        p.error("count must be 1..10 and polling interval >=1 sec")
    key_id, key_pem, key_file = credentials_from_env(args.environment)
    k = Kalshi(args.environment, api_key_id=key_id,
               private_key_file=key_file, private_key_pem=key_pem)
    if args.balance:
        print(json.dumps(k.balance(), indent=2))
    elif args.quote:
        m = k.market(args.ticker)
        ob = k.orderbook(args.ticker)
        print(json.dumps({"ticker": args.ticker, "market_status": m.get("status"),
                          "yes_ask": str(best_ask(ob, "YES")),
                          "no_ask": str(best_ask(ob, "NO"))}, indent=2))
    else:
        watch(k, args.ticker, args.outcome, args.count,
              args.demo_order, args.poll_seconds, args.log)


if __name__ == "__main__":
    main()
