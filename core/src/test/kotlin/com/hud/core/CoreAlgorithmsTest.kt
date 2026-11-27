package com.hud.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 算法层单元测试：里程积分 / 电量查表 / 滑动平均 / 耗电自学习。
 * 全在电脑上跑（`gradle :core:test`），不需要车、不需要手机。
 */
class CoreAlgorithmsTest {

    // ---------------- 电量查表 ----------------

    @Test
    fun `查表在参考锚点上精确命中`() {
        // v1.0.53：锚点按 11-19 实车全程重标（78.67V 真满电 / 63.8V 真耗尽静置）
        val m = SocModel()
        assertEquals(100f, m.soc(78.6f), 0.001f)
        assertEquals(94f, m.soc(77.3f), 0.001f)
        assertEquals(50f, m.soc(72.6f), 0.001f)
        assertEquals(15f, m.soc(66.3f), 0.001f)
        assertEquals(0f, m.soc(63.8f), 0.001f)
    }

    @Test
    fun `电压与电量往返闭合（六个点，误差小于 0点01V）`() {
        val m = SocModel()
        for (v in listOf(78.0f, 76.4f, 74.9f, 72.6f, 70.6f, 66.3f)) {
            val back = m.voltageOf(m.soc(v))
            assertEquals("v=$v 往返应闭合", v, back, 0.01f)
        }
    }

    @Test
    fun `超出量程被夹紧而不是外推`() {
        val m = SocModel()
        assertEquals(100f, m.soc(82f), 0.001f)
        assertEquals(0f, m.soc(60f), 0.001f)
        assertTrue(m.soc(Float.NaN).isNaN())
        assertTrue(m.voltageOf(Float.NaN).isNaN())
        assertEquals(63.8f, m.voltageOf(0f), 0.001f)    // 0% → 空电静置电压 63.8V
        assertEquals(63.8f, m.voltageOf(-5f), 0.001f)   // 负电量按 0% 夹紧
        assertEquals(78.6f, m.voltageOf(150f), 0.001f)  // 超 100% 按满电夹紧
    }

    @Test
    fun `把满电改成实测 78V 后同一电压电量更低`() {
        val old = SocModel(76.5f, 63f)
        val tuned = SocModel(78.0f, 63f)
        val at749old = old.soc(74.9f)
        val at749new = tuned.soc(74.9f)
        assertTrue("78V 满电时 74.9V 应该更悲观：old=$at749old new=$at749new", at749new < at749old)
        assertEquals(78.0f, tuned.voltageOf(100f), 0.01f)
        assertEquals(63.0f, tuned.voltageOf(0f), 0.01f)
    }

    // ---------------- 滑动平均 ----------------

    @Test
    fun `滑动平均在窗口内取均值、超窗丢弃`() {
        val s = Smoother(10_000L)
        s.add(1_000L, 70f)
        s.add(5_000L, 72f)
        assertEquals(71f, s.meanOr(5_000L), 0.001f)
        // 20 秒后旧样本全部超窗
        assertTrue(s.meanOr(20_000L).isNaN())
    }

    @Test
    fun `NaN 样本不入窗`() {
        val s = Smoother(10_000L)
        val first = s.add(0L, 70f)
        assertEquals(70f, first, 0.001f)
        val afterNaN = s.add(100L, Float.NaN)
        assertEquals(70f, afterNaN, 0.001f)
        s.reset()
        assertTrue(s.meanOr(200L).isNaN())
    }

    // ---------------- 里程积分 ----------------

    @Test
    fun `25kmh 跑 1 秒得到 0点00694km`() {
        val t = TripIntegrator()
        t.onSpeed(25f, 1_000L)
        t.onSpeed(25f, 2_000L)
        assertEquals(25f / 3600f, t.km, 1e-5f)
    }

    @Test
    fun `断流 10 秒不会补进假里程`() {
        val t = TripIntegrator()
        t.onSpeed(30f, 1_000L)
        val after = t.onSpeed(30f, 11_000L)   // dt=10s 超过上限
        assertEquals(0f, after, 1e-6f)
    }

    @Test
    fun `等红灯低速不计入`() {
        val t = TripIntegrator()
        var now = 0L
        repeat(10) {
            now += 1_000L
            t.onSpeed(0.8f, now)
        }
        assertEquals(0f, t.km, 1e-6f)
    }

    @Test
    fun `一小时的 25kmh 应该刚好 25 公里`() {
        val t = TripIntegrator()
        var now = 0L
        repeat(3600) {
            now += 1_000L
            t.onSpeed(25f, now)
        }
        assertEquals(25f, t.km, 0.05f)
    }

    @Test
    fun `reset 与 restore`() {
        val t = TripIntegrator(12.5f)
        assertEquals(12.5f, t.km, 1e-6f)
        t.reset()
        assertEquals(0f, t.km, 1e-6f)
        t.restore(3f)
        assertEquals(3f, t.km, 1e-6f)
    }

    @Test
    fun `GPS 距离兜底：直接加一段距离，NaN 与负数被忽略`() {
        val t = TripIntegrator(1f)
        assertEquals(1.5f, t.addKm(0.5f), 1e-6f)
        assertEquals(1.5f, t.addKm(Float.NaN), 1e-6f)
        assertEquals(1.5f, t.addKm(-0.2f), 1e-6f)
        assertEquals(1.5f, t.addKm(0f), 1e-6f)
    }

    // ---------------- 时速（GPS 位移兜底） ----------------

    @Test
    fun `时速：位移换算（25kmh 一秒走 6点94米）`() {
        // 25 km/h 跑 1 秒 = 6.944 米
        assertEquals(25f, GpsSpeed.kmh(6.944f, 1000L), 0.01f)
        // 10 km/h 跑 2 秒 = 5.556 米
        assertEquals(10f, GpsSpeed.kmh(5.556f, 2000L), 0.01f)
    }

    @Test
    fun `时速：时间间隔或位移不合理时给 NaN 而不是瞎报`() {
        assertTrue("200ms 太短，噪声会放大", GpsSpeed.kmh(5f, 200L).isNaN())
        assertTrue("10 秒说明中途丢点", GpsSpeed.kmh(50f, 10_000L).isNaN())
        assertTrue("原地不动", GpsSpeed.kmh(0f, 1000L).isNaN())
        assertTrue("一次跳 80 米不像真骑行", GpsSpeed.kmh(80f, 1000L).isNaN())
        assertTrue(GpsSpeed.kmh(Float.NaN, 1000L).isNaN())
    }

    @Test
    fun `时速：协议速度优先，其次 GPS，都没有就是 NaN`() {
        assertEquals(26f, GpsSpeed.pick(26f, 10f), 0.001f)
        assertEquals(10f, GpsSpeed.pick(Float.NaN, 10f), 0.001f)
        assertTrue(GpsSpeed.pick(Float.NaN, Float.NaN).isNaN())
        assertEquals(0f, GpsSpeed.pick(0f, 12f), 0.001f)
    }

    // ---------------- 耗电自学习 ----------------

    private fun entry(ts: Long, v: Float, km: Float) = EcoEntry(ts, v, km)

    @Test
    fun `满电到 80 电量跑 4 公里应该学出约 5 百分点每公里`() {
        val m = SocModel()
        val book = EcoBook()
        // v1.0.53 表：78.6V=100%、74.0V=70%，6km ⇒ 30%/6km = 5%/km
        val r = book.evaluate(entry(0L, 78.6f, 0f), entry(3_600_000L, 74.0f, 6f), m)
        assertTrue(r.learned)
        assertEquals(5f, r.newRate!!, 1e-4f)
        assertEquals(5f, r.segmentRate!!, 1e-4f)
        book.commit(r)
        assertEquals(1, book.n)
        assertEquals(5f, book.rate, 1e-4f)
    }

