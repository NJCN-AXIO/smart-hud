"""11-25 / 11-26 六份日志复盘：抽查静置点、续航口径、本箱里程、学习事件。

用户场景：不彻底充满，中途插电补电再骑；16:29 截图 74.8V 静置 / 94% / 本次 17.8km / 续航 ~6km。
用法: python tools/review_1125_26.py
"""
import csv, re, statistics
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261125_080147.csv",
    r"D:\下载\raw_20261125_120654.csv",
    r"D:\下载\raw_20261125_141608.csv",
    r"D:\下载\raw_20261126_102042.csv",
    r"D:\下载\raw_20261126_140246.csv",
    r"D:\下载\raw_20261126_165923.csv",
]

V_REF = [78.6, 77.3, 75.9, 74.9, 74.0, 73.3, 72.6, 72.1, 70.6, 66.3, 63.8]
SOC_REF = [100, 94, 87, 78, 70, 63, 50, 41, 28, 15, 0]

def soc_of(v):
    if v != v: return float("nan")
    if v >= V_REF[0]: return 100.0
    if v <= V_REF[-1]: return 0.0
    for i in range(len(V_REF) - 1):
        if V_REF[i + 1] <= v <= V_REF[i]:
            t = (v - V_REF[i + 1]) / (V_REF[i] - V_REF[i + 1])
            return SOC_REF[i + 1] + t * (SOC_REF[i] - SOC_REF[i + 1])
    return 0.0

def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def f(x, d=float("nan")):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return d
    return d if v != v else v

def ts(t): return datetime.fromtimestamp(int(t) / 1000).strftime("%m-%d %H:%M:%S")

recs, sysl = [], []
for path in FILES:
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3 or not r[0].isdigit(): continue
            t, kind = int(r[0]), r[1]
            if kind == "REC": recs.append((t, kv(r[2])))
            elif kind == "SYS": sysl.append((t, r[2]))
recs.sort(key=lambda x: x[0]); sysl.sort(key=lambda x: x[0])

print("REC=%d SYS=%d  跨度 %s -> %s" % (len(recs), len(sysl), ts(recs[0][0]), ts(recs[-1][0])))

print("\n== 每 10 分钟采样 ==\n %-16s %7s %4s %5s %6s %6s %6s %6s %3s" % (
    "time", "v", "soc", "chk", "km", "gkm", "odo", "rng", "er"))
last = 0
for t, p in recs:
    if t - last >= 600_000:
        last = t
        v = f(p.get("v"))
        print(" %-16s %7s %4s %5.0f %6s %6s %7s %6s %3s" % (
            ts(t), ("%.2f" % v) if v == v else "-", p.get("soc", "-"), soc_of(v),
            p.get("km", "-"), p.get("gkm", "-"), p.get("odo", "-"), p.get("rng", "-"), p.get("er", "-")))

print("\n== 长静置（≥90s，spd≤2/thr≤1.5/sag≤0.6）==\n %-16s %6s %5s %4s %6s %6s" % (
    "window", "v", "chk", "soc", "km", "rng"))
cur = None
def flush(cur):
    if not cur: return
    rows = cur["rows"]
    if (rows[-1][0] - cur["t0"]) < 90_000: return
    vs = [f(q.get("v")) for _, q in rows]
    vmed = statistics.median(vs)
    print(" %-16s %6.2f %5.1f %4s %6s %6s" % (
        ts(cur["t0"])[-11:] + "~" + ts(rows[-1][0])[-8:], vmed, soc_of(vmed),
        rows[-1][1].get("soc", "-"), rows[-1][1].get("km", "-"), rows[-1][1].get("rng", "-")))
for t, p in recs:
    spd, thr, sag, v = f(p.get("spd")), f(p.get("thr")), f(p.get("sag")), f(p.get("v"))
    ok = all(x == x for x in (spd, thr, sag)) and spd <= 2 and thr <= 1.5 and sag <= 0.6 and v == v
    if ok:
        if cur is None: cur = {"t0": t, "rows": []}
        cur["rows"].append((t, p))
    else:
        flush(cur); cur = None
flush(cur)

print("\n== 关键 SYS（锚点/学习/续航/满电/断电/补电）==")
KEY = ("anchor", "learned", "per-km", "implied", "full anchor", "boot full", "EMPTY", "empty",
       "est_range", "trip set", "trip reset", "soc snap", "volt suspect", "stale rest", "nudge",
       "rejects", "hard", "charge", "cutout", "align")
for t, s in sysl:
    if any(k in s for k in KEY):
        print(" [%s] %s" % (ts(t), s[:170]))
