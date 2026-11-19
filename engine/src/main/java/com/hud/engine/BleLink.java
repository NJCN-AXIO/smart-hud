package com.hud.engine;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;

/**
 * 育宁控制器的 BLE 通道（连接管理 / 重连 / 订阅 / 数据回调）。
 *
 * 组织方式参考 moonheart/tailg-cast 的 {@code engine/BleLink.java}：
 *   一个类只管"把设备连上、把通知订上、把字节抛出去"，
 *   不解析协议、不管界面、不落盘。上层（Service）拿到 onNotify 再去解析。
 *
 * 与参考实现的差异（都是这个场景必须的）：
 *   1. 这里会有两路通知（FF0FF6 那一路和 AE02 那一路），所以订阅是"排队 + CCCD 串写"，
 *      一路写完再写下一条——BLE 的 descriptor 写必须串行。
 *   2. 直连失败 3 次自动转扫描（参考项目只扫不直连）。
 *   3. 断开后按 2 秒节奏重连，直到 stop()。
 *
 * 红线：除了把 CCCD 写成 ENABLE_NOTIFICATION，不向控制器写任何数据。
 */
public final class BleLink implements HudBle.Transport {

    private static final String TAG = "HudBleLink";

    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID SVC_FFF0 = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_FFF4 = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_FFF6 = UUID.fromString("0000fff6-0000-1000-8000-00805f9b34fb");
    public static final UUID SVC_AE00 = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_AE02 = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb");

    /** 未配置时的兜底名称关键字（保守，只认几个明显特征） */
    private static final String[] AUTO_NAME_HINTS = {
            "yuning", "育宁", "yn-", "hud", "ebike", "e-bike", "controller", "smartlock"
    };

    private static final long SCAN_TIMEOUT_MS = 12000L;
    private static final long RETRY_MS = 2000L;

    private final Context ctx;
    private final HudBle.Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Deque<BluetoothGattCharacteristic> notifyQueue = new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private boolean running;
    private int connectFails;
    private boolean readDone;
    /** true = 用户手动点"扫描"，只列设备不自动连（避免扫到别的车就扑上去） */
    private volatile boolean manualScan;

    public BleLink(Context context, HudBle.Listener listener) {
        this.ctx = context.getApplicationContext();
        this.listener = listener;
    }

    // ---------------- lifecycle ----------------

    /** mac 为空则直接扫；name 用于随机地址时匹配 */
    public void start(String mac, String name) {
        running = true;
        connectFails = 0;
        h.removeCallbacksAndMessages(null);
        scheduleConnect(200L);
    }

    public void reconnectNow() {
        if (!running) {
            start(lastMac, lastWantName);
            return;
        }
        connectFails = 0;
        closeGatt();
        stopScan();
        scheduleConnect(200L);
    }

