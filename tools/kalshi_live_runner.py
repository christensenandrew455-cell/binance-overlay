#!/usr/bin/env python3
"""Production BTC15M execution worker. Run on a PRIVATE, persistent server.
Never autostarts on a Vercel deploy or on importing the module.
"""
from __future__ import annotations
import argparse
import json
import sqlite3
import time
from datetime import datetime, timezone, timedelta
from decimal import Decimal
from pathlib import Path
from uuid import uuid4
from zoneinfo import ZoneInfo

from kalshi_api import Kalshi, credentials_from_env, best_ask
from kalshi_live_rules import FiftySixty, RiskState, contract_count, permitted_at, in_window

DEFAULT_DB = Path.home() / ".kalshi-btc15m" / "live.sqlite3"
PREFIX = "KXBTC15M-"


def now_utc():
    return datetime.now(timezone.utc)


def when(s):
    t = datetime.fromisoformat(s.replace("Z", "+00:00"))
    if t.tzinfo is None:
        raise ValueError("Naive exchange timestamp")
    return t


def database(path):
    path.parent.mkdir(parents=True,exist_ok=True)
    db = sqlite3.connect(path, timeout=15, isolation_level=None)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), active INTEGER, locked INTEGER, anchor TEXT, wager TEXT, reason TEXT)")
    db.execute("INSERT OR IGNORE INTO state VALUES (1,0,0,NULL,NULL,'NOT_STARTED')")
    db.execute("CREATE TABLE IF NOT EXISTS orders (ticker TEXT PRIMARY KEY, outcome TEXT, client_id TEXT UNIQUE, count INTEGER, status TEXT, order_id TEXT, created_utc TEXT, settled_utc TEXT, pnl TEXT, fee TEXT, paid TEXT)")
    db.execute("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at_utc TEXT, event TEXT, details TEXT)")
    return db


def log(db,event,**details):
    ts=now_utc().isoformat()
    db.execute("INSERT INTO audit(at_utc,event,details) VALUES (?,?,?)",(ts,event,json.dumps(details,default=str)))
    print(json.dumps(dict(at_utc=ts,event=event,**details),default=str),flush=True)


def state(db):
    active,locked,anchor,wager,reason=db.execute("SELECT active,locked,anchor,wager,reason FROM state WHERE id=1").fetchone()
    return dict(active=bool(active),locked=bool(locked),anchor=anchor,wager=wager,reason=reason)


def balance(k):
    raw=k.balance()
    cash=Decimal(str(raw["balance"]))/100
    equity=cash+Decimal(str(raw.get("portfolio_value",0)))/100
    if equity<0 or cash<0:
        raise ValueError("Invalid account balance")
    return equity,cash


def lock(db,reason):
    db.execute("UPDATE state SET active=0,locked=1,reason=? WHERE id=1",(reason,))
    log(db,"FORCE_STOP",reason=reason)


def start(db,k):
    s=state(db)
    if s["locked"]:
        raise RuntimeError("Locked after loss or order uncertainty; requires reviewed reset")
    equity,cash=balance(k)
    if s["anchor"] is None:
        r=RiskState.start(equity)
    else:
        r=RiskState(Decimal(s["anchor"]),Decimal(s["wager"]))
        allowed,why=r.check(equity,cash)
        if not allowed:
            raise RuntimeError(why)
    db.execute("UPDATE state SET active=1,locked=0,anchor=?,wager=?,reason='ACTIVE' WHERE id=1",(str(r.anchor),str(r.fixed_wager)))
    log(db,"START",equity=equity,stake=r.fixed_wager,anchor=r.anchor)


def risk_check(db,k):
    s=state(db)
    if not s["active"] or s["locked"] or s["anchor"] is None:
        return None,None,None
    equity,cash=balance(k)
    r=RiskState(Decimal(s["anchor"]),Decimal(s["wager"]))
    ok,why=r.check(equity,cash)
    if r.stopped:
        lock(db,why)
        return None,equity,cash
    if str(r.anchor)!=s["anchor"] or str(r.fixed_wager)!=s["wager"]:
        db.execute("UPDATE state SET anchor=?,wager=? WHERE id=1",(str(r.anchor),str(r.fixed_wager)))
        log(db,"STAKE_STEP_UP",anchor=r.anchor,fixed_wager=r.fixed_wager,drawdown_floor=r.stop_floor)
    if not ok:
        return None,equity,cash
    return r,equity,cash


