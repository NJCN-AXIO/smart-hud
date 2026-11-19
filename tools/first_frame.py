"""打印日志里第一条 FRM 帧的十六进制（67B 基线用）。"""
import sys

path = sys.argv[1]
n = int(sys.argv[2]) if len(sys.argv) > 2 else 1

shown = 0
with open(path, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        p = line.rstrip("\n").split(",")
        if len(p) >= 4 and p[1] == "FRM":
            print(p[3].strip())
            shown += 1
            if shown >= n:
                break
