import csv, glob, sys

def parse_kv(s):
    d = {}
    for t in s.split():
        if "=" in t:
            k, v = t.split("=", 1)
            d[k] = v
    return d

for f in sorted(glob.glob(r"D:\下载\raw_2026111*.csv")):
    rows = list(csv.reader(open(f, newline="", encoding="utf-8", errors="replace")))
    # state timeline
    paused = None
    seg_start = None
    out = []
    last_km = None
    for r in rows:
        if len(r) < 3: continue
        kind = r[1]; ts = int(r[0]) if r[0].isdigit() else 0
        if kind == "SYS":
            msg = r[2]
            if "ui pause" in msg: paused = ts
            if "ui resume" in msg: paused = None
        elif kind == "REC" and paused:
            d = parse_kv(r[2])
            try: km = float(d.get("km","nan"))
            except: km = float("nan")
            spd = d.get("spd","-")
            if last_km is not None and km == km and km - last_km > 0.02:
                out.append((ts - paused, round(km - last_km, 2), spd))
            if km == km: last_km = km
    if paused is not None or out:
        print(f.split("\\")[-1], "bg_km_growth_rows=%d" % len(out))
        for t, dk, spd in out[:6]:
            print("   +%.1fs dkm=%.2f spd=%s" % (t/1000.0, dk, spd))
