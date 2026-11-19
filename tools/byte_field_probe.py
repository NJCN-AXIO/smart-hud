# -*- coding: utf-8 -*-
"""
67B 帧逐字节探针：除了已经用上的 [42] 转把、[47] 故障、[63][64] 电压、[65] 校验，
其余字节里有没有**电流 / 剩余电量 / 轮速**这类能大幅提升精度的字段？

用法：
    python tools/byte_field_probe.py "D:\\下载\\raw_20261117_*.csv"

输出：
    每个 index 的取值个数 / 范围 / 与(speed, 电压 sag) 的相关系数，
    以及按"有用程度"排序的候选列表。
"""
import glob
import io
import math
import sys

def unscramble(raw):
    """官方 67B 还原公式（从小程序 app-service.js 抠出来的）"""
    if len(raw) != 67:
        return None
    f = list(raw)
    for e in range(4, 66):
        f[e] = (f[e] - 11) & 0xFF
        f[e] = (85 ^ f[e]) & 0xFF
        f[e] = (f[e] - e) & 0xFF
        f[e] = (19 ^ f[e]) & 0xFF
        f[e] = (f[e] - e) & 0xFF
    return f

def load(path):
    frames = []   # (ts, [bytes])
    speeds = []   # (ts, kmh)
    with io.open(path, "r", encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            p = line.rstrip("\n").split(",")
            if len(p) < 3:
                continue
            try:
                ts = int(p[0])
            except ValueError:
                continue
            if p[1] == "FRM":
                hx = p[3] if len(p) > 3 else ""
                if not hx:
                    continue
                try:
                    raw = bytes(int(x, 16) for x in hx.split())
                except ValueError:
                    continue
                f = unscramble(raw)
                if f:
                    frames.append((ts, f))
            elif p[1] == "SYS" and len(p) > 2 and p[2].startswith("state"):
                for tok in p[2].split():
                    if tok.startswith("spd="):
                        try:
                            v = float(tok[4:])
                        except ValueError:
                            v = float("nan")
                        if not math.isnan(v):
                            speeds.append((ts, v))
    return frames, speeds

def pearson(xs, ys):
    n = len(xs)
    if n < 10:
        return float("nan")
    mx = sum(xs) / n
    my = sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx <= 0 or syy <= 0:
        return float("nan")
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    return sxy / math.sqrt(sxx * syy)

def main():
    pats = sys.argv[1:] or ["D:\\下载\\raw_*.csv"]
    files = []
    for p in pats:
        files.extend(sorted(glob.glob(p)))
    rows = []        # 所有帧 (ts, f, spd, sagV)
    per_file = []
    for path in files:
        frames, speeds = load(path)
        if not frames:
            continue
        # 速度按时间就近取值（前后 3 秒内）
        def spd_at(ts):
            lo, hi = 0, len(speeds) - 1
            if hi < 0:
                return float("nan")
            best = None
            while lo <= hi:
                mid = (lo + hi) // 2
                if speeds[mid][0] <= ts:
                    best = mid
                    lo = mid + 1
                else:
                    hi = mid - 1
            if best is None:
                best = 0
            cand = [best]
            if best + 1 < len(speeds):
                cand.append(best + 1)
            d, v = min(((abs(speeds[c][0] - ts), speeds[c][1]) for c in cand))
            return v if d <= 3000 else float("nan")

        # 电压滚动最大值（10 帧）当"空载参考"，差值≈负载压降
        volts = [((f[63] << 8) | f[64]) / 100.0 for _, f in frames]
        sag = []
        for i in range(len(frames)):
            win = volts[max(0, i - 10): i + 1]
            sag.append(max(win) - volts[i])
        for (ts, f), s, dv in zip(frames, sag, sag):
            rows.append((ts, f, spd_at(ts), dv))
        per_file.append((path, len(frames)))
        print("loaded %s frames=%d" % (path.split("\\")[-1], len(frames)))

    if not rows:
        print("no frames")
        return

    print("\n总帧 %d，文件 %d" % (len(rows), len(per_file)))
    print("\n== 每个字节的取值分布（只列取值数 >=2 的） ==")
    print("idx  nvals   min    max   mean   corr(spd)  corr(sag)")
    stats = []
    for idx in range(4, 67):
        vals = [r[1][idx] for r in rows]
        nvals = len(set(vals))
        if nvals < 2:
            continue
        mn, mx = min(vals), max(vals)
        mean = sum(vals) / len(vals)
        spd = [r[2] for r in rows]
        sagv = [r[3] for r in rows]
        cs = pearson(vals, spd)
        cv = pearson(vals, sagv)
        print("%3d  %5d  %5d  %5d  %6.1f   %8s   %8s" % (
            idx, nvals, mn, mx, mean,
            "-" if math.isnan(cs) else "%.3f" % cs,
            "-" if math.isnan(cv) else "%.3f" % cv))
        best = max(abs(cs) if not math.isnan(cs) else 0, abs(cv) if not math.isnan(cv) else 0)
        stats.append((best, idx, nvals, mn, mx, cs, cv))

    print("\n== 按相关性排序（|corr| 大的可能是电流/负载相关字段） ==")
    for best, idx, nvals, mn, mx, cs, cv in sorted(stats, reverse=True)[:10]:
        print("idx=%3d nvals=%3d range=[%d,%d] corr_spd=%s corr_sag=%s" % (
            idx, nvals, mn, mx,
            "-" if math.isnan(cs) else "%.3f" % cs,
            "-" if math.isnan(cv) else "%.3f" % cv))

if __name__ == "__main__":
    main()
