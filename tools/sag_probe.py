#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.49：用实车日志估"真实压降"，判断 LoadComp 是不是补过头。

思路：同一段路（30 秒窗）里，端电压的最小值出现在重载帧、最大值出现在松油门帧。
      两个值之差 ≈ 负载压降差，可以直接和 LoadComp.sagOf 的预测值对比。
用法： python tools/sag_probe.py <csv> [窗口秒数]
"""
import re
import sys
from collections import OrderedDict

NUM = re.compile(r"(\w+)=([^ ]+)")


def parse(line):
    if ",REC," not in line:
        return None
    d = {}
    for k, v in NUM.findall(line.split(",REC,", 1)[1]):
        d[k] = v
    return d


def fl(d, k):
    try:
        return float(d.get(k, "nan"))
    except Exception:
        return float("nan")


def main(paths, win=30):
    for path in paths:
        buckets = OrderedDict()
        for line in open(path, encoding="utf-8", errors="ignore"):
            d = parse(line)
            if not d or d.get("src") != "1":
                continue
            ts = int(line.split(",", 1)[0])
            key = ts // (win * 1000)
            rv, thr, spd = fl(d, "rv"), fl(d, "thr"), fl(d, "spd")
            if rv != rv:
                continue
            b = buckets.setdefault(key, {"rv": [], "thr": [], "spd": [], "sag": []})
            b["rv"].append(rv)
            if thr == thr:
                b["thr"].append(thr)
            if spd == spd:
                b["spd"].append(spd)
            s = fl(d, "sag")
            if s == s:
                b["sag"].append(s)
        print(f"== {path.split(chr(92))[-1]}  （{win}s 窗，只取实时帧）")
        rows = 0
        for k, b in buckets.items():
            if len(b["rv"]) < 10 or not b["spd"] or not b["thr"]:
                continue
            hi, lo = max(b["rv"]), min(b["rv"])
            sag_hi = max(b["sag"]) if b["sag"] else float("nan")
            sag_lo = min(b["sag"]) if b["sag"] else float("nan")
            spd_hi, spd_lo = max(b["spd"]), min(b["spd"])
            thr_hi, thr_lo = max(b["thr"]), min(b["thr"])
            # 只在"确实有负载摆动"的窗口里看（端电压差 >= 1.0V）
            if hi - lo >= 1.0:
                print(f"  rv {lo:.1f}..{hi:.1f} (Δ{hi - lo:.1f})  "
                      f"sag {sag_lo:.1f}..{sag_hi:.1f}  "
                      f"thr {thr_lo:.2f}..{thr_hi:.2f}  spd {spd_lo:.0f}..{spd_hi:.0f}")
                rows += 1
                if rows >= 12:
                    break
        print()


if __name__ == "__main__":
    sec = int(sys.argv[-1]) if sys.argv[-1].isdigit() else 30
    main([a for a in sys.argv[1:] if not a.isdigit()], sec)
