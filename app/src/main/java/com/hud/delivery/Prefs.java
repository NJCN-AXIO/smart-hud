package com.hud.delivery;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地配置。所有字段都在“设置”面板里可改，改完即时生效，不需要重新编译。
 */
public final class Prefs {

    private static final String FILE = "hud_prefs";
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        migrate();
    }

    /**
     * v1.0.36：修一个会系统性低估电量的老默认值。
     *
     * `v_full` 原来是 78.0V —— 那是**浮充电压**（6×13.0V），不是静置开路电压。
     * 而 [com.hud.core.SocModel] 的参考表是按 76.5V 满电建的，两者一错位，
     * 查表前会先被压缩一遍：75.1V 的满电静置会被映射成 ≈70%，越往下越偏，
     * 低电量段（69.6V→63.0V 那一大段）几乎全被压到个位数。
     * 这就是"显示 4%、实际还能跑很远"的结构性原因之一。
     *
     * 只迁移"还是老默认 78.0"的用户；用户自己改过的值（比如按实测标的 75.6）不动。
     */
    private void migrate() {
        if (sp.getBoolean("mig_vfull_765", false)) return;
        float saved = sp.getFloat("v_full", 76.5f);
        if (Math.abs(saved - 78.0f) < 0.01f) {
            sp.edit().putFloat("v_full", 76.5f).apply();
        }
        sp.edit().putBoolean("mig_vfull_765", true).apply();
    }

    // ---------- 车辆 / 蓝牙 ----------
    public String mac() { return sp.getString("mac", ""); }
    public void setMac(String v) {
        // 土耳其语等地区的默认 Locale 会把 i 变成 İ，MAC 里出现这种字符会连不上
        sp.edit().putString("mac", v == null ? "" : v.trim().toUpperCase(java.util.Locale.ROOT)).apply();
    }

    public String devName() { return sp.getString("dev_name", ""); }
    public void setDevName(String v) { sp.edit().putString("dev_name", v == null ? "" : v.trim()).apply(); }

    // ---------- 语音播报（参照 OpenTracks 的 voice announcements） ----------
    public boolean voice() { return sp.getBoolean("voice", true); }
    public void setVoice(boolean v) { sp.edit().putBoolean("voice", v).apply(); }

    /** v1.0.33：极简模式（只显示速度与电量） */
    public boolean lite() { return sp.getBoolean("lite_mode", false); }
    public void setLite(boolean v) { sp.edit().putBoolean("lite_mode", v).apply(); }

    /** v1.0.33：镜像显示（夜间靠挡风玻璃反射看） */
    public boolean mirror() { return sp.getBoolean("mirror_mode", false); }
    public void setMirror(boolean v) { sp.edit().putBoolean("mirror_mode", v).apply(); }

    /** v1.0.33：超速提醒阈值（km/h），0 = 关闭 */
    public int speedLimit() { return sp.getInt("speed_limit_kmh", 30); }
    public void setSpeedLimit(int v) { sp.edit().putInt("speed_limit_kmh", v).apply(); }

    // ---------- v1.0.38：断开后要冻结的"最后一次真电量" ----------
    /** v1.0.40：上一次已在界面上提示过的崩溃记录（同一条不再重复弹） */    public String lastCrashSeen() { return sp.getString("last_crash_seen", ""); }
    public void setLastCrashSeen(String v) { sp.edit().putString("last_crash_seen", v).apply(); }

    // ---------- v1.0.43：里程断档检测 ----------
    /** 服务最后一次活着的时刻（30 秒更新一次），下次启动据此判断"离开期间有多久没记录" */
    public long lastRunMs() { return sp.getLong("last_run_ms", 0L); }
    public void setLastRunMs(long v) { sp.edit().putLong("last_run_ms", v).apply(); }    // 进程被杀/闪退重启后，内存里的 lastLiveSoc 会丢；不落盘的话重启后又退回账本旧值。
    public float lastSoc() { return sp.getFloat("last_soc_shown", Float.NaN); }
    public long lastSocTs() { return sp.getLong("last_soc_ts", 0L); }
    public void setLastSoc(float v) {
        sp.edit().putFloat("last_soc_shown", v)
                .putLong("last_soc_ts", System.currentTimeMillis())
                .apply();
    }

    // ---------- 电压字段映射（协议解开后填进来） ----------
    public int vOffset() { return sp.getInt("v_off", -1); }
    public void setVOffset(int v) { sp.edit().putInt("v_off", v).apply(); }

    public int vLen() { return sp.getInt("v_len", 2); }
    public void setVLen(int v) { sp.edit().putInt("v_len", v).apply(); }

    public boolean vBig() { return sp.getBoolean("v_big", true); }
    public void setVBig(boolean v) { sp.edit().putBoolean("v_big", v).apply(); }

    public float vScale() { return sp.getFloat("v_scale", 0.1f); }
    public void setVScale(float v) { sp.edit().putFloat("v_scale", v).apply(); }

    public float vBias() { return sp.getFloat("v_bias", 0f); }
    public void setVBias(float v) { sp.edit().putFloat("v_bias", v).apply(); }

    // ---------- 速度字段映射 ----------
    public int sOffset() { return sp.getInt("s_off", -1); }
    public void setSOffset(int v) { sp.edit().putInt("s_off", v).apply(); }

    public int sLen() { return sp.getInt("s_len", 2); }
    public void setSLen(int v) { sp.edit().putInt("s_len", v).apply(); }

    public boolean sBig() { return sp.getBoolean("s_big", true); }
    public void setSBig(boolean v) { sp.edit().putBoolean("s_big", v).apply(); }

    public float sScale() { return sp.getFloat("s_scale", 0.1f); }
    public void setSScale(float v) { sp.edit().putFloat("s_scale", v).apply(); }

    // ---------- 电量模型 ----------
    /** 满电静置电压。实测充满静置 78.3V（72V 铅酸 6 块），所以默认给 78.0，不再用 76.5 */
    /** 满电静置电压（OCV，不是充电时的浮充电压）。72V 铅酸充满静置一晚 ≈ 76.2–76.5 */
    public float vFull() { return sp.getFloat("v_full", 76.5f); }
    public void setVFull(float v) { sp.edit().putFloat("v_full", v).apply(); }

    /** 空电（欠压保护）电压 */
    public float vEmpty() { return sp.getFloat("v_empty", 63.0f); }
    public void setVEmpty(float v) { sp.edit().putFloat("v_empty", v).apply(); }

    // ---------- 里程 ----------
    public float tripKm() { return sp.getFloat("trip_km", 0f); }
    public void setTripKm(float v) { sp.edit().putFloat("trip_km", v).apply(); }

    public long tripStart() { return sp.getLong("trip_start", 0L); }
    public void setTripStart(long v) { sp.edit().putLong("trip_start", v).apply(); }

    // ---------- 整车累计里程（v1.0.4，v1.0.6 校准为实测值） ----------
    // 车自带的累计里程（仪表上的总里程）由用户填一次当基准，之后每次长按清零，
    // 把"本次"加进 App 累计里，界面上就能看到"整车 11244 + 你后来跑的"。
    // 只是显示口径，不参与任何电压/电量计算。
    public int odoBaseKm() { return sp.getInt("odo_base_km", 11244); }
    public void setOdoBaseKm(int v) { sp.edit().putInt("odo_base_km", Math.max(0, v)).apply(); }
    public float odoAccumKm() { return sp.getFloat("odo_accum_km", 0f); }
    public void setOdoAccumKm(float v) { sp.edit().putFloat("odo_accum_km", Math.max(0f, v)).apply(); }

    /** 轮周长（毫米），用于将来用转速换算速度 */
    public int wheelMm() { return sp.getInt("wheel_mm", 1350); }
    public void setWheelMm(int v) { sp.edit().putInt("wheel_mm", v).apply(); }

    // ---------- 手动电压（协议标定前的临时顶班） ----------
    public float manualV() { return sp.getFloat("manual_v", Float.NaN); }
    public void setManualV(float v) { sp.edit().putFloat("manual_v", v).apply(); }

    public long manualTs() { return sp.getLong("manual_ts", 0L); }
    public void setManualTs(long v) { sp.edit().putLong("manual_ts", v).apply(); }

    /** 录入该电压时的里程：有了它，电压才能跟着里程自己往下走 */
    public float manualKm() { return sp.getFloat("manual_km", 0f); }
    public void setManualKm(float v) { sp.edit().putFloat("manual_km", v).putBoolean("manual_km_set", true).apply(); }

    /**
     * 老版本只存了电压没存里程，直接拿 0 当基准会让推算一次性掉一大截。
     * 这个标记用来区分「真的是 0km」和「升级前没记过」。
     */
    public boolean manualKmSet() { return sp.getBoolean("manual_km_set", false); }

    public void clearManual() {
        sp.edit().remove("manual_v").remove("manual_ts").apply();
    }

    // ---------- 电压账本（自学习每公里掉压） ----------
    public String vbook() { return sp.getString("vbook", ""); }
    public void setVbook(String v) { sp.edit().putString("vbook", v == null ? "" : v).apply(); }

    /** 学到的每公里耗多少电量（%/km）；0 = 还没学到 */
    public float socPerKm() { return sp.getFloat("socperkm", 0f); }
    public void setSocPerKm(float v) { sp.edit().putFloat("socperkm", v).apply(); }

    /**
     * 学到的每公里掉多少伏（V/km）；0 = 还没学过。
     * 有它之后，没有实测电压时界面显示的不再是"上次那个恒定的数"，而是真的会往下走。
     */
    public float vPerKm() { return sp.getFloat("vperkm", 0f); }
    public void setVPerKm(float v) { sp.edit().putFloat("vperkm", v).apply(); }

    // ---------- 本次充电周期（满电锚点：按里程数电，不靠骑行中的掉压） ----------
    /** 锚点电量（%，通常是 100） */
    public float cycleSoc() { return sp.getFloat("cycle_soc", Float.NaN); }
    public void setCycleSoc(float v) { sp.edit().putFloat("cycle_soc", v).apply(); }

    /** 锚点时刻的本次里程（km） */
    public float cycleKm() { return sp.getFloat("cycle_km", 0f); }
    public void setCycleKm(float v) { sp.edit().putFloat("cycle_km", v).apply(); }

    /** 锚点建立时间，用来判断"这是不是一次新的充电" */
    public long cycleTs() { return sp.getLong("cycle_ts", 0L); }
    public void setCycleTs(long v) { sp.edit().putLong("cycle_ts", v).apply(); }

    // ---------- 本箱电（v1.0.7：只由"充满"建立，长按清零不动它） ----------
    // 之前复用了 cycleSoc/cycleKm，可是长按清零会把 cycleSoc 改成"当前电量"、
    // cycleKm 改成 0，于是"本箱电"每清一次里程就重新数、Wh 也重新算，名不副实。
    //
    // 这里单独记一本，而且**用整车累计（odoKm）当坐标**，不用本次里程：
    // 本次里程清零会归 0，整车累计不会（清零时它把本次里程累进 accum 了）。
    // 用本次里程当坐标的话，清零一次"本箱电已跑"就归零 —— 这正是旧版的问题。
    public float chargeSoc() { return sp.getFloat("charge_soc", Float.NaN); }
    public float chargeOdoKm() { return sp.getFloat("charge_odo_km", Float.NaN); }
    public void setChargeAnchor(float soc, float odoKm) {
        sp.edit().putFloat("charge_soc", soc).putFloat("charge_odo_km", odoKm).apply();
    }

    public void clearCycle() {
        // v1.0.21（收尾审计）：原来漏删 cycle_km —— 只清 soc/ts 会让"本箱电起点里程"
        // 停在上一箱电的值，于是"本箱电跑了多少"会被算成一个大数，满电续航校准跟着偏。
        sp.edit().remove("cycle_soc").remove("cycle_km").remove("cycle_ts").apply();
    }

    /** 用户自报的满电续航（km），默认 80（实测 70km 跑完还有电，所以别按 70 封顶） */
    public int estRangeKm() { return sp.getInt("est_range_km", 80); }
    public void setEstRangeKm(int v) { sp.edit().putInt("est_range_km", v).apply(); }

    // ---------- 电量锚点（v1.0.0：停车静置校准 + 骑行按里程推算） ----------
    /** 锚点电量（%）；NaN = 还没有锚点 */
    public float socAnchorSoc() { return sp.getFloat("soc_anchor_soc", Float.NaN); }
    public void setSocAnchorSoc(float v) { sp.edit().putFloat("soc_anchor_soc", v).apply(); }

    /** 锚点里程（km，tripKm 口径） */
    public float socAnchorKm() { return sp.getFloat("soc_anchor_km", Float.NaN); }
    public void setSocAnchorKm(float v) { sp.edit().putFloat("soc_anchor_km", v).apply(); }

    public long socAnchorTs() { return sp.getLong("soc_anchor_ts", 0L); }
    public void setSocAnchorTs(long v) { sp.edit().putLong("soc_anchor_ts", v).apply(); }

    /** 学到的每公里耗电 %/km；NaN = 没学到（回落到 100/满电续航） */
    public float perKmPct() { return sp.getFloat("per_km_pct", Float.NaN); }
    public void setPerKmPct(float v) { sp.edit().putFloat("per_km_pct", v).apply(); }

    public void clearSocAnchor() { sp.edit().remove("soc_anchor_soc").remove("soc_anchor_km").remove("soc_anchor_ts").apply(); }

    // ---------- 界面 ----------
    /** true = Compose 三个大数字；false = 原来的自绘界面（出问题时的退路） */
    public boolean uiCompose() { return sp.getBoolean("ui_compose", true); }
    public void setUiCompose(boolean v) { sp.edit().putBoolean("ui_compose", v).apply(); }

    /** 已经学过几段（用来告诉用户可信度） */
    public int socPerKmN() { return sp.getInt("socperkm_n", 0); }
    public void setSocPerKmN(int v) { sp.edit().putInt("socperkm_n", v).apply(); }

    // ---------- 首次引导 ----------
    /** 首次上手引导弹过一次了没（只弹一次，别每次开 App 都挡一下） */
    public boolean tipShown() { return sp.getBoolean("tip_shown", false); }
    public void setTipShown(boolean v) { sp.edit().putBoolean("tip_shown", v).apply(); }

    /**
     * BLE 引擎：true = Nordic 官方 Android-BLE-Library（默认，成熟方案）；
     * false = 早期自写的 BluetoothGattCallback 版本（兜底）。
     * 两个实现都在包里，切换后 1 秒内重建链路。
     */
    public boolean nordicBle() { return sp.getBoolean("ble_nordic", true); }
    public void setNordicBle(boolean v) { sp.edit().putBoolean("ble_nordic", v).apply(); }

    /**
     * 是否也订阅 AE02（实验开关，默认 false）。
     * 实测对照：小程序只订 FFF6 → 收到 20B 帧（带遥测）；我们多订一个 AE02 → 变成 67B 帧（无遥测）。
     * 所以默认不订，保持和小程序同一种"对话方式"。
     */
    public boolean ae02() { return sp.getBoolean("sub_ae02", false); }
    public void setAe02(boolean v) { sp.edit().putBoolean("sub_ae02", v).apply(); }

    // ---------- 电池容量（v1.0.5：算 Wh/km 用） ----------
    // 72V 21Ah 铅酸＝1512Wh。这是**额定能量**，用来把"掉了多少电量"换算成"花了多少瓦时"，
    // 和电压口径无关，改容量不影响电压/电量显示。
    public int packV() { return sp.getInt("pack_v", 72); }
    public void setPackV(int v) { sp.edit().putInt("pack_v", Math.max(12, v)).apply(); }
    public int packAh() { return sp.getInt("pack_ah", 21); }
    public void setPackAh(int v) { sp.edit().putInt("pack_ah", Math.max(1, v)).apply(); }
    public float packWh() { return packV() * packAh(); }

    // ---------- 码表增强（v1.0.5：最高/平均速度、骑行时长） ----------
    public float statsMax() { return sp.getFloat("stats_max", Float.NaN); }
    public void setStatsMax(float v) { sp.edit().putFloat("stats_max", v).apply(); }
    public long statsMs() { return sp.getLong("stats_ms", 0L); }
    public void setStatsMs(long v) { sp.edit().putLong("stats_ms", v).apply(); }

    // ---------- 上次骑行快照（停车那一刻落盘，对标小牛"上次骑行"） ----------
    public float lastTripKm() { return sp.getFloat("last_trip_km", Float.NaN); }
    public long lastTripMs() { return sp.getLong("last_trip_ms", 0L); }
    public float lastTripAvg() { return sp.getFloat("last_trip_avg", Float.NaN); }
    public float lastTripMax() { return sp.getFloat("last_trip_max", Float.NaN); }
    public void setLastTrip(float km, long ms, float avg, float max) {
        sp.edit().putFloat("last_trip_km", km).putLong("last_trip_ms", ms)
                .putFloat("last_trip_avg", avg).putFloat("last_trip_max", max).apply();
    }
    /** 上一次记"上次骑行"时的本次里程：用来判断这算不算新的一趟（≥3km 才算） */
    public float lastTripSegKm() { return sp.getFloat("last_trip_seg_km", 0f); }
    public void setLastTripSegKm(float v) { sp.edit().putFloat("last_trip_seg_km", v).apply(); }

    // ---------- 防盗异动报警（v1.0.5，纯 GPS，不写控制器） ----------
    public boolean alarmOn() { return sp.getBoolean("alarm_on", true); }
    public void setAlarmOn(boolean v) { sp.edit().putBoolean("alarm_on", v).apply(); }

    // ---------- 亮度 / 主题（v1.0.5） ----------
    /** 0 = 自动（夜里自动压暗）、1 = 常亮、2 = 常暗 */
    public int brightMode() { return sp.getInt("bright_mode", 0); }
    public void setBrightMode(int v) { sp.edit().putInt("bright_mode", v % 3).apply(); }

    /** 配色主题：0 绿 / 1 青 / 2 琥珀 / 3 白 */
    public int themeIdx() { return sp.getInt("theme_idx", 0); }
    public void setThemeIdx(int v) { sp.edit().putInt("theme_idx", Math.max(0, v % 4)).apply(); }

    public void dump() {
        android.util.Log.i("HudPrefs", sp.getAll().toString());
    }
}
