package com.hud.core

/**
 * 72V 铅酸（6×12V）电量估算。
 *
 * 电压 → 电量 本质上是查表：铅酸的放电曲线中段很平，不能线性。
 * 表锚点用"单块 12V 电池静置开路电压"的常见经验值 ×6，用户可改满电/空电两点线性重标定。
 *
 * 注意：骑行时电压比静置低 1.5–3.5V（压降），所以调用方要先做滑动平均（[Smoother]）。
 */
class SocModel(vFull: Float = 78.6f, vEmpty: Float = 63.8f) {

    private val full: Float = if (vFull > 0f) vFull else 78.6f
    private val empty: Float = if (vEmpty > 0f) vEmpty else 63.8f

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
        /**
         * 表的参考量程：满电 78.6V / 空电 63.8V（v1.0.53 按 11-19 实车全程重标）。
         *
         * 旧值 76.5 / 63.0 是拍脑袋定的，实测差得很远：
         *   - 真满电静置 **78.67V**（11-19 14:10:03）→ 旧表少 2.2V，导致骑行到 76~77V
         *     就被判成"充满了"，自动清本次里程、电量被拽到 100（当晚发生两次）；
         *   - 真耗尽带载 **58.26V**（00:06:29）→ 旧空电 63.0V 高 4.7V，
         *     于是"0%" 之后还能骑 10.6km（用户以为没电了，提前推车）。
         */
        const val V_FULL_REF = 78.6f
        const val V_EMPTY_REF = 63.8f

        /**
         * 参考表：整包静置电压 → 电量（%）。
         *
         * v1.0.53 按"静置电压 ↔ 已跑公里"实测曲线重排（11-19 那一趟，共 86km）：
         *   0km 77.9 / 22.5km 74.9 / 32.5km 73.3 / 52.5km 72.1 / 57.5km 70.6
         *   62.5km 69.6 / 67.5km 66.3 / 72.5km 63.9（之后是推车段，电压回升，不参与标定）
         * 注意中段 72~73V 是一条近乎平的平台（30% 的电对应 1.3V）——
         * 所以这一段**必须靠里程推**，光看电压一定会飘（见 analysis/57）。
         */
        @JvmField
        val V_REF = floatArrayOf(
            78.6f, 77.3f, 75.9f, 74.9f, 74.0f, 73.3f,
            72.6f, 72.1f, 70.6f, 66.3f, 63.8f
        )

        /**
         * 对应的电量（%）。按"静置电压 ↔ 已跑公里 / 可用 81km"折算，
         * 再考虑低端（72V 铅酸放到 63.8V 静置时已经基本骑不动）压平。
         */
        @JvmField
        val SOC = floatArrayOf(
            100f, 94f, 87f, 78f, 70f, 63f,
            50f, 41f, 28f, 15f, 0f
        )
    }
}
