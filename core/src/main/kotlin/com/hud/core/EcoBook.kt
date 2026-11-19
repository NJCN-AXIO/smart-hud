package com.hud.core

/** 一条电压记录：时间、当时电压、当时的本次里程。 */
data class EcoEntry(val ts: Long, val v: Float, val km: Float)

/** 学一段的结果。newRate 为 null 表示这次没有形成有效学习段。 */
data class LearnResult(val learned: Boolean, val newRate: Float?, val segmentRate: Float?)

/**
 * 耗电账本：用两次「电压 + 当时里程」的读数，学出「每公里掉多少电量百分」。
 *
 * 为什么学 %/km 而不是 V/km：
 *   铅酸放电曲线中段极平、末段陡降，电压不是线性量。用两次读数的电压差算 V/km，
 *   会把前段（表面电荷消散、掉得飞快）当成常态 —— 中段严重低估续航、末段反而乐观。
 *   改学「电量百分/公里」，再配合 [SocModel] 反查电压，三者的口径才自洽。
 *
 * 纯计算：账本条目由调用方存（App 里存在 SharedPreferences 的 vbook 字段）。
 */
class EcoBook @JvmOverloads constructor(rate: Float = 0f, samples: Int = 0) {

    private var currentRate: Float = if (rate > 0f) rate else 0f
    private var sampleCount: Int = samples

    val rate: Float get() = currentRate
    val n: Int get() = sampleCount

    /**
     * 判断这份新读数能不能和上一条形成一段有效学习，能就把新速率算出来（EMA 0.7 旧 + 0.3 新）。
     * 注意：不修改内部状态，状态由 [commit] 写入 —— 这样调用方可以先落盘再提交，失败不留脏值。
     */
    fun evaluate(prev: EcoEntry, now: EcoEntry, model: SocModel): LearnResult {
        val dkm = now.km - prev.km
        if (dkm < MIN_SEG_KM) return LearnResult(false, null, null)

        val socPrev = model.soc(prev.v)
        val socNow = model.soc(now.v)
        if (socPrev.isNaN() || socNow.isNaN()) return LearnResult(false, null, null)

        val dsoc = socPrev - socNow               // 正数 = 电量在掉
        if (dsoc < MIN_SEG_SOC) return LearnResult(false, null, null)   // 没掉电（刚充过/静置）→ 不学

        val seg = dsoc / dkm
        if (seg < MIN_RATE || seg > MAX_RATE) return LearnResult(false, null, null)

        val next = if (currentRate <= 0.001f) seg else currentRate * 0.7f + seg * 0.3f
        return LearnResult(true, next, seg)
    }

    fun commit(result: LearnResult): Boolean {
        if (!result.learned) return false
        currentRate = result.newRate ?: currentRate
        sampleCount++
        return true
    }

    /** 哨点电量 + 已跑里程 → 当前电量（%）。没学会耗电速度时原样返回锚点电量。 */
    fun socNow(anchorSoc: Float, anchorKm: Float, nowKm: Float): Float {
        if (currentRate <= 0.001f) return anchorSoc
        val kmSince = maxOf(0f, nowKm - anchorKm)
        return maxOf(0f, anchorSoc - currentRate * kmSince)
    }

    /** 当前电量还能跑多少公里。耗电速率未知时返回 NaN —— 不瞎猜。 */
    fun rangeKm(socPercent: Float): Float {
        if (currentRate <= 0.001f || socPercent.isNaN()) return Float.NaN
        return maxOf(0f, socPercent / currentRate)
    }

    fun clear() {
        currentRate = 0f
        sampleCount = 0
    }

    companion object {
        /** 账本最多留多少条（老条目滚掉） */
        const val MAX_ENTRIES = 24
        /** 小于这个里程差，认为还是同一次录入（防手抖重复点） */
        const val SAME_KM = 0.3f
        const val SAME_MS = 120000L
        /** 一段至少要有这么多公里，否则噪声大于信号 */
        const val MIN_SEG_KM = 2.0f
        /** 合理的 %/km 区间，超出说明中途充过电或读数有误 */
        const val MIN_RATE = 0.2f
        const val MAX_RATE = 20.0f
        /** 这一段至少掉这么多百分点才算有效（否则可能只是静置/压降抖动） */
        const val MIN_SEG_SOC = 1.5f
    }
}
