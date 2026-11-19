package com.hud.protocol

/**
 * 育宁科技控制器的通知帧解析器（纯 JVM，无 Android 依赖）。
 *
 * 结构事实（抓包 + 育宁小程序源码双向确认）：
 *   - BLE，服务 0xFFF0，通知特征 0xFFF6（handle 0x000A），CCCD 0x000B
 *   - 控制器按 ~2Hz 推一帧，帧头 C9 9C；实车一帧是 **67 字节**，帧尾 CC
 *   - 早期抓到的 20 字节"帧"只是这一帧的前 20 个字节（残段），里面没有电压
 *
 * **67B 帧的 4..65 字节是加扰的**：育宁小程序 pages/index.vue 的
 * onBLECharacteristicValueChange 里有一组逆变换（见 [unscramble]），
 * 还原之后：
 *   - 电池电压 = (256 * f[63] + f[64]) / 100
 *   - 转把电压 = 0.019 * f[42]
 *   - 故障位   = f[47]（1转把 / 2刹车 / 4控制器 / 8霍尔 / 16欠压 / 64防盗 / 128P档）
 *   - 型号     = f[10]（37 默认）、车型 f[57]、仪表协议 f[50]、电压选择 f[43]
 *
 * 实车 3504 帧验证：解出 66.0–76.1V，首 73.9V / 末 71.6V，3504/3504 都在合理区间。
 *
 * 帧头/长度/帧尾任何一项不符都不套公式：AE02 那一路的帧如果也硬按 63/64 去读，
 * 会读出一堆"看着像电压"的垃圾值并混进电量计算里。
 */
