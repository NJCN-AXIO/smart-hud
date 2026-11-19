package com.hud.core

/**
 * 骑行压降补偿：把"带载电压"换算成"空载电压（OCV）"。
 *
 * 铅酸在放电时端电压 = OCV − I·R_int。72V/500W 的车在 30km/h 时电流大约 7–10A，
 * 内阻约 0.2Ω，压降 1.5–2.5V。所以骑行中看到的 73.9V，静置大概是 75.5–76.5V。
 *
 * 直接拿带载电压查表，会把"加速"误判成"没电"——骑手看到的是电量百分比在
 * 起步瞬间掉一档、松油门又涨回来。这里用速度线性近似电流（功率 ∝ 速度），
 * 做一次补偿，再交给 [SocModel] 查表：
 *
 *   ocv = loadV + min(maxSagV, ΔV_per_kmh × speedKmh)
 *
 * 停在红灯处 speed=0 → 补偿为 0 → 得到的就是真实静置电压。
 * 系数保守取值（0.06V/(km/h)、封顶 2.6V），宁可少补也不要补过头。
 */
class LoadComp(
    /** 封顶压降（V）：再快也不超过这个补偿量，防止高速时把电量算虚高 */
    private val maxSagV: Float = 2.6f,
    /** 每 km/h 的压降（V） */
    private val vPerKmh: Float = 0.06f
) {

    /** 带载电压 + 速度 → 空载电压。任一入参 NaN 时：速度为 0 处理，电压 NaN 原样返回 NaN。 */
    fun ocv(loadV: Float, speedKmh: Float): Float {
        if (loadV.isNaN()) return Float.NaN
        val s = if (speedKmh.isNaN() || speedKmh < 0f) 0f else speedKmh
        val sag = minOf(maxSagV, vPerKmh * s)
        return loadV + sag
    }

    /** 当前补偿量（V），界面/日志里可直接展示"补了 1.8V" */
    fun sagOf(speedKmh: Float): Float {
        val s = if (speedKmh.isNaN() || speedKmh < 0f) 0f else speedKmh
        return minOf(maxSagV, vPerKmh * s)
    }

    /**
     * v1.0.20：**带油门**的压降估计。
     *
     * 只按速度补是不够的 —— 用户实测的典型场景就是"上坡满油门 15km/h"：
     * 速度不高，可是电流拉满，实际压降能有 5–8V；而 [sagOf] 只补 0.9V。
     * 剩下的 4–7V 就被当成"电掉了"，电量与续航一起被吃掉，而且松油门后也回不来。
     *
     * 转把电压（协议 `0.019 × f[42]`）本身就是"油门开度"的直接代理：怠速约 0.8V，
     * 拧到底约 4.0–4.3V。所以压降 ∝ (转把 − 怠速)，比速度更接近电流。
     *
     *   sag = min(capV, vPerKmh × speed + vPerThrottle × max(0, throttle − idleV))
     *
     * 系数保守：每 1V 转把补 1.2V 压降，总补封顶 7V（再高就把电量算虚了）。
     * 刹车/停车时速度 0、转把回位 → 补偿 0，得到的仍是真实静置电压。
     */
    fun sagOf(speedKmh: Float, throttleV: Float): Float {
        val s = if (speedKmh.isNaN() || speedKmh < 0f) 0f else speedKmh
        val speedSag = minOf(maxSagV, vPerKmh * s)
        val thrSag = if (throttleV.isNaN()) 0f
        else minOf(loadSagCapV, vPerThrottleV * (throttleV - idleThrottleV).coerceAtLeast(0f))
        return minOf(loadSagCapV + maxSagV, speedSag + thrSag)
    }

    /** 带载电压 + 速度 + 转把 → 估计的空载电压（OCV） */
    fun ocv(loadV: Float, speedKmh: Float, throttleV: Float): Float {
        if (loadV.isNaN()) return Float.NaN
        return loadV + sagOf(speedKmh, throttleV)
    }

    companion object {
        /** 转把怠速电压（实测松手就是 0.8V） */
        const val idleThrottleV = 0.8f
        /**
         * 每 1V 转把对应的额外压降。
         *
         * v1.0.31：由实车日志标定，不再拍脑袋 —— `tools/fit_sag.py` 把 12 份原始 67B 帧
         * （3,096 帧，含电压与转把）逐帧解出来，取每段的怠速电压当 OCV 基线，
         * 统计"同一帧被负载压下去多少"：
         *   转把 0.8V → 0.2V（噪声水平）
         *   2.3V → 2.1V   2.5V → 3.1V   2.8V → 2.7V
         *   3.2V → 3.5V   3.4V → 3.5V（开始饱和）
         * 线性段斜率 ≈ 1.35V/V，饱和值 ≈ 3.5V（中位）/ 5.6V（90 分位）。
         */
        const val vPerThrottleV = 1.35f
        /** 转把项封顶：实测饱和在 3.5–4V，留到 5V 给最陡的坡，再多就是"把电量算虚" */
        const val loadSagCapV = 5.0f
        /** 转把减去怠速超过这个值，就认为"明显在出力"（别用它校准电量） */
        const val loadedThrottleV = 1.2f
    }
}
