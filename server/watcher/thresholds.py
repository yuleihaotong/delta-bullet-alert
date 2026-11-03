"""阈值判定。和 Android 端 `Thresholds.kt` 同一规则：单位是「相对 30 天均值的百分比」。

-3.0 表示低于均值 3% 触发买入，6.0 表示高于均值 6% 触发卖出。
Kotlin 那边曾经把小数 (-0.03) 和百分比 (-3.0) 直接比较，导致买入提醒永不触发；
两边都有单测钉住这条单位约定。
"""
from __future__ import annotations

from typing import Literal, Optional

Side = Literal["buy", "sell"]


def deviation_percent(price: float, ref: float) -> Optional[float]:
    """相对偏离，百分数。价格或参考值不可用时返回 None。"""
    if price <= 0 or ref <= 0:
        return None
    return (price - ref) / ref * 100.0


def evaluate(price: float, ref: float, buy_dev: float, sell_dev: float) -> Optional[Side]:
    dev = deviation_percent(price, ref)
    if dev is None:
        return None
    if dev <= buy_dev:
        return "buy"
    if dev >= sell_dev:
        return "sell"
    return None


def describe(side: Side, name: str, price: float, ref: float, threshold: float) -> tuple[str, str]:
    """(标题, 正文)，两端文案一致。"""
    dev = deviation_percent(price, ref) or 0.0
    label = "买入" if side == "buy" else "卖出"
    title = f"{label}提醒：{name}"
    body = f"现价 {price:.0f}，偏离 30 天均值 {dev:+.1f}%（阈值 {threshold:+.1f}%）"
    return title, body
