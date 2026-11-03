"""轮询循环：拉实时价 → 存 → 判断阈值 → 推送。

一个进程服务所有「用户」（自托管场景通常就一个），所以对 moligod 的请求是
全站共用的：100 个人用也是 60 秒一次，比每个客户端各打一次礼貌得多。
"""
from __future__ import annotations

import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional

from . import api, notify, thresholds
from .store import Store

BEIJING = timezone(timedelta(hours=8))


class Poller:
    def __init__(
        self,
        store: Store,
        fetch_prices: Callable[[], list] = api.fetch_current_prices,
        fetch_ref: Callable[[str], Optional[float]] = api.fetch_reference_mean,
        deliver: Callable[..., str] = notify.deliver,
        now: Callable[[], float] = time.time,
        tz=BEIJING,
    ):
        self.store = store
        self._fetch_prices = fetch_prices
        self._fetch_ref = fetch_ref
        self._deliver = deliver
        self._now = now
        self._tz = tz
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self.status = {
            "last_ok_ts": 0,
            "last_error": "",
            "last_tick_ts": 0,
            "last_alert": None,
        }

    # ---------------- 生命周期 ----------------
    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="poller", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=5)

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.tick()
            except Exception as e:                    # 永远别让循环死掉
                self.status["last_error"] = f"{type(e).__name__}: {e}"
            interval = max(30, int(self.store.settings().get("poll_seconds") or 60))
            self._stop.wait(interval)

    # ---------------- 一轮 ----------------
    def tick(self) -> None:
        settings = self.store.settings()
        self.status["last_tick_ts"] = int(self._now())
        try:
            prices = self._fetch_prices()
        except api.ApiError as e:
            self.status["last_error"] = f"{type(e).__name__}: {e}"
            return
        self.status["last_ok_ts"] = int(self._now())
        self.status["last_error"] = ""
        self.store.save_prices(prices)

        self._refresh_refs_if_stale(settings)
        self._check_thresholds(settings, prices)
        self._check_time_windows(settings)

    # ---------------- 参考均值 ----------------
    def _refresh_refs_if_stale(self, settings: dict) -> None:
        hours = float(settings.get("ref_refresh_hours") or 24)
        age = self._now() - self.store.refs_updated_ts()
        if age < hours * 3600:
            return
        for item in self.store.watchlist():
            mean = self._fetch_ref(item["name"])
            if mean and mean > 0:
                self.store.set_ref(item["name"], mean)
            time.sleep(1.5)                            # 对第三方接口客气点

    # ---------------- 阈值 ----------------
    def _check_thresholds(self, settings: dict, prices: list[dict]) -> None:
        refs = self.store.refs()
        if not refs:
            return
        by_name = {p["name"]: p for p in prices}
        cooldown = float(settings.get("cooldown_minutes") or 30) * 60
        now = int(self._now())

        for item in self.store.watchlist():
            name = item["name"]
            ref = refs.get(name)
            row = by_name.get(name)
            if not ref or not row or row["price"] <= 0:
                continue
            side = thresholds.evaluate(row["price"], ref, item["buy_dev"], item["sell_dev"])
            if side is None:
                continue
            if now - int(item["last_alert_ts"] or 0) < cooldown:
                continue

            threshold = item["buy_dev"] if side == "buy" else item["sell_dev"]
            title, body = thresholds.describe(side, name, row["price"], ref, threshold)
            delivered = self._deliver(settings, title, body, side)

            self.store.mark_alerted(name, now)
            self.store.log_alert(name, side, row["price"], ref, delivered)
            self.status["last_alert"] = {"ts": now, "name": name, "side": side, "delivered": delivered}

    # ---------------- 固定时段 ----------------
    def _check_time_windows(self, settings: dict) -> None:
        if not settings.get("window_enabled", True):
            return
        local = datetime.fromtimestamp(self._now(), tz=self._tz)
        today = local.strftime("%Y-%m-%d")
        for side, key, hour in (
            ("buy", "window_fired_buy", int(settings.get("buy_hour") or 7)),
            ("sell", "window_fired_sell", int(settings.get("sell_hour") or 20)),
        ):
            if local.hour != hour:
                continue
            if settings.get(key) == today:
                continue
            if side == "buy":
                title = "买入时段到了"
                body = "现在是最佳买入时段（30 天数据：日内相对均值最低），可以上线扫低价挂单。"
            else:
                title = "卖出时段到了"
                body = "现在是最佳卖出时段（30 天数据：日内相对均值最高），可以挂单出手。"
            delivered = self._deliver(settings, title, body, side)
            self.store.update_settings({key: today})
            self.store.log_alert("__window__", side, 0, 0.0, delivered)
            self.status["last_alert"] = {"ts": int(self._now()), "side": side, "delivered": delivered}
