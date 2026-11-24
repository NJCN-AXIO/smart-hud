"""11-24 上午 21km 骑行日志复盘（单文件）。

用法: python tools/review_1124_ride.py "D:\\下载\\raw_20261124_102047.csv"
"""
import csv, re, sys, statistics
from datetime import datetime

path = sys.argv[1] if len(sys.argv) > 1 else r"D:\下载\raw_20261124_102047.csv"

def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def f(x, default=float("nan")):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return default
    return default if v != v else v

def ts(t):
    return datetime.fromtimestamp(int(t) / 1000).strftime("%H:%M:%S")

recs, frms, rats, sysl = [], [], [], []
read_rows = {}
disco = []
connects = []

with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
    for r in csv.reader(fh):
        if len(r) < 3 or not r[0].isdigit():
            continue
        t, kind = int(r[0]), r[1]
        if kind == "REC":
            recs.append((t, kv(r[2])))
        elif kind == "FRM":
            tag, hexv = r[2], r[3] if len(r) > 3 else ""
            try:
                b = bytes.fromhex(hexv.replace(" ", ""))
            except ValueError:
                b = b""
            frms.append((t, tag, b))
        elif kind == "GAT":
            a = r[2]
            rest = " ".join(x for x in r[3:] if x)
            if a.startswith("READ "):
                read_rows.setdefault(a, []).append((t, rest))
            elif a == "CONNECT":
                connects.append(t)
            elif a == "DISCONNECTED":
                disco.append((t, rest))
            else:
                rats.append((t, a, rest))
        elif kind == "SYS":
            sysl.append((t, r[2]))

print("=" * 90)
print("文件:", path.split("\\")[-1])
print("REC=%d FRM=%d GAT=%d SYS=%d" % (len(recs), len(frms), len(rats) + len(read_rows) + len(connects) + len(disco), len(sysl)))
if recs:
    print("时间跨度: %s -> %s  (%.1f min)" % (ts(recs[0][0]), ts(recs[-1][0]), (recs[-1][0] - recs[0][0]) / 60000))

# ---------- READ 旁路审计 ----------
print("\n-- READ 旁路审计（v1.0.60 只读枚举）--")
for name, rows in sorted(read_rows.items()):
    nonempty = [(t, v) for t, v in rows if v.strip()]
    print("  %-16s 次数=%3d 非空=%d %s" % (name, len(rows), len(nonempty),
          nonempty[0][1][:40] if nonempty else ""))

# ---------- 链路健康 ----------
print("\n-- 链路 --")
print("  CONNECT=%d DISCONNECTED=%d" % (len(connects), len(disco)))
from collections import Counter
reason = Counter(re.search(r"reason=(\S+)", s).group(1) if re.search(r"reason=(\S+)", s) else s[:20] for _, s in disco)
print("  断开原因:", dict(reason))
after = [f(re.search(r"afterMs=(\d+)", s).group(1)) for _, s in disco if re.search(r"afterMs=(\d+)", s)]
if after:
    print("  连接存活 ms: 中位 %.0f  最短 %.0f  最长 %.0f" % (statistics.median(after), min(after), max(after)))

# ---------- FRM ----------
print("\n-- 帧 --")
if frms:
    lens = Counter(len(b) for _, _, b in frms)
    tags = Counter(tag for _, tag, _ in frms)
    print("  帧长:", dict(lens), " 标签:", dict(tags))

# ---------- REC 快照统计 ----------
print("\n-- 快照轨迹 --")
def col(k):
    return [(t, p) for t, p in recs if p.get(k) not in (None, "", "-", "NaN")]

def num(p, k):
    return f(p.get(k))

first, last = recs[0][1], recs[-1][1]
print("  FIRST v=%s soc=%s sra=%s spd=%s km=%s odo=%s gkm=%s sc=%s src=%s whc=%s" % (
    first.get("v"), first.get("soc"), first.get("sra"), first.get("spd"),
    first.get("km"), first.get("odo"), first.get("gkm"), first.get("sc"), first.get("src"), first.get("whc")))
print("  LAST  v=%s soc=%s sra=%s spd=%s km=%s odo=%s gkm=%s sc=%s src=%s whc=%s" % (
    last.get("v"), last.get("soc"), last.get("sra"), last.get("spd"),
    last.get("km"), last.get("odo"), last.get("gkm"), last.get("sc"), last.get("src"), last.get("whc")))

spds = [num(p, "spd") for _, p in recs if num(p, "spd") == num(p, "spd")]
ride = [s for s in spds if s >= 2]
if spds:
    print("  速度: 峰值 %.1f km/h  骑行段均值 %.1f  样本 %d" % (max(spds), statistics.mean(ride) if ride else float("nan"), len(ride)))

vs = [num(p, "v") for _, p in recs if num(p, "v") == num(p, "v")]
if vs:
    print("  显示电压: 最高 %.2f  最低 %.2f  末段中位 %.2f" % (max(vs), min(vs), statistics.median(vs[-30:])))

socv = [num(p, "soc") for _, p in recs if num(p, "soc") == num(p, "soc")]
if socv:
    print("  账本 SOC: 首 %.0f 末 %.0f 最低 %.0f" % (socv[0], socv[-1], min(socv)))

# 里程口径
def od(p): return num(p, "odo")
odos = [(t, od(p)) for t, p in recs if od(p) == od(p)]
if len(odos) >= 2:
    print("  总里程 odo: %.1f -> %.1f (Δ %.1f)" % (odos[0][1], odos[-1][1], odos[-1][1] - odos[0][1]))
gkms = [(t, num(p, "gkm")) for t, p in recs if num(p, "gkm") == num(p, "gkm")]
if len(gkms) >= 2:
    print("  GPS 里程 gkm: %.2f -> %.2f (Δ %.2f)" % (gkms[0][1], gkms[-1][1], gkms[-1][1] - gkms[0][1]))
kms = [(t, num(p, "km")) for t, p in recs if num(p, "km") == num(p, "km")]
if len(kms) >= 2:
    print("  本次里程 km: %.2f -> %.2f (Δ %.2f)" % (kms[0][1], kms[-1][1], kms[-1][1] - kms[0][1]))
scs = [num(p, "sc") for _, p in recs if num(p, "sc") == num(p, "sc")]
if scs:
    print("  里程系数 sc: %.3f -> %.3f" % (scs[0], scs[-1]))

# ---------- 静置样本 ----------
print("\n-- 静置样本（spd≤2, thr≤1.5, sag≤0.6）--")
rest = []
for t, p in recs:
    spd, thr, sag = num(p, "spd"), num(p, "thr"), num(p, "sag")
    if spd == spd and thr == thr and sag == sag and spd <= 2 and thr <= 1.5 and sag <= 0.6:
        rest.append((t, p))
for t, p in rest:
    print("  %s v=%s soc=%s sra=%s km=%s odo=%s" % (ts(t), p.get("v"), p.get("soc"), p.get("sra"), p.get("km"), p.get("odo")))

# ---------- 关键 SYS 事件 ----------
print("\n-- 关键事件 --")
KEY = ("align", "scale", "anchored", "rest cal", "calibrate", "low power", "EMPTY", "empty",
       "range implied", "est_range", "trip summary", "trip reset", "trip set", "learned",
       "link lost", "parked", "moving", "stale", "soc:")
for t, s in sysl:
    if any(k in s for k in KEY):
        print("  [%s] %s" % (ts(t), s[:140]))
