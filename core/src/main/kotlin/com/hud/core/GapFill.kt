package com.hud.core

/**
 * 隧道/桥洞导致 GPS 断档时，用断档前的速度把这段里程补回来。
 *
 * 为什么不直接用 `速度×时间` 一路积分：GPS 速度有 ±2–4 km/h 的噪声，一路积分会把噪声
 * 累成偏差（跑一小时能多出几百米）。但**短缺口**用最后一次可信速度补，比"直接少算一段"
 * 更接近真值——真实场景是穿高架/隧道 5–10 秒，那段路你确实骑过去了。
 *
 * 规则（都在 [meters] 里）：
 *  - 缺口 < [MIN_GAP_MS]：不补（1 秒的间隔本来就正常，补了等于重复计）
 *  - 缺口 > [MAX_GAP_MS]：不补（信号丢了半分钟，速度早就不可信，宁可不猜）
 *  - 速度 NaN/负数/超过 [MAX_SPEED_KMH]：不补
 *  - 速度低于 [MIN_SPEED_KMH]：不补
 *
 * 为什么要求"真的是在跑"（v0.9.9）：停车等单时 GPS 间隔被降到 3 秒，
 * 正好等于 [MIN_GAP_MS]，而静止时 GPS 速度仍有 ±2–6km/h 的噪声，
 * 按老规则每 3 秒都会拿这个噪声"补"一段假里程——停一小时能凭空多出 3–6 公里。
 * 真在隧道里骑的时候速度至少在 10km/h 以上，所以把门槛卡在 8km/h 不会误伤。
 */
object GapFill {

    const val MIN_GAP_MS = 3000L
    /** 最多补 15 秒——再长就不是"隧道"了，是没信号 */
    const val MAX_GAP_MS = 15000L
    const val MAX_SPEED_KMH = 70f
    /** 低于这个速度不补：静止时 GPS 速度噪声会变成假里程 */
    const val MIN_SPEED_KMH = 8f

    @JvmStatic
    fun meters(gapMs: Long, lastSpeedKmh: Float): Double {
        if (gapMs < MIN_GAP_MS || gapMs > MAX_GAP_MS) return 0.0
        if (lastSpeedKmh.isNaN() || lastSpeedKmh < MIN_SPEED_KMH || lastSpeedKmh > MAX_SPEED_KMH) return 0.0
        // km/h → m/s，再乘秒数
        return lastSpeedKmh / 3.6 * (gapMs / 1000.0)
    }
}
