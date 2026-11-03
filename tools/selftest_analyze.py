#!/usr/bin/env python3
"""合成数据自检：不碰真实接口，验证 analyze_intraday.py 的结论和口径。

构造 3 颗子弹 × 20 天，价格 = 基准价 × (1 + 日内形态)，
形态在 07:00 最低（-4%）、16:00 最高（+5%），每小时 min/max = 均价 ×(1∓1%)。
于是可以算出应当得到的答案：

  A.avg-to-avg = (1.05 / 0.96 - 1) - 10% = -0.625%    ← 固定时段按均价买卖其实亏手续费
  旧版把卖出小时「最大值」当卖价 → (1.05×1.01 / 0.96 - 1) - 10% = +0.47%
所以断言 A ≈ -0.63% 同时覆盖了两个 bug：单位/口径。

用法: python tools/selftest_analyze.py
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "analyze_intraday.py")

# 日内形态（相对基准价的偏离），唯一最低 07:00 = -4%，唯一最高 16:00 = +5%
DIURNAL = {
    0: -0.036, 1: -0.033, 2: -0.032, 3: -0.031, 4: -0.032, 5: -0.035,
    6: -0.039, 7: -0.040, 8: -0.034, 9: -0.020, 10: -0.005, 11: 0.004,
    12: 0.010, 13: 0.025, 14: 0.041, 15: 0.049, 16: 0.050, 17: 0.043,
    18: 0.028, 19: 0.023, 20: 0.027, 21: 0.029, 22: 0.018, 23: -0.008,
}


def build_fixture(root, days=20, bullets=(("测试弹A", 500), ("测试弹B", 2000), ("测试弹C", 5000))):
    start = date(2026, 7, 1)
    for name, base in bullets:
        hist = []
        for d in range(days):
            day = start + timedelta(days=d)
            for h in range(24):
                avg = base * (1 + DIURNAL[h])
                hist.append({
                    "time": f"{day.month:02d}-{day.day:02d} {h:02d}:00",
                    "avg": round(avg, 2),
                    "min": round(avg * 0.99, 2),
                    "max": round(avg * 1.01, 2),
                    "last": round(avg, 2),
                })
        with open(os.path.join(root, f"{name}.json"), "w", encoding="utf-8") as f:
            json.dump({"name": name, "object_id": name, "current_price": base, "history": hist},
                      f, ensure_ascii=False)
    return days


def main():
    # 不用系统临时目录：部分环境（含收紧的沙箱）在 %TEMP% 下建子目录会被拒
    tmp = os.environ.get("DBA_SELFTEST_DIR") or os.path.join(
        os.path.dirname(HERE), ".selftest-tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)
    try:
        run_checks(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def run_checks(tmp):
    data_dir = os.path.join(tmp, "data")
    out_dir = os.path.join(tmp, "out")
    os.makedirs(data_dir)
    days = build_fixture(data_dir)
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    proc = subprocess.run(
        [sys.executable, SCRIPT, "--data-dir", data_dir, "--out-dir", out_dir,
         "--bootstrap", "200"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=env,
    )
    if proc.returncode != 0:
        print(proc.stdout[-3000:])
        print(proc.stderr[-3000:])
        raise SystemExit(f"analyze_intraday.py 退出码 {proc.returncode}")

    report = open(os.path.join(out_dir, "intraday_report.md"), encoding="utf-8").read()

    failures = []

    def check(cond, msg):
        print(("  PASS  " if cond else "  FAIL  ") + msg)
        if not cond:
            failures.append(msg)

    check("最佳买入小时: 07:00" in report, "最佳买入小时应识别为 07:00")
    check("最佳卖出小时: 16:00" in report, "最佳卖出小时应识别为 16:00")
    check("**100.0%**" in report, "合成数据里买入时点每天都更便宜 → 一致性应为 100%")

    row = next((ln for ln in report.splitlines() if ln.startswith("| A.avg-to-avg |")), None)
    check(row is not None, "报告里应有 A.avg-to-avg 这一行")
    if row:
        val = float(re.search(r"([+-][\d.]+)%", row).group(1))
        # 真实答案 -0.625%；旧实现（卖出取小时最大值）会给出 +0.47%
        check(abs(val + 0.625) <= 0.02,
              f"A.avg-to-avg 应为 -0.63% 左右，实测 {val:+.2f}%")
        check(val < -0.3, f"A 必须是「按均价买卖亏手续费」，实测 {val:+.2f}%（>0 说明又用上极值了）")

    check("95% 置信区间" in report, "应输出按日 bootstrap 的置信区间")
    check("有效样本" in report, "应说明有效样本是「日」而不是 子弹×日")

    if failures:
        raise SystemExit(f"自检失败 {len(failures)} 项")
    print(f"\n全部通过（合成 {days} 天数据）")


if __name__ == "__main__":
    main()
