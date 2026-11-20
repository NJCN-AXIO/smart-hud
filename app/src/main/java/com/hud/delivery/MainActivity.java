package com.hud.delivery;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.hud.delivery.ui.HudToast;

import androidx.activity.ComponentActivity;

import com.hud.core.SocModel;
import com.hud.core.VoltDecay;
import com.hud.delivery.ui.ComposeHud;
import com.hud.delivery.ui.HudCallbacks;
import com.hud.protocol.FieldMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity implements HudView.Listener, HudCallbacks {

    private static final int REQ_PERMS = 2001;

    private HudView hud;
    private Prefs prefs;
    private AlertDialog settingsDialog;
    private final Handler h = new Handler(Looper.getMainLooper());

    // ---- 自动亮度：正午太阳下屏幕看不清、夜里又刺眼，用环境光跟着调 ----
    private SensorManager sensorMgr;
    private Sensor lightSensor;
    private float lastBrightness = -1f;
    /** v1.0.36：回到前台时的补救重连节流（10 秒内只插一次） */
    private long lastResumeReconnectMs = 0L;
    /** 最近一次读到的环境光（手动切档时要用它恢复自动档） */
    private float lastLux = -1f;
    private final SensorEventListener lightCb = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            applyBrightness(e.values != null && e.values.length > 0 ? e.values[0] : -1f);
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (hud != null) hud.refresh();
            h.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        // v1.0.24：把"闪退"也记进日志。
        // 用户没有 adb/logcat，装机后一崩就什么都拿不到（v1.0.23 的电压按钮闪退就是这么白丢的）。
        // 装一个默认异常处理器：崩之前把完整堆栈写进 raw_*.csv，导出日志就能直接看到原因。
        installCrashLogger();
        // v1.0.33：先把显示模式从设置里读出来，服务还没起来时界面也不会闪回默认布局
        HudState.liteMode = prefs.lite();
        HudState.mirrorMode = prefs.mirror();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (prefs.uiCompose()) {
            // 默认：Compose 三个大数字（长按顶部状态栏可切回自绘界面）
            ComposeHud.attach(this, this);
        } else {
            buildCanvasUi();
        }
        showSystemBars();

        ensurePermissions();
        h.postDelayed(this::maybeShowFirstRunTip, 600);
        // v1.0.40：崩溃不再"只进日志"。上一次崩过就在界面上摊开，点一下复制就能发给我 ——
        // 用户不用装 adb、也不用去文件管理器里翻 raw_*.csv。
        h.postDelayed(this::maybeShowCrashReport, 900);
    }

    /** v1.0.41：版本的唯一读法 —— 设置页显示、崩溃上报、日志抬头都用它，避免三处各写一套 */
    private String versionLine() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode;
            return "v" + pi.versionName + " · vc" + vc;
        } catch (Throwable t) {
            return "未知版本";
        }
    }

    /** 上一次运行崩溃过？直接摊在界面上（同一条只弹一次） */
    private void maybeShowCrashReport() {
        if (prefs == null || isFinishing()) return;
        String c = RawLog.lastCrash(this);
        if (c == null || c.equals(prefs.lastCrashSeen())) return;
        prefs.setLastCrashSeen(c);
        PanelUi ui = PanelUi.card(this, "上次运行崩了一次", "已经写进日志 · 点「复制」发给我就行");
        ui.body(c);
        ui.primary("复制这段文字", v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", c));
                    HudToast.show(this, "已复制到剪贴板");
                }
            } catch (Throwable ignored) {
            }
            ui.dismiss();
        });
        ui.secondary("知道了", v -> ui.dismiss());
        ui.show();
    }

    /**
     * 状态栏留着（v1.0.2）。
     *
     * 之前的沉浸式全屏把状态栏一起藏了，骑车时看不到时间、也看不到微信/订单通知，
     * 实用价值反而下降。现在：状态栏和导航栏都显示，颜色跟 HUD 背景一致（#07090C），
     * 内容由系统自动让出状态栏高度（setDecorFitsSystemWindows(true)），不会被挡。
     * 防误触改由三按钮条 + 长按来实现，不靠隐藏系统栏。
     */
    private void showSystemBars() {
        try {
            Window w = getWindow();
            w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                w.setDecorFitsSystemWindows(true);
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.show(WindowInsets.Type.systemBars());
                    // 深色背景 + 浅色图标（不要黑色图标，会在黑底上看不见）
                    c.setSystemBarsAppearance(0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            w.setStatusBarColor(0xFF07090C);
            w.setNavigationBarColor(0xFF07090C);
        } catch (Exception ignored) {
        }
    }

    /**
     * 首次上手引导：只弹一次。
     * 现场经验是"装上不知道怎么连"比"功能不够"更卡人，所以把三步直接摊开。
     */
    private void maybeShowFirstRunTip() {
        if (prefs == null || prefs.tipShown() || isFinishing()) return;
        prefs.setTipShown(true);
        // v1.0.39：换成和三个面板同一套的深色卡片（原来这里是系统 AlertDialog，白底蓝按钮）
        PanelUi ui = PanelUi.card(this, "第一次上车，就三步", "连上之后不用再管别的事");
        ui.body("1. 点「现在扫描」，找到名字里带 YN / 育宁 / 一长串数字的那台，点一下就连上，"
                + "MAC 会记住，之后通电自动连。\n\n"
                + "2. 连上后电压、电量、时速都由控制器和 GPS 直接读，不需要再填任何字段、也不需要标定。\n\n"
                + "3. 把这台手机的系统「自启动 / 后台高耗电」放行本 App，否则息屏十几分钟后蓝牙会被系统掐掉"
                + "（表现就是“骑一会儿断开，回来要等它重连”）。\n\n"
                + "首页：长按「本次里程」清零，长按「总里程」填车上仪表的数字一键对齐。");
        ui.primary("知道了", v -> ui.dismiss());
        ui.secondary("现在扫描", v -> { ui.dismiss(); showDevicePicker(); });
        ui.show();
    }

    // ---------------- 扫描并选择控制器 ----------------

    private AlertDialog scanDialog;   // 已不再使用（v1.0.39 改 PanelUi 卡片），保留字段避免其它引用报错
    private PanelUi scanUi;
    private boolean scanOpen;

    private void showDevicePicker() {
        HudState.scanDevices.clear();
        Intent i = new Intent(this, BleService.class);
        i.setAction("scan");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);

        // v1.0.39：系统 AlertDialog + 裸 Button 换成深色卡片，和三个面板同一套外观
        scanUi = PanelUi.cardTall(this, "连接控制器", "扫描 12 秒 · 点名字就连上，之后自动连", 0.62f);
        scanOpen = true;
        scanUi.dialog.setOnDismissListener(d -> scanOpen = false);
        scanUi.show();
        refreshScanList(0);
    }

    /** 每 0.7 秒重建一次列表：扫描是流式的，设备会一个个冒出来 */
    private void refreshScanList(int round) {
        if (!scanOpen || scanUi == null || round > 25) return;
        scanUi.clearContent();
        scanUi.hintLine(HudState.scanDevices.isEmpty()
                ? "正在扫描…（12 秒内没结果就把车通电、离手机近一点，再点一次扫描）"
                : "扫到 " + HudState.scanDevices.size() + " 台，点名字就连：");

        for (java.util.Map.Entry<String, String> e : HudState.scanDevices.entrySet()) {
            String mac = e.getKey();
            String[] f = e.getValue().split("\\|");
            String name = f.length > 0 ? f[0] : "";
            String rssi = f.length > 1 ? f[1] : "?";
            scanUi.listItem((name.isEmpty() ? "(无名设备)" : name) + "   " + mac + "   " + rssi + "dBm",
                    v -> {
                        prefs.setMac(mac);
                        if (!name.isEmpty()) prefs.setDevName(name);
                        HudState.devLabel = name.isEmpty() ? mac : name;
                        Intent c = new Intent(this, BleService.class);
                        c.setAction("connect");
                        c.putExtra("mac", mac);
                        c.putExtra("name", name);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(c);
                        else startService(c);
                        HudToast.show(this, "正在连 " + (name.isEmpty() ? mac : name));
                        if (scanUi != null) scanUi.dismiss();
                    });
        }
        h.postDelayed(() -> refreshScanList(round + 1), 700);
    }

    /** 原来的自绘界面，保留为备选（设置里可切回来）。 */
    private void buildCanvasUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        hud = new HudView(this);
        hud.setListener(this);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button btnV = smallButton("电压");
        Button btnMark = smallButton("标记");
        Button btnSet = smallButton("设置");
        bar.addView(btnV);
        bar.addView(btnMark);
        bar.addView(btnSet);
        FrameLayout.LayoutParams lpb = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpb.setMargins(0, 0, 0, dp(14));
        root.addView(bar, lpb);

        btnV.setOnClickListener(v -> showManualVoltageDialog());
        btnMark.setOnClickListener(v -> showMarkDialog());
        btnSet.setOnClickListener(v -> showSettingsDialog());

        setContentView(root);
        h.post(refresh);
    }

    // ---------------- Compose 界面的回调 ----------------

    @Override public void onManual() { showManualVoltageDialog(); }
    @Override public void onMark() { showMarkDialog(); }
    @Override public void onSettings() { showSettingsDialog(); }
    @Override public void onOdo() { showOdoAlignDialog(); }

    @Override
    public void onToggleLite() {
        // v1.0.33：左右滑动切极简模式 —— 立刻生效，同时记进设置（下次启动还是它）
        HudState.liteMode = !HudState.liteMode;
        prefs.setLite(HudState.liteMode);
        HudState.touch();
        HudToast.show(this, HudState.liteMode ? "极简模式（时速 + 电量）" : "标准模式");
    }
    @Override public void onResetTrip() { onLongPressReset(); }

    @Override
    public void onRetry() {
        // 幂等：服务活着就什么都不发生，死了就靠这一下拉起来；模式不一致由服务的 tick 自愈
        startServiceNow();
        HudToast.show(this, "正在重新启动采集服务…");
    }

    @Override
    public void onToggleUi() {
        prefs.setUiCompose(!prefs.uiCompose());
        HudToast.show(this, prefs.uiCompose() ? "切换到 Compose 界面" : "切换到自绘界面");
        recreate();
    }

    /** 标记弹窗里用的大按钮：骑车时戴手套也能一次点准 */
    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18f);
        b.setAllCaps(false);
        b.setMinHeight(dp(64));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setAlpha(0.72f);
        b.setMinWidth(dp(64));
        b.setMinHeight(dp(40));
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v1.0.49：界面每次回到前台都记一笔。用户报"开几分钟闪退回桌面"，
        // 日志里如果有 pause 但没有 destroy，就说明是别的 App 抢走/被系统收起；
        // 一条 resume 都没有就直接进下一份日志，才是真崩。没有这行就只能靠猜。
        RawLog.sys(System.currentTimeMillis(), "ui resume");
        h.removeCallbacks(refresh);
        startAutoBrightness();
        // v1.0.36：回到界面时如果还没连上，立刻插一次重连。
        // 原来的"被小程序抢走 → 8/20/45/60 秒阶梯退避"是为了不跟小程序抢，
        // 但用户离开小程序、回到这个 App 时，退避可能还剩几十秒 —— 屏幕上就一直是"连接中"。
        // 人回到前台本身就是"我现在要它"的信号，这时抢一次是安全的；10 秒内不重复，免得来回跳。
        if (System.currentTimeMillis() - lastResumeReconnectMs > 10_000L
                && HudState.connState != HudState.CONNECTED) {
            lastResumeReconnectMs = System.currentTimeMillis();
            try {
                startService(new Intent(this, BleService.class).setAction("reconnect"));
            } catch (Throwable ignored) {
            }
        }
        // Compose 界面自己轮询 HudState，不用这个自绘刷新循环
        if (prefs != null && prefs.uiCompose()) return;
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RawLog.sys(System.currentTimeMillis(), "ui pause");
        h.removeCallbacks(refresh);
        stopAutoBrightness();
    }

    @Override
    protected void onDestroy() {
        RawLog.sys(System.currentTimeMillis(), "ui destroy");
        super.onDestroy();
    }

    /**
     * 自动亮度：HUD 装在车把上，屏幕亮度是"看得见 / 看不见"的分界。
     * 正午太阳下需要满亮，夜里满亮会晃眼——所以读环境光，按 lux 分档。
     * 没有光线传感器（或读不到）就固定 0.85，别让屏幕卡在很暗的状态。
     */
    private void startAutoBrightness() {
        applyBrightness(-1f);                 // 先给一个安全值，传感器就位后再覆盖
        try {
            if (sensorMgr == null) sensorMgr = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sensorMgr == null) return;
            if (lightSensor == null) lightSensor = sensorMgr.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (lightSensor == null) return;
            sensorMgr.registerListener(lightCb, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception ignored) {
        }
    }

    private void stopAutoBrightness() {
        try {
            if (sensorMgr != null && lightCb != null) sensorMgr.unregisterListener(lightCb);
        } catch (Exception ignored) {
        }
    }

    /**
     * lux < 10 → 0.35（夜里）；< 200 → 0.6；< 2000 → 0.85；强光 → 1.0
     *
     * v1.0.5：多了手动档（0 自动 / 1 常亮 / 2 常暗），双击顶部就能切。
     * 手动档直接盖掉环境光 —— 有些车的挡风板会把光线传感器挡住，
     * 自动档会误判成"夜里"然后把屏幕压得很暗，手动档是给这种情况的兜底。
     */
    private void applyBrightness(float lux) {
        if (lux >= 0f) lastLux = lux;
        float target;
        int mode = prefs == null ? 0 : prefs.brightMode();
        if (mode == 1) target = 1.0f;
        else if (mode == 2) target = 0.25f;
        else if (lux < 0f) target = 0.85f;
        else if (lux < 10f) target = 0.35f;
        else if (lux < 200f) target = 0.6f;
        else if (lux < 2000f) target = 0.85f;
        else target = 1.0f;
        // 变化太小就不动窗口（避免传感器每 0.1 lux 抖一次都去改 Window 参数）
        if (Math.abs(target - lastBrightness) < 0.03f) return;
        lastBrightness = target;
        try {
            Window w = getWindow();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = target;
            w.setAttributes(lp);
        } catch (Exception ignored) {
        }
    }

    /**
     * 双击顶部切亮度档（v1.0.5，对标 FarDriver 独立仪表的 DIM 键）。
     * 骑车时不想翻设置页，抬手点两下就换档；Toast 只说一句当前档位。
     */
    @Override
    public void onCycleBright() {
        int next = ((prefs == null ? 0 : prefs.brightMode()) + 1) % 3;
        if (prefs != null) prefs.setBrightMode(next);
        lastBrightness = -1f;   // 强制立刻生效，别被"变化太小"的门槛挡掉
        applyBrightness(lastLux);
        String[] names = {"自动亮度", "常亮", "常暗"};
        HudToast.show(this, "亮度：" + names[next]);
    }

    // ---------------- 权限 ----------------

    private void ensurePermissions() {
        List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            want.add(Manifest.permission.BLUETOOTH_SCAN);
            want.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        want.add(Manifest.permission.ACCESS_COARSE_LOCATION);   // v1.0.12：必须一起请求，见 Manifest 注释
        want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            want.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> missing = new ArrayList<>();
        for (String p : want) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            startServiceNow();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        boolean bluetoothOk = true;
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            String p = permissions[i];
            if ((p.equals(Manifest.permission.BLUETOOTH_SCAN) || p.equals(Manifest.permission.BLUETOOTH_CONNECT))
                    && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                bluetoothOk = false;
            }
        }
        if (bluetoothOk) {
            startServiceNow();
        } else {
            HudToast.show(this, "没有蓝牙权限，HUD 无法读取控制器");
        }
        // v1.0.12：定位被拒/只给"大致位置"时立刻提示，不等服务起来再说
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            HudState.gpsWarn = coarse
                    ? "定位只有“大致位置”→ 时速/里程会失真"
                    : "没给定位权限 → 里程和时速不可用";
            HudToast.show(this, coarse
                            ? "现在只有“大致位置”权限：时速和里程会不准。请在系统设置里改成“精确位置”。"
                            : "没有定位权限：里程和时速不可用（电压/电量仍正常）。");
            HudState.touch();
        } else {
            HudState.gpsWarn = "";
        }
    }

    private void startServiceNow() {
        Intent i = new Intent(this, BleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    // ---------------- 长按清零 ----------------

    /** 秒 → "1小时12分" / "42分"；骑手要的是这个，不是 "01:12:07" */
    private static String dur(long sec) {
        if (sec <= 0) return "-";
        long h = sec / 3600, m = (sec % 3600) / 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d小时%d分", h, m);
        return String.format(java.util.Locale.US, "%d分", Math.max(1, m));
    }

    @Override
    public void onLongPressReset() {
        float km = HudState.tripKm;
        float acc = prefs.odoAccumKm();
        // v1.0.5：这里从"只问要不要清零"升级成"本次骑行统计"（码表口径），
        // 清零按钮还在，但先让人看到这一趟跑了多久、多快、多费电。
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US, "本次 %.1f km · 骑行 %s\n", km, dur(HudState.rideSec)));
        sb.append("平均 ").append(Float.isNaN(HudState.avgKmh) ? "--"
                        : String.format(java.util.Locale.US, "%.0f", HudState.avgKmh))
                .append(" km/h · 最高 ").append(Float.isNaN(HudState.maxKmh) ? "--"
                        : String.format(java.util.Locale.US, "%.0f", HudState.maxKmh)).append(" km/h");
        if (!Float.isNaN(HudState.whPerKm) || !Float.isNaN(HudState.whThisCharge)) {
            sb.append("\n");
            if (!Float.isNaN(HudState.whPerKm)) {
                sb.append(String.format(java.util.Locale.US, "最近 1km %.1f Wh/km", HudState.whPerKm));
            }
            if (!Float.isNaN(HudState.whThisCharge)) {
                sb.append(String.format(java.util.Locale.US, " · 本箱电已用 %.0f Wh", HudState.whThisCharge));
            }
        }
        if (!Float.isNaN(HudState.lastTripKm) && !Float.isNaN(HudState.lastTripAvg)) {
            sb.append(String.format(java.util.Locale.US, "\n\n上次骑行 %.1f km · %s · 均速 %.0f km/h",
                    HudState.lastTripKm, dur(HudState.lastTripSec), HudState.lastTripAvg));
        }
        sb.append(String.format(java.util.Locale.US,
                "\n\n清零后本次会累加进整车累计（≈ %.0f km），只清「本次」，不影响蓝牙。",
                prefs.odoBaseKm() + acc + Math.max(0f, km)));
        // v1.0.39：这块也换成深色卡片（原来是系统 AlertDialog：白底、系统蓝按钮）
        PanelUi ui = PanelUi.card(this, "本次骑行", "清零只清「本次」，不影响蓝牙和总里程");
        ui.body(sb.toString());
        ui.primary("清零本次里程", v -> {
                    // v1.0.4：先把本次累进整车累计，再归零（服务侧也有一道，防重复累加）
                    // v1.0.49：km / acc 是"弹窗打开那一刻"的快照。弹窗开着还在骑车时，
                    // 这段里程就既没进累计、又被 -- 归零吞掉了（总里程会比仪表越差越多）。
                    // 落盘前重新读一次实时值，用真正清零前的那一瞬。
                    float kmNow = HudState.tripKm;
                    if (Float.isNaN(kmNow)) kmNow = 0f;
                    float accNow = prefs.odoAccumKm();
                    if (kmNow > 0.1f) {
                        prefs.setOdoAccumKm(accNow + kmNow);
                        RawLog.sys(System.currentTimeMillis(), String.format(java.util.Locale.US,
                                "odo accumulate +%.2fkm -> %.2fkm (whole bike %.0fkm)",
                                kmNow, accNow + kmNow, prefs.odoBaseKm() + accNow + kmNow));
                    }
                    prefs.setTripKm(0f);
                    prefs.setTripStart(System.currentTimeMillis());
                    HudState.tripKm = 0f;
                    // 里程归零后，把电压哨点的基准也跟着挪过来，推算才不跑偏
                    if (!Float.isNaN(HudState.manualV)) {
                        prefs.setManualKm(0f);
                        HudState.manualKm = 0f;
                    }
                    // 关键：服务内存里还有旧的里程积分器，不通知它就会在下一秒把旧值写回来
                    //（旧版本"清零后又弹回 19km"就是这个原因）
                    Intent reset = new Intent(BleService.ACTION_TRIP_RESET);
                    reset.setPackage(getPackageName());
                    // v1.0.49：告诉服务"累计已经折过了"。旧版没这个标记，服务收到广播时
                    // 若刚好在 tick 里把 HudState.tripKm 写回旧值，就会再折一次 → 总里程多出一整趟。
                    reset.putExtra(BleService.EXTRA_ALREADY_FOLDED, true);
                    sendBroadcast(reset);
                    HudState.touch();
                    RawLog.sys(System.currentTimeMillis(), "trip reset by user");
                    ui.dismiss();
                });
        ui.secondary("取消", v -> ui.dismiss());
        ui.show();
    }

    // ---------------- 对齐整车累计（v1.0.25） ----------------
    //
    // 车上的里程是轮速计出来的，长期比 GPS 准。用户要求把总里程挂到主界面，
    // 边跑边和仪表对；对不上就长按那一行，输入仪表读数，立刻对齐。
    // 这里把"差多少"一并写进日志 —— 攒几趟就能看出 GPS 里程是系统性偏多还是偏少，
    // 那个差值才是里程系数（现在 1.6）真正该用的依据。

    /**
     * 让"整车里程"显示值正好等于 target。
     * 显示值 = 基准(整) + App累计(浮点) + 本次里程，所以反推出基准/累计即可；
     * 只动这两个账本，不碰本次里程、电量、续航。
     * 设置页和主屏长按共用这一个入口 —— 早前两处各写一套含义，才会出现"改这个那个就变"。
     */
    private void alignOdoTo(float target, String why) {
        // v1.0.53：对齐的同时**学习比例系数** —— 这是"校准完又跑偏"的解药。
        // 两次校准之间：仪表走了 ΔD、App 走了 ΔA，比值就是这台车 GPS 与车轮的真实比例。
        // 只有基线够长（≥5km）才学，避免刚校准完又校准一次把系数拉飞。
        float prevDash = prefs.odoCalDash();
        float prevApp = prefs.odoCalApp();
        float oldApp = HudState.odoKm;
        if (!Float.isNaN(prevDash) && !Float.isNaN(prevApp) && !Float.isNaN(oldApp)) {
            float dDash = target - prevDash;
            float dApp = oldApp - prevApp;
            if (dDash >= 5f && dApp >= 5f) {
                float ratio = dDash / dApp;
                float oldScale = prefs.odoScale();
                float newScale = oldScale * ratio;
                // v1.0.53：单次最多调 4%，区间 0.75~1.35。
                // 11-19 实测"仪表比 GPS 多算 ~30%"，但这个差里可能还夹着"App 被系统停了一段"
                // 的漏记 —— 一次就把系数拉满，会把漏记当成比例，下一趟反而记过头。
                // 每次只挪一步，校准两三次自然收敛；万一某次是漏记，误差也只吃一次。
                float lo = Math.max(0.75f, oldScale * 0.96f);
                float hi = Math.min(1.35f, oldScale * 1.04f);
                newScale = Math.min(hi, Math.max(lo, newScale));
                prefs.setOdoScale(newScale);
                RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                        "ODO_SCALE learn: dash +%.2f vs app +%.2f -> ratio %.4f, scale %.4f -> %.4f",
                        dDash, dApp, ratio, oldScale, newScale));
            }
        }
        float trip = Float.isNaN(HudState.tripKm) ? 0f : HudState.tripKm;
        float old = HudState.odoKm;
        int base = prefs.odoBaseKm();
        float accum = target - base - trip;
        if (accum < 0f) {
            // 填的数比"基准+本次"还小：说明基准也得往下调，且累计清零后仍要有小数余量
            base = Math.max(0, (int) Math.floor(target - trip));
            accum = Math.max(0f, target - base - trip);
        }
        prefs.setOdoBaseKm(base);
        prefs.setOdoAccumKm(accum);
        HudState.odoKm = base + accum + trip;
        // 记下这次校准点，下次校准才有 Δ 可算
        prefs.setOdoCalDash(target);
        prefs.setOdoCalApp(HudState.odoKm);
        prefs.setOdoCalTs(System.currentTimeMillis());
        HudState.gapWarn = "";      // v1.0.43：对齐完成，断档提示撤掉
        HudState.touch();
        float delta = Float.isNaN(old) ? 0f : (target - old);
        RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                "ODO_ALIGN(%s) app %.1f -> %.1f (meter) delta %+.2fkm base=%d accum=%.2f trip=%.2f",
                why, old, target, delta, base, accum, trip));
    }

    private void showOdoAlignDialog() {
        PanelUi ui = PanelUi.open(this, "对齐总里程", "长按触发的：填车上仪表现在显示的数字");

        LinearLayout now = ui.section("当前");
        ui.row(now, "App 记录", "基准 + App 累计 + 本次",
                ui.value(Float.isNaN(HudState.odoKm) ? "--"
                        : String.format(Locale.US, "%.1f km", HudState.odoKm), true));
        ui.row(now, "本次里程", "对齐时会算进基准，不会重复加",
                ui.value(String.format(Locale.US, "%.2f km", HudState.tripKm), false));

        LinearLayout in = ui.section("填仪表读数");
        EditText et = ui.input(Float.isNaN(HudState.odoKm) ? ""
                : String.format(Locale.US, "%.1f", HudState.odoKm), true);
        ui.row(in, "仪表显示 (km)", "照车上那个数填，整数就行", et);
        ui.note(in, "对齐只改整车里程的起点，不影响本次里程、电量和续航。\n"
                + "v1.0.53：每对一次表，App 会自动学一次「仪表 / GPS」的比例（单次最多调 4%），"
                + "跑几趟后就基本不用再手动对。");

        ui.primary("对齐", v -> {
            float target = parseFloat(et.getText().toString(), Float.NaN);
            if (Float.isNaN(target) || target < 0f || target > 999999f) {
                HudToast.show(this, "填一个 0–999999 之间的读数");
                return;
            }
            alignOdoTo(target, "main");
            HudToast.show(this, String.format(Locale.US, "已对齐到 %.1f km", target));
            ui.dismiss();
        });
        ui.secondary("关闭", v -> ui.dismiss());
        ui.show();
    }

    // ---------------- 电压面板（v1.0.23 重做） ----------------
    //
    // 旧版把「录入电压 / 设满电 / 自报续航 / 按已用电量校准」四件事塞在一个弹窗里，
    // 全是小字和长说明。现在只留「录入 + 看账本」两件事，续航相关全部搬到设置页，
    // 按 Material 3 深色 BottomSheet 的样子重排。

    private void showManualVoltageDialog() {
        VoltBook book = new VoltBook(this);
        SocModel model = new SocModel(prefs.vFull(), prefs.vEmpty());

        PanelUi ui = PanelUi.open(this, "电压", "和小程序对一次，之后按里程自己走");

        LinearLayout now = ui.section("此刻");
        ui.row(now, "带载电压", "骑行中读数，通常比静置低 1–5V",
                ui.value(v1(HudState.voltage), false));
        ui.row(now, "静置电压", "停稳 45 秒后的口径，和小程序同一把尺",
                ui.value(v1(HudState.ocvV), true));
        ui.row(now, "显示电量", null, ui.value(p0(HudState.soc), true));

        LinearLayout in = ui.section("录入一个小程序读数");
        float seed = !Float.isNaN(HudState.ocvV) ? HudState.ocvV : HudState.voltage;
        EditText et = ui.input(Float.isNaN(seed) ? "" : String.format(Locale.US, "%.1f", seed), true);
        ui.row(in, "整包电压 (V)", "照小程序上那个数填", et);

        VoltBook.Entry last = book.last();
        String lastTxt = last == null
                ? "还没录过。录一次等于打一个哨点，未来按里程往下推。"
                // 🔴 v1.0.23 闪退根因：分钟数是 long，却用了 %.0f —— Java 会抛
                // IllegalFormatConversionException，一点「电压」就闪退。改用 %d。
                : String.format(Locale.US, "上次：%.1fV（约 %.0f%%）@ %.1fkm · %d 分钟前",
                        last.v, model.soc(last.v), last.km,
                        Math.max(0L, (System.currentTimeMillis() - last.ts) / 60000L));
        ui.note(in, lastTxt);

        LinearLayout learn = ui.section("自学习");
        float per = prefs.socPerKm();
        int n = prefs.socPerKmN();
        ui.row(learn, "每公里耗电", n > 0 ? n + " 段样本" : "样本还不够，多跑几趟",
                ui.value(per > 0.001f ? String.format(Locale.US, "%.2f%%", per) : "--", false));
        ui.row(learn, "满电续航", "设置里的兜底值，学到实测会被取代",
                ui.value(prefs.estRangeKm() + " km", false));
        ui.row(learn, "还能跑", "按当前电量与耗电率估算",
                ui.value(Float.isNaN(HudState.rangeKm) ? "--"
                        : String.format(Locale.US, "%.0f km", HudState.rangeKm), true));

        ui.primary("保存这一个电压", v -> {
            float val = parseFloat(et.getText().toString(), Float.NaN);
            if (Float.isNaN(val) || val < 30f || val > 100f) {
                HudToast.show(this, "电压要在 30–100V 之间");
                return;
            }
            long t = System.currentTimeMillis();
            float km = HudState.tripKm;
            // 先用旧锚点学一次压降率，再覆盖新锚点（这才是他自己这块电池的曲线）
            float oldV = prefs.manualV();
            float oldKm = prefs.manualKm();
            float learnedK = VoltDecay.learnFromAnchors(oldV, oldKm, val, km);
            if (!Float.isNaN(learnedK)) {
                prefs.setVPerKm(learnedK);
                RawLog.sys(t, String.format(Locale.US,
                        "learned vPerKm=%.3f from %.1fV@%.1fkm -> %.1fV@%.1fkm",
                        learnedK, oldV, oldKm, val, km));
            }
            prefs.setManualV(val);
            prefs.setManualTs(t);
            prefs.setManualKm(km);
            HudState.manualV = val;
            HudState.manualTs = t;
            HudState.manualKm = km;
            boolean learned = book.record(t, val, km, model);
            HudState.touch();
            RawLog.sys(t, String.format(Locale.US, "manual voltage %.1fV at %.1fkm", val, km));
            HudToast.show(this, learned
                    ? String.format(Locale.US, "已记录 %.1fV，耗电速度更新为每公里 %.1f%%", val, prefs.socPerKm())
                    : String.format(Locale.US, "已记录 %.1fV（%.1fkm）", val, km));
            ui.dismiss();
        });
        ui.secondary("清掉手动值", v -> {
            prefs.clearManual();
            HudState.manualV = Float.NaN;
            HudState.manualTs = 0L;
            HudState.touch();
            RawLog.sys(System.currentTimeMillis(), "manual voltage cleared");
            ui.dismiss();
        });
        ui.show();
    }

    // ---------------- 标记面板（v1.0.23 重做） ----------------
    //
    // 旧版是为"字段求解器"服务的：要手点 ±0.1/±1、点一堆预设、还要手填时速，
    // 协议早就解出来了，这些全是负担。现在只保留一个作用：
    // 一键把「此刻界面上的读数」写进日志，方便和我对着截图核同一秒。

    private void showMarkDialog() {
        PanelUi ui = PanelUi.openBare(this, "标记", "一键把此刻读数写进日志，方便对着截图核对");

        LinearLayout now = ui.section("此刻读数");
        ui.row(now, "电压", "带载口径", ui.value(v1(HudState.voltage), true));
        ui.row(now, "静置口径", "停稳 45 秒后", ui.value(v1(HudState.ocvV), false));
        ui.row(now, "电量", null, ui.value(p0(HudState.soc), true));
        ui.row(now, "本次里程", null,
                ui.value(String.format(Locale.US, "%.1f km", HudState.tripKm), false));
        ui.row(now, "时速", "GPS 口径", ui.value(
                Float.isNaN(HudState.gpsKmh) ? "--"
                        : String.format(Locale.US, "%.0f km/h", HudState.gpsKmh), false));
        ui.note(now, "按下按钮会往日志写一行 MARK：时间、以上全部数值、\n"
                + "整车累计、电压来源、最近一帧的通道名。\n"
                + "我拿到日志就能和你截的图对上同一秒，不用你再记任何东西。");

        Button mark = ui.inlineButton(now, "记一笔");
        mark.setTextColor(PanelUi.ACC_INK);
        mark.setBackgroundColor(0x00000000);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(PanelUi.ACC);
        bg.setCornerRadius(PanelUi.dp(this, 14));
        mark.setBackground(bg);
        mark.setTypeface(mark.getTypeface(), android.graphics.Typeface.BOLD);
        mark.setOnClickListener(v -> {
            long t = System.currentTimeMillis();
            String ch = HudState.lastByTag.isEmpty() ? "-" : HudState.lastByTag.keySet().iterator().next();
            RawLog.sys(t, String.format(Locale.US,
                    "MARK v=%s ocv=%s soc=%s km=%.2f odo=%s spd=%s src=%d ch=%s",
                    v1(HudState.voltage), v1(HudState.ocvV), p0(HudState.soc),
                    HudState.tripKm, n1(HudState.odoKm), v1(HudState.gpsKmh),
                    HudState.voltageSource, ch));
            HudToast.show(this, "已记入日志（含全部读数）");
            ui.dismiss();
        });
        ui.show();
    }

    private static String v1(float v) {
        return (Float.isNaN(v) || Float.isInfinite(v)) ? "--"
                : String.format(Locale.US, "%.1f V", v);
    }

    private static String p0(float v) {
        return (Float.isNaN(v) || Float.isInfinite(v)) ? "--"
                : String.format(Locale.US, "%.0f%%", v);
    }

    private static String n1(float v) {
        return (Float.isNaN(v) || Float.isInfinite(v)) ? "--"
                : String.format(Locale.US, "%.1f", v);
    }

    // ---------------- 设置 ----------------


    // 从旧版保留：导出日志 / 后台保活两段原本夹在「标记」和「设置」之间，
    // v1.0.23 重排面板时被误删，已放回（clampV 随旧标记面板一起删除）。

    /**
     * 导出原始帧日志：把 logs 目录下**所有** raw_*.csv 合并成一份再发出去。
     *
     * 为什么合并：每启动一次 App 会新开一个 raw_时间戳.csv，
     * 所以跑十几公里那次的帧很可能分散在好几个文件里；只发"最新那个"容易发出去一个刚开的空文件。
     *
     * App 自己的目录普通文件管理器点不进去（Android 11+ 限制），所以走 FileProvider 分享，
     * 微信/QQ/邮箱都能直接收走。解 67B 扩展帧只看 CSV 里的 FRM 行就够（每帧全 hex）。
     */
    /**
     * 请求"忽略电池优化"。点了之后系统不再在息屏后掐后台（国产 ROM 仍可能另有"自启动"开关，
     * 这条只能解决 AOSP 那一层，所以文案里也提醒了）。
     */
    private void requestIgnoreBatteryOpt() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                HudToast.show(this, "在系统弹窗里点「允许」即可");
                return;
            }
        } catch (Exception e) {
            // 有些 ROM 没有这个入口，退到设置列表
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            HudToast.show(this, "这台手机没有电池优化入口，去系统的「自启动管理」里放行本 App");
        }
    }

    /**
     * v1.0.51：跳到本 App 的系统权限页。
     *
     * Android 14 起"后台定位（始终允许）"只能由用户在系统设置里改，代码无法弹框申请
     *（和前台定位一起申请时系统直接忽略）。所以这里只负责"把门打开"。
     */
    private void openAppPermissionSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            HudToast.show(this, "进「权限 → 位置信息」选「始终允许」，回来就生效");
        } catch (Exception e) {
            HudToast.show(this, "打不开系统设置：手动进 设置 → 应用 → 智能HUD → 权限 → 位置");
        }
    }

    private void shareLatestLog() {        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "logs");
            java.io.File[] files = dir.listFiles((d, n) -> n.startsWith("raw_") && n.endsWith(".csv"));
            if (files == null || files.length == 0) {
                HudToast.show(this, "还没有日志文件：先连一次车、跑几公里再导");
                return;
            }
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

            // v1.0.9：只导"最近 24 小时"的日志，并给总量设上限。
            // 旧版把所有日志（最多 24 个文件、单文件上限 16MB）全并成一个 CSV ——
            // 那是几百兆的复制，微信发不出去、手机也会卡死，等于点了没反应。
            long nowMs = System.currentTimeMillis();
            long keepFrom = nowMs - 24L * 3600_000L;
            java.util.List<java.io.File> pick = new java.util.ArrayList<>();
            long totalBytes = 0L;
            final long MAX_EXPORT_BYTES = 40L * 1024 * 1024;
            for (java.io.File f : files) {
                if (f.lastModified() < keepFrom) continue;
                if (totalBytes + f.length() > MAX_EXPORT_BYTES) continue;
                pick.add(f);
                totalBytes += f.length();
            }
            if (pick.isEmpty()) {           // 24 小时内没有 → 退回"最新的一个文件"
                pick.add(files[files.length - 1]);
            }

            // 顺手清掉 7 天前的导出文件：以前只导出、不清理，导出目录会一直涨
            java.io.File[] olds = dir.listFiles((d, n) -> n.startsWith("export_") && n.endsWith(".csv"));
            if (olds != null) {
                for (java.io.File f : olds) {
                    if (nowMs - f.lastModified() > 7L * 24 * 3600_000L) f.delete();
                }
            }

            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            java.io.File out = new java.io.File(dir, "export_" + fmt.format(new java.util.Date()) + ".csv");
            java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8));
            int rows = 0;
            try {
                for (java.io.File f : pick) {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                            new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8));
                    try {
                        String line = r.readLine();
                        // 第一行的表头只写一次；其余行原样拼上
                        if (rows == 0 && line != null) {
                            w.write(line);
                            w.write("\n");
                        }
                        while ((line = r.readLine()) != null) {
                            w.write(line);
                            w.write("\n");
                            rows++;
                        }
                    } finally {
                        r.close();
                    }
                }
            } finally {
                w.close();
            }

            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".files", out);
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_SEND);
            it.setType("text/csv");
            it.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            it.putExtra(android.content.Intent.EXTRA_SUBJECT, out.getName());
            it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(it, "导出 " + out.getName()));
            RawLog.sys(System.currentTimeMillis(), "export merged log " + out.getName()
                    + " (" + pick.size() + " files, " + rows + " rows, " + out.length() + "B)");
            HudToast.show(this, "已合并最近 24 小时 " + pick.size() + " 个日志、" + rows + " 行（"
                    + (out.length() / 1024) + "KB），选择微信/文件传输发出去");
        } catch (Exception e) {
            HudToast.show(this, "导出失败：" + e.getMessage());
        }
    }

    // ---------------- 设置（v1.0.23 重做） ----------------
    //
    // 旧版一屏 13 个分组、混着"电压字段 / 速度字段 / 试算 / AE02"这些协议调试项，
    // 日常根本用不到，找"换电瓶"要滚三屏。现在只放日常会动的四组，
    // 调试相关全部收进「高级」，需要时再进。
    private void showSettingsDialog() {
        PanelUi ui = PanelUi.open(this, "设置", "日常只需要这几项");

        // ---- 界面 ----
        LinearLayout uiCard = ui.section("界面");
        CheckBox cbVoice = ui.check("语音播报", prefs.voice());
        // v1.0.24：复选框走整行布局。之前用「左标签 + 右控件 108dp」的通用行，
        // 长文案被挤成三四行竖排（截图里就看出来了），非常难看。
        ui.checkRow(uiCard, cbVoice, "每公里报一次里程与电量；低电时也会报警。用手机自带 TTS，不联网。");

        // v1.0.33：两种显示模式（参考 DigiHUD 的 Lite / HUD 模式）
        CheckBox cbLite = ui.check("极简模式（只显示时速 + 电量）", prefs.lite());
        ui.checkRow(uiCard, cbLite, "字更大更省眼；主屏左右滑动也能切换。");
        CheckBox cbMirror = ui.check("镜像显示（夜间放仪表台靠玻璃反射）", prefs.mirror());
        ui.checkRow(uiCard, cbMirror, "整体上下翻转，反射到挡风玻璃上就是正的；白天别开。");

        EditText etSpeedLimit = ui.input(String.valueOf(prefs.speedLimit()), false);
        ui.row(uiCard, "超速提醒 (km/h)", "超过就变红并语音报一次；0 = 关闭", etSpeedLimit);

        final int[] themeNow = {prefs.themeIdx() % 4};
        ui.row(uiCard, "配色", "双击屏幕顶部也能切亮度", null);
        ui.chips(uiCard, new String[]{"绿", "青", "琥珀", "白"}, themeNow[0], idx -> {
            themeNow[0] = idx;
            prefs.setThemeIdx(idx);
            HudState.themeIdx = idx;
            HudState.touch();
        });

        final int[] brightNow = {prefs.brightMode() % 3};
        ui.row(uiCard, "亮度", null, null);
        ui.chips(uiCard, new String[]{"自动", "常亮", "常暗"}, brightNow[0], idx -> {
            brightNow[0] = idx;
            prefs.setBrightMode(idx);
            lastBrightness = -1f;
            applyBrightness(lastLux);
        });

        // ---- 设备 ----
        LinearLayout dev = ui.section("设备");
        ui.row(dev, "连接", HudState.devLabel == null ? "等待连接" :
                String.valueOf(HudState.devLabel), null);
        ui.inlineButton(dev, "扫描并选择设备").setOnClickListener(v -> {
            ui.dismiss();
            showDevicePicker();
        });
        EditText etMac = ui.input(prefs.mac(), false);
        ui.row(dev, "控制器 MAC", "留空则每次自动扫描", etMac);
        EditText etName = ui.input(prefs.devName(), false);
        ui.row(dev, "名称关键字", "MAC 是随机地址时用它认人", etName);
        ui.inlineButton(dev, "允许后台常驻（关掉系统省电限制）")
                .setOnClickListener(v -> requestIgnoreBatteryOpt());
        // v1.0.51：后台定位。Android 14 起"只在使用中允许"会让 App 切到高德/锁屏后取不到点，
        // 表现就是"离开这个界面后本次里程不涨"。这里给出状态 + 一键跳到系统权限页。
        boolean bgLoc = false;
        try {
            bgLoc = checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
        }
        ui.inlineButton(dev, bgLoc
                ? "后台定位：已允许（切高德/锁屏也能记里程）"
                : "后台定位：仅「使用时」→ 点这里改「始终允许」")
                .setOnClickListener(v -> openAppPermissionSettings());

        // ---- 运行状态（v1.0.52）----
        // 用户反馈"别让我去搜日志 fgs type=，太麻烦"。这三行就是把日志里最关键的三个状态
        // 直接摆到界面上：出问题时截一张图给我就够，不用翻 CSV。
        LinearLayout run = ui.section("运行状态");
        ui.note(run, "出问题截这张图给我就行，不用翻日志。");
        String fgs = HudState.fgsText == null || HudState.fgsText.isEmpty()
                ? "还没启动（服务未运行）"
                : HudState.fgsText + (HudState.locOk ? " ✅" : " ⚠");
        ui.row(run, "前台服务", "带定位型才能在后台继续取点", ui.value(fgs, false));
        String fixTxt;
        if (HudState.gpsFixAgeS < 0L) {
            fixTxt = "还没有定位点 ⚠";
        } else if (HudState.gpsFixAgeS <= 5L) {
            fixTxt = "正常（" + HudState.gpsFixAgeS + " 秒前）✅";
        } else if (HudState.gpsFixAgeS <= 20L) {
            fixTxt = HudState.gpsFixAgeS + " 秒前（偏慢）";
        } else {
            fixTxt = HudState.gpsFixAgeS + " 秒没点了 ⚠";
        }
        ui.row(run, "定位点", "停了 = 里程不会涨", ui.value(fixTxt, false));
        ui.row(run, "后台定位", "「始终允许」最稳，可选",
                ui.value(bgLoc ? "始终允许 ✅" : "仅使用时 ⚠", false));
        // v1.0.53：里程是不是全程都在记，看这一行就够 —— 出现"盲区"就说明那段时间
        // GPS 在报速、但一个位移点都没拿到（日志里对应 "odo blind"）。
        String blindTxt = HudState.odoBlind <= 0
                ? "0 次 ✅ 全程都在记"
                : (HudState.odoBlind + " 次 ⚠ 有速度没位移，那几段没记上");
        ui.row(run, "里程盲区", "有速度却没位移 = 漏记", ui.value(blindTxt, false));

        // ---- 电量 ----
        LinearLayout bat = ui.section("电量");
        EditText etRange = ui.input(String.valueOf(prefs.estRangeKm()), false);
        ui.row(bat, "满电续航 (km)", "兜底值；学到实测后会被取代", etRange);
        EditText etFull = ui.input(String.format(Locale.US, "%.1f", prefs.vFull()), true);
        ui.row(bat, "满电静置电压 (V)", String.format(Locale.US,
                "实测 %.1f；停稳 ≥8 分钟读到这个数才算真满", prefs.vFull()), etFull);
        EditText etEmpty = ui.input(String.format(Locale.US, "%.1f", prefs.vEmpty()), true);
        // v1.0.53：这一项的口径变了 —— 不再是"控制器欠压点"，而是**耗尽后的静置电压**。
        // 带载报警线由程序自己按 60.5V 判（实测这车彻底没电时带载 58.26V）。
        ui.row(bat, "耗尽静置电压 (V)", String.format(Locale.US,
                "实测 %.1f；带载报警线自动按 %.1f 判", prefs.vEmpty(), prefs.vEmpty() - 3.3f), etEmpty);
        EditText etPackV = ui.input(String.valueOf(prefs.packV()), false);
        ui.row(bat, "额定电压 (V)", null, etPackV);
        EditText etPackAh = ui.input(String.valueOf(prefs.packAh()), false);
        ui.row(bat, "容量 (Ah)", String.format(Locale.US, "按当前设置 %dV × %dAh ≈ %dWh",
                prefs.packV(), prefs.packAh(), (int) prefs.packWh()), etPackAh);
        // v1.0.27：这里原本是"整车累计 = 本次里程为 0 那一刻的读数"（也就是基准），
        // 而主屏那行长按填的是"仪表现在显示的数"（基准+累计+本次）——
        // 同一个数字出现两种含义，改一个另一个就跟着变，等于互相打架。
        // 现在两边统一成"整车里程 = 车上仪表现在显示多少"，填进去立刻按这个数显示。
        EditText etOdo = ui.input(Float.isNaN(HudState.odoKm)
                ? String.valueOf(prefs.odoBaseKm())
                : String.format(Locale.US, "%.1f", HudState.odoKm), true);
        ui.row(bat, "总里程 (km)", "照车上仪表填；对齐后会自动学里程比例", etOdo);
        // v1.0.53：把"里程系数"和上次对表的两个数摆出来 —— 用户能直接看出
        // "是我填的准，还是 App 一直少记"，不用再去翻日志。
        float scNow = prefs.odoScale();
        ui.row(bat, "里程系数", scNow > 1.02f || scNow < 0.98f
                        ? String.format(Locale.US, "仪表比 GPS 多算 %.0f%%，已折算", (scNow - 1f) * 100f)
                        : "1.000（GPS 与仪表一致）",
                ui.value(String.format(Locale.US, "%.3f", scNow), true));
        float calDash = prefs.odoCalDash();
        if (!Float.isNaN(calDash)) {
            float calApp = prefs.odoCalApp();
            float calTs = prefs.odoCalTs();
            String when = calTs > 0 ? new java.text.SimpleDateFormat("MM-dd HH:mm",
                    Locale.CHINA).format(new java.util.Date((long) calTs)) : "--";
            ui.note(bat, String.format(Locale.US,
                    "上次对表 %s：仪表 %.1f / App %.1f。再对一次表，上面这个系数会自动再学一步（每次最多 4%%）。",
                    when, calDash, calApp));
        }
        // v1.0.45 原本写"静置 ≥76.1V"；v1.0.53 门槛改了两处（电压按 78.6 满电重标 + 加了停稳/位移条件），
        // 文案必须跟着走，否则骑手会拿一个早就作废的数去判断"为什么没自动清"。
        ui.note(bat, String.format(Locale.US,
                "充满自动清「本次里程」的条件：静置 ≥8 分钟 + 电压 ≥%.1fV + 距上次满电已跑 30km 或 6 小时。"
                        + "总里程不受影响。", prefs.vFull() - 0.3f));
        // v1.0.53：电量算法换成人话摆在这。以前只在文档里写，界面上一个字都没有，
        // 骑手看到"停车 65%、骑起来 71%"只会以为坏了。
        ui.note(bat, "电量怎么算：停稳 ≥20 秒 → 按电压查表（这时最准）；骑行中 → 按「这一箱电跑了多少 km」推算，"
                + "电压不参与（骑行电压被压降打乱，越骑越不准）。两个口径切换时最多纠 5 个百分点。");

        ui.inlineButton(bat, Float.isNaN(HudState.kmThisCharge)
                ? "按已用电量校准满电续航"
                : String.format(Locale.US, "按已用电量校准满电续航（本箱电已跑 %.1fkm）",
                HudState.kmThisCharge))
                .setOnClickListener(v -> {
                    float km = HudState.kmThisCharge;
                    if (Float.isNaN(km) || km < 5f) {
                        HudToast.show(this, "先充满电记一次「满电」，再跑几公里才有得算");
                        return;
                    }
                    float cSoc = prefs.chargeSoc();
                    float socNow = HudState.soc;
                    int er;
                    if (!Float.isNaN(cSoc) && !Float.isNaN(socNow) && cSoc - socNow >= 20f) {
                        er = Math.round(km / (cSoc - socNow) * 100f);
                    } else {
                        er = Math.round(km + 3f);
                    }
                    if (er < 20) er = 20;
                    if (er > 250) er = 250;
                    prefs.setEstRangeKm(er);
                    etRange.setText(String.valueOf(er));
                    RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                            "calibrate range: %.1fkm / used %.0f%% -> estRange=%dkm", km,
                            (!Float.isNaN(cSoc) && !Float.isNaN(socNow)) ? (cSoc - socNow) : -1f, er));
                    HudToast.show(this, "满电续航已校准为 " + er + " km");
                });

        // ---- 数据 ----
        LinearLayout data = ui.section("数据");
        ui.note(data, "日志里已经自动记了电压、压降、电量、锚点、时速、里程；\n"
                + "跑完直接导出这一个文件发给我就行。");
        ui.inlineButton(data, "导出日志（直接发给我）").setOnClickListener(v -> shareLatestLog());
        // v1.0.27：清零入口也给一个明摆着的按钮 —— 之前只能长按主屏那个数字，不好记
        ui.inlineButton(data, String.format(Locale.US, "清零本次里程（现在 %.1f km）", HudState.tripKm))
                .setOnClickListener(v -> onLongPressReset());
        ui.inlineButton(data, "通知使用权（导航剩余里程）").setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                HudToast.show(this, "这台手机没有这个设置页，去「设置 → 通知与状态栏 → 通知使用权」找");
            }
        });

        // ---- 关于 / 版本（v1.0.41：用户要求，排查问题时也要靠它确认是哪个版本）----
        LinearLayout about = ui.section("关于");
        ui.row(about, "版本", versionLine(), null);
        ui.row(about, "包名", getPackageName(), null);
        ui.note(about, "发日志前先看一眼版本号：日志第一行的「build vX」应该和这里一致，不一致说明装的不是这一版。");
        ui.inlineButton(about, "复制版本信息").setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "hud-version", versionLine() + " · " + getPackageName()));
                    HudToast.show(this, "已复制");
                }
            } catch (Throwable ignored) {
            }
        });

        ui.primary("保存", v -> {
            String oldMac = prefs.mac();
            boolean oldVoice = prefs.voice();
            prefs.setVoice(cbVoice.isChecked());
            // v1.0.33：显示模式与超速阈值
            prefs.setLite(cbLite.isChecked());
            prefs.setMirror(cbMirror.isChecked());
            HudState.liteMode = cbLite.isChecked();
            HudState.mirrorMode = cbMirror.isChecked();
            int lim = parseInt(etSpeedLimit.getText().toString(), prefs.speedLimit());
            prefs.setSpeedLimit(Math.max(0, Math.min(120, lim)));
            HudState.touch();
            prefs.setMac(etMac.getText().toString());
            prefs.setDevName(etName.getText().toString());
            int er = (int) parseFloat(etRange.getText().toString(), prefs.estRangeKm());
            if (er < 10 || er > 300) er = prefs.estRangeKm();
            prefs.setEstRangeKm(er);
            float vf = parseFloat(etFull.getText().toString(), prefs.vFull());
            prefs.setVFull(vf < 60f || vf > 90f ? prefs.vFull() : vf);
            float ve = parseFloat(etEmpty.getText().toString(), prefs.vEmpty());
            prefs.setVEmpty(ve < 40f || ve > 80f ? prefs.vEmpty() : ve);
            prefs.setPackV(parseInt(etPackV.getText().toString(), prefs.packV()));
            prefs.setPackAh(parseInt(etPackAh.getText().toString(), prefs.packAh()));
            // 改"整车里程"= 用户读到的新读数 → 让显示值正好等于它（差额折进基准/累计）
            float odo = parseFloat(etOdo.getText().toString(), Float.NaN);
            if (!Float.isNaN(odo) && odo > 0f && odo < 999999f) {
                float cur = Float.isNaN(HudState.odoKm) ? -1f : HudState.odoKm;
                if (Math.abs(odo - cur) > 0.05f) alignOdoTo(odo, "settings");
            }
            HudState.devLabel = prefs.devName().isEmpty() ? prefs.mac() : prefs.devName();
            HudToast.show(this, "已保存（1 秒内生效）");

            if (!prefs.mac().equals(oldMac)) {
                Intent i = new Intent(this, BleService.class);
                i.setAction("reconnect");
                startForegroundService(i);
            }
            if (prefs.voice() != oldVoice) {
                Intent r = new Intent(this, BleService.class);
                r.setAction("restart");
                startForegroundService(r);
            }
            ui.dismiss();
        });
        ui.secondary("高级（协议调试）", v -> {
            ui.dismiss();
            showAdvancedSettingsDialog();
        });
        ui.show();
    }

    // ---------------- 高级：协议调试（v1.0.24 收窄） ----------------
    //
    // 用户反馈"高级里有一堆和设置重复的项"。这里原来是把整个旧设置页搬了过来：
    // 界面（Compose/语音）、设备（引擎/AE02/扫描/MAC/名称）、电量模型、电压账本、
    // 换电瓶、累计里程、电池容量、防盗、配色亮度、导航、数据——和主设置页重复了 8 组。
    //
    // 现在只留协议层的四样：电压字段、速度字段、当前帧、引擎开关。

    private void showAdvancedSettingsDialog() {
        PanelUi ui = PanelUi.open(this, "协议调试", "协议已解出，这里只在排查映射问题时动");

        LinearLayout vf = ui.section("电压字段");
        ui.note(vf, "偏移 / 位宽 / 大端(1=是) / 系数 / 偏置。当前协议是固定公式，这几项只影响兜底解码。");
        EditText etVOff = ui.input(String.valueOf(prefs.vOffset()), false);
        ui.row(vf, "偏移", null, etVOff);
        EditText etVLen = ui.input(String.valueOf(prefs.vLen()), false);
        ui.row(vf, "位宽", "通常 2", etVLen);
        EditText etVBig = ui.input(prefs.vBig() ? "1" : "0", false);
        ui.row(vf, "大端 (1/0)", null, etVBig);
        EditText etVScale = ui.input(String.format(Locale.US, "%.3f", prefs.vScale()), true);
        ui.row(vf, "系数", "原始值 × 系数", etVScale);
        EditText etVBias = ui.input(String.format(Locale.US, "%.2f", prefs.vBias()), true);
        ui.row(vf, "偏置", null, etVBias);

        LinearLayout sf = ui.section("速度字段");
        EditText etSOff = ui.input(String.valueOf(prefs.sOffset()), false);
        ui.row(sf, "偏移", null, etSOff);
        EditText etSLen = ui.input(String.valueOf(prefs.sLen()), false);
        ui.row(sf, "位宽", "通常 2", etSLen);
        EditText etSBig = ui.input(prefs.sBig() ? "1" : "0", false);
        ui.row(sf, "大端 (1/0)", null, etSBig);
        EditText etSScale = ui.input(String.format(Locale.US, "%.3f", prefs.sScale()), true);
        ui.row(sf, "系数", null, etSScale);

        LinearLayout frame = ui.section("当前帧");
        final TextView tvFrame = ui.note(frame, currentFrameText());
        ui.inlineButton(frame, "刷新当前帧").setOnClickListener(v -> tvFrame.setText(currentFrameText()));

        LinearLayout eng = ui.section("链路实现");
        CheckBox cbNordic = ui.check("蓝牙用 Nordic 官方库（推荐）", prefs.nordicBle());
        ui.checkRow(eng, cbNordic, "取消勾选 = 换回早期自己写的 GATT 实现，连不上时用来对照。");
        CheckBox cbAe02 = ui.check("也订阅 AE02（实验）", prefs.ae02());
        ui.checkRow(eng, cbAe02, "实测 3504 帧全是 67 字节、AE02 零通知；只有复现对比实验时才勾。");

        ui.primary("保存并重启链路", v -> {
            boolean oldNordic = prefs.nordicBle();
            boolean oldAe02 = prefs.ae02();
            prefs.setVOffset(parseInt(etVOff.getText().toString(), prefs.vOffset()));
            prefs.setVLen(parseInt(etVLen.getText().toString(), prefs.vLen()));
            prefs.setVBig(parseInt(etVBig.getText().toString(), 1) == 1);
            prefs.setVScale(parseFloat(etVScale.getText().toString(), prefs.vScale()));
            prefs.setVBias(parseFloat(etVBias.getText().toString(), prefs.vBias()));
            prefs.setSOffset(parseInt(etSOff.getText().toString(), prefs.sOffset()));
            prefs.setSLen(parseInt(etSLen.getText().toString(), prefs.sLen()));
            prefs.setSBig(parseInt(etSBig.getText().toString(), 1) == 1);
            prefs.setSScale(parseFloat(etSScale.getText().toString(), prefs.sScale()));
            prefs.setNordicBle(cbNordic.isChecked());
            prefs.setAe02(cbAe02.isChecked());
            RawLog.sys(System.currentTimeMillis(), String.format(Locale.US,
                    "advanced saved: v(%d/%d/%d/%.3f/%.2f) s(%d/%d/%d/%.3f) nordic=%s ae02=%s",
                    prefs.vOffset(), prefs.vLen(), prefs.vBig() ? 1 : 0, prefs.vScale(), prefs.vBias(),
                    prefs.sOffset(), prefs.sLen(), prefs.sBig() ? 1 : 0, prefs.sScale(),
                    prefs.nordicBle(), prefs.ae02()));
            if (prefs.nordicBle() != oldNordic || prefs.ae02() != oldAe02) {
                Intent r = new Intent(this, BleService.class);
                r.setAction("restart");
                startForegroundService(r);
            }
            HudToast.show(this, "已保存（改动会在 1 秒内生效）");
            ui.dismiss();
        });
        ui.show();
    }

    private String currentFrameText() {
        if (HudState.lastByTag.isEmpty()) return "还没有收到帧。";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, byte[]> e : HudState.lastByTag.entrySet()) {
            byte[] d = e.getValue();
            sb.append(e.getKey()).append(" (").append(d.length).append("B)\n");
            String hex = RawLog.hex(d);
            sb.append(hex.length() > 90 ? hex.substring(0, 90) + " …" : hex).append('\n');
        }
        return sb.toString();
    }

    // ---------------- 小工具 ----------------

    /** 崩溃也落盘：下次"点了某个按钮就闪退"时，日志里直接有堆栈，不用装 adb */
    private void installCrashLogger() {
        // v1.0.49：真正装钩子的是 HudApp（进程一启动就装，服务在后台崩也能抓到）。
        // 这里保留调用，是为了兼容老路径：HudApp 里幂等，重复调用不会叠加 handler。
        HudApp.install(this);
    }

    private LinearLayout verticalBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        l.setPadding(pad, pad, pad, pad);
        return l;
    }

    private LinearLayout horizontalBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private ScrollView wrapScroll(LinearLayout content) {
        ScrollView s = new ScrollView(this);
        s.addView(content);
        s.setPadding(0, dp(4), 0, dp(4));
        return s;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14f);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private EditText input(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private EditText num(String value, LinearLayout parent) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        parent.addView(e, lp);
        return e;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static float parseFloat(String s, float def) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }

    private static String fmt(float v) {
        return Float.isNaN(v) ? "--" : String.format(java.util.Locale.US, "%.2f", v);
    }
}
