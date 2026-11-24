"""11-24 晚间一箱电：检查点对照（电压查表 vs 账本 vs 里程）。"""
import csv, re, statistics
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261124_142534.csv",
    r"D:\下载\raw_20261124_153033.csv",
    r"D:\下载\raw_20261124_154537.csv",
    r"D:\下载\raw_20261124_155537.csv",
    r"D:\下载\raw_20261124_160134.csv",
    r"D:\下载\raw_20261124_161422.csv",
]

V_REF = [78.6, 77.3, 75.9, 74.9, 74.0, 73.3, 72.6, 72.1, 70.6, 66.3, 63.8]
SOC_REF = [100, 94, 87, 78, 70, 63, 50, 41, 28, 15, 0]

def soc_of(v):
    if v != v: return float("nan")
    if v >= V_REF[0]: return 100.0
    if v <= V_REF[-1]: return 0.0
    for i in range(len(V_REF) - 1):
        if V_REF[i + 1] <= v <= V_REF[i]:
            t = (v - V_REF[i + 1]) / (V_REF[i] - V_REF[i + 1])
            return SOC_REF[i + 1] + t * (SOC_REF[i] - SOC_REF[i + 1])
    return 0.0

def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def f(x, d=float("nan")):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return d
    return d if v != v else v

def ts(t): return datetime.fromtimestamp(int(t) / 1000).strftime("%H:%M:%S")

recs, sysl = [], []
for path in FILES:
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3 or not r[0].isdigit(): continue
            t, kind = int(r[0]), r[1]
            if kind == "REC": recs.append((t, kv(r[2])))
            elif kind == "SYS": sysl.append((t, r[2]))
recs.sort(key=lambda x: x[0]); sysl.sort(key=lambda x: x[0])

# trip summary 检查点时间
checks = [(t, s) for t, s in sysl if "trip summary:" in s]
print("检查点（SYS trip summary 附近最近一条 REC）")
print("%-9s %6s %4s %4s %5s %6s %6s %7s %5s %5s %5s" % (
    "time", "v", "soc", "sra", "查表", "km", "gkm", "odo", "whc", "spd", "diff"))
for ct, s in checks:
    near = min(recs, key=lambda x: abs(x[0] - ct))
    t, p = near
    v, soc, sra = f(p.get("v")), f(p.get("soc")), f(p.get("sra"))
    km, gkm, odo, whc, spd = f(p.get("km")), f(p.get("gkm")), f(p.get("odo")), f(p.get("whc")), f(p.get("spd"))
    tbl = soc_of(v)
    diff = (soc - tbl) if v == v else float("nan")
    print("%-9s %6s %4.0f %4s %5.1f %6.2f %6s %7s %5s %5s %5.1f" % (
        ts(t), ("%.2f" % v) if v == v else "-", soc,
        ("%.0f" % sra) if sra == sra else "-", tbl, km,
        ("%.2f" % gkm) if gkm == gkm else "-",
        ("%.1f" % odo) if odo == odo else "-",
        ("%.0f" % whc) if whc == whc else "-",
        ("%.0f" % spd) if spd == spd else "-", diff))

# 长静置 episode（连续静止 ≥60s，v 有效）
print("\n长静置（≥60s）")
cur = None
for t, p in recs:
    spd, thr, sag, v = f(p.get("spd")), f(p.get("thr")), f(p.get("sag")), f(p.get("v"))
    ok = spd == spd and thr == thr and sag == sag and spd <= 2 and thr <= 1.5 and sag <= 0.6 and v == v
    if ok:
        if cur is None: cur = {"t0": t, "rows": []}
        cur["rows"].append((t, p))
    else:
        if cur:
            if (cur["rows"][-1][0] - cur["t0"]) >= 60_000:
                rows = cur["rows"]
                vs = [f(q.get("v")) for _, q in rows]
                socs = [f(q.get("soc")) for _, q in rows]
                kms = [f(q.get("km")) for _, q in rows]
                vmed = statistics.median(vs)
                print("  %s~%s %3ds v=%.2f 查表=%4.1f 账本=%3.0f diff=%+4.1f km=%.2f" % (
                    ts(rows[0][0]), ts(rows[-1][0]), (rows[-1][0]-cur["t0"])/1000,
                    vmed, soc_of(vmed), statistics.median(socs),
                    statistics.median(socs)-soc_of(vmed), kms[-1]))
            cur = None
if cur and (cur["rows"][-1][0]-cur["t0"]) >= 60_000:
    rows = cur["rows"]; vs=[f(q.get("v")) for _,q in rows]; socs=[f(q.get("soc")) for _,q in rows]
    vmed=statistics.median(vs)
    print("  %s~%s v=%.2f 查表=%.1f 账本=%.0f" % (ts(rows[0][0]), ts(rows[-1][0]), vmed, soc_of(vmed), statistics.median(socs)))

# 开机前 90 秒电压序列
print("\n开机 90s 电压序列")
boot = next((t for t, _ in recs if t > 1795508000000), recs[0][0])
for t, p in recs:
    if t - boot > 90_000: break
    print("  %s v=%6s soc=%3s spd=%5s src=%s" % (ts(t), p.get("v"), p.get("soc"), p.get("spd"), p.get("src")))
