"""把 btsnoop 里某条连接的 ATT 往来按时间摊开：MTU、写包、通知包。

用法: python tools/capture_timeline.py <btsnoop> --conn 5
"""
import sys, struct, collections

path = sys.argv[1]
conn = 5
for i, a in enumerate(sys.argv):
    if a == "--conn":
        conn = int(sys.argv[i + 1], 0)

ATT_HANDLE_ATTR = 0x1B
ATT_WRITE_CMD = 0x52
ATT_WRITE_REQ = 0x12
ATT_NOTIFY = 0x1B
ATT_READ_REQ = 0x0A
ATT_READ_RSP = 0x0B
ATT_MTU_REQ = 0x02
ATT_MTU_RSP = 0x03

raw = open(path, "rb").read()
# btsnoop: 16 字节头，随后每条记录 24 字节头：len(4) incl_len(4) flags(4) drops(4) ts(8, 微秒)
pos = 16
events = []
while pos + 24 <= len(raw):
    orig_len, inc_len, flags, drops, ts = struct.unpack(">IIIIQ", raw[pos:pos + 24])
    pos += 24
    pkt = raw[pos:pos + inc_len]
    pos += inc_len
    if len(pkt) < 4:
        continue
    h4type = pkt[0]
    if h4type != 0x02:      # 只要 ACL
        continue
    handle_flags, dlen = struct.unpack("<HH", pkt[1:5])
    handle = handle_flags & 0x0FFF
    if handle != conn:
        continue
    l2cap = pkt[5:5 + dlen]
    if len(l2cap) < 4:
        continue
    cid, alen = struct.unpack("<HH", l2cap[:4])[::-1]
    att = l2cap[4:4 + alen]
    if not att:
        continue
    op = att[0]
    info = {"ts": ts, "op": op, "len": len(att)}
    if op == ATT_MTU_REQ:
        info["mtu"] = struct.unpack("<H", att[1:3])[0]
    elif op == ATT_MTU_RSP:
        info["mtu"] = struct.unpack("<H", att[1:3])[0]
    elif op in (ATT_WRITE_REQ, ATT_WRITE_CMD) and len(att) >= 3:
        info["handle"] = struct.unpack("<H", att[1:3])[0]
        info["data"] = att[3:]
    elif op == ATT_NOTIFY:
        info["handle"] = struct.unpack("<H", att[1:3])[0]
        info["data"] = att[3:]
    events.append(info)

print("ACL packets on handle 0x%04X: %d" % (conn, len(events)))
print("\n-- MTU --")
for e in events:
    if e["op"] in (ATT_MTU_REQ, ATT_MTU_RSP):
        print("  ts=%d op=%02X mtu=%d" % (e["ts"], e["op"], e.get("mtu", -1)))

notifies = [e for e in events if e["op"] == ATT_NOTIFY]
writes = [e for e in events if e["op"] in (ATT_WRITE_CMD, ATT_WRITE_REQ)]
print("\n-- op histogram --")
hist = collections.Counter(e["op"] for e in events)
for op, cnt in sorted(hist.items()):
    print("  op=0x%02X  %d" % (op, cnt))

print("\n-- writes: %d --" % len(writes))
for w in writes:
    h = w.get("handle", -1)
    d = w.get("data", b"")
    print("  ts=%d op=0x%02X handle=0x%04X len=%d" % (w["ts"], w["op"], h, len(d)))
    print("     " + " ".join("%02X" % b for b in d))

print("\n-- notifies: %d --" % len(notifies))
by_len = collections.Counter(len(n["data"]) for n in notifies)
print("  lengths:", dict(by_len))
uniq = collections.Counter(" ".join("%02X" % b for b in n["data"]) for n in notifies)
print("  distinct payloads:", len(uniq))
for payload, cnt in uniq.most_common(5):
    print("   x%-4d %s" % (cnt, payload))

if notifies:
    n0 = notifies[0]["data"]
    # 和 67B 广播帧的前 N 字节对比
    ref = bytes.fromhex("C99C9405595969596A811E4D757575751D1D1D1D2581257F0D0D2A0D757575751D1D1D3738"
                        "2BF51972724926490A496911120E16F5A9CD3E41D8F34A494949E6C1FDCC")
    same = n0[:len(ref)] == ref[:len(n0)]
    print("\n  首个通知 vs 67B 广播帧前缀一致: %s" % same)
    print("  通知内容: " + " ".join("%02X" % b for b in n0))
    print("  67B 前 %d 字节: " % len(n0) + " ".join("%02X" % b for b in ref[:len(n0)]))