class YuNingProtocol(
    private val voltageMap: FieldMap = FieldMap.NONE,
    private val speedMap: FieldMap = FieldMap.NONE
) {

    fun decode(raw: ByteArray?): YuNingFrame {
        if (raw == null || raw.isEmpty()) return YuNingFrame.EMPTY

        when (kind(raw)) {
            KIND_TELEMETRY -> {
                // 20B 只是 67B 帧的前 20 个字节（控制器推过来的残段）：电压在 f[63]/f[64]，
                // 这两个字节根本还没到，所以默认一律不出数。
                // 只有用户在设置里手工填了字段映射时才按他填的读（保留给"换控制器"的场景）。
                if (voltageMap.offset < 0) {
                    return YuNingFrame(raw, Float.NaN, Float.NaN, false, "20B 短帧（需完整 67B）")
                }
                var v = voltageMap.read(raw)
                var s = speedMap.read(raw)
                if (v.isNaN() || v < V_MIN || v > V_MAX) v = Float.NaN
                if (s.isNaN() || s < S_MIN || s > S_MAX) s = Float.NaN
                return YuNingFrame(raw, v, s, true, "手工映射（20B 帧）")
            }
            KIND_EXTENDED -> {
                if (raw.size != FRAME_LEN_EXT || (raw[FRAME_LEN_EXT - 1].toInt() and 0xFF) != TAIL) {
                    return YuNingFrame(raw, Float.NaN, Float.NaN, false,
                        "扩展帧（${raw.size}B，帧尾不符）")
                }
                // 育宁小程序 pages/index.vue 里 onBLECharacteristicValueChange 的还原公式：
                //   if (201 != l[0] || 156 != l[1] || 204 != l[66]) return "不是指令";
                //   for (e = 4; e <= 65; e++) { l[e]-=11; l[e]=85^l[e]; l[e]-=e; l[e]=19^l[e]; l[e]-=e; }
                //   v = (256*l[63] + l[64]) / 100        // 电池电压，1 位小数
                //   c = 0.019 * l[42]                    // 转把电压
                //   l[47] 的 bit0..bit7 = 转把/刹车/控制器/霍尔/欠压/空/防盗/P档
                val f = unscramble(raw)
                val v = (256 * f[63] + f[64]) / 100f
                val th = 0.019f * f[42]
                val vOk = v >= V_MIN && v <= V_MAX
                val note = when {
                    vOk -> "实测电压（育宁 67B · [63][64]/100）"
                    else -> "扩展帧（67B，电压越界 %.2f）".format(v)
                }
                return YuNingFrame(
                    raw,
                    if (vOk) v else Float.NaN,
                    Float.NaN,
                    vOk,
                    note,
                    throttle = if (th >= 0f && th <= 5f) th else Float.NaN,
                    faults = f[47].toInt() and 0xFF,
                    model = f[10]
                )
            }
            else -> {
                return YuNingFrame(raw, Float.NaN, Float.NaN, false, "未识别帧（${raw.size}B）")
            }
        }
    }

    /** 帧型：0=不认识 / 1=20B 遥测帧 / 2=67B 扩展帧（同一帧头） */
    fun kind(raw: ByteArray?): Int {
        if (raw == null || raw.size < 2) return KIND_UNKNOWN
        if (raw[0] != H0 || raw[1] != H1) return KIND_UNKNOWN
        if (raw.size == FRAME_LEN) return KIND_TELEMETRY
        if (raw.size > FRAME_LEN) return KIND_EXTENDED
        return KIND_UNKNOWN
    }

    fun isKnownFrame(raw: ByteArray?): Boolean = kind(raw) != KIND_UNKNOWN

    companion object {
        const val KIND_UNKNOWN = 0
        const val KIND_TELEMETRY = 1
        const val KIND_EXTENDED = 2
        /** 帧头 */
        const val H0: Byte = 0xC9.toByte()
        const val H1: Byte = 0x9C.toByte()
        /** 67B 帧的帧尾 */
        const val TAIL = 0xCC

        /** 抓包观测到的定长（短帧） */
        const val FRAME_LEN = 20
        /** 实车 67B 帧 */
        const val FRAME_LEN_EXT = 67

        /** 合理性区间：越界说明映射填错了，不往 UI 抛垃圾 */
        const val V_MIN = 30f
        const val V_MAX = 100f
        const val S_MIN = 0f
        const val S_MAX = 160f

        /** 抓包拿到的那条 20 字节帧（91 条全同），测试里当基线用 */
        const val CAPTURED_FRAME_HEX =
            "C9 9C 94 05 59 59 69 59 6A 81 1E 4D 75 75 75 75 1D 1D 1D 1D"

        /** 实车（14:11 那趟）第一帧，解出来是 73.9V / 转把 0.8V / P档 */
        const val RIDE_FRAME_HEX =
            "C9 9C 94 05 59 59 69 59 6A 81 1E 4D 75 75 75 75 1D 1D 1D 1D 25 81 25 7F 0D 0D 2A 0D " +
            "75 75 75 75 1D 1D 1D 37 38 2B F5 19 72 72 49 26 49 0A 49 69 11 12 0E 16 F5 A9 CD 3E " +
            "41 D8 F3 4A 49 49 49 E6 23 DC CC"

        /**
         * 育宁小程序那套加扰的逆变换：只处理 4..65 这 62 个字节，返回还原后的整帧。
         * 单测会拿实车帧验证（73.9V / 0.8V 转把 / P档）。
         */
        @JvmStatic
        fun unscramble(raw: ByteArray): IntArray {
            val f = IntArray(raw.size)
            for (i in raw.indices) f[i] = raw[i].toInt() and 0xFF
            for (e in 4..65) {
                if (e >= raw.size) break
                var x = f[e]
                x -= 11
                x = 85 xor (x and 0xFF)
                x -= e
                x = 19 xor (x and 0xFF)
                x -= e
                f[e] = x
            }
            return f
        }

        /**
         * 历史候选映射（[2..3]×0.05）。已证：这两个字节在每一帧里都一样（0x0594），
         * **不是电压**；留着只给"手工映射"这条老路和 FieldMap 单测用。
         */
        @JvmField
        val CANDIDATE_VOLTAGE = FieldMap(offset = 2, len = 2, bigEndian = false, scale = 0.05f, bias = 0f)
    }
}
