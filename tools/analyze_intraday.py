#!/usr/bin/env python3
"""Analyze 30-day hourly bullet price history from moligod.com.

Questions answered:
1. Within a daily cycle (0-23h Beijing), at which hour is it best to BUY / SELL?
2. Estimated daily return of a buy-low-sell-high cycle (net of trading-house fees).

Outputs (into delta-force-auto/output/):
  intraday_hourly_stats.csv   per-hour-of-day aggregated stats
  intraday_report.md          human-readable report
  intraday_curve.png          intraday seasonality chart (all items + top items)
"""
import json, os, re, statistics, sys
from datetime import datetime, timedelta
from collections import defaultdict

BASE = "/home/leiyu/workspace/delta-force-auto"
HIST_DIR = os.path.join(BASE, "data", "history")
OUT_DIR = os.path.join(BASE, "output")
os.makedirs(OUT_DIR, exist_ok=True)
YEAR = 2026  # data spans 07-23 .. 08-22, 2026

def parse_ts(s):
    """'MM-DD HH:MM' Beijing -> datetime(year=2026)"""
    return datetime.strptime(f"{YEAR}-{s}", "%Y-%m-%d %H:%M")

def load_items():
    items = []
    for fn in sorted(os.listdir(HIST_DIR)):
        if not fn.endswith(".json") or fn.startswith("_"):
            continue
        with open(os.path.join(HIST_DIR, fn), encoding="utf-8") as f:
            items.append(json.load(f))
    return items

