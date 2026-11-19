# -*- coding: utf-8 -*-
"""v1.0.44 验收工具：拿历史日志的 vm（真正喂给电量表的电压）重放两条管线，
对比"旧（只有 6 秒中位数）"和"新（中位数 + 死区 + 限速）"的跳变幅度。

用法：python tools/replay_soc.py "D:\\下载\\raw_20261118_200840.csv"
"""
import sys, re
import statistics

V_REF = [76.5, 75.6, 74.7, 73.8, 72.9, 72.0, 71.4, 70.8, 70.2, 69.6, 63.0]
SOC = [100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0]


def soc_of(v):
    if v != v:
        return float("nan")
    if v >= V_REF[0]:
        return 100.0
    if v <= V_REF[-1]:
        return 0.0
    for i in range(len(V_REF) - 1):
        hi, lo = V_REF[i], V_REF[i + 1]
        if hi >= v >= lo:
            k = (v - lo) / (hi - lo)
            return SOC[i + 1] + k * (SOC[i] - SOC[i + 1])
    return float("nan")


class Median6:
    def __init__(self, window=6000):
        self.w, self.buf = window, []

    def add(self, t, v):
        self.buf.append((t, v))
        self.buf = [(tt, vv) for tt, vv in self.buf if t - tt <= self.w][-64:]
        return statistics.median([vv for _, vv in self.buf])


class Display:
    def __init__(self, dead=3.0, down=2.0, up=2.0, snap=20.0):
        self.shown, self.last = float("nan"), 0
        self.dead, self.down, self.up, self.snap = dead, down, up, snap

    def on(self, now, target, allow_snap):
        if target != target:
            return self.shown
        t = min(100.0, max(0.0, target))
        if self.shown != self.shown or self.last <= 0:
            self.shown, self.last = t, now
            return self.shown
        dt = min(5.0, max(0.0, (now - self.last) / 1000.0))
        self.last = now
        d = t - self.shown
        if allow_snap and abs(d) >= self.snap:
            self.shown = t
            return self.shown
        if abs(d) <= self.dead:
            return self.shown
        step = (self.up if d > 0 else self.down) * (0.25 if dt <= 0 else dt)
        self.shown = min(t, self.shown + step) if d > 0 else max(t, self.shown - step)
        return self.shown


def parse(path):
    rows = []
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            p = line.split(",")
            if len(p) < 3 or p[1] != "REC":
                continue
            b = p[2]
            m = re.search(r"\bvm=([-0-9.]+|NaN|-)", b)
            s = re.search(r"\bv=([-0-9.]+|NaN|-)", b)
            if not m:
                continue
            try:
                vm = float(m.group(1))
            except ValueError:
                vm = float("nan")
            try:
                live = s and s.group(1) not in ("-", "NaN") and float(s.group(1)) > 40
            except ValueError:
                live = False
            rows.append((int(p[0]), vm, bool(live)))
    return rows


def jumps(seq):
    out = []
    prev = None
    for t, v in seq:
        if prev is not None and v == v and prev[1] == prev[1]:
            dt = (t - prev[0]) / 1000.0
            if dt <= 20 and abs(v - prev[1]) >= 5:
                out.append((t, dt, prev[1], v))
        prev = (t, v)
    return out


def run(path):
    rows = parse(path)
    old_seq, new_seq = [], []
    med = Median6()
    disp = Display()
    prev_live_t = 0
    for t, vm, live in rows:
        raw = soc_of(vm) if live else float("nan")
        o = med.add(t, raw) if raw == raw else (old_seq[-1][1] if old_seq else float("nan"))
        allow_snap = prev_live_t == 0 or t - prev_live_t > 60_000
        if live:
            prev_live_t = t
        n = disp.on(t, raw if raw == raw else float("nan"), allow_snap) if raw == raw else disp.on(t, float("nan"), False)
        old_seq.append((t, o))
        new_seq.append((t, n))
    print(path.split("\\")[-1])
    for name, seq in (("旧：仅 6s 中位数", old_seq), ("新：+死区+限速", new_seq)):
        j = jumps([(t, v) for t, v in seq])
        vals = [v for _, v in seq if v == v]
        print("  %-18s 跳变(>=5分/<=20s)=%-3d 全程 %s..%s" %
              (name, len(j), "%.0f" % min(vals) if vals else "-", "%.0f" % max(vals) if vals else "-"))
        for t, dt, a, b in j[:5]:
            print("      dt=%.1fs %.0f->%.0f" % (dt, a, b))


if __name__ == "__main__":
    for p in sys.argv[1:]:
        run(p)
        print()