    @Test
    fun `距离太短不学`() {
        val m = SocModel()
        val book = EcoBook()
        val r = book.evaluate(entry(0L, 78.6f, 0f), entry(60_000L, 74.0f, 1.5f), m)
        assertFalse(r.learned)
        assertFalse(book.commit(r))
    }

    @Test
    fun `没掉电不学（刚充过或静置）`() {
        val m = SocModel()
        val book = EcoBook()
        val r = book.evaluate(entry(0L, 74.0f, 0f), entry(600_000L, 74.0f, 3f), m)
        assertFalse(r.learned)
    }

    @Test
    fun `离谱的耗电速率被区间挡掉`() {
        val m = SocModel()
        val book = EcoBook()
        // 3 公里从 100% 掉到 0% ⇒ 33%/km，超过 20 的上限
        val r = book.evaluate(entry(0L, 78.6f, 0f), entry(600_000L, 63.8f, 3f), m)
        assertFalse(r.learned)
    }

    @Test
    fun `第二次学习走 EMA 0点7 旧加 0点3 新`() {
        val m = SocModel()
        val book = EcoBook()
        // 第一段：78.6V(100%) → 74.0V(70%)，6km ⇒ 5%/km
        val first = book.evaluate(entry(0L, 78.6f, 0f), entry(1_000L, 74.0f, 6f), m)
        book.commit(first)
        // 第二段：74.0V(70%) → 72.6V(50%)，5km ⇒ 4%/km
        val second = book.evaluate(entry(2_000L, 74.0f, 6f), entry(3_000L, 72.6f, 11f), m)
        assertTrue(second.learned)
        assertEquals(5f * 0.7f + 4f * 0.3f, second.newRate!!, 1e-4f)
        book.commit(second)
        assertEquals(2, book.n)
    }

    @Test
    fun `推算与剩余里程`() {
        val book = EcoBook(5f, 1)
        // 哨点 80%，跑了 4km ⇒ 80 - 20 = 60%
        assertEquals(60f, book.socNow(80f, 10f, 14f), 1e-4f)
        // 不会推成负数
        assertEquals(0f, book.socNow(10f, 0f, 100f), 1e-4f)
        assertEquals(12f, book.rangeKm(60f), 1e-4f)

        val blank = EcoBook()
        assertEquals(80f, blank.socNow(80f, 0f, 99f), 1e-6f)
        assertTrue("没学会之前剩余里程必须是 NaN", blank.rangeKm(80f).isNaN())
    }

    /**
     * 演示模式剧本的数值预演（App 里的 startDemo 用同一套公式）。
     * 打印出来贴进 analysis/，用户没装 App 也能先看到"会看到什么"。
     */
    @Test
    fun `演示剧本预演：电压走完一轮时电量与里程的变化`() {
        val model = SocModel(78.6f, 63.8f)   // v1.0.53 的实测推荐值
        val trip = TripIntegrator()
        var now = 0L
        val rows = StringBuilder()
        while (true) {
            val t = now / 1000L
            val riding = (t % 90L) >= 10L
            var v = 78.2f - 0.012f * t
            if (v < 64.0f) break
            if (riding) v -= 0.9f
            v += kotlin.math.sin(t / 7.0).toFloat() * 0.12f
            val kmh = if (riding) 24f + kotlin.math.sin(t / 9.0).toFloat() * 3f else 0f
            trip.onSpeed(kmh, now)
            if (t % 120L == 0L) {
                rows.append(
                    String.format("%5d s  %s  V=%.1f  soc=%3.0f%%  km=%5.2f%n",
                        t, if (riding) "骑行" else "停", v, model.soc(v), trip.km)
                )
            }
            now += 1000L
        }
        println("---- 演示剧本预演（每 2 分钟一行）----")
        println(rows.toString())
        assertTrue("一轮至少 10 分钟，够看到电压/电量/里程三档颜色的切换", now > 10L * 60L * 1000L)
        assertTrue("走到 64V 时电量应该已经见底（<5%）", model.soc(64.0f) < 5f)
        assertEquals(0f, model.soc(63.0f), 0.001f)
    }

    // ---------------- 距离：球面几何 ----------------

    @Test
    fun `一度纬度就是约一百一十一公里`() {
        val d = Geo.meters(28.0, 113.0, 29.0, 113.0)
        // 子午线上一度纬度 = πR/180 ≈ 111195.08 m
        assertEquals(111195.08, d, 1.0)
    }

    @Test
    fun `同一点距离为零、非法经纬度返回 NaN`() {
        assertEquals(0.0, Geo.meters(28.2, 113.0, 28.2, 113.0), 1e-6)
        assertTrue(Geo.meters(200.0, 113.0, 28.0, 113.0).isNaN())
        assertTrue(Geo.meters(Double.NaN, 113.0, 28.0, 113.0).isNaN())
    }

    @Test
    fun `二十米的东西向位移在两秒测距误差内`() {
        // 郴州一带，纬度 25.8：经度差 0.0002° ≈ 20 m
        val d = Geo.meters(25.8, 113.0, 25.8, 113.0 + 0.0002)
        assertEquals(20.0, d, 0.6)
    }

    // ---------------- 距离：OpenTracks 式门限 ----------------

    @Test
    fun `精度超过五十米的点整点丢弃`() {
        val t = TrackDistance()
        assertEquals(0.0, t.add(1000, 25.8, 113.0, 80.0, 30.0), 1e-6)
        assertEquals(1, t.skippedAccuracy)
        assertEquals(0.0, t.totalM, 1e-6)
    }

    @Test
    fun `真正的位移会被累计，累计值随步数线性增长`() {
        val t = TrackDistance()
        var tms = 0L
        var lat = 25.8
        t.add(tms, lat, 113.0, 8.0, 25.0)      // 第一个点只做基准
        repeat(10) {
            tms += 1000
            lat += 0.0000902                  // ≈ 10 m 每秒 = 36 km/h
            t.add(tms, lat, 113.0, 8.0, 36.0)
        }
        assertEquals(100.0, t.totalM, 3.0)     // 10 步 × 10 m
        assertEquals(10, t.accepted)
    }

    @Test
    fun `停车时十米内的乱跳不产生里程`() {
        val t = TrackDistance()
        var tms = 0L
        t.add(tms, 25.8, 113.0, 5.0, 0.0)
        // 静止十分钟：每秒钟在 ±5 m 内抖（真实 GPS 停车漂移就这个量级）
        val jitter = listOf(0.000045, -0.000040, 0.000030, -0.000035, 0.0)
        repeat(600) {
            tms += 1000
            t.add(tms, 25.8 + jitter[it % jitter.size], 113.0, 5.0, 0.4)
        }
        assertEquals("静止十分钟不该凭空累出里程", 0.0, t.totalM, 1.0)
        assertTrue(t.skippedJitter > 550)
    }

    @Test
    fun `有速度时用速度当尺子挡住中等跳点`() {
        val t = TrackDistance()
        t.add(0, 25.8, 113.0, 5.0, 25.0)
        // 1 秒瞬移 15m（≡54km/h），没超 200m 也没超 80km/h，但远高于 25km/h 的真实速度 → 挡掉
        val jump = t.add(1000, 25.8 + 0.000135, 113.0, 5.0, 25.0)
        assertEquals(0.0, jump, 1e-6)
        assertEquals(1, t.skippedBySpeed)
        // 正常一步 7m（≡25km/h）不受影响
        val step = t.add(2000, 25.8 + 0.000135 + 0.000063, 113.0, 5.0, 25.0)
        assertEquals(7.0, step, 1.5)
    }

