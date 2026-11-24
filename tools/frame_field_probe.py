"""从 67B 原始帧里挖速度/里程字段。

输入：D:\\下载\\raw_*.csv（FRM 行存原始十六进制帧，REC 行存快照 spd=/v=）。
做法：
  1. 按小程序逆变换解扰 f[4..65]；
  2. 逐字节统计取值范围/方差，与同时段 GPS 速度算相关性；
  3. 两字节组合（LE/BE）找全程单调递增、增量像里程的字段。
"""
import csv, re, os, glob, math
from datetime import datetime

FILES = sorted(glob.glob(r"D:\下载\raw_2026112*.csv"))


def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}


def unscramble(raw):
    f = list(raw)
    for e in range(4, 66):
        if e >= len(f):
            break
        x = f[e]
        x -= 11
        x = 85 ^ (x & 0xFF)
        x -= e
        x = 19 ^ (x & 0xFF)
        x -= e
        f[e] = x
    return f


frames = []   # (ts, f[])
recs = []     # (ts, kv)
for path in FILES:
    if not os.path.exists(path):
        continue
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3 or not r[0].isdigit():
                continue
            ts = int(r[0])
            if r[1] == "FRM":
                hx = r[3].replace(" ", "")
                if len(hx) < 130:
                    continue
                try:
                    raw = bytes.fromhex(hx)
                except ValueError:
                    continue
                if len(raw) != 67 or raw[66] != 0xCC:
                    continue
                frames.append((ts, unscramble(list(raw))))
            elif r[1] == "REC":
                recs.append((ts, kv(r[2])))

print(f"frames={len(frames)} recs={len(recs)}")

# REC 速度/电压按时间就近匹配
recs.sort()
def nearest(ts, key):
    best, bd = None, 1e18
    for t, p in recs:
        d = abs(t - ts)
        if d < bd:
            bd, best = d, p
        if t - ts > 3000:
            break
    if best is None or bd > 3000:
        return None
    v = best.get(key)
    try:
        return float(v)
    except (TypeError, ValueError):
        return None

# ---------- 逐字节：与速度相关性 ----------
by_byte = {i: [] for i in range(67)}
spd_pairs = {i: [] for i in range(67)}
for ts, f in frames:
    for i, x in enumerate(f):
        by_byte[i].append(x)
    sp = nearest(ts, "spd")
    if sp is not None and not math.isnan(sp):
        for i, x in enumerate(f):
            spd_pairs[i].append((x, sp))

print("\n== 字节取值范围（解扰后） ==")
rows = []
for i in range(67):
    vals = by_byte[i]
    if not vals:
        continue
    lo, hi, mean = min(vals), max(vals), sum(vals) / len(vals)
    uniq = len(set(vals))
    rows.append((i, lo, hi, mean, uniq))
for i, lo, hi, mean, uniq in rows:
    flag = "  <== 变量" if uniq > 3 else ""
    print(f"f[{i:2d}] {lo:4d}..{hi:4d} mean={mean:7.2f} uniq={uniq:4d}{flag}")

print("\n== 与 GPS 速度相关性（|r|>0.3 的字节） ==")
def corr(xs, ys):
    n = len(xs)
    if n < 20:
        return 0.0
    mx, my = sum(xs) / n, sum(ys) / n
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    dx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    dy = math.sqrt(sum((y - my) ** 2 for y in ys))
    return num / (dx * dy) if dx > 0 and dy > 0 else 0.0

cands = []
for i in range(67):
    xs = [a for a, _ in spd_pairs[i]]
    ys = [b for _, b in spd_pairs[i]]
    r = corr(xs, ys)
    if abs(r) > 0.3:
        cands.append((abs(r), r, i))
cands.sort(reverse=True)
for ar, r, i in cands:
    print(f"f[{i:2d}] r={r:+.3f}")

# ---------- 两字节里程候选：单调递增 ----------
print("\n== 两字节单调递增候选（里程表） ==")
frames.sort()
def mono_score(be, pos, endian):
    prev, inc, n = None, 0, 0
    deltas = []
    for ts, f in be:
        if endian == "LE":
            v = f[pos] | (f[pos + 1] << 8)
        else:
            v = (f[pos] << 8) | f[pos + 1]
        if prev is not None:
            n += 1
            if v >= prev:
                inc += 1
            deltas.append(v - prev)
        prev = v
    ratio = inc / n if n else 0
    total = (prev - [None])[0] if prev is not None else 0
    return ratio, n, total, deltas

# 只在一个长会话内判单调（按文件分段更稳）
for path in FILES:
    fr = [ (t, u) for t, u in frames if True ]
# 直接全量：找 LE/BE 组合里单调比例高、增量集中在小正数
best = []
for pos in range(66):
    for endian in ("LE", "BE"):
        prev, inc, n = None, 0, 0
        deltas = []
        for ts, f in frames:
            v = f[pos] | (f[pos+1] << 8) if endian == "LE" else (f[pos] << 8) | f[pos+1]
            if prev is not None:
                n += 1
                if v >= prev:
                    inc += 1
                deltas.append(v - prev)
            prev = v
        if n < 100:
            continue
        ratio = inc / n
        pos_deltas = [d for d in deltas if d > 0]
        med = sorted(pos_deltas)[len(pos_deltas)//2] if pos_deltas else 0
        if ratio > 0.85 and med <= 5:
            best.append((ratio, pos, endian, med, n))
best.sort(reverse=True)
for ratio, pos, endian, med, n in best[:15]:
    print(f"f[{pos:2d}|{pos+1:2d}] {endian} mono={ratio:.2f} median+delta={med} n={n}")
