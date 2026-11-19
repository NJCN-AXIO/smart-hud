import sys, glob, os

d = sys.argv[1]
rows = []
for f in sorted(glob.glob(os.path.join(d, "raw_20261117_*.csv"))):
    with open(f, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            p = line.rstrip("\n").split(",")
            if len(p) > 3 and p[1] == "FRM":
                b = p[3].split()
                rows.append((int(p[0]), b, os.path.basename(f)))
rows.sort(key=lambda x: x[0])
print("frames:", len(rows))

t0 = rows[0][0]
print("\n时刻  [42][47][61][63][64][65]   备注")
prev = None
for ts, b, f in rows:
    sig = (b[42], b[47], b[61], b[63], b[64], b[65])
    if sig != prev:
        print("%6.1fs  %s %s %s %s %s %s" % ((ts - t0) / 1000.0, *sig))
        prev = sig

# [42] 是否单调计数器？
v42 = [int(b[42], 16) for _, b, _ in rows]
inc = sum(1 for i in range(1, len(v42)) if v42[i] == (v42[i-1] + 1) % 256)
print("\n[42] 增加 1 的次数 / 总变化:", inc, "/", len(v42)-1)
v64 = [int(b[64], 16) for _, b, _ in rows]
sample = []
for i in range(0, min(20, len(rows))):
    sample.append((rows[i][1][64], rows[i][1][65]))
print("[64][65] 样例:", sample)