    @Test
    fun `单步两百米以上的跳点不计但重新对表`() {
        val t = TrackDistance()
        t.add(0, 25.8, 113.0, 5.0, 20.0)
        // 一跳 500 m（约 0.0045° 纬度）
        val jump = t.add(1000, 25.8045, 113.0, 5.0, 20.0)
        assertEquals(0.0, jump, 1e-6)
        assertEquals(1, t.skippedJump)
        // 跳点之后的正常一步仍然能算（基准点已经挪到跳点位置）
        val step = t.add(2000, 25.8045 + 0.0000902, 113.0, 5.0, 20.0)
        assertEquals(10.0, step, 1.0)
    }

    @Test
    fun `速度未知时只认明显超过噪声的位移`() {
        val t = TrackDistance()
        t.add(0, 25.8, 113.0, 5.0, Double.NaN)
        val tiny = t.add(1000, 25.8 + 0.00001, 113.0, 5.0, Double.NaN)  // ≈1.1 m
        assertEquals(0.0, tiny, 1e-6)
        val real = t.add(2000, 25.8 + 0.0000902, 113.0, 5.0, Double.NaN)
        // v1.0.1：抖动那一步不再"对表"，所以这里是从最开始的基准点量 —— 10 m（不再丢 1.1 m）
        assertEquals(10.0, real, 0.5)
    }

    @Test
    fun `1 秒瞬移 150 米按跳点丢弃，不计进里程`() {
        // 城市峡谷常见：一步 150m（没超 200m 的位移门限），但折合 540km/h
        val t = TrackDistance()
        t.add(0, 25.8, 113.0, 5.0, 20.0)
        val jump = t.add(1000, 25.8 + 0.00135, 113.0, 5.0, 20.0)
        assertEquals(0.0, jump, 1e-6)
        assertEquals(1, t.skippedJump)
        // 之后的正常一步照常累计（基准点已经挪到跳点位置）
        val step = t.add(2000, 25.8 + 0.00135 + 0.0000902, 113.0, 5.0, 20.0)
        assertEquals(10.0, step, 1.0)
    }

    @Test
    fun `低速慢骑的位移会累积，不会被每步清零`() {
        // 5km/h 慢骑，1 秒走 1.4m，分 8 步；老逻辑每步都"抖动对表"→ 计入 0
        val t = TrackDistance()
        t.add(0, 25.8, 113.0, 5.0, 5.0)
        var lat = 25.8
        var sum = 0.0
        var tms = 0L
        repeat(8) {
            tms += 1000
            lat += 0.0000126      // ≈1.4 m/s = 5 km/h
            sum += t.add(tms, lat, 113.0, 5.0, 5.0)
        }
        assertEquals("8 步 ≈11.2m，应该全部计入", 11.2, sum, 1.0)
    }

    // ---------------- 时速：1€ 滤波 ----------------

    @Test
    fun `一欧滤波把抖动压下去但保留阶跃响应`() {
        val f = OneEuro(0.4, 0.02, 1.0)
        var t = 0L
        // 真实场景：1Hz，30km/h 上下 ±4 的随机噪声（GPS 噪声是随机的，不是每秒反向）
        val rnd = java.util.Random(42L)
        val rawList = ArrayList<Double>()
        val outList = ArrayList<Double>()
        repeat(40) {
            t += 1000
            val raw = 30.0 + (rnd.nextDouble() * 8.0 - 4.0)
            rawList.add(raw)
            val o = f.filter(raw, 1000)
            if (it > 8) outList.add(o)
        }
        val inStd = std(rawList.subList(9, rawList.size))
        val outStd = std(outList)
        assertTrue("滤波后方差应明显变小：in=$inStd out=$outStd", outStd < inStd * 0.85)
        val mean = outList.average()
        assertTrue("均值不能被拉偏", mean in 28.0..32.0)

        // 阶跃到 0（停车）：3 秒内必须基本跟上
        var last = outList.last()
        repeat(3) {
            t += 1000
            last = f.filter(0.0, 1000)
        }
        assertTrue("阶跃后 3 秒应基本归零，实际 $last", last < 1.0)
    }

