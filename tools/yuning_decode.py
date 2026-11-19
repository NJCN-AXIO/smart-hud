"""按育宁小程序 app-service.js 里的还原公式，解 67B 帧。

小程序原代码（pages/index.vue，onBLECharacteristicValueChange 里）：
    if (201 != l[0] || 156 != l[1] || 204 != l[66]) return "不是指令";
    for (let e = 4; e <= 65; e++) {
        l[e] = l[e] - 11;
        l[e] = 85 ^ l[e];
        l[e] = l[e] - e;
        l[e] = 19 ^ l[e];
        l[e] = l[e] - e;
    }
之后：
    电压   = (256*f[63] + f[64]) / 100      → 保留 1 位
    转把   = 0.019 * f[42]
    故障位 = f[47] 的 bit0..bit7（转把/刹车/控制器/霍尔/欠压/…/防盗/P档）
    f[46] & 128 → MCU 供电异常
    型号   = f[10]（37 = 默认）
    车型   = f[57]（19=M1,20=M2,…）
    协议   = f[50]
    电压选择 = f[43]
    版本   = 256*f[7] + f[8]

用法: python tools/yuning_decode.py <raw_*.csv> [--every N]
"""
import sys, collections

path = sys.argv[1]
every = 1
for i, a in enumerate(sys.argv):
    if a == "--every":
        every = int(sys.argv[i + 1])


def unscramble(raw):
    """返回 f[]：l[0..3] 原样，l[4..65] 按小程序那五步还原。"""
    f = list(raw)
    for e in range(4, 66):
        v = f[e]
        v = v - 11
        v = 85 ^ (v & 0xFF) if v >= 0 else 85 ^ (v & 0xFF)
        v = v - e
        v = 19 ^ (v & 0xFF)
        v = v - e
        f[e] = v
    return f


fault_bits = {
    0x01: "转把故障", 0x02: "刹车", 0x04: "控制器故障", 0x08: "霍尔故障",
    0x10: "电池欠压", 0x20: "(空)", 0x40: "防盗", 0x80: "P档",
}

frames = []
with open(path, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) >= 4 and p[1] == "FRM":
            b = bytes.fromhex(p[3].replace(" ", ""))
            if len(b) >= 67:
                frames.append((int(p[0]), b))

print("frames:", len(frames))
if not frames:
    sys.exit()

vs = []
for ts, b in frames:
    if b[0] != 0xC9 or b[1] != 0x9C:
        continue
    f = unscramble(b)
    v = (256 * f[63] + f[64]) / 100.0
    vs.append((ts, v, f[42], f[47], f[10], f[57], f[50], f[43]))

print("\n-- 前 10 帧解出来的电压 --")
for ts, v, knob, flt, model, cartype, proto, vsel in vs[:10]:
    print("  v=%.1f  转把=%.1fV  故障=0x%02X(%s)  型号=%d 车型=%d 协议=%d 电压选择=%d"
          % (v, 0.019 * knob, flt,
             ",".join(n for bit, n in fault_bits.items() if flt & bit) or "无",
             model, cartype, proto, vsel))

print("\n-- 电压统计 --")
vals = [v for _, v, *_ in vs]
print("  count=%d  min=%.2f  max=%.2f  首=%.2f  末=%.2f"
      % (len(vals), min(vals), max(vals), vals[0], vals[-1]))
good = [v for v in vals if 30 <= v <= 100]
print("  落在 30–100V 内的比例: %d/%d" % (len(good), len(vals)))
hist = collections.Counter(round(v, 0) for v in good)
print("  分布（整数 V: 帧数）:", dict(sorted(hist.items())))

print("\n-- 每 %d 帧抽一条电压走势 --" % every)
for i in range(0, len(vs), every):
    ts, v, *_ = vs[i]
    print("  frame %4d  v=%.1f" % (i, v))

print("\n-- 故障位分布 --")
fc = collections.Counter(flt for _, _, _, flt, *_ in vs)
for flt, cnt in fc.most_common(8):
    print("  0x%02X x%-5d  %s" % (flt, cnt,
          ",".join(n for bit, n in fault_bits.items() if flt & bit) or "无"))
