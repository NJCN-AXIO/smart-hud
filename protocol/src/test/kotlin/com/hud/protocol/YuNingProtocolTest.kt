package com.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议层单元测试（纯 JVM，`gradle :protocol:test` 就跑，不需要手机）。
 *
 * 断言用的全是真实数据：
 *   - 抓包里那条 20 字节帧（91 条逐字节相同）
 *   - 小程序截图上的三个电池电压：69.7V / 71.4V / 72.3V
 */
class YuNingProtocolTest {

    private fun hexToBytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    /** 用真实帧骨架，只替换 bytes[2..3] 为某个电压的编码 */
    private fun frameOf(voltage: Float): ByteArray {
        val raw = hexToBytes(YuNingProtocol.CAPTURED_FRAME_HEX)
        val enc = Math.round(voltage / 0.05f)
        raw[2] = (enc and 0xFF).toByte()
        raw[3] = ((enc shr 8) and 0xFF).toByte()
        return raw
    }

    @Test
    fun `显式手工映射仍能读 20B 帧（历史候选，并非真电压）`() {
        // CANDIDATE_VOLTAGE（[2..3]×0.05）现在只当"字段映射能用"的探针：
        // 0x0594 在每一帧都不变，已证它不是电压；真电压在 67B 帧的 f[63]/f[64]。
        val raw = hexToBytes(YuNingProtocol.CAPTURED_FRAME_HEX)
        val f = YuNingProtocol(YuNingProtocol.CANDIDATE_VOLTAGE).decode(raw)

        assertTrue("显式给了映射就按映射读", f.recognized)
        assertTrue(f.hasVoltage)
        assertEquals(71.4f, f.voltage, 0.01f)
    }

    @Test
    fun `FieldMap 能把任意编码还原成电压`() {
        val p = YuNingProtocol(YuNingProtocol.CANDIDATE_VOLTAGE)
        // 20:06 → 69.7V、20:42 → 71.4V、21:06 → 72.3V（充电中递增）
        for (v in listOf(69.7f, 71.4f, 72.3f)) {
            val f = p.decode(frameOf(v))
            assertEquals("电压 $v 应原样解回", v, f.voltage, 0.051f)
        }
    }

    @Test
    fun `20B 短帧不出电压（电压在 63 64 字节，短帧还没到）`() {
        val f = YuNingProtocol().decode(hexToBytes(YuNingProtocol.CAPTURED_FRAME_HEX))

        assertFalse("20B 只是残段，不能出数", f.recognized)
        assertFalse(f.hasVoltage)
        assertTrue(f.note.contains("20B"))
    }

    @Test
    fun `帧头不符的帧不套映射（防 AE02 那一路污染电压）`() {
        val raw = hexToBytes(YuNingProtocol.CAPTURED_FRAME_HEX)
        raw[0] = 0x11
        raw[1] = 0x22
        val f = YuNingProtocol(YuNingProtocol.CANDIDATE_VOLTAGE).decode(raw)

        assertFalse(f.recognized)
        assertTrue("非育宁帧不出电压", f.voltage.isNaN())
        assertTrue(f.note.startsWith("未识别帧"))
    }

    @Test
    fun `长度不符的帧不出数`() {
        val raw = hexToBytes(YuNingProtocol.CAPTURED_FRAME_HEX).copyOf(12)
        val f = YuNingProtocol(YuNingProtocol.CANDIDATE_VOLTAGE).decode(raw)

        assertFalse(f.recognized)
        assertTrue(f.voltage.isNaN())
    }

    @Test
    fun `实车 67 字节帧解出实测电压 73_9V`() {
        // 14:11 那趟的第一帧（原始字节是加扰的，按小程序的公式还原后才能读）
        val raw = hexToBytes(YuNingProtocol.RIDE_FRAME_HEX)
        assertEquals(67, raw.size)

        val p = YuNingProtocol()
        assertEquals("应判为扩展帧", YuNingProtocol.KIND_EXTENDED, p.kind(raw))
        val f = p.decode(raw)

        assertTrue("67B 帧应能出电压", f.recognized)
        assertTrue(f.hasVoltage)
        assertEquals(73.9f, f.voltage, 0.05f)
        assertEquals("转把电压", 0.8f, f.throttle, 0.05f)
        assertTrue("这一帧带 P档", f.faultText().contains("P档"))
        assertTrue("文案要说明是实测", f.note.contains("实测电压"))
    }

    @Test
    fun `帧尾不是 CC 的 67 字节帧不出数`() {
        val raw = hexToBytes(YuNingProtocol.RIDE_FRAME_HEX)
        raw[66] = 0x00
        val f = YuNingProtocol().decode(raw)

        assertFalse(f.recognized)
        assertTrue(f.voltage.isNaN())
    }

    @Test
    fun `帧型判定三种情况`() {
        val p = YuNingProtocol(YuNingProtocol.CANDIDATE_VOLTAGE)
        val tele = hexToBytes(YuNingProtocol.CAPTURED_FRAME_HEX)
        val ext = ByteArray(67).also { tele.copyInto(it, 0) }
        val bad = ByteArray(67).also { it[0] = 0x11.toByte(); it[1] = 0x22.toByte() }

        assertEquals(YuNingProtocol.KIND_TELEMETRY, p.kind(tele))
        assertEquals(YuNingProtocol.KIND_EXTENDED, p.kind(ext))
        assertEquals(YuNingProtocol.KIND_UNKNOWN, p.kind(bad))
        assertEquals(YuNingProtocol.KIND_UNKNOWN, p.kind(null))
    }

    @Test
    fun `离谱的数值被区间挡掉`() {
        val raw = hexToBytes(YuNingProtocol.CAPTURED_FRAME_HEX)
        raw[2] = 0xFF.toByte()
        raw[3] = 0xFF.toByte()          // 65535 × 0.05 = 3276.75V
        val f = YuNingProtocol(YuNingProtocol.CANDIDATE_VOLTAGE).decode(raw)

        assertTrue(f.recognized)
        assertTrue("越界值必须丢成 NaN", f.voltage.isNaN())
    }

    @Test
    fun `FieldMap 小端与大端读法不同且可往返`() {
        val raw = byteArrayOf(0x00, 0x00, 0x94.toByte(), 0x05)
        val le = FieldMap(2, 2, bigEndian = false, scale = 0.05f).read(raw)
        val be = FieldMap(2, 2, bigEndian = true, scale = 0.05f).read(raw)

        assertEquals(71.4f, le, 0.001f)          // 0x0594 = 1428 → 71.40V
        assertEquals(1894.65f, be, 0.01f)        // 0x9405 = 37893 → 1894.65（明显不是电压）
    }

    @Test
    fun `FieldMap 越界或未配置返回 NaN`() {
        val raw = ByteArray(4)
        assertTrue(FieldMap(-1, 2, true, 1f).read(raw).isNaN())
        assertTrue(FieldMap(3, 2, true, 1f).read(raw).isNaN())
        assertTrue(FieldMap(0, 2, true, 1f).read(null).isNaN())
    }
}