    private fun std(v: List<Double>): Double {
        val m = v.average()
        return Math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    @Test
    fun `时速稳定器：低速持续一会儿就显示零`() {
        val s = SpeedStabilizer()
        var t = 0L
        var out = Float.NaN
        repeat(8) {
            t += 1000
            out = s.push(t, 1.1f)     // 低速抖动（GPS 常这样）
        }
        assertEquals(0f, out, 0.001f)
    }

    @Test
    fun `时速稳定器：丢两三个点仍保持上次值，超时就承认没有`() {
        val s = SpeedStabilizer()
        var t = 0L
        repeat(5) {
            t += 1000
            s.push(t, 26f)
        }
        val held = s.push(t + 2000, Float.NaN)     // 丢点 2 秒
        assertFalse("不该立刻掉成 --", held.isNaN())
        assertTrue(held > 20f)
        val gone = s.push(t + 20000, Float.NaN)    // 丢点 20 秒
        assertTrue("长时间没数据要老实承认没有", gone.isNaN())
    }

    // ---------------- 断档补里程 ----------------

    @Test
    fun `一秒钟的间隔不补（那本来就正常）`() {
        assertEquals(0.0, GapFill.meters(1500L, 30f), 1e-9)
    }

    @Test
    fun `隧道里丢八秒按上次速度补回来`() {
        // 30 km/h = 8.333 m/s，8 秒 ≈ 66.7 m
        assertEquals(66.67, GapFill.meters(8000L, 30f), 0.5)
    }

    @Test
    fun `缺口超过十五秒不再猜`() {
        assertEquals(0.0, GapFill.meters(60000L, 30f), 1e-9)
    }

    @Test
    fun `速度不可信时不补`() {
        assertEquals(0.0, GapFill.meters(8000L, Float.NaN), 1e-9)
        assertEquals(0.0, GapFill.meters(8000L, 0f), 1e-9)
        assertEquals(0.0, GapFill.meters(8000L, 120f), 1e-9)   // 骑不到 120，显然是坏值
    }

    @Test
    fun `停车时的 GPS 速度噪声不补里程`() {
        // 停车等单：间隔被降到 3000ms，正好撞上 3 秒门槛；静止时 GPS 常报 2–6km/h
        assertEquals(0.0, GapFill.meters(3000L, 3f), 1e-9)
        assertEquals(0.0, GapFill.meters(3000L, 6f), 1e-9)
        // 真的在骑（>8km/h）才补
        assertTrue(GapFill.meters(3000L, 15f) > 1.0)
    }

    // ---------------- 无实测电压时的按里程推算 ----------------

    @Test
    fun `兜底压降率就是满电欠压差除满电续航`() {
        // (78 - 63) / 80 = 0.1875 V/km
        assertEquals(0.1875f, VoltDecay.defaultVPerKm(78f, 63f, 80), 1e-4f)
    }

    @Test
    fun `跑了十公里电压必须跟着掉一点九伏`() {
        val v = VoltDecay.estimate(76.3f, 0.1875f, 10f, 63f)
        assertEquals(74.425f, v, 0.01f)
    }

    @Test
    fun `推算不会掉到欠压以下`() {
        assertEquals(63f, VoltDecay.estimate(70f, 0.1875f, 100f, 63f), 0.001f)
    }

    @Test
    fun `两个锚点能学出真实压降率`() {
        // 76.3V 跑了 8km 之后记到 74.9V → 0.175 V/km
        val k = VoltDecay.learnFromAnchors(76.3f, 0f, 74.9f, 8f)
        assertEquals(0.175f, k, 1e-4f)
    }

    @Test
    fun `信息不足时不瞎学`() {
        assertTrue(VoltDecay.learnFromAnchors(76.3f, 10f, 76.1f, 11f).isNaN())   // 只跑了 1km
        assertTrue(VoltDecay.learnFromAnchors(74.9f, 0f, 76.3f, 8f).isNaN())    // 电压反而升了（充电）
        assertTrue(VoltDecay.learnFromAnchors(78f, 0f, 60f, 5f).isNaN())         // 1km 掉 18V，不可能
    }

    // ---------------- 骑行压降补偿（v0.9.0） ----------------

    @Test
    fun `停车时不做补偿，读到的就是静置电压`() {
        val c = LoadComp()
        assertEquals(75.5f, c.ocv(75.5f, 0f), 1e-4f)
        assertEquals(0f, c.sagOf(0f), 1e-4f)
    }

    @Test
    fun `30km-h 时补 5V，把带载电压抬回空载`() {
        val c = LoadComp()
        // 11-19 实测：30km/h 以上带载压降中位 5.25V、P90 5.83V（旧版只补 1.8V）
        assertEquals(5.0f, c.sagOf(30f), 1e-4f)
        assertEquals(78.9f, c.ocv(73.9f, 30f), 1e-4f)
    }

    @Test
    fun `补偿封顶，高速时不会把电量算虚高`() {
        val c = LoadComp()
        assertEquals(6.0f, c.sagOf(80f), 1e-4f)
        assertEquals(6.0f, c.sagOf(200f), 1e-4f)
    }

    @Test
    fun `速度缺失按 0 处理，电压缺失仍是 NaN`() {
        val c = LoadComp()
        assertEquals(74.2f, c.ocv(74.2f, Float.NaN), 1e-4f)
        assertEquals(74.2f, c.ocv(74.2f, -5f), 1e-4f)
        assertTrue(c.ocv(Float.NaN, 25f).isNaN())
    }

    @Test
    fun `补偿后的电压查表，比直接查表更接近真实电量`() {
        val m = SocModel(vFull = 78.6f, vEmpty = 63.8f)
        val c = LoadComp()
        val loaded = m.soc(70.0f)                 // 直接拿带载电压查 → 偏低
        val fixed = m.soc(c.ocv(70.0f, 30f))      // 补偿 +5V 后再查
        assertTrue("补偿后电量应更高", fixed > loaded)
        assertTrue("但不会直接拉到 100%", fixed < 100f)
    }

    // ---------------- 里程：断档补点不能重复计（v0.9.1） ----------------

    @Test
    fun `隧道断档补完里程后，同一点再喂进来不能重复计`() {
        val t = TrackDistance()
        // 1) 起点
        t.add(0L, 26.0000, 113.0000, 5.0, 20.0)
        // 2) 断档 8 秒后出现新点，真实位移约 40m（20km/h × 8s）
        t.rebind(8000L, 26.00036, 113.0)          // 调用方已经补过 40m
        val again = t.add(8000L, 26.00036, 113.0, 5.0, 20.0)
        assertEquals("补过的这段不能再算一次", 0.0, again, 0.01)
    }

    @Test
    fun `不 rebind 时同一段路会被算两次（反证 rebind 的必要）`() {
        val t = TrackDistance()
        t.add(0L, 26.0000, 113.0000, 5.0, 20.0)
        val step = t.add(8000L, 26.00036, 113.0, 5.0, 20.0)
        assertEquals("真实位移 40m 会被完整计入", 40.0, step, 1.0)
    }

    @Test
    fun `rebind 不改变累计里程`() {
        val t = TrackDistance()
        t.add(0L, 26.0000, 113.0000, 5.0, 20.0)
        t.add(3000L, 26.00010, 113.0, 5.0, 20.0)
        val before = t.totalM
        t.rebind(4000L, 26.1000, 113.0)
        assertEquals("只是挪基准点，不动总里程", before, t.totalM, 1e-6)
    }

    // ---------------- 电压显示：时间窗中位数滤波（v1.0.0 / v1.0.1 改时间窗） ----------------

    @Test
    fun `中位数不会被一个离谱采样带走`() {
        val f = VoltFilter()
        var t = 0L
        repeat(8) { t += 500; f.add(t, 66f) }
        // 控制器偶尔吐出 58V 这种离群值
        assertEquals(66f, f.add(t + 500, 58f), 0.001f)
    }

    @Test
    fun `滤波后电压落在噪声带上沿（避开带载压降）`() {
        // v1.0.1：显示用 0.75 分位而不是中位数。实车统计 p50=65.5 / p75=67.6 / p90=68.3，
        // 小程序同时刻 68.6 —— 偏低那一截是带载压降，骑手眼里的"电压"是上沿那个数。
        val f = VoltFilter()
        val noisy = floatArrayOf(58f, 69f, 66f, 64f, 68f, 61f, 67f, 65f, 63f)
        var last = Float.NaN
        var t = 0L
        for (v in noisy) {
            t += 500
            last = f.add(t, v)
        }
        // 排序后 [58,61,63,64,65,66,67,68,69]，0.75 分位 → 67
        assertEquals(67f, last, 0.001f)
    }

    @Test
    fun `没有采样时中位数是空值`() {
        assertTrue(VoltFilter().median(0L).isNaN())
    }

    @Test
    fun `median 必须是真的 0_5 分位，不能等同 value`() {
        // v1.0.9 修的病：median 曾经直接 = value（0.9 分位），
        // 于是"静置窗是否稳定"那道闸门（比 value 与 median 的差）永远是 0，等于没生效。
        val f = VoltFilter(windowMs = 5000L, capacity = 64, quantile = 0.9)
        val vs = floatArrayOf(58f, 60f, 62f, 64f, 70f)
        var t = 0L
        for (v in vs) { t += 400; f.add(t, v) }
        val q = f.value(t)
        val m = f.median(t)
        assertEquals("0.9 分位应取到上沿", 64f, q, 0.001f)
        assertEquals("中位数应取到中间", 62f, m, 0.001f)
        assertTrue("两者必须不同，否则闸门形同虚设", kotlin.math.abs(q - m) > 1.0f)
    }

    @Test
    fun `超过时间窗的老采样不再参与中位数`() {
        // 2Hz：前 5 秒全是 58V（骑行压降），之后回到 70V 静置
        val f = VoltFilter(windowMs = 5000L)
        var t = 0L
        repeat(10) { t += 500; f.add(t, 58f) }
        repeat(10) { t += 500; f.add(t, 70f) }
        // 窗内只剩 70V 那批 → 中位数就是 70，不会还挂着 58
        assertEquals(70f, f.median(t), 0.001f)
    }

    // ---------------- 电量口径：静置锚点 + 里程推算（v1.0.0） ----------------

    @Test
    fun `没有锚点时用电压查表值起步`() {
        val t = SocTracker()
        assertEquals(62f, t.update(1000L, 3f, 62f), 0.01f)
    }

    @Test
    fun `有锚点后按里程下降而不是被电压噪声拽走`() {
        // 满电锚点：100% @ 0km，1.2%/km，按真实速度跑 1 小时 26.8km
        val t = SocTracker(1.2f, 100f, 0f)
        var shown = Float.NaN
        for (s in 0..3600) shown = t.update(s * 1000L, s * 26.8f / 3600f, 5f)   // 电压一路谎报 5%
        assertTrue("26.8km 后还该有六成多电，不该是 5%", shown > 60f)
        assertEquals(67.8f, shown, 1.5f)
    }

    @Test
    fun `显示值一秒最多变两个点`() {
        val t = SocTracker(1.0f, 100f, 0f)
        t.update(0L, 0f, 100f)
        // 一公里之外突然说该掉 30 个点：一秒内只允许走 0.033
        val shown = t.update(1000L, 30f, Float.NaN)
        assertTrue("不能跳变", shown > 99.9f)
    }

    @Test
    fun `两次静置锚点能学出每公里耗电`() {
        val t = SocTracker()
        t.anchorRest(0L, 100f, 0f)          // 充满停着
        val learned = t.anchorRest(3_600_000L, 60f, 20f)  // 跑 20km 后又停下，静置 60%
        assertEquals(2.0f, learned, 0.01f)
        assertEquals(2.0f, t.perKmPct, 0.01f)
    }

    @Test
    fun `里程太短的锚点不参与学习`() {
        val t = SocTracker()
        t.anchorRest(0L, 100f, 0f)
        val learned = t.anchorRest(60_000L, 40f, 1f)      // 只跑了 1km，样本不可信
        assertTrue(learned.isNaN())
    }

    @Test
    fun `上坡满油门的压降要按油门补 不是按速度补`() {
        val c = LoadComp()
        // 平路 30km/h、松油门（0.8V）：只补速度项 → 5.0V
        assertEquals(5.0f, c.sagOf(30f, 0.8f), 0.01f)
        // 上坡 15km/h、油门到底（4.2V）：速度项 2.8V、油门项 1.35*(4.2-0.8)=4.59 → 取较大值 4.59V
        // （v1.0.53 明确"取 max 不相加"：两个信号是同一份电流的两个侧面，相加会补过头）
        assertEquals(4.59f, c.sagOf(15f, 4.2f), 0.01f)
        // 停车（速度 0、油门回位）：一点不补，得到的就是真实静置电压
        assertEquals(0f, c.sagOf(0f, 0.8f), 0.01f)
        // 转把未知时退化成只看速度，不该凭空多补
        assertEquals(5.0f, c.sagOf(30f, Float.NaN), 0.01f)
        // OCV = 带载 + 补偿
        assertEquals(73.9f + 4.59f, c.ocv(73.9f, 15f, 4.2f), 0.05f)
    }

    @Test
    fun `整段骑行重放 上坡不掉电 松油门能回升`() {
        // v1.0.21：把"平路 → 上坡满油门 → 松油门滑行"整段重放一遍，
        // 验证 v1.0.20 的两条新规则（带载不纠偏、低载可回升）真的成立。
        // 这比"只看单个函数返回值"可靠 —— 上一版就是漏了"下一秒被 update 覆盖"。
        val m = SocModel(78.0f, 63.0f)
        val lc = LoadComp()
        var now = 0L

        // 旧行为（一直拿电压纠偏）用来做对照
        val old = SocTracker(1.25f, 100f, 0f)
        val neu = SocTracker(1.25f, 100f, 0f)
        var km = 0f

        fun step(ocvAt: Float, thr: Float, speed: Float, seconds: Int) {
            // 关键：端电压由 OCV 减去压降**推出来**，而不是我手填一个数 ——
            // 手填很容易造出"平路 76.5V、滑行 74V"这种同一块电不可能的序列（第一版就栽在这）。
            val sagNew = lc.sagOf(speed, thr)
            val sagOld = lc.sagOf(speed, Float.NaN)   // 旧版：只按速度补
            val loaded = thr - LoadComp.idleThrottleV > LoadComp.loadedThrottleV
            repeat(seconds) {
                now += 1_000L
                km += speed / 3600f
                // 新版：按"速度+油门"补 → 估出的 OCV 就是真 OCV
                // 旧版：只补速度 → 少补的那部分被当成"掉电"
                val ocvEstNew = ocvAt
                val ocvEstOld = ocvAt - (sagNew - sagOld)
                val socNew = m.soc(ocvEstNew)
                val socOld = m.soc(ocvEstOld)
                old.update(now, km, socOld)
                neu.update(now, km, socNew)
                if (loaded) old.fuseWithVoltage(now, km, socOld)      // 旧版：带载也纠偏
                else {
                    neu.fuseWithVoltage(now, km, socNew)             // 新版：只在低负载纠偏
                    neu.recoverUp(now, km, socNew)
                }
            }
        }

        val ocvFull = 77.9f          // 满电静置（78.0 表压 - 一点余量）
        // A：平路 60 秒（油门 2.0，25km/h）—— 压降约 1.5+1.4≈2.9V
        step(ocvFull, 2.0f, 25f, 60)
        val afterFlat = neu.shown

        // B：长上坡 240 秒（油门到底 4.2，12km/h，压降≈4.8V）—— 只走 0.8km
        // 纠偏要求"分歧连续 3 分钟"，所以 1–2 分钟的短坡本来就不会触发；
        // 真正会出事的是这种长坡（外卖路线上确实存在），用 4 分钟来对照。
        step(ocvFull - 0.15f, 4.2f, 12f, 240)
        val afterHillNew = neu.shown
        val afterHillOld = old.shown

        // 上坡只走了 0.8km，按 1.25%/km 只该掉 1 点；给足余量断言 ≤3 点
        assertTrue("上坡不该掉电：before=$afterFlat after=$afterHillNew",
                afterFlat - afterHillNew <= 3f)
        // 对照：旧逻辑（带载也纠偏）在这个长坡上会掉得明显更多
        assertTrue("旧逻辑应明显更惨：new=$afterHillNew old=$afterHillOld",
                afterHillOld < afterHillNew - 1f)

        // C：坡顶松油门滑行 180 秒（油门 0.8，10km/h）
        step(ocvFull - 0.4f, 0.8f, 10f, 180)
        val afterCoast = neu.shown

        // 滑行段只走了 0.5km，电量不该再额外往下掉（最多 1 点里程消耗）
        assertTrue("滑行不该再掉电：hill=$afterHillNew coast=$afterCoast",
                afterHillNew - afterCoast <= 1.5f)

        // D：真正需要"回升"的场景 —— 锚点被错误的低值污染（比如之前被压到了 60）
        // 这时 OCV 明明说 96%，就应该一点点还回来（受 10 点预算约束）。
        val wrong = SocTracker(1.25f, 60f, 0f)
        val socTrue = m.soc(ocvFull - 0.5f)
        var wKm = 0f
        var wNow = 0L
        repeat(300) {                       // 5 分钟滑行
            wNow += 1_000L
            wKm += 10f / 3600f
            wrong.update(wNow, wKm, socTrue)
            wrong.recoverUp(wNow, wKm, socTrue)
        }
        assertTrue("被压低的电量应该还回来：60 -> ${wrong.shown}（真实 $socTrue）",
                wrong.shown > 62f)
        assertTrue("回升受预算约束：${wrong.shown}",
                wrong.shown <= 60f + SocTracker.UP_BUDGET + 0.5f)

        // 回升总量同样受 10 点预算约束
        assertTrue(neu.getRecoveredTotal() <= SocTracker.UP_BUDGET + 0.01f)
        println(String.format("scenario: flat=%.1f hill_new=%.1f hill_old=%.1f coast=%.1f wrong60->%.1f (true %.1f)",
                afterFlat, afterHillNew, afterHillOld, afterCoast, wrong.shown, socTrue))
    }

    @Test
    fun `电压回升后电量能涨回来 但有门槛和预算`() {
        // v1.0.20：修"上坡扣电、下坡不还"。锚点 50%，但电压口径说 62%。
        val t = SocTracker(1.25f, 50f, 0f)
        t.update(0L, 0f, 50f)
        assertEquals(50f, t.shown, 0.5f)
        // 差 12 点 ≥ 门槛(4)，但要连续维持 45 秒才开始动
        assertEquals(0f, t.recoverUp(1_000L, 0f, 62f), 0.01f)
        assertEquals(0f, t.recoverUp(40_000L, 0f, 62f), 0.01f)
        // 过了 sustain：一次 +1
        assertEquals(1f, t.recoverUp(46_000L, 0f, 62f), 0.01f)
        // 每次抬升后要重新等一个 45 秒的 sustain，不许 5 秒一档地猛涨
        assertEquals(0f, t.recoverUp(52_000L, 0f, 62f), 0.01f)
        assertEquals(1f, t.recoverUp(92_000L, 0f, 62f), 0.01f)
        // 一箱电总共最多回升 10 点
        var total = 2f
        var ts = 92_000L
        while (total < 10f) {
            ts += 60_000L
            val step = t.recoverUp(ts, 0f, 100f)
            if (step <= 0f) break
            total += step
        }
        assertEquals(10f, total, 0.01f)
        assertTrue(t.getRecoveredTotal() <= SocTracker.UP_BUDGET + 0.01f)
    }

    @Test
    fun `换了新电瓶 满电时耗电率会往回摆`() {
        // 旧电瓶学到 3.7%/km（满电只能跑 27km）；换上新电瓶（80km ≈ 1.25%/km）后，
        // 如果还按 3.7 推算，第一箱电会掉得飞快。满电回摆 35%：
        // 3.7*0.65 + 1.25*0.35 = 2.405 + 0.4375 = 2.8425
        val t = SocTracker(3.7f, 100f, 0f)
        val relaxed = t.relaxPerKm(0.35f)
        assertEquals(2.8425f, relaxed, 0.01f)
        // 没学到耗电率时不该凭空造一个值
        val fresh = SocTracker()
        assertTrue(fresh.relaxPerKm(0.35f).isNaN())
    }

    @Test
    fun `学到的耗电率被夹在合理区间`() {
        val t = SocTracker()
        t.anchorRest(0L, 100f, 0f)
        // 25km 掉 100% ⇒ 4%/km（隐含续航 25km，真实存在这种老化电池）→ 原样采信
        // v1.0.16：上限 2.6 → 5.0。旧上限和"采信区间 20–150km"自相矛盾：
        // 采信了 4%/km 却按 2.6%/km 用，等于把 27km 的车当成 38km，剩 4% 时高估 40% 续航。
        val learned = t.anchorRest(3_600_000L, 0f, 25f)
        assertEquals(4.0f, learned, 0.01f)
        assertEquals(4.0f, t.perKmPct, 0.01f)
    }

    @Test
    fun `向下纠偏不会把耗电率学坏`() {
        // v1.0.7 修的病：anchorRestGuarded 往下挪了几点，又把这几点当成"真实消耗"学进去，
        // 停十几次就顶到上限，续航显示"4% 还能跑 1km"。
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        repeat(8) {
            val km = 5f * (it + 1)
            t.anchorRestGuarded(1000L + it * 1000L, t.predict(km) - 3f, km)
        }
        assertEquals("纠偏量不该被学成耗电", 1.25f, t.perKmPct, 0.05f)
    }

    @Test
    fun `离谱样本直接丢弃不再夹成最高耗电`() {
        val t = SocTracker()
        t.anchorRest(0L, 100f, 0f)
        // 3km 掉 100% ⇒ 33%/km，隐含续航 3km，物理上不可能：直接不学，别把 perKmPct 焊成 3.0
        val learned = t.anchorRest(3_600_000L, 0f, 3f)
        assertTrue("坏样本不该被采信", learned.isNaN())
        assertTrue("学习值应该还是空的", t.perKmPct.isNaN())
    }

    @Test
    fun `一箱电的纠偏预算用完就不再往下拉`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.nudgeBudgetPct = 6f
        t.update(0L, 0f, 100f)
        var km = 0f
        repeat(6) {
            km += 5f
            t.anchorRestGuarded(1000L + it * 1000L, t.predict(km) - 30f, km)
        }
        // 6 次里最多只花掉 6 点预算，之后只守里程口径
        assertEquals(6f, t.nudgedTotal, 0.2f)
        // 30km × 1.25%/km 本来就该掉到 62.5，再花 6 点预算 → 约 56.5；
        // 没有预算的话是 62.5 − 18 = 44.5，那才是"被一路按到底"
        assertTrue("不该被一路按到底", t.anchorSoc > 50f)
        val before = t.anchorSoc
        // 同一位置再停一次（电压仍然谎报 0%）：预算已用尽，只守里程口径，不再往下挪
        t.anchorRestGuarded(9000L, 0f, 30f)
        assertEquals("预算用尽后不再额外下挪", before, t.anchorSoc, 0.1f)
    }

