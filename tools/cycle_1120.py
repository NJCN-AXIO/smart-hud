import csv, re, sys
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261120_141242.csv",
    r"D:\下载\raw_20261120_155927.csv",
    r"D:\下载\raw_20261121_104534.csv",
    r"D:\下载\raw_20261121_170916.csv",
    r"D:\下载\raw_20261121_180715.csv",
]

def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def f(x):
    try:
        v = float(x)
    except Exception:
        return None
    return None if v != v else v

def d(t):
    return datetime.fromtimestamp(t / 1000).strftime("%m-%d %H:%M:%S")

for path in FILES:
    recs, sysl = [], []
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3 or not r[0].isdigit():
                continue
            t = int(r[0])
            (sysl if r[1] in ("SYS", "SUM") else recs).append((t, r[1], r[2]))
    print("=" * 90)
    print(path.split("\\")[-1], " REC=", len(recs))
    if not recs:
        continue
    print("  %s -> %s" % (d(recs[0][0]), d(recs[-1][0])))
    # 关键 SYS
    keys = ("ODO", "boot", "full charge", "rest cal", "low power", "walk gate", "odo blind",
            "soc snap", "gap since", "calibrate", "anchor", "speed warn")
    for t, k, s in sysl:
        if any(x in s for x in keys):
            print("   [%s] %s" % (d(t)[6:], s[:130]))
    # 采样点：每 15 分钟一行
    last = None
    print("   ---- 采样（每 ~15 分钟）----")
    for i, (t, k, s) in enumerate(recs):
        if last is not None and t - last < 15 * 60 * 1000:
            continue
        last = t
        p = kv(s)
        print("   %s v=%s soc=%s rng=%s trip=%s odo=%s sc=%s sag=%s spd=%s"
              % (d(t)[6:], p.get("v"), p.get("soc"), p.get("rng"), p.get("km"),
                 p.get("odo"), p.get("sc"), p.get("sag"), p.get("spd")))
