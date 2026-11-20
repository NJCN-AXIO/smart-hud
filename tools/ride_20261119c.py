import os, statistics
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261119_140938.csv", r"D:\下载\raw_20261119_141802.csv",
    r"D:\下载\raw_20261119_155237.csv", r"D:\下载\raw_20261119_172034.csv",
    r"D:\下载\raw_20261119_202834.csv", r"D:\下载\raw_20261120_001434.csv",
]
def ts(ms): return datetime.fromtimestamp(ms/1000).strftime("%H:%M:%S")
def num(x):
    try: return float(x)
    except: return None
def parse(s):
    d={}
    for t in s.split():
        if "=" in t:
            k,v=t.split("=",1); d[k]=v
    return d

rows=[]
for f in FILES:
    if not os.path.exists(f): continue
    with open(f, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            p=line.rstrip("\n").split(",")
            if len(p)<3 or p[1]!="REC": continue
            try: e=int(p[0])
            except: continue
            rows.append((e, parse(p[2])))
rows.sort(key=lambda x:x[0])

O0 = 11306.0
print("="*84)
print("F. 真实放电曲线（静置电压 vs 已跑公里，每 5 km 一档）")
print("="*84)
print("公里区间   静置样本  静置V中位  带载最低V  同期显示电量")
buckets={}
for e,d in rows:
    o=num(d.get("odo")); v=num(d.get("v")); spd=num(d.get("spd")); soc=num(d.get("soc"))
    if None in (o,v): continue
    if d.get("src")!="1": continue
    km=o-O0
    if km<0: continue
    b=int(km//5*5)
    buckets.setdefault(b,{"rest":[],"load":[],"soc":[]})
    if spd is not None and spd<=2: buckets[b]["rest"].append(v)
    else: buckets[b]["load"].append(v)
    if soc is not None: buckets[b]["soc"].append(soc)
for b in sorted(buckets):
    d=buckets[b]
    rest=statistics.median(d["rest"]) if d["rest"] else float("nan")
    lo=min(d["load"]) if d["load"] else float("nan")
    so=statistics.median(d["soc"]) if d["soc"] else float("nan")
    print(f"{b:3d}-{b+5:3d} {len(d['rest']):8d} {rest:10.2f} {lo:10.2f} {so:11.0f}")

print()
print("="*84)
print("G. 本次里程 vs 总里程：累计比例（检查是否漏记）")
print("="*84)
pairs=[]
prev=None
for e,d in rows:
    o=num(d.get("odo")); k=num(d.get("km"))
    if None in (o,k): continue
    if prev:
        dok=o-prev[1]; dkm=k-prev[0]
        if dok>0.05 and dkm<dok-0.05:
            pairs.append((e,dok,dkm))
    prev=(k,o)
print(f"odo 增长但 km 不涨/涨得少的间隔: {len(pairs)}")
if pairs:
    tot_o=sum(x[1] for x in pairs); tot_k=sum(x[2] for x in pairs)
    print(f"这些间隔里 odo 涨了 {tot_o:.2f} km，km 只涨了 {tot_k:.2f} km（漏 {(tot_o-tot_k):.2f} km, {100*tot_k/tot_o:.0f}%）")
    for e,a,b in pairs[:10]:
        print(f"  {ts(e)}  Δodo={a:.2f}  Δkm={b:.2f}")

print()
print("="*84)
print("H. 结束阶段：0% 之后还跑了多少（显示 0 起点 → 终点）")
print("="*84)
zero_at=None
for e,d in rows:
    s=num(d.get("soc")); o=num(d.get("odo"))
    if s is None or o is None: continue
    if s<=1 and zero_at is None:
        zero_at=(e,o)
print(f"第一次显示 <=1%: {ts(zero_at[0])}  odo={zero_at[1]:.1f}")
print(f"最终 odo: 11392.0  → 0% 之后又走了 {11392.0-zero_at[1]:.1f} km")
print(f"（其中用户自述最后 5 km 是半推半骑 → 能骑的约 {11392.0-zero_at[1]-5:.1f} km）")

print()
print("="*84)
print("I. 关键电压锚点")
print("="*84)
print("真实满电静置电压 : 78.67 V (14:10)")
print("App 设的满电电压 : 76.50 V  → 少 2.17 V，导致中段(76~77V)被误判成『满电』")
print("真实耗尽(带载)   : 58.26 V (00:06)")
print("App 设的空电电压 : 63.00 V  → 比真实耗尽高 4.7 V，导致 0% 提前约 15-20 km")
