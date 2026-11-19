# -*- coding: utf-8 -*-
"""蓝牙链路体检：连接/断开次数、断线时长、是否反复 status=-5。"""
import sys, os, re
from datetime import datetime, timezone, timedelta

TZ = timezone(timedelta(hours=8))


def ts_str(ms):
    return datetime.fromtimestamp(ms / 1000, TZ).strftime("%H:%M:%S")


def run(path):
    events = []
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            p = line.split(",")
            if len(p) < 3 or p[1] != "SYS":
                continue
            t, msg = int(p[0]), p[2]
            if msg.startswith("conn:"):
                events.append((t, msg))
            elif msg.startswith("build") or msg.startswith("service start"):
                events.append((t, msg))
    print("==", os.path.basename(path))
    print("   连接类事件 %d 条" % len(events))
    kinds = {}
    for t, m in events:
        key = re.sub(r"\(.*?\)", "", m)
        key = re.sub(r"11:22:33:44:55:66", "<dev>", key)
        kinds[key] = kinds.get(key, 0) + 1
    for k, v in sorted(kinds.items(), key=lambda x: -x[1]):
        print("   %-34s %d" % (k[:34], v))
    # 断档时长：ready -> disconnect
    ready_ts = None
    total_lost = 0
    lost_windows = []
    for t, m in events:
        if "通道就绪" in m:
            ready_ts = t
        elif "断开" in m and ready_ts:
            lost = t - ready_ts
            lost_windows.append((ts_str(ready_ts), ts_str(t), lost / 1000))
            total_lost += lost
            ready_ts = None
    print("   有数据的窗口 %d 个，累计 %.0f 秒" % (len(lost_windows), total_lost / 1000))
    for a, b, s in lost_windows[:12]:
        print("      %s -> %s  %.0fs" % (a, b, s))
    fails = [t for t, m in events if "连接失败" in m]
    if fails:
        print("   status=-5 次数 %d" % len(fails))
    print()


if __name__ == "__main__":
    for p in sys.argv[1:]:
        run(p)
