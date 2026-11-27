"""11-26 上午（10:20~12:10）：电量是被什么抬到 94 的？

用法: python tools/probe_1126_am.py
"""
import csv, re
from datetime import datetime

PATH = r"D:\下载\raw_20261126_102042.csv"

KEY = ("anchor", "nudge", "reject", "SOC ", "soc snap", "rest cal", "charge anchor",
       "learned", "per-km", "est_range", "trip set", "recharge", "volt suspect", "stale rest")

def ts(t):
    return datetime.fromtimestamp(int(t) / 1000).strftime("%H:%M:%S")

with open(PATH, newline="", encoding="utf-8", errors="ignore") as fh:
    for r in csv.reader(fh):
        if len(r) < 3 or not r[0].isdigit():
            continue
        hh = ts(int(r[0]))
        if not ("10:20:00" <= hh <= "12:10:00"):
            continue
        if r[1] != "SYS":
            continue
        s = r[2]
        if any(k in s for k in KEY):
            print("[%s] %s" % (hh, s[:160]))
