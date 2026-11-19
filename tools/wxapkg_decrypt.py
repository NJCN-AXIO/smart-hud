"""解出 PC 微信的 V1MMWX 加密 wxapkg，并找出含 BLE 关键字的那个小程序。

算法（公开方案）：
  key   = PBKDF2-HMAC-SHA1(appid, "saltiest", 1000, 32)
  IV    = b"the iv: 16 bytes"
  文件  = "V1MMWX" + AES256-CBC(前 1024 字节) + 其余字节 XOR ord(appid[-2])
解密后应是标准 wxapkg（头字节 0xBE）。

用法: python tools/wxapkg_decrypt.py <wxchat root> <outdir>
"""
import sys, os, re, hashlib
from Crypto.Cipher import AES

ROOT = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

IV = b"the iv: 16 bytes"
NEEDLES = [b"fff6", b"ae02",
           "\u8f6c\u628a".encode(), "\u4eea\u8868".encode(), "\u7231\u739b".encode(),
           "\u7535\u6c60\u7535\u538b".encode(), "P\u6863".encode(),
           "\u80b2\u5b81".encode(), b"yuning"]
APPID_RE = re.compile(r"[\\/]packages[\\/](wx[0-9a-fA-F]{16})[\\/]")

def decrypt(data, appid):
    if not data.startswith(b"V1MMWX"):
        return None
    key = hashlib.pbkdf2_hmac("sha1", appid.encode(), b"saltiest", 1000, 32)
    cipher = AES.new(key, AES.MODE_CBC, IV)
    try:
        head = cipher.decrypt(data[6:6 + 1024])
    except Exception:
        return None
    # PKCS7：最后一字节是填充长度
    pad = head[-1]
    if 1 <= pad <= 16:
        head = head[:-pad]
    k = ord(appid[-2]) if len(appid) >= 2 else 0x66
    table = bytes([i ^ k for i in range(256)])
    tail = data[6 + 1024:].translate(table)
    plain = head + tail
    if plain[:1] != b"\xbe":
        return None
    return plain

found = []
bad = 0
for dirpath, dirnames, filenames in os.walk(ROOT):
    for fn in filenames:
        if not fn.endswith(".wxapkg"):
            continue
        p = os.path.join(dirpath, fn)
        m = APPID_RE.search(p)
        if not m:
            continue
        appid = m.group(1)
        try:
            data = open(p, "rb").read()
        except Exception:
            continue
        plain = decrypt(data, appid)
        if plain is None:
            bad += 1
            continue
        low = plain.lower()
        hits = [n for n in NEEDLES if n.lower() in low]
        if hits:
            found.append((appid, p, len(data), len(plain), hits))
            out = os.path.join(OUT, appid + "_" + fn.replace(".wxapkg", "") + ".wxapkg")
            with open(out, "wb") as fh:
                fh.write(plain)

print("decrypt failed:", bad)
print("candidates:", len(found))
for appid, p, size, plen, hits in found:
    print("%s  %8d -> %8d  %s" % (appid, size, plen, [h.decode("utf-8", "replace") for h in hits]))
    print("    " + p)
