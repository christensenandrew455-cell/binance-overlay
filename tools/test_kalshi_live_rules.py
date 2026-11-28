import unittest
from datetime import datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo
from kalshi_live_rules import RiskState, contract_count, in_window, permitted_at, FiftySixty

class RiskTests(unittest.TestCase):
    def test_stake_steps_only_on_double_and_caps(self):
        s = RiskState.start(Decimal("100"))
        self.assertEqual(s.fixed_wager, Decimal("30.00"))
        self.assertTrue(s.check(Decimal("199.99"), Decimal("200"))[0])
        self.assertEqual(s.fixed_wager, Decimal("30.00"))
        self.assertTrue(s.check(Decimal("200"), Decimal("200"))[0])
        self.assertEqual(s.fixed_wager, Decimal("60.00"))
        self.assertEqual(s.stop_floor, Decimal("100"))
        self.assertTrue(s.check(Decimal("2000"), Decimal("2000"))[0])
        self.assertEqual(s.fixed_wager, Decimal("480.00"))
        self.assertTrue(s.check(Decimal("10000"), Decimal("10000"))[0])
        self.assertEqual(s.fixed_wager, Decimal("600.00"))

    def test_minimum_and_force_stop(self):
        with self.assertRaises(ValueError): RiskState.start(Decimal("99.99"))
        s = RiskState.start(Decimal("100"))
        self.assertEqual(s.check(Decimal("99.99"), Decimal("99.99")), (False, "BELOW_100_MINIMUM"))
        self.assertEqual(s.check(Decimal("50"), Decimal("50")), (False, "FIFTY_PERCENT_DRAWDOWN"))
        self.assertEqual(s.check(Decimal("200"), Decimal("200")), (False, "FORCE_STOP_LOCKED"))

    def test_budget_includes_fee_reserve(self):
        self.assertEqual(contract_count(Decimal("30")), 42)
        self.assertLessEqual(contract_count(Decimal("30")) * Decimal(".70"), Decimal("30"))

    def test_eastern_hours_minutes(self):
        tz = ZoneInfo("America/New_York")
        for hour in (10,11,17):
            self.assertTrue(in_window(datetime(2026,11,27,hour,30,tzinfo=tz)))
        for hour in (9,12,16,18,23):
            self.assertFalse(in_window(datetime(2026,11,27,hour,30,tzinfo=tz)))
        close = datetime(2026,11,27,10,15,tzinfo=tz)
        for minutes in (12,10,9,8,7,5):
            self.assertTrue(permitted_at(close-timedelta(minutes=minutes,seconds=30), close))
        for minutes in (13,11,6,4,1):
            self.assertFalse(permitted_at(close-timedelta(minutes=minutes,seconds=30), close))

    def test_both_outcomes_and_two_minute_trigger(self):
        a,b=FiftySixty(),FiftySixty()
        self.assertEqual(a.observe(Decimal(".50"),0), "ARMED")
        self.assertEqual(b.observe(Decimal(".49"),0), "ARMED")
        self.assertEqual(a.observe(Decimal(".60"),119), "BUY")
        self.assertEqual(b.observe(Decimal(".61"),119), "MISSED_PRICE_LIMIT")
        self.assertIsNone(a.observe(Decimal(".60"),120))
        c=FiftySixty()
        c.observe(Decimal(".50"),0)
        self.assertIsNone(c.observe(Decimal(".60"),121))

if __name__ == "__main__":
    unittest.main()
