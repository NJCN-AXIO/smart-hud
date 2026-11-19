#!/usr/bin/env python3
"""Inventory: per-connection traffic, advertising names, and per-byte variation."""
import sys, struct, collections

def recs(path):
    d = open(path, "rb").read()
    off, n = 16, len(d)
    while off + 24 <= n:
        o, incl, fl, dr, ts, us = struct.unpack(">6I", d[off:off + 24])
        off += 24
        pl = d[off:off + incl]
        off += incl
        yield ts + us / 1e6, fl, pl

def main():
    path = sys.argv[1]
    adv = collections.defaultdict(set)
    conns = []
    per_handle = collections.defaultdict(lambda: collections.Counter())
    att_by_handle = collections.defaultdict(list)
    h4 = collections.Counter()
    t0 = None

    for ts, fl, p in recs(path):
        if t0 is None:
            t0 = ts
        if not p:
            continue
        h4[p[0]] += 1
        b = p[1:]
        if p[0] == 4 and b and b[0] == 0x3E and len(b) > 2:
            sub = b[1]
            if sub == 0x02 and len(b) > 12:
                nrep = b[2]
                raw = b[3:]
                evt, at = raw[0], raw[1]
                addr = ":".join(f"{x:02X}" for x in raw[2:8][::-1])
                dlen = raw[8]
                ad = raw[9:9 + dlen]
                i = 0
                nm = None
                while i + 1 < len(ad):
                    ln, typ = ad[i], ad[i + 1]
                    if ln == 0:
                        break
                    v = ad[i + 2:i + 1 + ln]
                    if typ in (0x08, 0x09):
                        nm = v.decode("utf-8", "replace")
                    i += 1 + ln
                if nm:
                    adv[addr].add(nm)
                else:
                    adv[addr].add("(no name)")
            elif sub == 0x01 and len(b) > 14:
                st = b[2]
                hd = struct.unpack("<H", b[3:5])[0]
                addr = ":".join(f"{x:02X}" for x in b[8:14][::-1])
                conns.append((ts - t0, hd, addr, st))
        elif p[0] == 2 and len(b) >= 4:
            hf = struct.unpack("<H", b[0:2])[0]
            h = hf & 0xFFF
            pb = (hf >> 12) & 3
            dl = struct.unpack("<H", b[2:4])[0]
            l2 = b[4:4 + dl]
            if len(l2) < 4:
                per_handle[h]["SHORT"] += 1
                continue
            ln, cid = struct.unpack("<HH", l2[0:4])
            per_handle[h][f"pb{pb}/cid{cid:#06x}"] += 1
            if cid == 4 and len(l2) >= 5:
                a = l2[4:4 + ln]
                att_by_handle[h].append((ts - t0, a[0], a))

    print(f"== H4 packet types == {dict(h4)}")
    print("\n== advertising ==")
    for a in sorted(adv):
        print(f"  {a}  {sorted(adv[a])}")
    print("\n== LE connection complete ==")
    for c in conns:
        print(f"  t={c[0]:.2f}s handle=0x{c[1]:04X} addr={c[2]} status={c[3]}")
    print("\n== per-handle L2CAP mix ==")
    for h, cnt in sorted(per_handle.items()):
        print(f"  handle 0x{h:04X}: " + ", ".join(f"{k}={v}" for k, v in cnt.most_common(6)))
    print("\n== ATT frames per handle ==")
    for h, lst in att_by_handle.items():
        ops = collections.Counter(a[1] for a in lst)
        print(f"  handle 0x{h:04X}: n={len(lst)} ops=" + ", ".join(f"{k:#04x}:{v}" for k, v in ops.most_common()))

if __name__ == "__main__":
    main()
