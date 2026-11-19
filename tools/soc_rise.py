#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.49 复盘用：找出"骑行中电量反而往上跳"的样本。

用法： python tools/soc_rise.py <csv...>
读 REC 行：  ...,REC,HH:MM:SS rv=.. v=.. vm=.. dc=.. src=.. sag=.. thr=.. spd=.. km=.. odo=.. soc=.. sra=.. rng=..
只看 src=1（实时）且 spd>=10 的样本（真在骑），soc 比上一条升高 >=2 就记一笔。
"""
import re
import sys

NUM = re.compile(r"(\w+)=([^ ]+)")


def parse(line):
    if ",REC," not in line:
        return None
    body = line.split(",REC,", 1)[1]
    d = {}
    for k, v in NUM.findall(body):
        d[k] = v
    return d


def f(d, k):
    try:
        return float(d.get(k, "nan"))
    except Exception:
        return float("nan")


def main(paths):
    total_up = 0
    for path in paths:
        prev = None
        n_up = 0
        for line in open(path, encoding="utf-8", errors="ignore"):
            d = parse(line)
            if not d:
                continue
            soc, spd, src = f(d, "soc"), f(d, "spd"), f(d, "src")
            if soc != soc or spd != spd:
                prev = None
                continue
            if prev is not None and src == 1 and spd >= 10 and soc >= prev + 2:
                n_up += 1
                if n_up <= 6:
                    print(f"  {path.split(chr(92))[-1]}  {d.get('x', '')} soc {prev:.0f}->{soc:.0f} "
                          f"rv={d.get('rv')} vm={d.get('vm')} sag={d.get('sag')} "
                          f"thr={d.get('thr')} spd={d.get('spd')}")
            prev = soc
        print(f"{path.split(chr(92))[-1]:38s} 骑行中上跳 {n_up} 次")
        total_up += n_up
    print(f"合计 {total_up} 次")


if __name__ == "__main__":
    main(sys.argv[1:])
