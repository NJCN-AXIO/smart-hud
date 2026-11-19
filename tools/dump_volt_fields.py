"""把 67B 帧里的 f[60..66] 全打出来，看电压字段到底是"噪声"还是"结构"。

用法: python tools/dump_volt_fields.py <raw.csv> [--n 40] [--from 0]
"""
import sys

path = sys.argv[1]
n = 40
start = 0
for i, a in enumerate(sys.argv):
    if a == "--n":
        n = int(sys.argv[i + 1])
    if a == "--from":
        start = int(sys.argv[i + 1])


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


frames = []
with open(path, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) >= 4 and p[1] == "FRM":
            b = bytes.fromhex(p[3].replace(" ", ""))
            if len(b) >= 67:
                frames.append((int(p[0]), b))

print("frames:", len(frames))
print(" idx  t(ms)      f60 f61 f62 f63 f64 f65 f66   v=(256*63+64)/100  f64/10  f63+64/100")
for i in range(start, min(len(frames), start + n)):
    t, b = frames[i]
    f = unscramble(b)
    v1 = (256 * f[63] + f[64]) / 100.0
    v2 = f[64] / 10.0
    v3 = f[63] + f[64] / 100.0
    print("%4d %10d  %3d %3d %3d %3d %3d %3d %3d   %8.2f  %8.2f  %8.2f" % (
        i, t, f[60], f[61], f[62], f[63], f[64], f[65], f[66], v1, v2, v3))