    @Test
    fun `静置样本能学斜率，即使锚点差太多跳不过去`() {
        // 11-20/21 现场：perKm 学成 1.14（折合 87.6km），真实 1.30（77km）
        val t = SocTracker(1.14f, 80f, 18.46f)
        t.defaultRangeKm = 87.6f
        // 上次被电压确认过的锚点：80% @ 18.46km
        t.anchorRest(1000L, 80f, 18.46f)
        // 停在 58.74km，静置电压只给 15%：账本说 32%（差 17 点，跳不过去）
        val learned = t.learnPerKmFromRest(15f, 58.74f)
        // (80−15)/40.28 = 1.61%/km，权重 0.5 → 1.14/1.32 之间取中 ≈1.37
        assertTrue("该把斜率往真实方向拉", learned > 1.3f && learned < 1.45f)
        // 续航上限随之变紧：100/1.37 ≈ 73km，而不是 87.6
        assertTrue(t.perKmPct > 1.3f)
    }

    @Test
    fun `末端塌陷的静置读数不参与学斜率`() {
        // 11-17 那台车的毛病：26.8km 静置只有 66.2V（查表 4%），折算满电只有 28km
        val t = SocTracker(1.14f, 100f, 0f)
        t.defaultRangeKm = 87.6f
        t.anchorRest(1000L, 100f, 0f)
        assertTrue(t.learnPerKmFromRest(4f, 26.8f).isNaN())
        // 斜率没被带跑
        assertEquals(1.14f, t.perKmPct, 0.01f)
    }

