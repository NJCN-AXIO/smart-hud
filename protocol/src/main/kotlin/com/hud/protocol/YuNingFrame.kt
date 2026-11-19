package com.hud.protocol

/**
 * 解出来的一帧。纯数据，不含任何 Android 类型 —— 所以能在 JVM 上直接单元测试。
 */
data class YuNingFrame(
    val raw: ByteArray,
    val voltage: Float,
    val speed: Float,
    val recognized: Boolean,
    val note: String,
    /** 转把电压（育宁 67B 帧 f[42] × 0.019），NaN = 没解出来 */
    val throttle: Float = Float.NaN,
    /** 故障位（f[47]）：1转把 2刹车 4控制器 8霍尔 16欠压 64防盗 128P档 */
    val faults: Int = 0,
    /** 控制器型号编号（f[10]，37 = 默认） */
    val model: Int = -1
) {
    val hasVoltage: Boolean get() = !voltage.isNaN()
    val hasSpeed: Boolean get() = !speed.isNaN()

    /** 故障位的可读文案；没有故障返回空串 */
    fun faultText(): String {
        if (faults == 0) return ""
        val out = ArrayList<String>(4)
        if (faults and FAULT_THROTTLE != 0) out.add("转把故障")
        if (faults and FAULT_BRAKE != 0) out.add("刹车")
        if (faults and FAULT_CONTROLLER != 0) out.add("控制器故障")
        if (faults and FAULT_HALL != 0) out.add("霍尔故障")
        if (faults and FAULT_LOW_BATTERY != 0) out.add("电池欠压")
        if (faults and FAULT_ANTITHEFT != 0) out.add("防盗")
        if (faults and FAULT_P_GEAR != 0) out.add("P档")
        return out.joinToString("·")
    }

    fun hex(): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.size * 3)
        for ((i, b) in raw.withIndex()) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YuNingFrame) return false
        return raw.contentEquals(other.raw) &&
                voltage == other.voltage &&
                speed == other.speed &&
                recognized == other.recognized
    }

    override fun hashCode(): Int {
        var r = raw.contentHashCode()
        r = 31 * r + voltage.hashCode()
        r = 31 * r + speed.hashCode()
        r = 31 * r + recognized.hashCode()
        return r
    }

    override fun toString(): String =
        "YuNingFrame(v=$voltage, s=$speed, recognized=$recognized, len=${raw.size}, note=$note)"

    companion object {
        const val FAULT_THROTTLE = 0x01
        const val FAULT_BRAKE = 0x02
        const val FAULT_CONTROLLER = 0x04
        const val FAULT_HALL = 0x08
        const val FAULT_LOW_BATTERY = 0x10
        const val FAULT_ANTITHEFT = 0x40
        const val FAULT_P_GEAR = 0x80

        @JvmField
        val EMPTY = YuNingFrame(ByteArray(0), Float.NaN, Float.NaN, false, "空帧")
    }
}
