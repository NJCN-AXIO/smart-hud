import csv, re, statistics
from datetime import datetime
from collections import Counter

PATH = r"D:\下载\raw_20261120_001434.csv"

def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def fnum(x):
    try:
        v = float(x)
    except Exception:
        return None
    return None if v != v else v

syslines = []
recs = []
with open(PATH, newline="", encoding="utf-8", errors="ignore") as fh:
    for r in csv.reader(fh):
        if len(r) < 3 or not r[0].isdigit():
            continue
        t = int(r[0])
        if r[1] == "SYS":
            syslines.append((t, r[2]))
        elif r[1] == "REC":
            recs.append((t, kv(r[2])))

print("SYS 行数", len(syslines), "  REC 行数", len(recs))
print("\n--- SYS 行（前 40 条）---")
for t, s in syslines[:40]:
    print(datetime.fromtimestamp(t / 1000).strftime("%H:%M:%S"), s[:150])

print("\n--- gps rejected 统计（ok / badacc / jitter / jump）---")
acc = Counter()
n = 0
for t, s in syslines:
    if "gps rejected" in s:
        n += 1
        d = kv(s.split("gps rejected")[1])
        for k in ("jitter", "badacc", "jump", "ok"):
            acc[k] += int(float(d.get(k, 0) or 0))
print("次数", n, dict(acc))

print("\n--- 定位间隔分布（秒）---")
gaps = Counter()
for i in range(1, len(recs)):
    dt = (recs[i][0] - recs[i - 1][0]) / 1000.0
    gaps[round(dt)] += 1
for k in sorted(gaps):
    if gaps[k] >= 10:
        print("  %2ds: %d 次" % (k, gaps[k]))

print("\n--- 每分钟：里程增量 / 平均速度 / 平均精度 ---")
cur = None
rows = []
for t, d in recs:
    m = datetime.fromtimestamp(t / 1000).strftime("%H:%M")
    if m != cur:
        cur = m
        rows.append([m, [], [], [], []])
    rows[-1][1].append(fnum(d.get("km")))
    rows[-1][2].append(fnum(d.get("spd")))
    rows[-1][3].append(fnum(d.get("v")))
    rows[-1][4].append(fnum(d.get("sag")))
for m, km, spd, v, sag in rows:
    k0 = next((x for x in km if x is not None), None)
    k1 = next((x for x in reversed(km) if x is not None), None)
    so = [x for x in spd if x is not None]
    print("%s  km %+6.2f  spd均值 %5s  样本 %3d  v %s" % (
        m,
        (k1 - k0) if (k0 is not None and k1 is not None) else float("nan"),
        ("%.1f" % (sum(so) / len(so))) if so else "--",
        len(spd),
        ("%.1f" % v[-1]) if v[-1] else "--"))