    @Test
    fun `短段样本不学斜率`() {
        val t = SocTracker(1.14f, 80f, 18.46f)
        t.anchorRest(1000L, 80f, 18.46f)
        // 只跑了 2km、只掉 3 点：噪声，不足以说明斜率
        assertTrue(t.learnPerKmFromRest(77f, 20.46f).isNaN())
    }

    @Test
    fun `满电锚点会清空纠偏预算`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.nudgeBudgetPct = 6f
        t.update(0L, 0f, 100f)
        repeat(4) { t.anchorRestGuarded(1000L + it * 1000L, t.predict(10f) - 30f, 10f) }
        assertTrue(t.nudgedTotal > 0f)
        t.resetNudgeBudget()
        assertEquals(0f, t.nudgedTotal, 0.001f)
    }

    @Test
    fun `剩余续航按电量和耗电率算`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 26.8f, Float.NaN)                    // 26.8km 后 66.5%
        val range = t.rangeKm(80f)
        // 66.5 / 1.25 ≈ 53km，绝不可能是 1km
        assertTrue("续航不该是 1 公里", range > 40f)
    }

    @Test
    fun `里程清零后锚点跟着落位`() {
        // 停下车校准过：60% @ 26km；用户长按清零后 tripKm 变 0
        val t = SocTracker(1.25f, 60f, 26f)
        t.update(0L, 26f, 60f)
        t.rebaseIfBehind(0f)
        assertEquals(0f, t.anchorKm, 0.001f)
        assertEquals(60f, t.anchorSoc, 0.5f)
        assertEquals(60f, t.predict(0f), 0.5f)   // 清零后从当前位置继续往下走，不会变成负数
    }

    @Test
    fun `电压锚点和里程推算差太多时只小幅纠偏`() {
        // 满电出发：100% @ 0km，跑了 40km，里程口径说还剩 50%
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        t.update(1000L, 40f, 5f)
        // 控制器这一刻静置电压只有 65V，按表是 5% —— 差 45 点，明显不可信
        val first = t.anchorRestGuarded(2000L, 5f, 40f)
        assertTrue("这种情况不该学习 %/km", first.isNaN())
        assertEquals(1, t.rejects)
        assertEquals(50f, t.anchorSoc, 0.5f)     // 第一次只记里程口径，不跳
        assertEquals(50f, t.predict(40f), 0.5f)

        // 连续第三次都偏低才开始纠，且一次只挪 3 点
        t.anchorRestGuarded(3000L, 5f, 40f)
        t.anchorRestGuarded(4000L, 5f, 40f)
        assertEquals(47f, t.anchorSoc, 0.5f)
        assertEquals(3, t.rejects)
    }

    @Test
    fun `静置电压比账本高时向上放宽对齐 一次最多抬10点且不学斜率`() {
        // v1.0.63：旧锚点偏低（半充电/长停放回弹），静置 OCV 比账本高 25 点。
        // 向上这一侧不再走"三次 reject 才挪 3 点"，一次抬 10 点封顶，不参与 %/km 学习。
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        t.update(1000L, 40f, 75f)
        val learned = t.anchorRestGuarded(2000L, 75f, 40f)
        assertTrue("向上放宽也不该学习 %/km", learned.isNaN())
        assertEquals(0, t.rejects)
        assertEquals(60f, t.anchorSoc, 0.5f)      // pred=50 + upStep 10
        assertEquals(60f, t.shown, 0.5f)
    }

    @Test
    fun `静置电压比账本高且差在死区内时正常锚定并学习`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        val learned = t.anchorRestGuarded(1000L, 90f, 8f)
        assertEquals(0, t.rejects)
        assertEquals(1.25f, learned, 0.1f)
        assertEquals(90f, t.shown, 0.1f)
    }

    @Test
    fun `电压锚点在合理范围内时正常锚定并学习`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        // 跑 10km，静置电压给 87.5%（和里程推算 87.5% 一致）
        val learned = t.anchorRestGuarded(1000L, 87.5f, 10f)
        assertEquals(0, t.rejects)
        assertEquals(1.25f, learned, 0.1f)      // 12.5 点 / 10km
        assertEquals(87.5f, t.shown, 0.1f)
    }

    @Test
    fun `连续几次纠偏后会逐步靠向电压口径而不是永远不动`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        var km = 0f
        repeat(5) {
            km += 5f
            // 每次停车电压都偏低 30 点 → 前两次不动，之后每次只挪 3 点
            t.anchorRestGuarded(1000L + it * 1000L, t.predict(km) - 30f, km)
        }
        // 5 次里后 3 次共挪 9 点，而不是一次性掉 30
        assertTrue(t.anchorSoc > 0f)
        assertTrue(t.rejects >= 5)
    }

    // ---------------- v1.0.69：上抬留账，跌了就还 ----------------

    @Test
    fun `上抬依据被电压证伪时一次静置就整段还回来`() {
        // 11-26 现场：补完电浮电 77.3V（查表 78%）把账本从 60 抬到 78，
        // 15 分钟后真静置只有 64% —— 上抬依据不成立，不能等"三次确认 + 4 点/次"
        val t = SocTracker(1.25f, 60f, 0f)
        t.update(0L, 0f, 60f)
        t.anchorRestGuarded(1000L, 78f, 0f)
        assertEquals("先按电压抬上去（差 18 点在中段整段对齐）", 78f, t.anchorSoc, 0.5f)
        t.anchorRestGuarded(1000L + 15L * 60_000L, 64f, 0f)
        assertEquals("跌了 14 点就该整段落回", 64f, t.anchorSoc, 0.5f)
        assertEquals("落回也要记进纠偏预算（并夹到上限）", 12f, t.nudgedTotal, 0.1f)
        assertEquals("这是整段还账，不该留 reject 计数", 0, t.rejects)
    }

    @Test
    fun `上抬后只回落 5 点不算证伪，仍走小额纠偏`() {
        val t = SocTracker(1.25f, 60f, 0f)
        t.update(0L, 0f, 60f)
        t.anchorRestGuarded(1000L, 78f, 0f)
        t.anchorRestGuarded(2000L, 73f, 0f)
        // 78 → 最多往下 4 点（不是整段到 73）
        assertEquals(74f, t.anchorSoc, 0.3f)
    }

    @Test
    fun `上抬超过 40 分钟后不再回滚`() {
        // 40 分钟以后电压再低，多半是真实掉电，该按里程/慢纠偏走，不能翻旧账
        val t = SocTracker(1.25f, 60f, 0f)
        t.update(0L, 0f, 60f)
        t.anchorRestGuarded(1000L, 78f, 0f)
        t.anchorRestGuarded(1000L + 45L * 60_000L, 64f, 0f)
        assertEquals("不回滚，第一次仍只是守住账本", 78f, t.anchorSoc, 0.5f)
        assertEquals(1, t.rejects)
    }

    @Test
    fun `满电锚点会清掉上抬的账`() {
        val t = SocTracker(1.25f, 60f, 0f)
        t.update(0L, 0f, 60f)
        t.anchorRestGuarded(1000L, 78f, 0f)
        t.resetNudgeBudget()
        assertEquals(0f, t.nudgedTotal, 0.001f)
        // 清账之后，64 这个读数不再能触发"整段还账"，走回正常的「三次确认」通道
        t.anchorRestGuarded(2000L, 64f, 0f)
        assertEquals(78f, t.anchorSoc, 0.5f)
        assertEquals(1, t.rejects)
    }


    @Test
    fun `里程比锚点小的时候电量不会往回涨`() {
        // 11-26 两次"连上就九十多"的直接来源：对表/重启后 tripKm 变小，锚点里程没跟上。
        // 45 − 1.25×(0.46−56) = 114 → 夹 100，屏幕就按满电走。
        val t = SocTracker(1.25f, 45f, 56f)
        assertEquals("里程倒退不是充电，电量该原样是锚点值", 45f, t.predict(0.46f), 0.1f)
        assertEquals(45f, t.predict(56f), 0.1f)
        // 真的往前跑了才掉电
        assertEquals(42.5f, t.predict(58f), 0.1f)
    }

    // ---------------- v1.0.10：行驶中按电压纠偏 ----------------

    @Test
    fun `分歧不到 3 分钟不许动（长坡不能当成电量掉了`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        // 骑了 20km，里程口径说 75%，带载电压说 55%（差 20 点，像一次长坡）
        val km = 20f
        assertTrue(t.fuseWithVoltage(10_000L, km, 55f).isNaN())
        assertTrue(t.fuseWithVoltage(100_000L, km, 55f).isNaN())   // 才 100 秒
        assertEquals(100f, t.anchorSoc, 0.6f)                       // 锚点一点没动
        assertEquals(75f, t.predict(km), 0.6f)                      // 里程口径仍是 75%
    }

    @Test
    fun `分歧持续 3 分钟后一次只挪 3 点且不改耗电率`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        val km = 20f
        t.fuseWithVoltage(10_000L, km, 55f)          // 起算
        val moved = t.fuseWithVoltage(10_000L + 180_000L, km, 55f)
        assertFalse("这次应该动", moved.isNaN())
        assertEquals(72f, moved, 0.01f)              // 75 - 3
        assertEquals(1.25f, t.perKmPct, 0.0001f)     // **不参与学习**
        assertEquals(3f, t.nudgedTotal, 0.01f)       // 预算扣 3
    }

    @Test
    fun `一箱电的纠偏预算用完就只守里程口径`() {
        val t = SocTracker(1.25f, 100f, 0f)
        t.update(0L, 0f, 100f)
        var now = 0L
        var km = 0f
        repeat(10) {
            km += 5f
            now += 200_000L
            t.fuseWithVoltage(now, km, t.predict(km) - 25f)
            now += 200_000L
            t.fuseWithVoltage(now, km, t.predict(km) - 25f)   // 攒满 3 分钟再挪
        }
        assertTrue("总纠偏不能超过 12 点", t.nudgedTotal <= 12.01f)
    }
}


