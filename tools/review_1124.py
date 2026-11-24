"""11-21 晚 → 11-24 早 这批日志的批量复盘。

输出每份日志：起止时间、REC 条数、关键事件（满电锚点/对表/比例学习/静置校准/低电/空电锁）、
首末两条 REC 快照；最后汇总每箱电的里程与电量轨迹。
"""
import csv, re, os
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261121_194454.csv",
    r"D:\下载\raw_20261122_085137.csv",
    r"D:\下载\raw_20261122_110342.csv",
    r"D:\下载\raw_20261122_123014.csv",
    r"D:\下载\raw_20261122_205727.csv",
    r"D:\下载\raw_20261123_094352.csv",
    r"D:\下载\raw_20261123_150408.csv",
    r"D:\下载\raw_20261124_075554.csv",
    r"D:\下载\raw_20261124_075824.csv",
]

EVENT_KEYS = ("boot", "full", "charge", "ODO", "rest cal", "anchor", "skipped", "low power",
              "EMPTY", "per-km", "range implied", "est_range", "gap since", "gap warn",
              "suspect", "learned", "rebuild", "link lost", "walk gate", "soc stale")


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


def snap(p):
    return "v=%s soc=%s rng=%s km=%s odo=%s sc=%s spd=%s src=%s" % (
        p.get("v"), p.get("soc"), p.get("rng"), p.get("km"), p.get("odo"),
        p.get("sc"), p.get("spd"), p.get("src"))


for path in FILES:
    if not os.path.exists(path):
        print("!! missing", path)
        continue
    recs, sysl = [], []
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3 or not r[0].isdigit():
                continue
            t = int(r[0])
            (sysl if r[1] in ("SYS", "SUM") else recs).append((t, r[1], r[2]))
    print("=" * 96)
    print(os.path.basename(path), " REC=", len(recs))
    if not recs:
        for t, k, s in sysl:
            print("   [%s] %s" % (d(t)[6:], s[:150]))
        continue
    print("  %s -> %s" % (d(recs[0][0]), d(recs[-1][0])))
    print("  FIRST %s" % snap(kv(recs[0][2])))
    print("  LAST  %s" % snap(kv(recs[-1][2])))
    for t, k, s in sysl:
        if any(x in s for x in EVENT_KEYS):
            print("   [%s] %s" % (d(t)[6:], s[:150]))
