#!/usr/bin/env python3
"""btsnoop_hci (H4) parser -> ATT/GATT dump for protocol reverse engineering.

Usage:
    python parse_btsnoop.py <log> [--out report.txt]

Prints:
  * device names / MACs seen in advertising & LE connection events
  * GATT service / characteristic discovery (UUID + value handle)
  * every ATT notification / indication / read-response / write (handle + hex)
"""
import sys, struct, collections, re

ATT_OP = {
    0x01: "ERROR_RSP", 0x02: "MTU_REQ", 0x03: "MTU_RSP",
    0x04: "FIND_INFO_REQ", 0x05: "FIND_INFO_RSP",
    0x06: "FIND_BY_TYPE_REQ", 0x07: "FIND_BY_TYPE_RSP",
    0x08: "READ_BY_TYPE_REQ", 0x09: "READ_BY_TYPE_RSP",
    0x0A: "READ_REQ", 0x0B: "READ_RSP",
    0x0C: "READ_BLOB_REQ", 0x0D: "READ_BLOB_RSP",
    0x0E: "READ_MULTI_REQ", 0x0F: "READ_MULTI_RSP",
    0x10: "READ_BY_GROUP_REQ", 0x11: "READ_BY_GROUP_RSP",
    0x12: "WRITE_REQ", 0x13: "WRITE_RSP",
    0x1A: "PREPARE_WRITE", 0x1B: "NOTIFY", 0x1C: "PREPARE_WRITE_RSP",
    0x1D: "INDICATE", 0x1E: "CONFIRM", 0x1F: "SIGNED_WRITE",
    0x52: "WRITE_CMD",
}


def parse(path):
    recs = []
    with open(path, "rb") as fh:
        data = fh.read()
    off = 16
    n = len(data)
    while off + 24 <= n:
        # btsnoop is big-endian per spec
        orig, incl, flags, drops, ts_sec, ts_usec = struct.unpack(">6I", data[off:off + 24])
        off += 24
        payload = data[off:off + incl]
        off += incl
        recs.append((ts_sec + ts_usec / 1e6, flags, payload))
    return recs


def hexs(b):
    return " ".join(f"{x:02X}" for x in b)


