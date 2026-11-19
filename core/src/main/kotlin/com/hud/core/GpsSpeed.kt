package com.hud.core

/**
 * 用相邻两个定位点的距离/时间算瞬时速度。
 *
 * 为什么需要它：Android 的 `Location.getSpeed()` 在低速、刚出隧道、室内飘点时经常
 * 直接给 0 或者干脆 `hasSpeed()==false`，只靠它骑手会看到"骑着车却显示 0km/h"。
 * 这里用位移÷时间兜底，并对明显不合理的输入（时间间隔太长/太短、位移为 0）返回 NaN，
 * 交给上层决定要不要显示。
 */
object GpsSpeed {

    /** 相邻定位点的时间差允许范围（毫秒）：太短会放大噪声，太长说明中间丢过点 */
    const val MIN_DT_MS = 500L
    const val MAX_DT_MS = 5000L

    /** 单次位移上限（米）：城市里 GPS 跳点常有几十米，超过就不像是真的骑了 */
    const val MAX_DIST_M = 40f

    /**
     * @param distanceM 两点间距离（米，球面距离）
     * @param dtMs      两点时间差（毫秒）
     * @return km/h；数据不可用时 NaN
     */
    @JvmStatic
    fun kmh(distanceM: Float, dtMs: Long): Float {
        if (distanceM.isNaN() || distanceM <= 0f || distanceM > MAX_DIST_M) return Float.NaN
        if (dtMs < MIN_DT_MS || dtMs > MAX_DT_MS) return Float.NaN
        // m/ms × 3600 = km/h
        return distanceM / dtMs * 3600f
    }

    /** 展示用：取两个来源里"更像真的那个"。协议速度优先，其次 GPS。 */
    @JvmStatic
    fun pick(protocolKmh: Float, gpsKmh: Float): Float = when {
        !protocolKmh.isNaN() && protocolKmh >= 0f -> protocolKmh
        !gpsKmh.isNaN() && gpsKmh >= 0f -> gpsKmh
        else -> Float.NaN
    }
}
