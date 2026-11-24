"""11-24 下午到晚上六段日志合并复盘（半电起骑到彻底没电 + 推车 3km）。

用法: python tools/review_1124_evening.py
"""
import csv, re, statistics
from datetime import datetime
from collections import Counter

FILES = [
    r"D:\下载\raw_20261124_142534.csv",
    r"D:\下载\raw_20261124_153033.csv",
    r"D:\下载\raw_20261124_154537.csv",
    r"D:\下载\raw_20261124_155537.csv",
    r"D:\下载\raw_20261124_160134.csv",
    r"D:\下载\raw_20261124_161422.csv",
]

def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def f(x, d=float("nan")):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return d
    return d if v != v else v

def ts(t):
    return datetime.fromtimestamp(int(t) / 1000).strftime("%H:%M:%S")

recs, sysl, rats, reads, connects, disco = [], [], [], {}, [], []
for path in FILES:
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3 or not r[0].isdigit():
                continue
            t, kind = int(r[0]), r[1]
            if kind == "REC":
                recs.append((t, kv(r[2])))
            elif kind == "SYS":
                sysl.append((t, r[2]))
            elif kind == "GAT":
                a, rest = r[2], " ".join(x for x in r[3:] if x)
                if a.startswith("READ "):
                    reads.setdefault(a, []).append((t, rest))
                elif a == "CONNECT":
                    connects.append(t)
                elif a == "DISCONNECTED":
                    disco.append((t, rest))
                else:
                    rats.append((t, a, rest))

recs.sort(key=lambda x: x[0])
sysl.sort(key=lambda x: x[0])
print("=" * 96)
print("合并日志: REC=%d SYS=%d CONNECT=%d DISC=%d" % (len(recs), len(sysl), len(connects), len(disco)))
if recs:
    print("总跨度: %s -> %s  (%.1f min)" % (ts(recs[0][0]), ts(recs[-1][0]), (recs[-1][0]-recs[0][0])/60000))

# ---------- 逐条轨迹（每 5 分钟取一个样本） ----------
print("\n-- 轨迹采样（每 5 分钟一条）--")
def num(p, k): return f(p.get(k))
last_shot = 0
for t, p in recs:
    if t - last_shot >= 300_000:
        last_shot = t
        print("  %s v=%6s soc=%3s sra=%3s spd=%5s km=%6s gkm=%6s odo=%7s sc=%.3f src=%s whc=%s" % (
            ts(t), p.get("v"), p.get("soc"), p.get("sra"), p.get("spd"),
            p.get("km"), p.get("gkm"), p.get("odo"), f(p.get("sc"), 0), p.get("src"), p.get("whc")))

# ---------- 静置 episode ----------
print("\n-- 静置 episode（spd≤2, thr≤1.5, sag≤0.6, v 有效）--")
rest_ep, cur = [], None
for t, p in recs:
    spd, thr, sag, v = num(p, "spd"), num(p, "thr"), num(p, "sag"), num(p, "v")
    ok = spd == spd and thr == thr and sag == sag and spd <= 2 and thr <= 1.5 and sag <= 0.6 and v == v
    if ok:
        if cur is None:
            cur = {"t0": t, "rows": []}
        cur["rows"].append((t, p))
    else:
        if cur is not None:
            rest_ep.append(cur); cur = None
if cur is not None:
    rest_ep.append(cur)

for ep in rest_ep:
    rows = ep["rows"]
    if len(rows) < 3:
        continue
    vs = [num(p, "v") for _, p in rows]
    socs = [num(p, "soc") for _, p in rows]
    pras = [num(p, "sra") for _, p in rows if num(p, "sra") == num(p, "sra")]
    kms = [num(p, "km") for _, p in rows]
    odos = [num(p, "odo") for _, p in rows]
    gkms = [num(p, "gkm") for _, p in rows if num(p, "gkm") == num(p, "gkm")]
    print("  %s~%s n=%3d v=%.2f(med) soc=%.0f sra=%s km=%.2f->%.2f odo=%.1f gkm=%.2f" % (
        ts(rows[0][0]), ts(rows[-1][0]), len(rows), statistics.median(vs),
        statistics.median(socs),
        ("%.0f" % statistics.median(pras)) if pras else "-",
        kms[0], kms[-1], odos[-1], gkms[-1] if gkms else float("nan")))

# ---------- 骑行 episode ----------
print("\n-- 骑行 episode（spd≥2）--")
ride_ep, cur = [], None
for t, p in recs:
    spd = num(p, "spd")
    if spd == spd and spd >= 2:
        if cur is None:
            cur = {"t0": t, "rows": []}
        cur["rows"].append((t, p))
    else:
        if cur is not None:
            ride_ep.append(cur); cur = None
if cur is not None:
    ride_ep.append(cur)

for ep in ride_ep:
    rows = ep["rows"]
    vs = [num(p, "v") for _, p in rows if num(p, "v") == num(p, "v")]
    socs = [num(p, "soc") for _, p in rows]
    kms = [num(p, "km") for _, p in rows]
    gkms = [num(p, "gkm") for _, p in rows if num(p, "gkm") == num(p, "gkm")]
    spds = [num(p, "spd") for _, p in rows]
    print("  %s~%s n=%4d spd峰=%.0f v=%.1f~%.1f(med %.1f) soc=%.0f->%.0f km=%.2f->%.2f gkm=%.2f" % (
        ts(rows[0][0]), ts(rows[-1][0]), len(rows), max(spds),
        min(vs) if vs else float("nan"), max(vs) if vs else float("nan"),
        statistics.median(vs) if vs else float("nan"),
        socs[0], socs[-1], kms[0], kms[-1], gkms[-1] if gkms else float("nan")))

# ---------- 关键事件 ----------
print("\n-- 关键 SYS 事件 --")
KEY = ("align", "scale", "anchored", "rest cal", "calibrate", "low power", "EMPTY", "empty",
       "range implied", "est_range", "trip summary", "trip reset", "trip set", "learned",
       "link lost", "parked", "moving", "soc:", "hard", "energy", "nudge", "suspect",
       "ODO_SCALE", "anchor")
for t, s in sysl:
    if any(k in s for k in KEY):
        print("  [%s] %s" % (ts(t), s[:150]))

# ---------- 末段 ----------
print("\n-- 末段 30 条快照 --")
for t, p in recs[-30:]:
    print("  %s v=%6s soc=%3s sra=%3s spd=%5s km=%6s gkm=%6s odo=%7s src=%s" % (
        ts(t), p.get("v"), p.get("soc"), p.get("sra"), p.get("spd"),
        p.get("km"), p.get("gkm"), p.get("odo"), p.get("src")))
