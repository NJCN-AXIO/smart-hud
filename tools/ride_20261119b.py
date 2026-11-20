import os, statistics
from datetime import datetime

FILES = [
    r"D:\下载\raw_20261119_140938.csv",
    r"D:\下载\raw_20261119_141802.csv",
    r"D:\下载\raw_20261119_155237.csv",
    r"D:\下载\raw_20261119_172034.csv",
    r"D:\下载\raw_20261119_202834.csv",
    r"D:\下载\raw_20261120_001434.csv",
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

print("="*80); print("A. 本次里程清零点（km 断崖）"); print("="*80)
prev=None
for e,d in rows:
    k=num(d.get("km")); o=num(d.get("odo"))
    if k is None: continue
    if prev and k < prev[0]-0.5:
        print(f"{ts(e)}  km {prev[0]:.2f} -> {k:.2f}   odo={o:.1f}   丢 {prev[0]-k:.2f} km")
    prev=(k,o)

print(); print("="*80); print("B. 每一档 5% 电量对应的『静置电压』与『实跑公里』"); print("="*80)
print("电量档  静置样本  静置电压中位  最低带载V  该档实跑km  累计km")
band={}
prev_o=None
lastband=None
for e,d in rows:
    if d.get("src")!="1": continue
    soc=num(d.get("soc")); v=num(d.get("v")); spd=num(d.get("spd")); o=num(d.get("odo"))
    if None in (soc,v,o): continue
    b=int(soc//5*5)
    band.setdefault(b,{"rest":[],"load":[]})
    if spd is not None and spd<=2: band[b]["rest"].append(v)
    else: band[b]["load"].append(v)
    if prev_o is not None and lastband==b:
        band[b]["km"]=band.get(b,{}).get("km",0)+max(0,o-prev_o)
    prev_o=o; lastband=b
cum=0
for b in sorted(band,reverse=True):
    d=band[b]; km=d.get("km",0); cum+=km
    rest=statistics.median(d["rest"]) if d["rest"] else float("nan")
    loadmin=min(d["load"]) if d["load"] else float("nan")
    print(f"{b:3d}-{b+5:3d} {len(d['rest']):7d} {rest:12.2f} {loadmin:10.2f} {km:10.2f} {cum:8.2f}")

print(); print("="*80); print("C. 压降（静置电压 vs 带载电压，按油门档位）"); print("="*80)
sag={}
for e,d in rows:
    if d.get("src")!="1": continue
    v=num(d.get("v")); sag_v=num(d.get("sag")); thr=num(d.get("thr")); spd=num(d.get("spd"))
    if None in (v,sag_v,spd): continue
    if spd <= 2: k = "静止"
    elif spd < 10: k = "低速<10"
    elif spd < 20: k = "中速10-20"
    elif spd < 30: k = "高速20-30"
    else: k = "30+"
    sag.setdefault(k,[]).append(sag_v)
for k in ["静止","低速<10","中速10-20","高速20-30","30+"]:
    if k in sag:
        xs=sag[k]
        print(f"{k:10s} n={len(xs):5d}  sag中位={statistics.median(xs):5.2f}V  P90={sorted(xs)[int(len(xs)*0.9)]:5.2f}V  最大={max(xs):5.2f}V")

print(); print("="*80); print("D. 电量目标抖动（连续 10 秒内 target 变化幅度，按电压表换算）"); print("="*80)
V=[76.5,75.6,74.7,73.8,72.9,72.0,71.4,70.8,70.2,69.6,63.0]
S=[100,90,80,70,60,50,40,30,20,10,0]
def soc_of(v):
    if v>=V[0]: return 100
    if v<=V[-1]: return 0
    for i in range(10):
        if V[i+1]<=v<V[i]:
            f=(V[i]-v)/(V[i]-V[i+1]); return S[i]-f*(S[i]-S[i+1])
    return 0
win=[]; swings=[]
for e,d in rows:
    v=num(d.get("v")); spd=num(d.get("spd"))
    if v is None or spd is None or d.get("src")!="1": continue
    win.append((e,v))
    while win and e-win[0][0]>10000: win.pop(0)
    if len(win)>3 and e-win[0][0]>=8000:
        s=[soc_of(x[1]) for x in win]
        if max(s)-min(s)>=4: swings.append(max(s)-min(s))
print(f"10 秒窗口内电压换算 SOC 摆幅 >=4% 的次数: {len(swings)}")
if swings: print(f"摆幅中位 {statistics.median(swings):.1f}%  P90 {sorted(swings)[int(len(swings)*0.9)]:.1f}%  最大 {max(swings):.1f}%")

print(); print("="*80); print("E. 实时电压极值"); print("="*80)
vs=[(e,num(d.get("v"))) for e,d in rows if d.get("src")=="1" and num(d.get("v"))]
print(f"最大 {max(x[1] for x in vs):.2f}V @ {ts(max(vs,key=lambda x:x[1])[0])}")
print(f"最小 {min(x[1] for x in vs):.2f}V @ {ts(min(vs,key=lambda x:x[1])[0])}")
