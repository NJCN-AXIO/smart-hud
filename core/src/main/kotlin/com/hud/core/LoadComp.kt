package com.hud.core

/**
 * 骑行压降补偿：把"带载电压"换算成"空载电压（OCV）"。
 *
 * 铅酸放电时端电压 = OCV − I·R_int。72V/500W 车在 30km/h 时要 7–10A，整包内阻 0.3–0.5Ω，
 * 压降 3–6V 很正常——比老版本假设的 1.5–2.5V 大一倍。
 *
 * v1.0.53 按 11-19 实车全程重标（3,600 个"静止 vs 带载"配对样本，见 analysis/57）：
 *
 * | 车速 | sag 中位 | P90 | 最大 |
 * |---|---|---|---|
 * | 静止 | 0.00 | 0.05 | 2.95 |
 * | <10 km/h | 0.51 | 4.02 | 4.25 |
 * | 10–20 | 2.70 | 4.39 | 4.86 |
 * | 20–30 | 3.78 | 5.02 | 5.46 |
 * | 30+ | 5.25 | 5.83 | 6.29 |
 *
 * 旧模型是"0.06V/(km/h) + 封顶 5V"：30km/h 只补 1.8V，**少补 3.4V ≈ 少算 12 个百分点**，
 * 这正是"上坡掉电、平路松开又回来"的根。现在改成：
 *
 *   sag = max( 速度表插值 , 转把项 )，封顶 6.5V
 *
 * 速度表管"巡航"，转把项管"低速大扭矩"（爬坡 15km/h 满油门时速度项只有 1.2V，转把项能到 4.7V），
 * 取两者较大值而不是相加——实测两个信号是同一份电流的两个侧面，相加会补过头。
 */
class LoadComp(
    /** 封顶压降（V）：实测最大 6.29V，留一点余量 */
    private val maxSagV: Float = 6.5f,
    /** 兼容旧构造参数：每 km/h 的线性压降（仅在没有速度表时兜底） */
    private val vPerKmh: Float = 0.06f
) {

    /** 带载电压 + 速度 → 空载电压。 */
    fun ocv(loadV: Float, speedKmh: Float): Float {
        if (loadV.isNaN()) return Float.NaN
        return loadV + sagOf(speedKmh)
    }

    /** 当前补偿量（V），界面/日志里可直接展示"补了 4.4V" */
    fun sagOf(speedKmh: Float): Float {
        val s = if (speedKmh.isNaN() || speedKmh < 0f) 0f else speedKmh
        return speedSag(s)
    }

    /**
     * 带油门与速度的压降估计（v1.0.20 引入，v1.0.53 重标系数）。
     *
     * 转把电压（协议 `0.019 × f[42]`）是"油门开度"的直接代理：怠速约 0.8V，拧到底 4.0–4.3V。
     * 速度项负责巡航，转把项负责低速大扭矩，取较大值。
     */
    fun sagOf(speedKmh: Float, throttleV: Float): Float {
        val s = if (speedKmh.isNaN() || speedKmh < 0f) 0f else speedKmh
        val speedPart = speedSag(s)
        val thrPart = if (throttleV.isNaN()) 0f
        else vPerThrottleV * (throttleV - idleThrottleV).coerceAtLeast(0f)
        return minOf(maxSagV, maxOf(speedPart, thrPart))
    }

    /** 带载电压 + 速度 + 转把 → 估计的空载电压（OCV） */
    fun ocv(loadV: Float, speedKmh: Float, throttleV: Float): Float {
        if (loadV.isNaN()) return Float.NaN
        return loadV + sagOf(speedKmh, throttleV)
    }

    private fun speedSag(s: Float): Float {
        // 速度 → 压降 的实测折线
        val seg = SPEED_SAG
        if (s <= seg[0][0]) return seg[0][1]
        for (i in 0 until seg.size - 1) {
            val a = seg[i]
            val b = seg[i + 1]
            if (s <= b[0]) {
                val t = (s - a[0]) / (b[0] - a[0])
                return minOf(maxSagV, a[1] + t * (b[1] - a[1]))
            }
        }
        return minOf(maxSagV, seg[seg.size - 1][1])
    }

    companion object {
        /** 转把怠速电压（实测松手就是 0.8V） */
        const val idleThrottleV = 0.8f
        /** 每 1V 转把对应的压降（v1.0.31 由 3,096 帧标定为 1.35，v1.0.53 保持） */
        const val vPerThrottleV = 1.35f
        /** 转把减去怠速超过这个值，就认为"明显在出力"（别用它校准电量） */
        const val loadedThrottleV = 1.2f

        /** 速度 → 压降（V）实测折线：{km/h, sag} */
        @JvmField
        val SPEED_SAG = arrayOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(10f, 0.8f),
            floatArrayOf(20f, 2.7f),
            floatArrayOf(30f, 5.0f),
            floatArrayOf(45f, 6.0f)
        )
    }
}
