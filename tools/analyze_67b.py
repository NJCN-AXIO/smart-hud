import sys, glob, os, collections

d = sys.argv[1]
frames = []      # (ts, bytes_list, file)
gats = []
for f in sorted(glob.glob(os.path.join(d, "raw_20261117_*.csv"))):
    with open(f, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            p = line.rstrip("\n").split(",")
            if len(p) < 3:
                continue
            if p[1] == "FRM":
                frames.append((int(p[0]), p[3].split(), os.path.basename(f)))
            elif p[1] == "GAT":
                gats.append((int(p[0]), p[2], p[3] if len(p) > 3 else "", os.path.basename(f)))

print("total FRM:", len(frames))
if frames:
    n = len(frames[0][1])
    print("frame len:", n)

# 每个字节位置的取值分布
print("\n--- 每个偏移的取值种类（种类少 = 静态；多 = 有变化）---")
vals = []
for i in range(len(frames[0][1])):
    c = collections.Counter(fr[1][i] for fr in frames)
    vals.append(c)
for i, c in enumerate(vals):
    kinds = len(c)
    if kinds <= 3:
        s = " ".join("%s×%d" % (k, v) for k, v in c.most_common())
        print("  [%2d] kinds=%d  %s" % (i, kinds, s))
    else:
        print("  [%2d] kinds=%d  min=%s max=%s" % (i, kinds, min(c, key=lambda x: int(x, 16)), max(c, key=lambda x: int(x, 16))))

print("\n--- 变化最多的前 10 个偏移 ---")
order = sorted(range(len(vals)), key=lambda i: -len(vals[i]))
for i in order[:10]:
    c = vals[i]
    print("  [%2d] kinds=%d  %s" % (i, len(c), " ".join("%s×%d" % (k, v) for k, v in c.most_common(6))))

print("\n--- GAT 事件（前 40 条）---")
for g in gats[:40]:
    print(" ", g[0], g[1], g[2], g[3])
print("GAT 种类:", collections.Counter((g[1], g[2][:60]) for g in gats).most_common(10))
