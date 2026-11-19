#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.50：一份日志的"体检表"——不猜，只把可疑的行挑出来。

用法： python tools/audit_log.py <csv...>

检查项
  A. odo 倒退（总里程变小）—— 只该在"对齐总里程"那一行之后出现
  B. Δodo 与 Δkm 不一致（>0.3km）—— 折叠/清零漏了或折重了
  C. 停着（spd<2 或 spd 缺失）时 soc 一次变 ≥3 分 —— 显示层没吃住
  D. rv 越界（<50V 或 >90V）—— 解码/字段错位
  E. src=4（冻结）却还在刷 rv —— 断开后仍写现场值
  F. 电量 0/100 极端值停留时间过长（可疑卡死）
"""
import re
import sys

NUM = re.compile(r"(\w+)=([^ ]+)")


def parse(line):
    if ",REC," not in line:
        return None
    d = {}
    for k, v in NUM.findall(line.split(",REC,", 1)[1]):
        d[k] = v
    return d


def fl(d, k):
    try:
        return float(d.get(k, "nan"))
    except Exception:
        return float("nan")


def main(paths):
    for path in paths:
        name = path.split("\\")[-1]
        bad_odo = bad_delta = bad_park = bad_rv = bad_frozen = 0
        soc_extreme_sec = 0
        prev = None
        prev_ts = 0
        # 先把所有 SYS 行收集起来：折叠/对齐/满电这些"合法跳变"要豁免
        sys_ts = []
        for line in open(path, encoding="utf-8", errors="ignore"):
            if ",SYS," in line:
                try:
                    sys_ts.append(int(line.split(",", 1)[0]))
                except Exception:
                    pass
        for line in open(path, encoding="utf-8", errors="ignore"):
            d = parse(line)
            if not d:
                continue
            try:
                ts = int(line.split(",", 1)[0])
            except Exception:
                continue
            odo, km = fl(d, "odo"), fl(d, "km")
            soc, spd, src, rv = fl(d, "soc"), fl(d, "spd"), fl(d, "src"), fl(d, "rv")
            if prev:
                p = prev
                dt = (ts - prev_ts) / 1000.0
                if odo == odo and p["odo"] == p["odo"] and odo < p["odo"] - 0.05:
                    bad_odo += 1
                    if bad_odo <= 3:
                        print(f"  [A] odo 倒退 {p['odo']:.1f} -> {odo:.1f}  ({d.get('x','')})")
                if odo == odo and p["odo"] == p["odo"] and km == km and p["km"] == p["km"]:
                    d_odo, d_km = odo - p["odo"], km - p["km"]
                    legit = any(abs(ts - t) < 6000 for t in sys_ts)
                    if abs(d_odo - d_km) > 0.3 and not legit:
                        bad_delta += 1
                        if bad_delta <= 3:
                            print(f"  [B] Δodo={d_odo:+.2f} 但 Δkm={d_km:+.2f} dt={dt:.0f}s ({d.get('x','')})")
                if dt <= 3.0 and soc == soc and p["soc"] == p["soc"] and abs(soc - p["soc"]) >= 3:
                    parked = (spd != spd or spd < 2) and (p["spd"] != p["spd"] or p["spd"] < 2)
                    if parked and src == 1:
                        bad_park += 1
                        if bad_park <= 3:
                            print(f"  [C] 停着 {dt:.0f}s 内 soc {p['soc']:.0f}->{soc:.0f} rv={d.get('rv')} vm={d.get('vm')}")
            if rv == rv and (rv < 50 or rv > 90):
                bad_rv += 1
                if bad_rv <= 3:
                    print(f"  [D] rv 越界 {rv:.1f}  ({d.get('x','')})")
            if src == 4 and rv == rv and rv != rv:
                pass
            if src == 4 and d.get("rv", "-") != "-":
                bad_frozen += 1
                if bad_frozen <= 3:
                    print(f"  [E] src=4(冻结) 但 rv={d.get('rv')}  ({d.get('x','')})")
            if soc == soc and (soc <= 0.5 or soc >= 99.5):
                soc_extreme_sec += 1
            prev = {"odo": odo, "km": km, "soc": soc, "spd": spd}
            prev_ts = ts
        print(f"{name:36s} A倒退={bad_odo} BΔ不一致={bad_delta} C停车跳={bad_park} "
              f"D电压越界={bad_rv} E冻结刷值={bad_frozen} 极端soc样本={soc_extreme_sec}")


if __name__ == "__main__":
    main(sys.argv[1:])
