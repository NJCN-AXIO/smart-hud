"""解开 wxapkg（解密后的明文包），把里面的文件摊到目录里。

用法: python tools/wxapkg_unpack.py <in.wxapkg> <outdir>
格式：0xBE + indexLen(4) + bodyLen(4) + 0xED + ... + fileCount(4)
      每条：nameLen(4), name, offset(4), size(4)
"""
import sys, os, struct

src, outdir = sys.argv[1], sys.argv[2]
data = open(src, "rb").read()
if data[:1] != b"\xbe":
    raise SystemExit("不是明文 wxapkg（第一字节不是 0xBE）")

index_len, body_len = struct.unpack(">II", data[2:10])
count = struct.unpack(">I", data[14:18])[0]
print("files:", count, "index_len:", index_len, "body_len:", body_len)

pos = 18
os.makedirs(outdir, exist_ok=True)
for _ in range(count):
    name_len = struct.unpack(">I", data[pos:pos + 4])[0]
    pos += 4
    name = data[pos:pos + name_len].decode("utf-8", "replace")
    pos += name_len
    off, size = struct.unpack(">II", data[pos:pos + 8])
    pos += 8
    body = data[off:off + size]
    dst = os.path.join(outdir, name.lstrip("/").replace("/", os.sep))
    os.makedirs(os.path.dirname(dst), exist_ok=True) if os.path.dirname(dst) else None
    with open(dst, "wb") as fh:
        fh.write(body)
print("done ->", outdir)
