#!/usr/bin/env python3
"""Deep dive on the ATT connection (handle 0x0005): dump frames, analyse variation."""
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

def hx(b):
    return " ".join(f"{x:02X}" for x in b)

ATT = {0x01:"ERR",0x02:"MTU_REQ",0x03:"MTU_RSP",0x04:"FIND_INFO_REQ",0x05:"FIND_INFO_RSP",
       0x06:"FIND_BY_TYPE_REQ",0x07:"FIND_BY_TYPE_RSP",0x08:"RBT_REQ",0x09:"RBT_RSP",
       0x0A:"READ_REQ",0x0B:"READ_RSP",0x0C:"BLOB_REQ",0x0D:"BLOB_RSP",0x0E:"MULTI_REQ",
       0x0F:"MULTI_RSP",0x10:"RBG_REQ",0x11:"RBG_RSP",0x12:"WRITE_REQ",0x13:"WRITE_RSP",
       0x1B:"NOTIFY",0x1C:"PREP_WRITE_RSP",0x1D:"INDICATE",0x1E:"CONFIRM",0x52:"WRITE_CMD",
       0x05:"PREP_WRITE_RSP"}

def main():
    path = sys.argv[1]
    target = int(sys.argv[2], 16) if len(sys.argv) > 2 else 5
    frames = []
    adv = {}
    t0 = None
    for ts, fl, p in recs(path):
        if t0 is None:
            t0 = ts
        if not p:
            continue
        b = p[1:]
        if p[0] == 4 and b and b[0] == 0x3E and len(b) > 4:
            sub = b[2]
            if sub == 0x02:
                pr = b[3:]
                evt, at = pr[0], pr[1]
                addr = ":".join(f"{x:02X}" for x in pr[2:8][::-1])
                dl = pr[8]
                ad = pr[9:9 + dl]
                nm = ""
                i = 0
                while i + 1 < len(ad):
                    ln, typ = ad[i], ad[i + 1]
                    if ln == 0:
                        break
                    v = ad[i + 2:i + 1 + ln]
                    if typ in (0x08, 0x09):
                        nm = v.decode("utf-8", "replace")
                    i += 1 + ln
                adv[addr] = nm or adv.get(addr, "")
            elif sub == 0x01 and len(b) > 15:
                st = b[3]
                hd = struct.unpack("<H", b[4:6])[0]
                addr = ":".join(f"{x:02X}" for x in b[10:16][::-1])
                adv[f"CONN:0x{hd:04X}"] = f"{addr} status={st} t={ts-t0:.1f}s"
        if p[0] != 2:
            continue
        hf = struct.unpack("<H", b[0:2])[0]
        h = hf & 0xFFF
        dl = struct.unpack("<H", b[2:4])[0]
        l2 = b[4:4 + dl]
        if len(l2) < 4:
            continue
        ln, cid = struct.unpack("<HH", l2[0:4])
        if cid != 4:
            continue
        if h != target:
            continue
        a = l2[4:4 + ln]
        if a:
            frames.append((ts - t0, a[0], a))

    print(f"== target handle 0x{target:04X}: {len(frames)} ATT frames ==")
    print("\n== advertising / connections seen ==")
    for k, v in adv.items():
        print(f"  {k}: {v}")

    print("\n== all frames ==")
    for ts, op, a in frames:
        nm = ATT.get(op, f"{op:#04x}")
        if op in (0x1B, 0x1D):
            h = struct.unpack("<H", a[1:3])[0]
            print(f"  t={ts:7.2f} {nm:10s} h=0x{h:04X} len={len(a)-3:2d}  {hx(a[3:])}")
        elif op in (0x09, 0x0B, 0x0D, 0x11, 0x05):
            h = struct.unpack("<H", a[1:3])[0] if op in (0x0B, 0x0D, 0x09) else 0
            print(f"  t={ts:7.2f} {nm:10s} h=0x{h:04X} len={len(a):3d}  {hx(a)}")
        else:
            print(f"  t={ts:7.2f} {nm:10s}  {hx(a)}")

    notif = [(ts, struct.unpack("<H", a[1:3])[0], a[3:]) for ts, op, a in frames if op in (0x1B, 0x1D)]
    if notif:
        print("\n== notification variation (per byte) ==")
        handles = sorted({h for _, h, _ in notif})
        for h in handles:
            vals = [v for _, hh, v in notif if hh == h]
            for i in range(max(len(v) for v in vals)):
                col = [v[i] if i < len(v) else None for v in vals]
                uniq = sorted({x for x in col if x is not None})
                if len(uniq) <= 6:
                    s = str(uniq)
                else:
                    s = f"{len(uniq)} distinct, min={min(x for x in uniq)} max={max(x for x in uniq)}"
                print(f"  h=0x{h:04X} byte[{i:2d}] = {s}")
        print("\n== distinct notification payloads ==")
        dist = collections.Counter(v for _, _, v in notif)
        for v, c in dist.most_common(20):
            print(f"  x{c:3d}  {hx(v)}")

if __name__ == "__main__":
    main()