def main():
    path = sys.argv[1]
    out_path = None
    if "--out" in sys.argv:
        out_path = sys.argv[sys.argv.index("--out") + 1]
    recs = parse(path)

    stats = collections.Counter()
    adv_names = collections.defaultdict(set)
    adv_macs = set()
    conn_events = []
    att = []          # (ts, dir, opcode, handle, value)
    uuid_by_handle = {}
    char_defs = []    # (decl_handle, uuid, value_handle, props)
    services = []     # (start, end, uuid)
    t0 = recs[0][0] if recs else 0

    for ts, flags, p in recs:
        if not p:
            continue
        pkt = p[0]
        body = p[1:]
        if pkt == 0x01:
            stats["HCI_CMD"] += 1
        elif pkt == 0x04:
            stats["HCI_EVT"] += 1
            ev = body[0] if body else None
            # LE Meta Event
            if ev == 0x3E and len(body) > 2:
                sub = body[1]
                if sub == 0x02:  # advertising report
                    raw = body[2:]
                    # num_reports(1) + event_type(1) + addr_type(1) + addr(6) + len(1) + data
                    if len(raw) > 9:
                        evt_type = raw[1]
                        addr = ":".join(f"{x:02X}" for x in raw[3:9][::-1])
                        adv_macs.add(addr)
                        dlen = raw[9]
                        ad = raw[10:10 + dlen]
                        i = 0
                        while i + 1 < len(ad):
                            ln, typ = ad[i], ad[i + 1]
                            if ln == 0:
                                break
                            val = ad[i + 2:i + 1 + ln]
                            if typ in (0x08, 0x09):
                                try:
                                    adv_names[addr].add(val.decode("utf-8", "replace"))
                                except Exception:
                                    pass
                            if typ == 0x01:
                                adv_names[addr].add(f"[flags {val.hex()}]")
                            i += 1 + ln
                elif sub == 0x01:  # connection complete
                    if len(body) > 12:
                        st = body[2]
                        handle = struct.unpack("<H", body[3:5])[0]
                        addr = ":".join(f"{x:02X}" for x in body[8:14][::-1])
                        conn_events.append((ts - t0, handle, addr, st))
                elif sub == 0x03:  # connection update
                    pass
            elif ev == 0x05:  # disconnection complete
                if len(body) > 6:
                    handle = struct.unpack("<H", body[3:5])[0]
                    reason = body[6] if len(body) > 6 else -1
                    conn_events.append((ts - t0, handle, "DISCONNECT", reason))
        elif pkt == 0x02:
            stats["ACL"] += 1
            if len(body) < 4:
                continue
            hf = struct.unpack("<H", body[0:2])[0]
            handle = hf & 0x0FFF
            pb = (hf >> 12) & 0x3
            dlen = struct.unpack("<H", body[2:4])[0]
            l2 = body[4:4 + dlen]
            if len(l2) < 4:
                continue
            l2len, cid = struct.unpack("<HH", l2[0:4])
            if cid != 0x0004:
                stats[f"L2CAP_{cid:#06x}"] += 1
                continue
            a = l2[4:4 + l2len]
            if not a:
                continue
            op = a[0]
            name = ATT_OP.get(op, f"OP_{op:#04x}")
            stats["ATT_" + name] += 1
            if name == "NOTIFY" or name == "INDICATE":
                if len(a) >= 3:
                    h = struct.unpack("<H", a[1:3])[0]
                    att.append((ts - t0, "N", h, a[3:]))
            elif name == "WRITE_REQ" or name == "WRITE_CMD":
                if len(a) >= 3:
                    h = struct.unpack("<H", a[1:3])[0]
                    att.append((ts - t0, "W", h, a[3:]))
            elif name == "READ_RSP":
                if len(a) >= 3:
                    h = struct.unpack("<H", a[1:3])[0]
                    att.append((ts - t0, "R", h, a[3:]))
            elif name == "READ_BY_TYPE_RSP":
                if len(a) >= 2:
                    ln = a[1]
                    for i in range(2, len(a) - ln + 1, ln):
                        e = a[i:i + ln]
                        if ln >= 5:
                            uh = struct.unpack("<H", e[0:2])[0]
                            props = e[2]
                            vh = struct.unpack("<H", e[3:5])[0]
                            uuid = e[5:]
                            char_defs.append((uh, uuid, vh, props))
                            uuid_by_handle[vh] = uuid
                        elif ln == 2:
                            uh = struct.unpack("<H", e[0:2])[0]
                            uuid = a[i + 2:i + ln] if False else None
            elif name == "READ_BY_GROUP_RSP":
                if len(a) >= 2:
                    ln = a[1]
                    for i in range(2, len(a) - ln + 1, ln):
                        e = a[i:i + ln]
                        if ln >= 6:
                            s = struct.unpack("<H", e[0:2])[0]
                            en = struct.unpack("<H", e[2:4])[0]
                            services.append((s, en, e[4:]))
            elif name == "FIND_INFO_RSP":
                if len(a) >= 2:
                    fmt = a[1]
                    for i in range(2, len(a) - fmt + 1, fmt):
                        e = a[i:i + fmt]
                        if fmt == 4:
                            h = struct.unpack("<H", e[0:2])[0]
                            uuid_by_handle[h] = e[2:4]
    # ---- report ----
    lines = []
    add = lines.append
    add(f"file: {path}")
    add(f"records: {len(recs)}  span: {recs[-1][0]-t0:.1f}s" if recs else "records: 0")
    add("")
    add("== HCI/ATT counters ==")
    for k, v in stats.most_common():
        add(f"  {k}: {v}")
    add("")
    add("== advertising names / addresses ==")
    for addr in sorted(adv_macs):
        add(f"  {addr}  names={sorted(adv_names.get(addr, []))}")
    add("")
    add("== connections ==")
    for c in conn_events:
        add(f"  t={c[0]:.2f}s handle=0x{c[1]:04X} {c[2]} st/reason={c[3]}")
    add("")
    add("== GATT services ==")
    for s, e, u in services:
        uu = " ".join(f"{x:02X}" for x in u)
        add(f"  handles 0x{s:04X}-0x{e:04X}  uuid={uu}")
    add("")
    add("== characteristic declarations ==")
    for dh, uuid, vh, props in char_defs:
        add(f"  decl=0x{dh:04X} value=0x{vh:04X} props=0x{props:02X} uuid={' '.join(f'{x:02X}' for x in uuid)}")
    add("")
    # per-handle payload buckets
    add("== ATT payload handles (N=notify/indicate, R=read, W=write) ==")
    byh = collections.defaultdict(list)
    for ts, kind, h, v in att:
        byh[(kind, h)].append((ts, v))
    for (kind, h), lst in sorted(byh.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        lens = collections.Counter(len(v) for _, v in lst)
        uu = uuid_by_handle.get(h)
        uus = " ".join(f"{x:02X}" for x in uu) if uu else "?"
        add(f"  [{kind}] handle=0x{h:04X} uuid={uus} count={len(lst)} lens={dict(lens)}")
        for ts, v in lst[:8]:
            add(f"      t={ts:8.2f}s  {hexs(v)}")
    add("")
    add("== all ATT frames (first 400) ==")
    for ts, kind, h, v in att[:400]:
        add(f"  t={ts:8.2f}s [{kind}] 0x{h:04X}  {hexs(v)}")

    txt = "\n".join(lines)
    if out_path:
        with open(out_path, "w", encoding="utf-8") as fh:
            fh.write(txt)
    print("\n".join(lines[:120]))


if __name__ == "__main__":
    main()