    public void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
    }

    public HudBle.State state() {
        if (gatt != null && ready) return HudBle.State.CONNECTED;
        if (connecting) return HudBle.State.CONNECTING;
        if (scanning) return HudBle.State.SCANNING;
        return HudBle.State.IDLE;
    }

    private String lastMac = "";
    private String lastWantName = "";
    private volatile boolean ready;
    private volatile boolean connecting;
    private volatile boolean scanning;

    /**
     * 固定实例的重连任务（v1.0.17）。
     *
     * 旧写法 `h.postDelayed(this::connect, delay)` 每次调用都会生成一个新的 lambda 对象，
     * `removeCallbacks` 根本删不掉它 —— 于是"断开一次 + 手动重连一次"会叠出两条排队任务，
     * 各自再 connectGatt 一次，产生两个 GATT 对象互相打架（旧 gatt 被 close 时把新链路的回调一起带走）。
     */
    private final Runnable connectTask = this::connect;

    /** 上一次成功连上的时刻，用来判断"是不是刚连上就被抢了" */
    private volatile long connectedAtMs;

    /**
     * 被"抢"之后的退避（v1.0.17）：8s → 20s → 45s → 60s 封顶。
     *
     * 与 NordicBleLink 同一套判断：控制器是被动设备，只有打开小程序时才会被连走，
     * 所以"刚连上就断"多半是用户正在看小程序，这时候 2 秒一撞只会把小程序踢掉。
     */
    private long contentionDelayMs = 8_000L;

    private void scheduleConnect(long delayMs) {
        if (!running) return;
        h.removeCallbacks(connectTask);      // 先删掉排队中的旧任务，绝不叠加
        h.postDelayed(connectTask, delayMs);
    }

    /** 连接看门狗：20 秒还没 ready 就判定这次连接卡死，重开一条（v1.0.17） */
    private final Runnable connectWatchdog = () -> {
        if (ready) return;
        if (!connecting && gatt == null) return;
        Log.w(TAG, "connect watchdog fired: no link in 20s");
        closeGatt();
        connectFails++;
        emit(HudBle.State.IDLE, "连接超时(20s)，重连中");
        retryLater(connectFails >= 3 ? 8_000L : RETRY_MS);
    };

    // ---------------- connect ----------------

    private void connect() {
        if (!running) return;
        // v1.0.17：已经连着 / 正在连就别再开一条。旧版没有这道闸，
        // 排队任务和手动重连各来一次，就会同时存在两个 GATT，互相打断。
        if (ready || (connecting && gatt != null)) return;
        if (!btOk()) {
            emit(HudBle.State.IDLE, "缺少蓝牙权限");
            return;
        }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            emit(HudBle.State.IDLE, "无蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            emit(HudBle.State.IDLE, "蓝牙未开启");
            retryLater(4000L);
            return;
        }

        if (connectFails < 3 && !lastMac.isEmpty()) {
            try {
                BluetoothDevice dev = adapter.getRemoteDevice(lastMac);
                connectGatt(dev);
                return;
            } catch (Exception e) {
                Log.w(TAG, "bad mac " + lastMac, e);
            }
        }
        startScan(adapter);
    }

    /** 上层在设置里改了 MAC/名字就叫这个 */
    public void setTarget(String mac, String name) {
        this.lastMac = mac == null ? "" : mac.trim().toUpperCase(Locale.US);
        this.lastWantName = name == null ? "" : name.trim();
    }

    /**
     * 手动扫描：只把扫到的设备报给上层（设置页用来让用户点选），不自动连。
     * 用户点中某一台之后再调 [connectTo]。
     */
    public void scanForList() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) {
            emit(HudBle.State.IDLE, "蓝牙没开，先在系统里打开蓝牙");
            return;
        }
        manualScan = true;
        stopScan();
        startScan(a);
    }

    /** 用户在列表里点了某一台：记住它并直接连。 */
    public void connectTo(String mac, String name) {
        manualScan = false;
        stopScan();
        setTarget(mac, name);
        if (mac != null && !mac.trim().isEmpty()) {
            try {
                BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter a = bm == null ? null : bm.getAdapter();
                if (a != null) {
                    lastMac = mac.trim().toUpperCase(Locale.US);
                    connectGatt(a.getRemoteDevice(lastMac));
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "connectTo bad mac " + mac, e);
            }
        }
        connect();
    }

    private void connectGatt(BluetoothDevice dev) {
        closeGatt();
        connecting = true;
        emit(HudBle.State.CONNECTING, "连接 " + dev.getAddress());
        listener.onGatt("CONNECT", dev.getAddress());
        try {
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
            // v1.0.17：连接看门狗。旧版 connectGatt 没有超时，一旦对端不回应，
            // connecting 会永远停在 true —— 之后所有重连都被上面的闸挡住，链路就此死掉。
            h.removeCallbacks(connectWatchdog);
            h.postDelayed(connectWatchdog, 20_000L);
        } catch (Exception e) {
            Log.e(TAG, "connectGatt failed", e);
            connecting = false;
            retryLater(3000L);
        }
    }

    private void startScan(BluetoothAdapter adapter) {
        scanner = adapter.getBluetoothLeScanner();
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
            // v1.0.17：扫描超时这条回调在"已经连上"之后照样会跑，
            // 旧代码末尾无条件 retryLater(3000) → 3 秒后再 connectGatt 一次，
            // 等于在正在推送的链路上再开一条，对端会踢人（对上"连上十几秒断一次"）。
            if (ready || connecting) return;
            stopScan();
            if (manualScan) {
                manualScan = false;
                emit(HudBle.State.IDLE, "扫描结束，没找到设备");
                return;
            }
            retryLater(3000L);
        }, SCAN_TIMEOUT_MS);
    }

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

            if (manualScan) return;   // 手动扫描模式：只上报，等用户点

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
                lastMac = dev.getAddress();
                if (name != null && !name.isEmpty()) {
                    if (lastWantName.isEmpty()) lastWantName = name;
                    listener.onDevicePicked(dev.getAddress(), name);
                } else {
                    listener.onDevicePicked(dev.getAddress(), "");
                }
                connectGatt(dev);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed " + errorCode);
            scanning = false;
            retryLater(3000L);
        }
    };

    private void retryLater(long delayMs) {
        if (!running) return;
        scheduleConnect(delayMs);
    }

    private void closeGatt() {
        h.removeCallbacks(connectWatchdog);
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {
        }
        gatt = null;
        notifyQueue.clear();
        ready = false;
        connecting = false;
    }

    // ---------------- GATT ----------------

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "conn state=" + newState + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectFails = 0;
                connectedAtMs = System.currentTimeMillis();
                contentionDelayMs = 8_000L;      // v1.0.17：连上即归零
                h.removeCallbacks(connectWatchdog); // v1.0.17：连上就撤看门狗
                emit(HudBle.State.CONNECTING, "已连接，发现服务…");
                try {
                    g.discoverServices();
                } catch (Exception e) {
                    Log.e(TAG, "discover", e);
                }
            } else {
                long dur = connectedAtMs > 0 ? System.currentTimeMillis() - connectedAtMs : 0;
                connectedAtMs = 0;
                closeGatt();
                connectFails++;
                // v1.0.17：把 0x0A/8/62/133 翻成人话（和 NordicBleLink 一套口径）
                if (status == 133 || status == 8 || status == 62) {
                    emit(HudBle.State.IDLE,
                            "控制器被占用(" + status + ")：小程序/另一台手机连着它，先退掉再连");
                } else if (status == 19) {
                    emit(HudBle.State.IDLE, "对端主动断开(19)，重连中");
                } else {
                    emit(HudBle.State.IDLE, "断开(status=" + status + ")，重连中");
                }
                if (dur > 0 && dur < 5_000) {
                    // 刚连上就被抢 → 大概率是用户打开了小程序，别 2 秒一撞把它踢掉
                    long d = contentionDelayMs;
                    contentionDelayMs = Math.min(60_000L, Math.max(20_000L, contentionDelayMs * 2));
                    listener.onGatt("CONTENTION", "dropped after " + dur + "ms, back off " + (d / 1000) + "s");
                    retryLater(d);
                } else {
                    retryLater(RETRY_MS);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "discover status " + status);
                retryLater(RETRY_MS);
                return;
            }
            notifyQueue.clear();
            readDone = false;
            boolean found = false;

            for (BluetoothGattService s : g.getServices()) {
                listener.onGatt("SVC " + shortUuid(s.getUuid()), "");
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    listener.onGatt("CHR " + shortUuid(c.getUuid()),
                            "props=0x" + Integer.toHexString(c.getProperties()));
                    boolean notifiable = (c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
                    if (!notifiable) continue;
                    // 抓包线索：FFF6 是已知通道，AE02 上次没订到，优先订这两路
                    if (c.getUuid().equals(CHR_FFF6) || c.getUuid().equals(CHR_AE02)) {
                        notifyQueue.addFirst(c);
                    } else {
                        notifyQueue.addLast(c);
                    }
                    found = true;
                }
            }

            pumpNotify(null);
            try {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            } catch (Exception ignored) {
            }

            if (!found) {
                ready = true;
                emit(HudBle.State.CONNECTED, "已连接，但没有可订阅通道");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i(TAG, "cccd write " + shortUuid(d.getCharacteristic().getUuid()) + " status=" + status);
            pumpNotify(d.getCharacteristic());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            deliver(c, c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            deliver(c, value);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onGatt("READ " + shortUuid(c.getUuid()), hex(c.getValue()));
            }
            maybeReadOnce();
        }
    };

    private void deliver(BluetoothGattCharacteristic c, byte[] value) {
        if (value == null) return;
        listener.onNotify(shortUuid(c.getUuid()), value);
    }

    /** 订阅队列：一路写完 CCCD 再写下一条（BLE 描述符写必须串行） */
    private void pumpNotify(BluetoothGattCharacteristic done) {
        if (gatt == null) return;
        if (done != null && !ready) {
            // 第一条 CCCD 写成功 = 通道就绪，这一点参考实现里也是这么用的
            ready = true;
            connecting = false;
            emit(HudBle.State.CONNECTED, "通道就绪 · " + shortUuid(done.getUuid()));
        }
        BluetoothGattCharacteristic c = notifyQueue.poll();
        if (c == null) {
            maybeReadOnce();
            if (!ready) {
                ready = true;
                connecting = false;
                emit(HudBle.State.CONNECTED, "已连接（无可订阅通道）");
            }
            return;
        }
        try {
            gatt.setCharacteristicNotification(c, true);
            writeCccdOnly(c);
        } catch (Exception e) {
            Log.e(TAG, "enable notify failed", e);
            pumpNotify(null);
        }
    }

    /**
     * 全工程【唯一】的写入口，也是红线守卫。
     *
     * 只允许一件事：把 Client Characteristic Configuration Descriptor（0x2902）写成 `01 00`，
     * 这是 BLE 标准的"订阅通知"操作，写在描述符表里（控制器 BLE 栈保存"要不要推数据"这个开关），
     * 不是往控制器的指令通道发数据。
     *
     * 明确禁止：
     *   - 往 FFF5 / AE01 这类可写特征写任何字节（那是控制指令，写错可能锁死或变砖）
     *   - 除 CCCD 以外任何描述符写
     *   - writeCharacteristic（本类里根本没有这个调用，也不许加）
     *
     * 谁要往控制器发数据，必须从别的地方开新口子 —— 但那就不再是"只读"了，不要做。
     */
    private boolean writeCccdOnly(BluetoothGattCharacteristic c) {
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) {
            listener.onGatt("NO-CCCD " + shortUuid(c.getUuid()), "");
            pumpNotify(null);
            return false;
        }
        if (!CCCD.equals(d.getUuid())) {
            // 理论上到不了这儿（getDescriptor(CCCD) 已经限定），留一道保险
            listener.onGatt("WRITE-BLOCKED", shortUuid(d.getUuid()));
            pumpNotify(null);
            return false;
        }
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);   // = 01 00
        boolean ok = gatt.writeDescriptor(d);
        if (!ok) listener.onGatt("CCCD-WRITE-QUEUED-FAILED", shortUuid(c.getUuid()));
        return ok;
    }

    /** 顺手读一次 FFF4（通常是型号/版本/序列号），只读、只读一次 */
    private void maybeReadOnce() {
        if (readDone || gatt == null) return;
        readDone = true;
        try {
            for (BluetoothGattService s : gatt.getServices()) {
                if (!s.getUuid().equals(SVC_FFF0)) continue;
                BluetoothGattCharacteristic c = s.getCharacteristic(CHR_FFF4);
                if (c != null) {
                    gatt.readCharacteristic(c);
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "read fff4", e);
        }
    }

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

    public static String shortUuid(UUID u) {
        String s = u.toString().toUpperCase(Locale.US);
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805F9B34FB")) {
            return "0x" + s.substring(4, 8);
        }
        return s;
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
