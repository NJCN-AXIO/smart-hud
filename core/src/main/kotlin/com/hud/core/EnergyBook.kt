package com.hud.core

/**
 * 能耗窗口：最近 1 公里的平均功耗 Wh/km（对标小牛仪表的"最近 1km 平均功耗"）。
 *
 * 我们没有电流采样（67B 帧里没有这个字段，3820 帧逐字节验证过），所以不可能算瞬时 kW。
 * 但**能量守恒是绕不过去的**：电池是 72V×21Ah≈1512Wh，这段路掉了 x% 电量、跑了 y km，
 * 平均功耗就是 1512 * (x/100) / y Wh/km。这个数比"猜电流算功率"靠谱得多，
 * 而且是实测锚点（每次停车静置校准后的电量下降量）。
 *
 * 用法：每秒 add(本次里程, 当前电量%)；读 whPerKm(电池Wh)。
 */
class EnergyBook @JvmOverloads constructor(
    private val capacity: Int = 128,
    /** 统计窗口：最近 1 公里 */
    private val windowKm: Float = 1.0f,
    /** 窗口里至少要有这么多公里、掉这么多电量才算得出来（否则是噪声） */
    private val minKm: Float = 0.4f,
    private val minDropPct: Float = 0.5f,
) {
    private val kms = FloatArray(capacity)
    private val socs = FloatArray(capacity)
    private var size = 0
    private var head = 0

    fun add(km: Float, socPct: Float) {
        if (km.isNaN() || socPct.isNaN()) return
        if (size > 0) {
            val lastIdx = (head + size - 1) % capacity
            val lastKm = kms[lastIdx]
            val lastSoc = socs[lastIdx]
            // 里程倒退 = 用户清零了本次里程
            if (km + 0.05f < lastKm) { clear() }
            // 电量跳升 = 充电或重新校准，窗口里的旧样本已经没有意义
            else if (socPct > lastSoc + 1.0f) { clear() }
            else if (km - lastKm < 0.02f) {
                // 原地不动：只刷新最后一个点的电量，不新增样本（否则窗口全是同一个位置）
                socs[lastIdx] = socPct
                return
            }
        }
        val idx = if (size < capacity) (head + size) % capacity else {
            val old = head
            head = (head + 1) % capacity
            old
        }
        kms[idx] = km
        socs[idx] = socPct
        if (size < capacity) size++
    }

    /** 最近 windowKm 的平均 Wh/km；样本不够就是 NaN（界面显示 --） */
    fun whPerKm(packWh: Float): Float {
        if (size < 2 || packWh <= 0f || packWh.isNaN()) return Float.NaN
        val newest = (head + size - 1) % capacity
        val newestKm = kms[newest]
        val newestSoc = socs[newest]
        // 从最新往回找，取"刚好满窗口"的那一点（这样才是最近 1km，
        // 而不是把 5 公里前的老样本也拉进来平均）
        var pick = head
        for (j in size - 1 downTo 0) {
            val i = (head + j) % capacity
            if (newestKm - kms[i] >= windowKm) { pick = i; break }
        }
        val dKm = newestKm - kms[pick]
        val dSoc = socs[pick] - newestSoc
        if (dKm < minKm || dSoc < minDropPct) return Float.NaN
        return (packWh * dSoc / 100f / dKm)
    }

    fun clear() {
        size = 0
        head = 0
    }

    val samples: Int get() = size
}
