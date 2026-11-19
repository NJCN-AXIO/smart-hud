import csv, sys, glob
for f in sorted(glob.glob(r"D:\下载\raw_2026111*.csv")):
    lo_fast = 0; lo_slow = 0; recs = 0
    with open(f, newline="", encoding="utf-8", errors="replace") as fh:
        for row in csv.reader(fh):
            if len(row) < 2: continue
            if row[1] != "REC": continue
            recs += 1
            kv = row[2] if len(row) > 2 else ""
            d = {}
            for t in kv.split():
                if "=" in t:
                    k, v = t.split("=", 1); d[k] = v
            try:
                dc = float(d.get("dc","nan")); spd = float(d.get("spd","nan"))
            except ValueError:
                continue
            if dc < 63.0:
                if spd >= 12: lo_fast += 1
                else: lo_slow += 1
    print(f.split("\\")[-1], "rec=%d" % recs, "dc<63 & spd>=12:%d" % lo_fast, "dc<63 & slow:%d" % lo_slow)
