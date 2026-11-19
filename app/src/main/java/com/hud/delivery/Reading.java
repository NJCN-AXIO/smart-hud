package com.hud.delivery;

/** 一帧解析结果。没解出来的字段一律是 NaN —— 绝不用 0 或假值冒充。 */
public final class Reading {

    public static final float NA = Float.NaN;

    public final long ts;
    public final byte[] raw;
    /** 供电电压（V），NaN = 未解码 */
    public final float voltage;
    /** 车速（km/h），NaN = 未解码 */
    public final float speedKmh;
    /** 控制器自己上报的电量百分比（若有），NaN = 无 */
    public final float socRaw;
    /** 供 UI 显示的一句说明 */
    public final String note;

    public Reading(long ts, byte[] raw, float voltage, float speedKmh, float socRaw, String note) {
        this.ts = ts;
        this.raw = raw;
        this.voltage = voltage;
        this.speedKmh = speedKmh;
        this.socRaw = socRaw;
        this.note = note;
    }

    public boolean hasVoltage() { return !Float.isNaN(voltage); }
    public boolean hasSpeed() { return !Float.isNaN(speedKmh); }
}
