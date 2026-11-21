package com.hud.delivery;

/** 服务与界面之间的简易共享状态（免 Binder 样板代码）。服务写，界面 150ms 轮询读。 */
public final class HudState {

    public static final int IDLE = 0;
    public static final int SCANNING = 1;
    public static final int CONNECTING = 2;
    public static final int CONNECTED = 3;

    /** 电压来源 */
    public static final int SRC_NONE = 0;
    public static final int SRC_BLE = 1;
    public static final int SRC_MANUAL = 2;
    /** 手动哨点 + 里程推算出来的电压（比 SRC_MANUAL 更"活"，但仍非实测） */
    public static final int SRC_EST = 3;
    /**
     * v1.0.44：连着过、现在断了 —— 显示的是"断开前最后那一帧真读到的电量"。
     * 和 SRC_EST 分开：一个是从未测到的推算，一个是刚测到的最后读数，界面要说清楚。
     */
    public static final int SRC_FROZEN = 4;

    private static final Object LOCK = new Object();

    public static volatile int connState = IDLE;
    public static volatile String connText = "未连接";
    public static volatile String devLabel = "";

    /** 当前用于显示/算电量的电压（可能是蓝牙实时值，也可能是手动录入值） */
    public static volatile float voltage = Float.NaN;
    public static volatile int voltageSource = SRC_NONE;
    public static volatile float soc = Float.NaN;
    public static volatile float speedKmh = Float.NaN;
    public static volatile float gpsKmh = Float.NaN;
    /** v1.0.53：滤波后的 GPS 时速原值（断开控制器时显示值会被遮成 NaN，闸门要看这个） */
    public static volatile float gpsKmhRaw = Float.NaN;
    public static volatile float tripKm = 0f;
    /** 整车累计（仪表基准 + App 累计 + 本次），只用于显示，不参与任何计算（v1.0.4） */
    public static volatile float odoKm = Float.NaN;
    /**
     * v1.0.53：里程盲区次数 —— "GPS 在报 8km/h 以上、但这一秒既没有位移也没有积分"的次数。
     * 每次进盲区里程就少记一点；设置页把它摆出来，骑手不用翻日志就知道这趟漏没漏。
     */
    public static volatile int odoBlind = 0;
    /** v1.0.53：最近一次进盲区的时刻（0 = 本趟没有过），界面写"刚才 xx 前还在漏" */
    public static volatile long odoBlindTs = 0L;

    /** 手动录入电压及其时间（协议标定成功前用来先顶上） */
    public static volatile float manualV = Float.NaN;
    public static volatile long manualTs = 0L;
    /** 录入时的里程（推算的基准点） */
    public static volatile float manualKm = 0f;
    /** 最近一次实测值（推算模式下和显示值不同） */
    public static volatile float manualAnchorV = Float.NaN;
    /** 最近一次实测值对应的电量（%） */
    public static volatile float manualAnchorSoc = Float.NaN;
    /** 距离上次实测跑了多少公里 */
    public static volatile float kmSinceManual = 0f;
    /** 学到的每公里耗电（%/km，0 = 还没学到） */
    public static volatile float socPerKm = 0f;
    /** 按当前电压估的剩余里程（NaN = 不知道） */
    public static volatile float rangeKm = Float.NaN;

    /**
     * 电量是不是"按里程数"的（true）而不是"按电压查表"的（false）。
     * 骑行中电压有压降、查表会跳，所以有满电锚点时优先按里程数电；
     * 界面/日志必须标出来，别让人以为这是电压直算的结果。
     */
    public static volatile boolean socFromKm = false;
    /** 本次充电已跑（自锚点起），km */
    public static volatile float kmThisCharge = Float.NaN;
    /** 锚点电量 */
    public static volatile float anchorSoc = Float.NaN;