// ---------------- v1.0.36：电量百分比显示层滤波 ----------------

class SocSmoothTest {

    @Test
    fun `单帧离谱值不会把显示带偏`() {
        val s = SocSmooth(6000L, 64, 0.5)
        for (i in 0..5) s.add(i * 1000L, 60f)
        // 第 7 帧突然掉到 17%（坏帧 / ocvCand 与瞬时值来回切）
        val shown = s.add(6000L, 17f)
        assertEquals(60f, shown, 0.001f)
    }

    @Test
    fun `0 是合法电量，不会被当成非法值丢掉`() {
        val s = SocSmooth(6000L, 64, 0.5)
        s.add(0L, 100f)
        s.add(1000L, 0f)
        s.add(2000L, 0f)
        assertEquals(0f, s.value(2000L), 0.001f)
    }

    @Test
    fun `真实下降在几个样本内跟得上`() {
        val s = SocSmooth(6000L, 64, 0.5)
        s.add(0L, 60f); s.add(1000L, 60f); s.add(2000L, 60f)
        s.add(3000L, 55f); s.add(4000L, 55f); s.add(5000L, 55f)
        // 窗口 0..5000 共 6 个样本：{60,60,60,55,55,55} → 取索引 2，即 55
        assertEquals(55f, s.value(5000L), 0.001f)
        s.add(6000L, 55f); s.add(7000L, 55f)
        // 0 秒那个样本已过期，窗口 1000..7000：{60,60,55,55,55,55} → 55
        assertEquals(55f, s.value(7000L), 0.001f)
    }