def current_market(k,at):
    markets=k._request("GET","/markets",params=dict(series_ticker="KXBTC15M",status="open",limit=100)).get("markets",[])
    found=[]
    for m in markets:
        if not m.get("ticker","").startswith(PREFIX) or m.get("status") not in ("open","active"):
            continue
        close=when(m["close_time"])
        if close-timedelta(minutes=15)<=at<close:
            found.append(m)
    return found[0] if len(found)==1 else None


def order_body(ticker,outcome,count,cid):
    if not ticker.startswith(PREFIX) or outcome not in ("YES","NO") or not 1<=count<=1000:
        raise ValueError("Unsafe order arguments")
    return dict(ticker=ticker,client_order_id=cid,side="bid" if outcome=="YES" else "ask",
                count=str(count),price="0.6000" if outcome=="YES" else "0.4000",
                time_in_force="immediate_or_cancel",self_trade_prevention_type="taker_at_cross",
                post_only=False,cancel_order_on_pause=True)


def order_once(db,k,market,outcome,quote):
    ticker=market["ticker"]
    at=now_utc()
    if quote!=Decimal("0.60") or not permitted_at(at,when(market["close_time"])):
        return
    r,equity,cash=risk_check(db,k)  # Second independent balance check before sending
    if r is None or db.execute("SELECT 1 FROM orders WHERE ticker=?",(ticker,)).fetchone():
        return
    count=contract_count(min(r.fixed_wager,cash))  # 60c limit plus 10c/contract fee reserve
    if count<1: return
    cid=str(uuid4())
    # Durable idempotency claim BEFORE the network call; never auto-retry.
    db.execute("INSERT INTO orders(ticker,outcome,client_id,count,status,created_utc) VALUES(?,?,?,?,?,?)",
               (ticker,outcome,cid,count,"UNCERTAIN",at.isoformat()))
    log(db,"ORDER_SUBMIT",ticker=ticker,outcome=outcome,count=count,budget=r.fixed_wager,limit=quote,client_id=cid)
    try:
        raw=k._request("POST","/portfolio/events/orders",authenticated=True,
                       json=order_body(ticker,outcome,count,cid))
        order=raw.get("order",raw)
        oid=order.get("order_id",raw.get("order_id"))
        if not oid:
            raise ValueError("Order accepted without resolvable order id")
        db.execute("UPDATE orders SET status='AWAITING_SETTLEMENT',order_id=? WHERE ticker=?",(oid,ticker))
        log(db,"ORDER_RESPONSE",ticker=ticker,order_id=oid,
            fill_count=order.get("fill_count"),remaining_count=order.get("remaining_count"))
    except Exception as exc:
        lock(db,"ORDER_STATUS_UNCERTAIN")
        log(db,"ORDER_ERROR",ticker=ticker,error=repr(exc))


def reconcile(db,k):
    rows=db.execute("SELECT ticker,outcome,order_id FROM orders WHERE status='AWAITING_SETTLEMENT'").fetchall()
    for ticker,outcome,oid in rows:
        try:
            m=k.market(ticker)
            if m.get("result") not in ("yes","no") or m.get("status") not in ("finalized","settled","closed"):
                continue
            fills=k.fills(oid).get("fills",[])
            if not fills:
                db.execute("UPDATE orders SET status='NO_FILL' WHERE ticker=?",(ticker,))
                log(db,"NO_FILL",ticker=ticker,order_id=oid)
                continue
            quantity=cost=fee=Decimal(0)
            for f in fills:
                if f.get("outcome_side","").lower()!=outcome.lower():
                    raise ValueError("Unexpected fill outcome; cannot compute P&L")
                q=Decimal(str(f.get("count_fp",f.get("count",0))))
                px=Decimal(str(f.get("yes_price_dollars" if outcome=="YES" else "no_price_dollars",0)))
                ffee=Decimal(str(f.get("fee_cost",0)))
                if q<=0 or px<=0 or px>=1:
                    raise ValueError("Missing/invalid fill fields")
                quantity+=q
                cost+=q*px
                fee+=ffee
            payout=quantity if m["result"]==outcome.lower() else Decimal(0)
            pnl=payout-cost-fee
            db.execute("UPDATE orders SET status='SETTLED',settled_utc=?,pnl=?,fee=?,paid=? WHERE ticker=?",
                       (now_utc().isoformat(),str(pnl),str(fee),str(cost),ticker))
            log(db,"REALIZED_PNL",ticker=ticker,quantity=quantity,paid=cost,fee=fee,
                payout=payout,pnl=pnl,notification=("+" if pnl>0 else "")+str(pnl))
        except Exception as exc:
            lock(db,"RECONCILIATION_ERROR")
            log(db,"SETTLEMENT_ERROR",ticker=ticker,error=repr(exc))
            return


