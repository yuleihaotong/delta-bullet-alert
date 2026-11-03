"""极简 HTTP 层：REST + 静态页面。

只用标准库，不引框架——自托管一个单用户小工具，装依赖越少越不容易烂。
"""
from __future__ import annotations

import json
import mimetypes
import os
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable

from .store import Store

STATIC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")


class Handler(BaseHTTPRequestHandler):
    store: Store = None            # type: ignore[assignment]
    poller = None                  # type: ignore[assignment]
    token: str = ""
    server_version = "DeltaWatcher/1.1"

    # ---------------- 工具 ----------------
    def log_message(self, fmt: str, *args) -> None:      # 别把访问日志刷满终端
        pass

    def _authorized(self) -> bool:
        if not self.token:
            return True
        header = self.headers.get("Authorization", "")
        if header.startswith("Bearer ") and header[7:] == self.token:
            return True
        query = urllib.parse.urlparse(self.path).query
        return urllib.parse.parse_qs(query).get("token", [""])[0] == self.token

    def _send(self, code: int, body: bytes, ctype: str = "application/json; charset=utf-8") -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj: Any) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {}

    # ---------------- 路由 ----------------
    def do_GET(self) -> None:
        path = urllib.parse.urlparse(self.path).path
        if path in ("/", "/index.html"):
            return self._static("index.html")
        if path in ("/manifest.webmanifest", "/app.js", "/style.css", "/icon.svg"):
            return self._static(path.lstrip("/"))
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        if path == "/api/state":
            return self._json(200, self._state())
        if path == "/api/alerts":
            return self._json(200, {"alerts": self.store.recent_alerts(50)})
        return self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        path = urllib.parse.urlparse(self.path).path
        body = self._body()
        if path == "/api/settings":
            self.store.update_settings(body)
            return self._json(200, {"settings": self._public_settings()})
        if path == "/api/watch":
            name = (body.get("name") or "").strip()
            if not name:
                return self._json(400, {"error": "name required"})
            settings = self.store.settings()
            self.store.add_watch(
                name,
                float(body.get("buy_dev", settings["buy_dev"])),
                float(body.get("sell_dev", settings["sell_dev"])),
            )
            return self._json(200, {"ok": True})
        if path == "/api/unwatch":
            self.store.remove_watch((body.get("name") or "").strip())
            return self._json(200, {"ok": True})
        if path == "/api/test":
            from . import notify
            result = notify.deliver(self.store.settings(), "测试提醒", "如果你看到这条，推送链路是通的。", "buy")
            return self._json(200, {"delivered": result})
        if path == "/api/tick":
            self.poller.tick()
            return self._json(200, {"ok": True, "status": self.poller.status})
        return self._json(404, {"error": "not found"})

    # ---------------- 数据 ----------------
    def _public_settings(self) -> dict:
        """密钥类只回「配没配」，别把 key 再吐回页面。"""
        settings = self.store.settings()
        public = {k: v for k, v in settings.items()
                  if k not in ("bark_key", "telegram_token", "telegram_chat", "webhook_url")}
        public["bark_key_set"] = bool(settings.get("bark_key"))
        public["webhook_set"] = bool(settings.get("webhook_url"))
        public["telegram_set"] = bool(settings.get("telegram_token"))
        return public

    def _state(self) -> dict:
        import time

        from .thresholds import deviation_percent

        public = self._public_settings()
        refs = self.store.refs()
        prices = self.store.prices()
        watch = []
        for item in self.store.watchlist():
            name = item["name"]
            price = (prices.get(name) or {}).get("price") or 0
            ref = refs.get(name)
            dev = deviation_percent(price, ref) if ref else None
            watch.append({
                "name": name,
                "price": price,
                "ref": ref,
                "deviation": dev,
                "buy_dev": item["buy_dev"],
                "sell_dev": item["sell_dev"],
                "last_alert_ts": item["last_alert_ts"],
            })
        all_prices = [
            {"name": n, "price": p["price"], "updated_at_ms": p["updated_at_ms"]}
            for n, p in prices.items()
        ]
        return {
            "settings": public,
            "watch": watch,
            "prices": all_prices,
            "alerts": self.store.recent_alerts(20),
            "status": self.poller.status,
            "server_now": int(time.time()),
        }

    def _static(self, name: str) -> None:
        safe = os.path.basename(name)
        path = os.path.join(STATIC_DIR, safe)
        if not os.path.isfile(path):
            return self._json(404, {"error": "not found"})
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        if safe.endswith(".webmanifest"):
            ctype = "application/manifest+json"
        with open(path, "rb") as f:
            self._send(200, f.read(), ctype)


def serve(store: Store, poller, host: str = "127.0.0.1", port: int = 8710, token: str = "") -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {"store": store, "poller": poller, "token": token})
    httpd = ThreadingHTTPServer((host, port), handler)
    return httpd
