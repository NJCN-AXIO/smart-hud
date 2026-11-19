"""用实车日志验证 v1.0.0 的电压显示：中位数滤波后到底会显示多少。

对比对象：用户截图里小程序 68.6V / 我们的旧版 62.5V。
旧版是 4 秒滑动平均（会被 58V 这种离群值拖低），新版是 9 点中位数。

用法: python tools/sim_volt_display.py <raw.csv>
"""
import sys, statistics

path = sys.argv[1]


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


vals = []
with open(path, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) >= 4 and p[1] == "FRM":
            b = bytes.fromhex(p[3].replace(" ", ""))
            if len(b) >= 67:
                f = unscramble(b)
                vals.append((256 * f[63] + f[64]) / 100.0)

print("frames:", len(vals))
print("raw   mean=%.2f median=%.2f min=%.2f max=%.2f" % (
    statistics.mean(vals), statistics.median(vals), min(vals), max(vals)))

# 9 点滚动中位数 = App 上会显示的值
win = 9
shown = []
for i in range(len(vals)):
    lo = max(0, i - win + 1)
    shown.append(statistics.median(vals[lo:i + 1]))
print("shown mean=%.2f median=%.2f min=%.2f max=%.2f" % (
    statistics.mean(shown), statistics.median(shown), min(shown), max(shown)))

# 显示值的秒级抖动：相邻两个采样之间最多变多少
jumps = [abs(shown[i] - shown[i - 1]) for i in range(1, len(shown))]
jumps.sort()
print("step p50=%.2fV p90=%.2fV max=%.2fV" % (
    jumps[len(jumps) // 2], jumps[int(len(jumps) * 0.9)], jumps[-1]))

# 旧版 4 秒 ≈ 8 帧的滑动平均，用来解释"为什么旧版显示 62.5"
avg = []
for i in range(len(vals)):
    lo = max(0, i - 8)
    avg.append(sum(vals[lo:i + 1]) / (i + 1 - lo))
print("old 8-frame mean: mean=%.2f median=%.2f min=%.2f max=%.2f" % (
    statistics.mean(avg), statistics.median(avg), min(avg), max(avg)))
