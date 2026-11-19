"""把一天的 state 行按时间排好，看"里程 vs 静置电压"这一天到底怎么掉的。

用法: python tools/day_soc_curve.py <csv...>
"""
import sys, glob, datetime, re

rows = []
for path in sys.argv[1:]:
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            p = line.rstrip("\n").split(",")
            if len(p) >= 3 and p[1] == "SYS" and p[2].startswith("state "):
                m = re.search(r"v=([\d.]+|NaN).*soc=([\d.]+|NaN).*km=([\d.]+)", p[2])
                if not m:
                    continue
                try:
                    v = float(m.group(1)); soc = float(m.group(2)); km = float(m.group(3))
                except ValueError:
                    continue
                rows.append((int(p[0]), v, soc, km))

rows.sort()
print("state rows:", len(rows))
last_km = None
for ts, v, soc, km in rows:
    if last_km is None or abs(km - last_km) >= 0.4 or True:
        t = datetime.datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S")
        print("%s  km=%5.2f  v=%5.1f  app_soc=%3.0f" % (t, km, v, soc))
    last_km = km

# 每个 0.5km 取一次"最高电压"，近似静置电压（停车时电压最高）
buckets = {}
for ts, v, soc, km in rows:
    b = round(km * 2) / 2
    buckets[b] = max(buckets.get(b, 0), v)
print("\n--- 每 0.5km 的最高读数（近似静置电压）---")
for b in sorted(buckets):
    print("%5.1fkm  %5.1f V" % (b, buckets[b]))
