#!/usr/bin/env python3
"""把一条连接上的 ATT 流量摊开，专看"写包 / 读回复 / 通知"三类。

目标：找出电压值（71.4V -> 0x0594）在这份抓包里除了 20 字节通知帧之外，
还有没有第二处独立出现的地方。

用法：
    python dump_gatt.py <log> [--conn 5] [--all]
"""
import sys
import collections

from parse_btsnoop import parse, hexs

L2CAP_CID_ATT = 0x0004


def le_handle(payload):
    """H4 type 0x02 = ACL。这份抓包里每条 ACL 都带完整 4 字节 L2CAP 头，
    所以直接按 analyze_conn.py 验证过的偏移取，不判 pb。"""
    if len(payload) < 4 or payload[0] != 0x02:
        return None, None
    b = payload[1:]
    hf = b[0] | (b[1] << 8)
    handle = hf & 0x0FFF
    dl = b[2] | (b[3] << 8)
    l2 = b[4:4 + dl]
    if len(l2) < 4:
        return handle, None
    ln = l2[0] | (l2[1] << 8)
    cid = l2[2] | (l2[3] << 8)
    if cid != L2CAP_CID_ATT:
        return handle, None
    return handle, l2[4:4 + ln]


def main():
    path = sys.argv[1]
    want = 5
    if "--conn" in sys.argv:
        want = int(sys.argv[sys.argv.index("--conn") + 1], 0)
    show_all = "--all" in sys.argv

    recs = parse(path)
    t0 = recs[0][0]

    # 按 (opcode, handle/…) 归类
    kinds = collections.Counter()
    per_kind = collections.defaultdict(list)

    for ts, flags, payload in recs:
        h, att = le_handle(payload)
        if att is None or len(att) == 0:
            continue
        if h != want:
            continue
        op = att[0]
        if op in (0x1B, 0x1D):                     # notify / indicate
            hl = att[1] | (att[2] << 8) if len(att) >= 3 else 0
            val = att[3:]
            key = ("NOTIFY", hl)
        elif op in (0x52, 0x12, 0x13, 0x1E):        # write cmd / write req / rsp
            if op == 0x1E:
                hl, val = 0, att[1:]
                key = ("CONFIRM", 0)
            else:
                hl = att[1] | (att[2] << 8) if len(att) >= 3 else 0
                val = att[3:]
                key = ("WRITE_CMD" if op == 0x52 else "WRITE_REQ", hl)
        elif op in (0x0B, 0x0D):                    # read response
            val = att[1:]
            key = ("READ_RSP", 0)
        elif op == 0x0A:                            # read request -> 记 handle
            hl = att[1] | (att[2] << 8)
            per_kind[("READ_REQ", hl)].append((ts - t0, b""))
            kinds[("READ_REQ", hl)] += 1
            continue
        else:
            key = ("OP%02X" % op, 0)
            val = att[1:]

        kinds[key] += 1
        per_kind[key].append((ts - t0, val))

    print("=== 连接句柄 0x%04X 的 ATT 分类 ===" % want)
    for k, n in sorted(kinds.items(), key=lambda kv: -kv[1]):
        print("  %-24s %6d" % ("%s 0x%04X" % k, n))

    print("\n=== 每一类的首末样本（值截断到 70 字节）===")
    for k, n in sorted(kinds.items(), key=lambda kv: -kv[1]):
        vals = per_kind[k]
        if not vals:
            print("\n[%s 0x%04X] 共 %d 次（无负载）" % (k[0], k[1], n))
            continue
        uniq = len({v for _, v in vals})
        print("\n[%s 0x%04X] 共 %d 次，去重后 %d 种" % (k[0], k[1], n, uniq))
        shown = 0
        seen = set()
        for off, v in vals:
            if show_all:
                pick = True
            else:
                pick = v not in seen
            if not pick:
                continue
            seen.add(v)
            print("  t=%8.3fs len=%3d  %s" % (off, len(v), hexs(v[:70])))
            shown += 1
            if shown >= 6 and not show_all:
                print("  …（其余 %d 条重复/略）" % (len(vals) - shown))
                break


if __name__ == "__main__":
    main()
