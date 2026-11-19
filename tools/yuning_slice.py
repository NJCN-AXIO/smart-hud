"""从育宁小程序 app-service.js 里按关键字切出代码片段，方便逐段读。"""
import sys, re

path = sys.argv[1]
text = open(path, encoding="utf-8", errors="replace").read()

KEYS = sys.argv[2:]

for k in KEYS:
    pat = k
    try:
        idxs = [m.start() for m in re.finditer(re.escape(pat), text)]
    except Exception:
        idxs = []
    print("\n" + "=" * 78)
    print("KEY: %s   hits=%d" % (k, len(idxs)))
    for i in idxs[:6]:
        s = max(0, i - 400)
        e = min(len(text), i + 900)
        print("\n--- @%d ---" % i)
        print(text[s:e])
