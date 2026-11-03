#!/usr/bin/env python3
"""Fetch 30-day hourly history for all bullets from moligod.com.

用法:
  python tools/fetch_history.py                        # 写到 <repo>/data/history
  python tools/fetch_history.py --out-dir /tmp/hist
  python tools/fetch_history.py --limit 720 --sleep 1.5

依赖: pip install pycryptodome
"""
import argparse
import gzip
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from Crypto.Cipher import AES

API = "https://moligod.com/api/market"
UA = "Mozilla/5.0 (market-analysis)"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(REPO_ROOT, "data", "history")


def _retry_after(err):
    """服务端给了 Retry-After 就听它的（秒）。"""
    try:
        return int(err.headers.get("Retry-After", ""))
    except (TypeError, ValueError):
        return None


def http_get(url, retries=5):
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
            return urllib.request.urlopen(req, timeout=25)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                wait = _retry_after(e) or 5 * (i + 1)
                print(f"  429 -> sleep {wait}s", flush=True)
                time.sleep(wait)
            elif e.code in (500, 502, 503, 504):
                wait = 3 * (i + 1)
                print(f"  {e.code} -> sleep {wait}s", flush=True)
                time.sleep(wait)
            else:
                raise
        except Exception as e:
            wait = 3 * (i + 1)
            print(f"  err {e} -> sleep {wait}s", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"failed after retries: {url}")


def fetch_current_prices():
    r = http_get(f"{API}/ammo-choice-prices?t=" + str(int(time.time() * 1000)))
    body = r.read()
    key = r.headers.get("x-ammo-choice-key") or ""
    aeskey = hashlib.sha256(key.encode()).digest()
    iv, ct = body[1:13], body[13:]
    c = AES.new(aeskey, AES.MODE_GCM, nonce=iv)
    c.update(b"ammo-choice-prices-v1")
    pt = c.decrypt_and_verify(ct[:-16], ct[-16:])
    return json.loads(gzip.decompress(pt).decode())


def safe_filename(name):
    """Windows 不允许 < > : " / \\ | ? * 出现在文件名里（如 '.357 Magnum 9号霰射"鼠弹"'）。"""
    return re.sub(r'[<>:"/\\|?*]', "_", name)


def fetch_history(name, limit=720, interval="1h"):
    url = (f"{API}/history?name=" + urllib.parse.quote(name, safe="")
           + f"&interval={interval}&limit={limit}&lookup=name")
    r = http_get(url)
    return json.loads(r.read().decode())


def main(argv=None):
    ap = argparse.ArgumentParser(description="拉取 moligod 逐小时历史（默认 30 天）")
    ap.add_argument("--out-dir", default=DEFAULT_OUT, help="数据输出目录（默认 <repo>/data/history）")
    ap.add_argument("--limit", type=int, default=720, help="小时点数（720=30 天）")
    ap.add_argument("--interval", default="1h", help="聚合粒度，默认 1h")
    ap.add_argument("--sleep", type=float, default=1.5, help="两次请求之间的间隔秒数")
    args = ap.parse_args(argv)

    out = os.path.abspath(args.out_dir)
    os.makedirs(out, exist_ok=True)

    cur = fetch_current_prices()
    items = cur["items"]
    print(f"current prices: {len(items)} items", flush=True)
    with open(os.path.join(out, "_current.json"), "w", encoding="utf-8") as f:
        json.dump(cur, f, ensure_ascii=False, indent=1)

    ok, fail = 0, []
    for idx, it in enumerate(items):
        name = it["name"]
        fp = os.path.join(out, f"{idx:02d}_{safe_filename(name)}.json")
        if os.path.exists(fp):
            ok += 1
            print(f"[{idx + 1}/{len(items)}] cached: {name}", flush=True)
            continue
        try:
            d = fetch_history(name, limit=args.limit, interval=args.interval)
            if d.get("status") == "ok" and d.get("data"):
                with open(fp, "w", encoding="utf-8") as f:
                    json.dump({"name": name, "object_id": it.get("object_id"),
                               "current_price": it.get("price"), "history": d["data"]},
                              f, ensure_ascii=False)
                ok += 1
                print(f"[{idx + 1}/{len(items)}] OK {name}: {len(d['data'])} pts", flush=True)
            else:
                fail.append(name)
                print(f"[{idx + 1}/{len(items)}] no data: {name}", flush=True)
        except Exception as e:
            fail.append(name)
            print(f"[{idx + 1}/{len(items)}] FAIL {name}: {e}", flush=True)
        time.sleep(args.sleep)
    print(f"done. ok={ok} fail={len(fail)} {fail}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
