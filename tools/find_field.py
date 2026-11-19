#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
电压/速度字段自动求解器。

输入：App 导出的原始帧日志 raw_*.csv（RawLog 写的格式）
      epoch_ms,kind,a,b,c,d,e
      - kind=MAT 行（v0.2.1 起）：a=通道名, b=小程序电压, c=小程序速度, d=GPS速度, e=该帧 hex
      - kind=MAT 行（v0.2.0 旧格式）：b=小程序电压, c=小程序速度, d=GPS速度, e=帧 hex
      - kind=FRM 行：a=通道名, b=帧 hex      （可选，仅用于回放）

原理：把每个 (帧, 标注电压) 当成一个样本，遍历
      偏移 × 位宽(1..4) × 字节序(大/小端)，
      与标注值做最小二乘拟合 V = k*x + b，
      按 R² 和最大残差排序，同时把 k 反查成"好看的系数"（0.01 / 0.02 / 0.05 / 0.1 ...）。

  控制器可能同时推两路通知（FFF6 / AE02），每路各记一条 MAT 样本，
  因此这里按通道分组求解——混在一起会把拟合结果污染掉。

用法：
      python find_field.py raw_20261116_203656.csv
      python find_field.py --selftest          # 合成数据自检，验证求解器本身没写错
"""
import sys, csv, math, random

NICE_SCALES = [0.001, 0.002, 0.005, 0.01, 0.02, 0.025, 0.05, 0.04, 0.1, 0.2, 0.25, 0.5, 1.0,
               0.003, 0.015, 0.03, 0.06, 0.0625, 0.125, 1.0 / 3.0]


def hex_to_bytes(s):
    try:
        return [int(x, 16) for x in s.replace(",", " ").split()]
    except ValueError:
        return []


def looks_hex(s):
    s = (s or "").strip()
    if not s:
        return False
    return len(hex_to_bytes(s)) > 0


def field_value(raw, off, width, big):
    if off + width > len(raw):
        return None
    v = 0
    if big:
        for i in range(width):
            v = (v << 8) | raw[off + i]
    else:
        for i in range(width - 1, -1, -1):
            v = (v << 8) | raw[off + i]
    return v


def lsq(xs, ys):
    n = len(xs)
    mx = sum(xs) / n
    my = sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    if sxx == 0:
        return None
    k = sxy / sxx
    b = my - k * mx
    pred = [k * x + b for x in xs]
    sse = sum((y - p) ** 2 for y, p in zip(ys, pred))
    sst = sum((y - my) ** 2 for y in ys)
    r2 = 1.0 - sse / sst if sst > 0 else 0.0
    maxres = max(abs(y - p) for y, p in zip(ys, pred))
    return k, b, r2, maxres


def nice_scale_of(k):
    """k 是 V/LSB 系数，直接和常见系数比（0.01/0.05/0.1 ...）。"""
    if k <= 0:
        return None
    best = min(NICE_SCALES, key=lambda s: abs(k - s) / s)
    if abs(k - best) / best < 0.03:
        return best
    return None


def solve(samples, max_width=4):
    """samples: [(raw_bytes, target_value)] -> ranked candidate list"""
    if len(samples) < 2:
        return []
    lens = {}
    for raw, _ in samples:
        lens.setdefault(len(raw), 0)
        lens[len(raw)] += 1
    out = []
    for L, cnt in lens.items():
        if cnt < 2:
            continue
        subset = [(r, v) for r, v in samples if len(r) == L]
        if len(set(round(v, 3) for _, v in subset)) < 2:
            continue
        for w in range(1, max_width + 1):
            for off in range(0, L - w + 1):
                for big in (True, False):
                    xs, ys = [], []
                    for raw, v in subset:
                        x = field_value(raw, off, w, big)
                        if x is None:
                            continue
                        xs.append(x)
                        ys.append(v)
                    if len(xs) < 2 or len(set(xs)) < 2:
                        continue
                    fit = lsq(xs, ys)
                    if fit is None:
                        continue
                    k, b, r2, maxres = fit
                    if k <= 0:
                        continue          # 电压应随原值单调正向
                    nice = nice_scale_of(k)
                    # 若系数是"好看的"值，用它重算偏置与残差，更贴近实际实现
                    if nice:
                        b2 = sum(y - nice * x for x, y in zip(xs, ys)) / len(xs)
                        k_use, b_use = nice, b2
                    else:
                        k_use, b_use = k, b
                    pred_res = max(abs(y - (k_use * x + b_use)) for x, y in zip(xs, ys))
                    out.append(dict(
                        offset=off, width=w, big=big, k=k_use, b=b_use,
                        r2=r2, maxres=pred_res, n=len(xs), nice=nice is not None, L=L
                    ))
    # 排序：
    #   1) 系数是好系数（0.05/0.1 这类）优先
    #   2) 偏置接近 0 优先 —— 真实字段一般直接编码绝对值，
    #      需要叠一个 64V 常数偏置的，多半是"少读了高位字节"的假解
    #   3) 残差、位宽、偏移
    out.sort(key=lambda d: (0 if d["nice"] else 1,
                            0 if abs(d["b"]) < 1.0 else 1,
                            round(d["maxres"], 2),
                            d["width"], d["offset"]))
    return out


def read_csv(path):
    """返回 marks: [(tag, raw_bytes, stated_v, stated_kmh, epoch_ms, gps_kmh)] 以及 frames"""
    marks = []
    frames = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for row in csv.reader(fh):
            if len(row) < 5 or row[0] == "epoch_ms":
                continue
            kind = row[1].strip()
            ts = int(row[0]) if row[0].strip().isdigit() else 0
            if kind == "MAT":
                if len(row) >= 7 and looks_hex(row[6]):
                    tag = row[2].strip() or "?"
                    hexs = row[6]
                    i_v, i_s, i_g = 3, 4, 5
                else:
                    tag = "?"
                    hexs = row[5] if len(row) > 5 else row[4]
                    i_v, i_s, i_g = 2, 3, 4
                raw = hex_to_bytes(hexs)
                if not raw:
                    continue
                try:
                    v = float(row[i_v])
                except (ValueError, IndexError):
                    continue
                try:
                    spd = float(row[i_s])
                except (ValueError, IndexError):
                    spd = float("nan")
                try:
                    gps = float(row[i_g])
                except (ValueError, IndexError):
                    gps = float("nan")
                marks.append((tag, raw, v, spd, ts, gps))
            elif kind == "FRM":
                raw = hex_to_bytes(row[3] if len(row) > 3 else row[2])
                if raw:
                    frames.append(raw)
    return marks, frames


def check_span(subset):
    vs = [m[2] for m in subset]
    distinct = sorted(set(round(v, 2) for v in vs))
    print("   samples: %d, distinct voltages: %d %s" % (len(subset), len(distinct), distinct))
    if len(distinct) < 3:
        print("   !! 少于 3 个不同电压：任何 2 点都能拟合，结论不可信。")
    if distinct and (max(distinct) - min(distinct)) < 3.0:
        print("   !! 电压跨度 < 3V：区分度太低，容易把别的字段拟合进来。")
    return distinct


def report_one(tag, subset):
    print("\n=== channel %s (%d samples) ===" % (tag, len(subset)))
    check_span(subset)
    cands = solve([(m[1], m[2]) for m in subset])
    if not cands:
        print("   没有可用候选（帧长度不一致或数值全同）")
        return None
    print("   offset len endian  scale        bias      R2      maxRes  nice  n")
    print("   " + "-" * 70)
    for d in cands[:10]:
        print("   %-6d %-3d %-6s %-12.5g %-8.3g %-7.4f %-7.3f %-5s %d" % (
            d["offset"], d["width"], "BE" if d["big"] else "LE",
            d["k"], d["b"], d["r2"], d["maxres"], "Y" if d["nice"] else "-", d["n"]))
    good = [d for d in cands if d["maxres"] < 0.6 and d["r2"] > 0.95]
    if good:
        best = good[0]
        print("   >>> 这个通道的候选：偏移=%d 位宽=%d 大端=%d 系数=%.6g 偏置=%.6g"
              " (R2=%.4f 残差=%.3fV n=%d)" % (
                  best["offset"], best["width"], 1 if best["big"] else 0,
                  best["k"], best["b"], best["r2"], best["maxres"], best["n"]))
        return best
    print("   >>> 该通道没有候选能落在 0.6V 残差内：样本跨度不够 / 帧被压缩加密 / 电压不在这条通道。")
    return None


def report(marks):
    if not marks:
        print("no MAT rows. 先按 README 用 App 的“标记”按钮采集样本。")
        return
    tags = sorted(set(m[0] for m in marks))
    print("channels in samples: %s" % tags)
    bests = []
    for tag in tags:
        subset = [m for m in marks if m[0] == tag]
        b = report_one(tag, subset)
        if b:
            b["tag"] = tag
            bests.append(b)

    print("\n" + "=" * 62)
    if bests:
        bests.sort(key=lambda d: (d["maxres"], -d["n"]))
        b = bests[0]
        print(">>> 结论：建议把下面这组填进 App「设置 → 电压字段」")
        print("    偏移(offset)=%d  位宽(len)=%d  大端=%d  系数(scale)=%.6g  偏置(bias)=%.6g"
              % (b["offset"], b["width"], 1 if b["big"] else 0, b["k"], b["b"]))
        print("    来自通道 %s，R2=%.4f 最大残差=%.3f V 样本数=%d"
              % (b["tag"], b["r2"], b["maxres"], b["n"]))
    else:
        print(">>> 没有任何通道给出可用候选。下一步：确认 AE02 是否在推数据 / 加大电压跨度 / 考虑帧被扰动。")

    # 速度字段
    for tag in tags:
        speeds = [(m[1], m[3]) for m in marks if m[0] == tag and not math.isnan(m[3])]
        if len(speeds) >= 3 and len(set(round(s, 1) for _, s in speeds)) >= 3:
            print("\n--- speed candidates (%s) ---" % tag)
            for d in solve(speeds)[:8]:
                print("offset=%-3d len=%-2d %s  scale=%-9.5g bias=%-8.3g R2=%.4f maxRes=%.3f" % (
                    d["offset"], d["width"], "BE" if d["big"] else "LE",
                    d["k"], d["b"], d["r2"], d["maxres"]))
    if len(marks) < 3:
        print("\n(速度样本不足，跳过速度求解)")


def selftest():
    """造一批帧：电压放在 offset 2..3，小端，0.05V/LSB；再加噪声。求解器应能反解出来。"""
    random.seed(7)
    marks = []
    volts = [76.4, 75.1, 73.8, 72.0, 70.6, 69.2, 67.8, 66.5]
    for v in volts:
        raw_val = int(round(v / 0.05))
        raw = [0xC9, 0x9C, raw_val & 0xFF, (raw_val >> 8) & 0xFF,
               random.randint(0, 255), random.randint(0, 255), 0x69, 0x59,
               random.randint(0, 255), 0x81, 0x1E, 0x4D, 0x75, 0x75, 0x75, 0x75,
               0x1D, 0x1D, 0x1D, 0x1D]
        marks.append(("0xFFF6", raw, v + random.choice([0, 0.1, -0.1]), 0.0, 0, 0.0))
    cands = solve([(m[1], m[2]) for m in marks])
    best = cands[0]
    ok = (best["offset"] == 2 and best["width"] == 2 and not best["big"]
          and abs(best["k"] - 0.05) < 1e-6 and best["maxres"] < 0.6)
    print("SELFTEST: best = offset=%d len=%d %s scale=%.6g maxRes=%.3f"
          % (best["offset"], best["width"], "BE" if best["big"] else "LE", best["k"], best["maxres"]))
    print("SELFTEST:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        return selftest()
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    marks, frames = read_csv(args[0])
    print("file: %s  (frames logged: %d)" % (args[0], len(frames)))
    report(marks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
