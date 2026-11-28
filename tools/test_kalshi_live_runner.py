"""Offline tests of the production gate; never connect to Kalshi."""
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

import kalshi_live_runner as runner


class FakeKalshi:
    def __init__(self, cash_cents=10000):
        self.cash=cash_cents
        self.sent=[]
    def balance(self):
        return {"balance":self.cash,"portfolio_value":0}
    def _request(self,method,path,**kwargs):
        if method=="POST":
            self.sent.append(kwargs["json"])
            return {"order":{"order_id":"fake-order-id","fill_count":"1.00","remaining_count":"0.00"}}
        return {"markets":[]}


class WorkerSafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.db=runner.database(Path(self.tmp.name)/"state.sqlite")
        self.k=FakeKalshi()

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def test_user_start_stop_then_restart_keeps_anchor(self):
        runner.start(self.db,self.k)
        s=runner.state(self.db)
        self.assertTrue(s["active"])
        self.assertEqual(s["wager"],"30.00")
        self.db.execute("UPDATE state SET active=0 WHERE id=1")
        self.k.cash=15000
        runner.start(self.db,self.k)
        self.assertEqual(runner.state(self.db)["wager"],"30.00")

    def test_minimum_and_force_stop_persist(self):
        self.k.cash=9900
        with self.assertRaises(ValueError): runner.start(self.db,self.k)
        self.k.cash=10000
        runner.start(self.db,self.k)
        self.k.cash=5000
        risk,_,_=runner.risk_check(self.db,self.k)
        self.assertIsNone(risk)
        self.assertTrue(runner.state(self.db)["locked"])
        self.k.cash=10000
        with self.assertRaises(RuntimeError):runner.start(self.db,self.k)

    def test_order_is_deduped_and_requires_valid_time(self):
        runner.start(self.db,self.k)
        close=datetime(2026,11,27,15,15,tzinfo=timezone.utc)  # 10:15 AM Eastern
        market={"ticker":"KXBTC15M-FAKE","close_time":close.isoformat(),"status":"active"}
        at=close-timedelta(minutes=10,seconds=30)
        with patch.object(runner,"now_utc",return_value=at):
            runner.order_once(self.db,self.k,market,"YES",Decimal("0.60"))
            runner.order_once(self.db,self.k,market,"YES",Decimal("0.60"))
        self.assertEqual(len(self.k.sent),1)
        self.assertEqual(self.k.sent[0]["count"],"42")
        self.assertEqual(self.k.sent[0]["side"],"bid")
        self.assertEqual(self.k.sent[0]["price"],"0.6000")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM orders").fetchone()[0],1)

    def test_wrong_minute_or_quote_never_submits(self):
        runner.start(self.db,self.k)
        close=datetime(2026,11,27,15,15,tzinfo=timezone.utc)
        m={"ticker":"KXBTC15M-FAKE","close_time":close.isoformat()}
        with patch.object(runner,"now_utc",return_value=close-timedelta(minutes=6,seconds=30)):
            runner.order_once(self.db,self.k,m,"YES",Decimal("0.60"))
        with patch.object(runner,"now_utc",return_value=close-timedelta(minutes=5,seconds=30)):
            runner.order_once(self.db,self.k,m,"YES",Decimal("0.61"))
        self.assertEqual(self.k.sent,[])

    def test_readable_daily_stats_from_realized_only(self):
        self.assertEqual(runner.daily_stats(self.db)["wins"],0)


if __name__=="__main__":unittest.main()
