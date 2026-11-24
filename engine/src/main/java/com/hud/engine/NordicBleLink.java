package com.hud.engine;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.ConnectionPriorityRequest;
import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.observer.ConnectionObserver;

/**
 * 用 Nordic 官方 Android-BLE-Library（no.nordicsemi.android:ble）实现的 BLE 通道。
 *
 * 为什么换：早期 {@link BleLink} 自己写了 GATT 回调状态机、CCCD 串行队列、133 重试、
 * 断开重连——这些正是 BLE 在安卓上最容易翻车的地方，而这个库（Nordic 官方、被大量
 * 产品用过的成熟库）已经把「请求排队、串行执行、超时、重试、服务失效」都做好了。
 *
 * 分工和早期版本完全一样：这里只负责把设备连上、把通知订上、把字节抛给上层；
 * 不解析协议、不管界面、不落盘。
 *
 * 红线（和早期版本一致，必须有）：
 *   本类只调用 {@code enableNotifications()} 让控制器开始推数据，那是 BLE 标准的
 *   「订阅」——库内部写的是 CCCD(0x2902) 描述符，不是往控制器的指令通道发数据。
 *   全程不调用 {@code writeCharacteristic()}，一个字节的控制指令都不发。
 */
public final class NordicBleLink implements HudBle.Transport {

    private static final String TAG = "HudNordicLink";

    private static final long SCAN_TIMEOUT_MS = 12_000L;
    private static final long RETRY_MS = 2_000L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    /** 直连几次之后插一次扫描（恢复被占用的 GATT 状态） */
    private static final int DIRECT_TRIES = 3;

    /** 未配置时的兜底名称关键字（和早期版本一致） */
    private static final String[] AUTO_NAME_HINTS = {
            "yuning", "育宁", "yn-", "hud", "ebike", "e-bike", "controller", "smartlock"
    };

