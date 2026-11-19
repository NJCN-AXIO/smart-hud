"""看看 idx42 / idx47 / idx63 是不是计数器/标志位。"""
import sys, collections

path = sys.argv[1]
rows = []
with open(path, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) >= 4 and p[1] == "FRM":
            rows.append((int(p[0]), bytes.fromhex(p[3].replace(" ", ""))))

for idx in (42, 47, 63):
    seq = [b[idx] for _, b in rows]
    d = collections.Counter(((seq[i + 1] - seq[i]) % 256) for i in range(len(seq) - 1))
    print("")
    print("== idx {}: distinct={} / delta ==".format(idx, len(set(seq))))
    for k, v in d.most_common(6):
        print("   +%-3d  %d 次" % (k, v))
    print("   前30:", " ".join("%02X" % x for x in seq[:30]))
    print("   后30:", " ".join("%02X" % x for x in seq[-30:]))

# idx42 是不是时间戳/计数器：看它和帧序号的线性关系
seq42 = [b[42] for _, b in rows]
print("\n== idx42 vs 帧序号（每 200 帧取一次）==")
for i in range(0, len(seq42), max(1, len(seq42) // 30)):
    print("   frame %5d -> %02X" % (i, seq42[i]))
