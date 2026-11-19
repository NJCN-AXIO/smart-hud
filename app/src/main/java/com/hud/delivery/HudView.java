package com.hud.delivery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 极简 HUD：整屏就三个数（电压 / 电量 / 本次里程），骑车时扫一眼就能读。
 * 全部自绘，不依赖任何 UI 库，也不吃 XML 布局。
 */
public final class HudView extends View {

    private static final int C_BG = 0xFF07090C;
    private static final int C_DIM = 0xFF6B7480;
    private static final int C_TEXT = 0xFFEDF1F5;
    private static final int C_GREEN = 0xFF35E07A;
    private static final int C_AMBER = 0xFFFFC24B;
    private static final int C_RED = 0xFFFF5A5A;
    /** 手动录入的电压用灰蓝：能看，但一眼就知道不是实时值 */
    private static final int C_MANUAL = 0xFF9FB3C8;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long lastSeq = -1;

    public interface Listener {
        void onLongPressReset();
    }

    private Listener listener;

    public HudView(Context c) {
        super(c);
        setBackgroundColor(C_BG);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        barBg.setColor(0xFF1A1F26);
    }

    public void setListener(Listener l) { this.listener = l; }

    private void sync() {
        // 数据在 HudState 里，View 只负责画
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;

        // ---- 顶部状态行 ----
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setColor(C_DIM);
        p.setTextSize(Math.max(11f, h * 0.018f));
        String left = HudState.devLabel == null || HudState.devLabel.isEmpty() ? "未设置设备" : HudState.devLabel;
        c.drawText(left, 16f, h * 0.045f, p);

        String right = HudState.connText + String.format(Locale.US, "  %.1fHz  %dB", HudState.rateHz, HudState.lastFrameLen);
        float rw = p.measureText(right);
        c.drawText(right, w - 16f - rw, h * 0.045f, p);

        // ---- 右上角时速（和 Compose 界面同一套口径与颜色；在状态行下方一行，不重叠） ----
        drawSpeedBadge(c, w, h);

        // ---- 电压 ----
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        String vTxt = Float.isNaN(HudState.voltage) ? "--.-" : String.format(Locale.US, "%.1f", HudState.voltage);
        int vColor;
        if (Float.isNaN(HudState.voltage)) {
            vColor = C_DIM;
        } else if (HudState.voltage < 67f) {
            vColor = C_RED;                                  // 该充电了，不管数据来源
        } else if (HudState.voltageSource == HudState.SRC_MANUAL
                || HudState.voltageSource == HudState.SRC_EST) {
            vColor = C_MANUAL;                               // 手动/推算值用灰蓝，防止误当成实时
        } else {
            vColor = C_GREEN;
        }
        p.setColor(vColor);
        drawFitted(c, vTxt, 0f, 0f, cx, h * 0.235f, w * 0.86f, h * 0.26f, false);
        drawUnit(c, "V", cx + measureBigWidth(vTxt, w * 0.86f, h * 0.26f) / 2f + h * 0.015f, h * 0.235f + h * 0.02f, h * 0.06f, vColor);

        // ---- 电量 ----
        float soc = HudState.soc;
        String socTxt = Float.isNaN(soc) ? "--" : String.format(Locale.US, "%.0f", soc);
        int socColor = Float.isNaN(soc) ? C_DIM : (soc <= 20 ? C_RED : (soc <= 40 ? C_AMBER : C_GREEN));
        p.setColor(socColor);
        drawFitted(c, socTxt, 0f, 0f, cx - w * 0.10f, h * 0.50f, w * 0.44f, h * 0.17f, false);
        drawUnit(c, "%", cx - w * 0.10f + measureBigWidth(socTxt, w * 0.44f, h * 0.17f) / 2f + h * 0.012f,
                h * 0.50f + h * 0.012f, h * 0.05f, socColor);

        // ---- 电量条 ----
        float barL = w * 0.08f, barR = w * 0.92f, barT = h * 0.60f, barH = h * 0.016f;
        RectF r = new RectF(barL, barT, barR, barT + barH);
        c.drawRoundRect(r, barH / 2f, barH / 2f, barBg);
        if (!Float.isNaN(soc)) {
            float fillW = (barR - barL) * Math.max(0f, Math.min(100f, soc)) / 100f;
            RectF f = new RectF(barL, barT, barL + Math.max(barH, fillW), barT + barH);
            p.setColor(socColor);
            c.drawRoundRect(f, barH / 2f, barH / 2f, p);
        }

        // ---- 里程 ----
        float trip = HudState.tripKm;
        int tripColor = trip > 60f ? C_RED : (trip >= 50f ? C_AMBER : C_TEXT);
        p.setColor(tripColor);
        String tripTxt = String.format(Locale.US, "%.1f", trip);
        drawFitted(c, tripTxt, 0f, 0f, cx, h * 0.755f, w * 0.66f, h * 0.19f, false);
        drawUnit(c, "km", cx + measureBigWidth(tripTxt, w * 0.66f, h * 0.19f) / 2f + h * 0.014f,
                h * 0.755f + h * 0.014f, h * 0.055f, tripColor);

        // ---- 底部说明 ----
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        // 剩余里程（能估就估，估不了就老实说估不了）
        p.setTextSize(Math.max(10f, h * 0.019f));
        String rangeTxt;
        float rk = HudState.rangeKm;
        int rangeColor = C_DIM;
        if (Float.isNaN(rk)) {
            rangeTxt = "剩余里程 --（再录一次电压就能算出来）";
        } else {
            rangeTxt = String.format(Locale.US, "还能跑 ~%.0f km · 每公里约耗 %.1f%%", rk, HudState.socPerKm);
            rangeColor = rk < 8f ? C_RED : (rk < 15f ? C_AMBER : C_DIM);
        }
        p.setColor(rangeColor);
        float raz = p.measureText(rangeTxt);
        c.drawText(rangeTxt, cx - raz / 2f, h * 0.905f, p);

        // 一行状态/提示
        p.setTextSize(Math.max(10f, h * 0.016f));
        String hint;
        int hintColor = C_DIM;
        if (HudState.voltageSource == HudState.SRC_EST) {
            if (HudState.kmSinceManual >= 8f) {
                hint = String.format(Locale.US, "已推算 %.1fkm（%.0f%%），建议点「电压」再录一次",
                        HudState.kmSinceManual, HudState.soc);
                hintColor = C_AMBER;
            } else {
                hint = String.format(Locale.US, "实测 %.1fV（%.0f%%）→ 推算 %.1fV（已跑 %.1fkm）",
                        HudState.manualAnchorV, Float.isNaN(HudState.manualAnchorSoc) ? 0f : HudState.manualAnchorSoc,
                        HudState.voltage, HudState.kmSinceManual);
            }
        } else if (HudState.voltageSource == HudState.SRC_MANUAL) {
            long mins = Math.max(0L, (System.currentTimeMillis() - HudState.manualTs) / 60000L);
            hint = "手动录入 " + vTxt + "V · " + mins + " 分钟前 · 跑起来会自动往下走";
        } else if (!Float.isNaN(HudState.voltage)) {
            hint = "长按屏幕清零本次里程";
        } else {
            hint = HudState.note.isEmpty() ? "协议未解码 · 点「电压」手动录入先顶上" : HudState.note;
        }
        p.setColor(hintColor);
        float hw = p.measureText(hint);
        c.drawText(hint, cx - hw / 2f, h * 0.955f, p);

        // 速度已经搬到右上角大字号（drawSpeedBadge），底部不再重复画小字
    }