    /**
     * 最近一次**真的解出来**的电压和它的时刻。
     * 链路每 14–17 秒被踢一次，严格"8 秒过期"会让屏幕一半时间空白；
     * 折中：过期后大字变暗、下面标「上次 N 秒前」，既不骗人也不瞎。
     */
    public static volatile float lastGoodV = Float.NaN;
    public static volatile long lastGoodVTs = 0L;

    /** 定位是否可用（权限 + 系统定位开关）。false 时速/里程就只能是 -- */
    public static volatile boolean gpsOk = true;

    /**
     * v1.0.52：三项"运行环境"状态，**只给设置页的「运行状态」卡片用**（用户截图给我就行，不用翻日志）。
     *
     * 为什么要在界面上摆出来：上一版让他"连上车先搜日志 fgs type=" —— 那是给工程师看的，
     * 骑手不该为了一个诊断去翻 CSV。现在这三行直接摆在设置里，有问题一眼就能看见并截图。
     */
    /** 前台服务实际声明的类型（connectedDevice / connectedDevice|location） */
    public static volatile String fgsText = "";
    /** 有没有定位权限（"始终允许"=bgLocOk 也为 true） */
    public static volatile boolean locOk = false;
    public static volatile boolean bgLocOk = false;
    /** 距最近一次定位点的秒数；-1 = 本次运行还没拿到过定位 */
    public static volatile long gpsFixAgeS = -1L;

    /** 手动锚点录入后过了多久（毫秒），用来在界面上标"这个数是 N 分钟前录的" */
    public static volatile long manualAgeMs = 0L;
    /** 当前推算用的压降率（V/km）：学过的 or 兜底的，写底部说明用 */
    public static volatile float vPerKmUsed = 0f;

    public static volatile long frames = 0L;
    public static volatile long lastFrameTs = 0L;
    public static volatile int lastFrameLen = 0;
    public static volatile String lastFrameHex = "";
    public static volatile byte[] lastFrameRaw = null;
    /** 每个通道各自保留最后一帧：控制器可能同时有 FFF6 和 AE02 两路通知 */
    public static final java.util.concurrent.ConcurrentHashMap<String, byte[]> lastByTag =
            new java.util.concurrent.ConcurrentHashMap<>();
    public static volatile String note = "";
    public static volatile float rateHz = 0f;

    /**
     * 定位权限不够时给骑手看的一句话（空 = 正常）。
     *
     * v1.0.12：为什么单独做一个字段 —— 里程和时速完全依赖 GPS，权限被拒（或者系统只给了
     * "大致位置"，精度≈1km）时，HUD 上就是两个 "--" 和一根不动的里程，骑手只会觉得"坏了"，
     * 而真正的原因藏在长按才展开的 diag 行里。这条会直接显示在状态行。
     */
    public static volatile String gpsWarn = "";

    /**
     * v1.0.43：里程断档提示。
     *
     * 实车对照发现：仪表 11271 km、App 11248.9 km，差 22 km。原因不是算错，
     * 而是"那 22 km 是在 App 没运行的时候跑掉的" —— App 不在跑就没有 GPS 采样，谁也补不回来。
     * 所以不装死：startup 时若发现距上次运行超过 10 分钟，就在状态行挂一句
     * "离开 37 分钟未记录 · 长按总里程对齐"，你按一下仪表读数就归位。
     * 对齐（或本次里程清零）时清空。
     */
    public static volatile String gapWarn = "";

    /** v1.0.44：断开后"冻结读数"距今多久（毫秒），界面用来写"断开前读数（3 分钟前）" */
    public static volatile long freezeAgeMs = 0L;
    /** v1.0.44：欠压状态提示（电压掉到欠压线以下时提示"控制器随时会断电保护"），空 = 正常 */
    public static volatile String lowVoltWarn = "";

    /** 67B 帧里解出的故障位文案（"刹车·P档" 之类），空 = 无 */
    public static volatile String faultsText = "";
    /** 转把电压（0.019×f42），NaN = 没解出来 */
    public static volatile float throttleV = Float.NaN;

