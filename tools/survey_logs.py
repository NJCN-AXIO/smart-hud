import sys, os, glob, collections

files = sorted(glob.glob(os.path.join(sys.argv[1], "raw_20261117_*.csv")))
for f in files:
    kinds = collections.Counter()
    tags = collections.Counter()
    lens = collections.Counter()
    mat = []
    with open(f, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            p = line.rstrip("\n").split(",")
            if len(p) < 3:
                continue
            k = p[1]
            kinds[k] += 1
            if k == "FRM":
                tags[p[2]] += 1
                hx = p[3] if len(p) > 3 else ""
                n = len(hx.split())
                lens[n] += 1
            if k == "MAT":
                mat.append(p)
    print("="*70)
    print(os.path.basename(f))
    print("  kinds:", dict(kinds))
    print("  FRM tags:", dict(tags))
    print("  FRM lengths:", dict(lens))
    if mat:
        print("  MAT rows:", len(mat))
        for p in mat[:8]:
            print("   ", p[:6])