    /** 右上角大一点的时速（自绘界面也要和 Compose 保持一致：骑行最常看这个） */
    private void drawSpeedBadge(Canvas c, float w, float h) {
        float kmh = Float.isNaN(HudState.speedKmh) ? HudState.gpsKmh : HudState.speedKmh;
        if (Float.isNaN(kmh)) return;
        int col = kmh > 35f ? C_RED : (kmh >= 25f ? C_AMBER : C_GREEN);
        p.setColor(col);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(h * 0.055f);
        String num = String.format(Locale.US, "%.0f", kmh);
        float tw = p.measureText(num);
        float x = w - 18f - tw;
        float y = h * 0.135f;
        c.drawText(num, x, y, p);

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setTextSize(h * 0.018f);
        String unit = (Float.isNaN(HudState.speedKmh) && !Float.isNaN(HudState.gpsKmh)) ? "km/h GPS" : "km/h";
        float uw = p.measureText(unit);
        c.drawText(unit, w - 18f - uw, y + h * 0.026f, p);
    }

    /** 居中画一串大数字，按可用宽度自动缩字号 */
    private void drawFitted(Canvas c, String text, float unusedX, float unusedY,
                            float cx, float cy, float maxW, float maxTextSize, boolean unused) {
        p.setTextSize(maxTextSize);
        float tw = p.measureText(text);
        if (tw > maxW && tw > 0f) {
            p.setTextSize(maxTextSize * maxW / tw);
        }
        p.setTextAlign(Paint.Align.CENTER);
        // Paint.FontMetrics 基线校正，让数字视觉居中
        float baseline = cy - (p.descent() + p.ascent()) / 2f;
        c.drawText(text, cx, baseline, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private float measureBigWidth(String text, float maxW, float maxTextSize) {
        p.setTextSize(maxTextSize);
        float tw = p.measureText(text);
        if (tw > maxW && tw > 0f) p.setTextSize(maxTextSize * maxW / tw);
        return p.measureText(text);
    }

    private void drawUnit(Canvas c, String unit, float x, float y, float size, int color) {
        p.setColor(color);
        p.setTextSize(size);
        c.drawText(unit, x, y, p);
    }

    // ---- 交互 ----

    private long downMs = 0L;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downMs = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_UP:
                if (System.currentTimeMillis() - downMs > 1200L && listener != null) {
                    listener.onLongPressReset();
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    public void refresh() { invalidate(); }
}
