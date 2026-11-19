# -*- coding: utf-8 -*-
"""v1.0.44 复盘：抽取 REC 行，量化"电量跳变 / 欠压崩溃 / 断连冻结"三类现象。"""
import csv, re, sys, os, collections

KEYS = ["rv", "v", "vm", "dc", "src", "sag", "thr", "spd", "km", "odo", "soc", "sra", "rng", "er", "vn", "vx"]


def parse_rec(line):
    parts = line.split(",")
    if len(parts) < 3 or parts[1] != "REC":
        return None
    body = parts[2]
    d = {"ts": parts[0]}
    for k in KEYS:
        m = re.search(r"%s=([-0-9.]+|NaN|-)" % k, body)
        if m:
            s = m.group(1)
            try:
                d[k] = float(s)
            except ValueError:
                d[k] = None
        else:
            d[k] = None
    return d


def analyze(path):
    recs = []
    crash = 0
    ex = collections.Counter()
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            if ",CRASH" in line or "CRASH" in line.split(",")[1:2]:
                crash += 1
            p = line.split(",")
            if len(p) > 1 and p[1] in ("SYS",) and len(p) > 2:
                for tag in ("tick-ex", "frame-ex", "fix-ex"):
                    if tag in p[2]:
                        ex[tag] += 1
            r = parse_rec(line)
            if r:
                recs.append(r)
    if not recs:
        print("%-28s 无 REC" % os.path.basename(path))
        return
    # 电量跳变：相邻 REC 间隔<=20s 且 |dsoc|>=5
    jumps = []
    for a, b in zip(recs, recs[1:]):
        if a["soc"] is None or b["soc"] is None:
            continue
        dt = (float(b["ts"]) - float(a["ts"])) / 1000.0
        if dt <= 20 and abs(b["soc"] - a["soc"]) >= 5:
            jumps.append((b["ts"], dt, a["soc"], b["soc"], a["v"], b["v"], b["thr"], b["spd"]))
    # 欠压：v<63 的占比；同时 soc=0
    low = [r for r in recs if r["v"] is not None and r["v"] < 63]
    soc0 = [r for r in recs if r["soc"] is not None and r["soc"] <= 1]
    live = [r for r in recs if r["src"] == 1]
    stale = [r for r in recs if r["v"] is None and r["rv"] is not None]
    print("%-28s REC=%-5d 崩溃=%-2d 异常=%s" % (os.path.basename(path), len(recs), crash, dict(ex) or "-"))
    print("   v<63V: %d 行  soc<=1: %d 行  实时(src=1): %d  断连冻结(rv有/v无): %d" %
          (len(low), len(soc0), len(live), len(stale)))
    print("   电量跳变(<=20s,>=5点): %d 次" % len(jumps))
    for j in jumps[:8]:
        ts, dt, s0, s1, v0, v1, thr, spd = j
        print("     %s dt=%.1fs soc %.0f->%.0f  v %s->%s thr=%s spd=%s" % (ts, dt, s0, s1, v0, v1, thr, spd))
    # 电压区间
    vs = [r["v"] for r in recs if r["v"] is not None]
    if vs:
        print("   实时电压范围 %.2f..%.2f" % (min(vs), max(vs)))
    # 总里程进度
    odo = [r["odo"] for r in recs if r["odo"] is not None]
    if odo:
        print("   odo %.1f -> %.1f" % (odo[0], odo[-1]))


if __name__ == "__main__":
    for p in sys.argv[1:]:
        analyze(p)
        print()
