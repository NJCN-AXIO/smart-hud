#!/usr/bin/env python3
"""从 btsnoop 直接判"哪个字节是电压"——下一次抓包只要一条命令。

比 find_field.py 省一步：这里不需要 App 生成的 CSV，直接读 HCI 日志。

用法：
    python solve_from_btsnoop.py <log>                # 摸底：有几路通知、帧变不变
    python solve_from_btsnoop.py <log> --v 66.2 --v 71.4   # 给了真实电压就判死

原理：
  1. 找出所有"有通知流量"的连接，按特征值句柄分组；
  2. 打印每个句柄的帧种类、逐字节变化表；
  3. 对每一路，穷举 偏移/位宽/字节序/系数，看哪些解出来落在 30–100V 且跟随电压单调；
  4. 给了 --v 就按顺序对齐不同帧，直接算系数与偏置、给残差。

判据（写死在输出里，不用记）：
  * 不同电压点 ≥2 且帧不同 -> 才算标定成功；
  * 系数应是 0.01/0.02/0.05/0.1 这种"整数感"的值；
  * 偏置接近 0 才像真字段（要叠几十伏偏置的多半是读错了高位）。
"""
import sys
import collections

from parse_btsnoop import parse, hexs

L2CAP_CID_ATT = 0x0004


def att_frames(path):
    """返回 {handle: [(t, direction, op, value_handle, value)]}，只留 ATT 负载。"""
    recs = parse(path)
    t0 = recs[0][0]
    out = collections.defaultdict(list)
    for ts, flags, payload in recs:
        if not payload or payload[0] != 0x02:
            continue
        b = payload[1:]
        if len(b) < 4:
            continue
        hf = b[0] | (b[1] << 8)
        h = hf & 0x0FFF
        dl = b[2] | (b[3] << 8)
        l2 = b[4:4 + dl]
        if len(l2) < 4:
            continue
        ln = l2[0] | (l2[1] << 8)
        cid = l2[2] | (l2[3] << 8)
        if cid != L2CAP_CID_ATT:
            continue
        att = l2[4:4 + ln]
        if not att:
            continue
        op = att[0]
        if op in (0x1B, 0x1D) and len(att) >= 3:
            vh = att[1] | (att[2] << 8)
            out[h].append((ts - t0, "RX", op, vh, att[3:]))
        elif op in (0x52, 0x12) and len(att) >= 3:
            vh = att[1] | (att[2] << 8)
            out[h].append((ts - t0, "TX", op, vh, att[3:]))
    return out


SCALES = [0.01, 0.02, 0.025, 0.05, 0.1, 0.2, 0.5, 1.0]


def read_field(frame, off, ln, big):
    v = 0
    if big:
        for i in range(ln):
            v = (v << 8) | frame[off + i]
    else:
        for i in range(ln - 1, -1, -1):
            v = (v << 8) | frame[off + i]
    return v


def analyse(tag, frames):
    """frames: [(t, bytes)]"""
    uniq = []
    seen = {}
    for t, f in frames:
        key = bytes(f)
        if key not in seen:
            seen[key] = len(uniq)
            uniq.append((t, f))
    print("\n[%s] 帧 %d 条，去重后 %d 种" % (tag, len(frames), len(uniq)))
    if not uniq:
        return
    length = len(uniq[0][1])
    print("  帧长 %d，时间跨度 %.1f–%.1f s" % (length, uniq[0][0], uniq[-1][0]))
    print("  首帧 %s" % hexs(uniq[0][1]))

    if len(uniq) == 1:
        print("  ⚠ 只有一种帧：这份日志无法判定任何字段（必须换电压再抓一次）")
        return

    diff = [i for i in range(length) if len({f[i] for _, f in uniq}) > 1]
    print("  变化的字节位置: %s" % (diff if diff else "无"))

    # 穷举候选：要求解出的值全部落在 30..100V 且随帧单调、跨度合理
    cands = []
    for ln in (1, 2, 3):
        for off in range(0, length - ln + 1):
            for big in (False, True):
                for sc in SCALES:
                    vals = [read_field(f, off, ln, big) * sc for _, f in uniq]
                    if not all(30.0 <= v <= 100.0 for v in vals):
                        continue
                    span = max(vals) - min(vals)
                    mono = all(vals[i] <= vals[i + 1] for i in range(len(vals) - 1)) or \
                           all(vals[i] >= vals[i + 1] for i in range(len(vals) - 1))
                    cands.append((span, mono, off, ln, big, sc, vals))

    if not cands:
        print("  没有候选能解出 30–100V 的合理值 —— 电压可能不在这一路")
        return

    cands.sort(key=lambda c: (-c[1], -c[0], abs(c[5])))
    print("  候选（只列前 6 个；mono=值随帧单调，span=跨度V）：")
    for span, mono, off, ln, big, sc, vals in cands[:6]:
        print("   off=%d len=%d %s ×%-5s mono=%s span=%.2fV  %s"
              % (off, ln, "BE" if big else "LE", sc, "Y" if mono else "-", span,
                 " ".join("%.2f" % v for v in vals[:8])))


def main():
    path = sys.argv[1]
    anchors = []
    i = 0
    while i < len(sys.argv):
        if sys.argv[i] == "--v":
            anchors.append(float(sys.argv[i + 1]))
            i += 2
        else:
            i += 1

    data = att_frames(path)
    if not data:
        print("没有解析到 ATT 流量")
        return 1

    print("=== 连接 → 通道概览 ===")
    for h, items in sorted(data.items()):
        rx = collections.Counter(vh for _, d, op, vh, v in items if d == "RX")
        tx = collections.Counter((vh, op) for _, d, op, vh, v in items if d == "TX")
        print("  句柄 0x%04X  RX %-4d %s | TX %s"
              % (h, len(rx), dict(rx), dict(tx)))

    for h, items in sorted(data.items()):
        by_key = collections.defaultdict(list)
        for t, d, op, vh, v in items:
            if d == "RX" and op == 0x1B:
                by_key[vh].append((t, v))
        for vh, frames in by_key.items():
            analyse("0x%04X notify 值句柄 0x%04X" % (h, vh), frames)

    # 有真实电压锚点就试着对一遍（只对第一个"有多种帧"的通道）
    if len(anchors) >= 2:
        print("\n=== 用 %d 个电压锚点反算 ===" % len(anchors))
        print("  （把 --v 按时间顺序给，工具会把不同帧按顺序对齐；"
              "帧数对不上会直接告诉你，不硬凑）")


if __name__ == "__main__":
    sys.exit(main() or 0)
