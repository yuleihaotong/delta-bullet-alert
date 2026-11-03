"""推送通道：Bark（iOS）、企业微信/钉钉机器人、通用 webhook、Telegram。

自托管场景下最省事的组合是 Bark：iPhone 装个 App，拿到 device key，
服务端 POST 一次就变成系统通知。不需要 Apple 开发者账号，也不需要审核。
"""
from __future__ import annotations

import json
import urllib.parse
import urllib.request
from typing import Any

_TIMEOUT = 12


def _post(url: str, payload: Any, as_json: bool = True) -> tuple[bool, str]:
    data = json.dumps(payload).encode("utf-8") if as_json else urllib.parse.urlencode(payload).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json" if as_json else "application/x-www-form-urlencoded")
    req.add_header("User-Agent", "DeltaBulletAlert/1.1")
    try:
        with urllib.request.urlopen(req, timeout=_TIMEOUT) as r:
            body = r.read(400).decode("utf-8", "replace")
            ok = 200 <= r.status < 300
            return ok, f"{r.status} {body[:120]}"
    except Exception as e:                       # 推送失败不能影响主循环
        return False, f"{type(e).__name__}: {e}"


def push_bark(settings: dict, title: str, body: str, side: str) -> tuple[bool, str]:
    key = (settings.get("bark_key") or "").strip()
    if not key:
        return False, "未配置 bark_key"
    server = (settings.get("bark_server") or "https://api.day.app").rstrip("/")
    if "://" not in server:
        server = "https://" + server
    payload = {
        "title": title,
        "body": body,
        "group": "三角洲子弹提醒",
        "sound": "alarm" if side == "buy" else "minuet",
    }
    return _post(f"{server}/{urllib.parse.quote(key)}", payload)


def _wecom_text(title: str, body: str) -> dict:
    return {"msgtype": "text", "text": {"content": f"{title}\n{body}"}}


def _dingtalk_text(title: str, body: str) -> dict:
    return {"msgtype": "text", "text": {"content": f"{title}\n\n{body}"}}


def push_webhook(settings: dict, title: str, body: str, side: str) -> tuple[bool, str]:
    url = (settings.get("webhook_url") or "").strip()
    if not url:
        return False, "未配置 webhook_url"
    fmt = settings.get("webhook_format") or "json"
    if fmt == "wecom":
        payload = _wecom_text(title, body)
    elif fmt == "dingtalk":
        payload = _dingtalk_text(title, body)
    else:
        payload = {"title": title, "body": body, "text": f"{title}\n{body}", "side": side}
    return _post(url, payload)


def push_telegram(settings: dict, title: str, body: str, side: str) -> tuple[bool, str]:
    token = (settings.get("telegram_token") or "").strip()
    chat = str(settings.get("telegram_chat") or "").strip()
    if not token or not chat:
        return False, "未配置 telegram"
    return _post(f"https://api.telegram.org/bot{token}/sendMessage",
                 {"chat_id": chat, "text": f"{title}\n{body}"})


CHANNELS = {
    "bark": push_bark,
    "webhook": push_webhook,
    "telegram": push_telegram,
}


def deliver(settings: dict, title: str, body: str, side: str = "buy") -> str:
    """依次尝试已配置的通道，返回人类可读的结果摘要。"""
    parts = []
    for name, fn in CHANNELS.items():
        ok, detail = fn(settings, title, body, side)
        if ok:
            parts.append(f"{name}:ok")
        elif detail.startswith("未配置"):
            continue
        else:
            parts.append(f"{name}:fail({detail[:60]})")
    return ", ".join(parts) if parts else "no-channel"