def main():
    items = load_items()
    print(f"loaded {len(items)} items")
    if not items:
        print("no data yet"); return

    # ---- collect hourly series per item ----
    series = {}          # item -> list of (dt, avg, mn, mx, last)
    for it in items:
        pts = []
        for p in it["history"]:
            try:
                dt = parse_ts(p["time"])
            except Exception:
                continue
            pts.append((dt, p["avg"], p["min"], p["max"], p["last"]))
        pts.sort(key=lambda x: x[0])
        if len(pts) >= 24 * 20:   # require >=20 days
            series[it["name"]] = pts

    print(f"usable items (>=20 days): {len(series)}")

    # ---- per item stats ----
    item_stats = {}   # name -> dict
    for name, pts in series.items():
        avgs = [p[1] for p in pts]
        mean = statistics.mean(avgs)
        item_stats[name] = {
            "mean": mean, "n": len(pts),
            "first": pts[0][0], "last": pts[-1][0],
            "min_price": min(p[2] for p in pts), "max_price": max(p[3] for p in pts),
        }

    # ---- hourly-of-day aggregation (relative to item mean) ----
    # hour -> list of (rel_avg, rel_min, rel_max, rel_last)
    hour_buckets = defaultdict(list)
    for name, pts in series.items():
        m = item_stats[name]["mean"]
        for dt, ap, mn, mx, last in pts:
            h = dt.hour
            hour_buckets[h].append((ap / m - 1, mn / m - 1, mx / m - 1, last / m - 1))

    rows = []
    for h in range(24):
        rel_avg = [x[0] for x in hour_buckets[h]]
        rel_min = [x[1] for x in hour_buckets[h]]
        rel_max = [x[2] for x in hour_buckets[h]]
        rows.append({
            "hour": h,
            "n": len(rel_avg),
            "rel_avg_mean": statistics.mean(rel_avg),
            "rel_avg_median": statistics.median(rel_avg),
            "rel_min_mean": statistics.mean(rel_min),
            "rel_max_mean": statistics.mean(rel_max),
            "spread_hourly_mean": statistics.mean([x[2] - x[1] for x in hour_buckets[h]]),
        })

    best_buy_hour = min(rows, key=lambda r: r["rel_avg_mean"])
    best_sell_hour = max(rows, key=lambda r: r["rel_avg_mean"])

    # ---- daily-cycle range (per item per day) ----
    daily_ranges = []   # (item, day, low_avg, high_avg, range_pct)
    daily_extreme_ranges = []  # using min/max fields
    for name, pts in series.items():
        by_day = defaultdict(list)
        for dt, ap, mn, mx, last in pts:
            by_day[dt.date()].append((ap, mn, mx))
        for day, vals in by_day.items():
            if len(vals) < 8:   # need decent coverage of the day
                continue
            lows = [v[1] for v in vals]; highs = [v[2] for v in vals]
            lo_avg = min(v[0] for v in vals); hi_avg = max(v[0] for v in vals)
            daily_ranges.append((name, day, lo_avg, hi_avg, hi_avg / lo_avg - 1))
            daily_extreme_ranges.append((name, day, min(lows), max(highs), max(highs) / min(lows) - 1))

    def mean_of(xs): return statistics.mean(xs) if xs else 0.0

    # ---- fee scenarios ----
    fees = [0.10, 0.13, 0.15]
    # strategy A: buy at best-buy-hour avg, sell at best-sell-hour avg (same-day, hour-avg)
    # strategy B: buy at hourly min of best-buy hour, sell at hourly max of best-sell hour
    bh, sh = best_buy_hour["hour"], best_sell_hour["hour"]
    strat = {}
    for label, buy_fn, sell_fn in [
        ("A.avg-to-avg", lambda x: x[1], lambda x: x[3]),
        ("B.min-to-max", lambda x: x[2], lambda x: x[3]),
    ]:
        cycle_rets = defaultdict(list)   # fee -> list of per-day returns
        for name, pts in series.items():
            by_day = defaultdict(list)
            for dt, ap, mn, mx, last in pts:
                by_day[dt.date()].append((dt.hour, ap, mn, mx, last))
            for day, vals in by_day.items():
                b = [v for v in vals if v[0] == bh]
                s = [v for v in vals if v[0] == sh]
                if not b or not s:
                    continue
                buy_p = buy_fn(b[0]); sell_p = sell_fn(s[0])
                if buy_p <= 0:
                    continue
                gross = sell_p / buy_p - 1
                for f in fees:
                    cycle_rets[f].append(gross - f)
        strat[label] = {f: (mean_of(v), len(v)) for f, v in cycle_rets.items()}

    # ---- consistency: how often buy-hour is cheaper than sell-hour ----
    consistency = []
    for name, pts in series.items():
        by_day = defaultdict(list)
        for dt, ap, mn, mx, last in pts:
            by_day[dt.date()].append((dt.hour, ap))
        for day, vals in by_day.items():
            b = [v[1] for v in vals if v[0] == bh]
            s = [v[1] for v in vals if v[0] == sh]
            if b and s:
                consistency.append(1 if s[0] > b[0] else 0)
    consistency_rate = mean_of(consistency) if consistency else 0

    # ---- report ----
    L = []
    L.append("# 三角洲行动 子弹 日内(24小时)买卖时机分析报告")
    L.append("")
    L.append(f"- 数据源: moligod.com `/api/market/history`（北京时间 UTC+8，逐小时 avg/min/max/last）")
    L.append(f"- 窗口: {min(v['first'] for v in item_stats.values()):%m-%d %H:%M} ~ {max(v['last'] for v in item_stats.values()):%m-%d %H:%M}（约 30 天）")
    L.append(f"- 样本: {len(series)} 种子弹 × 720 小时点（每种子弹至少 20 天完整数据）")
    L.append(f"- 说明: 相对价格 = 该小时价格 / 该子弹 30 天均值 - 1（消除子弹间价格水平差异）")
    L.append("")
    L.append("## 1. 日内价格形态（各小时相对均值偏离，%）")
    L.append("")
    L.append("| 小时 | 样本数 | 均值偏离% | 中位数偏离% | 小时均值偏离% | 小时极值偏离% |")
    L.append("|---|---|---|---|---|---|")
    for r in sorted(rows, key=lambda x: x["hour"]):
        L.append(f"| {r['hour']:02d}:00 | {r['n']} | {r['rel_avg_mean']*100:+.2f} | {r['rel_avg_median']*100:+.2f} | {r['rel_min_mean']*100:+.2f} | {r['rel_max_mean']*100:+.2f} |")
    L.append("")
    L.append(f"## 2. 结论：最佳买卖时段")
    L.append("")
    L.append(f"- **最佳买入小时: {bh:02d}:00-{bh+1:02d}:00**（日内相对均价最低 {best_buy_hour['rel_avg_mean']*100:+.2f}%）")
    L.append(f"- **最佳卖出小时: {sh:02d}:00-{sh+1:02d}:00**（日内相对均价最高 {best_sell_hour['rel_avg_mean']*100:+.2f}%）")
    L.append(f"- 买卖价差（均值口径）: {(best_sell_hour['rel_avg_mean']-best_buy_hour['rel_avg_mean'])*100:.2f} 个百分点")
    L.append(f"- 日内规律稳定性: 在 {len(consistency)} 个「日」样本中，买入小时价格 < 卖出小时价格 的占比 **{consistency_rate*100:.1f}%**")
    L.append("")
    L.append("## 3. 日内波动空间（潜在套利上限）")
    L.append("")
    dr = [r[4] for r in daily_ranges]
    de = [r[4] for r in daily_extreme_ranges]
    L.append(f"- 同一子弹同日 小时均值最高/最低 平均差距: **{mean_of(dr)*100:.2f}%**（保守口径，按小时均值买/卖）")
    L.append(f"- 同日 小时极值(min/max) 平均差距: **{mean_of(de)*100:.2f}%**（激进口径，盯盘抢价）")
    L.append(f"- 注: 交易行卖出手续费最低 **10%**（按偏离基准价每 4% 加 0.1%），买入另有 3% 保证金占用")
    L.append("")
    L.append("## 4. 日化收益估算（单日一个买卖周期，扣手续费后）")
    L.append("")
    L.append(f"策略: 买入小时 {bh:02d}:00，卖出小时 {sh:02d}:00（同一天内）")
    L.append("")
    L.append("| 策略口径 | 手续费10% | 手续费13% | 手续费15% |")
    L.append("|---|---|---|---|")
    for label, res in strat.items():
        cells = [f"{res[f][0]*100:+.2f}% (n={res[f][1]})" for f in fees]
        L.append(f"| {label} | " + " | ".join(cells) + " |")
    L.append("")
    L.append("> 年化参考 = 日化 × 365（仅数学折算；受风控/流动性/手续费动态/库存占用限制，实际远低于此）")
    L.append("")
    L.append("## 5. 建议")
    L.append("")
    L.append("- 若手续费按 10% 下限计，日内套利需价差 > 10% 才有利可图；数据表明见 §3/§4。")
    L.append("- 建议结合 moligod 实时价做「相对低点触发买入」而非固定小时硬买（日内形态仅统计规律，非保证）。")
    L.append("- 波动大的子弹（如 .300BLK、12.7x55mm 系列）机会更多但风险也更大，见附录逐子弹日波动。")
    L.append("")
    L.append("## 附录：逐子弹 30 天波动与当前价")
    L.append("")
    L.append("| 子弹 | 30天均值 | 日波动均值(avg口径)% | 日波动均值(极值)% | 当前价 |")
    L.append("|---|---|---|---|---|")
    for name, st in sorted(item_stats.items(), key=lambda kv: -kv[1]["max_price"]):
        item_dr = [r[4] for r in daily_ranges if r[0] == name]
        item_de = [r[4] for r in daily_extreme_ranges if r[0] == name]
        cur = next((i.get("current_price") for i in items if i["name"] == name), None)
        L.append(f"| {name} | {st['mean']:.0f} | {mean_of(item_dr)*100:.2f}% | {mean_of(item_de)*100:.2f}% | {cur} |")

    report = "\n".join(L)
    with open(os.path.join(OUT_DIR, "intraday_report.md"), "w", encoding="utf-8") as f:
        f.write(report)
    print(report)

    # ---- CSV ----
    import csv
    with open(os.path.join(OUT_DIR, "intraday_hourly_stats.csv"), "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader(); w.writerows(rows)

    # ---- chart ----
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(11, 5))
        hours = [r["hour"] for r in rows]
        ax.plot(hours, [r["rel_avg_mean"]*100 for r in rows], marker="o", label="所有子弹平均")
        ax.axhline(0, color="gray", lw=0.8)
        ax.axvspan(best_buy_hour["hour"], best_buy_hour["hour"]+1, color="green", alpha=0.15, label=f"买入 {bh:02d}:00")
        ax.axvspan(best_sell_hour["hour"], best_sell_hour["hour"]+1, color="red", alpha=0.15, label=f"卖出 {sh:02d}:00")
        ax.set_xlabel("北京时间小时"); ax.set_ylabel("相对 30 天均值偏离 (%)")
        ax.set_title("三角洲行动子弹日内价格形态（30 天平均，moligod.com 数据）")
        ax.grid(alpha=0.3); ax.legend()
        fig.tight_layout(); fig.savefig(os.path.join(OUT_DIR, "intraday_curve.png"), dpi=130)
        print("chart saved")
    except Exception as e:
        print("chart failed:", e)

if __name__ == "__main__":
    main()
