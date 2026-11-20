import csv, re, sys, statistics
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261119_140938.csv",
    r"D:\下载\raw_20261119_141802.csv",
    r"D:\下载\raw_20261119_155237.csv",
    r"D:\下载\raw_20261119_172034.csv",
    r"D:\下载\raw_20261119_202834.csv",
    r"D:\下载\raw_20261120_001434.csv",
]

def parse_kv(s):
    out = {}
    for m in re.finditer(r"(\w+)=(\S+)", s or ""):
        out[m.group(1)] = m.group(2)
    return out

def fnum(x):
    try:
        v = float(x)
    except Exception:
        return None
    return None if v != v else v

for path in FILES:
    rows = []
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3:
                continue
            if r[1] != "REC":
                continue
            kv = parse_kv(r[2])
            t = int(r[0])
            rows.append((t, kv))
    print("=" * 78)
    print(path.split("\\")[-1], "REC=", len(rows))
    if not rows:
        continue
    t0, t1 = rows[0][0], rows[-1][0]
    print("  %s -> %s  (%.0f min)" % (
        datetime.fromtimestamp(t0 / 1000).strftime("%m-%d %H:%M:%S"),
        datetime.fromtimestamp(t1 / 1000).strftime("%m-%d %H:%M:%S"),
        (t1 - t0) / 60000))
    spds = [fnum(kv.get("spd")) for _, kv in rows]
    valid = [s for s in spds if s is not None]
    nan_n = sum(1 for s in spds if s is None)
    moving = [s for s in valid if s >= 1]
    print("  spd 有效 %d / %d  (NaN %d)   有效中 >=1km/h %d" % (len(valid), len(spds), nan_n, len(moving)))
    if valid:
        print("  spd 均值 %.1f   移动段均值 %.1f   最大 %.1f" % (
            statistics.mean(valid), statistics.mean(moving) if moving else float("nan"), max(valid)))
    src4 = sum(1 for _, kv in rows if kv.get("src") == "4")
    print("  src=4 占比 %.0f%%" % (100.0 * src4 / len(rows)))
    # 里程增长段：连续 km 增长区间的平均 spd
    km = [fnum(kv.get("km")) for _, kv in rows]
    segs = []
    a = None
    for i, v in enumerate(km):
        if v is None:
            continue
        if a is None:
            a = i
        elif v <= km[a] + 1e-6:
            a = i
    # 简化：统计"km 增长的那几秒"的 spd
    grew = []
    last = None
    for i, v in enumerate(km):
        if v is None:
            continue
        if last is not None and v > last + 0.005:
            s = fnum(rows[i][1].get("spd"))
            if s is not None:
                grew.append(s)
        last = v
    if grew:
        print("  km 增长秒数 %d，其 spd 均值 %.1f 中位 %.1f 最大 %.1f" % (
            len(grew), statistics.mean(grew), statistics.median(grew), max(grew)))
