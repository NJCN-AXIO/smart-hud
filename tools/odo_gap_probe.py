# -*- coding: utf-8 -*-
"""v1.0.53 补充分析：把"APP 总里程比仪表盘少 9.19km"这件事拆开看。

用法: python tools/odo_gap_probe.py D:\\下载\\raw_20261119_*.csv ...

输出：
  1) 每份日志的首尾 odo / 首尾时刻
  2) ODO_ALIGN / walk gate / 断链 / 服务重启 等事件的完整清单
  3) 里程"该涨没涨"的三类漏记：走路闸门跳过、断链期(km 不涨但 spd 有数)、GPS 断流
  4) ∫|spd|dt（速度积分路径）与 odo 增量对照
"""
import csv
import glob
import io
import os
import re
import sys
from datetime import datetime

REC_RE = re.compile(r"(\d\d):(\d\d):(\d\d)\s+(.*)")
KV_RE = re.compile(r"(\w+)=([^\s]+)")


def fnum(x):
    try:
        return float(x)
    except Exception:
        return float("nan")


def parse(path):
    recs, syss = [], []
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        for row in csv.reader(f):
            if len(row) < 2:
                continue
            if row[1] == "REC" and len(row) > 2:
                m = REC_RE.match(row[2])
                kv = {}
                if m:
                    for k, v in KV_RE.findall(m.group(4)):
                        kv[k] = v
                kv["_t"] = int(fnum(row[0]))
                kv["_hm"] = row[2][:8]
                recs.append(kv)
            elif row[1] == "SYS" and len(row) > 2:
                syss.append((int(fnum(row[0]),) if False else int(float(row[0])), row[2]))
    return recs, syss


def main():
    files = sys.argv[1:]
    if not files:
        files = sorted(glob.glob(r"D:\下载\raw_2026111*.csv")) + \
            sorted(glob.glob(r"D:\下载\raw_20261120_*.csv"))
    total_odo = 0.0
    total_skip = 0.0
    total_int = 0.0
    total_frozen_km = 0.0

    for path in files:
        recs, syss = parse(path)
        if not recs:
            continue
        name = os.path.basename(path)
        t0, t1 = recs[0]["_t"], recs[-1]["_t"]
        print("=" * 78)
        print("%s  %s -> %s  REC=%d" % (
            name,
            datetime.fromtimestamp(t0 / 1000).strftime("%m-%d %H:%M:%S"),
            datetime.fromtimestamp(t1 / 1000).strftime("%m-%d %H:%M:%S"),
            len(recs)))

        odo_a = fnum(recs[0].get("odo", "nan"))
        odo_b = fnum(recs[-1].get("odo", "nan"))
        if odo_a == odo_a and odo_b == odo_b:
            print("  odo %.1f -> %.1f  (+%.2f km)" % (odo_a, odo_b, odo_b - odo_a))
            total_odo += odo_b - odo_a

        # 速度积分路径：∫ spd dt（spd 为 -1/NaN 时不计）
        integ = 0.0
        prev_t = None
        prev_spd = None
        for r in recs:
            spd = fnum(r.get("spd", "nan"))
            t = r["_t"]
            if prev_t is not None and prev_spd is not None and spd == spd and spd >= 0:
                dt = (t - prev_t) / 1000.0
                if 0 < dt <= 5:
                    integ += spd / 3600.0 * dt
            prev_t, prev_spd = t, spd
        total_int += integ
        print("  ∫spd·dt = %.2f km" % integ)

        # km（本次里程）累计：只统计"向上"的增量（忽略清零）
        ups = 0.0
        prev_km = None
        for r in recs:
            km = fnum(r.get("km", "nan"))
            if km == km and prev_km is not None and km > prev_km:
                ups += km - prev_km
            prev_km = km if km == km else prev_km
        print("  Σ km 正向增量 = %.2f km（本次里程被清零时会断，只当参考）" % ups)

        # 关键事件
        ev = [(t, s) for (t, s) in syss if
              ("ODO_ALIGN" in s or "walk gate" in s or "trip reset" in s or
               "full charge" in s or "service start" in s or "soc snap" in s)]
        for t, s in ev:
            print("  [%s] %s" % (datetime.fromtimestamp(t / 1000).strftime("%H:%M:%S"), s))

    print("=" * 78)
    print("六份合计：odo +%.2f km / ∫spd·dt %.2f km" % (total_odo, total_int))


if __name__ == "__main__":
    main()