    /**
     * 耗尽**静置**电压（V），v1.0.53 起默认 63.8（11-19 实车耗尽后静置实测）。
     * v1.0.52 之前它是"控制器欠压点 63.0"，那是**带载**口径 —— 同一个 63.0
     * 在带载时是彻底没电、在静置时还有 15%，混用就会 66.8V 就标红。
     * 界面拿它算颜色时按"带载起点 = vEmpty − 4"来分档（见 HudScreen.voltageColor）。
     */
    public static volatile float vEmpty = 63.8f;
    /** 超速提醒阈值（km/h，0 = 关闭）。界面拿它决定时速颜色，别再写死 25/35 */
    public static volatile int speedLimitKmh = 30;
    /**
     * v1.0.53：当前这个电量百分比是按哪种口径算出来的 —— true = 停稳查电压（准），
     * false = 骑行中按里程账本推。界面上必须写出来，否则骑手看到"停车 65%、骑起来 71%"
     * 只会以为坏了（其实是两个口径）。
     */
    public static volatile boolean socAtRest = true;
    /**
     * v1.0.56：**已确认没电**（控制器低电压保护，拧油门不走）。
     *
     * 一旦立起，电量与续航锁在 0，不再由里程账本"回升"到 21%、"还能跑 19km"。
     * 只有"真充满"（自动判满）或用户手动清本次里程时才解除。
     */
    public static volatile boolean emptyLatch = false;
    /** 压降补偿后的空载电压（OCV），电量就是按它查出来的；NaN = 没有实测 */
    public static volatile float ocvV = Float.NaN;
    /** 当前大数字显示的是不是"静置电压"（v1.0.17；停车 ≥12 秒后切到静置口径） */
    public static volatile boolean voltIsOcv = false;
    /** 当前补偿了多少伏（速度越快越多，封顶 6.5V —— v1.0.53 实测 30km/h 压降中位 5.25V），底部说明用 */
    public static volatile float sagV = 0f;

    /** 链路是否已升到 HIGH 连接优先级（Nordic 库文档建议；0.9.3 起） */
    public static volatile boolean priorityHigh = false;
    /** 语音播报开着吗（设置里的开关） */
    public static volatile boolean voiceOn = false;
    /** TTS 引擎真的可用吗（没有中文包时为 false，界面显示"语音✗"，免得你以为它在播） */
    public static volatile boolean voiceReady = false;

    /**
     * 正在用的电压映射是不是"候选"（offset=2/2字节/小端/×0.05）。
     * 这个映射和 71.4V 截图吻合，但只对过一次表，界面要如实标出来。
     */
    public static volatile boolean mapCandidate = false;

    /** 帧在变、电压却一位不动 → 映射多半不是电压字段 */
    public static volatile boolean mapSuspect = false;

    /** 演示模式：数据是合成的，界面必须显式标出来，免得当真 */

    /**
     * 扫描到的设备：mac → "名字|rssi|时间"。设置页的"扫描并选择控制器"用它列出来。
     * 最多保留 60 台，超过清空重来（避免长时间扫描把内存堆满）。
     */
    public static final java.util.concurrent.ConcurrentHashMap<String, String> scanDevices =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 扫描中？界面据此显示"正在扫描…" */
    public static volatile boolean scanning = false;

    /** 相邻两个 GPS 点的球面距离累加（km）。速度字段没标定时的里程兜底 */
    public static volatile float gpsKm = Float.NaN;

    /** 当前速度是不是 GPS 给的（界面据此标"GPS"小字，别让人以为控制器报了） */
    public static volatile boolean speedFromGps = false;

    public static volatile long seq = 0L;

    private HudState() {}

    public static void touch() {
        synchronized (LOCK) {
            seq++;
        }
    }

    public static void setConn(int state, String text) {
        connState = state;
        connText = text;
        touch();
    }

