package com.hud.delivery;

import com.hud.core.TripIntegrator;

/**
 * 单次里程的 Android 适配层：数学在 :core.TripIntegrator，这里只负责落盘。
 *
 * 落盘节奏：每 5 秒写一次 SharedPreferences（不是每个速度样本都写，避免闪存写放大）。
 */
public final class TripTracker {

    private static final long SAVE_INTERVAL_MS = 5000L;

    private final Prefs prefs;
    private final TripIntegrator impl;
    private long lastSaveMs = 0L;

    public TripTracker(Prefs prefs) {
        this.prefs = prefs;
        this.impl = new TripIntegrator(prefs.tripKm());
        if (prefs.tripStart() == 0L) prefs.setTripStart(System.currentTimeMillis());
    }

    public synchronized float km() {
        return impl.getKm();
    }

    public synchronized void onSpeed(float kmh, long nowMs) {
        impl.onSpeed(kmh, nowMs);
        if (nowMs - lastSaveMs > SAVE_INTERVAL_MS) {
            lastSaveMs = nowMs;
            prefs.setTripKm(impl.getKm());
        }
    }

    /**
     * 直接加一段距离：速度字段还没标定时，App 拿不到速度，用 GPS 相邻点距离兜底。
     * 调用方负责过滤 GPS 漂移（小抖动不要算）。
     */
    public synchronized void addKm(float deltaKm, long nowMs) {
        impl.addKm(deltaKm);
        if (nowMs - lastSaveMs > SAVE_INTERVAL_MS) {
            lastSaveMs = nowMs;
            prefs.setTripKm(impl.getKm());
        }
    }

    public synchronized void reset(long nowMs) {
        impl.reset();
        prefs.setTripKm(0f);
        prefs.setTripStart(nowMs);
    }

    public synchronized void flush() {
        prefs.setTripKm(impl.getKm());
    }

    /** 覆盖当前里程（演示模式用完把真实里程还原回去，别让假数据污染里程表）。 */
    public synchronized void setKm(float km) {
        impl.restore(km);
        prefs.setTripKm(km);
    }
}
