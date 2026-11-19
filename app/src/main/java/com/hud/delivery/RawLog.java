package com.hud.delivery;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 原始帧落盘（App 私有目录，无需 root，USB 直接取）。
 *
 * 这是整个第一阶段最重要的东西：
 *   FRM  行 —— 每一帧原始字节，用于回放/离线分析
 *   MAT  行 —— “人工标注”。按下标记时，把当时小程序上显示的电压/速度
 *              和最近一帧的原始 hex 绑在同一条记录里。
 *              有了它，字段求解器（tools/find_field.py）就不需要靠时间戳猜对齐。
 */
public final class RawLog {

    private static final String TAG = "HudRawLog";
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 16L * 1024 * 1024;

    private static File file;
    private static BufferedWriter writer;
    private static long size = 0L;

    private RawLog() {}

    public static void init(Context ctx) {
        synchronized (LOCK) {
            if (writer != null) return;
            try {
                File dir = new File(ctx.getExternalFilesDir(null), "logs");
                if (!dir.exists()) dir.mkdirs();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                file = new File(dir, "raw_" + fmt.format(new Date()) + ".csv");
                writer = new BufferedWriter(new FileWriter(file, true));
                writeHeaderLocked();
                size = file.length();
                pruneOldFilesLocked(dir);
                Log.i(TAG, "log file: " + file.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "init failed", e);
            }
        }
    }

    private static void writeHeaderLocked() throws Exception {
        writer.write("epoch_ms,kind,a,b,c,d,e\n");
        writer.flush();
    }

    /**
     * 保留多少个日志文件。
     *
     * v1.0.7：12 → 24，并且**24 小时内的一律不删**。
     * 原因：每启动一次 App 就新开一个文件，骑一趟（尤其 9MB 那种大日志）
     * 很容易被后面十几次开关机挤掉 —— 用户那趟 9MB 的骑行主体日志就是这么没的，
     * 事后无法复盘。现在按"时间"保护今天的，再按"数量"兜住存储。
     */
    private static final int KEEP_FILES = 24;
    /** 这个时间内的日志不参与清理（毫秒） */
    private static final long PROTECT_MS = 24L * 60 * 60 * 1000;

    private static void pruneOldFilesLocked(File dir) {
        File[] all = dir.listFiles((d, n) -> n.startsWith("raw_") && n.endsWith(".csv"));
        if (all == null || all.length <= KEEP_FILES) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int drop = all.length - KEEP_FILES;
        long now = System.currentTimeMillis();
        for (int i = 0; i < drop; i++) {
            if (all[i].equals(file)) continue;      // 永远不删正在写的那个
            if (now - all[i].lastModified() < PROTECT_MS) continue;   // 今天的留着
            if (all[i].delete()) Log.i(TAG, "pruned old log: " + all[i].getName());
        }
    }

    public static String path() {
        synchronized (LOCK) {
            return file == null ? "(未创建)" : file.getAbsolutePath();
        }
    }

    /**
     * v1.0.40：找出"最近一次崩溃"，直接给界面显示 —— 用户截个图就行，不用翻文件管理器。
     * 只找 24 小时内的日志；返回 null 表示这 24 小时没崩过。
     */
    public static String lastCrash(Context ctx) {
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "logs");
            File[] all = dir.listFiles((d, n) -> n.startsWith("raw_") && n.endsWith(".csv"));
            if (all == null) return null;
            java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            long now = System.currentTimeMillis();
            for (File f : all) {
                if (now - f.lastModified() > 24L * 3600_000L) break;
                String hit = null;
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains(",SYS,CRASH")) hit = line;
                    }
                }
                if (hit != null) {
                    int i = hit.indexOf(",SYS,");
                    String s = hit.substring(i + 5).replace("\r", "");
                    if (s.length() > 420) s = s.substring(0, 420) + "…";
                    return f.getName() + "\n" + s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 每个通知帧 */
    public static void frame(long ts, String tag, byte[] data) {
        line(ts, "FRM", tag, hex(data), "", "", "");
    }

    /** 人工标注：a=通道名, b=小程序电压, c=小程序速度, d=GPS速度, e=当帧 hex */
    public static void mark(long ts, String tag, float statedV, float statedKmh, float gpsKmh, byte[] frame) {
        line(ts, "MAT", tag,
                String.format(Locale.US, "%.2f", statedV),
                String.format(Locale.US, "%.1f", statedKmh),
                String.format(Locale.US, "%.1f", gpsKmh),
                hex(frame));
    }

    public static void sys(long ts, String msg) {
        line(ts, "SYS", msg, "", "", "", "");
    }

    /** 推算电压时间线：a=推算电压, b=本次里程, c=每公里掉压（只作参考，不参与字段拟合） */
    public static void est(long ts, float v, float km, float perKm) {
        line(ts, "EST", String.format(Locale.US, "%.1f", v),
                String.format(Locale.US, "%.2f", km),
                String.format(Locale.US, "%.4f", perKm), "", "");
    }

    public static void dev(long ts, String addr, String name) {
        line(ts, "DEV", addr, name, "", "", "");
    }

    public static void gatt(long ts, String what, String detail) {
        line(ts, "GAT", what, detail, "", "", "");
    }

    /**
     * v1.0.22：**每 2 秒一条完整状态行**。
     *
     * 加这条的原因：用户明确提出"不要让我手记电压/里程/掉电量，太麻烦，
     * 需要什么数据都记到日志里，我直接发日志给你"。
     * 所以把标定/复盘真正需要的每一项都自动落盘 —— 包括
     * 端电压、上屏电压、静置电压、压降估计、转把、GPS 时速、
     * 电量（显示值 + 电压口径值 + 锚点 + 学到的 %/km + 设置里的满电续航）、
     * 本次/整车里程、纠偏与回升的累计用量、每公里电耗。
     *
     * kv 形如 "v=66.05 dv=66.1 ocv=74.2 ..."（空格分隔，不带逗号）。
     */
    public static void rec(long ts, String kv) {
        line(ts, "REC", hhmmss(ts) + " " + kv, "", "", "", "");
    }

    /** v1.0.22：一段骑行结束时的自动汇总（不改一行代码也能看出这趟表现） */
    public static void sum(long ts, String kv) {
        line(ts, "SUM", hhmmss(ts) + " " + kv, "", "", "", "");
    }

    private static final SimpleDateFormat HMS =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static String hhmmss(long ts) {
        return HMS.format(new Date(ts));
    }

    private static void line(long ts, String kind, String a, String b, String c, String d, String e) {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                if (size > MAX_BYTES) rotateLocked();
                String row = ts + "," + kind + "," + esc(a) + "," + esc(b) + ","
                        + esc(c) + "," + esc(d) + "," + esc(e) + "\n";
                writer.write(row);
                writer.flush();
                // 按字节算：之前 row.length() 数的是字符，中文一行 3 字节，
                // 16MB 的轮转门槛会晚到三倍，一个文件能长到 40MB+
                size += row.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            } catch (Exception ex) {
                Log.e(TAG, "write failed", ex);
            }
        }
    }

    private static void rotateLocked() throws Exception {
        writer.close();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        File dir = file.getParentFile();
        file.renameTo(new File(dir, "raw_" + fmt.format(new Date()) + "_old.csv"));
        file = new File(dir, "raw_" + fmt.format(new Date()) + ".csv");
        writer = new BufferedWriter(new FileWriter(file, true));
        writeHeaderLocked();
        size = 0;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace(",", ";").replace("\n", " ");
    }

    public static String hex(byte[] d) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder(d.length * 3);
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", d[i]));
        }
        return sb.toString();
    }
}
