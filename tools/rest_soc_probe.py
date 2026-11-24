"""复盘静置电压 vs 账本电量 / 查表电量。

从 raw_*.csv 的 REC 行取"车停着、没带载"的样本：sag 小、spd 低、thr 低。
按文件输出静置点的电压、查表 sra、账本 soc、本次里程，便于核对底部曲线偏在哪。
"""
import csv, glob, re, os, statistics

FILES = sorted(glob.glob(r"D:\下载\raw_2026112*.csv"))


def kv(s):
    return {m.group(1): m.group(2) for m in re.finditer(r"(\w+)=(\S+)", s or "")}


def fnum(x, default=float("nan")):
    try:
        return float(x)
    except (TypeError, ValueError):
        return default


for path in FILES:
    if not os.path.exists(path):
        continue
    rest = []
    anchors = []
    with open(path, newline="", encoding="utf-8", errors="ignore") as fh:
        for r in csv.reader(fh):
            if len(r) < 3 or not r[0].isdigit():
                continue
            if r[1] == "REC":
                p = kv(r[2])
                v = fnum(p.get("v"))
                sag = fnum(p.get("sag"))
                spd = fnum(p.get("spd"))
                thr = fnum(p.get("thr"))
                soc = fnum(p.get("soc"))
                sra = fnum(p.get("sra"))
                km = fnum(p.get("km"))
                if v > 0 and sag <= 0.6 and spd <= 2 and thr <= 1.5:
                    rest.append((v, sra, soc, km))
            elif r[1] == "SYS":
                line = r[2]
                if "anchor" in line or "anchorRest" in line or "cutoff" in line or "hardLand" in line:
                    anchors.append(line)
    if not rest:
        continue
    vs = [x[0] for x in rest]
    print(f"\n==== {os.path.basename(path)}  静置点 n={len(rest)}  v {min(vs):.1f}..{max(vs):.1f}")
    # 抽样：每 1/6 段取一个
    step = max(1, len(rest) // 6)
    for i in range(0, len(rest), step):
        v, sra, soc, km = rest[i]
        print(f"  v={v:5.1f} sra={sra:4.0f} soc={soc:4.0f} km={km:6.1f}")
    if rest:
        v, sra, soc, km = rest[-1]
        print(f"  末 v={v:5.1f} sra={sra:4.0f} soc={soc:4.0f} km={km:6.1f}")
    for a in anchors[:6]:
        print("  SYS:", a[:110])
