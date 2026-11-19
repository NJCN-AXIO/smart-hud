package com.hud.core

/**
 * 没有实测电压时，把"上次手工录的电压"按里程往下推。
 *
 * 为什么必须推：控制器 67B 帧里没有电压（实车 2983 帧已证），骑手只能停车时看小程序记一次。
 * 如果界面一直显示那个记下来的数（76.3V 骑了 3 公里还是 76.3V），那是个**假数**，
 * 比显示 `--` 更误导——骑手会以为电还满着。
 *
 * 压降率有两个来源：
 *  - 学过的：两次手工锚点之间"掉了多少伏 / 跑了多少公里"，这是他自己那块电池的真实斜率；
 *  - 没学过的：按 (满电 - 欠压) / 满电续航 兜底，量级 0.19 V/km（78V、63V、80km）。
 */
object VoltDecay {

    /** 压降率的合理范围（V/km）：低于它说明没在骑，高于它说明电池/接线有问题 */
    const val MIN_V_PER_KM = 0.02f
    const val MAX_V_PER_KM = 0.80f

    /** 两个锚点至少隔这么远才拿来学，否则噪声比信号大 */
    const val LEARN_MIN_KM = 2.0f

    /** 兜底压降率：(满电-欠压)/满电续航 */
    @JvmStatic
    fun defaultVPerKm(vFull: Float, vEmpty: Float, rangeKm: Int): Float {
        if (rangeKm <= 0 || vFull <= vEmpty) return MIN_V_PER_KM
        val v = (vFull - vEmpty) / rangeKm
        return clamp(v)
    }

    /**
     * 推算当前电压：锚点电压减去"压降率 × 已跑里程"，最低夹到欠压值。
     * 不做线性外推到 0V —— 铅酸到欠压就该推车了，显示低于欠压没有意义。
     */
    @JvmStatic
    fun estimate(anchorV: Float, vPerKm: Float, kmSince: Float, vEmpty: Float): Float {
        if (anchorV.isNaN()) return Float.NaN
        val k = if (vPerKm.isNaN() || vPerKm <= 0f) MIN_V_PER_KM else clamp(vPerKm)
        val since = if (kmSince.isNaN() || kmSince < 0f) 0f else kmSince
        val v = anchorV - k * since
        return if (v < vEmpty) vEmpty else v
    }

    /**
     * 用两个手工锚点学压降率。
     * @return V/km；数据不可用（间隔太近、电压没降、降得太猛）时 NaN
     */
    @JvmStatic
    fun learnFromAnchors(v1: Float, km1: Float, v2: Float, km2: Float): Float {
        if (v1.isNaN() || v2.isNaN() || km1.isNaN() || km2.isNaN()) return Float.NaN
        val dk = km2 - km1
        if (dk < LEARN_MIN_KM) return Float.NaN
        val dv = v1 - v2
        if (dv <= 0.15f) return Float.NaN          // 没降或反而升（充电了），不学
        val k = dv / dk
        if (k < MIN_V_PER_KM || k > MAX_V_PER_KM) return Float.NaN
        return k
    }

    private fun clamp(v: Float): Float = when {
        v < MIN_V_PER_KM -> MIN_V_PER_KM
        v > MAX_V_PER_KM -> MAX_V_PER_KM
        else -> v
    }
}
