import sys, glob, os, collections

d = sys.argv[1]
big = os.path.join(d, "raw_20261117_100526.csv")

sysmsgs = collections.Counter()
frames = []
with open(big, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) < 3:
            continue
        k = p[1]
        if k == "SYS":
            # 按前 40 字聚合，看出到底什么被刷了 17 万条
            sysmsgs[p[2][:40]] += 1
        elif k == "FRM":
            frames.append((int(p[0]), p[3].split()))

print("big file FRM count:", len(frames))
print("top SYS messages:")
for m, c in sysmsgs.most_common(8):
    print("  %6d  %s" % (c, m))

if frames:
    print("\nfirst frame:", " ".join(frames[0][1]))
    print("last  frame:", " ".join(frames[-1][1]))
    n = len(frames[0][1])
    print("len:", n, "span(s):", (frames[-1][0]-frames[0][0])/1000.0)
