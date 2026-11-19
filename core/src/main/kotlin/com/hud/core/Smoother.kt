package com.hud.core

/**
 * 时间窗滑动平均。骑行时电压抖 1–2V，直接显示 0.1V 精度会跳得没法看。
 *
 * 环形缓冲 + 按时间倒序取，超过窗口的样本不再计入。
 * 传入 NaN 不当样本，直接返回当前均值（没有样本时返回 NaN）。
 */
class Smoother @JvmOverloads constructor(private val windowMs: Long, private val capacity: Int = 256) {

    private val ts = LongArray(capacity)
    private val value = FloatArray(capacity)
    private var count = 0
    private var head = 0

    fun add(nowMs: Long, v: Float): Float {
        if (v.isNaN()) return meanOr(nowMs)
        ts[head] = nowMs
        value[head] = v
        head = (head + 1) % capacity
        if (count < capacity) count++
        return meanOr(nowMs)
    }

    /** 当前窗口均值；窗口内没有样本则返回 NaN（绝不返回 0 冒充）。 */
    fun meanOr(nowMs: Long): Float {
        if (count == 0) return Float.NaN
        var sum = 0f
        var n = 0
        for (i in 0 until count) {
            val k = (head - 1 - i + capacity * 2) % capacity
            if (nowMs - ts[k] > windowMs) break      // 缓冲按时间递减，一旦超窗就停
            sum += value[k]
            n++
        }
        return if (n == 0) Float.NaN else sum / n
    }

    fun reset() {
        count = 0
        head = 0
    }

    val samples: Int get() = count
}
