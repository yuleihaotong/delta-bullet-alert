#!/usr/bin/env python3
"""Fetch 30-day hourly history for all bullets from moligod.com."""
import urllib.request, hashlib, gzip, json, time, os, sys
from Crypto.Cipher import AES

OUT = "/home/leiyu/workspace/delta-force-auto/data/history"
os.makedirs(OUT, exist_ok=True)

UA = "Mozilla/5.0 (market-analysis)"

def http_get(url, retries=5):
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
            r = urllib.request.urlopen(req, timeout=25)
            return r
        except urllib.error.HTTPError as e:
            if e.code == 429:
                wait = 5 * (i + 1)
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
    r = http_get("https://moligod.com/api/market/ammo-choice-prices?t=" + str(int(time.time()*1000)))
    body = r.read()
    key = r.headers.get("x-ammo-choice-key") or ""
    aeskey = hashlib.sha256(key.encode()).digest()
    iv, ct = body[1:13], body[13:]
    c = AES.new(aeskey, AES.MODE_GCM, nonce=iv)
    c.update(b"ammo-choice-prices-v1")
    pt = c.decrypt_and_verify(ct[:-16], ct[-16:])
    return json.loads(gzip.decompress(pt).decode())

def fetch_history(name, limit=720, interval="1h"):
    url = ("https://moligod.com/api/market/history?name=" + urllib.parse.quote(name)
           + f"&interval={interval}&limit={limit}&lookup=name")
    r = http_get(url)
    return json.loads(r.read().decode())

def main():
    cur = fetch_current_prices()
    items = cur["items"]
    print(f"current prices: {len(items)} items", flush=True)
    with open(os.path.join(OUT, "_current.json"), "w", encoding="utf-8") as f:
        json.dump(cur, f, ensure_ascii=False, indent=1)

    ok, fail = 0, []
    for idx, it in enumerate(items):
        name = it["name"]
        fp = os.path.join(OUT, f"{idx:02d}_{name.replace('/', '_')}.json")
        if os.path.exists(fp):
            ok += 1
            print(f"[{idx+1}/{len(items)}] cached: {name}", flush=True)
            continue
        try:
            d = fetch_history(name)
            if d.get("status") == "ok" and d.get("data"):
                with open(fp, "w", encoding="utf-8") as f:
                    json.dump({"name": name, "object_id": it.get("object_id"), "current_price": it.get("price"), "history": d["data"]}, f, ensure_ascii=False)
                ok += 1
                print(f"[{idx+1}/{len(items)}] OK {name}: {len(d['data'])} pts", flush=True)
            else:
                fail.append(name)
                print(f"[{idx+1}/{len(items)}] no data: {name}", flush=True)
        except Exception as e:
            fail.append(name)
            print(f"[{idx+1}/{len(items)}] FAIL {name}: {e}", flush=True)
        time.sleep(1.5)
    print(f"done. ok={ok} fail={len(fail)} {fail}", flush=True)

if __name__ == "__main__":
    import urllib.parse
    main()
