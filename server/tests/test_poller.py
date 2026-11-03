"""轮询逻辑测试：用假接口和假推送，不联网。"""
import os
import sqlite3
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone

from watcher.poller import BEIJING, Poller
from watcher.store import Store


class FakeApi:
    def __init__(self, prices, ref=1000.0):
        self.prices = prices
        self.ref = ref
        self.calls = 0

    def __call__(self):
        self.calls += 1
        return [{"object_id": str(i), "name": n, "price": p, "updated_at_ms": 0}
                for i, (n, p) in enumerate(self.prices.items())]


class PollerTest(unittest.TestCase):
    def setUp(self):
        # 内存库：测试不需要落盘，也绕开某些环境下「新建目录不可写」的坑
        self.store = Store(":memory:")
        self.sent = []

    def tearDown(self):
        self.store._db.close()

    def make(self, prices, now=None):
        self.api = FakeApi(prices)
        clock = (lambda: now) if now is not None else time.time
        return Poller(
            self.store,
            fetch_prices=self.api,
            fetch_ref=lambda name: 1000.0,
            deliver=lambda s, t, b, side: self.sent.append((t, b, side)) or "test:ok",
            now=clock,
        )

    def test_buy_threshold_fires(self):
        self.store.add_watch("M855", -3.0, 6.0)
        self.store.set_ref("M855", 1000.0)
        p = self.make({"M855": 960})
        p.tick()
        self.assertEqual(len(self.sent), 1)
        self.assertEqual(self.sent[0][2], "buy")
        self.assertIn("M855", self.sent[0][0])

    def test_no_fire_inside_band(self):
        self.store.add_watch("M855", -3.0, 6.0)
        self.store.set_ref("M855", 1000.0)
        p = self.make({"M855": 990})          # -1%，没到线
        p.tick()
        self.assertEqual(self.sent, [])

    def test_cooldown_blocks_repeat(self):
        self.store.add_watch("M855", -3.0, 6.0)
        self.store.set_ref("M855", 1000.0)
        now = time.time()
        p = self.make({"M855": 900}, now=now)
        p.tick()
        self.assertEqual(len(self.sent), 1)
        # 5 分钟后再触发同一颗：应被 30 分钟冷却挡住
        p2 = self.make({"M855": 900}, now=now + 300)
        p2.tick()
        self.assertEqual(len(self.sent), 1)
        # 过了冷却再触发
        p3 = self.make({"M855": 900}, now=now + 3600)
        p3.tick()
        self.assertEqual(len(self.sent), 2)

    def test_api_failure_sets_error_and_keeps_last_ok(self):
        self.store.add_watch("M855", -3.0, 6.0)
        self.store.set_ref("M855", 1000.0)
        p = self.make({"M855": 990})
        p.tick()
        ok_ts = p.status["last_ok_ts"]
        self.assertGreater(ok_ts, 0)

        def boom():
            from watcher.api import ApiError
            raise ApiError("HTTP 503")

        p._fetch_prices = boom
        p.tick()
        self.assertIn("503", p.status["last_error"])
        self.assertEqual(p.status["last_ok_ts"], ok_ts)     # 上一次成功时间不能被清掉

    def test_window_fires_once_per_day(self):
        self.store.update_settings({"window_enabled": True, "buy_hour": 7, "sell_hour": 20})
        fixed = datetime(2026, 11, 3, 7, 5, tzinfo=BEIJING).timestamp()
        p = self.make({}, now=fixed)
        p.tick()
        p.tick()                                   # 同一小时内再来一次，不该重复
        self.assertEqual(len(self.sent), 1)
        self.assertIn("买入时段", self.sent[0][0])

    def test_window_disabled(self):
        self.store.update_settings({"window_enabled": False})
        fixed = datetime(2026, 11, 3, 7, 5, tzinfo=BEIJING).timestamp()
        p = self.make({}, now=fixed)
        p.tick()
        self.assertEqual(self.sent, [])

    def test_refs_refreshed_when_missing(self):
        self.store.add_watch("M855", -3.0, 6.0)
        p = self.make({"M855": 1000})
        p.tick()
        self.assertAlmostEqual(self.store.refs()["M855"], 1000.0)

    def test_alert_is_persisted(self):
        self.store.add_watch("M855", -3.0, 6.0)
        self.store.set_ref("M855", 1000.0)
        p = self.make({"M855": 900})
        p.tick()
        alerts = self.store.recent_alerts()
        self.assertEqual(len(alerts), 1)
        self.assertEqual(alerts[0]["side"], "buy")
        self.assertEqual(alerts[0]["delivered"], "test:ok")


if __name__ == "__main__":
    unittest.main()
