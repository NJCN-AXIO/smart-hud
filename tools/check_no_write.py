#!/usr/bin/env python3
"""
红线自检：本工程绝不允许向控制器写数据。

允许的唯一例外：把 CCCD（0x2902，Client Characteristic Configuration Descriptor）
写成 ENABLE_NOTIFICATION_VALUE（01 00）来开启通知 —— 那是 BLE 标准的"订阅"操作，
写在描述符表里，不是往控制器的指令通道发数据。

用法：
    python tools/check_no_write.py            # 扫描整个工程源码
    python tools/check_no_write.py <root>     # 指定工程根目录

退出码：0 = 通过；1 = 发现违规写操作。
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..")

SRC_DIRS = ["app/src", "engine/src", "protocol/src", "core/src"]
EXTS = (".java", ".kt")

# 违反"只读"的写法：一旦出现就必须判失败
FORBIDDEN = [
    (re.compile(r"\bwriteCharacteristic\b"), "写特征值（= 往控制器发数据）"),
    (re.compile(r"\bexecuteReliableWrite\b"), "提交可靠写"),
    (re.compile(r"\bbeginReliableWrite\b"), "开始可靠写"),
    (re.compile(r"BluetoothGattCharacteristic\.WRITE_TYPE_"), "写类型常量"),
    (re.compile(r"\bWRITE_NO_RESPONSE\b"), "写无响应"),
]

# 允许的唯一写：CCCD 描述符 = 开通知
ALLOW_CCCD = re.compile(r"writeDescriptor")
ALLOW_VALUE = re.compile(r"ENABLE_NOTIFICATION_VALUE|ENABLE_INDICATION_VALUE")
# Nordic 官方库的"订阅"入口：库内部只写 CCCD(0x2902) 并置 01 00，不写特征值。
# 它是成熟的等价写法，所以在这里明确登记，而不是靠"看不见就算过"。
ALLOW_SUBSCRIBE = re.compile(r"\benableNotifications\b|\benableIndications\b")


def scan():
    hits = []
    cccd_writes = []
    subscribes = []
    for d in SRC_DIRS:
        base = os.path.join(ROOT, d)
        if not os.path.isdir(base):
            continue
        for dirpath, _, files in os.walk(base):
            for fn in files:
                if not fn.endswith(EXTS):
                    continue
                p = os.path.join(dirpath, fn)
                rel = os.path.relpath(p, ROOT)
                try:
                    text = open(p, encoding="utf-8").read()
                except UnicodeDecodeError:
                    text = open(p, encoding="gbk", errors="replace").read()
                for lineno, line in enumerate(text.splitlines(), 1):
                    code = line.split("//")[0]      # 粗略去掉行尾注释
                    if code.strip().startswith("*") or code.strip().startswith("/*"):
                        continue
                    for pat, why in FORBIDDEN:
                        if pat.search(code):
                            hits.append((rel, lineno, why, line.strip()))
                    if ALLOW_SUBSCRIBE.search(code):
                        subscribes.append((rel, lineno, line.strip()))
                    if ALLOW_CCCD.search(code):
                        cccd_writes.append((rel, lineno, line.strip()))
                        # setValue 和 writeDescriptor 通常隔一两行，取上下 6 行判断"值是不是 01 00"
                        ctx = "\n".join(text.splitlines()[max(0, lineno - 7):lineno + 5])
                        if not ALLOW_VALUE.search(ctx):
                            hits.append((rel, lineno, "描述了写 CCCD 但附近没有 ENABLE_NOTIFICATION_VALUE",
                                         line.strip()))
    return hits, cccd_writes, subscribes


def main():
    hits, cccd, subs = scan()
    print("=" * 62)
    print("红线自检：除了 CCCD 开通知，不向控制器写任何数据")
    print("=" * 62)

    print("\n[允许] 手写 CCCD 写（开启通知）：")
    if not cccd:
        print("  (无)")
    for rel, ln, line in cccd:
        print("  %s:%d  %s" % (rel, ln, line))

    print("\n[允许] 库的订阅入口（库内部只写 CCCD 0x2902）：")
    if not subs:
        print("  (无)")
    for rel, ln, line in subs:
        print("  %s:%d  %s" % (rel, ln, line))

    print("\n[禁止] 写特征值 / 可靠写 / 写类型常量：")
    if not hits:
        print("  未发现 —— 通过")
    for rel, ln, why, line in hits:
        print("  ✗ %s:%d  %s\n      %s" % (rel, ln, why, line))

    print("\n结论：", end="")
    if hits:
        print("失败（%d 处违规）" % len(hits))
        return 1
    if not cccd and not subs:
        print("失败（一处订阅都没有：收不到通知，等于没连上）")
        return 1
    print("通过（CCCD 订阅路径 %d 处 = 手写 %d + 库 %d）" % (len(cccd) + len(subs), len(cccd), len(subs)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
