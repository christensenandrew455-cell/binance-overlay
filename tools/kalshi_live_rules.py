"""Deterministic, fail-closed rules for the production BTC 15-minute bot.
Amounts are dollars (Decimal). These rules do not submit orders.
"""
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, ROUND_DOWN
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
ONE_HUNDRED = Decimal("100.00")
CAP = Decimal("600.00")
PERCENT = Decimal("0.30")
MINUTES_LEFT = frozenset({12, 10, 9, 8, 7, 5})


def in_window(at: datetime) -> bool:
    if at.tzinfo is None:
        raise ValueError("Timestamp must be timezone-aware")
    at = at.astimezone(ET)
    return (10 <= at.hour < 12) or at.hour == 17


def allowed_minute(at: datetime, close_at: datetime) -> bool:
    if at.tzinfo is None or close_at.tzinfo is None:
        raise ValueError("Timestamps must be timezone-aware")
    remaining = (close_at - at).total_seconds()
    return remaining > 0 and int(remaining // 60) in MINUTES_LEFT


def permitted_at(at: datetime, close_at: datetime) -> bool:
    return in_window(at) and allowed_minute(at, close_at)


@dataclass
class RiskState:
    anchor: Decimal
    fixed_wager: Decimal
    stopped: bool = False

    @classmethod
    def start(cls, equity: Decimal) -> "RiskState":
        if equity < ONE_HUNDRED:
            raise ValueError("Balance must be at least $100 to start")
        return cls(equity, min((equity * PERCENT).quantize(Decimal("0.01"), rounding=ROUND_DOWN), CAP))

    @property
    def stop_floor(self) -> Decimal:
        return self.anchor / 2

    def check(self, equity: Decimal, available_cash: Decimal) -> tuple[bool, str]:
        if self.stopped:
            return False, "FORCE_STOP_LOCKED"
        if equity <= self.stop_floor:
            self.stopped = True
            return False, "FIFTY_PERCENT_DRAWDOWN"
        # Step-up only at 2x the last anchor (not after every profitable trade).
        while equity >= self.anchor * 2:
            self.anchor *= 2
            self.fixed_wager = min((self.anchor * PERCENT).quantize(Decimal("0.01"), rounding=ROUND_DOWN), CAP)
        if equity < ONE_HUNDRED:
            return False, "BELOW_100_MINIMUM"
        if available_cash < self.fixed_wager:
            return False, "INSUFFICIENT_AVAILABLE_CASH"
        return True, "READY"


def contract_count(wager: Decimal, max_price: Decimal = Decimal("0.60"),
                   fee_reserve: Decimal = Decimal("0.10")) -> int:
    if wager <= 0 or max_price <= 0 or fee_reserve < 0:
        return 0
    return int((wager / (max_price + fee_reserve)).to_integral_value(rounding=ROUND_DOWN))


class FiftySixty:
    """Trigger once on a 50c -> exactly 60c executable ask within 120 s."""
    def __init__(self):
        self.armed_at: float | None = None
        self.fired = False

    def observe(self, ask: Decimal | None, t: float) -> str | None:
        if self.fired:
            return None
        if ask is None or ask <= 0 or ask >= 1:
            self.armed_at = None
            return None
        if self.armed_at is not None and t - self.armed_at > 120:
            self.armed_at = None
        if ask <= Decimal("0.50"):
            if self.armed_at is None:
                self.armed_at = t
                return "ARMED"
            return None
        if self.armed_at is not None and ask >= Decimal("0.60"):
            self.fired = True  # Never retrigger even if the limit cannot fill.
            return "BUY" if ask == Decimal("0.60") else "MISSED_PRICE_LIMIT"
        return None
