package com.hud.core

/**
 * 两点间球面距离。
 *
 * 参考实现：Google `android-maps-utils` 的 `SphericalUtil.computeDistanceBetween` 用的
 * 就是同一个公式（haversine，地球平均半径 6371008.8 m）。手机端每 1 秒两个点的间距
 * 只有几十米，haversine 与 WGS84 椭球严格解（`Location.distanceTo` 用的 Vincenty）
 * 的差值在 0.1% 以内，对"今天跑了多少公里"完全看不出区别。
 *
 * 之所以在 :core 里自己算一份而不是直接用 `Location.distanceTo`：这一层是纯 JVM，
 * 要能在单元测试里跑，不依赖 Android 框架。
 */
object Geo {

    /** 地球平均半径（米），与 IUGG 及 Google SphericalUtil 一致 */
    const val EARTH_R_M = 6371008.8

    /** 1 度纬度的大致弧长（米），用于测试与粗估 */
    const val M_PER_DEG_LAT = 111195.08

    @JvmStatic
    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!inRange(lat1, lon1) || !inRange(lat2, lon2)) return Double.NaN
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return EARTH_R_M * c
    }

    private fun inRange(lat: Double, lon: Double): Boolean =
        !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0
}