def daily_stats(db):
    day=now_utc().astimezone(ZoneInfo("America/New_York")).date()
    rows=db.execute("SELECT settled_utc,pnl FROM orders WHERE status='SETTLED'").fetchall()
    pnls=[Decimal(p) for ts,p in rows if when(ts).astimezone(ZoneInfo("America/New_York")).date()==day]
    return dict(wins=sum(p>0 for p in pnls),losses=sum(p<0 for p in pnls),
                net_pnl=str(sum(pnls,Decimal(0))))


def run(db,k,interval=2.0):
    trackers={}
    while True:
        tick=time.monotonic()
        try:
            reconcile(db,k)
            r,equity,cash=risk_check(db,k)
            at=now_utc()
            if r is not None and in_window(at):
                market=current_market(k,at)
                if market:
                    ticker=market["ticker"]
                    if permitted_at(at,when(market["close_time"])) and not db.execute("SELECT 1 FROM orders WHERE ticker=?",(ticker,)).fetchone():
                        pairs=trackers.setdefault(ticker,{"YES":FiftySixty(),"NO":FiftySixty()})
                        book=k.orderbook(ticker)
                        for outcome in ("YES","NO"):
                            ask=best_ask(book,outcome)
                            action=pairs[outcome].observe(ask,time.monotonic())
                            if action:
                                log(db,"SIGNAL_"+action,ticker=ticker,outcome=outcome,ask=ask)
                            if action=="BUY":
                                order_once(db,k,market,outcome,ask)
                                break
                    else:
                        trackers.pop(ticker,None)
                if len(trackers)>2: trackers.clear()
        except Exception as exc:
            trackers.clear()  # No trading on stale feed or ambiguous API responses.
            log(db,"WORKER_ERROR",error=repr(exc))
        time.sleep(max(.5,interval-(time.monotonic()-tick)))


def main():
    p=argparse.ArgumentParser(description="Kalshi production risk-gated trading worker")
    p.add_argument("--db",type=Path,default=DEFAULT_DB)
    action=p.add_mutually_exclusive_group(required=True)
    for command in ("start","stop","run","status","reset-after-review"):
        action.add_argument("--"+command,action="store_true")
    args=p.parse_args()
    db=database(args.db)
    if args.status:
        print(json.dumps(dict(state=state(db),today=daily_stats(db)),indent=2))
        return
    if args.stop:
        db.execute("UPDATE state SET active=0,reason='USER_STOP' WHERE id=1")
        log(db,"USER_STOP")
        return
    if args.reset_after_review:
        db.execute("UPDATE state SET active=0,locked=0,anchor=NULL,wager=NULL,reason='REVIEWED_RESET' WHERE id=1")
        log(db,"MANUAL_RESET_AFTER_REVIEW")
        return
    keyid,pem,keyfile=credentials_from_env("production")
    if not keyid or not pem:
        raise RuntimeError("Production env KEY_API_ID and KALSHI_API_KEY required on the private worker")
    k=Kalshi("production",api_key_id=keyid,private_key_pem=pem,private_key_file=keyfile)
    if args.start: start(db,k)
    else: run(db,k)


if __name__=="__main__": main()
