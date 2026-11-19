# -*- coding: utf-8 -*-
"""从原始 67B 帧里拟合"转把电压 → 压降"，替掉手调系数。

思路（和 BMS 里"内阻压降 = V_ocv - V_term"一个口径）：
  1) 每份日志里，取"怠速帧"（转把 ≈ 0.8V，车基本没在出力）的电压 90 分位当这一段路的 OCV 基线；
  2) 其余帧算 delta = 基线 - 本帧电压（>0 表示被负载压下去多少）；
  3) 按转把电压分桶，看 delta 随转把怎么长 —— 这条曲线就是 k 的来历。

用法： python tools/fit_sag.py "D:\下载\raw_20261117_*.csv"
"""
import csv
import glob
import statistics
import sys


def decode(hexs):
    raw = bytes.fromhex(hexs.replace(" ", ""))
    if len(raw) != 67 or raw[66] != 0xCC:
        return None
    f = list(raw)
    for e in range(4, 66):
        f[e] -= 11
        f[e] = 85 ^ f[e]
        f[e] -= e
        f[e] = 19 ^ f[e]
        f[e] -= e
    v = (256 * f[63] + f[64]) / 100.0
    thr = 0.019 * f[42]
    return v, thr


def frames(path):
    out = []
    with open(path, "r", encoding="utf-8", errors="ignore") as fh:
        for row in csv.reader(fh):
            if len(row) < 3:
                continue
            if row[1] != "FRM":
                continue
            d = decode(row[3])
            if d:
                out.append(d)
    return out


def pct(vals, q):
    if not vals:
        return None
    s = sorted(vals)
    i = min(len(s) - 1, max(0, int(q * (len(s) - 1))))
    return s[i]


def main():
    files = []
    for pat in sys.argv[1:]:
        files.extend(glob.glob(pat))
    if not files:
        print("没有匹配到日志")
        return
    print("file                       n   ocv    thr_p90  v_min")
    all_pts = []
    for p in sorted(files):
        fs = frames(p)
        if len(fs) < 30:
            continue
        v_idle = [v for v, t in fs if t < 0.9]
        ocv = pct(v_idle, 0.9) if v_idle else pct([v for v, _ in fs], 0.9)
        if ocv is None:
            continue
        all_pts.append((p, ocv, fs))
        print("%-24s %4d %6.2f  %5.2f   %6.2f" % (
            p.split("\\")[-1][:24], len(fs), ocv,
            pct([t for _, t in fs], 0.9), min(v for v, _ in fs)))

    print()
    print("转把桶   样本   delta中位   delta90   占比")
    buckets = {}
    for _, ocv, fs in all_pts:
        for v, t in fs:
            if t <= 0.0 or t > 6.0:
                continue
            b = round(t * 10) / 10.0
            buckets.setdefault(b, []).append(ocv - v)
    for b in sorted(buckets):
        d = buckets[b]
        if len(d) < 30:
            continue
        print("%.1fV    %5d   %7.2f   %7.2f  %5.1f%%" % (
            b, len(d), statistics.median(d), pct(d, 0.9),
            100.0 * len(d) / sum(len(x) for x in buckets.values())))


if __name__ == "__main__":
    main()
