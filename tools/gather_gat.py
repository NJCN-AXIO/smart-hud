import sys, glob, os, collections

d = sys.argv[1]
whats = collections.Counter()
details = collections.Counter()
with open(os.path.join(d, "raw_20261117_100526.csv"), encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) > 2 and p[1] == "GAT":
            whats[p[2]] += 1
            if p[3]:
                details[(p[2], p[3][:70])] += 1

print("--- GAT what ---")
for w, c in whats.most_common():
    print("  %6d  %s" % (c, w))
print("\n--- GAT with detail ---")
for (w, dd), c in details.most_common(30):
    print("  %6d  %s | %s" % (c, w, dd))
