"""Offline tests: no Kalshi credentials, network, or orders required."""
import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from kalshi_api import FiftyToSixty, Kalshi, best_ask, make_order


class KalshiAdapterTests(unittest.TestCase):
    def test_book_asks_are_complementary(self):
        book = {"orderbook_fp": {
            "yes_dollars": [["0.3500", "4.00"], ["0.4000", "3.00"]],
            "no_dollars": [["0.3400", "2.00"], ["0.4400", "5.00"]],
        }}
        self.assertEqual(best_ask(book, "YES"), Decimal("0.5600"))
        self.assertEqual(best_ask(book, "NO"), Decimal("0.6000"))
        self.assertIsNone(best_ask({"orderbook_fp": {"yes_dollars": [], "no_dollars": []}}, "YES"))

    def test_v2_order_mapping_yes_no(self):
        y = make_order("KXBTC15M-TEST", "YES", Decimal("0.60"), 1, "yes-test")
        n = make_order("KXBTC15M-TEST", "NO", Decimal("0.60"), 1, "no-test")
        self.assertEqual((y["side"], y["price"]), ("bid", "0.6000"))
        self.assertEqual((n["side"], n["price"]), ("ask", "0.4000"))
        self.assertEqual(y["time_in_force"], "immediate_or_cancel")
        self.assertEqual(y["post_only"], False)
        with self.assertRaises(ValueError):
            make_order("KXBTC15M-TEST", "YES", Decimal("0.61"), 1)
        with self.assertRaises(ValueError):
            make_order("NOTBTC-TEST", "YES", Decimal("0.60"), 1)

    def test_two_minute_transition(self):
        d = FiftyToSixty()
        self.assertIsNone(d.observe(Decimal("0.52"), 0))
        self.assertEqual(d.observe(Decimal("0.50"), 10), "armed")
        self.assertIsNone(d.observe(Decimal("0.58"), 60))
        self.assertEqual(d.observe(Decimal("0.60"), 120), "trigger")
        self.assertIsNone(d.observe(Decimal("0.60"), 121))

    def test_expired_transition_does_not_trigger(self):
        d = FiftyToSixty()
        self.assertEqual(d.observe(Decimal("0.49"), 0), "armed")
        self.assertIsNone(d.observe(Decimal("0.60"), 121))

    def test_production_trades_disabled(self):
        k = Kalshi("production")
        with self.assertRaises(PermissionError):
            k.submit_demo_order({"ticker": "KXBTC15M-TEST"})


if __name__ == "__main__":
    unittest.main()
