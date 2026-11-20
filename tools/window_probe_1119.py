import csv, re
from datetime import datetime

PATH = r"D:\下载\raw_20261119_202834.csv"
PATH2 = r"D:\下载\raw_20261120_001434.csv"

def parse_kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}

def fnum(x):
    try:
        v = float(x)
    except Exception:
        return None
    return None if v != v else v

def load(p):
    rows = []
    with open(p, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) >= 3 and r[1] == "REC":
                rows.append((int(r[0]), parse_kv(r[2])))
    return rows

def window(rows, t0, t1, label):
    sub = [(t, kv) for t, kv in rows if t0 <= t < t1]
    if not sub:
        print(label, "空")
        return
    spds = [fnum(kv.get("spd")) for _, kv in sub]
    ok = [s for s in spds if s is not None]
    integ = 0.0
    prev = None
    for t, kv in sub:
        s = fnum(kv.get("spd"))
        if prev is not None and s is not None:
            integ += s * min(1.0, (t - prev) / 1000.0)
        prev = t if s is not None else prev
    kms = [fnum(kv.get("km")) for _, kv in sub]
    km0 = next((k for k in kms if k is not None), None)
    km1 = next((k for k in reversed(kms) if k is not None), None)
    gk = [fnum(kv.get("gkm")) for _, kv in sub]
    gk_ok = [g for g in gk if g is not None]
    src4 = sum(1 for _, kv in sub if kv.get("src") == "4")
    t0s = datetime.fromtimestamp(sub[0][0] / 1000).strftime("%H:%M:%S")
    t1s = datetime.fromtimestamp(sub[-1][0] / 1000).strftime("%H:%M:%S")
    print("%s  %s->%s  行%d  spd有效%d  src4=%.0f%%" % (
        label, t0s, t1s, len(sub), len(ok), 100.0 * src4 / len(sub)))
    if ok:
        print("    ∫spd·dt=%.2fkm   spd均值%.1f  里程 %.2f->%.2f" % (
            integ, sum(ok) / len(ok), km0 if km0 is not None else -1, km1 if km1 is not None else -1))
    if gk_ok:
        print("    gkm 跨度 %.2f->%.2f (=%.2fkm)" % (gk_ok[0], gk_ok[-1], gk_ok[-1] - gk_ok[0]))

rows = load(PATH)
T = lambda h, m, s=0: int(datetime(2026, 11, 19, h, m, s).timestamp() * 1000)
window(rows, T(20, 28, 35), T(22, 8, 16), "20:28->22:08（对表前）")
window(rows, T(22, 8, 16), T(23, 59, 59), "22:08->00:00")
rows2 = load(PATH2)
window(rows2, int(datetime(2026, 11, 20, 0, 14, 35).timestamp() * 1000),
       int(datetime(2026, 11, 20, 1, 11, 3).timestamp() * 1000), "00:14->01:11")
window(rows, T(23, 59, 59), int(datetime(2026, 11, 20, 0, 14, 33).timestamp() * 1000), "00:00->00:14")
