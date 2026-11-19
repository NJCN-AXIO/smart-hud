"""在微信小程序的 wxapkg 里找育宁那个包，并判断加密与否。

用法: python tools/scan_wxapkg.py "C:\\Users\\user\\AppData\\Roaming\\Tencent\\xwechat" out.txt
"""
import os, sys

root = sys.argv[1]
NEEDLES = [b"\xe8\x82\xb2\xe8\x93\x9d", b"\xe5\xbc\x9d\xee\x94\x8e".replace(b"\xee\x94\x8e", b"\xae"), b"yuning", b"YN", b"YNBT"]

hits = []
count = 0
for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        if not fn.endswith(".wxapkg"):
            continue
        p = os.path.join(dirpath, fn)
        try:
            chunk = open(p, "rb").read()
        except Exception:
            continue
        count += 1
        head = chunk[:16]
        magic = ""
        if chunk[:7] == b"V1MMWX":
            magic = "V1MMWX(encrypted)"
        elif chunk[:1] == b"\xbe":
            magic = "v1-plain"
        elif chunk[:8] == b"APP_V8??":
            magic = "APP_V8"
        else:
            magic = "unknown:" + chunk[:4].hex()
        text = chunk.lower()
        found = [n for n in NEEDLES if n.lower() in text]
        if found:
            hits.append((p, len(chunk), magic, found))

print("wxapkg files scanned:", count)
print("hits by 育宁/yuning:", len(hits))
for p, size, magic, found in hits:
    print("%10d  %-16s  %s  %s" % (size, magic, found, p))
