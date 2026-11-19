"""从 raw_*.csv 里抽出 state 行（SYS,state v=..soc=..km=..），算真实的 V/km 与 %/km。

用途：判断"每公里耗电"到底该不该被夹在 2.6 上限。
用法: python tools/ride_slope.py "D:\\下载\\raw_20261117_*.csv"
"""
import sys, glob, re, datetime

pat = re.compile(r"v=([\d.]+).*?soc=([\dN/A.]+).*?km=([\d.]+)")

def read(path):
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if ",SYS,state " not in line:
                continue
            try:
                ts = int(line.split(",")[0])
            except Exception:
                continue
            m = pat.search(line)
            if not m:
                continue
            try:
                v = float(m.group(1)); km = float(m.group(3))
            except Exception:
                continue
            soc = None
            try:
                soc = float(m.group(2))
            except Exception:
                pass
            rows.append((ts, v, soc, km))
    return rows

files = sorted(glob.glob(sys.argv[1]))
all_rows = []
for p in files:
    r = read(p)
    if not r:
        continue
    all_rows += r
    dur = (r[-1][0] - r[0][0]) / 1000.0
    print("%-34s n=%3d  %5.0fs  v %5.1f→%5.1f  km %5.2f→%5.2f  soc %s→%s" % (
        p.split("\\")[-1], len(r), dur, r[0][1], r[-1][1], r[0][3], r[-1][3],
        "?" if r[0][2] is None else "%.0f" % r[0][2],
        "?" if r[-1][2] is None else "%.0f" % r[-1][2]))

all_rows.sort()
print("\n合计 %d 条 state 行，时间跨度 %.1f 分钟" % (
    len(all_rows), (all_rows[-1][0] - all_rows[0][0]) / 60000.0 if all_rows else 0))

# 只看"里程在长"的相邻样本，算 ΔV/Δkm 与 Δsoc/Δkm
dvs, dsocs = [], []
for a, b in zip(all_rows, all_rows[1:]):
    dk = b[3] - a[3]
    if dk < 0.5 or dk > 12:      # 跳过清零/长时间断档
        continue
    dvs.append((b[1] - a[1]) / dk)
    if a[2] is not None and b[2] is not None:
        dsocs.append((a[2] - b[2]) / dk)

def stat(name, arr):
    if not arr:
        print("%s: 无样本" % name); return
    arr = sorted(arr)
    n = len(arr)
    print("%s: n=%d  中位=%.3f  均值=%.3f  p10=%.3f  p90=%.3f" % (
        name, n, arr[n//2], sum(arr)/n, arr[int(n*0.1)], arr[int(n*0.9)]))

print()
stat("ΔV/Δkm (V 每公里)", dvs)
stat("Δsoc/Δkm (% 每公里)", dsocs)
