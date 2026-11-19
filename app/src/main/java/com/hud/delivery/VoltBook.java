package com.hud.delivery;

import android.content.Context;

import com.hud.core.EcoBook;
import com.hud.core.EcoEntry;
import com.hud.core.LearnResult;
import com.hud.core.SocModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 电压账本的 Android 适配层。
 *
 * 数学（学 %/km、EMA、区间校验、剩余里程）全在 :core.EcoBook，
 * 这里只做两件事：把 "ts,v,km;..." 从 SharedPreferences 读写成条目列表、写系统日志。
 */
public final class VoltBook {

    public static final class Entry {
        public final long ts;
        public final float v;
        public final float km;

        Entry(long ts, float v, float km) {
            this.ts = ts;
            this.v = v;
            this.km = km;
        }
    }

    private final Prefs prefs;
    private final EcoBook book;

    public VoltBook(Context c) {
        prefs = new Prefs(c);
        book = new EcoBook(prefs.socPerKm(), prefs.socPerKmN());
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        String s = prefs.vbook();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] f = part.split(",");
            if (f.length < 3) continue;
            try {
                out.add(new Entry(Long.parseLong(f[0].trim()),
                        Float.parseFloat(f[1].trim()),
                        Float.parseFloat(f[2].trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public Entry last() {
        List<Entry> l = all();
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    /** 记一条，并尝试学一段。返回 true = 这次贡献了一个有效学习段。 */
    public boolean record(long ts, float v, float km, SocModel model) {
        List<Entry> l = all();
        if (!l.isEmpty()) {
            Entry prev = l.get(l.size() - 1);
            if (Math.abs(km - prev.km) < EcoBook.SAME_KM && ts - prev.ts < EcoBook.SAME_MS) {
                l.set(l.size() - 1, new Entry(ts, v, km));   // 同一次录入：覆盖
                save(l);
                return false;
            }
        }
        boolean learned = false;
        if (!l.isEmpty()) {
            Entry prev = l.get(l.size() - 1);
            LearnResult r = book.evaluate(
                    new EcoEntry(prev.ts, prev.v, prev.km),
                    new EcoEntry(ts, v, km), model);
            if (book.commit(r)) {
                prefs.setSocPerKm(book.getRate());
                prefs.setSocPerKmN(book.getN());
                RawLog.sys(ts, String.format(Locale.US, "learn soc/km: seg=%.3f -> %.3f (n=%d, dkm=%.1f)",
                        r.getSegmentRate(), book.getRate(), book.getN(), km - prev.km));
                learned = true;
            }
        }
        l.add(new Entry(ts, v, km));
        while (l.size() > EcoBook.MAX_ENTRIES) l.remove(0);
        save(l);
        return learned;
    }

    private void save(List<Entry> l) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : l) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.ts).append(',')
                    .append(String.format(Locale.US, "%.2f", e.v)).append(',')
                    .append(String.format(Locale.US, "%.2f", e.km));
        }
        prefs.setVbook(sb.toString());
    }

    /** 哨点电量 + 里程 → 当前电量（%） */
    public float socNow(float anchorSoc, float anchorKm, float nowKm) {
        return book.socNow(anchorSoc, anchorKm, nowKm);
    }

    /** 按当前电量估剩余公里（耗电速率未知时返回 NaN，不瞎猜） */
    public float rangeKm(float socPercent) {
        return book.rangeKm(socPercent);
    }

    public void clear() {
        prefs.setVbook("");
        prefs.setSocPerKm(0f);
        prefs.setSocPerKmN(0);
        book.clear();
    }
}
