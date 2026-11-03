#!/usr/bin/env python3
"""入口：起轮询线程 + 网页。

    python app.py                       # http://127.0.0.1:8710
    python app.py --port 9000 --token s3cret
    python app.py --db /data/watcher.db --poll 60

想给外网访问就加 --token（否则别人能改你的自选和推送配置）。
"""
from __future__ import annotations

import argparse
import os
import sys

from watcher.poller import Poller
from watcher.store import Store
from watcher.web import serve


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="三角洲子弹提醒 · 自托管版")
    ap.add_argument("--host", default="127.0.0.1", help="监听地址；对外用 0.0.0.0")
    ap.add_argument("--port", type=int, default=8710)
    ap.add_argument("--db", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "watcher.db"))
    ap.add_argument("--token", default=os.environ.get("WATCHER_TOKEN", ""),
                    help="访问口令；空表示不鉴权（只建议监听 127.0.0.1 时用）")
    ap.add_argument("--poll", type=int, default=0, help="轮询秒数，覆盖数据库里的设置")
    ap.add_argument("--no-poller", action="store_true", help="只起网页，不轮询（调试用）")
    args = ap.parse_args(argv)

    store = Store(args.db)
    if args.poll:
        store.update_settings({"poll_seconds": max(30, args.poll)})
    if not args.token and args.host not in ("127.0.0.1", "localhost"):
        print("警告：监听非本地地址却没有 --token，任何人都能改配置。", file=sys.stderr)

    poller = Poller(store)
    if not args.no_poller:
        poller.start()
        poller.tick()                      # 起来先拉一次，页面立刻有数据

    httpd = serve(store, poller, host=args.host, port=args.port, token=args.token)
    print(f"三角洲子弹提醒已启动 → http://{'127.0.0.1' if args.host == '0.0.0.0' else args.host}:{args.port}")
    print(f"数据库：{args.db}；轮询：{store.settings()['poll_seconds']}s；"
          f"推送：{'已配置' if _has_channel(store) else '未配置（去网页里填 Bark key）'}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
    finally:
        poller.stop()
        httpd.server_close()
    return 0


def _has_channel(store: Store) -> bool:
    s = store.settings()
    return bool(s.get("bark_key") or s.get("webhook_url") or s.get("telegram_token"))


if __name__ == "__main__":
    sys.exit(main())
