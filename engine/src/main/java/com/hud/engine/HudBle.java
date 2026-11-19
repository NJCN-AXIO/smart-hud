package com.hud.engine;

/**
 * BLE 层的公共类型：状态、回调、引擎接口。
 *
 * 为什么单独抽出来：现在有两套引擎——
 *   1. {@link NordicBleLink}   —— 用 Nordic 官方 Android-BLE-Library（成熟库，默认）
 *   2. {@link BleLink}         —— 早期自写的 BluetoothGattCallback 版本（留作兜底）
 * 上层（BleService）只认这个接口，换引擎不用改业务代码。
 */
public final class HudBle {

    private HudBle() {
    }

    public enum State { IDLE, SCANNING, CONNECTING, CONNECTED }

    public interface Listener {
        /** 连接状态变化，text 是给人看的一句话 */
        void onState(State state, String text);

        /** 收到通知：tag 是短 UUID（如 0xFFF6），value 是原始字节 */
        void onNotify(String tag, byte[] value);

        /** GATT 层事件（服务/特征/读结果），用于落盘分析 */
        void onGatt(String what, String detail);

        /** 扫描到设备（用于日志/选择列表） */
        void onScanDevice(String mac, String name, int rssi);

        /** 决定连它了（上层可以据此把 MAC 存起来） */
        void onDevicePicked(String mac, String name);
    }

    /** 两套引擎都要实现的最小接口，语义和早期 BleLink 完全一致 */
    public interface Transport {
        /** 上层在设置里改了 MAC/名字就叫这个 */
        void setTarget(String mac, String name);

        /** mac 为空则直接扫；name 用于随机地址时匹配 */
        void start(String mac, String name);

        void stop();

        void reconnectNow();

        /** 只把扫到的设备报给上层（设置页用来让用户点选），不自动连 */
        void scanForList();

        /** 用户在列表里点了某一台：记住它并直接连 */
        void connectTo(String mac, String name);

        State state();
    }
}
