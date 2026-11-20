import csv, glob, os, re, statistics, sys
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261119_140938.csv",
    r"D:\下载\raw_20261119_141802.csv",
    r"D:\下载\raw_20261119_155237.csv",
    r"D:\下载\raw_20261119_172034.csv",
    r"D:\下载\raw_20261119_202834.csv",
    r"D:\下载\raw_20261120_001434.csv",
]

def ts(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%m-%d %H:%M:%S")

def parse_rec(s):
    d = {}
    for tok in s.split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            d[k] = v
    return d

def num(x):
    try:
        return float(x)
    except Exception:
        return None

rows = []          # (epoch, rec dict)
sums = []          # SUM lines
versions = []
spans = []
for f in FILES:
    if not os.path.exists(f):
        print("缺失:", f)
        continue
    first = last = None
    ver = None
    nrec = 0
    with open(f, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            parts = line.rstrip("\n").split(",")
            if len(parts) < 2:
                continue
            try:
                e = int(parts[0])
            except Exception:
                continue
            kind = parts[1]
            first = first or e
            last = e
            if kind == "REC":
                d = parse_rec(parts[2])
                rows.append((e, d))
                nrec += 1
            elif kind == "SUM":
                sums.append((e, line.strip()))
            elif kind == "SYS":
                msg = ",".join(parts[2:])
                m = re.search(r"build v([\d.]+)", msg)
                if m:
                    ver = m.group(1)
                    versions.append((os.path.basename(f), ver))
    spans.append((os.path.basename(f), first, last, nrec, ver))

print("=" * 78)
print("① 文件清单")
print("=" * 78)
for name, a, b, n, v in spans:
    dur = (b - a) / 60000 if a and b else 0
    print(f"{name:30s} v{v or '?':7s} {ts(a) if a else '?'} → {ts(b) if b else '?'}  {dur:6.1f}min  REC={n}")

rows.sort(key=lambda x: x[0])
print()
print("=" * 78)
print("② 全程里程 / 时间")
print("=" * 78)
odo_first = odo_last = trip_first = trip_last = None
for e, d in rows:
    o = num(d.get("odo"))
    k = num(d.get("km"))
    if o is None:
        continue
    if odo_first is None:
        odo_first, t_first = o, e
    odo_last, t_last = o, e
    if k is not None:
        if trip_first is None:
            trip_first = k
        trip_last = k
print(f"时间      {ts(t_first)} → {ts(t_last)}   ({(t_last - t_first)/3600000:.2f} 小时)")
print(f"总里程    {odo_first:.1f} → {odo_last:.1f}   增 {odo_last - odo_first:.2f} km")
print(f"本次里程  {trip_first:.2f} → {trip_last:.2f}   增 {trip_last - trip_first:.2f} km")
for e, s in sums[-4:]:
    print("SUM  " + s)

print()
print("=" * 78)
print("③ 电量-电压曲线（src=1 实时帧，按 soc 分档统计）")
print("=" * 78)
print("soc档  样本   静置电压(中位)  带载最低V  带载中位V  该档里程(km)")
band = {}
prev = None
for e, d in rows:
    if d.get("src") != "1":
        continue
    soc = num(d.get("soc"))
    v = num(d.get("v"))
    o = num(d.get("odo"))
    if soc is None or v is None or o is None:
        continue
    b = int(soc // 5 * 5)
    band.setdefault(b, {"v": [], "vmin": [], "km": []})
    band[b]["v"].append(v)
    band[b]["vmin"].append(num(d.get("vn")) or v)
    if prev is not None and prev[0] == b:
        band[b]["km"].append(max(0.0, o - prev[1]))
    prev = (b, o)

for b in sorted(band, reverse=True):
    d = band[b]
    km = sum(d["km"])
    print(f"{b:3d}-{b+5:3d} {len(d['v']):6d}  {statistics.median(d['v']):8.2f}  {min(d['vmin']):9.2f}  {statistics.median(d['vmin']):9.2f}  {km:8.2f}")

print()
print("=" * 78)
print("④ 电量跳变 / 异常（相邻 REC 间隔 <=120s 且 |Δsoc|>=5）")
print("=" * 78)
last = None
jumps = 0
for e, d in rows:
    soc = num(d.get("soc"))
    if soc is None:
        continue
    if last and e - last[1] <= 120000 and abs(soc - last[0]) >= 5:
        jumps += 1
        if jumps <= 25:
            print(f"{ts(e)}  soc {last[0]:.0f} → {soc:.0f}  ({(soc-last[0]):+.0f})  odo={d.get('odo')} src={d.get('src')} v={d.get('v')}")
    last = (soc, e)
print(f"跳变总数(>=5): {jumps}")

print()
print("=" * 78)
print("⑤ 低压端细节（最后 20 分钟）")
print("=" * 78)
t_end = rows[-1][0]
for e, d in rows:
    if e < t_end - 20 * 60000:
        continue
    if d.get("src") != "1":
        continue
    print(f"{ts(e)} v={d.get('v')} dc={d.get('dc')} spd={d.get('spd')} thr={d.get('thr')} km={d.get('km')} odo={d.get('odo')} soc={d.get('soc')} sra={d.get('sra')} rng={d.get('rng')}")

print()
print("=" * 78)
print("⑥ 断连/冻结占比")
print("=" * 78)
tot = len(rows)
frozen = sum(1 for e, d in rows if d.get("src") == "4")
live = sum(1 for e, d in rows if d.get("src") == "1")
print(f"REC 总数 {tot}   src=1(实时) {live} ({live*100/tot:.1f}%)   src=4(冻结) {frozen} ({frozen*100/tot:.1f}%)")
others = {}
for e, d in rows:
    s = d.get("src")
    if s not in ("1", "4"):
        others[s] = others.get(s, 0) + 1
print("其他 src:", others)
