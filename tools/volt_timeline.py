"""对照实验：把某段日志的电压按时间打出来，和用户同时刻的小程序截图对比。

用法: python tools/volt_timeline.py <raw.csv> [--every N] [--from SEC] [--to SEC]
"""
import sys, datetime

path = sys.argv[1]
every = 20
for i, a in enumerate(sys.argv):
    if a == "--every":
        every = int(sys.argv[i + 1])

def unscramble(raw):
    f = list(raw)
    for e in range(4, 66):
        v = f[e] - 11
        v = 85 ^ (v & 0xFF)
        v = v - e
        v = 19 ^ (v & 0xFF)
        v = v - e
        f[e] = v
    return f

rows = []
with open(path, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) >= 4 and p[1] == "FRM":
            b = bytes.fromhex(p[3].replace(" ", ""))
            if len(b) < 67:
                continue
            f = unscramble(b)
            v = (256 * f[63] + f[64]) / 100.0
            rows.append((int(p[0]), v, f[63], f[64], 0.019 * f[42], f[47]))

print("frames=%d  span=%s -> %s" % (
    len(rows),
    datetime.datetime.fromtimestamp(rows[0][0] / 1000).strftime("%H:%M:%S"),
    datetime.datetime.fromtimestamp(rows[-1][0] / 1000).strftime("%H:%M:%S")))

t0 = rows[0][0]
for i in range(0, len(rows), every):
    ts, v, hi, lo, thr, flt = rows[i]
    print("%s  v=%5.2f  f63=%3d f64=%4d  throttle=%.2f fault=%d" % (
        datetime.datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S"), v, hi, lo, thr, flt))