    @Test
    fun `NaN 不入窗，也不清空已有结果`() {
        val s = SocSmooth(6000L, 64, 0.5)
        s.add(0L, 80f)
        val shown = s.add(1000L, Float.NaN)
        assertEquals(80f, shown, 0.001f)
    }

    @Test
    fun `reset 之后重新开始`() {
        val s = SocSmooth(6000L, 64, 0.5)
        s.add(0L, 80f)
        s.reset()
        assertTrue(s.value(0L).isNaN())
        assertEquals(0, s.samplesIn(0L))
    }
}
class PerKmClampTest {

    @Test
    fun `学到的每公里耗电被满电续航约束住（3点3 只能当 2点5）`() {
        val t = SocTracker(3.3f, 100f, 0f)
        assertEquals(2.5f, t.perKmOrDefault(80f), 0.001f)
        // 满电 100% + 2.5%/km → 续航 40km，仍然偏高，但不再离谱到 30km 以下
        t.update(1L, 0f, Float.NaN)
        assertEquals(40f, t.rangeKm(80f), 0.01f)
    }

    @Test
    fun `过于乐观的样本也被下限挡住`() {
        val t = SocTracker(0.3f, 100f, 0f)
        assertEquals(0.5f, t.perKmOrDefault(80f), 0.001f)
    }

    @Test
    fun `没有样本时按满电续航折算`() {
        val t = SocTracker(Float.NaN, 100f, 0f)
        assertEquals(1.25f, t.perKmOrDefault(80f), 0.001f)
        t.update(1L, 0f, Float.NaN)
        assertEquals(80f, t.rangeKm(80f), 0.01f)
    }
}
// ---------------- v1.0.44：SOC 显示层（死区 + 限速 + 重连直跳） ----------------

class SocDisplayTest {

    @Test
    fun `查表抖 0 与 2 之间来回时不闪`() {
        // 实测 raw_20261118_224404.csv：vm 在 64.59/62.00 之间切源，soc 0↔2 每 2 秒跳一次
        val d = SocDisplay()
        d.seed(2f, 0L)
        var now = 2000L
        for (i in 0 until 30) {
            val t = if (i % 2 == 0) 0f else 2f
            d.onEstimate(now, t, false)
            now += 2000
        }
        assertEquals("0↔2 应被死区吃掉", 2f, d.value(), 0.01f)
    }

    @Test
    fun `一段 11 分的下滑被摊成平滑下降而不是一帧跳完`() {
        // 实测 20:08 日志：电压几乎不动，显示 75 -> 69 -> 64 只用 4 秒
        val d = SocDisplay()
        d.seed(75f, 0L)
        var now = 2000L
        for (i in 0 until 10) {
            d.onEstimate(now, 64f, false)
            now += 2000
        }
        // 2 分/秒 × 2 秒/拍 = 每拍最多掉 4 分，不可能一拍到底
        assertEquals(64f, d.value(), 0.01f)
    }

    @Test
    fun `重连后充过电 目标稳住 6 秒才直跳`() {
        val d = SocDisplay()
        d.seed(2f, 0L)
        var now = 1000L
        repeat(5) {
            d.onEstimate(now, 96f, true)
            now += 2000L
        }
        assertEquals(96f, d.value(), 0.01f)
    }

    @Test
    fun `重连瞬间的中间值闪一下不落位`() {
        // 实测日志 63 -> 76 -> 67：45 秒窗没收敛时出来的中间值，6 秒内自己就散了
        val d = SocDisplay()
        d.seed(63f, 0L)
        d.onEstimate(1000L, 76f, true)
        d.onEstimate(3000L, 67f, true)
        // 只允许按限速挪一两分，绝不能落在 76 那种一帧中间值上
        assertTrue("中间值不应落位，实际 ${d.value()}", d.value() < 70f)
    }

    @Test
    fun `没有输入时保持不动`() {
        val d = SocDisplay()
        d.seed(58f, 0L)
        d.onEstimate(2000L, Float.NaN, false)
        assertEquals(58f, d.value(), 0.01f)
    }

    @Test
    fun `回升也按 2 分每秒限速`() {
        val d = SocDisplay()
        d.seed(40f, 0L)
        d.onEstimate(1000L, 60f, false)
        assertEquals(42f, d.value(), 0.01f)   // 1 秒最多升 2 分
    }

    /**
     * v1.0.49：骑行档。
     *
     * 实车日志（raw_20261118_150145.csv）里 30km/h 巡航 30 秒内端电压摆 4–5V，
     * 查表值就在 85–95 之间来回；2 分死区挡不住 3 分摆动，屏幕上看得到
     * "骑得好好的电量自己往上爬两格"。骑行档的死区 3 分、上升 1 分/秒。
     */
    @Test
    fun `骑行中目标抖 3 分不动`() {
        val d = SocDisplay()
        d.seed(90f, 0L)
        d.onEstimate(1000L, 93f, false, true)
        assertEquals(90f, d.value(), 0.01f)
    }

    @Test
    fun `骑行中回升只按 1 分每秒`() {
        val d = SocDisplay()
        d.seed(80f, 0L)
        d.onEstimate(1000L, 90f, false, true)
        assertEquals(81f, d.value(), 0.01f)   // 停车时是 2 分/秒，骑行时压到 1 分/秒
    }

    @Test
    fun `停车时仍按 2 分每秒校正回来`() {
        val d = SocDisplay()
        d.seed(80f, 0L)
        d.onEstimate(1000L, 90f, false, false)
        assertEquals(82f, d.value(), 0.01f)
    }
}