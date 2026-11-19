import sys, glob, os, statistics

d = sys.argv[1]
events = []
frames = []
for f in sorted(glob.glob(os.path.join(d, "raw_20261117_*.csv"))):
    base = os.path.basename(f)
    with open(f, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            p = line.rstrip("\n").split(",")
            if len(p) < 3:
                continue
            if p[1] == "GAT":
                events.append((int(p[0]), p[2], p[3] if len(p) > 3 else "", base))
            elif p[1] == "FRM":
                frames.append((int(p[0]), base))

events.sort(key=lambda x: x[0])
frames.sort(key=lambda x: x[0])

# 连接时长：CONNECTED -> 下一个 DISCONNECTED
durs = []
open_ts = None
for ts, w, det, base in events:
    if w == "CONNECTED":
        open_ts = ts
    elif w == "DISCONNECTED" and open_ts:
        durs.append((ts - open_ts) / 1000.0)
        open_ts = None

print("连接成功次数:", sum(1 for e in events if e[1] == "CONNECTED"))
print("断开次数:", sum(1 for e in events if e[1] == "DISCONNECTED"))
if durs:
    print("连接时长(s): n=%d  中位=%.1f  最短=%.1f  最长=%.1f  平均=%.1f"
          % (len(durs), statistics.median(durs), min(durs), max(durs), sum(durs)/len(durs)))
    hist = {}
    for x in durs:
        k = int(x // 5) * 5
        hist[k] = hist.get(k, 0) + 1
    print("分布(5s 桶):", dict(sorted(hist.items())))

# 帧间隔断档：>3 秒的 gap，说明数据停过
gaps = []
for i in range(1, len(frames)):
    dt = (frames[i][0] - frames[i-1][0]) / 1000.0
    if dt > 3:
        gaps.append((frames[i-1][0], dt, frames[i][1]))
print("\n帧间隔 >3s 的断档次数:", len(gaps))
for g in gaps[:15]:
    print("   at %d  gap=%.1fs  %s" % (g[0], g[1], g[2]))

# 帧率
if frames:
    span = (frames[-1][0] - frames[0][0]) / 1000.0
    print("\n总帧=%d 跨 %.0fs → 平均 %.2f Hz" % (len(frames), span, len(frames)/span))
