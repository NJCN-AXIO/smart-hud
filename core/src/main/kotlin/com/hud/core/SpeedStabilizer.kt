package com.hud.core

/**
 * 时速显示的最后一道工序：抗抖、抗断、抗"骑着车突然显示 0"。
 *
 * 三层，每层都能对应到一个真实的坑：
 *  1. **1€ 滤波**（[OneEuro]）：GPS / 控制器速度都带 ±2–4 km/h 的抖动，直接显示会闪。
 *  2. **停车归零**：低速时 GPS 常在 0 与 2–3 之间来回跳，看着像在动。低于
 *     [zeroKmh] 持续 [zeroHoldMs] 就干脆显示 0。
 *  3. **短时保持**：GPS 1 秒才来一个点，隧道/桥洞偶尔丢 2–3 个点。丢点期间保留上次
 *     平滑值（最多 [holdMs]），而不是立刻掉成 `--` —— 骑手看到 0 会以为车坏了。
 *
 * 10 秒以上没新数据仍然返回 NaN（那是真没信号，不能骗人）。
 */
class SpeedStabilizer @JvmOverloads constructor(
    minCutoffHz: Double = 0.4,
    beta: Double = 0.02,
    dCutoffHz: Double = 1.0,
    /** 低于这个速度视为"要停了"（km/h） */
    val zeroKmh: Float = 1.5f,
    /** 低于 [zeroKmh] 持续多久就显示 0（毫秒） */
    val zeroHoldMs: Long = 600L,
    /** 丢点期间最多保持上次值多久（毫秒） */
    val holdMs: Long = 4000L
) {

    private val filter = OneEuro(minCutoffHz, beta, dCutoffHz)

    private var lastOut = Float.NaN
    private var lastGoodT = 0L
    private var belowSince = 0L
    private var prevT = 0L
    private var hasPrev = false

    fun reset() {
        filter.reset()
        lastOut = Float.NaN
        lastGoodT = 0L
        belowSince = 0L
        prevT = 0L
        hasPrev = false
    }

    /**
     * @param tMs  本次观测时刻
     * @param kmh  本次原始速度；不可用传 NaN
     * @return 用于显示的速度；彻底没数据时 NaN
     */
    fun push(tMs: Long, kmh: Float): Float {
        if (kmh.isNaN() || kmh < 0f) {
            // 丢点：短暂保持，长时间仍无数据才承认没有
            if (!lastOut.isNaN() && tMs - lastGoodT <= holdMs) return lastOut
            return Float.NaN
        }

        val dt = if (hasPrev) (tMs - prevT).coerceAtLeast(1L) else 1000L
        hasPrev = true
        prevT = tMs

        if (kmh < zeroKmh) {
            if (belowSince == 0L) belowSince = tMs
            if (tMs - belowSince >= zeroHoldMs) {
                filter.reset()
                lastOut = 0f
                lastGoodT = tMs
                return 0f
            }
        } else {
            belowSince = 0L
        }

        val out = filter.filter(kmh.toDouble(), dt).toFloat()
        lastOut = out
        lastGoodT = tMs
        return out
    }

    /** 展示值（不改滤波器） */
    fun value(): Float = lastOut
}
