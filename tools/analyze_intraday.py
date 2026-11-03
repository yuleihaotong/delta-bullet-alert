#!/usr/bin/env python3
"""Analyze 30-day hourly bullet price history from moligod.com.

Questions answered:
1. Within a daily cycle (0-23h Beijing), at which hour is it best to BUY / SELL?
2. Estimated daily return of a buy-low-sell-high cycle (net of trading-house fees).

用法:
  python tools/analyze_intraday.py                          # 数据 <repo>/data/history，输出 <repo>/output
  python tools/analyze_intraday.py --data-dir /tmp/hist --out-dir /tmp/out
  python tools/analyze_intraday.py --bootstrap 0            # 跳过置信区间（快）

Outputs:
  intraday_hourly_stats.csv   per-hour-of-day aggregated stats
  intraday_report.md          human-readable report
  intraday_curve.png          intraday seasonality chart
"""
import argparse
import csv
import json
import os
import random
import statistics
import sys
from collections import defaultdict
from datetime import datetime

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DATA = os.path.join(REPO_ROOT, "data", "history")
DEFAULT_OUT = os.path.join(REPO_ROOT, "output")
DEFAULT_YEAR = 2026  # 接口返回 "MM-DD HH:MM" 无年份


def parse_ts(s, year):
    """'MM-DD HH:MM' Beijing -> datetime(year=...)"""
    return datetime.strptime(f"{year}-{s}", "%Y-%m-%d %H:%M")


def mean_of(xs):
    return statistics.mean(xs) if xs else 0.0


def load_items(hist_dir):
    items = []
    for fn in sorted(os.listdir(hist_dir)):
        if not fn.endswith(".json") or fn.startswith("_"):
            continue
        with open(os.path.join(hist_dir, fn), encoding="utf-8") as f:
            items.append(json.load(f))
    return items


