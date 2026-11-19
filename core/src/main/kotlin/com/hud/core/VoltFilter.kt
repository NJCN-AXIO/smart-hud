package com.hud.core

/**
 * 端电压中位数滤波（v1.0.1：改成按**时间窗**取中位数）。
 *
 * 为什么需要它：育宁控制器 67B 帧里的电压字段 `(256*f[63]+f[64])/100` 是"瞬时采样"，
 * 实测同一分钟内会在 58–69V 之间跳 ±5V，小程序显示的也是这个未滤波的瞬时值。
 * 骑手需要的不是"某一毫秒的采样"，而是"现在大概多少伏"——参考 DigiHUD / Garmin 码表
 * 这类仪表的做法：显示值先做稳健滤波再上屏。
 *
 * 为什么用中位数：中位数能直接丢掉 1–2 个离谱采样，滑动平均会被离群值拖偏
 * （混进一个 58V，均值要好几秒才爬回来）。
 *
 * 为什么改成时间窗（v1.0.1）：原来固定"9 个样本"。控制器推帧的速率并不恒定
 * （实测 2Hz，但重连后、切优先级后会短暂变快），9 个样本可能只覆盖 0.4 秒、
 * 也可能覆盖 9 秒 —— 同样的代码在两种速率下平滑程度差 20 倍。
 * 按时间窗取样本，才不会"换个手机/换条链路就换一种手感"。
 */
class VoltFilter @JvmOverloads constructor(
    /** 时间窗：只在这一段时间内的采样参与统计 */
    private val windowMs: Long = 5000L,
    /** 环形缓冲上限：2Hz × 5s = 10，留足余量给突发速率 */
    private val capacity: Int = 64,
    /**
     * 取哪个分位数（0..1）。
     *
     * 为什么不是 0.5（中位数）：这个电压字段的分布是**单边偏低**的——
     * 实车 313 帧统计 p50=65.5、p75=67.6、p90=68.3，而小程序同时刻显示 68.6。
     * 偏低的那一截是带载压降（灯光/控制器自耗）的采样，骑手眼里的"电压"是小程序那个偏高的数。
     * 用 0.75 分位：既丢掉最离谱的高值，又不会被带载压降拖下去，显示值和小程序对得上。
     *
     * 停车校准电量用 [windowMs]=45s + 0.9 分位：静置时该字段仍会往下掉几个伏，
     * 0.9 分位更接近开路电压，查表得到的电量才可信。
     */
    private val quantile: Double = 0.75,
) {

    private val ts = LongArray(capacity)
    private val vs = FloatArray(capacity)
    private var head = 0          // 下一个写入位置
    private var count = 0
    private val scratch = FloatArray(capacity)

    /** 最近一次算出的中位数（窗口内没有样本时用它兜底，别让数字闪空） */
    private var last = Float.NaN

    /** 喂一个采样；NaN / 非法值会被忽略。返回当前分位值。 */
    fun add(nowMs: Long, v: Float): Float {
        if (v.isNaN() || v <= 0f) return value(nowMs)
        ts[head] = nowMs
        vs[head] = v
        head = (head + 1) % capacity
        if (count < capacity) count++
        return value(nowMs)
    }

    /** 时间窗内的分位值；窗口内一个样本都没有时返回最近一次的结果 */
    fun value(nowMs: Long): Float = quantileOf(nowMs, quantile)

    /**
     * 任意分位值（v1.0.9）。
     *
     * 之前 [median] 直接 `= value(nowMs)`，也就是"中位数"其实返回的是 0.9 分位值。
     * 后果很实在：`calibrateSocAtRest()` 里有一道"静置窗是否稳定"的闸门，
     * 比的是 `value()` 和 `median()` 的差 —— 同一个数相减永远是 0，闸门从来没生效过。
     * 于是"刚松油门/刚起步"的窗口也会被当成静置平台，拿它当电量锚点。
     */
    fun quantileOf(nowMs: Long, q: Double): Float {
        if (count == 0) return Float.NaN
        var n = 0
        for (i in 0 until count) {
            val idx = (head - count + i + capacity) % capacity
            // 时间戳比"现在"还新的（时钟回退/乱序）也收进来，免得丢掉刚到的样本
            if (nowMs - ts[idx] <= windowMs) {
                scratch[n++] = vs[idx]
            }
        }
        if (n == 0) {
            // 全过期：拿最新一个兜底（数据流断了由上层负责清空，不是这里的事）
            val idx = (head - 1 + capacity) % capacity
            return vs[idx]
        }
        for (i in 1 until n) {
            val x = scratch[i]
            var j = i - 1
            while (j >= 0 && scratch[j] > x) {
                scratch[j + 1] = scratch[j]
                j--
            }
            scratch[j + 1] = x
        }
        val at = ((n - 1) * q.coerceIn(0.0, 1.0)).toInt().coerceIn(0, n - 1)
        if (q == quantile) last = scratch[at]
        return scratch[at]
    }

    /** 真·中位数（0.5 分位）。用于判断"这个窗是否真的静置"。 */
    fun median(nowMs: Long): Float = quantileOf(nowMs, 0.5)

    fun reset() {
        count = 0
        head = 0
        last = Float.NaN
    }

    /** 已经攒了多少个有效采样（用来判断"滤波是否已经收敛，可以拿去当锚点"） */
    val samples: Int get() = count

    /** 当前窗口里有多少个采样（判断"这一窗是否真的都是静置数据"） */
    fun samplesIn(nowMs: Long): Int {
        var n = 0
        for (i in 0 until count) {
            val idx = (head - count + i + capacity) % capacity
            if (nowMs - ts[idx] <= windowMs) n++
        }
        return n
    }
}
