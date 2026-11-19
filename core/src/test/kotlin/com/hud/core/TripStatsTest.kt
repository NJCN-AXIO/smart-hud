package com.hud.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.5 新增：码表统计（MXS/AVS/时长）与能耗窗口（Wh/km）。
 * 独立成文件，避免动到已通过的 72 条老用例。
 */
class TripStatsTest {

    @Test
    fun `跑一分钟三十码 里程与平均速度对得上`() {
        val s = TripStats()
        // 30km/h = 8.33m/s，1 秒 0.00833km
        var km = 0f
        for (i in 0..60) {
            val t = 1_000_000L + i * 1000L
            if (i > 0) km += 30f / 3600f
            s.update(t, km, 30f)
        }
        // 第一帧没有时间差，所以是 60 秒运动时长
        assertEquals(60L, s.movingSec)
        assertEquals(30f, s.maxKmh, 0.01f)
        // km ≈ 0.5km，/60s → 30km/h
        assertEquals(30f, s.avgKmh(km), 0.6f)
    }

    @Test
    fun `停车等单的时间不算进平均速度`() {
        val s = TripStats()
        var km = 0f
        var t = 1_000_000L
        s.update(t, 0f, 30f)   // 起始帧（第一帧没有时间差）
        // 骑 60 秒 30km/h
        for (i in 1..60) {
            t += 1000L
            km += 30f / 3600f
            s.update(t, km, if (i < 55) 30f else 0f)
        }
        val kmAtRideEnd = km
        // 再停 10 分钟（GPS 每秒报 0）
        for (i in 1..600) {
            t += 1000L
            s.update(t, km, 0f)
        }
        assertEquals(60L, s.movingSec)
        // 平均速度仍然按 60 秒算，不被停车时间稀释
        assertEquals(30f, s.avgKmh(kmAtRideEnd), 1.0f)
    }

    @Test
    fun `停车时的 GPS 噪声不会刷出最高速度`() {
        val s = TripStats()
        var km = 0f
        var t = 1_000_000L
        s.update(t, 0f, 40f)   // 起始帧
        for (i in 1..30) {
            t += 1000L
            km += 40f / 3600f
            s.update(t, km, 40f)
        }
        // 停下来以后 GPS 还在 6km/h 地抖
        for (i in 1..120) {
            t += 1000L
            s.update(t, km, 6f)
        }
        assertEquals(40f, s.maxKmh, 0.01f)
        assertEquals(30L, s.movingSec)
    }

    @Test
    fun `速度全 NaN 但里程在长 也算在骑`() {
        val s = TripStats()
        var km = 0f
        var t = 1_000_000L
        s.update(t, 0f, Float.NaN)   // 起始帧
        for (i in 1..60) {
            t += 1000L
            km += 0.008f
            s.update(t, km, Float.NaN)
        }
        assertEquals(60L, s.movingSec)
        assertTrue(s.maxKmh.isNaN())    // 没有速度样本就不编一个最高速度
    }

    @Test
    fun `清零后统计跟着归零`() {
        val s = TripStats()
        s.update(1_000L, 0.5f, 30f)
        s.update(2_000L, 0.5f, 30f)
        s.reset()
        assertEquals(0L, s.movingSec)
        assertTrue(s.maxKmh.isNaN())
    }

    @Test
    fun `平均速度样本太少时不给值`() {
        val s = TripStats()
        s.update(1_000L, 0.01f, 20f)
        assertTrue(s.avgKmh(0.01f).isNaN())
    }

    @Test
    fun `能耗窗口 1点5公里掉3个点 算出30Wh每公里`() {
        val b = EnergyBook()
        val packWh = 1512f   // 72V * 21Ah
        b.add(0f, 80f)
        b.add(0.5f, 79f)
        b.add(1.0f, 78f)
        b.add(1.5f, 77f)
        assertEquals(1512f * 3f / 100f / 1.5f, b.whPerKm(packWh), 0.5f)
    }

    @Test
    fun `窗口只取最近一公里 不被很久以前的样本拖住`() {
        val b = EnergyBook()
        b.add(0f, 90f)
        // 前 5km 很省：只掉 1 个点
        b.add(5f, 89f)
        // 最近 1km 掉 4 个点（爬坡）
        b.add(5.5f, 87f)
        b.add(6f, 85f)
        assertEquals(1512f * 4f / 100f / 1.0f, b.whPerKm(1512f), 1.0f)
    }

    @Test
    fun `电量回弹 充电或重新校准 会清空窗口`() {
        val b = EnergyBook()
        b.add(0f, 60f)
        b.add(1f, 58f)
        b.add(1.2f, 65f)   // 校准把电量抬回去了
        assertEquals(1, b.samples)
        assertTrue(b.whPerKm(1512f).isNaN())
    }

    @Test
    fun `原地不动只刷新电量 不刷屏样本`() {
        val b = EnergyBook()
        for (i in 0..50) b.add(1f, 70f - i * 0.02f)
        assertEquals(1, b.samples)
        // 位置没变 → 算不出 Wh/km
        assertTrue(b.whPerKm(1512f).isNaN())
    }

    @Test
    fun `清零里程后窗口重建`() {
        val b = EnergyBook()
        b.add(3f, 50f)
        b.add(4f, 48f)
        b.add(0.1f, 48f)   // 用户长按清零
        assertEquals(1, b.samples)
    }
}
