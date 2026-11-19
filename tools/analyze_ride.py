"""逐字节看一份 raw_*.csv 里 67B 帧到底哪些位在变。

用法: python tools/analyze_ride.py "D:\\下载\\raw_20261117_141147.csv"
"""
import sys, collections

path = sys.argv[1]
rows = []
with open(path, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) >= 4 and p[1] == "FRM":
            rows.append((int(p[0]), p[2], bytes.fromhex(p[3].replace(" ", ""))))

print("frames:", len(rows), "tags:", set(t for _, t, _ in rows))
lens = collections.Counter(len(b) for _, _, b in rows)
print("lengths:", dict(lens))
if not rows:
    sys.exit()

n = len(rows[0][2])
ts0, ts1 = rows[0][0], rows[-1][0]
print("span: %.1f s" % ((ts1 - ts0) / 1000.0))

sets = [set() for _ in range(n)]
for _, _, b in rows:
    for i in range(n):
        sets[i].add(b[i])

print("\n-- 每字节取值个数（>1 才列）--")
for i in range(n):
    if len(sets[i]) > 1:
        print("idx %2d  distinct=%3d  min=%3d max=%3d  first=%02X last=%02X"
              % (i, len(sets[i]), min(sets[i]), max(sets[i]), rows[0][2][i], rows[-1][2][i]))

uniq = set(b for _, _, b in rows)
print("\ndistinct frames:", len(uniq), "/", len(rows))
print("\n-- 前 3 帧 --")
for t, _, b in rows[:3]:
    print(t, " ".join("%02X" % x for x in b))
print("-- 后 3 帧 --")
for t, _, b in rows[-3:]:
    print(t, " ".join("%02X" % x for x in b))
