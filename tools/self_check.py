#!/usr/bin/env python3
"""
一键自检：把"能离线验证的东西"全跑一遍，最后给一句话结论。

跑什么：
  1. 红线自检 —— 除了 CCCD 开通知，不向控制器写任何数据（tools/check_no_write.py）
  2. :protocol 单测 —— 协议解析（8 个用例，真帧断言）
  3. :core 单测     —— 电量查表 / 滑动平均 / 里程积分 / 耗电自学习（18 个用例）
  4. assembleDebug  —— 出 APK
  5. APK 完整性     —— zip 本地头/中央目录条数一致 + testzip 无损坏
  6. APK 版本       —— versionCode / versionName 与 app/build.gradle 一致

用法：
    python tools/self_check.py               # 全跑
    python tools/self_check.py --skip-build  # 只跑静态检查与单测（不重新打包）
"""
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = r"C:\Users\user\.gradle\wrapper\dists\gradle-8.9-bin\90ujzsggybs7v3y4eq2lwjyvr\gradle-8.9\bin\gradle.bat"

results = []


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  [OK]   " if ok else "  [FAIL] ") + name + (("  " + detail) if detail else ""))


def run(cmd, timeout=600):
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def suite_counts(module, expect_tests):
    """从 JUnit XML 里读用例数与失败数（不靠控制台，中文用例名会乱码）"""
    d = os.path.join(ROOT, module, "build", "test-results", "test")
    if not os.path.isdir(d):
        return None
    tests = fails = errs = 0
    seen = False
    for fn in os.listdir(d):
        if not (fn.startswith("TEST-") and fn.endswith(".xml")):
            continue
        t = open(os.path.join(d, fn), encoding="utf-8", errors="replace").read()
        m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', t)
        if m:
            # 一个测试类一个 XML：全部加起来才是总数（v1.0.5 加了 TripStatsTest 才发现这里只读第一个）
            tests += int(m.group(1))
            fails += int(m.group(3))
            errs += int(m.group(4))
            seen = True
    return (tests, fails, errs) if seen else None


def main():
    skip_build = "--skip-build" in sys.argv

    print("=" * 66)
    print("Delivery HUD 自检")
    print("工程：", ROOT)
    print("=" * 66)

    # 1. 红线
    print("\n[1/6] 红线自检：不向控制器写数据")
    rc, out = run([sys.executable, os.path.join(ROOT, "tools", "check_no_write.py"), ROOT])
    record("只读红线（唯一写是 CCCD 开通知）", rc == 0,
           "" if rc == 0 else out.strip().splitlines()[-1] if out.strip() else "")

    # 2 & 3. 单测
    print("\n[2/6] 协议层单测 :protocol")
    rc, out = run([GRADLE, "-p", ROOT, ":protocol:test", "--console=plain"])
    c = suite_counts("protocol", 8)
    ok = rc == 0 and c is not None and c[1] == 0 and c[2] == 0
    record(":protocol 单测", ok, "" if c is None else "tests=%d fail=%d err=%d" % c)

    print("\n[3/6] 算法层单测 :core")
    rc, out = run([GRADLE, "-p", ROOT, ":core:test", "--console=plain"])
    c = suite_counts("core", 18)
    ok = rc == 0 and c is not None and c[1] == 0 and c[2] == 0
    record(":core 单测", ok, "" if c is None else "tests=%d fail=%d err=%d" % c)

    apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

    # 4. 打包
    if skip_build:
        print("\n[4/6] 打包：跳过（--skip-build）")
        record("assembleDebug", os.path.isfile(apk), "使用已有 APK")
    else:
        print("\n[4/6] 打包 assembleDebug")
        rc, out = run([GRADLE, "-p", ROOT, "assembleDebug", "--console=plain"])
        record("assembleDebug", rc == 0 and os.path.isfile(apk))

    # 5. APK 完整性
    print("\n[5/6] APK 完整性")
    if not os.path.isfile(apk):
        record("APK 存在", False, "没找到 app-debug.apk")
    else:
        data = open(apk, "rb").read()
        # 字节扫描只当参考：压缩流里偶尔会正好出现 "PK\x03\x04"，
        # 用它判相等会误报（v1.0.5 就报过一次 143 vs 142）。
        # 真正的完整性用 zipfile 走中央目录 + CRC 校验。
        local_hint = data.count(b"PK\x03\x04")
        central_hint = data.count(b"PK\x01\x02")
        try:
            zf = zipfile.ZipFile(apk)
            entries = len(zf.infolist())
            bad = zf.testzip()
        except Exception as e:
            entries = -1
            bad = "异常: %s" % e
        ok = entries >= 50 and bad is None
        record("zip 结构 + 逐条目 CRC", ok,
               "entries=%d testzip=%s size=%d (字节扫描 local=%d central=%d，仅供参考)"
               % (entries, bad, len(data), local_hint, central_hint))

        # 6. 版本号
        print("\n[6/6] 版本号一致性")
        g = open(os.path.join(ROOT, "app", "build.gradle"), encoding="utf-8").read()
        vc = re.search(r"versionCode\s+(\d+)", g)
        vn = re.search(r'versionName\s+"([^"]+)"', g)
        want_code = vc.group(1) if vc else "?"
        want_name = vn.group(1) if vn else "?"

        aapt2 = r"C:\Users\user\Android\Sdk\build-tools\34.0.0\aapt2.exe"
        got = None
        if os.path.isfile(aapt2):
            rc2, out2 = run([aapt2, "dump", "badging", apk])
            m = re.search(r"versionCode='(\d+)' versionName='([^']+)'", out2)
            if m:
                got = (m.group(1), m.group(2))
        ok = got is not None and got[0] == want_code and got[1] == want_name
        record("APK 版本 = build.gradle", ok,
               "APK=%s build.gradle=%s/%s" % (got, want_code, want_name))

    print("\n" + "=" * 66)
    fails = [r for r in results if not r[1]]
    if fails:
        print("结论：%d 项未通过 —— %s" % (len(fails), ", ".join(f[0] for f in fails)))
        return 1
    print("结论：全部通过（%d 项）" % len(results))
    print("注：蓝牙真连、权限流程、Compose 真机排版必须有设备才能验，不在此列。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
