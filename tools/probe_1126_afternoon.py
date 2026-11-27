"""看 11-26 14:02~14:40 的电压与显示电量，核对"94% / 74.8V / 6km"是怎么形成的。

用法: python tools/probe_1126_afternoon.py
"""
import csv, re
from datetime import datetime

PATH = r"D:\下载\raw_20261126_140246.csv"

def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def fnum(x):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return None if v != v else v

def ts(t):
    return datetime.fromtimestamp(int(t) / 1000).strftime("%H:%M:%S")

recs, sysl = [], []
with open(PATH, newline="", encoding="utf-8", errors="ignore") as fh:
    for r in csv.reader(fh):
        if len(r) < 3 or not r[0].isdigit():
            continue
        t, kind = int(r[0]), r[1]
        if kind == "REC":
            recs.append((t, kv(r[2])))
        elif kind == "SYS":
            sysl.append((t, r[2]))

print("== SYS ==")
for t, s in sysl:
    print(" [%s] %s" % (ts(t), s[:160]))

print("\n== REC 每 30 秒（14:02~14:45）==")
print(" %-8s %7s %5s %4s %4s %6s %6s %5s %4s" % ("time", "rv", "v", "src", "sag", "km", "odo", "soc", "rng"))
last = 0
for t, p in recs:
    hh = ts(t)
    if not (hh >= "14:02:00" and hh <= "14:45:00"):
        continue
    if t - last >= 30_000:
        last = t
        print(" %-8s %7s %7s %4s %4s %6s %6s %5s %4s" % (
            hh, p.get("rv", "-"), p.get("v", "-"), p.get("src", "-"), p.get("sag", "-"),
            p.get("km", "-"), p.get("odo", "-"), p.get("soc", "-"), p.get("rng", "-")))