def main(argv=None):
    ap = argparse.ArgumentParser(description="日内买卖时机分析")
    ap.add_argument("--data-dir", default=DEFAULT_DATA, help="fetch_history.py 的输出目录")
    ap.add_argument("--out-dir", default=DEFAULT_OUT, help="报告/CSV/图表输出目录")
    ap.add_argument("--year", type=int, default=DEFAULT_YEAR,
                    help="历史时间戳补的年份（接口只给 MM-DD HH:MM）")
    ap.add_argument("--min-days", type=int, default=20, help="单颗子弹最少完整天数")
    ap.add_argument("--bootstrap", type=int, default=2000,
                    help="按日聚类 bootstrap 次数；0 表示跳过")
    ap.add_argument("--seed", type=int, default=20260101, help="bootstrap 随机种子")
    args = ap.parse_args(argv)

    hist_dir = os.path.abspath(args.data_dir)
    out_dir = os.path.abspath(args.out_dir)
    os.makedirs(out_dir, exist_ok=True)

    items = load_items(hist_dir)
    print(f"loaded {len(items)} items from {hist_dir}")
    if not items:
        print("no data yet; run tools/fetch_history.py first")
        return 1

    # ---- collect hourly series per item ----
    series = {}          # item -> list of (dt, avg, mn, mx, last)
    for it in items:
        pts = []
        for p in it["history"]:
            try:
                dt = parse_ts(p["time"], args.year)
            except Exception:
                continue
            pts.append((dt, p["avg"], p["min"], p["max"], p["last"]))
        pts.sort(key=lambda x: x[0])
        if len(pts) >= 24 * args.min_days:   # require >= min_days
            series[it["name"]] = pts

    print(f"usable items (>={args.min_days} days): {len(series)}")
    if not series:
        print("no item has enough history")
        return 1

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
    hour_buckets = defaultdict(list)
    for name, pts in series.items():
        m = item_stats[name]["mean"]
        for dt, ap, mn, mx, last in pts:
            hour_buckets[dt.hour].append((ap / m - 1, mn / m - 1, mx / m - 1, last / m - 1))

    rows = []
    for h in range(24):
        rel_avg = [x[0] for x in hour_buckets[h]]
        rel_min = [x[1] for x in hour_buckets[h]]
        rel_max = [x[2] for x in hour_buckets[h]]
        rows.append({
            "hour": h,
            "n": len(rel_avg),
            "rel_avg_mean": mean_of(rel_avg),
            "rel_avg_median": statistics.median(rel_avg) if rel_avg else 0.0,
            "rel_min_mean": mean_of(rel_min),
            "rel_max_mean": mean_of(rel_max),
            "spread_hourly_mean": mean_of([x[2] - x[1] for x in hour_buckets[h]]),
        })

    best_buy_hour = min(rows, key=lambda r: r["rel_avg_mean"])
    best_sell_hour = max(rows, key=lambda r: r["rel_avg_mean"])

    # ---- daily-cycle range (per item per day) ----
    daily_ranges = []            # (item, day, low_avg, high_avg, range_pct)
    daily_extreme_ranges = []    # using min/max fields
    cons_by_day = defaultdict(list)   # day -> [1/0 ...] 当日各子弹「买入时点更便宜」的计数
    bh, sh = best_buy_hour["hour"], best_sell_hour["hour"]

    for name, pts in series.items():
        by_day = defaultdict(list)
        for dt, ap, mn, mx, last in pts:
            by_day[dt.date()].append((dt.hour, ap, mn, mx))
        for day, vals in by_day.items():
            if len(vals) < 8:   # need decent coverage of the day
                continue
            lows = [v[2] for v in vals]
            highs = [v[3] for v in vals]
            lo_avg = min(v[1] for v in vals)
            hi_avg = max(v[1] for v in vals)
            daily_ranges.append((name, day, lo_avg, hi_avg, hi_avg / lo_avg - 1))
            daily_extreme_ranges.append((name, day, min(lows), max(highs),
                                         max(highs) / min(lows) - 1))
            b = [v[1] for v in vals if v[0] == bh]
            s = [v[1] for v in vals if v[0] == sh]
            if b and s:
                cons_by_day[day].append(1 if s[0] > b[0] else 0)

    consistency = [x for xs in cons_by_day.values() for x in xs]
    consistency_rate = mean_of(consistency)

    # ---- 按「日」聚类：55 颗子弹同一天高度相关，有效样本 ≈ 天数而不是 子弹×日 ----
    day_hour = defaultdict(lambda: defaultdict(list))
    for name, pts in series.items():
        m = item_stats[name]["mean"]
        for dt, ap, mn, mx, last in pts:
            day_hour[dt.date()][dt.hour].append(ap / m - 1)
    days = sorted(day_hour)
    day_hour_mean = {d: {h: statistics.mean(v) for h, v in hs.items()}
                     for d, hs in day_hour.items()}

    boot = None
    if args.bootstrap > 0 and days:
        rng = random.Random(args.seed)
        spreads, hit_buy, hit_sell, cons_rates = [], [], [], []
        for _ in range(args.bootstrap):
            pick = [days[rng.randrange(len(days))] for _ in range(len(days))]
            prof = []
            for h in range(24):
                vals = [day_hour_mean[d][h] for d in pick if h in day_hour_mean[d]]
                prof.append(mean_of(vals))
            b = min(range(24), key=lambda h: prof[h])
            s = max(range(24), key=lambda h: prof[h])
            spreads.append(prof[s] - prof[b])
            hit_buy.append(1 if b == bh else 0)
            hit_sell.append(1 if s == sh else 0)
            cons_rates.append(mean_of([x for d in pick for x in cons_by_day.get(d, [])]))

        def ci(xs):
            xs = sorted(xs)
            return xs[int(0.025 * len(xs))], xs[int(0.975 * len(xs)) - 1]

        boot = {
            "spread": ci(spreads),
            "hit_buy": mean_of(hit_buy),
            "hit_sell": mean_of(hit_sell),
            "cons": ci([c for c in cons_rates if c is not None]),
        }
        print(f"bootstrap x{args.bootstrap} over {len(days)} days done")

    # ---- fee scenarios ----
    fees = [0.10, 0.13, 0.15]
    # A: 买入小时均价 → 卖出小时均价（可在盘口挂单等成交）
    # B: 买入小时最低 → 卖出小时最高（完美预判，仅作套利上限参考，不可执行）
    strat = {}
    for label, buy_fn, sell_fn in [
        ("A.avg-to-avg", lambda x: x[1], lambda x: x[1]),
        ("B.min-to-max", lambda x: x[2], lambda x: x[3]),
    ]:
        cycle_rets = defaultdict(list)
        for name, pts in series.items():
            by_day = defaultdict(list)
            for dt, ap, mn, mx, last in pts:
                by_day[dt.date()].append((dt.hour, ap, mn, mx, last))
            for day, vals in by_day.items():
                b = [v for v in vals if v[0] == bh]
                s = [v for v in vals if v[0] == sh]
                if not b or not s:
                    continue
                buy_p = buy_fn(b[0])
                sell_p = sell_fn(s[0])
                if buy_p <= 0:
                    continue
                gross = sell_p / buy_p - 1
                for f in fees:
                    cycle_rets[f].append(gross - f)
        strat[label] = {f: (mean_of(v), len(v)) for f, v in cycle_rets.items()}

    # ---- report ----
    L = []
    L.append("# 三角洲行动 子弹 日内(24小时)买卖时机分析报告")
    L.append("")
    L.append("- 数据源: moligod.com `/api/market/history`（北京时间 UTC+8，逐小时 avg/min/max/last）")
    L.append(f"- 窗口: {min(v['first'] for v in item_stats.values()):%m-%d %H:%M}"
             f" ~ {max(v['last'] for v in item_stats.values()):%m-%d %H:%M}（约 30 天）")
    L.append(f"- 样本: {len(series)} 种子弹 × 逐小时点（每种子弹至少 {args.min_days} 天完整数据）")
    L.append("- 说明: 相对价格 = 该小时价格 / 该子弹 30 天均值 - 1（消除子弹间价格水平差异）")
    L.append("")
    L.append("## 1. 日内价格形态（各小时相对均值偏离，%）")
    L.append("")
    L.append("| 小时 | 样本数 | 均值偏离% | 中位数偏离% | 小时均值偏离% | 小时极值偏离% |")
    L.append("|---|---|---|---|---|---|")
    for r in sorted(rows, key=lambda x: x["hour"]):
        L.append(f"| {r['hour']:02d}:00 | {r['n']} | {r['rel_avg_mean']*100:+.2f} | "
                 f"{r['rel_avg_median']*100:+.2f} | {r['rel_min_mean']*100:+.2f} | "
                 f"{r['rel_max_mean']*100:+.2f} |")
    L.append("")
    L.append("## 2. 结论：最佳买卖时段")
    L.append("")
    L.append(f"- **最佳买入小时: {bh:02d}:00-{bh+1:02d}:00**（日内相对均价最低 {best_buy_hour['rel_avg_mean']*100:+.2f}%）")
    L.append(f"- **最佳卖出小时: {sh:02d}:00-{sh+1:02d}:00**（日内相对均价最高 {best_sell_hour['rel_avg_mean']*100:+.2f}%）")
    L.append(f"- 买卖价差（均值口径）: {(best_sell_hour['rel_avg_mean']-best_buy_hour['rel_avg_mean'])*100:.2f} 个百分点")
    L.append(f"- 日内规律稳定性: 在 {len(consistency)} 个「子弹×日」样本中，买入小时价格 < 卖出小时价格 的占比 **{consistency_rate*100:.1f}%**")
    L.append("")
    L.append("### 2.1 统计稳健性（按「日」聚类）")
    L.append("")
    L.append(f"- 有效样本 ≈ **{len(days)} 个交易日**：55 颗子弹同一天的价格高度相关，"
             f"上面 {len(consistency)} 个「子弹×日」不能当独立样本看。")
    if boot:
        L.append(f"- 按日 bootstrap（n={args.bootstrap}, seed={args.seed}）：买卖价差 95% 置信区间 "
                 f"**[{boot['spread'][0]*100:.2f}, {boot['spread'][1]*100:.2f}] 个百分点**")
        L.append(f"- 重采样中「最佳买入小时」仍是 {bh:02d}:00 的比例: {boot['hit_buy']*100:.1f}%；"
                 f"卖出 {sh:02d}:00: {boot['hit_sell']*100:.1f}%")
        L.append(f"- 日内规律一致性 95% 置信区间: [{boot['cons'][0]*100:.1f}%, {boot['cons'][1]*100:.1f}%]")
    else:
        L.append("- 已跳过 bootstrap（--bootstrap 0），上面的百分比没有置信区间。")
    L.append("")
    L.append("## 3. 日内波动空间（潜在套利上限）")
    L.append("")
    dr = [r[4] for r in daily_ranges]
    de = [r[4] for r in daily_extreme_ranges]
    L.append(f"- 同一子弹同日 小时均值最高/最低 平均差距: **{mean_of(dr)*100:.2f}%**（保守口径，按小时均值买/卖）")
    L.append(f"- 同日 小时极值(min/max) 平均差距: **{mean_of(de)*100:.2f}%**（激进口径，盯盘抢价）")
    L.append("- 注: 交易行卖出手续费最低 **10%**（按偏离基准价每 4% 加 0.1%），买入另有 3% 保证金占用")
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
    L.append("- **A.avg-to-avg** 是「能挂到均价」的口径，最接近固定时段硬买硬卖；")
    L.append("- **B.min-to-max** 用小时极值，等于事后知道底和顶，只能当年化上限看，不能当收益预期；")
    L.append("- 手续费随挂单价偏离基准价上浮，所以「卖到最高价」的实际费率高于 10%，B 行仍偏乐观。")
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
        L.append(f"| {name} | {st['mean']:.0f} | {mean_of(item_dr)*100:.2f}% | "
                 f"{mean_of(item_de)*100:.2f}% | {cur} |")

    report = "\n".join(L) + "\n"
    with open(os.path.join(out_dir, "intraday_report.md"), "w", encoding="utf-8") as f:
        f.write(report)
    print(report)

    # ---- CSV ----
    with open(os.path.join(out_dir, "intraday_hourly_stats.csv"), "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    # ---- chart ----
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(11, 5))
        hours = [r["hour"] for r in rows]
        ax.plot(hours, [r["rel_avg_mean"] * 100 for r in rows], marker="o", label="所有子弹平均")
        ax.axhline(0, color="gray", lw=0.8)
        ax.axvspan(bh, bh + 1, color="green", alpha=0.15, label=f"买入 {bh:02d}:00")
        ax.axvspan(sh, sh + 1, color="red", alpha=0.15, label=f"卖出 {sh:02d}:00")
        ax.set_xlabel("北京时间小时")
        ax.set_ylabel("相对 30 天均值偏离 (%)")
        ax.set_title("三角洲行动子弹日内价格形态（30 天平均，moligod.com 数据）")
        ax.grid(alpha=0.3)
        ax.legend()
        fig.tight_layout()
        fig.savefig(os.path.join(out_dir, "intraday_curve.png"), dpi=130)
        print("chart saved")
    except Exception as e:
        print("chart failed:", e)

    return 0


if __name__ == "__main__":
    sys.exit(main())
