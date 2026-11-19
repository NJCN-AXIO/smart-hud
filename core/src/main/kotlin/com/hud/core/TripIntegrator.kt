package com.hud.core

/**
 * 单次里程积分：km += v(km/h) × dt(h)。
 *
 * 两个实操细节（都是被真实路况逼出来的）：
 *  1. dt 上限 2 秒 —— App 被系统挂起/断流后，不能一次性补进一大段假里程；
 *  2. 低于 1 km/h 不计 —— 等红灯时里程不该慢慢爬升。
 *
 * 纯计算，不碰存储：持久化交给调用方（App 里是 TripTracker + Prefs）。
 */
class TripIntegrator @JvmOverloads constructor(initialKm: Float = 0f) {

    private var kmValue: Float = initialKm
    private var lastMs: Long = 0L

    val km: Float get() = kmValue

    /** 喂一个速度样本，返回累计里程。 */
    fun onSpeed(kmh: Float, nowMs: Long): Float {
        if (lastMs > 0L) {
            val dt = nowMs - lastMs
            if (dt in 1..MAX_DT_MS && kmh >= MIN_KMH) {
                kmValue += kmh * (dt / 3600000f)
            }
        }
        lastMs = if (kmh >= MIN_KMH) nowMs else 0L
        return kmValue
    }

    fun reset() {
        kmValue = 0f
        lastMs = 0L
    }

    fun restore(km: Float) {
        kmValue = km
    }

    /**
     * 直接加一段距离（公里）。
     *
     * 用途：速度字段还没标定时，App 拿不到速度，只能靠 GPS 相邻两点的球面距离累加。
     * 这条路径进来的距离已经由调用方校验过（过滤掉 GPS 漂移），这里只做累加。
     */
    fun addKm(deltaKm: Float): Float {
        if (deltaKm.isNaN() || deltaKm <= 0f) return kmValue
        kmValue += deltaKm
        return kmValue
    }

    companion object {
        const val MIN_KMH = 1.0f
        const val MAX_DT_MS = 2000L
    }
}
