package com.hud.delivery;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.hud.core.EnergyBook;
import com.hud.core.GapFill;
import com.hud.core.GpsSpeed;
import com.hud.core.SocDisplay;
import com.hud.core.SocModel;
import com.hud.core.SocSmooth;
import com.hud.core.SocTracker;
import com.hud.core.SpeedStabilizer;
import com.hud.core.TrackDistance;
import com.hud.core.TripStats;
import com.hud.core.VoltDecay;
import com.hud.core.VoltFilter;
import com.hud.engine.BleLink;
import com.hud.engine.HudBle;
import com.hud.engine.NordicBleLink;
import com.hud.protocol.FieldMap;
import com.hud.protocol.YuNingFrame;
import com.hud.protocol.YuNingProtocol;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 前台服务：只做"应用层"的事 —— 解析、平滑、里程、落盘、喂给 UI、保活通知。
 *
 * 蓝牙那一段（扫描 / 连接 / 重连 / 订阅队列 / GATT 回调）全部在
 * :engine 的 {@link BleLink} 里，这里只实现它的回调：
 *   onNotify  → 把字节交给 :protocol 的 YuNingProtocol 解析
 *   onState   → 更新界面连接状态
 *   onGatt    → 原始事件落盘，供事后分析
 *
 * 红线不变：除了把 CCCD 写成 0x0100（开启通知的标准做法），不向控制器写任何数据。
 */
public class BleService extends Service implements HudBle.Listener {

    private static final String TAG = "HudService";
    private static final String CH_ID = "hud_ble";
    /** 焦点通知/岛的通道：IMPORTANCE_DEFAULT 才可能被系统抬成焦点通知，LOW 会被压成普通通知 */
    private static final String CH_FOCUS = "hud_focus";
    /** 防盗报警通道：IMPORTANCE_HIGH，才能震动能响、能浮在锁屏上（对标九号/小牛的异动报警） */
    private static final String CH_ALARM = "hud_alarm";
    private static final int NOTIF_ID = 1001;
    private static final int ALARM_NOTIF_ID = 1002;

    private final Handler h = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private HudBle.Transport link;

    private YuNingProtocol protocol;
    /**
     * 电压显示滤波（v1.0.0）：控制器 67B 帧的电压是瞬时采样，实测同一分钟在 58–69V 之间跳。
     * 上屏前过一道 9 点中位数（≈4.5 秒），既能压掉离群尖峰，又不会像滑动平均那样被一个坏值拖几秒。
     */
    private VoltFilter voltFilter;
    /** 数据流断过之后，本次上电见过的最高（中位数）电压：冷启动电量起点用它，别用带载瞬时值 */
    private float bootMaxV = Float.NaN;
    /** 上一次往静置平滑器里喂电压的时刻（按"帧"喂，不按 tick 喂） */

    /** 静置电压的 30 秒慢平滑：只用来"停车时校准电量锚点" */
    private VoltFilter restFilter;
    /** v1.0.57：最后一次有效的静置均值（断链/熄火后 20 分钟内还能拿它做保守校准） */
    private float lastRestV = Float.NaN;
    private long lastRestVMs = 0L;
    /** v1.0.30：逐帧"电压 + 该帧压降"的分位器 —— 查电量表用的 OCV 从这里取，不再拿错配的值 */
    private VoltFilter ocvCand;
    /** 带载电压 → 空载电压的压降估计：只在"还没有锚点"时给电量一个起点用 */
    private final com.hud.core.LoadComp loadComp = new com.hud.core.LoadComp();
    /** 电量：停车静置锚点 + 骑行按里程推算（不再是"每秒拿噪声电压查表"） */
    private SocTracker socTracker;
    /**
     * v1.0.18：「换了新电瓶」的复位请求。
     *
     * 设置页只在 UI 进程里改 SharedPreferences，可跑的 `socTracker` 在服务里
     * 还抱着旧电瓶学到的 3.7%/km 和旧锚点 —— 不重建的话，点了按钮也照样按旧电瓶算。
     * 用这个标记让 tick 自己重建一次，避免跨组件去拿服务实例。
     */
    public static final java.util.concurrent.atomic.AtomicBoolean packResetRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private SocModel soc;
    private TripTracker trip;
    private VoltBook voltBook;

    private volatile boolean running = false;

    private long rateStartMs = 0L;
    private long rateCount = 0L;

    /** 蓝牙实时电压（滑动平均后）。NaN = 还没标定/还没数据 */
    private float liveV = Float.NaN;
    /** v1.0.32：这一帧的**原始**电压（没进滤波器），日志里用来逐帧复算 */
    private float lastRawV = Float.NaN;
    /** v1.0.32：真正喂给电量表的那个电压（逐帧配平的 OCV 分位值），调参全靠它 */
    private float lastVMap = Float.NaN;
    /** v1.0.32：上一次记过日志的电量整数，用来只在"跳一格"时落一行 SOC 记录 */
    private int lastLoggedSoc = Integer.MIN_VALUE;
    /** v1.0.33：手机电量采样节流 */
    private long lastPhonePctMs = 0L;
    /** v1.0.33：超速语音节流（60 秒最多喊一次） */
    private long lastSpeedWarnMs = 0L;
    /**
     * v1.0.36：电量百分比显示层滤波（6 秒窗中位数）。
     *
     * 电压已经滤过，但"电压→百分比"是查表，低端很陡：电压 0.3V 的正常抖动能放大成 5–10 个点；
     * 再叠加 ocvCand（45 秒窗）与瞬时值之间的切换，就会看到"同一时刻 51%→17%"。
     * 这里对**算出来的百分比**再取一次中位数 —— 这是 BMS 显示 SOC 的常规做法，
     * 不是又加一层"猜测"：输入的仍然是这一帧电压查出来的真值。
     */
    // v1.0.49：中位窗 6s → 12s。
    // 实车日志里 30km/h 巡航时端电压能在 30 秒内摆 4–5V（油门几乎没变），查表值跟着摆约 10 分；
    // 6 秒窗只压得住一半，于是屏幕上能看到电量自己往上爬两格。12 秒窗只是让"这个数"更稳，
    // 不影响任何真实变化的量（真正掉电是持续的，中位数一样跟得下来）。
    private SocSmooth socSmooth = new SocSmooth(12000L, 96, 0.5);
    /**
     * v1.0.37：**最后一次连上时真正显示过的电量**。
     *
     * 旧逻辑断开后取 `socTracker.getShown()`，但 v1.0.30 改成"连上就直读"之后，
     * 直读分支只往账本写锚点、不再推进 shown —— 于是断开那一刻冻结的是一个**几小时前**的旧值。
     * 用户实测：连着时 100%，一断开变 29%、"还能跑 ~30km"，就是这么来的。
     * 现在断开就冻结"最后那一帧真的算出来的数"。
     */
    private float lastLiveSoc = Float.NaN;
    /**
     * v1.0.44：SOC 显示层 —— 死区 + 限速 + 重连直跳（数学在 :core.SocDisplay）。
     *
     * 6 秒中位数（socSmooth）压得住单帧异常，但压不住"一整个 45 秒窗换气"
     * （实测电压几乎不动，屏幕 4 秒内 75→69→64）。所以输出端再加一层限速：
     * 目标值先过中位数，再经过这里，屏幕每秒最多动 2 分（下降）/ 6 分（回升）。
     */
    private SocDisplay socDisplay = new SocDisplay();
    /** 上一次"有帧"的 tick 时间：用来判断"断了几分钟才回来"，决定要不要允许直跳 */
    private long lastLiveTickMs = 0L;
    /**
     * v1.0.45：重连后的"直跳确认窗"（20 秒）。
     * 单看第一帧就允许直跳，会被 45 秒窗还没收敛的中间值骗到（日志里 63→76→67 就是这么来的）。
     * 现在重连后给 20 秒窗口，SocDisplay 在这段里要求"目标值稳住 6 秒"才落位。
     */
    private long snapWindowUntil = 0L;
    /** 最近一次收到"可用电压"的时间：超过 8 秒就作废，别让旧值骗人 */
    private long lastLiveVMs = 0L;
    /** v1.0.30：直读电量时，把真值同步进账本的节流时间（断开后才用得着） */
    private long lastSocSyncMs = 0L;
    /** 假连接看门狗上一次重连的时间 */
    private long lastWatchdogMs = 0L;

    /** 上一次写 EST 时间线时的里程 */
    private float lastEstKm = -1f;

    /** GPS 距离兜底的上次读数（km，-1 = 还没有数据） */
    private float lastGpsKm = -1f;
    /** v1.0.49：上一次记"步行不记里程"这条日志的时刻（30 秒节流，别刷屏） */
    private long lastWalkLogMs = 0L;
    /** v1.0.53：上一次记"里程盲区（有速度没位移）"的时刻（60 秒节流） */
    private long lastOdoBlindLogMs = 0L;
    /** v1.0.53：上一次"连不上 → 硬重建链路"的时刻（10 分钟节流） */
    private long lastHardResetMs = 0L;
    /**
     * v1.0.54：硬重建的退避间隔（毫秒）。在家熄火时控制器本来就不应答，
     * 固定 10 分钟重建一次纯属耗电；改成 6→12→24→30 分钟封顶，连上就复位。
     */
    private long hardResetBackoffMs = 6L * 60_000L;
    /** v1.0.50：上一次记"OCV 窗口刚重建、先不动"的时刻（30 秒节流） */
    private long lastFreshHoldLogMs = 0L;
    /** v1.0.50：端电压连续低于欠压线的起点（0 = 没低），避免满载爬坡被误报成"低压保护" */
    private long lowVoltSinceMs = 0L;
    /** v1.0.50：心跳节流 + 上一拍的电量来源（心跳日志里带上，方便判断"当时是实时读数还是冻结值"） */
    private long lastHeartbeatMs = 0L;
    private int hudSrcLast = HudState.SRC_NONE;

    /** 通知栏上一次刷新时间（每 5 秒刷一次数值，骑车不看手机也能瞄一眼） */
    private long lastNotifMs = 0L;

    /** 审计行（每 30 秒一条 state …）上一次写入时间 */
    private long lastStateLogMs = 0L;

    /** v1.0.22：完整状态行（REC，每 2 秒一条）上一次写入时间 */
    private long lastRecMs = 0L;

    /** v1.0.22：本次"服务生命周期"的起点与极值，供结束时的 SUM 汇总行使用 */
    private boolean sessStarted = false;
    private long sessTs0 = 0L;
    private float sessV0 = Float.NaN;
    private float sessSoc0 = Float.NaN;
    private float sessKm0 = 0f;
    private float sessOdo0 = Float.NaN;
    private float sessMinV = Float.NaN;
    private float sessMaxV = Float.NaN;
    private long lastSumMs = 0L;

    private static String n0(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? "-" : String.format(Locale.US, "%.0f", v);
    }

    private static String n1(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? "-" : String.format(Locale.US, "%.1f", v);
    }

    private static String n2(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? "-" : String.format(Locale.US, "%.2f", v);
    }

    private static String n3(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? "-" : String.format(Locale.US, "%.3f", v);
    }

    /** 最近一个定位点的精度（米，-1 = 还没定位），审计行要带上 */
    private float lastAccM = -1f;

    private LocationManager lm;

    /** 扫描日志去重：同一 MAC 30 秒只记一次，避免刷爆 CSV */
    private static final Map<String, Long> SCAN_SEEN = new HashMap<>();