    private final Context ctx;
    private final HudBle.Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());

    private ControllerManager manager;
    private BluetoothLeScanner scanner;
    private BluetoothDevice lastDevice;

    private boolean running;
    private volatile boolean manualScan;
    private volatile boolean ready;
    private volatile boolean connecting;
    private volatile boolean scanning;
    private int connectFails;
    /**
     * 连续直连次数。实车日志里的坑：`connectFails >= 3` 之后代码就**永远只扫描**，
     * 明明 MAC 还在、车就在旁边，却要等 12 秒扫描超时再等 3 秒才动一次，
     * 表现就是"断开以后一直转圈，得手动点一下设备才回来"。
     * 现在的策略：直连 3 次 → 扫描 1 次（刷新系统里这个设备的连接状态）→ 再直连 3 次…
     */
    private int directAttempts;
    /** 本次连接建立的时刻（用来算"这次连了多久"，实车日志靠它判断断连原因） */
    private long connectedAt;
    /** v1.0.13：本次连接尝试的开始时刻（elapsedRealtime），用来判断"连接是不是卡死了" */
    private long connectStartedAt = 0L;
    /** v1.0.13：本次连接尝试允许占用多久（快连 20s / autoConnect 95s），给"卡死"兜底 */
    private long connectBudgetMs = 20_000L;
    /**
     * 被"抢"之后的退避（v1.0.15）：8s → 20s → 45s → 60s 封顶。
     *
     * 控制器是被动设备，只有用户打开小程序时才会被连走。也就是说"被抢"
     * 通常等于"用户正在看小程序" —— 这时候去抢回来只会让他刚打开的界面卡在"连接中"。
     */
    private long contentionDelayMs = 8_000L;
    /** 重连退避：2s → 4s → 8s → 15s 封顶，别每 2 秒去撞一次把对端撞烦了 */
    private long retryDelayMs = RETRY_MS;

    private String lastMac = "";
    private String lastWantName = "";
    /** 本次连接发现的可通知特征（给 initialize 用，顺序按 FFF6/AE02 优先） */
    private final List<BluetoothGattCharacteristic> notifyTargets = new ArrayList<>();
    /**
     * 是否同时订阅 AE02（实验开关，默认关）。
     *
     * 旧假设（已推翻）：订了 AE02 会把控制器切到 67B 模式，不订就是小程序那套 20B。
     * 实测（14:11–14:56 那趟，3504 帧）：勾着 AE02 时照样全是 67B，AE02 一条通知都没来。
     * 结论：订阅组合不影响帧格式（67B 里也没有电压）；这个开关只用于复现对比实验。
     */
    private volatile boolean experimentalAe02 = false;

    public void setExperimentalAe02(boolean on) {
        this.experimentalAe02 = on;
    }
    /** 顺手读一次 FFF4 的标记（型号/版本，只读） */
    private boolean readDone;

    public NordicBleLink(Context context, HudBle.Listener listener) {
        this.ctx = context.getApplicationContext();
        this.listener = listener;
    }

    // ---------------- HudBle.Transport ----------------

    @Override
    public void setTarget(String mac, String name) {
        this.lastMac = mac == null ? "" : mac.trim().toUpperCase(Locale.US);
        this.lastWantName = name == null ? "" : name.trim();
    }

    /**
     * 重连任务本身固定成一个 Runnable（v1.0.13）。
     * 以前每次都现场 new 一个 lambda，`removeCallbacks` 根本删不掉 ——
     * 于是 start()/reconnectNow()/stop() 之后，旧队列里的 connect 还会照常跑一次，
     * 造成"两条并发的连接尝试"，对端只会认一条，另一条把链路踢掉。
     */
    private final Runnable connectTask = this::connect;

    @Override
    public void start(String mac, String name) {
        if (mac != null && !mac.trim().isEmpty()) {
            lastMac = mac.trim().toUpperCase(Locale.US);
        }
        if (name != null) {
            lastWantName = name.trim();
        }
        running = true;
        connectFails = 0;
        directAttempts = 0;
        retryDelayMs = RETRY_MS;
        contentionDelayMs = 8_000L;
        h.removeCallbacksAndMessages(null);
        h.postDelayed(connectTask, 200L);
    }

    @Override
    public void reconnectNow() {
        if (!running) {
            start(lastMac, lastWantName);
            return;
        }
        connectFails = 0;
        directAttempts = 0;
        retryDelayMs = RETRY_MS;
        contentionDelayMs = 8_000L;    // v1.0.15：用户手动点"重连"就是要马上抢，不用等
        h.removeCallbacks(connectTask);          // v1.0.13：清掉排队中的旧重连，别叠加
        closeManager();
        stopScan();
        h.postDelayed(connectTask, 200L);
    }

    @Override
    public void stop() {
        running = false;
        h.removeCallbacks(connectTask);
        h.removeCallbacksAndMessages(null);
        stopKeepAlive();
        stopScan();
        closeManager();
    }

    @Override
    public HudBle.State state() {
        if (manager != null && ready) return HudBle.State.CONNECTED;
        if (connecting) return HudBle.State.CONNECTING;
        if (scanning) return HudBle.State.SCANNING;
        return HudBle.State.IDLE;
    }

    @Override
    public void scanForList() {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            emit(HudBle.State.IDLE, "蓝牙没开，先在系统里打开蓝牙");
            return;
        }
        manualScan = true;
        stopScan();
        startScan(a);
    }

    @Override
    public void connectTo(String mac, String name) {
        manualScan = false;
        stopScan();
        setTarget(mac, name);
        if (mac != null && !mac.trim().isEmpty()) {
            try {
                BluetoothAdapter a = adapter();
                if (a != null) {
                    BluetoothDevice dev = a.getRemoteDevice(mac.trim().toUpperCase(Locale.US));
                    lastDevice = dev;
                    connectDevice(dev);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "connectTo bad mac " + mac, e);
            }
        }
        connect();
    }

    // ---------------- connect ----------------

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private void connect() {
        if (!running) return;
        // v1.0.13：**已经连上就绝不再去碰它**。
        // 实测症状：链路是好的，但每隔十几秒就"断开→回连"一次。根因是下面扫描的
        // 12 秒超时回调在**连接成功之后依然会跑**，它末尾又排了一次 retryLater(3000)，
        // 3 秒后这里就会对一个已在流的 GATT 再发起一次 connect ——
        // 库会把它当成"重连"，对端则直接把这个连接踢掉。于是形成"连上 → 12 秒 → 被踢 → 回连"。
        if (ready && manager != null) return;
        // 也挡掉并发：正在连的时候再来一次，会多建一个 GATT（Android 的 GATT 客户端是有限资源）
        // 但"卡住"的连接不能永久挡住重试：超过 20 秒（连接超时是 15 秒）就当作已失效
        if (connecting && SystemClock.elapsedRealtime() - connectStartedAt < connectBudgetMs) return;
        if (!btOk()) {
            emit(HudBle.State.IDLE, "缺少蓝牙权限");
            return;
        }
        BluetoothAdapter a = adapter();
        if (a == null) {
            emit(HudBle.State.IDLE, "无蓝牙适配器");
            return;
        }
        if (!a.isEnabled()) {
            emit(HudBle.State.IDLE, "蓝牙未开启");
            retryLater(4000L);
            return;
        }

        // 直连 → 扫描 交替：有 MAC 时先直连（快），连撞 3 次才扫一次（扫描能刷新系统里的
        // 连接状态，比"一直撞一个已经被占用的 GATT"更容易恢复）。扫完不管成没成，回到直连，
        // 不要像老逻辑那样"失败 3 次以后永远只扫描"。
        if (!lastMac.isEmpty() && directAttempts < DIRECT_TRIES) {
            try {
                lastDevice = a.getRemoteDevice(lastMac);
                directAttempts++;
                connectDevice(lastDevice);
                return;
            } catch (Exception e) {
                Log.w(TAG, "bad mac " + lastMac, e);
            }
        }
        startScan(a);
    }

    @SuppressLint("MissingPermission")
    private void connectDevice(BluetoothDevice dev) {
        if (!running || dev == null) return;
        // 同一个设备、manager 还在 → 复用同一个 GATT 直接重连，
        // 不销毁重建（实测每次断开都新建一遍，连接风暴更严重）
        BluetoothDevice prev = lastDevice;
        boolean reuse = manager != null && prev != null && prev.getAddress().equals(dev.getAddress());
        if (!reuse) {
            closeManager();
            manager = new ControllerManager(this);
            manager.setConnectionObserver(connectionObserver);
        } else {
            readDone = false;
            notifyTargets.clear();
        }
        lastDevice = dev;
        connecting = true;
        connectStartedAt = SystemClock.elapsedRealtime();   // v1.0.13：给"连接卡死"兜底
        ready = false;
        connectedAt = 0;
        listener.onGatt(reuse ? "RECONNECT" : "CONNECT", dev.getAddress());
        emit(HudBle.State.CONNECTING, (reuse ? "回连 " : "连接 ") + dev.getAddress() + " · Nordic 库");

        // 第一次连用"快连"；**连续连不上**才用系统的 autoConnect。
        // v1.0.1 修：原来只要 connectFails>0 就用 autoConnect，而对端每 14–17 秒主动踢一次，
        // 于是每次"踢掉→回连"都走 autoConnect —— 那是系统后台连接，不受我们 15 秒超时约束，
        // 实际表现就是"车在旁边也要等十几秒才回来"，还会被系统按省电策略拖长。
        // 对端主动断开（链路本来建立过）应当直接快连，只有反复连不上才交给系统。
        final boolean auto = connectFails >= 2;
        // v1.0.13：**autoConnect 绝对不能配 15 秒超时**。
        // 实测日志（raw_20261117_221542.csv）里是一串
        //   DISCONNECTED reason=10 afterMs=0   (17s → 19s → 57s → 33s → 31s)
        // reason=10 = HCI 0x0A "Connection Accept Timeout" —— 控制器压根没应答连接请求
        // （它那边还残留着上一条链路的"已连接"状态，暂时不广播/不接受）。
        // autoConnect 的意义在于"把连接挂起，等它重新可连时由系统自动完成"，
        // 而 15 秒的 timeout 会在它变可连之前就把请求取消掉 —— 于是每次都在 0x0A 上白撞一次。
        // 换成 90 秒：够它把旧链路收干净，也仍然不会无限挂着。
        final long connectTimeout = auto ? 90_000L : CONNECT_TIMEOUT_MS;
        connectBudgetMs = auto ? 95_000L : 20_000L;   // v1.0.13：并发判定要和实际超时对齐
        manager.connect(dev)
                .retry(3, 100)
                .timeout(connectTimeout)
                .useAutoConnect(auto)
                .done(device -> {
                    connectFails = 0;
                    directAttempts = 0;
                    retryDelayMs = RETRY_MS;
                    contentionDelayMs = 8_000L;      // v1.0.15：连上了就把"被抢退避"归零
                    listener.onGatt("CONNECTED", device.getAddress());
                })
                .fail((device, status) -> {
                    connecting = false;
                    ready = false;
                    connectFails++;
                    if (status == 133) {
                        // 这个场景里最常见的原因：微信小程序还占着连接
                        // v1.0.14：用户实测确认控制器**单连接独占**，所以这条提示是主因而不是特例
                        emit(HudBle.State.IDLE, "控制器被占用(133)：小程序/另一台手机连着它，先退掉再连");
                    } else if (status == 10 || status == 8 || status == 62) {
                        // 0x0A 连接请求没应答 / 超时 / 建立失败 —— 单连接约束下同样多半是"别人占着"
                        emit(HudBle.State.IDLE, "连不上(0x" + Integer.toHexString(status)
                                + ")：控制器可能被小程序/另一台手机占着");
                    } else {
                        emit(HudBle.State.IDLE, "连接失败(status=" + status + ")，重连中");
                    }
                    // 不要在库自己的回调里直接 close：投递到我们自己的 handler，避开重入。
                    // 133 之后控制器和手机两边都要一点时间把旧的 GATT 收干净，2 秒就去撞
                    // 只会继续吃 133；连续撞不上就一路退避（4→8→16→24s），别把电和连接次数耗光。
                    // 成功连上后 done() 会把 retryDelayMs 复位。
                    h.post(() -> {
                        closeManager();
                        retryLater(status == 133 ? nextRetryDelayFor133() : nextRetryDelay());
                    });
                })
                .enqueue();
    }

    private void retryLater(long delayMs) {
        if (!running) return;
        if (ready && manager != null) return;      // v1.0.13：已连上就不要排重连
        h.postDelayed(connectTask, delayMs);
    }

    @SuppressLint("MissingPermission")
    private void closeManager() {
        stopKeepAlive();
        ControllerManager m = manager;
        manager = null;
        ready = false;
        connecting = false;
        readDone = false;
        lastDiagReadMs = 0L;
        notifyTargets.clear();
        if (m != null) {
            try {
                m.disconnect();
            } catch (Exception ignored) {
            }
            try {
                m.close();     // 释放 GATT 客户端，避免"连上又连不上"的残留
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------- 扫描 ----------------

    @SuppressLint("MissingPermission")
    private void startScan(BluetoothAdapter a) {
        // v1.0.36：先停掉可能还在跑的那一轮。
        // connect() 在"直连 3 次失败"之后进这里，而上一轮扫描的超时回调可能还没到点；
        // 同一回调重复 startScan 在部分机型上直接回 SCAN_FAILED_ALREADY_STARTED，
        // 之后 scanning 一直是 true，state() 永远显示"扫描中"，看起来就是卡死。
        stopScan();
        scanner = a.getBluetoothLeScanner();
        if (scanner == null) return;
        scanning = true;
        emit(HudBle.State.SCANNING, "扫描附近设备…");
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCb);
        } catch (Exception e) {
            Log.e(TAG, "startScan failed", e);
            scanning = false;
            return;
        }
        h.postDelayed(() -> {
            stopScan();
            // v1.0.13：只有"还没连上、也没在连"才继续排队重试。
            // 否则这次扫描的收尾会在连接成功之后变成一次多余的 connect（见 connect() 顶部注释）。
            if (!running || ready || connecting) return;
            // 扫完（不管有没有扫到）都回到"直连优先"，别陷在扫描循环里
            directAttempts = 0;
            if (manualScan) {
                manualScan = false;
                emit(HudBle.State.IDLE, "扫描结束，没找到设备");
                return;
            }
            retryLater(3000L);
        }, SCAN_TIMEOUT_MS);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) {
        }
        scanning = false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            if (dev == null) return;
            String name = null;
            try {
                name = dev.getName();
            } catch (Exception ignored) {
            }
            if (name == null && result.getScanRecord() != null) {
                try {
                    name = result.getScanRecord().getDeviceName();
                } catch (Exception ignored) {
                }
            }
            listener.onScanDevice(dev.getAddress(), name == null ? "" : name, result.getRssi());

            if (manualScan) return;   // 手动扫描：只上报，等用户点

            boolean hit = !lastMac.isEmpty() && dev.getAddress().equalsIgnoreCase(lastMac);
            String lname = name == null ? "" : name.toLowerCase(Locale.US);
            if (!hit && !lastWantName.isEmpty() && lname.contains(lastWantName.toLowerCase(Locale.US))) {
                hit = true;
            }
            if (!hit && lastMac.isEmpty() && lastWantName.isEmpty()) {
                for (String k : AUTO_NAME_HINTS) {
                    if (lname.contains(k)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                stopScan();
                connectFails = 0;
                directAttempts = 0;
                lastMac = dev.getAddress();
                listener.onDevicePicked(dev.getAddress(), name == null ? "" : name);
                connectDevice(dev);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed " + errorCode);
            scanning = false;
            retryLater(3000L);
        }
    };

    // ---------------- 连接观察 ----------------

    private final ConnectionObserver connectionObserver = new ConnectionObserver() {
        @Override
        public void onDeviceConnecting(@NonNull BluetoothDevice device) {
            connecting = true;
            emit(HudBle.State.CONNECTING, "连接中 " + device.getAddress());
        }

        @Override
        public void onDeviceConnected(@NonNull BluetoothDevice device) {
            emit(HudBle.State.CONNECTING, "已连接，发现服务…");
        }

        @Override
        public void onDeviceFailedToConnect(@NonNull BluetoothDevice device, int reason) {
            // 具体文案在 ConnectRequest.fail() 里出，这里不重复
            Log.w(TAG, "failed to connect, reason=" + reason);
        }

        @Override
        public void onDeviceReady(@NonNull BluetoothDevice device) {
            ready = true;
            connecting = false;
            connectedAt = System.currentTimeMillis();
            lastNotifyMs = 0L;              // 每条新连接重新判断"数据有没有在流"
            keepAliveIdleLogged = false;
            emit(HudBle.State.CONNECTED,
                    notifyTargets.isEmpty() ? "已连接（无通知通道）" : "通道就绪 · Nordic 库");
            if (manager != null) manager.raisePriority();
            startKeepAlive();
        }

        @Override
        public void onDeviceDisconnecting(@NonNull BluetoothDevice device) {
        }

        @Override
        public void onDeviceDisconnected(@NonNull BluetoothDevice device, int reason) {
            ready = false;
            connecting = false;
            stopKeepAlive();
            // 记清楚"这次连了多久"：实车日志显示每次都是 14–17 秒就断，
            // 有这行才判断得出是控制器到点主动踢、还是我们这边的问题
            long dur = connectedAt > 0 ? (System.currentTimeMillis() - connectedAt) : 0;
            connectedAt = 0;
            listener.onGatt("DISCONNECTED", "reason=" + reason + " afterMs=" + dur);
            if (!running) return;
            emit(HudBle.State.IDLE, "断开(" + reasonText(reason) + ")，自动回连");
            // v1.0.14：**用户实测：控制器同一时刻只允许一条 BLE 连接** ——
            // App 连着时小程序连不上，小程序连着时 App 连不上（两种方向都会互相踢）。
            // 所以"刚连上 5 秒内就被断"极可能是别人（小程序/另一台手机）在抢，
            // 这时候 1.5 秒猛回连只会反过来把对方的连接撞掉，两边都连不成。
            final boolean stolenQuickly = dur > 0 && dur < 5_000L;
            h.post(() -> {
                // v1.0.1：链路本来建立过（connectedAt>0）→ 这是对端主动踢，下一次用快连；
                // 只有"连都没连上"才累积失败次数并走 autoConnect。
                if (dur > 0) {
                    connectFails = 0;
                } else {
                    connectFails++;
                }
                if (stolenQuickly) {
                    // v1.0.15：控制器是**被动设备**，不会主动去连谁 ——
                    // 只有你自己打开小程序时，它才会被小程序连走。也就是说：
                    // 这时候的"断开"几乎一定是"你正在看小程序"。
                    // 所以绝对不能 8 秒就去抢回来（那会在你刚看到电量时把小程序踢掉，
                    // 小程序界面就一直转"连接中"）。改成阶梯退避：8s → 20s → 45s → 60s 封顶，
                    // 你用完关掉小程序，我们最多 1 分钟内自然接回来。
                    long d = contentionDelayMs;
                    contentionDelayMs = Math.min(60_000L, Math.max(20_000L, contentionDelayMs * 2));
                    listener.onGatt("CONTENTION", "link dropped after " + dur
                            + "ms (likely 小程序), back off " + (d / 1000) + "s");
                    emit(HudBle.State.IDLE, "控制器被小程序占着 · " + (d / 1000) + " 秒后再试");
                    retryLater(d);
                    return;
                }
                // 对端主动关（reason=0）：控制器通常马上又开始广播，1.5 秒后立刻回连，
                // 别用长退避——那会把"每 20 秒里有 9 秒采不到数据"拖成 14 秒
                retryLater(reason == 0 ? 1500L : nextRetryDelay());
            });
        }
    };

    /** 退避：2s → 4s → 8s → 15s 封顶 */
    private long nextRetryDelay() {
        long d = retryDelayMs;
        retryDelayMs = Math.min(15_000L, Math.max(RETRY_MS, retryDelayMs * 2));
        return d;
    }

    /**
     * 断连原因说人话（v1.0.13）。
     *
     * 这些都是蓝牙规范里的 HCI/GATT 错误码，日志里只有一个数字时根本判断不了
     * "是车自己踢的、还是我们这边的问题"。实测 221542 那份日志里连着一串 `reason=10`，
     * 当时完全看不出是什么意思 —— 查下来是 0x0A Connection Accept Timeout（控制器没应答）。
     */
    private static String reasonText(int r) {
        switch (r) {
            case 0:   return "0 正常关闭";
            case 8:   return "8 连接超时(信号弱)";
            case 10:  return "10(0x0A) 控制器没应答连接请求";
            case 19:  return "19 对端主动断开(控制器踢的)";
            case 22:  return "22 本机主动断开";
            case 62:  return "62 连接建立失败";
            case 133: return "133 GATT 错误(常见:小程序占用)";
            default:  return String.valueOf(r);
        }
    }

    /**
     * 133 专用退避：4s → 8s → 16s → 24s 封顶。
     *
     * 实车日志里 133 是连着来的（一次"133 风暴"几十条），固定 4 秒重撞只会换来下一串 133，
     * 还把连接次数和电量都烧掉。这里跟普通退避共用 [retryDelayMs] 这一个计数器，
     * 但起点是 4 秒、封顶 24 秒；连上以后 done() 会归零。
     */
    private long nextRetryDelayFor133() {
        long d = Math.max(4000L, Math.min(24_000L, retryDelayMs));
        retryDelayMs = Math.min(24_000L, Math.max(4000L, d * 2));
        return d;
    }

    // ---------------- 读保活（只读，不发写包） ----------------

    /**
     * 实车日志显示：每次连上约 14 秒就被对端关闭（reason=0），而小程序那次活了 45 秒以上。
     * 一个只读就能验证的假设：控制器的"看门狗"是不是**任何 ATT 往来**都会重置？
     * 如果是，隔 3 秒读一次可读特征（2A00 设备名这类无害特征）就能把连接吊住；
     * 如果必须收到写包才认，那读了也没用 —— 这个实验零风险，值得先试。
     */
    private static final long KEEPALIVE_MS = 3000L;
    /** 申请的最大 MTU：67B 帧 + 3B 头一包放下（Android 上限就是 517，247 是常见安全值） */
    private static final int MTU_REQUEST = 247;
    /** 链路还在但连续这么久没有通知 → 先原地重订阅（比整链重连便宜得多） */
    private static final long SILENT_RESUBSCRIBE_MS = 8000L;
    private long lastResubMs;
    private BluetoothGattCharacteristic keepAliveChar;
    private boolean keepAliveLogged;
    private boolean keepAliveIdleLogged;
    /** 最近一次收到通知的时间：数据在流的时候根本不需要再读保活 */
    private long lastNotifyMs;

    private final Runnable keepAliveTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            ControllerManager m = manager;
            if (!ready || m == null) return;
            long now = SystemClock.elapsedRealtime();
            // 静默自愈（v1.0.1）：链路 up 但 8 秒没数据 → 先重订一次通知，别急着拆链路。
            // 20 秒的看门狗留给上层（BleService），那条会整条重连；这里是最便宜的第一次尝试。
            if (ready && m != null && lastNotifyMs > 0 && now - lastNotifyMs > SILENT_RESUBSCRIBE_MS
                    && now - lastResubMs > 20_000L) {
                lastResubMs = now;
                listener.onGatt("SILENT", "no notify for " + ((now - lastNotifyMs) / 1000) + "s, resubscribe");
                m.resubscribe();
            }
            // 自适应：控制器本来就在 2Hz 推数据时，这条 3 秒一次的读纯属白耗射频与电量，
            // 还可能和通知挤同一个连接事件。只有"静默 > 2 秒"才真的需要它把链路吊住。
            if (lastNotifyMs > 0 && now - lastNotifyMs < 2000L) {
                if (!keepAliveIdleLogged) {
                    keepAliveIdleLogged = true;
                    listener.onGatt("KEEPALIVE-IDLE", "data flowing, skip ping");
                }
            } else {
                m.pingRead(keepAliveChar);
            }
            // v1.0.60：每 15 秒只读重一次 FFF5/FFF7，抓随车速/里程变化的快照
            diagReadPeriodic();
            h.postDelayed(this, KEEPALIVE_MS);
        }
    };

    /** 挑一个"读它绝对不会改变任何设置"的特征：优先 2A00（标准设备名），其次 FFF4 */
    private void startKeepAlive() {
        h.removeCallbacks(keepAliveTask);
        keepAliveChar = null;
        for (BluetoothGattCharacteristic c : allScanned) {
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) continue;
            if (c.getUuid().equals(UUID_SVC_GAP_NAME)) { keepAliveChar = c; break; }
            if (keepAliveChar == null) keepAliveChar = c;
        }
        if (keepAliveChar == null) {
            listener.onGatt("KEEPALIVE-SKIP", "no readable characteristic");
            return;
        }
        if (!keepAliveLogged) {
            keepAliveLogged = true;
            listener.onGatt("KEEPALIVE", "read " + BleLink.shortUuid(keepAliveChar.getUuid())
                    + " every " + KEEPALIVE_MS + "ms");
        }
        h.postDelayed(keepAliveTask, KEEPALIVE_MS);
    }

    private void stopKeepAlive() {
        h.removeCallbacks(keepAliveTask);
    }

    /** 标准 GAP 设备名特征 0x2A00（Service 0x1800） */
    private static final UUID UUID_SVC_GAP_NAME =
            UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB");
    /** 育宁控制器的工程/型号特征 0xFFF4（只读） */
    private static final UUID UUID_FFF4 =
            UUID.fromString("0000FFF4-0000-1000-8000-00805F9B34FB");
    /** v1.0.60 只读探索：FFF5（READ/WRITE/NOTIFY）、FFF7（READ/WRITE_NO_RESP） */
    private static final UUID UUID_FFF5 =
            UUID.fromString("0000FFF5-0000-1000-8000-00805F9B34FB");
    private static final UUID UUID_FFF7 =
            UUID.fromString("0000FFF7-0000-1000-8000-00805F9B34FB");
    /** 周期性重读只读特征的间隔（毫秒）：抓一次会随车速/里程变化的快照 */
    private static final long DIAG_READ_PERIOD_MS = 15_000L;
    private long lastDiagReadMs;

    // ---------------- BleManager 子类 ----------------

    /**
     * 育宁控制器的 BleManager：只做三件事——记下服务、订阅通知、读一次 FFF4。
     *
     * 不要求"某个服务必须有"：这台控制器上 FFF0 和 AE00 两套服务，哪套有数据还没验证完，
     * 所以找到几路就订几路，一路都没有也算连上（只是没数据）。
     */
    private static final class ControllerManager extends BleManager {

        private final NordicBleLink owner;

        ControllerManager(NordicBleLink owner) {
            super(owner.ctx);
            this.owner = owner;
        }

        @Override
        public int getMinLogPriority() {
            return Log.DEBUG;
        }

        @Override
        public void log(int priority, @NonNull String message) {
            Log.println(priority, TAG, message);
        }

        @Override
        protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
            owner.collectServices(gatt);
            // 永远返回 true：这台车能订几路就订几路，缺服务不该导致"连不上"
            return true;
        }

        @Override
        protected void initialize() {
            // 先申请 MTU（v1.0.1）：67B 的帧在默认 MTU=23 下只能 20B 一块地到，
            // 中间掉一块，f[66]==0xCC 的整帧校验就不过 → 那一帧白丢。
            // 申请 247 之后一帧一个包，丢帧率明显降；MTU 交换是 ATT 链路层协商，
            // 不往控制器的指令通道写任何字节。
            requestMtu(MTU_REQUEST)
                    .with((device, mtu) -> owner.listener.onGatt("MTU", String.valueOf(mtu)))
                    .fail((device, status) -> owner.listener.onGatt("MTU-FAIL", "status=" + status))
                    .enqueue();

            // 订阅 + 读一次型号：这些是 protected 方法，只能在这里调（不能在外部类里调）
            for (BluetoothGattCharacteristic c : owner.notifyTargets) {
                subscribe(c);
            }
            owner.readInfoOnce(this);
        }

        /** 订阅一路通知（库内部只写 CCCD 0x2902，不写特征值） */
        void subscribe(BluetoothGattCharacteristic c) {
            setNotificationCallback(c).with((device, data) -> {
                if (data != null) owner.deliver(c.getUuid(), data);
            });
            enableNotifications(c)
                    .fail((device, status) -> owner.listener.onGatt(
                            "SUBSCRIBE-FAIL " + BleLink.shortUuid(c.getUuid()), "status=" + status))
                    .enqueue();
        }

        /**
         * 重新订阅（v1.0.1）：安卓上有个经典毛病——链路还在，但通知队列卡住，收不到数据。
         * 老版本只能等 20 秒看门狗把整条链路拆了重连；现在先原地把 CCCD 再写一遍
         * （仍然只是写描述符，不碰控制器的指令通道），通常一次就能恢复。
         */
        void resubscribe() {
            for (BluetoothGattCharacteristic c : owner.notifyTargets) {
                subscribe(c);
            }
            owner.listener.onGatt("RESUBSCRIBE", "n=" + owner.notifyTargets.size());
        }

        /**
         * 把连接参数提到 HIGH（约 11–15ms 间隔）。
         *
         * 依据：Nordic 官方库文档明确写"高频通知建议申请更高连接优先级以降低延迟"；
         * 社区（Android BLE 长连接实践）同样建议由前台服务在连接就绪后主动申请，
         * 避免系统给到 30–50ms 的省电间隔导致 2Hz 通知被挤压、偶发堆积。
         * 这是纯本地链路参数申请，不改控制器任何状态，符合只读红线。
         */
        void raisePriority() {
            try {
                // v1.0.1：改成 BALANCED。2Hz × 67B ≈ 134 B/s，BALANCED（30–50ms 间隔）
                // 的吞吐是这个量的十倍以上，够用；HIGH（11–15ms）只会多耗射频与电量，
                // 而且有的控制器收到参数更新请求会直接断链 —— 这正是"断得勤"的嫌疑之一。
                requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_BALANCED)
                        .done(device -> owner.listener.onGatt("PRIORITY", "BALANCED"))
                        .fail((device, status) -> owner.listener.onGatt("PRIORITY-FAIL", "status=" + status))
                        .enqueue();
            } catch (Exception e) {
                Log.w(TAG, "priority request failed", e);
            }
        }

        /** 读一次 FFF4（型号/版本），只读 */
        void readOnce(BluetoothGattCharacteristic c) {
            readCharacteristic(c)
                    .with((device, data) -> owner.listener.onGatt(
                            "READ " + BleLink.shortUuid(c.getUuid()),
                            data == null ? "" : BleLink.hex(data.getValue())))
                    .fail((device, status) -> owner.listener.onGatt(
                            "READ-FAIL " + BleLink.shortUuid(c.getUuid()), "status=" + status))
                    .enqueue();
        }

        /**
         * 静默读（保活用）：只发 READ 请求重置对端的空闲计时，不往日志里写值。
         * 读操作本身不会改变控制器任何状态，符合"只读红线"。
         */
        void pingRead(BluetoothGattCharacteristic c) {
            if (c == null) return;
            try {
                readCharacteristic(c).enqueue();
            } catch (Exception ignored) {
            }
        }

        @Override
        protected void onServicesInvalidated() {
            owner.notifyTargets.clear();
        }
    }

    /** 服务发现完成：记日志、按 FFF6/AE02 优先排好可通知特征 */
    @SuppressLint("MissingPermission")
    private void collectServices(BluetoothGatt gatt) {
        notifyTargets.clear();
        allScanned.clear();
        List<BluetoothGattCharacteristic> pre = new ArrayList<>();
        List<BluetoothGattCharacteristic> post = new ArrayList<>();
        for (BluetoothGattService s : gatt.getServices()) {
            listener.onGatt("SVC " + BleLink.shortUuid(s.getUuid()), "");
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                allScanned.add(c);
                listener.onGatt("CHR " + BleLink.shortUuid(c.getUuid()),
                        "props=0x" + Integer.toHexString(c.getProperties()));
                boolean notifiable = (c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
                if (!notifiable) continue;
                if (c.getUuid().equals(BleLink.CHR_FFF6) || c.getUuid().equals(BleLink.CHR_AE02)) {
                    pre.add(c);
                } else {
                    post.add(c);
                }
            }
        }
        notifyTargets.addAll(pre);
        notifyTargets.addAll(post);

        // ---------- 默认只订 FFF6；AE02 只在实验开关打开时才订 ----------
        // 历史结论已修订：
        //   抓包（微信小程序）CCCD 只写过 FFF6 → 收到 20 字节（其实是 67B 包在 MTU 23 下的前缀）
        //   实车 14:11 那趟：AE02 勾着、3504 帧仍全 67B、AE02 零通知
        // 所以"写 AE02 的 CCCD 会切帧格式"这个假设是错的；这里剔除 AE02 只是照小程序的做法，
        // 少一路空订阅，不影响帧内容。
        if (!experimentalAe02) {
            for (int i = notifyTargets.size() - 1; i >= 0; i--) {
                if (notifyTargets.get(i).getUuid().equals(BleLink.CHR_AE02)) {
                    notifyTargets.remove(i);
                    listener.onGatt("SUBSCRIBE-SKIP 0xAE02", "experimental off (does not change frame format)");
                }
            }
        }
        Log.i(TAG, "notifiable channels=" + notifyTargets.size() + " / total chr=" + allScanned.size());
        StringBuilder plan = new StringBuilder();
        for (BluetoothGattCharacteristic c : notifyTargets) {
            if (plan.length() > 0) plan.append(" > ");
            plan.append(BleLink.shortUuid(c.getUuid()));
        }
        listener.onGatt("SUBSCRIBE-PLAN", plan.toString());
    }

    /**
     * 订阅由 {@link ControllerManager#initialize()} 完成（那是库规定的初始化钩子，
     * 库会在 connect 的 done 回调前把这一串请求跑完）。这里只负责投递数据。
     */
    private void deliver(UUID uuid, Data data) {
        byte[] value = data.getValue();
        if (value == null) return;
        lastNotifyMs = SystemClock.elapsedRealtime();
        listener.onNotify(BleLink.shortUuid(uuid), value);
    }

    /**
     * 连上后把所有带 READ 属性的特征各读一次（纯读，不写特征值）。
     * v1.0.60：旧版只读 GAP 名 + FFF4；这次把 FFF5/FFF6/FFF7 等可读特征也纳入，
     * 看控制器有没有在 READ 快照里直接带速度/里程/电量。值全 hex 落 GAT 日志。
     */
    private void readInfoOnce(ControllerManager m) {
        if (readDone) return;
        readDone = true;
        int n = 0;
        for (BluetoothGattCharacteristic c : allScanned) {
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) continue;
            m.readOnce(c);
            n++;
        }
        listener.onGatt("READ-PLAN", "readable=" + n);
    }

    /** 周期重读 FFF5/FFF7（只读）：对比两次读值有没有随车速/里程变化 */
    private void diagReadPeriodic() {
        ControllerManager m = manager;
        if (m == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastDiagReadMs < DIAG_READ_PERIOD_MS) return;
        lastDiagReadMs = now;
        for (UUID u : new UUID[]{UUID_FFF5, UUID_FFF7}) {
            BluetoothGattCharacteristic c = findReadable(u);
            if (c != null) m.readOnce(c);
        }
    }

    private BluetoothGattCharacteristic findReadable(UUID u) {
        for (BluetoothGattCharacteristic c : allScanned) {
            if (c.getUuid().equals(u)
                    && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                return c;
            }
        }
        return null;
    }

    /** 服务发现时抓到的全部特征（读 FFF4 用，和「要订阅的」分开存） */
    private final List<BluetoothGattCharacteristic> allScanned = new ArrayList<>();

    // ---------------- utils ----------------

    private void emit(HudBle.State s, String text) {
        if (listener != null) listener.onState(s, text);
    }

    private boolean hasPerm(String p) {
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean btOk() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
                    && hasPerm(Manifest.permission.BLUETOOTH_SCAN);
        }
        return true;
    }
}
