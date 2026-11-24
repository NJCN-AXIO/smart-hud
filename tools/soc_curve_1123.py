"""逐 10 分钟复盘 11-23 这一箱电：电压表口径 vs 里程口径，看偏差在哪一段形成。"""
import csv, re, os
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261123_094352.csv",
    r"D:\下载\raw_20261123_150408.csv",
    r"D:\下载\raw_20261124_075554.csv",
    r"D:\下载\raw_20261124_075824.csv",
]


def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}


for path in FILES:
    if not os.path.exists(path):
        continue
    recs = []
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3 or not r[0].isdigit() or r[1] != "REC":
                continue
            recs.append((int(r[0]), r[2]))
    print("=" * 100)
    print(os.path.basename(path))
    last = None
    for t, s in recs:
        if last is not None and t - last < 10 * 60 * 1000:
            continue
        last = t
        p = kv(s)
        def g(k): return p.get(k) or "-"
        print(f"  {datetime.fromtimestamp(t/1000).strftime('%H:%M')} v={g('v'):<6} sra={g('sra'):<4} "
              f"soc={g('soc'):<4} rng={g('rng'):<5} km={g('km'):<6} odo={g('odo'):<8} "
              f"pk={g('pk'):<6} er={g('er'):<3} whc={g('whc'):<5} spd={g('spd'):<4} src={g('src')}")
