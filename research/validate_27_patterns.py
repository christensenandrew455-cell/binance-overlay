#!/usr/bin/env python3
"""Validate the 27 Nov-17-19 discovery patterns on 1000 EARLIER settled BTC markets.
Uses only historical Kalshi YES OHLC bid/ask, not synthetic quotes. Python 3.11+.
No orders, no simulated fills. Writes readable and CSV reports to research/out/.
"""
import csv
import datetime as dt
import json
import pathlib
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from zoneinfo import ZoneInfo

BASE = "https://external-api.kalshi.com/trade-api/v2"
SERIES = "KXBTC15M"
EAST = ZoneInfo("America/New_York")
CUTOFF = dt.datetime(2026, 11, 17, tzinfo=EAST).timestamp()
TARGET = 1000
PATHS = [
 ((40,50),"DOWN"), ((30,40),"UP"), ((60,50),"DOWN"),
 ((50,40,50),"DOWN"), ((40,30,40),"UP"), ((50,60,50),"DOWN"),
 ((40,60,50),"DOWN"), ((70,60),"DOWN"), ((20,40),"UP"),
 ((80,60),"DOWN"), ((70,50),"DOWN"), ((40,20,40),"UP"),
 ((50,30,40),"UP"), ((30,20,40),"UP"), ((30,50,40),"UP"),
 ((10,50),"DOWN"), ((30,20,50),"DOWN"), ((40,20,50),"DOWN"),
 ((50,20,40),"UP"), ((30,10,50),"DOWN"), ((40,10,50),"DOWN"),
 ((50,70,50),"DOWN"), ((60,70,50),"DOWN"), ((10,40),"UP"),
 ((20,10,50),"DOWN"), ((20,30,50),"DOWN"), ((50,70,60),"DOWN")]
WINDOWS = [("11-9",9,11), ("8-5",5,8), ("4-1",1,4)]

def get_json(endpoint, params):
    url = BASE + "/" + endpoint + "?" + urllib.parse.urlencode(params)
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={
                "Accept":"application/json", "User-Agent":"BTCResearchValidation/1.0"})
            with urllib.request.urlopen(req, timeout=35) as r:
                return json.load(r)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as ex:
            if attempt == 4:
                raise RuntimeError(str(ex))
            time.sleep(min(12, 1.5 * (attempt+1)**2))
    raise RuntimeError("unreachable")

def is_session(close_ts):
    end = dt.datetime.fromtimestamp(close_ts,EAST)
    start = dt.datetime.fromtimestamp(close_ts-900,EAST)
    return (start.date()==end.date() and
            dt.time(10,0)<=start.time() and end.time()<=dt.time(23,0))

def listings(archived):
    endpoint = "historical/markets" if archived else "markets"
    cursor = ""
    out = {}
    for page in range(14):
        params = {"series_ticker":SERIES,"limit":1000}
        if not archived: params["status"] = "settled"
        if cursor: params["cursor"] = cursor
        response = get_json(endpoint,params)
        for x in response.get("markets",[]):
            ticker = x.get("ticker","")
            won = x.get("result","").lower()
            if not ticker.startswith(SERIES+"-") or won not in ("yes","no"):
                continue
            try:
                close_ts = int(dt.datetime.fromisoformat(
                    x["close_time"].replace("Z","+00:00")).timestamp())
            except (KeyError,ValueError,TypeError):
                continue
            if close_ts>=CUTOFF or close_ts>=time.time() or not is_session(close_ts):
                continue
            out[ticker] = (ticker,close_ts,won=="yes",archived)
        new_cursor = response.get("cursor") or ""
        print("listing",endpoint,"page",page+1,"eligible",len(out),flush=True)
        if not new_cursor or cursor==new_cursor or len(out)>=3000:
            break
        cursor = new_cursor
    return list(out.values())

def price(x,field):
    if not isinstance(x,dict): return None
    a = x.get(field+"_dollars", x.get(field))
    try:
        return float(a) if a is not None else None
    except (TypeError,ValueError): return None

def value(c):
    bid,ask = c.get("yes_bid"),c.get("yes_ask")
    bc,ac = price(bid,"close"),price(ask,"close")
    def good(b,a):
        return b is not None and a is not None and 0<=b<=a<=1 and a-b<=.25
    if not good(bc,ac): return None
    samples = [(price(bid,p),price(ask,p)) for p in ("open","high","low","close")]
    if all(good(b,a) for b,a in samples):
        return 100 * sum((b+a)/2 for b,a in samples)/4
    return 100 * (bc+ac)/2

