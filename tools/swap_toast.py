#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.49：把 MainActivity 里的系统 Toast 换成深色 HudToast（统一皮肤）。

用法： python tools/swap_toast.py
只做一件事：`Toast.makeText(this, <args>, Toast.LENGTH_SHORT|LONG).show()` → `HudToast.show(this, <args>)`
"""
import io
import re

PATH = r"C:\Users\user\Doubao\chats\2026-11-16\new-chat\delivery-hud\app\src\main\java\com\hud\delivery\MainActivity.java"

PAT = re.compile(
    r"Toast\.makeText\(\s*this,\s*(?P<msg>.+?),\s*Toast\.LENGTH_(?:SHORT|LONG)\s*\)\s*\.show\(\)",
    re.S,
)


def main():
    with io.open(PATH, encoding="utf-8") as f:
        src = f.read()
    n = 0

    def repl(m):
        nonlocal n
        n += 1
        return "HudToast.show(this, " + m.group("msg").strip() + ")"

    out = PAT.sub(repl, src)
    if n and "import com.hud.delivery.ui.HudToast;" not in out:
        out = out.replace("import android.widget.Toast;",
                          "import android.widget.Toast;\nimport com.hud.delivery.ui.HudToast;", 1)
    with io.open(PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print("replaced", n)


if __name__ == "__main__":
    main()
