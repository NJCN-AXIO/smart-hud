"""逐帧看 11-25 08:01:50 ~ 08:05 的电压，判断 v1.0.67 的"连续 8 秒 ≥78.4V"门槛能否挡住这次满电判定。

用法: python tools/probe_boot_detail.py
"""
import csv, re
from datetime import datetime

PATH = r"D:\下载\raw_20261125_080147.csv"
T0 = 1795  # placeholder

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

rows = []
with open(PATH, newline="", encoding="utf-8", errors="ignore") as fh:
    for r in csv.reader(fh):
        if len(r) < 3 or not r[0].isdigit():
            continue
        t, kind = int(r[0]), r[1]
        if kind == "REC":
            rows.append((t, kv(r[2])))
        elif kind == "SYS":
            pass

start = None
for t, p in rows:
    if ts(t) == "08:01:40":
        start = t
        break
if start is None:
    start = rows[0][0]

print(" %-8s %6s %4s %4s %6s %6s %5s" % ("time", "v", "src", "sag", "km", "odo", "soc"))
hi_run_start, hi_run_end, hi_frames = None, None, 0
for t, p in rows:
    if t < start or t > start + 300_000:
        continue
    v = fnum(p.get("v"))
    print(" %-8s %6s %4s %4s %6s %6s %5s" % (
        ts(t), ("%.2f" % v) if v is not None else "-", p.get("src", "-"),
        p.get("sag", "-"), p.get("km", "-"), p.get("odo", "-"), p.get("soc", "-")))
    if v is not None and v >= 78.4:
        if hi_run_start is None:
            hi_run_start = t
        hi_frames += 1
        hi_run_end = t
    else:
        if hi_run_start is not None:
            print("   -> 连续 >=78.4V: %s~%s  %d 帧  %.1fs" % (
                ts(hi_run_start), ts(hi_run_end), hi_frames, (hi_run_end - hi_run_start) / 1000.0))
        hi_run_start, hi_run_end, hi_frames = None, None, 0
if hi_run_start is not None:
    print("   -> 连续 >=78.4V: %s~%s  %d 帧  %.1fs" % (
        ts(hi_run_start), ts(hi_run_end), hi_frames, (hi_run_end - hi_run_start) / 1000.0))
