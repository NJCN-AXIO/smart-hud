import statistics, sys
def unscramble(raw):
    f=list(raw)
    for e in range(4,66):
        v=f[e]-11; v=85^(v&0xFF); v=v-e; v=19^(v&0xFF); v=v-e; f[e]=v
    return f
for path in [r"D:\下载\raw_20261117_221245.csv", r"D:\下载\raw_20261117_204050.csv", r"D:\下载\raw_20261117_211524.csv"]:
    vals=[]
    for line in open(path, encoding="utf-8", errors="replace"):
        p=line.rstrip("\n").split(",")
        if len(p)>=4 and p[1]=="FRM":
            b=bytes.fromhex(p[3].replace(" ",""))
            if len(b)>=67:
                f=unscramble(b); vals.append((256*f[63]+f[64])/100.0)
    vals.sort()
    n=len(vals)
    def pct(q): return vals[min(n-1,int(n*q))]
    print(path.split("_")[-1], "n=%d"%n,
          "p05=%.1f p25=%.1f p50=%.1f p75=%.1f p90=%.1f p95=%.1f max=%.1f" % (
          pct(.05),pct(.25),pct(.5),pct(.75),pct(.9),pct(.95),vals[-1]),
          "mean=%.1f" % statistics.mean(vals))
