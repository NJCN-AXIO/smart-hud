"""11-20/21 复盘深挖：逐分钟看"帧还在不在、里程在不在长、电量锚点有没有机会校准"。

用法：python tools\deep_probe_1121.py <csv> [<csv> ...]
输出四段：
  1) 每分钟：REC 帧数、有电压帧数、GPS 速度、本次里程增量
  2) BLE 空档：连续 >20s 没有电压帧的区间（骑行中掉帧 = 电量失去信源）
  3) 里程空档：GPS 位移没动但速度在报（骑手在跑、App 没记）
  4) 静置窗口：连续 ≥90s 速度≈0 的时间段，以及当时是否有可用静置电压
"""
import sys, re, datetime, collections

VOL = re.compile(r'\bv=([0-9.]+|-|nan)')
SPD = re.compile(r'\bspd=([0-9.]+|-|NaN|nan)')
KM = re.compile(r'\bkm=([0-9.]+|-|nan)')
SOC = re.compile(r'\bsoc=([0-9.]+|-|nan)')
RNG = re.compile(r'\brng=([0-9.]+|-|nan)')
SRC = re.compile(r'\bsrc=(\d+)')


def num(m, s):
    if not m:
        return None
    v = m.group(1)
    try:
        return float(v)
    except ValueError:
        return None


def parse(path):
    recs = []
    sysl = []
    with open(path, 'r', encoding='utf-8', errors='ignore') as f:
        for ln in f:
            p = ln.rstrip('\n').split(',')
            if len(p) < 3:
                continue
            try:
                ms = int(p[0])
            except ValueError:
                continue
            kind = p[1]
            body = ','.join(p[2:])
            if kind == 'REC':
                recs.append((ms, body))
            else:
                sysl.append((ms, kind, body))
    return recs, sysl


def hhmm(ms):
    return datetime.datetime.fromtimestamp(ms / 1000).strftime('%H:%M:%S')


def main(paths):
    for path in paths:
        recs, sysl = parse(path)
        if not recs:
            continue
        print('=' * 90)
        print(path, 'REC=', len(recs))
        # 1) 逐分钟统计
        per = collections.OrderedDict()
        for ms, body in recs:
            k = ms // 60000
            d = per.setdefault(k, {'n': 0, 'v': 0, 'spd': [], 'km': None})
            d['n'] += 1
            if None not in (num(VOL.search(body), None),) and num(VOL.search(body), None) is not None:
                d['v'] += 1
            s = num(SPD.search(body), None)
            if s is not None:
                d['spd'].append(s)
            km = num(KM.search(body), None)
            if km is not None:
                d['km'] = km
        print('---- 每分钟（只打印 帧<20 或 里程<0.02 的分钟）----')
        for k in sorted(per):
            d = per[k]
            spd = sum(d['spd']) / len(d['spd']) if d['spd'] else None
            flag = ''
            if d['n'] < 20:
                flag += ' LOWFPS'
            if d['v'] < 10:
                flag += ' LOWV'
            if d['km'] is not None and d['km'] < 0.02:
                flag += ' NOKM'
            if flag:
                print('  %s n=%3d v=%3d spd=%s km=%s%s' % (
                    datetime.datetime.fromtimestamp(k * 60).strftime('%H:%M'),
                    d['n'], d['v'], '%.1f' % spd if spd is not None else '-',
                    '%.2f' % d['km'] if d['km'] is not None else '-', flag))
        # 2) 电压空档
        print('---- 电压空档 >20s ----')
        lastv = None
        gaps = []
        for ms, body in recs:
            if num(VOL.search(body), None) is not None:
                if lastv is not None and ms - lastv > 20000:
                    gaps.append((lastv, ms))
                lastv = ms
        for a, b in gaps:
            print('  %s -> %s  (%d s)' % (hhmm(a), hhmm(b), (b - a) // 1000))
        if not gaps:
            print('  （无）')
        # 3) 里程空档：速度>=10 但没有里程增长
        print('---- 有速度但里程不长（累计）----')
        segs = []
        start = None
        prev_km = None
        prev_ms = None
        for ms, body in recs:
            km = num(KM.search(body), None)
            spd = num(SPD.search(body), None)
            if km is None or spd is None:
                continue
            if prev_km is not None and km < prev_km:
                prev_km = None      # 清零/并账
            if prev_km is not None:
                dk = km - prev_km
                dt = (ms - prev_ms) / 1000.0
                if spd >= 10 and dk < 0.003 * dt:   # 10km/h 一分钟该有 0.17km
                    if start is None:
                        start = prev_ms
                else:
                    if start is not None and ms - start >= 30000:
                        segs.append((start, prev_ms))
                    start = None
            prev_km = km
            prev_ms = ms
        if start is not None:
            segs.append((start, prev_ms))
        for a, b in segs:
            print('  %s -> %s  (%d s)' % (hhmm(a), hhmm(b), (b - a) // 1000))
        if not segs:
            print('  （无）')
        # 4) 静置窗口
        print('---- 静置窗口 >=90s ----')
        win = []
        start = None
        for ms, body in recs:
            spd = num(SPD.search(body), None)
            km = num(KM.search(body), None)
            still = (spd is not None and spd < 2.0)
            if still:
                if start is None:
                    start = ms
            else:
                if start is not None and ms - start >= 90000:
                    win.append((start, ms))
                start = None
        if start is not None and prev_ms - start >= 90000:
            win.append((start, prev_ms))
        for a, b in win:
            print('  %s -> %s  (%d s)' % (hhmm(a), hhmm(b), (b - a) // 1000))
        if not win:
            print('  （无）')


if __name__ == '__main__':
    main(sys.argv[1:] or [r'D:\下载\raw_20261121_170916.csv'])
