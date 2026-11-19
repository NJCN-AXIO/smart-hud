package com.hud.core

/**
 * 码表增强统计：最高速度（MXS）/ 平均速度（AVS）/ 骑行时长。
 *
 * 口径照 KT-LCD8S、King-Meter KM5S 那一套传统码表的惯例：
 *  - 平均速度用**运动时间**算，停车等单的时间不算进去（否则等 10 分钟接单，
 *    平均速度会掉到 3km/h，看着像个坏秤）；
 *  - 最高速度只在真实行驶时刷新，停车时的 GPS 噪声（2–6km/h）不能当"最高"；
 *  - 时长同样是运动时长，和码表的"单次骑行时间"一致。
 *
 * 纯计算，不碰控制器、不依赖新硬件：tick 里把"本次里程 + 当前速度"喂进来即可。
 */
class TripStats @JvmOverloads constructor(
    private val movingSpeedKmh: Float = 2.0f,
    /** 速度掉下去之后多久算"停下来了"：GPS 一秒一个点，掉一帧不代表停了 */
    private val holdMs: Long = 6000L,
) {
    /** 本次最高速度（km/h），没跑起来过就是 NaN */
    var maxKmh: Float = Float.NaN
        private set

    /** 运动时长（毫秒） */
    var movingMs: Long = 0L
        private set

    private var lastTs = -1L
    private var lastKm = Float.NaN
    private var lastMoveTs = -1L
    /** 正在"保持期"里（速度掉了但还没到判定停车）：这段时长最后要扣掉，否则每停一次就虚增 6 秒 */
    private var tailPending = false

    fun update(nowMs: Long, tripKm: Float, speedKmh: Float) {
        if (tripKm.isNaN()) return
        // 第一帧没有时间差；系统卡顿/后台被冻结时 dt 可能是几十秒，别一口气把时长灌进去
        val dt = if (lastTs > 0) (nowMs - lastTs).coerceIn(0L, 3000L) else 0L

        // "在动"只认一件事：**里程在长**。这就是码表的量法（轮子转了才算走）。
        // 不能拿速度当判据：停车时 GPS 速度常年 2–6km/h 地抖，按速度算会把
        // "停着等单"记成"骑了 149 秒"，均速也被拖垮（第一版单测就是这么挂的）。
        val grew = !lastKm.isNaN() && tripKm - lastKm >= 0.001f
        if (grew) lastMoveTs = nowMs
        val moving = lastMoveTs > 0 && nowMs - lastMoveTs <= holdMs

        if (lastTs > 0 && moving) movingMs += dt
        if (grew) {
            tailPending = false
        } else if (moving) {
            tailPending = true
        } else if (tailPending) {
            // 刚判定停车：把"最后一次真实位移之后"的那段 hold 时间扣回去。
            // 不扣的话，每停一次运动时长就白长 6 秒，平均速度会一路偏低（30s 的一趟虚增 20%）。
            movingMs = (movingMs - (nowMs - lastMoveTs).coerceIn(0L, holdMs)).coerceAtLeast(0L)
            tailPending = false
        }

        // 最高速度同样只在"真的在走"的时刻记
        if (moving && !speedKmh.isNaN() && speedKmh >= movingSpeedKmh
            && (maxKmh.isNaN() || speedKmh > maxKmh)) maxKmh = speedKmh

        lastTs = nowMs
        lastKm = tripKm
    }

    /** 平均速度：运动时间不足 30 秒不给值（刚开始骑算出来会乱跳） */
    fun avgKmh(tripKm: Float): Float {
        if (movingMs < 30_000L || tripKm.isNaN() || tripKm <= 0f) return Float.NaN
        return (tripKm * 3_600_000.0 / movingMs.toDouble()).toFloat()
    }

    val movingSec: Long get() = movingMs / 1000L

    /** 进程被杀/重装后恢复（服务每次 tick 会把这两个数落到 prefs） */
    fun restore(max: Float, ms: Long) {
        if (!max.isNaN() && max > 0f) maxKmh = max
        movingMs = if (ms > 0L) ms else 0L
    }

    fun reset() {
        maxKmh = Float.NaN
        movingMs = 0L
        lastTs = -1L
        lastKm = Float.NaN
        lastMoveTs = -1L
        tailPending = false
    }
}
