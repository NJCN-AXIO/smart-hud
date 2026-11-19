package com.hud.core

/**
 * 72V 铅酸（6×12V）电量估算。
 *
 * 电压 → 电量 本质上是查表：铅酸的放电曲线中段很平，不能线性。
 * 表锚点用"单块 12V 电池静置开路电压"的常见经验值 ×6，用户可改满电/空电两点线性重标定。
 *
 * 注意：骑行时电压比静置低 1.5–3.5V（压降），所以调用方要先做滑动平均（[Smoother]）。
 */
class SocModel(vFull: Float = 76.5f, vEmpty: Float = 63.0f) {

    private val full: Float = if (vFull > 0f) vFull else 76.5f
    private val empty: Float = if (vEmpty > 0f) vEmpty else 63.0f

    /** 电压 → 电量（%）。NaN 进、NaN 出；超出量程按 0/100 夹紧。 */
    fun soc(voltage: Float): Float {
        if (voltage.isNaN()) return Float.NaN
        val span = maxOf(1f, full - empty)
        val vRef = V_EMPTY_REF + (voltage - empty) * (V_FULL_REF - V_EMPTY_REF) / span

        if (vRef >= V_REF[0]) return 100f
        if (vRef <= V_REF[V_REF.lastIndex]) return 0f
        for (i in 0 until V_REF.lastIndex) {
            if (vRef <= V_REF[i] && vRef >= V_REF[i + 1]) {
                val t = (vRef - V_REF[i + 1]) / (V_REF[i] - V_REF[i + 1])
                return SOC[i + 1] + t * (SOC[i] - SOC[i + 1])
            }
        }
        return 0f
    }

    /**
     * 反查：电量（%）→ 电压。
     * 推算电压必须走这条路——铅酸中段很平，按 V/km 线性外推会跑偏，
     * 按 % 推算再反查电压才和电量曲线自洽。
     */
    fun voltageOf(socPercent: Float): Float {
        if (socPercent.isNaN()) return Float.NaN
        val s = socPercent.coerceIn(0f, 100f)
        val vRef: Float = when {
            s >= SOC[0] -> V_REF[0]
            s <= SOC[SOC.lastIndex] -> V_REF[V_REF.lastIndex]
            else -> {
                var out = V_REF[V_REF.lastIndex]
                for (i in 0 until SOC.lastIndex) {
                    if (s <= SOC[i] && s >= SOC[i + 1]) {
                        val t = (s - SOC[i + 1]) / (SOC[i] - SOC[i + 1])
                        out = V_REF[i + 1] + t * (V_REF[i] - V_REF[i + 1])
                        break
                    }
                }
                out
            }
        }
        val span = maxOf(1f, full - empty)
        return empty + (vRef - V_EMPTY_REF) * span / (V_FULL_REF - V_EMPTY_REF)
    }

    companion object {
        /** 表的参考量程：满电 76.5V / 空电 63.0V */
        const val V_FULL_REF = 76.5f
        const val V_EMPTY_REF = 63.0f

        /** 参考表：整包电压 → 电量（%） */
        @JvmField
        val V_REF = floatArrayOf(
            76.5f, 75.6f, 74.7f, 73.8f, 72.9f, 72.0f,
            71.4f, 70.8f, 70.2f, 69.6f, 63.0f
        )

        @JvmField
        val SOC = floatArrayOf(
            100f, 90f, 80f, 70f, 60f, 50f,
            40f, 30f, 20f, 10f, 0f
        )
    }
}