    // ---------------- v1.0.33：界面模式与提醒（借鉴 DigiHUD / Speedo360） ----------------
    /** 极简模式：只留"时速 + 电量"两个超大数字（DigiHUD 的 Lite Mode，强光/雨天最省眼） */
    public static volatile boolean liteMode = false;
    /** 镜像显示：整体上下翻转，夜间把手机平放、靠挡风玻璃反射来看（DigiHUD 的 HUD Mode） */
    public static volatile boolean mirrorMode = false;
    /** 手机自身电量百分比，-1 = 未知。骑手最怕的是手机先没电，HUD 直接黑掉 */
    public static volatile int phonePct = -1;

    /** 只处理“帧相关”的东西：速度、帧内容、频率。电压/电量统一由 setPower 写。 */
    public static void onFrame(Reading r, String tag, float rateHzVal) {
        if (r != null) {
            speedKmh = r.speedKmh;
            note = r.note == null ? "" : r.note;
            if (r.raw != null) {
                lastFrameLen = r.raw.length;
                lastFrameHex = RawLog.hex(r.raw);
                lastFrameRaw = r.raw;
                lastByTag.put(tag, r.raw);
            }
        }
        rateHz = rateHzVal;
        touch();
    }

    public static void setPower(float v, float socVal, int source) {
        voltage = v;
        soc = socVal;
        voltageSource = source;
        touch();
    }

    // ---------------- 码表增强 + 能耗（v1.0.5） ----------------
    // 口径见 core/TripStats、core/EnergyBook：全是本地计算，不碰控制器。
    public static volatile float avgKmh = Float.NaN;      // 本次平均速度（运动时间口径）
    public static volatile float maxKmh = Float.NaN;      // 本次最高速度
    public static volatile long rideSec = 0L;             // 本次骑行时长（停车不计）
    public static volatile float whPerKm = Float.NaN;     // 最近 1km 平均功耗
    public static volatile float whThisCharge = Float.NaN; // 本箱电已经用掉多少 Wh

    // 上次骑行（停车那一刻的快照，供长按弹窗展示）
    public static volatile float lastTripKm = Float.NaN;
    public static volatile long lastTripSec = 0L;
    public static volatile float lastTripAvg = Float.NaN;
    public static volatile float lastTripMax = Float.NaN;

    /** 配色主题序号（0 绿 / 1 青 / 2 琥珀 / 3 白），UI 每秒读，不在 UI 线程改 */
    public static volatile int themeIdx = 0;

    // ---------------- 导航信息（v1.0.4：借高德/百度导航的通知栏文案） ----------------
    // 骑手骑行时基本都会开高德导航。高德/百度的导航通知里有"剩余 X 公里 · X 分钟"，
    // 通过通知使用权读出来显示在 HUD 底部，不用切 App 就能知道还有多远。
    // 读的是系统通知，和控制器无关；拿不到就什么都不显示。
    public static volatile String navLine = "";    public static volatile long navTs = 0L;
    public static volatile String navPkg = "";
    /**
     * v1.0.48：本机有没有给"通知使用权"。
     *
     * 这个值由服务每 30 秒查一次（Settings.Secure 的字符串查询不适合放进每帧的 Composable 里）。
     * 之前没授权时界面什么都不显示，用户只能猜"是不是 App 没做导航联动"——现在主界面会直接写一行。
     */
    public static volatile boolean navPerm = false;
    /** 导航联动提示的展示截止时刻（启动后 90 秒内提示一次"怎么开"）。0 = 不再提示 */
    public static volatile long navHintUntil = 0L;

    public static void setNav(String pkg, String line) {
        navPkg = pkg == null ? "" : pkg;
        navLine = line == null ? "" : line;
        navTs = System.currentTimeMillis();
        touch();
    }

    public static void clearNav(String pkg) {
        if (pkg == null || pkg.equals(navPkg)) {
            navLine = "";
            navTs = 0L;
            navPkg = "";
            touch();
        }
    }
}