def load(m):
    ticker,close_ts,yes_won,archived=m
    start=close_ts-900
    endpoint = ("historical/markets/"+ticker+"/candlesticks" if archived else
                "series/"+SERIES+"/markets/"+ticker+"/candlesticks")
    response = get_json(endpoint,{"start_ts":start,"end_ts":close_ts,"period_interval":1})
    minutes={}
    for c in response.get("candlesticks",[]):
        elapsed=int(c.get("end_period_ts",0))-start
        if elapsed<60 or elapsed>900 or elapsed%60: continue
        v=value(c)
        if v is not None and 0<=v<=100: minutes[elapsed//60]=min(90,int(v//10)*10)
    if len(minutes)<2: return None
    return (ticker,close_ts,yes_won,minutes)

def first_hit(m,steps):
    minutes=m[3]
    progress=0
    for i in sorted(k for k in minutes if 1<=k<=14):
        if minutes[i]==steps[progress]:
            progress+=1
            if progress==len(steps):
                left=15-i
                return left if 1<=left<=11 else None
    return None

def pct(n,d):
    return round(n*100/d,1) if d else 0.0

def write_results(markets,skipped):
    outdir=pathlib.Path("research/out")
    outdir.mkdir(parents=True,exist_ok=True)
    all_rows=[]
    details=[]
    for steps,side in PATHS:
        hits=[]
        for m in markets:
            left=first_hit(m,steps)
            if left is not None:
                hits.append((left,m[2] if side=="UP" else not m[2]))
        wins=sum(w for _,w in hits)
        window_stats=[]
        for label,low,high in WINDOWS:
            h=[w for left,w in hits if low<=left<=high]
            window_stats.append((label,len(h),sum(h)))
            details.append(dict(pattern=" -> ".join(map(str,steps)),buy=side,
                time_left=label,hits=len(h),wins=sum(h),
                hit_rate_pct=pct(len(h),len(markets)),win_rate_pct=pct(sum(h),len(h))))
        common=max(window_stats,key=lambda x:(x[1],int(x[0].split("-")[0])))
        best=max((w for w in window_stats if w[1]>=10),
                 key=lambda x:(x[2]/x[1],x[1]),default=None)
        all_rows.append(dict(pattern=" -> ".join(map(str,steps)),buy=side,
            hits=len(hits),hit_rate_pct=pct(len(hits),len(markets)),
            wins=wins,win_rate_pct=pct(wins,len(hits)),
            common_time_left=common[0],common_hits=common[1],
            best_win_time_left_ge10=best[0] if best else "n/a",
            best_time_hits=best[1] if best else 0,
            best_time_win_rate_pct=pct(best[2],best[1]) if best else "n/a"))
    all_rows.sort(key=lambda r:(-r["hits"],-r["win_rate_pct"],r["pattern"]))
    for filename,rows in [("all_27_patterns.csv",all_rows),("all_27_time_windows.csv",details)]:
        with (outdir/filename).open("w",newline="") as f:
            writer=csv.DictWriter(f,fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    with (outdir/"summary.txt").open("w") as f:
        def line(text):
            f.write(text+"\n")
            print(text,flush=True)
        line("VALIDATION: "+str(len(markets))+"/1000 eligible, older than Nov 17, 2026 Eastern; skipped "+str(skipped))
        if len(markets)<1000: line("INCOMPLETE: do not represent as full validation")
        line("FIRST/ LAST CLOSE: "+str(markets[-1][1] if markets else "NA")+"/"+str(markets[0][1] if markets else "NA"))
        line("All patterns sorted by matches; first completion M04-M14; all 27 retained.")
        line("pattern | buy | hits | hit rate | wins | WR | common time | best WR time >=10 hits")
        for r in all_rows:
            line(r["pattern"]+" | "+r["buy"]+" | "+str(r["hits"])+" | "+
                 str(r["hit_rate_pct"])+"% | "+str(r["wins"])+" | "+
                 str(r["win_rate_pct"])+"% | "+r["common_time_left"]+" | "+
                 r["best_win_time_left_ge10"])
        line("Above 10% hit rate: "+str(sum(r["hit_rate_pct"]>10 for r in all_rows)))
        line("Historical outcome rates only; no actual fill or fee estimates.")
    return all_rows

def main():
    candidates={}
    errors=[]
    for archive in (False,True):
        try:
            for m in listings(archive): candidates.setdefault(m[0],m)
        except Exception as ex:
            errors.append(str(ex))
    order=sorted(candidates.values(),key=lambda m:m[1],reverse=True)
    print("Candidate markets",len(order),"listing errors",errors,flush=True)
    markets=[]
    skipped=0
    # Batch to avoid thousands of unneeded simultaneous requests.
    with ThreadPoolExecutor(max_workers=6) as pool:
        for start in range(0,len(order),48):
            if len(markets)>=TARGET: break
            batch=order[start:start+48]
            futures={pool.submit(load,m):m for m in batch}
            for future in as_completed(futures):
                try:
                    result=future.result()
                    if result is not None: markets.append(result)
                    else: skipped+=1
                except Exception as ex:
                    skipped+=1
                    if len(errors)<10: errors.append(str(ex))
            print("VALID",len(markets),"/1000 checked",start+len(batch),
                  "skipped",skipped,flush=True)
    markets=sorted(markets,key=lambda m:m[1],reverse=True)[:TARGET]
    if not markets:
        raise RuntimeError("No valid Kalshi candle markets: "+repr(errors))
    print("Download errors:",errors,flush=True)
    write_results(markets,skipped)

if __name__=="__main__":
    main()
