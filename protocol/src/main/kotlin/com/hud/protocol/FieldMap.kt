package com.hud.protocol

/**
 * 一个"字段提取器"：从原始帧里按 偏移 / 位宽 / 字节序 / 系数 / 偏置 取一个数值。
 *
 * 为什么不在协议类里写死字节位置：
 *   育宁控制器的协议没公开，电压/速度落在哪个字节是靠抓包标定出来的。
 *   字段映射做成参数，标定结果一填就生效，协议类本身不用改、不用重编。
 *
 * 取不到（未配置 / 越界）时返回 NaN —— 调用方必须能处理 NaN，绝不用 0 冒充。
 */
data class FieldMap(
    val offset: Int,
    val len: Int,
    val bigEndian: Boolean,
    val scale: Float,
    val bias: Float = 0f
) {
    init {
        require(len in 1..4) { "len must be 1..4, got $len" }
    }

    val enabled: Boolean get() = offset >= 0

    fun read(data: ByteArray?): Float {
        if (!enabled || data == null) return Float.NaN
        if (offset + len > data.size) return Float.NaN
        var v = 0L
        if (bigEndian) {
            for (i in 0 until len) v = (v shl 8) or (data[offset + i].toLong() and 0xFFL)
        } else {
            for (i in len - 1 downTo 0) v = (v shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return v.toFloat() * scale + bias
    }

    override fun toString(): String =
        "off=$offset len=$len ${if (bigEndian) "BE" else "LE"} ×$scale" +
                (if (bias != 0f) " +$bias" else "")

    companion object {
        @JvmField
        val NONE = FieldMap(-1, 2, true, 0.1f, 0f)
    }
}
