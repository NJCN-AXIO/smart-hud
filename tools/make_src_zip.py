"""把 delivery-hud 源码打成 zip（排除构建产物、dist、日志）。

用法： python tools/make_src_zip.py <project_root> <out.zip> [version]
"""
import os
import sys
import zipfile

EXCLUDE_DIRS = {".gradle", "build", "dist", ".idea", "captures",
                # 育宁小程序的解包产物/明文包是第三方素材，留在 analysis/ 里备查，不进源码包
                "yuning_plain", "yuning_out", "__pycache__"}
EXCLUDE_FILES = {"build_out.txt", "build_protocol.txt", "build_engine.txt",
                 "build_app1.txt", "build_app2.txt", "build_clean.txt",
                 "local.properties"}
EXCLUDE_EXT = {".apk", ".zip", ".log"}


def _skip(fn: str) -> bool:
    """根目录的 build_*.txt 是构建日志，一并排掉。"""
    return fn in EXCLUDE_FILES or os.path.splitext(fn)[1] in EXCLUDE_EXT \
        or (fn.startswith("build_") and fn.endswith(".txt"))


def main() -> int:
    root = os.path.abspath(sys.argv[1])
    out = os.path.abspath(sys.argv[2])
    version = sys.argv[3] if len(sys.argv) > 3 else ""

    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
            for fn in filenames:
                if _skip(fn):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, root).replace(os.sep, "/")
                z.write(full, rel)
                count += 1

        if version:
            readme = "Delivery HUD v%s\n" % version
            z.writestr("VERSION.txt", readme)
            count += 1

    print("files=%d size=%d -> %s" % (count, os.path.getsize(out), out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
