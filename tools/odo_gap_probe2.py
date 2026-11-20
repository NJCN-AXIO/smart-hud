# -*- coding: utf-8 -*-
"""v1.0.53 补充分析 2：每条日志的 GPS 口径 vs 速度积分口径 vs 真实（仪表）口径。

回答一个问题：那 20 km 到底丢在哪一类里 —— ①App 根本没跑 ②跑了但 GPS 没点 ③点了但被闸门/NaN 吃掉。
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
    recs, syss, head = [], [], None
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        for row in csv.reader(f):
            if len(row) < 2:
                continue
            if row[1] == "REC" and len(row) > 2:
                m = REC_RE.match(row[2])
                kv = {"_t": int(float(row[0])), "_hm": row[2][:8]}
                if m:
                    for k, v in KV_RE.findall(m.group(4)):
                        kv[k] = v
                recs.append(kv)
            elif row[1] == "SYS" and len(row) > 2:
                if "build v" in row[2]:
                    head = row[2]
                syss.append((int(float(row[0])), row[2]))
    return recs, syss, head


def main():
    files = sys.argv[1:] or (sorted(glob.glob(r"D:\下载\raw_2026111*.csv")) +
                             sorted(glob.glob(r"D:\下载\raw_20261120_*.csv")))
    print("%-26s %8s %8s %8s %6s %6s %6s %5s" % (
        "file", "gkm", "∫spd", "odo+", "nan%", "src4%", "gkm落", "frz"))
    for path in files:
        recs, syss, head = parse(path)
        if not recs:
            continue
        gkm_a = fnum(recs[0].get("gkm", "nan"))
        gkm_b = fnum(recs[-1].get("gkm", "nan"))
        gkm = gkm_b - gkm_a if (gkm_a == gkm_a and gkm_b == gkm_b) else float("nan")
        odo_a = fnum(recs[0].get("odo", "nan"))
        odo_b = fnum(recs[-1].get("odo", "nan"))
        odod = odo_b - odo_a if (odo_a == odo_a and odo_b == odo_b) else float("nan")

        integ, prev_t, prev_spd = 0.0, None, None
        nan_n, src4_n, gkm_nan_n = 0, 0, 0
        for r in recs:
            spd = fnum(r.get("spd", "nan"))
            if spd != spd or spd < 0:
                nan_n += 1
            if r.get("src") == "4":
                src4_n += 1
            g = fnum(r.get("gkm", "nan"))
            if g != g:
                gkm_nan_n += 1
            t = r["_t"]
            if prev_t is not None and spd == spd and spd >= 0:
                dt = (t - prev_t) / 1000.0
                if 0 < dt <= 5:
                    integ += spd / 3600.0 * dt
            prev_t, prev_spd = t, spd
        n = float(len(recs))
        print("%-26s %8.2f %8.2f %8.2f %5.0f%% %5.0f%% %6.0f" % (
            os.path.basename(path), gkm, integ, odod,
            100 * nan_n / n, 100 * src4_n / n, gkm_nan_n))
    print("gkm=过滤后 GPS 位移累计  ∫spd=速度×时间  odo+=显示总里程增量  nan%=无有效时速  "
          "src4%=蓝牙断开帧占比")


if __name__ == "__main__":
    main()
