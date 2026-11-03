"""SQLite 存储：设置、自选、参考均值、行情快照、提醒记录。

单用户自托管场景，一个进程一个库，够用。并发用一把锁串行化，别搞连接池。
"""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from typing import Any, Optional

DEFAULT_SETTINGS: dict[str, Any] = {
    "buy_dev": -3.0,
    "sell_dev": 6.0,
    "poll_seconds": 60,
    "cooldown_minutes": 30,
    "buy_hour": 7,
    "sell_hour": 20,
    "window_enabled": True,
    "bark_key": "",
    "bark_server": "https://api.day.app",
    "webhook_url": "",
    "webhook_format": "json",          # json | wecom | dingtalk
    "telegram_token": "",
    "telegram_chat": "",
    "ref_refresh_hours": 24,
    "window_fired_buy": "",            # 当天已提醒过的日期，防重复
    "window_fired_sell": "",
}

_SCHEMA = """
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS watchlist (
    name TEXT PRIMARY KEY,
    buy_dev REAL NOT NULL,
    sell_dev REAL NOT NULL,
    last_alert_ts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS refs (name TEXT PRIMARY KEY, mean REAL NOT NULL, updated_ts INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS prices (name TEXT PRIMARY KEY, price INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    name TEXT NOT NULL,
    side TEXT NOT NULL,
    price INTEGER NOT NULL,
    ref REAL NOT NULL,
    delivered TEXT NOT NULL
);
"""


class Store:
    def __init__(self, path: str):
        self._lock = threading.RLock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._lock, self._db:
            self._db.executescript(_SCHEMA)
            self._db.execute("PRAGMA journal_mode=WAL")

    # ---------------- settings ----------------
    def settings(self) -> dict[str, Any]:
        with self._lock:
            rows = self._db.execute("SELECT key, value FROM settings").fetchall()
        data = dict(DEFAULT_SETTINGS)
        for row in rows:
            try:
                data[row["key"]] = json.loads(row["value"])
            except json.JSONDecodeError:
                data[row["key"]] = row["value"]
        return data

    def update_settings(self, patch: dict[str, Any]) -> dict[str, Any]:
        allowed = set(DEFAULT_SETTINGS)
        with self._lock, self._db:
            for key, value in patch.items():
                if key in allowed:
                    self._db.execute(
                        "INSERT INTO settings(key, value) VALUES(?, ?) "
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                        (key, json.dumps(value)),
                    )
        return self.settings()

    # ---------------- watchlist ----------------
    def watchlist(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute(
                "SELECT name, buy_dev, sell_dev, last_alert_ts FROM watchlist ORDER BY rowid"
            ).fetchall()
        return [dict(r) for r in rows]

    def add_watch(self, name: str, buy_dev: float, sell_dev: float) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO watchlist(name, buy_dev, sell_dev) VALUES(?, ?, ?) "
                "ON CONFLICT(name) DO UPDATE SET buy_dev=excluded.buy_dev, sell_dev=excluded.sell_dev",
                (name, float(buy_dev), float(sell_dev)),
            )

    def remove_watch(self, name: str) -> None:
        with self._lock, self._db:
            self._db.execute("DELETE FROM watchlist WHERE name=?", (name,))

    def mark_alerted(self, name: str, ts: int) -> None:
        with self._lock, self._db:
            self._db.execute("UPDATE watchlist SET last_alert_ts=? WHERE name=?", (ts, name))

    # ---------------- refs / prices ----------------
    def refs(self) -> dict[str, float]:
        with self._lock:
            rows = self._db.execute("SELECT name, mean FROM refs").fetchall()
        return {r["name"]: r["mean"] for r in rows}

    def refs_updated_ts(self) -> int:
        with self._lock:
            row = self._db.execute("SELECT MAX(updated_ts) AS ts FROM refs").fetchone()
        return int(row["ts"] or 0)

    def set_ref(self, name: str, mean: float) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO refs(name, mean, updated_ts) VALUES(?, ?, ?) "
                "ON CONFLICT(name) DO UPDATE SET mean=excluded.mean, updated_ts=excluded.updated_ts",
                (name, float(mean), int(time.time())),
            )

    def save_prices(self, prices: list[dict[str, Any]]) -> None:
        with self._lock, self._db:
            self._db.executemany(
                "INSERT INTO prices(name, price, updated_at_ms) VALUES(?, ?, ?) "
                "ON CONFLICT(name) DO UPDATE SET price=excluded.price, updated_at_ms=excluded.updated_at_ms",
                [(p["name"], int(p["price"]), int(p.get("updated_at_ms") or 0)) for p in prices],
            )

    def prices(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            rows = self._db.execute("SELECT name, price, updated_at_ms FROM prices").fetchall()
        return {r["name"]: {"price": r["price"], "updated_at_ms": r["updated_at_ms"]} for r in rows}

    # ---------------- alerts ----------------
    def log_alert(self, name: str, side: str, price: int, ref: float, delivered: str) -> int:
        with self._lock, self._db:
            cur = self._db.execute(
                "INSERT INTO alerts(ts, name, side, price, ref, delivered) VALUES(?, ?, ?, ?, ?, ?)",
                (int(time.time()), name, side, int(price), float(ref), delivered),
            )
            return int(cur.lastrowid or 0)

    def recent_alerts(self, limit: int = 30) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute(
                "SELECT ts, name, side, price, ref, delivered FROM alerts ORDER BY id DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [dict(r) for r in rows]

    def last_alert_ts(self, name: str) -> Optional[int]:
        for row in self.watchlist():
            if row["name"] == name:
                return int(row["last_alert_ts"])
        return None
