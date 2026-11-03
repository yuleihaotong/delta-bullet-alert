"""moligod.com 行情客户端：实时价（加密）+ 逐小时历史。

和 Android 端 `PriceApi.kt` 同一套解密：响应 = [版本字节=1][12B IV][AES-GCM 密文+tag]，
AAD = "ammo-choice-prices-v1"，密钥 = SHA-256(响应头 x-ammo-choice-key)。
"""
from __future__ import annotations

import gzip
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request


class ApiError(RuntimeError):
    """接口不可用（网络、限流、格式变化）。"""


class RateLimited(ApiError):
    """429；调用方应退避。"""


BASE = "https://moligod.com/api/market"
UA = "DeltaBulletAlert/1.1"
_AAD = b"ammo-choice-prices-v1"


def _get(url: str, timeout: int = 20, retries: int = 3) -> tuple[int, dict, bytes]:
    backoff = 1.0
    last: Exception | None = None
    for _ in range(retries):
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
        except urllib.error.HTTPError as e:
            if e.code == 429:
                wait = _retry_after(e) or backoff
                time.sleep(min(wait, 60))
                backoff *= 2
                last = RateLimited("429")
                continue
            raise ApiError(f"HTTP {e.code}") from e
        except Exception as e:                      # 网络抖动
            last = e
            time.sleep(backoff)
            backoff *= 2
    raise RateLimited(str(last) if last else "failed")


def _retry_after(err: urllib.error.HTTPError) -> float | None:
    try:
        return float(err.headers.get("Retry-After", ""))
    except (TypeError, ValueError):
        return None


def _decrypt(body: bytes, key: str) -> bytes:
    from Crypto.Cipher import AES

    if not body or body[0] != 1:
        raise ApiError("payload format changed")
    iv, ct = body[1:13], body[13:]
    aes = AES.new(hashlib.sha256(key.encode()).digest(), AES.MODE_GCM, nonce=iv)
    aes.update(_AAD)
    return gzip.decompress(aes.decrypt_and_verify(ct[:-16], ct[-16:]))


def fetch_current_prices() -> list[dict]:
    """返回 [{"name": str, "price": int, "object_id": str, "updated_at_ms": int}]。"""
    url = f"{BASE}/ammo-choice-prices?t={int(time.time() * 1000)}"
    _, headers, body = _get(url)
    key = headers.get("x-ammo-choice-key") or ""
    if not key:
        raise ApiError("no x-ammo-choice-key header")
    data = json.loads(_decrypt(body, key).decode("utf-8"))
    out = []
    for item in data.get("items", []):
        price = item.get("price")
        out.append({
            "object_id": str(item.get("object_id") or ""),
            "name": item.get("name") or "",
            "price": int(round(float(price))) if price is not None else 0,
            "updated_at_ms": int(item.get("updated_at_ms") or 0),
        })
    return out


def fetch_history(name: str, limit: int = 720, interval: str = "1h") -> list[dict]:
    url = (f"{BASE}/history?name={urllib.parse.quote(name, safe='')}"
           f"&interval={interval}&limit={limit}&lookup=name")
    _, _, body = _get(url)
    data = json.loads(body.decode("utf-8"))
    return data.get("data") or []


def fetch_reference_mean(name: str, limit: int = 720) -> float | None:
    """30 天逐小时 avg 的算术平均；拿不到返回 None。"""
    try:
        rows = fetch_history(name, limit=limit)
    except ApiError:
        return None
    avgs = [float(r["avg"]) for r in rows if r.get("avg")]
    return sum(avgs) / len(avgs) if avgs else None