    // ---------------- lifecycle ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        RawLog.init(this);
        // v1.0.40：每次起服务把版本写进日志 —— 事后复盘时第一眼就能确认"这份日志是哪个版本的"
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        RawLog.sys(System.currentTimeMillis(), "build v" + ver);
        // v1.0.48：导航联动没有授权时，启动后 90 秒内在主界面提示一次"去哪开"，过时不候
        HudState.navHintUntil = System.currentTimeMillis() + 90_000L;
        // 权限状态先查一次，别等 tick 的 30 秒节流（否则刚进主界面会误显示"未开"）
        HudState.navPerm = NavBridge.enabled(this);
        h.post(new Runnable() {
            @Override public void run() {
                startForegroundSafe();
            }
        });
        rebuildDecoder();
        // v1.0.33：界面模式从设置里恢复（进程被杀重启后不该回到默认布局）
        HudState.liteMode = prefs.lite();
        HudState.mirrorMode = prefs.mirror();
        trip = new TripTracker(prefs);
        voltBook = new VoltBook(this);
        HudState.tripKm = trip.km();
        // 恢复上次会话学到的电量锚点/耗电率：进程重启不该又从噪声电压里猜电量
        // 老版本学到的 socPerKm（%）继续当初始值用，别让用户白跑几十公里
        long anchorTs = prefs.socAnchorTs();
        // 超过 18 小时的锚点不能信：中间可能充过电，也可能换了电池
        if (anchorTs > 0 && System.currentTimeMillis() - anchorTs > 18L * 3600_000L) {
            prefs.clearSocAnchor();
            RawLog.sys(System.currentTimeMillis(), "soc anchor older than 18h, cleared");
        }
        // v1.0.58：**空电硬着陆锚点只认 30 分钟。**
        // 11-22 现场：前一箱末端空电锁在 3%，车搁了 73 分钟（铅酸回弹/可能补过电，
        // 静置端电压已 72.7–73.6V ≈ 50%），开机却还按 3% 锚点推算，全天显示 4%、
        // 每 5 分钟低电告警一次。30 分钟以上的底部锚点默认已经回弹，清掉让第一帧
        // 静置电压重新起锚；30 分钟内同一段骑行续上的空电状态仍然保留。
        if (prefs.socAnchorSoc() <= 5f && anchorTs > 0
                && System.currentTimeMillis() - anchorTs > 30L * 60_000L) {
            RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                    "bottom anchor %.0f%% aged %d min, cleared (battery likely rebounded/recharged)",
                    prefs.socAnchorSoc(), (System.currentTimeMillis() - anchorTs) / 60_000L));
            prefs.clearSocAnchor();
        }
        float seedPerKm = prefs.perKmPct();
        if (Float.isNaN(seedPerKm) && prefs.socPerKm() > 0.05f) seedPerKm = prefs.socPerKm();
        // v1.0.37：清掉历史遗留的"毒样本"。
        // 老版本在被带载压降骗过的那几趟里，学过 3.3%/km 这类值（折合满电只有 30km），
        // 而且它一直存在手机里：新版本即使电压口径修好了，续航仍会按 3.3 算出"还能跑 30km"。
        // 正常 21Ah/72V 是 1.2–1.3%/km；>2.0%/km 只可能来自旧口径的错样本，直接丢掉重学
        // （停车静置校准会重新学，采信区间仍限定在"满电 20–150km"）。
        if (!Float.isNaN(seedPerKm) && seedPerKm > 2.0f) {
            RawLog.sys(System.currentTimeMillis(),
                    String.format(Locale.US, "per_km %.2f%%/km looks poisoned (>2.0), cleared", seedPerKm));
            seedPerKm = Float.NaN;
            prefs.setPerKmPct(Float.NaN);
            prefs.setSocPerKm(Float.NaN);
        }
        socTracker = new SocTracker(seedPerKm, prefs.socAnchorSoc(), prefs.socAnchorKm());
        socTracker.setDefaultRangeKm(prefs.estRangeKm());
        // v1.0.38：恢复"最后一次真电量"。闪退/被杀重启后若丢了它，界面会退回账本旧值
        // （那正是"断开后电量还是很早之前"的另一个来源）。12 小时内的才认。
        long lastSocTs0 = prefs.lastSocTs();
        // v1.0.43：判断"这次启动前，App 有几年没在车上"
        // 实车对照：仪表 11271、App 11248.9，差 22km —— 差的这段就是 App 没运行时跑掉的，
        // GPS 没采到就永远补不回来。所以启动时把断档时长摆在状态行，用户按一下仪表读数即可对上。
        long lastRun = prefs.lastRunMs();
        if (lastRun > 0L) {
            long gapMs = System.currentTimeMillis() - lastRun;
            if (gapMs > 10L * 60_000L) {
                long min = gapMs / 60_000L;
                // v1.0.48：缩短成一行能放下的说法（原来是"离开 1 小时 17 分未记录里程 · 长按总里程可对齐"，
                // 11sp 一行放不下，会在三个按钮上方折成两行，把上面的大数字整体顶上去）
                HudState.gapWarn = "断档 " + (min >= 60 ? (min / 60) + "h" + (min % 60) + "m"
                        : min + "m") + " · 长按总里程对齐";
                gapWarnMs = System.currentTimeMillis();   // v1.0.46：这提示只挂 15 分钟，别一直占着状态行
                RawLog.sys(System.currentTimeMillis(),
                        "gap since last run: " + min + " min (odometer may be short)");
            }
        }
        prefs.setLastRunMs(System.currentTimeMillis());
        if (lastSocTs0 > 0L && System.currentTimeMillis() - lastSocTs0 < 12L * 3600_000L) {
            lastLiveSoc = prefs.lastSoc();
            // v1.0.44：显示层也从这里起手 —— 断链重开时先摆上去一次的读数，别从 NaN 开始飘
            socDisplay.seed(lastLiveSoc, System.currentTimeMillis());
            RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                    "restored last soc=%.0f (%dh ago)", lastLiveSoc,
                    (System.currentTimeMillis() - lastSocTs0) / 3600_000L));
        }
        socTracker.update(System.currentTimeMillis(), trip.km(), Float.NaN);
        HudState.devLabel = prefs.devName().isEmpty() ? prefs.mac() : prefs.devName();
        HudState.manualV = prefs.manualV();
        HudState.manualTs = prefs.manualTs();
        // v1.0.42：**开机就退休旧的手动锚点**，不再等"收到第一帧"。
        //
        // v1.0.38 只在 onNotify 里退休（收到真帧那一刻），于是出现一个很难受的状态：
        // 没连控制器时，几天前手输的 71.3V 仍然顶班 —— 界面显示 70.6V / 27%，
        // 用户看到的是"没连车却有读数"，而且这个数还是几天前的推算（日志 14:55:
        // `state v=70.6 src=3 soc=27`，同一行 `ocv=NaN` 说明根本没有真帧）。
        //
        // 口子收紧到"6 小时前的锚点一律作废"（manualTs=0 的老数据也算作废）：
        // 协议早就解出来了，直读电压才是主路径；手动值只在"你刚填的这半天内"有意义。
        if (!Float.isNaN(HudState.manualV)
                && (HudState.manualTs == 0L
                || System.currentTimeMillis() - HudState.manualTs > 6L * 3600_000L)) {
            RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                    "manual anchor retired at startup (%.1fV, age %s) : direct read is the main path",
                    HudState.manualV, HudState.manualTs == 0L ? "unknown"
                            : ((System.currentTimeMillis() - HudState.manualTs) / 3600_000L) + "h"));
            HudState.manualV = Float.NaN;
            HudState.manualTs = 0L;
            prefs.setManualV(Float.NaN);
            prefs.setManualTs(0L);
        }
        // 老版本只存了电压、没存里程：把基准点挪到"现在"，否则一升级电压会突然掉一大截
        if (!Float.isNaN(HudState.manualV) && !prefs.manualKmSet()) {
            prefs.setManualKm(trip.km());
            RawLog.sys(System.currentTimeMillis(), "manual anchor re-based at " + trip.km() + "km");
        }
        HudState.manualKm = prefs.manualKm();

        // v1.0.5：恢复码表统计与上次骑行快照（进程被杀/系统杀后台后不用从头算）
        stats.restore(prefs.statsMax(), prefs.statsMs());
        HudState.maxKmh = stats.getMaxKmh();
        HudState.rideSec = stats.getMovingSec();
        HudState.avgKmh = stats.avgKmh(trip.km());
        HudState.lastTripKm = prefs.lastTripKm();
        HudState.lastTripSec = prefs.lastTripMs() / 1000L;
        HudState.lastTripAvg = prefs.lastTripAvg();
        HudState.lastTripMax = prefs.lastTripMax();
        HudState.themeIdx = prefs.themeIdx();
        // 本次里程是跨会话累计的：能耗窗口和统计的基准都从当前里程接上
        if (!Float.isNaN(HudState.soc)) energy.add(HudState.tripKm, HudState.soc);

        link = buildEngine();
        registerBtWatcher();
        registerTripResetReceiver();
        initVoice();
    }

    /** 长按清零的接收器（只在同 App 内广播，不导出） */
    private BroadcastReceiver tripResetRx;

    private void registerTripResetReceiver() {
        tripResetRx = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (ACTION_TRIP_RESET.equals(intent.getAction())) {
                    resetTripInMemory(intent.getBooleanExtra(EXTRA_ALREADY_FOLDED, false));
                } else if (ACTION_TRIP_ADD.equals(intent.getAction())) {
                    float d = intent.getFloatExtra(EXTRA_TRIP_DELTA, 0f);
                    addTripInMemory(d);
                } else if (ACTION_TRIP_SET.equals(intent.getAction())) {
                    float km = intent.getFloatExtra(EXTRA_TRIP_KM, 0f);
                    setTripInMemory(km);
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_TRIP_RESET);
        f.addAction(ACTION_TRIP_ADD);
        f.addAction(ACTION_TRIP_SET);
        // v1.0.12：统一用 ContextCompat.registerReceiver —— 它在 API 33 以下会自动忽略 flag，
        // 33+ 才真正生效，比手写 if (SDK_INT >= 33) 分支更稳（旧写法在 33+ 的某些机型上会抛
        // IllegalArgumentException: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified）。
        androidx.core.content.ContextCompat.registerReceiver(this, tripResetRx, f,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    // ---------------- 语音播报（参照 OpenTracks / 码表类项目的 voice announcements） ----------------

    private TextToSpeech tts;
    private boolean ttsReady;
    /**
     * 已经播报到第几个 5 公里（1 = 5km，2 = 10km…）。
     * v0.9.9 改：以前存的是"上次播报的里程"，里程清零后它就恒大于当前里程，
     * 播报会一直停到里程重新超过旧值为止（用户以为语音坏了）。
     */
    private int lastAnnounceBucket = 0;

    /**
     * 骑车时不可能一直看屏幕，所以关键信息用"听"的：
     *  - 每 5 公里报一次「已行驶 X 公里，电量 X%」
     *  - 低电时（和震动同一时刻）报「电量不足，还剩 X%」
     *
     * 只做本地 TTS，不联网、不需要额外权限；引擎不可用（没有中文包）就静默降级，
     * 绝不因为播报失败影响主功能。
     */
    private void initVoice() {
        HudState.voiceOn = prefs.voice();
        if (!HudState.voiceOn) return;
        try {
            tts = new TextToSpeech(this, status -> {
                if (status != TextToSpeech.SUCCESS) {
                    RawLog.sys(System.currentTimeMillis(), "tts init failed status=" + status);
                    HudState.voiceReady = false;
                    HudState.touch();
                    return;
                }
                int r = tts.setLanguage(Locale.CHINA);
                boolean ok = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                ttsReady = ok;
                HudState.voiceReady = ok;
                HudState.touch();
                RawLog.sys(System.currentTimeMillis(), "tts ready=" + ok + " lang=" + r);
            });
        } catch (Exception e) {
            Log.w(TAG, "tts create failed", e);
            HudState.voiceReady = false;
        }
    }

    private void speak(String text) {
        if (!ttsReady || tts == null) return;
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hud-announce");
        } catch (Exception e) {
            Log.w(TAG, "tts speak failed", e);
        }
    }

    /** 每 5 公里报一次（按"第几个 5 公里"计数，清零后自然归零，恢复播报） */
    private void maybeAnnounceKm() {
        float km = HudState.tripKm;
        if (Float.isNaN(km)) return;
        if (km < 5f) {
            if (km < 1f) lastAnnounceBucket = 0; // 用户清零了：计数跟着归零
            return;
        }
        int bucket = (int) (km / 5f);
        if (bucket <= lastAnnounceBucket) return;
        lastAnnounceBucket = bucket;
        float soc = HudState.soc;
        speak(String.format(Locale.US, "已行驶%.0f公里，电量%s",
                km, Float.isNaN(soc) ? "未知" : String.format(Locale.US, "百分之%.0f", soc)));
    }

    /**
     * 蓝牙被手动关掉再打开（很多车钥匙一拧，手机的蓝牙省电策略会短暂重启蓝牙适配器）。
     * 不监听的话，适配器重启后我们的连接就变成僵尸，界面一直"断开"却不再自愈。
     */
    private BroadcastReceiver btWatcher;

    private void registerBtWatcher() {
        btWatcher = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
                int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (st == BluetoothAdapter.STATE_ON) {
                    RawLog.sys(System.currentTimeMillis(), "bluetooth ON -> relink");
                    h.postDelayed(() -> {
                        if (link != null) link.reconnectNow();
                    }, 800L);
                } else if (st == BluetoothAdapter.STATE_OFF) {
                    RawLog.sys(System.currentTimeMillis(), "bluetooth OFF");
                    HudState.setConn(HudState.IDLE, "蓝牙已关闭");
                }
            }
        };
        IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btWatcher, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(btWatcher, f);
    }

    /**
     * 选 BLE 引擎。默认用 Nordic 官方库（成熟方案，GATT 队列/重试/超时都由库处理）；
     * 万一在某台手机上连不上，设置里可以切回早期自写的实现，两个都在包里。
     */
    private HudBle.Transport buildEngine() {
        if (prefs.nordicBle()) {
            RawLog.sys(System.currentTimeMillis(), "ble engine = nordic (library 2.11.0)");
            NordicBleLink n = new NordicBleLink(this, this);
            // 默认只订 FFF6（和小程序的订阅组合一致）。
            // 注意：14:11–14:56 那趟 3504 帧证明"勾上 AE02 也照样全是 67B、AE02 零通知"，
            // 所以这个开关不影响帧格式，只是多一路空订阅；留着只为复现对比实验。
            n.setExperimentalAe02(prefs.ae02());
            RawLog.sys(System.currentTimeMillis(), "subscribe ae02 = " + prefs.ae02()
                    + (prefs.ae02() ? " (实验项：实测无数据，建议关掉)" : ""));
            return n;
        }
        RawLog.sys(System.currentTimeMillis(), "ble engine = legacy (hand-written)");
        return new BleLink(this, this);
    }

    /** 长按清零的广播：界面改了 prefs，但内存里的里程积分器在服务里，必须让它一起归零 */
    public static final String ACTION_TRIP_RESET = "com.hud.delivery.action.TRIP_RESET";
    /**
     * v1.0.49：界面清零时附带这个标记 = 「本次里程我已经折进 App 累计了」。
     * 服务收到后只清内存/落盘，不再折第二遍（旧版两边都折，碰上一次 tick 就先翻倍）。
     */
    public static final String EXTRA_ALREADY_FOLDED = "already_folded";

    /**
     * v1.0.56：对表时把差额并进「本次里程」的广播。
     *
     * 用户原话："总里程变了、本次里程数量没变，这里要修复一下"。
     * 对表补的是"仪表比 App 多走的那一段"，它确实发生在这趟里，所以本次里程也该一起补；
     * 否则总里程是 11476、本次只有 70.3，续航和 %/km 又按 70.3 去算 —— 两头互相矛盾。
     */
    public static final String ACTION_TRIP_ADD = "com.hud.delivery.action.TRIP_ADD";
    public static final String EXTRA_TRIP_DELTA = "trip_delta_km";

    /**
     * v1.0.59：对表时把「本次里程」直接覆盖到目标值的广播。
     *
     * 旧版对表走 TRIP_ADD（加差额）：界面内存副本和服务端积分器是两份副本，5 秒落盘
     * 节奏下两者已经差出几公里，各自扣各自的，重启回读服务端副本后对表减掉的里程又
     * 弹回总里程（11-24 07:55 对齐 −4.00，07:58 重启总里程回到 11604.1）。
     * 改成 SET：界面按持久账本算出目标本次里程，服务端直接覆盖积分器并立即落盘，
     * 锚点里程按"目标 − 当前"的差平移。
     */
    public static final String ACTION_TRIP_SET = "com.hud.delivery.action.TRIP_SET";
    public static final String EXTRA_TRIP_KM = "trip_km";

    /**
     * v0.9.9 修的一个真 bug：以前"长按清零"只写了 SharedPreferences 和 HudState，
     * 而正在跑的服务手里还攥着旧的 TripIntegrator（19.1km），下一秒 tick 就把
     * HudState.tripKm 又写回 19.1 —— 界面上表现为"清零后立刻弹回"。
     * 现在界面发广播，服务在这里把内存、落盘、各里程锚点一起归零。
     */
    /**
     * v1.0.22：一段骑行的自动汇总行（SUM）。
     *
     * 用户不想手记任何东西 → 每趟结束（清零 / 停车 / 退出服务）自动写一条，
     * 只发日志就能看懂"这趟从多少电跑到多少电、掉了几伏、纠偏动过几次"。
     */
    private void writeSum(long now, String why) {
        if (!sessStarted) return;
        float km = Math.max(0f, HudState.tripKm - sessKm0);
        if (km < 0.1f) return;                       // 没跑就别占一行
        if (now - lastSumMs < 60_000L && !"reset".equals(why)) return;
        lastSumMs = now;
        long durMin = Math.max(0L, (now - sessTs0) / 60_000L);
        RawLog.sum(now, new StringBuilder(170)
                .append("why=").append(why)
                .append(" km=").append(n2(km))
                .append(" odo=").append(n1(HudState.odoKm))
                .append(" soc=").append(n0(sessSoc0)).append("->").append(n0(HudState.soc))
                .append(" v=").append(n1(sessV0)).append("->").append(n1(HudState.voltage))
                .append(" vmin=").append(n1(sessMinV))
                .append(" vmax=").append(n1(sessMaxV))
                .append(" mx=").append(n0(HudState.maxKmh))
                .append(" av=").append(n0(HudState.avgKmh))
                .append(" nud=").append(n1(socTracker.getNudgedTotal()))
                .append(" rcv=").append(n1(socTracker.getRecoveredTotal()))
                .append(" pk=").append(n3(HudState.socPerKm))
                .append(" usr=").append(n0(prefs.estRangeKm()))
                .append(" wh=").append(n0(HudState.whPerKm))
                .append(" dur=").append(durMin)
                .toString());
        // 汇总完，把基线挪到"现在"，下一段从零开始算
        sessKm0 = HudState.tripKm;
        sessSoc0 = HudState.soc;
        sessV0 = HudState.voltage;
        sessTs0 = now;
        sessMinV = Float.NaN;
        sessMaxV = Float.NaN;
    }

    /**
     * @param alreadyFolded 界面那边已经把「本次里程」折进 App 累计了（v1.0.49 之后会带这个标记）。
     *
     * 为什么要有这个参数：旧版本是**两边都折**——MainActivity 先把 km 写进 odoAccum，
     * 再广播过来；服务这边如果碰巧还没读到 HudState.tripKm=0（tick 刚把它写回 5.0），
     * 就会再折一次，总里程凭空多出一整趟（几公里）。现在由界面明确告知"我折过了"，
     * 服务只负责把内存/落盘清干净，不重复累加。
     */
    /**
     * v1.0.56：对表补进来的差额，并进「本次里程」。
     *
     * 服务里那份 TripIntegrator 才是"本次里程"的真身，界面改 HudState 会在下一秒被覆盖，
     * 所以必须走广播。顺带把电量锚点平移同样的距离 —— 否则里程突然 +7km，
     * `predict` 立刻把电量往下拽十几点，屏幕上又是一次"跳电"。
     */
    private void addTripInMemory(float deltaKm) {
        if (Float.isNaN(deltaKm) || Math.abs(deltaKm) < 0.02f) return;
        long now = System.currentTimeMillis();
        // 扣得太狠（负数比当前里程还大）就只扣到 0
        if (deltaKm < 0f && trip != null) deltaKm = Math.max(deltaKm, -trip.km());
        if (trip != null) trip.addKm(deltaKm, now);
        HudState.tripKm = trip != null ? trip.km() : Math.max(0f, HudState.tripKm + deltaKm);
        prefs.setTripKm(HudState.tripKm);
        if (socTracker != null) socTracker.shiftAnchorKm(deltaKm);
        HudState.touch();
        RawLog.sys(now, String.format(Locale.US,
                "trip += %.2fkm (odometer align, now %.2f)", deltaKm, HudState.tripKm));
    }

    /**
     * v1.0.59：把本次里程直接覆盖到目标值（对表专用）。
     *
     * 与 {@link #addTripInMemory} 的区别：这里不依赖服务端当前副本加差额，而是直接
     * 覆盖积分器并立刻落盘；锚点里程按"目标 − 当前"平移，避免两份副本各扣各的。
     */
    private void setTripInMemory(float targetKm) {
        if (Float.isNaN(targetKm)) return;
        targetKm = Math.max(0f, targetKm);
        long now = System.currentTimeMillis();
        float before = trip != null ? trip.km() : HudState.tripKm;
        float shift = targetKm - before;
        if (trip != null) trip.setKm(targetKm);
        HudState.tripKm = targetKm;
        prefs.setTripKm(targetKm);
        if (socTracker != null && Math.abs(shift) >= 0.02f) socTracker.shiftAnchorKm(shift);
        HudState.touch();
        RawLog.sys(now, String.format(Locale.US,
                "trip set %.2fkm <- %.2fkm (odometer align; anchor shift %+.2f)",
                targetKm, before, shift));
    }

    /**
     * v1.0.56：控制器断电保护的那一刻，正好是"这箱电的真实可用里程"最硬的一次标定。
     *
     * 11-20/21 实测：满电 11399 → 断电 11476，77km；而设置的满电续航是 87.6（早前学歪了），
     * 于是底部还剩 8~19km 的假余量。这里把 estRange 直接改成"本箱电实际跑了多少"，
     * 下一箱电的续航从第一天就是准的。
     *
     * 只在"这箱电真的是从满电起算"（cycleSoc ≥ 95）且跑了 ≥20km 时才学 —— 半路充一次电不算样本。
     */
    private void learnTrueRangeOnCutoff(float kmCharge) {
        if (Float.isNaN(kmCharge) || kmCharge < 20f) return;
        float cSoc = prefs.cycleSoc();
        if (Float.isNaN(cSoc) || cSoc < 95f) return;
        int er = (int) Math.round(kmCharge);
        if (er < 40 || er > 250) return;
        int old = prefs.estRangeKm();
        prefs.setEstRangeKm(er);
        if (socTracker != null) {
            float p = socTracker.relaxPerKm(0.9f, 100f / er);
            if (!Float.isNaN(p)) prefs.setPerKmPct(p);
        }
        RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                "est_range learned from cutout: %d -> %d km (rode %.1f km from %.0f%%)",
                old, er, kmCharge, cSoc));
    }

    private void resetTripInMemory(boolean alreadyFolded) {
        long now = System.currentTimeMillis();
        // v1.0.22：清零前先写一条 SUM，把这一趟的表现留在日志里（折叠与统计归零都在 helper 里）
        if (alreadyFolded) {
            // 累计已经由界面折过了，但这一趟的汇总（SUM）还得写 —— 复盘全靠它
            if (trip != null && trip.km() > 0.05f) writeSum(now, "user reset");
            prefs.setTripKm(0f);
            prefs.setTripStart(now);
            if (trip != null) trip.reset(now);
            clearTripState(now);
            RawLog.sys(now, "trip reset by user (folded by ui, no double count)");
        } else {
            resetTripFoldingOdo(now, "user reset");
        }
        // 电压哨点的基准跟着挪，推算才不会跑偏
        if (!Float.isNaN(HudState.manualV)) {
            prefs.setManualKm(0f);
            HudState.manualKm = 0f;
        }
        // 本箱电的账本也重新起算：锚点电量取"清零这一刻的电量"，不然没电压时电量会跳回 100%
        if (!Float.isNaN(HudState.soc)) {
            prefs.setCycleSoc(HudState.soc);
        }
        if (prefs.cycleTs() > 0) prefs.setCycleKm(0f);
        // 下面这些显示缓存不一起清，屏幕上会留一会儿上一趟的数字
        HudState.avgKmh = Float.NaN;
        HudState.rideSec = 0L;
        HudState.whPerKm = Float.NaN;
        HudState.whThisCharge = Float.NaN;
        HudState.maxKmh = Float.NaN;
        lastEstKm = -1f;
        RawLog.sys(now, "trip reset by user (service): counters cleared");
        HudState.touch();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("stop".equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("restart".equals(action)) {
            // 干净重来：先彻底停掉当前链路，再按最新设置重建（演示开关切换走这条路）
            restartEverything();
            return START_STICKY;
        }
        if ("reconnect".equals(action)) {
            if (link != null) link.reconnectNow();
            return START_STICKY;
        }
        if ("scan".equals(action)) {
            // 设置页点了"扫描"：只列设备，不自动连
            HudState.scanDevices.clear();
            HudState.scanning = true;
            HudState.touch();
            if (link != null) link.scanForList();
            return START_STICKY;
        }
        if ("connect".equals(action)) {
            String mac = intent.getStringExtra("mac");
            String name = intent.getStringExtra("name");
            HudState.scanning = false;
            if (link != null) link.connectTo(mac, name);
            return START_STICKY;
        }
        if (!running) {
            startEverything();
        }
        return START_STICKY;
    }

    /** 把当前链路全部拆掉，保证 restart 后不会有两套在跑。 */
    private void restartEverything() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (link != null) link.stop();
        stopGps();
        if (trip != null) trip.flush();
        startEverything();
    }

    /** 按当前设置启动链路。 */
    private void startEverything() {
        running = true;
        RawLog.sys(System.currentTimeMillis(), "service start");
        bootMs = System.currentTimeMillis();   // v1.0.46：冷启动 90 秒内不算"停稳"
        startGps();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (link != null) {
                    link.setTarget(prefs.mac(), prefs.devName());
                    link.start(prefs.mac(), prefs.devName());
                }
            }
        }, 300);
        h.postDelayed(tick, 1000);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // v1.0.43：用户从最近任务里划掉 App 时，别让采集跟着停 —— 里程断档就是这么来的。
        // 2 秒后用闹钟把服务拉起来（用 setAndAllowWhileIdle，Android 12+ 不需要精确闹钟权限）。
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            Intent restart = new Intent(this, BleService.class);
            restart.setAction("restart");
            android.app.PendingIntent pi = android.app.PendingIntent.getService(this, 7919, restart,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                            | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            if (am != null) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 2000L, pi);
            }
            if (trip != null) trip.flush();
            prefs.setLastRunMs(System.currentTimeMillis());
            RawLog.sys(System.currentTimeMillis(), "task removed: restart scheduled in 2s");
        } catch (Throwable t) {
            RawLog.sys(System.currentTimeMillis(), "task removed: schedule failed " + t);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (link != null) link.stop();
        stopGps();
        if (btWatcher != null) {
            try { unregisterReceiver(btWatcher); } catch (Exception ignored) { }
            btWatcher = null;
        }
        if (tripResetRx != null) {
            try { unregisterReceiver(tripResetRx); } catch (Exception ignored) { }
            tripResetRx = null;
        }
        if (trip != null) trip.flush();
        // v1.0.43：把"最后运行时刻"落下 —— 下次启动用它判断"离开期间丢了多久的里程"
        prefs.setLastRunMs(System.currentTimeMillis());
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
            ttsReady = false;
        }
        // v1.0.22：服务停止前写一条 SUM —— 切后台被系统回收时也能留下这趟的账
        writeSum(System.currentTimeMillis(), "stop");
        RawLog.sys(System.currentTimeMillis(), "service stop");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** 设置页改完映射后调用，让新参数立刻生效。 */
    public void rebuildDecoderHere() { rebuildDecoder(); }

    private void rebuildDecoder() {
        FieldMap v = new FieldMap(prefs.vOffset(), prefs.vLen(), prefs.vBig(), prefs.vScale(), prefs.vBias());
        FieldMap s = new FieldMap(prefs.sOffset(), prefs.sLen(), prefs.sBig(), prefs.sScale(), 0f);
        protocol = new YuNingProtocol(v, s);
        soc = new SocModel(prefs.vFull(), prefs.vEmpty());
        if (socTracker != null) socTracker.setDefaultRangeKm(prefs.estRangeKm());
        // v1.0.4：显示口径往"小程序同源"靠 —— 3 秒窗 / 0.9 分位。
        // 骑手是拿手机小程序当基准的（同一帧字段、同一时刻），小程序显示的是最新一帧；
        // 5 秒 / 0.75 分位会把带载压降算进去，读出来比小程序低 1–3V，看着就"不准"。
        //
        // v1.0.37 修订：0.9 分位的前提是"这个字段只会偏低（带载压降）"，但实测日志里
        // 同一分钟 rv 在 71.1–74.9 之间走、vx=78，**向上也有尖峰**（连 80.2V 都上过屏）。
        // 取 0.9 分位等于专挑尖峰：vm 被推到 78.8V → 查表 100%，
        // 而电压明明只有 73V —— "满电却只剩 30km 续航"就是这么来的。
        // 全部改成中位数（0.5）：上下尖峰都免疫，代价是比"最轻载那一帧"低 0.5–1V。
        if (voltFilter == null) voltFilter = new VoltFilter(3000L, 48, 0.5);
        if (restFilter == null) restFilter = new VoltFilter(45000L, 160, 0.5);
        // 逐帧 OCV 候选：窗口 45 秒、中位数
        if (ocvCand == null) ocvCand = new VoltFilter(45000L, 160, 0.5);
        HudState.vEmpty = prefs.vEmpty();
        if (!v.getEnabled()) {
            voltFilter.reset();
            if (restFilter != null) restFilter.reset();
        }
        // 用的是"候选映射"就如实标出来：和 71.4V 对过一次，但没同刻验证
        HudState.mapCandidate = (prefs.vOffset() == 2 && prefs.vLen() == 2 && !prefs.vBig()
                && Math.abs(prefs.vScale() - 0.05f) < 1e-6f && Math.abs(prefs.vBias()) < 1e-6f);
        // 换了映射就重新判，别把上一组的结论留着
        HudState.mapSuspect = false;
        lastRawForCheck = null;
        frozenFrames = 0;
        Log.i(TAG, "protocol rebuilt: V[" + v + "] S[" + s + "] candidate=" + HudState.mapCandidate);
    }

    // ---------------- foreground ----------------

    private void startForegroundSafe() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "蓝牙连接", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
                // 焦点通知通道：无声音、无震动、不亮屏，但重要性够（LOW 通道系统不会当焦点通知）
                NotificationChannel fc = new NotificationChannel(CH_FOCUS, "仪表状态", NotificationManager.IMPORTANCE_DEFAULT);
                fc.setShowBadge(false);
                fc.setSound(null, null);
                fc.enableVibration(false);
                fc.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(fc);
                // 防盗报警通道：这条要能响、能震、能浮在锁屏上，所以 HIGH
                NotificationChannel ac = new NotificationChannel(CH_ALARM, "车辆异动报警",
                        NotificationManager.IMPORTANCE_HIGH);
                ac.setShowBadge(true);
                ac.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                ac.enableVibration(true);
                nm.createNotificationChannel(ac);
            }
            RawLog.sys(System.currentTimeMillis(), "focus island: " + FocusIsland.describe(this));
            Notification n = buildNotification("正在连接控制器…");
            // v1.0.51：**必须带 location 类型**（见 AndroidManifest 里那段注释）。
            // Android 14 起，只声明 connectedDevice 的前台服务退到后台后取定位会被拒，
            // 屏幕上就是"切到高德以后本次里程不涨、时速卡住"。
            // 没有定位权限时不要硬塞 location 类型 —— 那会让 startForeground 直接抛 SecurityException。
            boolean hasLoc = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
                    || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION);
            int fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (hasLoc) fgsType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                    "fgs type=%d (connectedDevice%s) locPerm=%s bgLocPerm=%s",
                    fgsType, hasLoc ? "|location" : "", hasLoc,
                    hasPerm("android.permission.ACCESS_BACKGROUND_LOCATION")));
            // v1.0.52：同一份状态也摆到界面上（设置页「运行状态」），用户截图即可，不必翻日志
            HudState.fgsText = hasLoc ? "连接设备 + 定位" : "仅连接设备（没定位权限）";
            HudState.locOk = hasLoc;
            HudState.bgLocOk = hasPerm("android.permission.ACCESS_BACKGROUND_LOCATION");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIF_ID, n, fgsType);
                } catch (Exception e) {
                    Log.w(TAG, "startForeground typed failed, fallback", e);
                    RawLog.sys(System.currentTimeMillis(),
                            "fgs typed failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
                    try {
                        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                    } catch (Exception e2) {
                        startForeground(NOTIF_ID, n);
                    }
                }
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CH_FOCUS);
        } else {
            b = new Notification.Builder(this);
        }
        // 头部就是"电量"，正文是电压/里程/续航：锁屏、通知栏、岛上都读这一条
        String head = Float.isNaN(HudState.soc)
                ? "智能HUD"
                : String.format(Locale.US, "电量 %.0f%%", HudState.soc);
        b.setContentTitle(head)
                .setContentText(text)
                .setSmallIcon(com.hud.delivery.R.drawable.ic_stat_hud)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openIntent());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setSound(null).setVibrate(null).setDefaults(0);   // 静默：通道重要级别够、但不响不亮
        }
        Notification n = b.build();
        // 挂上小米焦点通知/超级岛参数（没权限时系统自动当普通通知显示，不会报错）
        String ticker;
        if (Float.isNaN(HudState.voltage)) {
            ticker = head;
        } else if (Float.isNaN(HudState.soc)) {
            ticker = String.format(Locale.US, "%.1fV", HudState.voltage);
        } else {
            ticker = String.format(Locale.US, "%.1fV · %.0f%%", HudState.voltage, HudState.soc);
        }
        FocusIsland.attach(this, n, head, text, ticker);
        return n;
    }

    private PendingIntent openIntent() {
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, it, flags);
    }

    private String lastNotifText = "";
    private long lastNotifLogMs = 0L;

    private void updateNotification(String text) {
        // 实测踩坑：状态文案在扫描/重连时被反复推送，23 分钟往 CSV 里写了 17 万行 SYS，
        // 文件 9MB、还把真正有用的帧淹了。所以同一句话：
        //   - 通知栏最多 2 秒刷一次（系统调用也是有成本的）
        //   - CSV 只记状态变化，5 秒一条封顶
        long now = System.currentTimeMillis();
        boolean changed = !text.equals(lastNotifText);
        if (!changed && now - lastNotifLogMs < 2000L) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
        if (changed || now - lastNotifLogMs > 5000L) {
            lastNotifText = text;
            lastNotifLogMs = now;
            RawLog.sys(now, "conn: " + text);
        } else {
            lastNotifLogMs = now;
        }
    }

    /** 只刷通知栏内容，不写 SYS 日志（每 5 秒一次，别把 CSV 刷爆）。 */
    /**
     * 满电锚点：车停着、电压已经到满电附近、而且离上次锚点超过 6 小时
     * → 认为刚充完电，从这一刻开始"按里程数电"。
     * 条件卡得紧一点：骑在半路电压也可能冲到 76V 以上，那种不能算充满。
     */
    /**
     * 低电提醒：电量 ≤20% 或电压离欠压只差 3V 时就震一下。
     * 骑车时不可能一直盯着屏幕，震一下比屏幕上变个颜色有效得多；
     * 5 分钟最多一次，避免把骑手震烦（也在日志里留痕，事后能核对触发时刻）。
     */
    private long lastLowAlertMs = 0L;

    /** v1.0.46：服务启动时刻（"刚开机就算停稳"的冷却用） */
    private long bootMs = 0L;
    /** v1.0.46：开机满电只处理一次 —— 把上次残留的本次里程折走 */
    private boolean bootFullPending = true;
    /** v1.0.67：开机高电压串（连续 ≥6 帧、持续 ≥8s 才算真满电，挡启动尖峰） */
    private long bootHighMs = 0L;
    private int bootHighFrames = 0;
    /** v1.0.46：断档提示的出现时刻（挂 15 分钟后自动撤掉） */
    private long gapWarnMs = 0L;
    /**
     * v1.0.69：链路刚恢复的时刻。
     *
     * 断连几分钟以上再连上时，控制器给出的第一段静置电压可能是"刚拔掉充电器的表面电荷"
     * （11-26 实测 77.3–78.0V 持续十余分钟，真静置只有 74.8V）。这段时间里绝不能拿它
     * 往上锚定电量 —— 一抬就是十几个点，用户就要按虚高的续航出门。
     */
    private long linkBackMs = 0L;

    private void maybeLowPowerAlert(long now, float v, float socVal, int src, float ocv, boolean atRest) {
        // 只有"电量真的算出来"才提醒
        if (Float.isNaN(socVal)) return;
        if (src == HudState.SRC_NONE && !HudState.socFromKm) return;
        // v1.0.46：**只在真的连着控制器时震**。
        // 断开后电量是冻结值（很可能是昨晚的 2%），再拿它震一次毫无意义 ——
        // 手机揣兜里走路也会被震，还以为是车出事了。
        if (src != HudState.SRC_BLE) return;
        // v1.0.62：**静置开路电压 ≥70V 就不算低电**。
        // 11-24 账本被续航硬顶压到 4%，车停着 OCV 72.2V，仍按 4% 误报了 5 次"电量不足"。
        // 闸门只在静置口径下生效；骑行中的带载压降仍走下面的 vLowLine 判断，不误放深亏电。
        if (atRest && !Float.isNaN(ocv) && ocv >= 70f) {
            RawLog.sys(now, String.format(Locale.US,
                    "low power skip: rest ocv=%.1fV >=70, soc=%.0f", ocv, socVal));
            return;
        }
        // v1.0.53：vEmpty 现在表示**静置**耗尽电压（63.8），而这里拿到的 v 是带载端电压。
        // 直接 +3 会变成 66.8V 就震 —— 那时电池还有 15~20%，一路被震到真没电。
        // 按"带载起点 = vEmpty − 4"来判（63.8 → 59.8，+3 = 62.8）。
        float vLowLine = prefs.vEmpty() - 4f + 3f;
        boolean low = socVal <= 20f || (!Float.isNaN(v) && v < vLowLine);
        if (!low) return;
        // 电压低但电量账本说还有电（刚停下车还在 66V？那是噪声），以电量为主
        if (!Float.isNaN(socVal) && socVal > 30f && !Float.isNaN(v) && v < vLowLine) {
            RawLog.sys(now, String.format(Locale.US,
                    "low voltage but soc=%.0f%%, skip alert (noise guard)", socVal));
            return;
        }
        if (now - lastLowAlertMs < 5 * 60_000L) return;
        lastLowAlertMs = now;
        try {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb == null || !vb.hasVibrator()) return;
            long[] pattern = {0L, 300L, 200L, 300L, 200L, 600L};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vb.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vb.vibrate(pattern, -1);
            }
            RawLog.sys(now, String.format(Locale.US, "low power alert: soc=%.0f v=%s", socVal,
                    Float.isNaN(v) ? "-" : String.format(Locale.US, "%.1f", v)));
            speak(String.format(Locale.US, "电量不足，还剩百分之%.0f，请尽快充电", socVal));
        } catch (Exception e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    private void maybeAnchorFull(long now, float v) {
        if (Float.isNaN(v)) return;
        // v1.0.53：**"满了"必须是真满了**。
        // 11-19 实测：骑到中段静置 76~77V，旧阈值（vFull−0.4=76.1）就判"充满"，
        // 一晚误判两次 —— 自动清本次里程 12km、电量 76→100→73 来回抽。
        // 现在三条硬门槛同时成立才算：
        //   ① 电压 ≥ vFull−0.3（新 vFull=78.6 → 78.3，中段 76~77 再也进不来）
        //   ② 已经真正停稳 ≥8 分钟（不是"这一秒速度 0"）
        //   ③ 距上次判满 ≥30km 或 ≥6 小时（骑两公里重启 App 不会再清一次）
        long ts = prefs.cycleTs();
        boolean farEnough = ts <= 0L || (now - ts >= 6L * 3600_000L);
        if (!farEnough) {
            float ranSince = Math.max(0f, HudState.odoKm - prefs.chargeOdoKm());
            farEnough = ranSince >= 30f;
            if (!farEnough) return;
        }
        if (v < prefs.vFull() - 0.3f) return;
        float speed = Float.isNaN(HudState.speedKmh) ? HudState.gpsKmh : HudState.speedKmh;
        boolean quiet;
        if (Float.isNaN(speed)) {
            quiet = kmGrowingTs > 0L && now - kmGrowingTs > 480_000L && now - bootMs > 480_000L;
        } else {
            quiet = speed <= 2f;
        }
        if (!quiet) return;
        // ② 停稳要"持续"：只有这一秒速度 0 不算，看真正静止了多久
        if (stationarySince <= 0L || now - stationarySince < 480_000L) return;
        prefs.setCycleSoc(100f);
        // v1.0.47：这里原来是 setCycleKm(tripKm)，紧接着 resetTripFoldingOdo 又把本次清零 ——
        // 于是"本箱电已跑"的起点记成了一个已经不存在的里程。改成 0：满电就是从 0 起算。
        prefs.setCycleKm(0f);
        prefs.setCycleTs(now);
        // v1.0.45：充满就把"本次里程"归零，重新计一趟（用户要求）。
        resetTripFoldingOdo(now, "full charge");
        applyFullAnchor(now, v);
    }

    /**
     * v1.0.45：把「本次里程」清零，但**先折进 App 累计**，总里程一个数都不少。
     * 总里程 = 基准 + App 累计 + 本次，直接清零会让屏幕上的 11303.0 掉成 11296.5。
     *
     * v1.0.47：顺手把"本次骑行"的统计一起归零。
     * 之前只有长按清零会清 stats/energy，"充满自动清"只清了里程 ——
     * 于是出现过"本次 0.0km，旁边均速 21、本箱电已跑 6.5km"的自我矛盾。
     * 清零前先把这一趟写进 SUM 日志，记录不会丢。
     */
    private void resetTripFoldingOdo(long now, String why) {
        // v1.0.49：取 HudState 和内存积分器里更大的那个 —— 界面清零时会把 HudState 先置 0，
        // 只读 HudState 就会漏掉还没折叠的那一段（"清零后总里程少了一截"）。
        float tripBefore = HudState.tripKm;
        if (trip != null) {
            float t = trip.km();
            if (!Float.isNaN(t) && (Float.isNaN(tripBefore) || t > tripBefore)) tripBefore = t;
        }
        // 一公里都没跑（比如刚开机就满电）就不必写 SUM，免得日志里全是 0 的流水账
        if (!Float.isNaN(tripBefore) && tripBefore > 0.05f) writeSum(now, why);
        if (!Float.isNaN(tripBefore) && tripBefore > 0.05f) {
            float acc = prefs.odoAccumKm() + tripBefore;
            prefs.setOdoAccumKm(acc);
            RawLog.sys(now, String.format(Locale.US,
                    "%s: trip reset (%.2f km folded into odo accum %.2f)", why, tripBefore, acc));
        }
        if (trip != null) {
            trip.reset(now);                 // 内存归零 + 落盘 + 新的 tripStart
        } else {
            prefs.setTripKm(0f);
            prefs.setTripStart(now);
        }
        odoSpeedFill = false;                // v1.0.57：清零后不要拿旧盲区状态去吞第一段位移
        odoSpdSinceMs = 0L;
        clearTripState(now);
    }

    /** 只清「本次」相关的显示与统计（不碰 odometer 累计），折叠逻辑在 [resetTripFoldingOdo] */
    private void clearTripState(long now) {
        HudState.tripKm = 0f;
        // v1.0.49：**锚点也要跟着落地**。
        // 旧版只把 trip 归零，锚点还停在 26.9km（清零前的位置）→ `predict(0)` 变成
        // 锚点电量 + 1.25%/km × 26.9 ≈ +34 点，电量瞬间被推到 100%，
        // 续航也跟着翻倍 —— 用户看到的"清零后电量乱跳"就是这条。
        if (socTracker != null) socTracker.rebaseIfBehind(0f);
        HudState.gapWarn = "";      // 都从 0 重新计了，别再挂着"离开期间未记录"
        // 码表统计 / 能耗窗口 / 播报桶一起归零，否则 0.0km 旁边还挂着上一趟的均速和最高速
        lastSummaryKmF = -1f;
        lastAnnounceBucket = 0;
        stats.reset();
        energy.clear();
        prefs.setStatsMax(Float.NaN);
        prefs.setStatsMs(0L);
        prefs.setLastTripSegKm(0f);
        HudState.maxKmh = Float.NaN;
        lastEstKm = -1f;      // 续航估计的缓存也一起作废，否则第一秒还会显示上一趟的估算
        HudState.touch();
    }

    /**
     * v1.0.46：开机那一下如果就是满电，先把上次残留的「本次里程」折走再开始跑。
     *
     * 为什么单独一条路径：运行时那条要求"真的停稳"，而刚开机时 GPS 可能还没出点、
     * 里程也没开始长，既判不了"在骑"，又确实是"充满了出来"。这时只要电压够高、60 秒内的第一帧，
     * 就认定是新的一箱电 —— 此刻折走里程不会误伤任何正在跑的公里。
     */
    private void maybeBootFullReset(long now, boolean live, float v) {
        if (!bootFullPending) return;
        if (now - bootMs > 60_000L) {
            bootFullPending = false;
            return;
        }
        if (!live || Float.isNaN(v)) return;
        // v1.0.67：满电门槛从 vFull−0.4 收紧到 vFull−0.2，并且必须"持续"。
        // 11-24 半电出门：启动瞬时报过 78.46V 一两个尖峰，旧门槛（78.2）当场误判满电，
        // 里程被折走清零、电量锚点焊成 100%；静置后真实 OCV 只有 75.3V（≈85%）。
        // 现在连续 ≥6 帧、持续 ≥8 秒都在门槛上方才认；中间掉一次就重新数。
        if (v < prefs.vFull() - 0.2f) {
            bootHighMs = 0L;
            bootHighFrames = 0;
            return;
        }
        if (bootHighMs == 0L) bootHighMs = now;
        bootHighFrames++;
        if (bootHighFrames < 6 || now - bootHighMs < 8_000L) return;
        // v1.0.50：**这一箱电刚起算就别再清一次。**
        // 旧版这条开机路径没有 30 分钟去重：充满电出门跑了两公里、顺手退出 App 再打开，
        // 静置电压还在 76V 上方 → 又触发一次"满电清零"，把刚跑的两公里折走、本次里程归零，
        // 用户看到的就是"本次里程自己变 0 了"。跟运行时那条一样加去重窗口。
        long cts = prefs.cycleTs();
        if (cts > 0 && now - cts < 30L * 60_000L) {
            bootFullPending = false;
            return;
        }
        bootFullPending = false;
        resetTripFoldingOdo(now, "boot full charge");
        applyFullAnchor(now, v);
    }

    /** 满电锚点：电量/续航/本箱电都从这里重新起算（原 maybeAnchorFull 的后半段） */
    private void applyFullAnchor(long now, float v) {
        // v1.0.56：真充满 → 解除"已没电"锁定（下一箱电从 100% 重新算）
        if (HudState.emptyLatch) {
            HudState.emptyLatch = false;
            RawLog.sys(now, "EMPTY LATCH cleared (full charge)");
        }
        // v1.0.7：本箱电单独记一本账，只在这里（真正充满）建立，长按清零不会把它抹掉。
        // 坐标用整车累计（odoKm），本次里程清零后这本账依然连续。
        prefs.setChargeAnchor(100f, HudState.odoKm);
        // 满电也是电量锚点：明天早上满电出门，不该还显示昨天的 40%
        if (socTracker != null) {
            socTracker.anchorRest(now, 100f, HudState.tripKm);
            socTracker.resetNudgeBudget();      // v1.0.3：新的一箱电，纠偏预算归零
            socTracker.resetRecoverBudget();    // v1.0.20：回升预算同样归零
            socTracker.clearRejects();          // v1.0.67：离群计数也随新箱电清零
            // v1.0.18：满电时把学到的耗电率往默认值回摆 35%（不重学、不清零）。
            // v1.0.19：只在"学到 ≥2.4%/km（折算满电 <42km）"时才回摆 —— 正常 80km 的车（1.25%/km）
            // 不该被满电动作无谓地拽一次。这样它只处理"明显跑不到"的样本，不碰正常车。
            float learnedPerKm = socTracker.getPerKmPct();
            if (!Float.isNaN(learnedPerKm) && learnedPerKm > 2.4f) {
                float relaxed = socTracker.relaxPerKm(0.35f);
                if (!Float.isNaN(relaxed)) {
                    prefs.setPerKmPct(relaxed);
                    RawLog.sys(now, String.format(Locale.US,
                            "full anchor: per_km relaxed %.2f -> %.2f (was implying <42km)", 
                            learnedPerKm, relaxed));
                }
            }
            prefs.setSocAnchorSoc(100f);
            prefs.setSocAnchorKm(HudState.tripKm);
            prefs.setSocAnchorTs(now);
        }
        RawLog.sys(now, String.format(Locale.US, "full anchor @ %.1fV / %.1fkm", v, HudState.tripKm));
    }

    private void updateNotificationNumbers() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(statusLine()));
        } catch (Exception ignored) {
        }
    }

    /** 通知栏那句人话：状态 · 电压 · 电量 · 里程 · 时速 · 还能跑（锁屏下拉/岛上就能看）。 */
    private String statusLine() {
        String v = Float.isNaN(HudState.voltage) ? "--" : String.format(Locale.US, "%.1fV", HudState.voltage);
        String s = Float.isNaN(HudState.soc) ? "--" : String.format(Locale.US, "%.0f%%", HudState.soc);
        String km = String.format(Locale.US, "%.1fkm", HudState.tripKm);
        float spd = Float.isNaN(HudState.speedKmh) ? HudState.gpsKmh : HudState.speedKmh;
        String sp = Float.isNaN(spd) ? "" : " · " + String.format(Locale.US, "%.0fkm/h", spd);
        String rg = Float.isNaN(HudState.rangeKm) ? "" : " · 还能" + String.format(Locale.US, "%.0fkm", HudState.rangeKm);
        // v1.0.3：断线/重连时通知栏必须说实话，否则锁屏上看到的还是"上次的漂亮数字"
        String st = HudState.connState == HudState.CONNECTED ? "" : "重连中 · ";
        return st + v + " · " + s + " · " + km + sp + rg;
    }

    // ---------------- HudBle.Listener ----------------

    @Override
    public void onState(HudBle.State state, String text) {
        int st;
        switch (state) {
            case CONNECTED:  st = HudState.CONNECTED;  break;
            case CONNECTING: st = HudState.CONNECTING; break;
            case SCANNING:   st = HudState.SCANNING;   break;
            default:         st = HudState.IDLE;       break;
        }
        HudState.setConn(st, text);
        // v1.0.7：记"上一次真正连上"的时刻。报警武装要用它做闸门 ——
        // 只有"半小时内还见过控制器"的停车，才算"车就在身边、刚停好"，
        // 否则手机放在家里/揣在身上去坐车，都会被当成"车被骑走"。
        if (st == HudState.CONNECTED) {
            lastConnOkTs = System.currentTimeMillis();
            hardResetBackoffMs = 6L * 60_000L;   // v1.0.54：连上就复位重建退避
        }
        updateNotification(text);
    }

    @Override
    public void onNotify(String tag, byte[] value) {
        if (value == null) return;
        long now = System.currentTimeMillis();
        // v1.0.40：帧回调也兜底。这里跑在 BLE 回调线程，抛异常同样会带走整个进程；
        // 而且它是每帧都跑的高频路径，一个越界就是"连上后几秒就闪退"。
        try {
            handleFrame(tag, value, now);
        } catch (Throwable t) {
            String key = "frame " + t.getClass().getSimpleName();
            long cnt = tickErrCount.getOrDefault(key, 0L) + 1;
            tickErrCount.put(key, cnt);
            if (cnt <= 3) RawLog.sys(now, "frame-ex #" + cnt + " " + stackOf(t));
        }
    }

    private void handleFrame(String tag, byte[] value, long now) {
        RawLog.frame(now, tag, value);

        YuNingFrame f = protocol.decode(value);
        Reading r = new Reading(now, f.getRaw(), f.getVoltage(), f.getSpeed(), Reading.NA, f.getNote());

        // 67B 帧除了电压还带着故障位（转把/刹车/霍尔/欠压/P档），顺手搬到界面上：
        // "P档"这类不是故障而是状态，骑车时看到它就知道为什么拧油门不走
        HudState.faultsText = f.faultText();
        HudState.throttleV = f.getThrottle();

        if (r.hasVoltage()) {
            // 数据流断过（重连/超时）就重新开始记"本次上电见过的最高电压"
            if (lastLiveVMs == 0L) bootMaxV = Float.NaN;
            liveV = voltFilter.add(now, r.voltage);
            lastRawV = r.voltage;   // v1.0.32：留一份未滤波的原值，日志里可逐帧复算
            if (restFilter != null) restFilter.add(now, r.voltage);   // 45s/0.9 分位：停车校准用
            // v1.0.30：**在这一帧就把"这一帧的压降"补回去**，得到这一帧的开路电压候选值。
            // 为什么不等到 tick 再"用最新电压 + 最新压降"：两者不是同一时刻的，会互相错配 ——
            // 3 秒分位取的是"最轻载的那一帧"，再叠上"此刻满油门"的压降，等于把电池往高了估。
            // 逐帧配对之后取 45 秒 90 分位，得到的就是"这辆车这段路里最接近开路的那一档电压"。
            if (ocvCand != null) {
                // v1.0.37：压降补偿封顶 2.5V（实测标定值最高 6.2V 是"深踩到底"那一瞬，
                // 拿它去补 45 秒窗的平均值会把电量整体抬高两格）。封顶后仍在补偿，但不再过冲。
                float sagFrame = loadComp.sagOf(HudState.gpsKmh, f.getThrottle());
                if (!Float.isNaN(sagFrame)) sagFrame = Math.min(2.5f, sagFrame);
                ocvCand.add(now, r.voltage + (Float.isNaN(sagFrame) ? 0f : sagFrame));
            }
            if (Float.isNaN(bootMaxV) || liveV > bootMaxV) bootMaxV = liveV;
            lastLiveVMs = now;
            // v1.0.38：**手动电压锚点一旦有了真帧就退役**。
            // 这是协议没解出来之前的顶班手段（手输一个电压，再按里程往下推）。
            // 但如果一直留着，断开控制器时它就会接管：用几天前手输的 71.3V 按里程推，
            // 电量显示成"很早之前"的数（日志里 `v=71.3 src=3 soc=38` 就是它）。
            // 现在只要收到第一帧带电压的数据，就把它从内存和存储里一起清掉。
            // 现在只要收到真帧、且这个旧锚点是 6 小时以前留下的，就把它从内存和存储里一起清掉。
            // （6 小时内不清：万一你刚手动标过一次、还在同一趟里，别让输入当场消失。）
            if (!Float.isNaN(HudState.manualV)
                    && (HudState.manualTs == 0L || now - HudState.manualTs > 6L * 3600_000L)) {
                RawLog.sys(now, String.format(Locale.US,
                        "manual anchor retired (%.1fV) : live voltage now available", HudState.manualV));
                HudState.manualV = Float.NaN;
                HudState.manualTs = 0L;
                prefs.setManualV(Float.NaN);
                prefs.setManualTs(0L);
            }
            HudState.lastGoodV = liveV;
            HudState.lastGoodVTs = now;
            // "帧变了电压却没变 → 映射可能不对"这个防呆只对手工映射有意义：
            // 67B 那套公式已经在 3504 帧上验过，怠速/P档时电压本来就可以一秒不动，
            // 再跑这个判定会误报，所以实测通道不参与。
            if (!f.getNote().contains("实测电压")) checkMappingStillSame(r, now);
        }

        // 频率统计（5 秒窗口）
        if (rateStartMs == 0L) { rateStartMs = now; rateCount = 0; }
        rateCount++;
        long el = now - rateStartMs;
        float rate = el >= 1000 ? (rateCount * 1000f / el) : HudState.rateHz;
        if (el >= 5000) { rateStartMs = now; rateCount = 0; }

        HudState.frames++;
        HudState.lastFrameTs = now;
        HudState.onFrame(r, tag, rate);

        if (HudState.connState != HudState.CONNECTED) {
            HudState.setConn(HudState.CONNECTED, "已连接 · " + tag);
            updateNotification(String.format(Locale.US, "已连接 · %s · %.1fHz", tag, rate));
        }
    }

    private byte[] lastRawForCheck;
    private float lastDecodedV = Float.NaN;
    private int frozenFrames;

    /**
     * 防呆：如果"帧在变、我们解出的电压却一位不动"，那这个映射八成不是电压字段。
     *
     * v1.0.9 修了两个真问题：
     *  1. **只在骑行时判定**。以前停车等红灯，电压本来就不会变（0.01V 一档，静置几分钟都不翻位），
     *     5 帧（2.5 秒）就点亮"映射可能不对" —— 每天必然误报。
     *  2. **能自动撤掉**。以前置位之后只有改设置/重启服务才清（`HudState.mapSuspect=false` 只在
     *     rebuildDecoder 里），于是一条假警告会一直挂在屏幕底部。现在电压真的动了就撤回，
     *     并且要连续 3 帧都动到 0.15V 以上才撤，避免一帧抖动就来回闪。
     */
    private void checkMappingStillSame(Reading r, long now) {
        if (r.raw == null || r.raw.length == 0) return;
        if (lastRawForCheck == null) {
            lastRawForCheck = r.raw.clone();
            lastDecodedV = r.voltage;
            frozenFrames = 0;
            return;
        }
        if (java.util.Arrays.equals(lastRawForCheck, r.raw)) return;
        lastRawForCheck = r.raw.clone();

        boolean sameV = !Float.isNaN(lastDecodedV) && Math.abs(r.voltage - lastDecodedV) < 0.001f;
        lastDecodedV = r.voltage;

        // 电压在正常范围内活动 → 映射是好的，把之前的怀疑撤掉
        if (!sameV) {
            frozenFrames = 0;
            if (HudState.mapSuspect) {
                HudState.mapSuspect = false;
                HudState.touch();
                RawLog.sys(now, "map suspect cleared (voltage moved to " + r.voltage + ")");
            }
            return;
        }

        // 只有"车真的在跑"才怀疑映射：骑行中电压一定会随负载动
        boolean riding = (kmGrowingTs > 0L && now - kmGrowingTs < 5000L)
                || (!Float.isNaN(HudState.gpsKmh) && HudState.gpsKmh > 5f);
        if (!riding) { frozenFrames = 0; return; }

        frozenFrames++;
        if (frozenFrames >= 10 && !HudState.mapSuspect) {   // 5 秒（2Hz × 10）
            HudState.mapSuspect = true;
            HudState.touch();
            RawLog.sys(now, "map suspect: frame changed but voltage frozen at " + r.voltage);
        }
    }

    @Override
    public void onGatt(String what, String detail) {
        RawLog.gatt(System.currentTimeMillis(), what, detail);
        // 链路优先级是"申请了才算生效"，申请失败也要让界面说实话
        if ("PRIORITY".equals(what)) {
            HudState.priorityHigh = true;
            HudState.touch();
        } else if ("PRIORITY-FAIL".equals(what)) {
            HudState.priorityHigh = false;
            HudState.touch();
        }
    }

    @Override
    public void onScanDevice(String mac, String name, int rssi) {
        long now = System.currentTimeMillis();
        // 设备列表（设置页点选用）：覆盖写，保留最近一次的名字与信号强度
        HudState.scanDevices.put(mac, (name == null ? "" : name) + "|" + rssi + "|" + now);
        if (HudState.scanDevices.size() > 60) HudState.scanDevices.clear();
        HudState.touch();

        Long last = SCAN_SEEN.get(mac);
        if (last != null && now - last < 30000L) return;
        SCAN_SEEN.put(mac, now);
        if (SCAN_SEEN.size() > 200) SCAN_SEEN.clear();
        RawLog.dev(now, mac, (name == null ? "" : name) + " rssi=" + rssi);
    }

    @Override
    public void onDevicePicked(String mac, String name) {
        try {
            if (!mac.isEmpty()) prefs.setMac(mac);
            if (name != null && !name.isEmpty() && prefs.devName().isEmpty()) prefs.setDevName(name);
            HudState.devLabel = prefs.devName().isEmpty() ? prefs.mac() : prefs.devName();
            RawLog.sys(System.currentTimeMillis(), "picked " + mac + " " + name);
        } catch (Exception ignored) {
        }
    }

    // ---------------- timer: 里程积分 ----------------

    private String lastCfg = "";

    /** v1.0.40：tick 兜底用 —— 同类异常计数 + 上次落盘时刻（防日志被刷爆） */
    private final java.util.Map<String, Long> tickErrCount = new java.util.HashMap<>();
    private long tickErrLastMs = 0L;

    /** 堆栈转成 1200 字以内的字符串（日志一行放不下整段） */
    static String stackOf(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String s = sw.toString().replace("\r", "");
            return s.length() > 1200 ? s.substring(0, 1200) : s;
        } catch (Throwable ignored) {
            return String.valueOf(t);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            // v1.0.40：整个 tick 包一层兜底。这里跑在主线程，任何一处没预料到的异常
            // 都会直接把 App 崩掉（表现出来就是"固定几分钟后闪退"）。
            // 兜底不是掩盖问题：异常照样写进 CSV（kind=SYS，前缀 tick-ex），
            // 但我们不陪葬 —— 下一秒继续跑，仪表不黑屏。
            try {
            // 定位是"骑手后补权限"的场景：装上 App 先点拒绝，后来在系统里打开了。
            // 每秒自检一次（内部有 guard，已经在跑就直接返回），不必重启 App。
            if (locCb == null && hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startGps();
            }

            // 设置页改了字段映射 → 1 秒内自动生效，不用重连、不用重装
            String cfg = prefs.vOffset() + "/" + prefs.vLen() + "/" + prefs.vBig() + "/"
                    + prefs.vScale() + "/" + prefs.vBias() + "/"
                    + prefs.sOffset() + "/" + prefs.sLen() + "/" + prefs.sBig() + "/"
                    + prefs.sScale() + "/" + prefs.vFull() + "/" + prefs.vEmpty() + "/"
                    // v1.0.9：满电续航也要进签名。它决定"没有学习值时的 %/km 初值"，
                    // 而 socTracker.defaultRangeKm 只在 rebuildDecoder 里同步 ——
                    // 以前改了续航，要等下次重启服务才生效，那之前的电量推算仍按旧值走。
                    + prefs.estRangeKm();
            if (!cfg.equals(lastCfg)) {
                lastCfg = cfg;
                rebuildDecoder();
            }

            // --- v1.0.12：GPS 断流时让时速失效 ---
            // 旧行为：`HudState.gpsKmh` 只在收到定位时才更新，一旦 GPS 断了（隧道、地下车库、
            // 商场里、或者被系统掐了定位），屏幕上会一直挂着**几分钟前的 32 km/h**，
            // 骑手看到的是一个"车还在跑"的假象 —— 这比显示 "--" 危险得多。
            // SpeedStabilizer 自己也只保持 4 秒，所以这里只需要"整条失效"。
            if (lastGpsFixWall > 0L && now - lastGpsFixWall > 8000L && !Float.isNaN(HudState.gpsKmh)) {
                speedStab.reset();
                HudState.gpsKmh = Float.NaN;
                HudState.speedFromGps = false;
                RawLog.sys(now, "gps speed stale > 8s, dropped (last fix "
                        + ((now - lastGpsFixWall) / 1000L) + "s ago)");
            }
            // v1.0.52：设置页「运行状态」那行"定位点"用这个 —— 用户截图就能看出"系统还在不在给点"
            HudState.gpsFixAgeS = lastGpsFixWall > 0L ? (now - lastGpsFixWall) / 1000L : -1L;

            // 电压过期：实测里 FFF6 那一路会中途停推，界面上还挂着 10 分钟前的旧电压 ——
            // 那比显示 --.- 更危险，所以超过 8 秒没新值就当没有
            if (!Float.isNaN(liveV) && lastLiveVMs > 0 && now - lastLiveVMs > 8000L) {
                liveV = Float.NaN;
                lastLiveVMs = 0L;          // v1.0.1：清掉"最后收帧时刻"，下次上电才能重置峰值
                // v1.0.38：把"这一刻冻结的电量"写进日志，方便事后核对"断开前后是不是同一个数"
                RawLog.sys(now, String.format(Locale.US,
                        "link lost: freeze soc=%s (last live)", Float.isNaN(lastLiveSoc) ? "--" : String.format(Locale.US, "%.0f", lastLiveSoc)));

                bootMaxV = Float.NaN;
                if (voltFilter != null) voltFilter.reset();
                // v1.0.57：断链前先把"最后一段静置均值"存下来 —— 熄火停车后的校准要用它
                if (restFilter != null) {
                    float rv = restFilter.value(now);
                    if (!Float.isNaN(rv)) {
                        lastRestV = rv;
                        lastRestVMs = now;
                    }
                    restFilter.reset();
                }
                if (ocvCand != null) ocvCand.reset();   // v1.0.30：断链后别拿旧候选算电量
                // v1.0.46：**lastVMap 也必须清**。
                // v1.0.44 为了修 0↔2 抖动，让"候选样本不足时沿用上次 vMap"；
                // 但断链时只 reset 了 ocvCand，lastVMap 还留着断开前那个数。
                // 于是"昨晚 64.6V 没电 → 今早充满 79V 连上"的头几秒，查表用的还是 64.6V，
                // 电量先按"没电"算一遍，再被确认闸拦住/回跳。清掉它，重连后第一帧就按新帧算。
                lastVMap = Float.NaN;
                // 注意：lastLiveTickMs 不能清 —— 它是"断档多久"的坐标，清了就永远开不了直跳确认窗
                HudState.ocvV = Float.NaN;
                HudState.sagV = 0f;
                RawLog.sys(now, "live voltage stale > 8s, dropped");
            }

            // 假连接看门狗：状态是"已连接"却连续 20 秒一帧都没有（蓝牙常见：链路在、数据不来），
            // 主动重连一次，最多 30 秒一次，避免真的掉线还在傻等
            if (HudState.connState == HudState.CONNECTED
                    && HudState.lastFrameTs > 0
                    && now - HudState.lastFrameTs > 20_000L
                    && now - lastWatchdogMs > 30_000L) {
                lastWatchdogMs = now;
                RawLog.sys(now, "watchdog: connected but silent >20s, reconnect");
                if (link != null) link.reconnectNow();
            }

            // v1.0.53：**连不上就硬重置一次链路**。
            // 11-19 17:20–18:23 那一个多小时，日志里 35 次"控制器没应答连接请求"、
            // 34 次"连接失败(status=-5)"—— 控制器一直在广播、App 一直在扫，
            // 但每轮 connect 都卡在 GATT，说明这一侧的栈已经僵了，靠退避重试永远好不了。
            // v1.0.54 修正：那一段其实是**用户在家、车熄火**（他本人更正），控制器本来就不应答。
            // 所以不能每 10 分钟硬重建一次 —— 在家放一夜等于白拆十几次链路，费电又耗蓝牙。
            // 改成退避：6 分钟 → 12 → 24 → 30 分钟封顶，连上一次就重置回 6 分钟。
            if (running && HudState.connState != HudState.CONNECTED
                    && now - Math.max(lastConnOkTs, bootMs) > hardResetBackoffMs
                    && now - lastHardResetMs > hardResetBackoffMs) {
                lastHardResetMs = now;
                hardResetBackoffMs = Math.min(30L * 60_000L,
                        Math.max(6L * 60_000L, hardResetBackoffMs * 2L));
                RawLog.sys(now, "conn: 长时间没连上 → 重建链路 (engine="
                        + (prefs.nordicBle() ? "nordic" : "legacy")
                        + ", next backoff " + (hardResetBackoffMs / 60_000L) + "min)");
                try {
                    if (link != null) {
                        link.stop();
                        link = buildEngine();
                        link.setTarget(prefs.mac(), prefs.devName());
                        link.start(prefs.mac(), prefs.devName());
                    }
                } catch (Throwable t) {
                    RawLog.sys(now, "conn: rebuild failed " + stackOf(t));
                }
            }

            // 停车静置校准（v1.0.3 改成三段 + 里程闸门）：停稳 120 秒先校一次，之后每 3 分钟再校，
            // 一轮停车最多 3 次。铅酸静置越久越接近开路电压，第一校只能算"粗校"。
            // 闸门：最近 90 秒里程还在长 → 车根本没停，这时读到的电压是带载压降，不能当开路电压。
            if (parkedMode && stationarySince > 0L && now - stationarySince >= 120_000L
                    && now - kmGrowingTs >= 90_000L
                    && restCalCount < 3 && now - lastRestCalMs >= 180_000L) {
                lastRestCalMs = now;
                restCalCount++;
                RawLog.sys(now, "rest cal #" + restCalCount + " @"
                        + ((now - stationarySince) / 1000) + "s parked");
                calibrateSocAtRest(now);
            }

            // ---------- 里程积分（v1.0.53：位移优先、速度兜底，一条通道不重不漏）----------
            // 旧版是"速度有数就走速度积分、没数才走 GPS 位移"，两条路**互斥**，两个坑：
            //   ① 速度路径期间 lastGpsKm 不更新 → 一断速就把攒下来的位移一次性补进去（双计）；
            //   ② 反过来，位移明明在涨、时速被判 NaN 的那几秒全丢。
            // 11-19 两趟日志按对表算：App 一直少记 22~30%（86km 只记了 65.8km），就是这个结构。
            // 现在每秒只取一支：有位移就用位移，没位移才用时速×1 秒，绝不两条叠加。
            float gk = HudState.gpsKm;
            // 连上车时"推着走"也算里程（车轮真在转、仪表也记）；没连车才按 8km/h 挡散步。
            boolean linked = lastLiveVMs > 0L && now - lastLiveVMs < 8000L;
            float gate = linked ? 1.5f : 8f;
            float gateSpd = !Float.isNaN(HudState.gpsKmh) ? HudState.gpsKmh : HudState.gpsKmhRaw;
            boolean looksLikeRiding = Float.isNaN(gateSpd) || gateSpd >= gate;
            float posDelta = (!Float.isNaN(gk) && lastGpsKm >= 0f) ? gk - lastGpsKm : Float.NaN;
            boolean posUsed = false;
            // v1.0.57：**时速兜底原来是一段死代码。**
            //
            // 这一支问的是 `HudState.speedKmh`，而它只由 Reading（67B 帧）写入 ——
            // 协议里根本没有轮速，所以它恒为 NaN，于是"没位移就按速度积分"从来没执行过。
            // 11-21 的日志把后果摆出来了：`odo blind: spd=26km/h but no gps delta for 62s`，
            // 定位精度一超 50m（进商场、过桥洞），位移被丢掉，时速明明还准，里程却整段不涨。
            // 现在按"控制器速度 → 显示时速 → 原始滤波时速"三级回落；GPS 口径要 4km/h 起、且持续
            // 4 秒才算（停车漂移常见 1–3km/h，不能让它凭空生里程）。
            float spdOdo = !Float.isNaN(HudState.speedKmh) ? HudState.speedKmh
                    : (!Float.isNaN(HudState.gpsKmh) ? HudState.gpsKmh : HudState.gpsKmhRaw);
            boolean spdFromGps = Float.isNaN(HudState.speedKmh);
            float minOdoSpd = spdFromGps ? 4f : 1f;
            boolean spdOk = looksLikeRiding && !Float.isNaN(spdOdo) && spdOdo >= minOdoSpd;
            if (spdOk) {
                if (odoSpdSinceMs == 0L) odoSpdSinceMs = now;
            } else {
                odoSpdSinceMs = 0L;
            }
            boolean spdSustained = spdOk && (!spdFromGps || now - odoSpdSinceMs >= 4_000L);

            if (looksLikeRiding && !Float.isNaN(posDelta) && posDelta >= 0.0005f) {
                if (odoSpeedFill) {
                    // 盲区里已经按速度把这一段积过了：位移回来时这一大跳是"补课"，
                    // 只对表、不再计一次（否则桥洞/商场那 1km 会被算两遍）
                    odoSpeedFill = false;
                } else {
                    // 0.5 米门槛：推车 3km/h 每秒只有 0.8 米，门槛再高就把低速段整段丢掉
                    trip.addKm(posDelta * prefs.odoScale(), now);
                }
                posUsed = true;
            } else if (spdSustained) {
                trip.onSpeed(spdOdo * prefs.odoScale(), now);
                posUsed = true;          // 用了速度口径：本 tick 的位移就不要了（不叠加）
                if (spdFromGps) odoSpeedFill = true;
            } else {
                trip.onSpeed(0f, now);   // 停着/走路：只推进时间戳，不累计
            }
            if (!looksLikeRiding && !Float.isNaN(posDelta) && posDelta >= 0.02f
                    && now - lastWalkLogMs > 30_000L) {
                // 只在"确实攒了 20 米以上"时记一笔，不然走路时日志会被刷满
                lastWalkLogMs = now;
                RawLog.sys(now, String.format(Locale.US,
                        "walk gate: spd=%.1fkm/h < %.1f, skip %.2fkm (not a ride)",
                        HudState.gpsKmh, gate, posDelta));
            }
            // lastGpsKm 何时推进是这件事的命门：
            //   这支用掉了（位移或速度）→ 必须推进，否则下一支会把同一段再算一遍；
            //   位移太小不足门槛 → 不推进，让它继续攒（否则推车每 0.4 米就被吃掉）；
            if (posUsed || !looksLikeRiding || Float.isNaN(gk)) {
                if (!Float.isNaN(gk)) lastGpsKm = gk;
            }
            // 盲区自检：连着车、GPS 在报速、却没有位移也没有积分 → 说明定位点没点上，里程在漏
            if (linked && !Float.isNaN(HudState.gpsKmh) && HudState.gpsKmh >= 8f
                    && Float.isNaN(posDelta) && now - lastOdoBlindLogMs > 60_000L) {
                lastOdoBlindLogMs = now;
                HudState.odoBlind++;
                HudState.odoBlindTs = now;
                RawLog.sys(now, String.format(Locale.US,
                        "odo blind: spd=%.1fkm/h but no gps delta for %ds (km not growing)",
                        HudState.gpsKmh, (now - Math.max(kmGrowingTs, now - 3_600_000L)) / 1000));
            }
            HudState.tripKm = trip.km();
            // v1.0.49：本次里程如果被清零（或换过电瓶重来），锚点必须一起落地，
            // 否则 predict 里的 (km - anchorKm) 变成负数，电量会被反向顶高。
            if (socTracker != null) socTracker.rebaseIfBehind(HudState.tripKm);

            // v1.0.3：用**里程在不在长**判断车在不在跑，别只信 GPS 速度。
            // 实测（19:51–22:12 那几份日志）：GPS 速度偶发全是 NaN（速度精度被判不合格），
            // 车明明在 28km/h 跑，tick 里 spd=NaN、gkm 却每 30 秒长 0.25km —— 状态机把"骑行"
            // 当成"停车"，于是拿**带载电压**当静置电压去校准，电量一路被按到 4%。
            if (HudState.tripKm > lastKmForMove + 0.02f) {
                kmGrowingTs = now;
            }
            lastKmForMove = HudState.tripKm;
            // v1.0.7：停稳 5 分钟 + 蓝牙断开 → 武装报警（详见 maybeArmAlarm）
            maybeArmAlarm(now);
            // 整车累计（显示口径）：仪表基准 + App 累计 + 本次
            HudState.odoKm = prefs.odoBaseKm() + prefs.odoAccumKm() + HudState.tripKm;

            // v1.0.5：码表统计（最高/平均速度、骑行时长）。速度优先用控制器速度，
            // 没有就退回 GPS 速度；两个都没有时 TripStats 会靠"里程在长"自己认出来在骑。
            float statSpeed = !Float.isNaN(HudState.speedKmh) ? HudState.speedKmh : HudState.gpsKmh;
            stats.update(now, HudState.tripKm, statSpeed);
            HudState.maxKmh = stats.getMaxKmh();
            HudState.rideSec = stats.getMovingSec();
            HudState.avgKmh = stats.avgKmh(HudState.tripKm);

            // ---------------- 电压 ----------------
            // v1.0.0：上屏的是**中位数滤波后的实测端电压**（和控制器/小程序同一口径），
            // 不再叠加任何"压降补偿"——那是猜测，会把 68V 显示成 66V，骑手一看就对不上。
            // v1.0.18：设置页点了"换了新电瓶" → 在这里重建电量账本（旧电瓶的 %/km 和锚点不能沿用）
            if (packResetRequested.getAndSet(false)) {
                socTracker = new SocTracker(Float.NaN, Float.NaN, Float.NaN);
                socTracker.setDefaultRangeKm(prefs.estRangeKm());
                socTracker.update(now, HudState.tripKm, Float.NaN);
                bootMaxV = Float.NaN;              // 重新认"本次上电最高电压"
                // v1.0.24（用户实测 "--%"）：这里原来只清了**内存里的**账本，没清 SharedPreferences 里的
                // socAnchorSoc。于是下面的 bootstrap 因为"prefs 里说有锚点"而不跑，
                // 内存账本又没有锚点 → update() 一直返回 NaN → 大数字整天显示 "--%"。
                // 两个账本必须一起清。
                prefs.clearSocAnchor();
                RawLog.sys(now, String.format(Locale.US,
                        "pack reset by user: per_km and soc anchor cleared, km=%.2f", HudState.tripKm));
            }
            float disp = Float.NaN;
            float ocvUsed = Float.NaN;
            if (!Float.isNaN(liveV)) {
                disp = liveV;
                // v1.0.1：静置电压由 restFilter 按"每帧"采样（45s / 0.9 分位），tick 只负责读值。
                // 之前是 tick 每秒喂一次同一个值 → 把最新值反复加权，30 秒均值会贴着最后一帧走。
                ocvUsed = restFilter != null ? restFilter.value(now) : Float.NaN;
                HudState.ocvV = ocvUsed;
            }
            // v1.0.17：停车 ≥12 秒就把大数字切到"静置电压"，骑行中仍显示带载端电压。
            //
            // 为什么这么改：控制器同一时刻只允许一条连接，所以你和"育宁小程序"永远是**不同时刻**看电压。
            // 小程序只在你停车、退掉 HUD 之后才连得上 → 它读到的必然是静置电压（不带载、不压降）；
            // 而我们骑行中读到的是带载电压（爬坡能低 3–6V）。两边一对比就差 2–3V，
            // 看起来像"App 不准"，其实是两个物理量。
            // 停车 12 秒后改用静置值，就和小程序落在同一口径上了；骑行中保留带载值（那个才有诊断意义）。
            boolean parkedLongEnough = (parkedMode || (stationarySince > 0L && now - stationarySince > 12_000L))
                    && !Float.isNaN(ocvUsed) && restFilter != null && restFilter.samplesIn(now) >= 8;
            // v1.0.30：大数字永远是这一帧的原始电压 —— 和小程序同一口径、同一时刻就该是同一个数。
            // 静置读数 ocvUsed 只用来查表/校准，不再替换显示值（那会让"停车"和"骑行"看到两个数）。
            HudState.voltIsOcv = parkedLongEnough;

            // ---------------- 电量（v1.0.0 新口径） ----------------
            // 不再"每秒拿噪声电压查表"（实测会让电量 51%→17% 地跳）。
            // 现在：停车静置时用电压校准锚点，骑行时按里程推算，显示值每秒限速 2%。
            float speedKmh = HudState.gpsKmh;
            // v1.0.20：压降估计要**同时看油门**，不能只看速度。
            // 用户实测的坑正是"上坡满油门 15km/h"：速度不高 → 旧公式只补 0.9V，
            // 而实际电流拉满、压降 5–8V，剩下的全被当成"电掉了"。
            // 转把电压只对"刚到的帧"可信：链路断了以后它会停在最后一帧（可能是满油门 4V），
            // 拿它当"正在出力"会永久挡住电量纠偏。
            float thrV = (lastLiveVMs > 0L && now - lastLiveVMs < 3000L) ? HudState.throttleV : Float.NaN;
            float sag = Float.isNaN(speedKmh) && Float.isNaN(thrV)
                    ? 0f : loadComp.sagOf(speedKmh, thrV);
            HudState.sagV = sag;
            float socByVolt = Float.NaN;
            if (!Float.isNaN(disp)) {
                // 用"端电压 + 压降估计"查表，尽量贴近开路电压（OCV）
                socByVolt = soc.soc(disp + sag);
            }
            // 完全没有锚点（新装/清过数据）：等中位数收敛后先落一个"临时锚点"，
            // 否则电量会跟着噪声电压每秒飘；后面每次停车都会用静置电压重新校准。
            // v1.0.1：起点用"本次上电见过的最高电压"而不是当前瞬时值 ——
            // 冷启动往往正好在起步/爬坡，端电压被压掉 3–6V，用瞬时值会把满电显示成 60%，
            // 骑手一看就以为要没电了。最高值最接近静置电压，宁可略高也别吓人。
            if (Float.isNaN(prefs.socAnchorSoc()) && !Float.isNaN(socByVolt)
                    && voltFilter != null && voltFilter.getSamples() >= 5) {
                float vBoot = Float.isNaN(bootMaxV) ? disp : Math.max(disp, bootMaxV);
                float soc0 = soc.soc(vBoot + sag);
                if (Float.isNaN(soc0)) soc0 = socByVolt;
                socTracker.anchorRest(now, soc0, HudState.tripKm);
                prefs.setSocAnchorSoc(socTracker.getAnchorSoc());
                prefs.setSocAnchorKm(socTracker.getAnchorKm());
                prefs.setSocAnchorTs(now);
                RawLog.sys(now, String.format(Locale.US,
                        "soc bootstrap v=%.1f(peak %.1f) soc=%.0f km=%.2f (temp anchor, will re-calibrate at rest)",
                        vBoot, bootMaxV, soc0, HudState.tripKm));
            }
            // ---------------- 电量：连上就直读（v1.0.30） ----------------
            //
            // 用户的质疑是对的：蓝牙已经连着、电压每帧都在手上，凭什么还要
            // "锚点 + %/km + 纠偏预算 + 回升预算"绕一大圈？小程序只有一个电压、直接查表，
            // 反而跟车上对得上。所以这里改成：
            //   连上（8 秒内有帧）→ 电量 =（当前电压 + 压降补偿）查 OCV 表，每秒一次，是"读数"不是"推算"；
            //   断开 → 冻结在断开前的读数，不按里程猜着往下掉。
            // 压降补偿保留：带载端电压比开路低 2–6V，不补的话上坡掉电、下坡不还（这是实测过的坑）。
            boolean live = lastLiveVMs > 0L && now - lastLiveVMs < 8000L && !Float.isNaN(disp);
            float socVal;
            if (live) {
                // v1.0.44：**断了几分钟才回来 → 允许直跳**（可能刚充过电，慢慢爬才是骗人）；
                // 一直在连着 → 一律走死区 + 限速，屏幕不许抽。
                // v1.0.45：直跳不是"第一帧就跳"，而是重连后的 20 秒窗口里，等目标值稳住 6 秒再落位。
                if (lastLiveTickMs > 0L && now - lastLiveTickMs > 60_000L) {
                    snapWindowUntil = now + 20_000L;
                    linkBackMs = now;      // v1.0.69：这之后 90 秒内的静置校准只许往下
                    RawLog.sys(now, "soc: link back after " + ((now - lastLiveTickMs) / 60_000L)
                            + " min, snap confirm window open");
                }
                boolean allowSnap = snapWindowUntil > 0L && now <= snapWindowUntil;
                lastLiveTickMs = now;
                // v1.0.53：**分清"站着"和"跑着"**，这是这次复盘最核心的一条。
                // 11-19 全程里，屏幕上显示 64% 的同一刻，电压换算只有 58%；骑行中 10 秒窗口
                // 内电压换算的电量摆幅中位 25%、最大 91%（见 analysis/57）。所以：
                //   站着（停稳 20 秒 + 没油门）→ 电压可信，查表（这是"量血压"）
                //   跑着 → 电压不可信，按里程账本推（这是"数步子"）
                // 旧版两种情况都拿电压查表，所以永远在"多估"和"少估"之间抽。
                // v1.0.58：P 档（驻车锁）+ 油门怠速 + GPS 速度 ≤2km/h 也算静置。
                // 11-24 07:58 现场：车在 P 档、转把 0.8、速度 '--'，界面却标"骑行·按里程推算"，
                // 原因是开机瞬间 GPS 给过一次 21km/h，stationarySince 被清零、20 秒没攒够。
                // parkedMode（停稳 60 秒）同样直接算静置。
                boolean pGearIdle = HudState.faultsText != null
                        && HudState.faultsText.contains("P档")
                        && (Float.isNaN(thrV) || thrV < 1.5f);
                boolean slowEnough = Float.isNaN(speedKmh) || speedKmh <= 2f;
                boolean atRest = parkedMode
                        || (stationarySince > 0L && now - stationarySince >= 20_000L
                                && slowEnough && (Float.isNaN(thrV) || thrV < 1.5f))
                        || (pGearIdle && slowEnough);
                // v1.0.53：把口径摆给界面（“停稳·按电压” / “骑行·按里程”）
                HudState.socAtRest = atRest;
                // 查表电压优先用"逐帧配平的 OCV 候选"（这个值里已经含了每一帧自己的压降），
                // v1.0.44：候选样本不够时，**沿用上一次的 vMap**，不再退回"本帧 + 本帧压降"。
                // 实测（224404 日志）就是这么抖的：ocvCand 样本数在 8 附近来回穿，
                // vm 在 64.59/62.00 之间切源，屏幕上的电量 0↔2 每两秒跳一次。
                float vMap = (ocvCand != null && ocvCand.samplesIn(now) >= 6)
                        ? ocvCand.value(now) : Float.NaN;
                if (Float.isNaN(vMap)) vMap = lastVMap;
                // v1.0.50：**窗口刚重建时，不许拿单帧当靶子。**
                //
                // 断链时 ocvCand 和 lastVMap 都会清掉（为了"换电瓶/刚充满"能立刻按新帧算）。
                // 于是重连后的头 4–6 秒只剩 `disp + sag` 一条单帧，端电压随便摆 0.5V，
                // 查表就差 5–10 分 —— 屏幕上就是"63→76→67 一闪而过"（v1.0.45 确认闸拦的就是它，
                // 但确认闸只管直跳，斜坡照样在跑）。
                // 现在窗口没攒够样本时，**目标值直接沿用上一次的显示值**（等于"先不动"），
                // 等 45 秒窗里样本够了再按新数据走；真换过电瓶/刚充满，差 ≥20 分照样走直跳确认。
                boolean freshWindow = Float.isNaN(vMap);
                if (freshWindow) {
                    // v1.0.58：窗口没攒够样本时，先问里程账本；账本有锚点就按它推算，
                    // 别再死攥着上次落盘的旧电量（11-22 全天冻在 4% 的另一条支路）。
                    float predHold = socTracker == null ? Float.NaN
                            : socTracker.predict(HudState.tripKm);
                    if (!Float.isNaN(predHold)) socVal = predHold;
                    else socVal = Float.isNaN(lastLiveSoc) ? soc.soc(disp + sag) : lastLiveSoc;
                    if (now - lastFreshHoldLogMs > 30_000L) {
                        lastFreshHoldLogMs = now;
                        RawLog.sys(now, String.format(Locale.US,
                                "soc: window fresh, hold %.0f (samples=%d)",
                                socVal, ocvCand == null ? 0 : ocvCand.samplesIn(now)));
                    }
                } else {
                    lastVMap = vMap;   // v1.0.32：记下来，日志里能看到"到底拿多少伏去查表"
                    socVal = soc.soc(vMap);
                    if (Float.isNaN(socVal)) socVal = soc.soc(disp + sag);
                }
                // v1.0.36：查表结果再进一次 6 秒窗中位数，压掉单帧跳变（见字段注释）
                if (!Float.isNaN(socVal)) socVal = socSmooth.add(now, socVal);
                // v1.0.53：**跑着的时候不信电压，走里程账本**。
                // 账本 = 上一次静置锚点 − 每公里耗电% × 之后跑的里程；perKm 由每次静置校准学出来。
                // 这样上坡掉电、松油门回弹、重连后一闪而过这几个老毛病，从源头上没有了
                // —— 因为骑行过程中电压根本不参与显示。
                if (!atRest && socTracker != null) {
                    float pred = socTracker.predict(HudState.tripKm);
                    if (!Float.isNaN(pred)) socVal = pred;
                } else if (atRest && socTracker != null) {
                    // 站着的时候允许"电压纠正"。v1.0.63：向下仍限 5 点（防系统性偏低把电量拖走）；
                    // 向上放宽到 10 点 —— 静置 OCV 比旧账本高，说明之前估低了（半充电/长停放回弹），
                    // 该抬就抬；再往上由 SocTracker.upStepPct 封顶，不会一次对齐到满电。
                    float pred = socTracker.predict(HudState.tripKm);
                    if (!Float.isNaN(pred) && !Float.isNaN(socVal)) {
                        socVal = Math.max(pred - 5f, Math.min(pred + 10f, socVal));
                    }
                }
                // v1.0.44：最后再走显示层（死区 + 限速），这才是上屏的那个数
                // v1.0.49：骑行中单独一套参数（死区 3 分、上升 1 分/秒）—— 带载端电压自己在摆，
                // 让屏幕跟着摆就成了"电量自己往上爬两格"。
                if (!Float.isNaN(socVal)) {
                    // v1.0.53：显示层的"骑行参数"改为 `!atRest`。骑行时目标已经是里程账本，
                    // 本来就不会摆；死区 3 只是防单步抖动，停下后立刻回落到静置电压口径。
                    boolean riding = !atRest;
                    float before = socDisplay.value();
                    socVal = socDisplay.onEstimate(now, socVal, allowSnap, riding);
                    // v1.0.46：真发生"确认后的直跳"就记一笔 —— 复盘时能区分
                    // "这是充完电落到真值"还是"又被噪声带跑了"
                    if (!Float.isNaN(before) && Math.abs(socVal - before) >= 15f) {
                        RawLog.sys(now, String.format(Locale.US,
                                "soc snap %.0f -> %.0f (vm=%.2f, %s)", before, socVal, lastVMap,
                                allowSnap ? "snap window" : "plain"));
                    }
                }
                if (!Float.isNaN(socVal)) lastLiveSoc = socVal;   // v1.0.37：留给"断开后冻结"用
                // 账本跟着真值走：只有**静置**时才拿电压口径当锚点（骑行中的电压不是真值，不能当锚）
                // v1.0.53：改用 anchorRestGuarded —— 一次异常低压只挪 3 点、一箱电有预算，
                // 不会像 11-18 那样"每个红绿灯往下拽一点，最后 26.8km 只剩 4%"。
                if (atRest && !Float.isNaN(socVal) && socTracker != null
                        && (now - lastSocSyncMs > 60_000L
                        || Float.isNaN(socTracker.getShown())
                        || Math.abs(socVal - socTracker.getShown()) > 6f)) {
                    lastSocSyncMs = now;
                    socTracker.anchorRestGuarded(now, socVal, HudState.tripKm);
                    prefs.setSocAnchorSoc(socTracker.getAnchorSoc());
                    prefs.setSocAnchorKm(socTracker.getAnchorKm());
                    prefs.setSocAnchorTs(now);
                    prefs.setLastSoc(socVal);      // v1.0.38：跟着落盘，闪退后也不退回旧值
                }
            } else {
                // v1.0.37：断开就冻结"最后那一帧真的算出来的数"，而不是账本里几小时前的旧值
                // v1.0.42：连冻结值都没有（这次从没连上过、盘里也没存过）= 直接显示 "--%"。
                // 不再回落到 socTracker.getShown()：账本锚点可能是几天前手动电压推出来的 27%，
                // 没连车就摆一个 27% 在屏幕上，比 "--" 更容易误导人。
                socVal = lastLiveSoc;
            }
            int src;
            if (Float.isNaN(socVal)) {
                src = HudState.SRC_NONE;
            } else if (live) {
                src = HudState.SRC_BLE;
            } else if (!Float.isNaN(lastLiveSoc)) {
                // v1.0.44：断开后显示的是"断开前最后那一帧的读数" —— 这在语义上不是"推算"，
                // 单独给一个来源，界面才能说清"显示的是断开前读数（几分钟前）"，
                // 而不是之前那句容易被误读成"电压是猜的"。
                src = HudState.SRC_FROZEN;
                HudState.freezeAgeMs = lastLiveTickMs > 0L ? Math.max(0L, now - lastLiveTickMs) : 0L;
            } else {
                src = HudState.SRC_EST;
            }
            HudState.socFromKm = false;
            float perKm = socTracker.perKmOrDefault(prefs.estRangeKm());
            HudState.socPerKm = perKm;
            // 剩下的续航也从同一个读数推：电量回升，续航就跟着回升（不再"上坡扣掉、松油门不还"）
            HudState.rangeKm = (!Float.isNaN(socVal) && perKm > 0.01f) ? socVal / perKm : Float.NaN;

            // 手动电压（协议还没解出来时的临时顶班）：只在"从没连上过"时才用
            // v1.0.38：加 `Float.isNaN(lastLiveSoc)` 这道闸 —— 只要这次会话真读到过电量，
            // 就不允许旧的手动锚点再把显示值拉回去（那是"断开后显示很早之前电量"的真凶）。
            if (Float.isNaN(disp) && !Float.isNaN(HudState.manualV) && Float.isNaN(lastLiveSoc)) {
                float anchorKm = prefs.manualKm();
                float kmS = Math.max(0f, HudState.tripKm - anchorKm);
                float vPerKm = prefs.vPerKm();
                if (vPerKm <= 0.01f) {
                    vPerKm = VoltDecay.defaultVPerKm(prefs.vFull(), prefs.vEmpty(), prefs.estRangeKm());
                }
                disp = VoltDecay.estimate(HudState.manualV, vPerKm, kmS, prefs.vEmpty());
                src = kmS > 0.05f ? HudState.SRC_EST : HudState.SRC_MANUAL;
                HudState.manualAnchorV = HudState.manualV;
                HudState.manualAnchorSoc = soc.soc(HudState.manualV);
                HudState.kmSinceManual = kmS;
                HudState.manualAgeMs = HudState.manualTs > 0
                        ? Math.max(0L, now - HudState.manualTs) : 0L;
                HudState.vPerKmUsed = vPerKm;
            }

            // 离线时"手动/推算电压"把 disp 补上了：这时它就是一个电压读数，照样查表算电量，
            // 别出现"电压有数、电量 --%"（v1.0.27 修过一次，v1.0.30 把口径统一到这里）。
            // v1.0.38：只有当"从没读到过真电量"时才允许它覆盖 —— 否则它会把冻结值改成旧推算。
            if (!live && !Float.isNaN(disp) && Float.isNaN(lastLiveSoc)) {
                socByVolt = soc.soc(disp + sag);
                if (!Float.isNaN(socByVolt)) {
                    socVal = socByVolt;
                }
            }

            // v1.0.42：续航必须和"最终上屏的电量"同源。
            // 之前 rangeKm 是在上面那段"离线重算"之前就算好的，于是日志里出现
            // `soc=27 rng=80.0`：一箱只剩 27% 的车显示满电续航，自相矛盾。
            // 现在每次重算 socVal 之后，都用同一个值重算续航。
            HudState.rangeKm = (!Float.isNaN(socVal) && perKm > 0.01f)
                    ? socVal / perKm : Float.NaN;

            // 本箱电（"本箱电已跑 X km"）：用整车累计当坐标 —— 本次里程清零不会把它归零。
            // （旧版用 cycleKm（本次里程坐标），长按清零后"本箱电已跑"直接变成 0，和
            //  同一弹窗里的"本箱电已用 Wh"（跨清零累计）自相矛盾。）
            maybeBootFullReset(now, live, disp);
            maybeAnchorFull(now, disp);
            // v1.0.46：断档提示挂 15 分钟就撤（提示是用来"提醒你对齐一次"的，不是永久横幅）
            if (HudState.gapWarn.length() > 0 && gapWarnMs > 0L && now - gapWarnMs > 15L * 60_000L) {
                HudState.gapWarn = "";
            }
            float kmCharge = Float.NaN;
            float cSoc = prefs.chargeSoc();
            float cOdo = prefs.chargeOdoKm();
            // v1.0.62：本箱里程锚点必须真是满电（≥95%）才参与硬顶计算；
            // 半路残留的旧锚点不能拿来扣续航，否则会把上一箱的里程也算进这一箱。
            if (!Float.isNaN(cSoc) && cSoc >= 95f && !Float.isNaN(cOdo) && !Float.isNaN(HudState.odoKm)) {
                kmCharge = Math.max(0f, HudState.odoKm - cOdo);
            }
            HudState.kmThisCharge = kmCharge;
            HudState.anchorSoc = prefs.cycleSoc();

            hudSrcLast = src;
            // v1.0.56：**上屏之前先过两道护栏**。
            //  ① 空电锁：控制器已断电保护 → 电量最多 3%、续航 0（不管账本怎么算）
            //  ② 续航硬顶：剩余 = min(电量口径, 满电续航 − 本箱电已跑)。两个口径取小的那个，
            //     这样"学歪的 %/km"最多让你少看几公里，不会让你多跑十几公里后推车。
            if (HudState.emptyLatch) {
                socVal = Math.min(Float.isNaN(socVal) ? 2f : socVal, 3f);
                HudState.rangeKm = 0f;
            } else if (!Float.isNaN(HudState.rangeKm)) {
                // v1.0.68：**删掉"满电续航 − 本箱电已跑"这道硬顶。**
                // 11-26 截图现场：74.8V 静置、94%，续航却只有 ~6km。原因是 charge 锚点是
                // 11-25 08:02 那次半电误判的"满电"，之后两天所有里程都记进"本箱电已跑"（96.8km），
                // capKm = 80 − 96.8 → 0，把 94% 的续航压成 6km。
                // 用户的使用方式是"骑一段回家补电、补一会儿再骑"，本箱里程坐标在补电场景根本无效。
                // 现在续航只跟电量和满电续航走，不再减本箱里程；电量本身由静置电压校准。
                float capKm = prefs.estRangeKm() > 10f ? prefs.estRangeKm() : 80f;
                HudState.rangeKm = Math.min(HudState.rangeKm, capKm);
            }
            HudState.setPower(disp, socVal, src);

            // v1.0.44：**欠压提示**。实车"没动力、拧油门不走"往往就是这一条：
            // 电压掉到欠压线以下，控制器在做低电压保护（不是坏了）。
            // 之前界面只有电压变红，没有一句话说明原因，骑手只能猜。
            // v1.0.53：欠压提示的判据不能再用 vEmpty（63.8 是**静置**口径）。
            // 11-19 实测这车"彻底没电"时带载 58.26V，所以带载口径的报警线单列 60.5V，
            // 否则骑到还剩 10km 就会天天喊"低压保护"。
            // v1.0.57：**断电那一刻链路正好断掉，也不能漏判。**
            // 11-21 18:24 的现场就是这样：控制器已经拧不动（P档），蓝牙却掉线了，
            // 屏幕挂着 32 秒前的 68.0V（灰），于是"低压保护"这条根本没触发，电量还是 9%。
            // 现在允许用 120 秒内的最后一次带载电压判 —— 断电保护不会在 2 分钟内自己变好。
            if (live && !Float.isNaN(disp)) {
                lastLiveDispV = disp;
                lastLiveDispVMs = now;
            }
            float cutV = (live && !Float.isNaN(disp)) ? disp
                    : ((lastLiveDispVMs > 0L && now - lastLiveDispVMs <= 120_000L)
                    ? lastLiveDispV : Float.NaN);
            if (!Float.isNaN(cutV) && cutV < LOW_VOLT_WARN_V) {
                // v1.0.50：**先确认"真的走不动"，再喊低压保护。**
                //
                // 旧版只看端电压 < 欠压线就提示"拧油门不走"，可满载爬坡时端电压本来就会被
                // 压到 62V（40% 电量也照样掉到线下），车明明在跑，屏幕上却说"低压保护"。
                // 现在要求：连续低于欠压线 ≥8 秒，且车速 <12km/h（真走不动才成立）。
                if (lowVoltSinceMs == 0L) lowVoltSinceMs = now;
                boolean stuck = (Float.isNaN(speedKmh) || speedKmh < 12f);
                if (stuck && now - lowVoltSinceMs >= 8_000L) {
                    HudState.lowVoltWarn = "没电了 · 控制器已断电保护（拧油门不走），请充电";
                    // v1.0.56：**空电硬着陆**。
                    //
                    // 11-21 18:14 的现场：带载 59.8V、车已经拧不动，屏幕还写 21% / 还能跑 19km，
                    // 结果又推了几公里。原因是骑行口径的电量走"里程账本"，账本在中段被纠偏预算
                    // 卡住后一直偏乐观，到底部再也纠不回来。
                    // 控制器都断电保护了，账本说什么都不算数：立刻锁 3%、续航 0，并解除
                    // "里程账本还能回升"的可能（emptyLatch），直到真充满才解。
                    if (!HudState.emptyLatch) {
                        HudState.emptyLatch = true;
                        if (socTracker != null) socTracker.hardLand(now, HudState.tripKm, 3f);
                        socVal = 3f;
                        HudState.rangeKm = 0f;
                        learnTrueRangeOnCutoff(kmCharge);
                        RawLog.sys(now, String.format(Locale.US,
                                "EMPTY LATCH: controller cutout at v=%.1f%s, kmCharge=%s -> soc=3%% range=0",
                                cutV, live ? "" : " (link lost, last live)",
                                Float.isNaN(kmCharge) ? "-" : String.format(Locale.US, "%.1f", kmCharge)));
                    }
                } else {
                    HudState.lowVoltWarn = "";
                }
            } else {
                lowVoltSinceMs = 0L;
                HudState.lowVoltWarn = "";
            }

            // v1.0.5：能耗。电池没有电流采样（67B 里没这个字段），所以用能量守恒倒算：
            // 这段路掉了 x% 电量、跑了 y km → 平均功耗 = 额定Wh * x/100 / y。
            // 窗口取"最近 1km"，对标小牛仪表的最近 1km 平均功耗。
            if (!Float.isNaN(socVal)) {
                energy.add(HudState.tripKm, socVal);
            }
            HudState.whPerKm = energy.whPerKm(prefs.packWh());
            // 本箱电已用 Wh：锚点只由"充满"建立（v1.0.7 与 cycleSoc 分开），
            // 所以长按清零不会把它清零 —— 这一箱电到底花了多少，是一个跨多次出车的账。
            float chargeSoc = prefs.chargeSoc();
            HudState.whThisCharge = (!Float.isNaN(chargeSoc) && !Float.isNaN(socVal) && chargeSoc > socVal)
                    ? prefs.packWh() * (chargeSoc - socVal) / 100f : Float.NaN;

            // 统计每 30 秒落盘一次（进程被杀后能接上，不用从头开始算）
            if (now - lastStatsSaveMs >= 30_000L) {
                lastStatsSaveMs = now;
                prefs.setStatsMax(stats.getMaxKmh());
                prefs.setStatsMs(stats.getMovingMs());
            }
            maybeAnnounceKm();
            // v1.0.33：手机电量（骑手最怕的是手机先没电，HUD 直接黑屏）
            if (now - lastPhonePctMs > 30_000L) {
                lastPhonePctMs = now;
                try {
                    android.os.BatteryManager bm = (android.os.BatteryManager)
                            getSystemService(BATTERY_SERVICE);
                    if (bm != null) {
                        HudState.phonePct = bm.getIntProperty(
                                android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    }
                } catch (Throwable ignored) {
                }
                // v1.0.48：顺手查一次"通知使用权"，主界面要靠它决定显示导航文案还是"未开启"。
                // 放在 30 秒节流里：Settings.Secure 是字符串查询，别每秒都去读。
                boolean perm = NavBridge.enabled(BleService.this);
                if (perm != HudState.navPerm) {
                    HudState.navPerm = perm;
                    RawLog.sys(now, "nav permission: " + (perm ? "granted" : "denied"));
                    HudState.touch();
                }
            }
            // v1.0.33：超速提醒（借鉴 DigiHUD 的 warning speed：超过阈值变红 + 语音，60 秒只喊一次）
            // v1.0.53：顺手把阈值同步给界面（时速颜色以前写死 25/35，那是国标电动自行车的线，
            // 这台是 72V 电摩、实测能上 50，早就对不上设置里的数）
            HudState.speedLimitKmh = prefs.speedLimit();
            if (!Float.isNaN(speedKmh) && prefs.voice()) {
                int lim = prefs.speedLimit();
                if (lim > 0 && speedKmh > lim + 2f && now - lastSpeedWarnMs > 60_000L) {
                    lastSpeedWarnMs = now;
                    speak("超速了，当前" + Math.round(speedKmh) + "公里");
                    RawLog.sys(now, String.format(Locale.US,
                            "speed warn: %.0f > limit %d", speedKmh, lim));
                }
            }

            maybeLowPowerAlert(now, disp, socVal, src, HudState.ocvV, HudState.socAtRest);

            // 每 5 km 往 CSV 里写一条电量时间线（事后复盘/标定用）
            if (src == HudState.SRC_EST && (lastEstKm < 0f || HudState.tripKm - lastEstKm >= 5f)) {
                lastEstKm = HudState.tripKm;
                RawLog.est(now, disp, HudState.tripKm, HudState.socPerKm);
            } else if (src != HudState.SRC_EST) {
                lastEstKm = -1f;
            }

            // 一旦协议标定成功、拿到实时电压，手动值自动作废（不再误导）
            if (!Float.isNaN(liveV) && !Float.isNaN(HudState.manualV)) {
                HudState.manualV = Float.NaN;
                RawLog.sys(now, "live voltage acquired, manual cleared");
            }

            HudState.touch();

            // 通知栏每 5 秒刷一次数值：骑车时不掏手机也能看到还剩多少电
            if (now - lastNotifMs > 5000L) {
                lastNotifMs = now;
                updateNotificationNumbers();
            }

            // ---------------- 每 30 秒一条审计行 ----------------
            // 为什么加：之前几趟的日志里只有帧和"gps rejected"，没有"界面当时显示的是多少"，
            // 事后无法核对"时速准不准、里程有没有多算、电压推算走到哪一步"。
            // 这条 state 行把界面上的四个数原样落盘，复盘时对着看即可。
            if (now - lastStateLogMs >= 30000L) {
                lastStateLogMs = now;
                // v1.0.43：顺手刷一次"我还活着"的时间戳。被杀/被清理后，下次启动据此算断档时长。
                prefs.setLastRunMs(now);
                // v1.0.21（收尾审计）：`spd` 原来打的是 `HudState.speedKmh` —— 那个字段永远是 NaN，
                // 于是每 30 秒一条的审计行里速度一直是 "spd=NaN"，白丢一半诊断价值。
                // 现在打真正上屏的 GPS 时速，并额外带上转把（压降补偿靠它）。
                RawLog.sys(now, String.format(Locale.US,
                        "state v=%.1f src=%d soc=%.0f km=%.2f spd=%.0f gkm=%.2f acc=%.0f ocv=%.1f sag=%.1f thr=%.1f",
                        disp, src, socVal, HudState.tripKm,
                        Float.isNaN(speedKmh) ? -1f : speedKmh,
                        HudState.gpsKm, lastAccM, ocvUsed, HudState.sagV,
                        Float.isNaN(thrV) ? -1f : thrV));
            }

            // ---------------- 每 2 秒一条完整状态行（REC） ----------------
            // v1.0.22：用户要求"不要让我手记，什么都记进日志"。这条行把标定和复盘
            // 需要的每一项都自动落盘；事后只需要发 CSV（+ 顺手截几张图）即可。
            if (!sessStarted && !Float.isNaN(disp)) {
                sessStarted = true;
                sessTs0 = now;
                sessV0 = disp;
                sessSoc0 = socVal;
                sessKm0 = HudState.tripKm;
                sessOdo0 = HudState.odoKm;
            }
            if (!Float.isNaN(disp)) {
                sessMinV = Float.isNaN(sessMinV) ? disp : Math.min(sessMinV, disp);
                sessMaxV = Float.isNaN(sessMaxV) ? disp : Math.max(sessMaxV, disp);
            }
            if (now - lastRecMs >= 2000L) {
                lastRecMs = now;
                // v1.0.32：REC 只留"复算电量用得着的"。
                // 旧版的 nud/rcv/aa/ak/pk 是锚点引擎的产物，v1.0.30 之后那些数已经不再驱动显示，
                // 留着只会让日志看起来复杂；反过来真正缺的是这几样：
                //   rv = 原始帧电压（未滤波）  vm = 真正喂给电量表的 OCV 分位值
                //   sra = 直接拿原始电压查表的结果（和显示值对比，一眼看出补偿做了多少）
                RawLog.rec(now, new StringBuilder(220)
                        // v1.0.44：链路断了以后 lastRawV 会一直停在最后一帧（224404 日志里
                        // 连断 10 分钟还每行写 `rv=64.57`）。那会让人以为"还在读、只是显示不上去"，
                        // 所以只在有帧时写原值，否则写 "-"。
                        .append("rv=").append(lastLiveVMs > 0L && now - lastLiveVMs < 8000L
                                ? n2(lastRawV) : "-")
                        .append(" v=").append(n2(liveV))
                        .append(" vm=").append(n2(lastVMap))
                        .append(" dc=").append(n2(disp))
                        .append(" src=").append(src)
                        .append(" sag=").append(n2(sag))
                        .append(" thr=").append(n2(thrV))
                        .append(" spd=").append(n0(speedKmh))
                        .append(" km=").append(n2(HudState.tripKm))
                        .append(" odo=").append(n1(HudState.odoKm))
                        // v1.0.53：把 GPS 位移口径也写进日志 —— 复盘时能直接看出
                        // "位移 vs 速度积分 vs 显示总里程"三者差多少，不用再靠猜
                        .append(" gkm=").append(n2(HudState.gpsKm))
                        .append(" sc=").append(n3(prefs.odoScale()))
                        .append(" soc=").append(n0(socVal))
                        // v1.0.50：断链后 lastRawV 是"最后一帧"的残值，别再拿它算一个假 sra 出来
                        .append(" sra=").append(lastLiveVMs > 0L && now - lastLiveVMs < 8000L
                                ? n0(soc.soc(lastRawV)) : "-")
                        .append(" rng=").append(n1(HudState.rangeKm))
                        .append(" pk=").append(n3(HudState.socPerKm))
                        .append(" er=").append(n0(prefs.estRangeKm()))
                        .append(" vn=").append(n0(sessMinV))
                        .append(" vx=").append(n0(sessMaxV))
                        .append(" wh=").append(n0(HudState.whPerKm))
                        .append(" whc=").append(n0(HudState.whThisCharge))
                        .toString());
            }
            // v1.0.32：电量每"跳一格"就落一行 —— 调参时不用时间轴对齐，直接看这一串就够。
            if (live && !Float.isNaN(socVal)) {
                int sInt = Math.round(socVal);
                if (sInt != lastLoggedSoc) {
                    if (lastLoggedSoc != Integer.MIN_VALUE) {
                        RawLog.sys(now, String.format(Locale.US,
                                "SOC %d -> %d  vm=%.2f rv=%.2f sag=%.2f thr=%.2f spd=%.0f km=%.2f",
                                lastLoggedSoc, sInt, lastVMap, lastRawV, sag, thrV, speedKmh,
                                HudState.tripKm));
                    }
                    lastLoggedSoc = sInt;
                }
            }

            // ---------------- 每 5 分钟一条心跳（v1.0.50） ----------------
            // 用户反复反馈"固定几分钟后闪退回桌面"。闪退分两种：
            //  ① Java 崩溃 → HudApp 的崩溃钩子会写 CRASH；
            //  ② 被系统秒杀 / 原生崩溃（ANR、OOM、蓝牙 HAL）→ 什么都不会写。
            // 有了心跳，日志里"最后一条 hb 之后就直接断了"就能区分这两种，
            // 也能看出是不是固定时长（比如恰好 5 分钟 → 更像系统清理而不是代码崩）。
            if (now - lastHeartbeatMs >= 300_000L) {
                lastHeartbeatMs = now;
                long upSec = (now - bootMs) / 1000L;
                Runtime rt = Runtime.getRuntime();
                RawLog.sys(now, String.format(Locale.US,
                        "hb up=%ds conn=%d src=%d soc=%s odo=%.1f tripe=%.1f heap=%d/%dMB thr=%d",
                        upSec, HudState.connState, hudSrcLast,
                        Float.isNaN(socVal) ? "--" : String.format(Locale.US, "%.0f", socVal),
                        HudState.odoKm, HudState.tripKm,
                        (rt.totalMemory() - rt.freeMemory()) / 1048576L,
                        rt.maxMemory() / 1048576L, Thread.activeCount()));
            }

            } catch (Throwable t) {
                // 同一个异常如果每秒刷一次会撑爆日志：同类只记前 3 次，之后 60 秒记一次
                String key = t.getClass().getSimpleName() + ":" + (t.getStackTrace().length > 0
                        ? t.getStackTrace()[0].toString() : "?");
                long cnt = tickErrCount.getOrDefault(key, 0L) + 1;
                tickErrCount.put(key, cnt);
                if (cnt <= 3 || now - tickErrLastMs > 60_000L) {
                    tickErrLastMs = now;
                    RawLog.sys(now, "tick-ex #" + cnt + " " + key + "\n"
                            + stackOf(t));
                }
            }
            h.postDelayed(this, 1000);
        }
    };

    // ---------------- GPS fallback ----------------

    private LocationListener locCb;
    private Location lastLoc;
    /** v1.0.12：最后一次**真正收到定位**的系统时间。GPS 断流时用它把卡住的时速作废 */
    private long lastGpsFixWall = 0L;
    /** 停车状态机：停在路边等单时把 GPS 从 1s 降到 3s，一省电二省流量 */
    private boolean parkedMode;
    private long stationarySince;
    /** v1.0.19：连续几次"电压口径比能量口径低太多"（≥3 次才反过来认电压） */
    private int voltSuspectCount;
    /** 本轮停车已经做了几次静置校准（最多 3 次：120s / 3min / 8min） */
    private int restCalCount;    private long lastRestCalMs;
    /** 里程最近一次"在增长"的时刻：判断车到底是在跑还是停着 */
    private long kmGrowingTs;
    /** v1.0.57：最后一次带载电压（链路掉线时用来判"断电保护"） */
    private float lastLiveDispV = Float.NaN;
    private long lastLiveDispVMs = 0L;
    /** v1.0.57：GPS 速度兜底连续多久了（不到 4 秒不算，挡停车漂移） */
    private long odoSpdSinceMs;
    /** v1.0.57：盲区里已经按速度积过里程，位移恢复时别把那一段再算一遍 */
    private boolean odoSpeedFill;
    /** v1.0.53：带载口径的低压报警线（实测这车"彻底没电"时带载 58.26V） */
    private static final float LOW_VOLT_WARN_V = 60.5f;
    private float lastKmForMove;
    private float lastSummaryKmF = -1f;
    /** 里程：照 OpenTracks 的精度门限 + 抗漂移/抗跳点 */
    private final TrackDistance trackDist = new TrackDistance();
    /** 时速：1€ 滤波 + 停车归零 + 短时保持 */
    private final SpeedStabilizer speedStab = new SpeedStabilizer();
    private long lastGpsDropLogMs;

    // ---------- v1.0.5：码表统计 / 能耗 / 防盗 ----------
    /** 最高速度、平均速度、骑行时长（KT-LCD8S 那套口径） */
    private final TripStats stats = new TripStats();
    /** 最近 1km 能耗窗口（Wh/km） */
    private final EnergyBook energy = new EnergyBook();
    /** 报警：武装时的位置锚点（v1.0.7 重做，见 maybeArmAlarm / checkAlarm） */
    private Location alarmAnchor;
    private boolean alarmArmed;
    /** "离开锚点且在快速移动"的起点时刻：连续 15 秒才真响 */
    private long alarmArmMotionTs;
    private long alarmFireTs;
    /** 最近一次真正连上控制器的时刻（报警武装的闸门） */
    private long lastConnOkTs;
    /** 最近一次统计落盘（每 30 秒一次，进程被杀也能接上） */
    private long lastStatsSaveMs;

    private boolean hasPerm(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void startGps() {
        if (locCb != null) return;          // 已经在跑，别重复注册监听
        try {
            // v1.0.12：这里用 checkSelfPermission 而不是自定义 hasPerm()，让静态检查（Android Lint）
            // 也能看见这道判断 —— 否则它会把下面每一次 requestLocationUpdates 都标成"可能崩溃"。
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                HudState.gpsOk = false;
                // v1.0.12：把"为什么没有时速和里程"直接写在屏幕上。
                // 系统只给了"大致位置"（≈1km 精度）和完全拒绝，对骑手是两件完全不同的事，
                // 处理方式也不同（前者去改成"精确"，后者去开权限），所以分开说。
                HudState.gpsWarn = hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
                        ? "定位只有“大致位置”→ 时速/里程会失真，请在系统设置里改成“精确位置”"
                        : "没给定位权限 → 里程和时速不可用（设置 › 应用 › 智能HUD › 权限 › 位置）";
                RawLog.sys(System.currentTimeMillis(), "gps: " + HudState.gpsWarn);
                HudState.touch();
                return;
            }
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) { HudState.gpsOk = false; return; }
            HudState.gpsOk = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (!HudState.gpsOk) {
                HudState.gpsWarn = "系统定位开关没开 → 里程和时速不可用（下拉通知栏打开“位置信息”）";
                RawLog.sys(System.currentTimeMillis(), "gps: provider disabled");
                HudState.touch();
            } else {
                HudState.gpsWarn = "";      // 权限和开关都正常，撤掉提醒
            }
            locCb = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    if (l == null) return;
                    // v1.0.40：定位回调兜底 —— 系统回调线程抛异常同样会带走整个进程
                    try {
                        handleFix(l);
                    } catch (Throwable t) {
                        String key = "fix " + t.getClass().getSimpleName();
                        long cnt = tickErrCount.getOrDefault(key, 0L) + 1;
                        tickErrCount.put(key, cnt);
                        if (cnt <= 3) RawLog.sys(System.currentTimeMillis(),
                                "fix-ex #" + cnt + " " + stackOf(t));
                    }
                }
                @Override public void onStatusChanged(String p, int s, Bundle b) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locCb, Looper.getMainLooper());
            RawLog.sys(System.currentTimeMillis(), "gps started");
        } catch (Exception e) {
            Log.w(TAG, "gps start failed", e);
        }
    }

    /**
     * v1.0.40：定位回调主体。原来写死在匿名类里，异常会直接崩掉进程；
     * 搬到独立方法后由外面统一 try/catch 兜底，并把堆栈写进 CSV（前缀 fix-ex）。
     */
    private void handleFix(Location l) {
        lastGpsFixWall = System.currentTimeMillis();   // v1.0.12：时速过期判定用这个
            long now = l.getTime() > 0 ? l.getTime() : System.currentTimeMillis();
            double acc = l.hasAccuracy() ? (double) l.getAccuracy() : Double.NaN;
            // 这一刻相对上一点真实挪了多少米（判断"车是不是停在等单"要用）
            float moveM = lastLoc == null ? 0f : lastLoc.distanceTo(l);

            // v1.0.37 的"没连控制器就不算在骑车"在 v1.0.53 被**拆开重做**。
            // 11-19 17:20–18:23 那一小时蓝牙确实没应答（src=4），但用户后来更正：
            // **那段时间人在家、车熄火**，所以 0 km 是对的 —— 不是被规则吃掉。
            // 但"里程要不要记"跟着蓝牙状态走，本身就不合理：控制器只是电量来源，
            // 断开时 GPS 照样知道走了多远。所以现在里程只由"是不是真在骑车"决定
            // （parkedMode + 速度闸门），与蓝牙无关；只有"没连车 + 速度像走路（<8km/h）"才不记。
            boolean linkFresh = lastLiveVMs > 0L
                    && System.currentTimeMillis() - lastLiveVMs < 15_000L;

            // ---- 隧道/桥洞断档：用断档前的速度把里程补回来（3–15 秒才补）----
            // v0.9.9：停车态一律不补。停车时 GPS 间隔变成 3 秒，正好撞上 GapFill 的 3 秒门槛，
            // 而静止时 GPS 速度仍有 2–6km/h 噪声 → 每 3 秒补一段假里程，停一小时能多出 3–6 公里。
            if (lastLoc != null && !parkedMode) {
                long gap = now - lastLoc.getTime();
                // 必须用"断档前那个点自己的速度"：SpeedStabilizer 只保持 4 秒，
                // 8 秒的断档等回来时它已经是 0，拿它去补等于没补（老 bug）
                float vPre = lastLoc.hasSpeed()
                        ? lastLoc.getSpeed() * 3.6f : speedStab.value();
                double fill = GapFill.meters(gap, vPre);
                if (fill > 0.5) {
                    float base = Float.isNaN(HudState.gpsKm) ? 0f : HudState.gpsKm;
                    HudState.gpsKm = base + (float) fill / 1000f;
                    // **关键**：这段已经补过了，必须把里程积分器的基准点挪到当前点，
                    // 否则下面 trackDist.add 会把同一段路再算一次（隧道里里程翻倍）
                    trackDist.rebind(now, l.getLatitude(), l.getLongitude());
                    RawLog.sys(now, String.format(Locale.US,
                            "gps gap %ds filled %.0fm @%.0fkm/h", gap / 1000, fill, vPre));
                }
            }

            // ---- 时速（先算，因为里程的死区判断要用它）----
            float rawSpeed = l.hasSpeed() ? l.getSpeed() * 3.6f : Float.NaN;
            // 速度本身也带"精度"：低速时系统常给出 ±2m/s 的坏值，
            // 这种坏值宁可用位移兜底，也不要让它把显示拉到 20km/h 再掉回来
            if (!Float.isNaN(rawSpeed) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && l.hasSpeedAccuracy()
                    && l.getSpeedAccuracyMetersPerSecond() > 1.5f
                    && rawSpeed < 15f) {
                rawSpeed = Float.NaN;
            }
            if (Float.isNaN(rawSpeed) && lastLoc != null) {
                long dtMs = now - lastLoc.getTime();
                float d = lastLoc.distanceTo(l);
                // v1.0.1：间隔下限从 500ms 提到 800ms（0.5 秒里的位移噪声最大），
                // 上限从 100km/h 降到 60km/h —— 外卖车实际跑不到 60，
                // 超过就是跳点噪声，别让它把时速顶上去再掉回来。
                if (dtMs >= 800L && dtMs <= 5000L && d >= 1f && d <= 200f) {
                    float fallback = d / dtMs * 3600f;
                    if (fallback <= 60f) rawSpeed = fallback;
                }
            }

            // ---- 里程：门限式累计（OpenTracks 默认精度 50m，位移<3m 且低速算抖动）----
            // v1.0.1：停车态**完全不计**，只把基准点挪过去。停车时 GPS 速度噪声常有 3–6km/h，
            // 会骗过"速度>2km/h 才算在动"的那道门，停一小时能白记两三公里。
            // v1.0.53：断开控制器时不再整段冻结（那是丢了一小时里程的元凶），
            // 但"没连车 + 速度像走路（<8km/h）"仍然不计 —— 保住 v1.0.37 要挡的那件事。
            boolean walkingWhileUnlinked = !linkFresh && !Float.isNaN(rawSpeed) && rawSpeed < 8f;
            double step;
            if (parkedMode || walkingWhileUnlinked) {
                trackDist.rebind(now, l.getLatitude(), l.getLongitude());
                step = 0;
            } else {
                step = trackDist.add(now, l.getLatitude(), l.getLongitude(), acc, rawSpeed);
            }
            if (step > 0) {
                float base = Float.isNaN(HudState.gpsKm) ? 0f : HudState.gpsKm;
                HudState.gpsKm = base + (float) step / 1000f;
            } else if (now - lastGpsDropLogMs > 60_000L) {
                // 一分钟记一次丢点原因，避免刷屏（也是排查"里程偏多/偏少"的证据）
                lastGpsDropLogMs = now;
                RawLog.sys(now, String.format(Locale.US,
                        "gps rejected: acc=%.0fm jitter=%d badacc=%d jump=%d ok=%d",
                        acc, trackDist.getSkippedJitter(), trackDist.getSkippedAccuracy(),
                        trackDist.getSkippedJump(), trackDist.getAccepted()));
            }

            // ---- 时速显示值：1€ 滤波 + 停车归零 + 丢点保持 ----
            // v1.0.37：控制器没连时把时速设为"无" —— 这条过度了：
            // 断开时 GPS 照样在报速，屏幕却一直是 "-- km/h"，骑手看不到自己多快。
            // 现在改成"照常显示 GPS 时速，只是断开时把 <8km/h 的走路值挡掉"，不再整段熄屏。
            float shown = speedStab.push(now, rawSpeed);
            if (!linkFresh && !Float.isNaN(shown) && shown < 8f) shown = Float.NaN;
            HudState.gpsKmh = shown;
            // v1.0.53：原始滤波值单独留一份 —— 里程闸门要用"真实速度"判断是不是在骑车，
            // 不能用被遮成 NaN 的显示值（否则 NaN 一律按"在骑"就挡不住走路了）
            HudState.gpsKmhRaw = shown;
            HudState.speedFromGps = !Float.isNaN(shown);

            if (!Double.isNaN(acc)) lastAccM = (float) acc;
            // v1.0.7：异动判定独立于"停车/骑行"状态机（车被骑走时状态机已经切到骑行，
            // 旧版在这里就把锚点清掉了，等于永远报不出来）
            checkAlarm(now, l, moveM);
            lastLoc = l;
            updateStationary(now, shown, moveM);
            HudState.touch();
    }

    /**
     * 停车状态机。
     *
     * 骑手一天里有大量时间停在商场门口等单，这时 1 秒一次 GPS 纯属浪费：
     *  - 停稳 60 秒 → 间隔降到 3 秒（省电约 2/3，也不影响等人时的显示）
     *  - 一动（速度 >2.5km/h 或挪了 8 米）→ 立刻恢复 1 秒，不丢起步那一段
     *  - 顺手在"停下来的那一刻"写一条 trip summary，方便事后回看这一趟
     */
    private void updateStationary(long now, float shown, float moveM) {
        // 门槛从 2.5km/h / 8m 提到 3.5km/h / 15m（v0.9.9）：
        // 停车时 GPS 速度和坐标都在抖，动不动就 >8m 会让"停车降频"反复进出，
        // 既费电又让日志刷屏。真起步时一两秒内就会超过 3.5km/h，不会漏掉起步。
        // v1.0.3：再加一条**里程在长 = 在跑**。GPS 速度偶发全 NaN 时，只看速度会把骑行误判成停车。
        boolean moving = (!Float.isNaN(shown) && shown > 3.5f) || moveM > 15f
                || (kmGrowingTs > 0L && now - kmGrowingTs < 5000L);
        if (moving) {
            stationarySince = 0L;
            if (parkedMode) exitParked(now);
            return;
        }
        if (stationarySince == 0L) stationarySince = now;
        if (!parkedMode && now - stationarySince > 60_000L) enterParked(now);
    }

    private void enterParked(long now) {
        parkedMode = true;
        try {
            if (lm != null && locCb != null
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f,
                        locCb, Looper.getMainLooper());
            }
        } catch (Exception ignored) {
        }
        RawLog.sys(now, "gps parked: interval 1000ms -> 3000ms");
        // v1.0.22：停下 60 秒就把这一趟的 SUM 写掉 —— 用户不用记任何东西，
        // 也避免"跑完直接关屏/杀进程"导致这段数据没有任何汇总。
        writeSum(now, "park");
        // v1.0.7：锚点不在这里记。停 60 秒（等红灯、取餐）太短，还谈不上"离车"；
        // 真正武装在 tick 里的 maybeArmAlarm：停稳 5 分钟且蓝牙断开才记锚点。
        writeTripSummary(now);
        // 铅酸刚停下时还在"回弹"（端电压要 5–10 分钟才接近开路电压），
        // 所以不在这里立刻校准：交给 tick 里 90 秒/3 分钟/8 分钟三次分段校准。
        restCalCount = 0;
        lastRestCalMs = 0L;
    }

    /**
     * 停车静置校准电量锚点（v1.0.0 的核心）。
     *
     * 骑手一天里会在商场门口停很多次，每次停稳都是"量一次静置电压"的机会：
     * 这时端电压≈开路电压，查表得到的电量是可信的。把它记成锚点，
     * 骑行时电量就从这个锚点按里程往下走，比拿噪声电压逐秒查表稳得多。
     *
     * 同时用「上一次锚点 → 这一次锚点」学习每公里耗电%，越跑越准。
     */
    private void calibrateSocAtRest(long now) {
        if (socTracker == null) return;
        // 闸门一：最近 90 秒里程还在涨 → 车在跑，读到的电压是带载压降，绝不能当开路电压
        if (kmGrowingTs > 0L && now - kmGrowingTs < 90_000L) {
            RawLog.sys(now, "soc calibrate skipped: odometer still growing (riding, not parked)");
            return;
        }
        // 只用 30 秒慢平滑的静置均值，不再拿"最后一帧瞬时值"兜底（v1.0.1）：
        // 瞬时值是 ±5V 的噪声源，拿它当锚点等于把噪声焊死在电量里。
        float v = restFilter != null ? restFilter.value(now) : Float.NaN;
        boolean staleV = false;
        if (Float.isNaN(v) && lastRestVMs > 0L && now - lastRestVMs <= 20L * 60_000L) {
            // v1.0.57：**熄火停车也能校准。**
            //
            // 骑手停好车往往就把钥匙拧掉 —— 控制器不再推帧，8 秒后 restFilter 被 reset，
            // 于是"停稳 120s / 301s / 481s"三次校准全是 `no settled voltage`。
            // 11-21 的日志里这类跳过有二十多条，静置校准形同虚设。
            // 用"断链前最后一段静置均值"（≤20 分钟）当参考：它可能偏低（那是带载读数），
            // 偏低只会把电量算少，宁可保守也不能让骑手以为还有电。
            v = lastRestV;
            staleV = true;
        }
        if (Float.isNaN(v)) {
            RawLog.sys(now, "soc calibrate skipped: no settled voltage (waiting first stop)");
            return;
        }
        if (!staleV) {
            lastRestV = v;
            lastRestVMs = now;
        }
        // 闸门二：45 秒静置窗里"分位值"和"中位值"差太多 → 这个窗口跨了负载变化（刚松油门/刚起步），
        // 不是真正的静置平台，等下一次校准。
        // （stale 值没有"窗口"可言，跳过这两道）
        if (!staleV) {
            float med = restFilter.median(now);
            if (!Float.isNaN(med) && Math.abs(v - med) > 1.2f) {
                RawLog.sys(now, String.format(Locale.US,
                        "soc calibrate skipped: window not settled (%.1f vs %.1f)", v, med));
                return;
            }
            // 窗口里样本太少（刚重连完就来停车）也不算：均值不收敛。
            // v1.0.3：这里原来查的是 5 秒显示窗（voltFilter），停车后只有 4–5 帧，经常正好卡在门槛下，
            // 导致"该校准却跳过"。改查 45 秒静置窗（restFilter）：停车 90 秒后至少有 90 帧。
            if (restFilter != null && restFilter.samplesIn(now) < 10) {
                RawLog.sys(now, "soc calibrate skipped: too few settled samples");
                return;
            }
        }
        // 只有"像静置电压"才校准：低于欠压+0.5 或高于满电+2 的读数多半是异常的
        if (v < prefs.vEmpty() || v > prefs.vFull() + 2f) return;
        float socRest = soc.soc(v);
        if (Float.isNaN(socRest)) return;
        // v1.0.57：学斜率要用"电压说的那个数"，不能用下面能量体检修正过的数 ——
        // 否则学进去的是我们自己压出来的下限，不是这段路的真实耗电。
        float socVoltRaw = socRest;
        float pred = socTracker.predict(HudState.tripKm);
        if (staleV && !Float.isNaN(pred) && socRest > pred) {
            // 熄火后的旧读数只能往下压，不能往上抬（旧值可能只是带载压降，不是开路电压）
            RawLog.sys(now, String.format(Locale.US,
                    "soc stale rest %.1fV -> %.0f%% > ledger %.0f%%, ignore", v, socRest, pred));
            return;
        }
        // v1.0.69：**刚重连的头 90 秒，只许往下校准。**
        //
        // 11-26 14:26 现场：断链 138 分钟后重连，控制器报的静置电压从 76.3V 一路爬到 77.8V
        // （充电器浮电/表面电荷），电量从 58% 被抬到 94%，而十几分钟后真静置只有 74.8V（78%）。
        // 这一段"往上抬"全是被浮电带跑的，所以重连初期只接受"往下"的纠正 —— 往下永远安全。
        if (linkBackMs > 0L && now - linkBackMs <= 90_000L
                && !Float.isNaN(pred) && socRest > pred + 2f) {
            RawLog.sys(now, String.format(Locale.US,
                    "soc calibrate skipped: link just back %.0fs, rise %.0f->%.0f not trusted (v=%.1f)",
                    (now - linkBackMs) / 1000f, pred, socRest, v));
            return;
        }

        // v1.0.19：先用"能量核算"给电压读数做一次体检，再决定信不信它。
        //
        // 为什么要这层：用户这台是**全新 72V/21Ah 电瓶**（满电约 80km），可是 11-17 的日志里
        // 跑到 26.8km 时静置电压已经掉到 66.2V（按查表 = 4%）。用能量核对一下就知道这个数不对劲：
        // 72V×21Ah = 1512Wh，26.8km 就算按最费的 30Wh/km 也只用掉约 800Wh（≈53%），
        // 还剩近一半的电。也就是说"66V 就该 4%"这个结论，是**电压表末端塌陷**造成的假象
        // （铅酸末端、单格落后、带载余温都会这样），不是电真的没了。
        // 若直接拿它当锚点，骑手就会看到"26.8km 掉到 4%、还能跑 1km"这种明显违背常识的数。
        //
        // 规则：电压口径若比能量口径低 15 点以上，先只认"能量口径 -15"这个下限；
        //       连续 3 次校准都这么低，才反过来认定"是能量模型估歪了"，让电压说了算。
        float socEnergy = Float.NaN;
        try {
            float whKm = energy.whPerKm(prefs.packWh());
            float kmBox = HudState.odoKm - prefs.chargeOdoKm();
            // v1.0.68：**本箱里程坐标已经失效时不要做能量体检。**
            // 11-25 现场：charge 锚点是半电误判出来的"满电"，两天后 kmBox 累计到 96.8km（超过满电续航），
            // socEnergy 被算成 0，于是 `socRest > socEnergy + 15` 把正确的 84%（75.5V）压成 15%，
            // 账本越压越离谱。里程坐标在"多次补电"场景本来就不成立，超过满电续航就当作无效。
            float erKm = prefs.estRangeKm() > 10f ? prefs.estRangeKm() : 80f;
            if (!Float.isNaN(whKm) && whKm > 5f && kmBox > 1f && kmBox <= erKm * 1.05f) {
                float packWh = prefs.packWh();
                if (packWh > 100f) {
                    socEnergy = (100f - (whKm * kmBox) / packWh * 100f);
                    socEnergy = Math.max(0f, Math.min(100f, socEnergy));
                }
            }
        } catch (Exception ignored) {
        }
        if (!Float.isNaN(socEnergy)) {
            if (socRest < socEnergy - 15f) {
                voltSuspectCount++;
                if (voltSuspectCount < 3) {
                    float floor = socEnergy - 15f;
                    RawLog.sys(now, String.format(Locale.US,
                            "volt suspect #%d: v=%.1f socV=%.0f but energy says %.0f -> use %.0f",
                            voltSuspectCount, v, socRest, socEnergy, floor));
                    socRest = Math.max(socRest, floor);
                } else {
                    RawLog.sys(now, String.format(Locale.US,
                            "volt suspect #%d: accepting voltage (energy model off): v=%.1f socV=%.0f vs energy %.0f",
                            voltSuspectCount, v, socRest, socEnergy));
                    voltSuspectCount = 0;      // 认了电压，计数归零，下次重新观察
                }
            } else if (socRest > socEnergy + 15f) {
                // 反方向：电压说很满、能量说用掉很多。同样别立刻信，但也别把电量往上抬太多
                RawLog.sys(now, String.format(Locale.US,
                        "volt high vs energy: socV=%.0f energy=%.0f (keep voltage)", socRest, socEnergy));
                socRest = Math.min(socRest, socEnergy + 15f);
            } else {
                voltSuspectCount = 0;          // 两个口径一致，电压可信
            }
        }

        float learned = socTracker.anchorRestGuarded(now, socRest, HudState.tripKm);
        // v1.0.68：静置电压比账本高 ≥8 点、且落在中段 → 判定"中途补过电"，
        // 把本箱里程坐标从这里重新起算。否则"本箱电已跑"会把补电前那趟一直累加下去
        // （11-26 已累到 96.8km，是续航被压成 6km 的另一半原因）。
        if (!Float.isNaN(pred) && socRest - pred >= 8f && socRest >= 20f) {
            prefs.setChargeAnchor(socRest, HudState.odoKm);
            RawLog.sys(now, String.format(Locale.US,
                    "charge anchor moved: recharge detected (v=%.1f socV=%.0f ledger %.0f) odo=%.1f",
                    v, socRest, pred, HudState.odoKm));
        }
        prefs.setSocAnchorSoc(socTracker.getAnchorSoc());
        prefs.setSocAnchorKm(socTracker.getAnchorKm());
        prefs.setSocAnchorTs(now);
        prefs.setPerKmPct(socTracker.getPerKmPct());
        if (!Float.isNaN(learned)) {
            RawLog.sys(now, String.format(Locale.US,
                    "soc anchor learned %.2f%%/km v=%.1f soc=%.0f km=%.2f",
                    learned, v, socRest, HudState.tripKm));
            // v1.0.16：把"每公里耗多少"换算成隐含续航写进日志。
            // 实测这台车从满电跑到 66V 大约只有 27km（≈3.7%/km），而设置页里填的是 80——
            // 有了这行，事后一眼就能看出"该把满电续航改成多少"，不用再靠猜。
            float impliedRange = learned > 0.05f ? 100f / learned : Float.NaN;
            if (!Float.isNaN(impliedRange)) {
                RawLog.sys(now, String.format(Locale.US,
                        "range implied ~%.0fkm vs settings %dkm%s",
                        impliedRange, prefs.estRangeKm(),
                        Math.abs(impliedRange - prefs.estRangeKm()) > 8f
                                ? "  <- differs a lot, consider changing est_range" : ""));
                // v1.0.58：锚点正常学出来的斜率也同步满电续航（与 learnPerKmFromRest 同口径），
                // 否则 er 一直停在用户手填的 80，续航上限不随实际耗电收紧。
                int erAnchor = Math.round(impliedRange);
                if (erAnchor >= 40 && erAnchor <= 250
                        && Math.abs(erAnchor - prefs.estRangeKm()) >= 3) {
                    int oldEr = prefs.estRangeKm();
                    prefs.setEstRangeKm(erAnchor);
                    RawLog.sys(now, String.format(Locale.US,
                            "per-km %.2f (anchor sample) -> est_range %d -> %d km",
                            learned, oldEr, erAnchor));
                }
            }
        } else if (socTracker.getRejects() > 0) {
            // 电压口径和里程口径差得太多：只小幅纠偏，别让电量被一路按到底
            RawLog.sys(now, String.format(Locale.US,
                    "soc anchor nudge%+.0f v=%.1f socV=%.0f socKm=%.0f km=%.2f rejects=%d",
                    socTracker.getAnchorSoc() - (Float.isNaN(pred) ? 0f : pred),
                    v, socRest, pred, HudState.tripKm, socTracker.getRejects()));
        } else {
            RawLog.sys(now, String.format(Locale.US,
                    "soc anchored at rest v=%.1f soc=%.0f km=%.2f", v, socRest, HudState.tripKm));
        }
        // v1.0.57：**锚点跳不跳是一回事，斜率学不学是另一回事。**
        // 上面 anchorRestGuarded 因为"差 17 点"拒掉的时候，那段 32km 的耗电证据也一起被丢了，
        // perKm 于是一直卡在 1.14，底部一路偏乐观。这里独立再学一次（带"末端塌陷"过滤），
        // 学到就把满电续航一起改过来 —— 续航上限 `estRange − kmCharge` 立刻跟着变紧。
        float learned2 = socTracker.learnPerKmFromRest(socVoltRaw, HudState.tripKm);
        if (!Float.isNaN(learned2)) {
            prefs.setPerKmPct(learned2);
            int er = Math.round(100f / learned2);
            if (er >= 40 && er <= 250 && Math.abs(er - prefs.estRangeKm()) >= 3) {
                int old = prefs.estRangeKm();
                prefs.setEstRangeKm(er);
                RawLog.sys(now, String.format(Locale.US,
                        "per-km %.2f (rest sample) -> est_range %d -> %d km",
                        learned2, old, er));
            } else {
                RawLog.sys(now, String.format(Locale.US,
                        "per-km learned %.2f (declined from rest), range stays %d km",
                        learned2, prefs.estRangeKm()));
            }
        }
        HudState.touch();
    }

    private void exitParked(long now) {
        parkedMode = false;
        try {
            if (lm != null && locCb != null
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f,
                        locCb, Looper.getMainLooper());
            }
        } catch (Exception ignored) {
        }
        RawLog.sys(now, "gps moving: interval -> 1000ms");
        // v1.0.7：这里**不再**清报警锚点。车被骑走的那一刻，状态机正好会从"停车"切到"骑行"，
        // 旧版在这一行就把锚点抹了 —— 等于把最该报警的场景给删了。解除条件交给 checkAlarm：
        // 蓝牙连上（人在车上）／手机以步行速度离开（人拿着手机走）。
    }

    /** 报警动作：震动 + 高优先级通知 + 语音（三样都上，戴耳机/手机在兜里都能感知到） */
    private void triggerAlarm(float distM) {
        try {
            Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                long[] pat = {0, 800, 400, 800, 400, 1500};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pat, -1));
                } else {
                    vib.vibrate(pat, -1);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "alarm vibrate failed", e);
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CH_ALARM)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("车辆异动报警")
                    .setContentText(String.format(Locale.US, "车已离开停放位置约 %.0f 米", distM))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build();
            nm.notify(ALARM_NOTIF_ID, n);
        } catch (Exception e) {
            Log.w(TAG, "alarm notify failed", e);
        }
        speak("车辆异动，请注意");
    }

    /**
     * 报警武装（v1.0.7，对标九号/小牛的"异动报警"，纯 GPS，不碰控制器）。
     *
     * 为什么要 5 分钟：停 60 秒就武装的话，等红灯、取餐都会被当成"离开车"，
     * 手一动就报，谁都会马上关掉这个功能。
     * 为什么要蓝牙断开：车通电时控制器在线，人就在旁边，没有必要防；
     * 断开着才说明是"车停着、没人管"的状态。
     */
    private void maybeArmAlarm(long now) {
        if (!prefs.alarmOn() || alarmArmed) return;
        if (!parkedMode || stationarySince <= 0L) return;
        if (now - stationarySince < 5 * 60_000L) return;
        if (kmGrowingTs > 0L && now - kmGrowingTs < 5 * 60_000L) return;
        if (HudState.connState == HudState.CONNECTED) return;
        // 只有"半小时内真的连过控制器"才武装：证明车就在身边、刚停好。
        // 手机整天没连过车，却以车速度移动 —— 那是人在坐车，不是车被偷。
        if (lastConnOkTs <= 0L || now - lastConnOkTs > 30 * 60_000L) return;
        Location a = lastLoc;
        if (a == null && lm != null
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                a = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {
            }
        }
        if (a == null) return;
        alarmAnchor = a;
        alarmArmed = true;
        alarmArmMotionTs = 0L;
        RawLog.sys(now, String.format(Locale.US, "alarm armed @%.5f,%.5f (parked %ds, ble=%d)",
                a.getLatitude(), a.getLongitude(), (now - stationarySince) / 1000, HudState.connState));
    }

    /**
     * 异动判定（v1.0.7 重做）。
     *
     * 关键认知：我们拿**手机的 GPS**去判断车有没有动。所以这个功能只在
     * 「手机留在车上/车旁」时才成立；人拿着手机走去商场，那 300 米位移是人的，不是车的。
     * 旧版在这一点上完全失守 —— 只要走出 25 米、蓝牙又是断的，20 秒后必报，
     * 骑手每进一次商场就被吓一次。
     *
     * 现在的三条出口：
     *  1. 蓝牙连上 → 车通电、人在车上 → 解除；
     *  2. 手机以**步行速度**离开锚点（<12km/h，实测骑手走路 4–5km/h）→ 判为"人走了"，
     *     悄悄解除并重新锚定，绝不响（这时也确实看不见车了）；
     *  3. 手机以**机动车速度**离开锚点（≥12km/h）并持续 15 秒 → 判为"车被骑走"→ 报警。
     * 慢推（3–5km/h 推走）分不出来，会被人走路那条吸收掉 —— 这是纯 GPS 方案的天然上限，
     * 已写进文档，不假装能防。
     */
    private void checkAlarm(long now, Location l, float moveM) {
        if (!alarmArmed || alarmAnchor == null) return;
        if (!prefs.alarmOn()) { disarm("setting turned off"); return; }
        if (l == null) return;
        double acc = l.hasAccuracy() ? l.getAccuracy() : 999;
        if (acc > 40) return;                       // 精度太差，别下判断

        float dist = alarmAnchor.distanceTo(l);
        if (dist < 20f) { alarmArmMotionTs = 0L; return; }   // 还在原地（GPS 漂移范围内）

        // 1) 车通电了 = 主人回来 / 正坐在车上，不可能是偷
        if (HudState.connState == HudState.CONNECTED) { disarm("ble connected"); return; }

        // 2) 这次位移像不像机动车：用"位移 / 距上次定位的间隔"，比 GPS 的速度字段稳
        long dt = lastLoc == null ? 0L : now - lastLoc.getTime();
        float kmh = (dt >= 300L && dt <= 15_000L && moveM > 0f)
                ? moveM / (dt / 1000f) * 3.6f : Float.NaN;
        if (!Float.isNaN(kmh) && kmh < 12f) {
            // 人拿着手机走掉了：一旦手机离车，我们就"看"不到车了 —— 直接解除，
            // 不能只是重锚。只重锚的话，等会儿他上车/坐公交（≥12km/h）还会被当成车被偷。
            RawLog.sys(now, String.format(Locale.US,
                    "alarm disarmed: phone walked away (%.0fm, %.0fkm/h)", dist, kmh));
            disarm("phone walked away");
            return;
        }

        if (now - alarmFireTs < 5 * 60_000L) return;        // 5 分钟内只报一次
        if (alarmArmMotionTs <= 0L) { alarmArmMotionTs = now; return; }
        if (now - alarmArmMotionTs < 15_000L) return;       // 连续 15 秒还在"车速度"地跑才响

        alarmFireTs = now;
        alarmArmed = false;
        alarmAnchor = null;
        alarmArmMotionTs = 0L;
        RawLog.sys(now, String.format(Locale.US,
                "ALARM: bike ridden away %.0fm @%.0fkm/h (ble=%d) -> notify+vibrate",
                dist, kmh, HudState.connState));
        triggerAlarm(dist);
    }

    private void disarm(String why) {
        if (!alarmArmed && alarmAnchor == null) return;
        alarmArmed = false;
        alarmAnchor = null;
        alarmArmMotionTs = 0L;
        RawLog.sys(System.currentTimeMillis(), "alarm disarmed: " + why);
    }

    /** 停下来时写一条"这一趟跑了多少"：里程、耗时、均速、当前电压电量（含停车时间，写清楚） */
    private void writeTripSummary(long now) {
        float km = HudState.tripKm;
        if (Float.isNaN(km) || km <= 0f) return;
        if (lastSummaryKmF >= 0f && km - lastSummaryKmF < 1.0f) return;
        lastSummaryKmF = km;
        long start = prefs.tripStart();
        // v1.0.5：时长/均速改用"运动时间"口径（停车等单不算），和码表的 AVS 一致；
        // stats 还没攒够样本时退回旧的墙钟算法，别把 summary 写成 -
        long rideSec = stats.getMovingSec();
        float avg = stats.avgKmh(km);
        if (Float.isNaN(avg) && start > 0 && now > start) {
            float minutes = (now - start) / 60000f;
            if (minutes > 1f) avg = km / (minutes / 60f);
            rideSec = (long) (minutes * 60f);
        }
        float max = stats.getMaxKmh();
        // 没数据就写 "-"，不要写出 "avg=NaNkmh" 这种既难看又无法解析的东西
        String durText = rideSec > 0 ? String.format(Locale.US, "%.0f", rideSec / 60f) : "-";
        String avgText = Float.isNaN(avg) ? "-" : String.format(Locale.US, "%.1f", avg);
        String vText = Float.isNaN(HudState.voltage) ? "-" : String.format(Locale.US, "%.1f", HudState.voltage);
        String socText = Float.isNaN(HudState.soc) ? "-" : String.format(Locale.US, "%.0f", HudState.soc);
        // 这一趟落成"上次骑行"，长按里程格的弹窗直接读它。
        // v1.0.7：只有"离上次记录跑了 ≥3km"才算一趟。否则取个餐停 60 秒就写一次，
        // 弹窗里的"上次骑行"会变成 0.4km / 2分钟，看着像坏了。
        if (km - prefs.lastTripSegKm() >= 3.0f) {
            prefs.setLastTrip(km, rideSec * 1000L, avg, max);
            prefs.setLastTripSegKm(km);
            HudState.lastTripKm = km;
            HudState.lastTripSec = rideSec;
            HudState.lastTripAvg = avg;
            HudState.lastTripMax = max;
        } else {
            RawLog.sys(now, String.format(Locale.US,
                    "trip summary kept (segment %.2fkm < 3km, not a new trip)", km - prefs.lastTripSegKm()));
        }
        RawLog.sys(now, "trip summary: km=" + String.format(Locale.US, "%.2f", km)
                + " dur=" + durText + "min avg=" + avgText + "kmh max="
                + (Float.isNaN(max) ? "-" : String.format(Locale.US, "%.1f", max))
                + " v=" + vText + " soc=" + socText
                + " wh=" + (Float.isNaN(HudState.whThisCharge) ? "-" : String.format(Locale.US, "%.0f", HudState.whThisCharge)));
    }

    private void stopGps() {
        try {
            if (lm != null && locCb != null) lm.removeUpdates(locCb);
        } catch (Exception ignored) {
        }
        locCb = null;
        // 链路重启（改设置/换引擎）后，上一次的定位基准已经过期：
        // 留着它会让"新起点"到"旧终点"之间凭空算一段里程，也会触发一次假的隧道补里程。
        lastLoc = null;
        lastGpsKm = -1f;
        trackDist.reset();
        speedStab.reset();
        HudState.gpsKmh = Float.NaN;   // 别把重启前的旧时速留在屏幕上
    }
}
