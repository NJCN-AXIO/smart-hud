package com.hud.core

/**
 * 1€ 滤波器（One Euro Filter）。
 *
 * 参考：Casiez, Roussel, Vogel. *1€ Filter: A Simple Speed-based Low-pass Filter for
 * Noisy Input in Interactive Systems*, CHI 2012 —— 交互/传感领域最常被引用的抖动平滑方案，
 * 被大量项目直接移植（浏览器手势、AR 姿态、GPS 速度显示都用它）。
 *
 * 相比"取最近 N 秒平均"（本工程的 [Smoother]）它的好处是**自适应**：
 * 慢的时候重平滑（数字不抖），快的时候轻平滑（不会滞后半秒才跟上）。
 * 对骑手的意义：停车时数字不会在 0/3/5 之间跳，加速时又不会延迟显示。
 */
class OneEuro(
    /** 静止时低通截止频率（Hz）：越小越稳、越滞后 */
    var minCutoffHz: Double = 0.4,
    /**
     * 速度对截止频率的放大系数。注意：这个值跟**信号单位**有关。
     * 原始论文的例子信号量级是 0–1，我们这里速度是 km/h（0–40），
     * 相邻差值动辄 5–10，所以 beta 要小一到两个数量级，用 0.02。
     */
    var beta: Double = 0.02,
    /** 微分项自身的截止频率（Hz） */
    var dCutoffHz: Double = 1.0
) {

    private var hasPrev = false
    private var prevT = 0L
    private var xPrev = 0.0
    private var dxPrev = 0.0

    fun reset() {
        hasPrev = false
        prevT = 0L
        xPrev = 0.0
        dxPrev = 0.0
    }

    /**
     * @param x     本次观测值
     * @param dtMs  距上次观测的毫秒数
     * @return 滤波后的值；首次调用直接返回 x
     */
    fun filter(x: Double, dtMs: Long): Double {
        if (x.isNaN()) return Double.NaN
        if (!hasPrev) {
            hasPrev = true
            prevT = dtMs
            xPrev = x
            dxPrev = 0.0
            return x
        }
        val dt = dtMs / 1000.0
        if (dt <= 0.0) return xPrev

        // 先平滑"微分"（变化速度），再按变化速度抬截止频率
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoffHz, dt)
        dxPrev = aD * dx + (1 - aD) * dxPrev

        val cutoff = minCutoffHz + beta * Math.abs(dxPrev)
        val a = alpha(cutoff, dt)
        xPrev = a * x + (1 - a) * xPrev
        return xPrev
    }

    private fun alpha(cutoffHz: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoffHz)
        return 1.0 / (1.0 + tau / dt)
    }
}
