package com.hud.core

/**
 * 电量百分比的显示层滤波（v1.0.36）。
 *
 * 为什么还要再滤一层：电压已经滤过（[VoltFilter]），但"电压→百分比"是查表，
 * 表在低端很陡（68.0V→66.0V 就差好几格），电压上 0.3V 的正常抖动会放大成 5–10 个百分点；
 * 再叠加 `ocvCand` 45 秒窗与瞬时值之间的来回切换，用户就看到"同一时刻 51% 跳到 17%"。
 *
 * 做法照 BMS 的 SOC 显示惯例：**对"算出来的百分比"取时间窗中位数**，不是取电压。
 * 中位数天生抗单点异常（一次坏帧不会带偏显示），窗口 6 秒 → 真实变化最多慢 3 秒，
 * 骑行时感觉不出来，但不会再出现两位数跳变。
 *
 * 与 [VoltFilter] 的区别：这里必须接受 0 —— 0% 是合法电量，[VoltFilter.add] 把 v<=0 当非法丢弃。
 */
class SocSmooth @JvmOverloads constructor(
    /** 时间窗（毫秒）：6 秒 ≈ 12 帧（2Hz），足够压掉单帧异常 */
    private val windowMs: Long = 6000L,
    /** 环形缓冲上限 */
    private val capacity: Int = 64,
    /** 取哪个分位：0.5 = 中位数（抗单点异常又不偏向两端） */
    private val quantile: Double = 0.5
) {

    private val ts = LongArray(capacity)
    private val vs = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private val scratch = FloatArray(capacity)

    /** 喂一个百分比；NaN 会被忽略。返回当前窗口的稳健值。 */
    fun add(nowMs: Long, soc: Float): Float {
        if (soc.isNaN()) return value(nowMs)
        ts[head] = nowMs
        vs[head] = soc.coerceIn(0f, 100f)
        head = (head + 1) % capacity
        if (count < capacity) count++
        return value(nowMs)
    }

    fun value(nowMs: Long): Float {
        if (count == 0) return Float.NaN
        var n = 0
        for (i in 0 until count) {
            val idx = (head - count + i + capacity) % capacity
            if (nowMs - ts[idx] <= windowMs) scratch[n++] = vs[idx]
        }
        if (n == 0) {
            // 全过期：拿最新一个兜底（数据流断了由上层决定冻结/清空）
            return vs[(head - 1 + capacity) % capacity]
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
        val at = ((n - 1) * quantile.coerceIn(0.0, 1.0)).toInt().coerceIn(0, n - 1)
        return scratch[at]
    }

    fun reset() {
        count = 0
        head = 0
    }

    /** 窗口内的有效样本数 */
    fun samplesIn(nowMs: Long): Int {
        var n = 0
        for (i in 0 until count) {
            val idx = (head - count + i + capacity) % capacity
            if (nowMs - ts[idx] <= windowMs) n++
        }
        return n
    }
}
