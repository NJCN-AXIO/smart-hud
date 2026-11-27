"""探测 11-25 / 11-26 各次开机前后 3 分钟的电压序列，用来核对 v1.0.67 的"连续 8 秒 ≥78.4V"门槛是否挡得住误判。

用法: python tools/probe_boot_full.py
"""
import csv, re
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261125_080147.csv",
    r"D:\下载\raw_20261125_120654.csv",
    r"D:\下载\raw_20261125_141608.csv",
    r"D:\下载\raw_20261126_102042.csv",
    r"D:\下载\raw_20261126_140246.csv",
    r"D:\下载\raw_20261126_165923.csv",
]

def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def fnum(x):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return None if v != v else v

def ts(t):
    return datetime.fromtimestamp(int(t) / 1000).strftime("%m-%d %H:%M:%S")

recs, sysl = [], []
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
recs.sort(key=lambda x: x[0])
sysl.sort(key=lambda x: x[0])

# 1) 所有 SYS 里的 build / restored / full / boot
print("== 开机与满电相关 SYS ==")
for t, s in sysl:
    if any(k in s for k in ("boot ->", "build v", "restored last", "full anchor", "boot full",
                            "EMPTY LATCH", "trip reset", "trip set")):
        print(" [%s] %s" % (ts(t), s[:150]))

# 2) 每个开机点前 120s 到后 180s 的电压序列（只打 ≥76V 的）
print("\n== 每次开机窗口内 >=76V 的帧 ==")
boots = [t for t, s in sysl if "boot ->" in s]
for b in boots:
    rows = [(t, p) for t, p in recs if b - 120_000 <= t <= b + 180_000]
    hit = []
    for t, p in rows:
        v = fnum(p.get("v"))
        if v is not None and v >= 76:
            hit.append((t, v, p.get("src"), p.get("soc"), p.get("km")))
    print("\n boot @ %s  窗口帧=%d  命中>=76V=%d" % (ts(b), len(rows), len(hit)))
    if hit:
        # 连续段统计
        segs, cur = [], None
        for t, v, src, soc, km in hit:
            if cur is None or t - cur[-1][0] > 12_000:
                if cur:
                    segs.append(cur)
                cur = [(t, v, src, soc, km)]
            else:
                cur.append((t, v, src, soc, km))
        if cur:
            segs.append(cur)
        for seg in segs:
            dur = (seg[-1][0] - seg[0][0]) / 1000.0
            mx = max(x[1] for x in seg)
            print("   段 %s~%s  帧=%2d 时长=%5.1fs  最高=%.2fV  末帧soc=%s km=%s" % (
                ts(seg[0][0])[-8:], ts(seg[-1][0])[-8:], len(seg), dur, mx, seg[-1][3], seg[-1][4]))
