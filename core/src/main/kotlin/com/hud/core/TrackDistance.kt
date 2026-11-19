package com.hud.core

/**
 * 用 GPS 累计里程——照 OpenTracks（Google MyTracks 的直系后代）的做法做过滤。
 *
 * 参考实测值来自 OpenTracks 的 `res/values/settings.xml`：
 *  - `recording_gps_accuracy_default = 50`（米）：精度比它差的定位点**整点丢弃**
 *  - `recording_distance_interval_default = 10`（米）：位移小于这个量级不认为在"移动"
 *  - 距离本体用 `Location.distanceTo`（WGS84 测地线），我们这里用等价的 [Geo.meters]
 *
 * 骑手场景要额外处理两件 OpenTracks 不管的事：
 *  1. **停在路边等电梯时的 GPS 漂移**：手机静止不动，位置却在 3–8 米内乱跳，
 *     一停十分钟就能凭空累出 1–2 公里。这里用"速度低 + 位移小 = 判抖动，不计也不丢点"。
 *  2. **城市峡谷跳点**：单步几十上百米的瞬移（穿桥洞、高楼反射），一步就算进去，
 *     里程立刻多几百米。超过 [maxStepM] 当跳点处理：不计，但把基准点挪过去（重新对表）。
 */
class TrackDistance @JvmOverloads constructor(
    /** 精度门限（米）：超过就整点丢弃，与 OpenTracks 默认 50 一致 */
    val maxAccuracyM: Double = 50.0,
    /** 判定"真的在动"的最小位移（米）：取 OpenTracks 的距离间隔默认值 10m */
    val minMoveM: Double = 10.0,
    /** 速度未知时（GPS 弱）放宽到一半，否则慢骑时会整段丢里程 */
    val minMoveUnknownM: Double = 5.0,
    /** 判定"真的在动"的最小速度（km/h）：低于它且位移又小 → 算抖动 */
    val minSpeedKmh: Double = 2.0,
    /** 单步位移上限（米）：超过当跳点，不计但重新对表 */
    val maxStepM: Double = 200.0,
    /**
     * 位移/时间推出来的速度上限（km/h）：超过当跳点（v1.0.1）。
     *
     * 光靠 [maxStepM] 不够：城市峡谷/桥洞下经常出现"1 秒瞬移 150m"（=540km/h），
     * 150m 没超 200m 的门限就被当成真实位移计进去了，一趟车能多记几百米。
     * 外卖车的实际极速也就 45km/h，用 80km/h 当上限足够宽松又挡得住瞬移。
     */
    val maxSpeedKmh: Double = 80.0,
    /**
     * 用 GPS 报的速度做"一致性校验"（v1.0.4，参考高德/Strava 的做法）：
     * 位移推出来的速度超过 `真实速度 × 1.6 + 6`，就当这一步是跳点。
     *
     * 为什么还要这一道：`maxSpeedKmh` 只看绝对上限，1 秒瞬移 30m（≡108km/h）虽然会被挡，
     * 但 15m（≡54km/h，外卖车跑不到）却混得过去 —— 一趟车能虚增几百米。
     * 有速度的时候拿速度当尺子，比任何固定阈值都准。
     */
    val speedFactor: Double = 1.6,
    val speedSlackKmh: Double = 6.0
) {

    private var hasLast = false
    private var lastT = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0

    /** 诊断计数：被丢弃/被跳过各多少次，用来在日志里判断"到底是谁不准" */
    var accepted = 0
        private set
    var skippedAccuracy = 0
        private set
    var skippedJitter = 0
        private set
    var skippedJump = 0
        private set
    /** 被"速度一致性"挡掉的步数（有速度但位移推速远高于速度） */
    var skippedBySpeed = 0
        private set

    /** 累计里程（米），只增不减 */
    var totalM = 0.0
        private set

    /**
     * 喂一个定位点，返回**这一步**新增的米数（0 表示这一步不计）。
     *
     * @param tMs     定位时刻（毫秒）
     * @param lat     纬度
     * @param lon     经度
     * @param accM    水平精度（米）；未知传 NaN
     * @param speedKmh 该点速度（km/h）；未知传 NaN
     */
    fun add(tMs: Long, lat: Double, lon: Double, accM: Double, speedKmh: Double): Double {        // 精度不达标：整点不要，连基准点都不更新（否则差点会把基准拽偏）
        if (!accM.isNaN() && accM > maxAccuracyM) {
            skippedAccuracy++
            return 0.0
        }
        if (lat.isNaN() || lon.isNaN()) {
            skippedAccuracy++
            return 0.0
        }
        if (!hasLast) {
            remember(tMs, lat, lon)
            return 0.0
        }
        val dt = tMs - lastT
        if (dt <= 0L) return 0.0

        val d = Geo.meters(lastLat, lastLon, lat, lon)
        if (d.isNaN()) return 0.0

        // 跳点：不计，但重新对表（否则下一个点会跟跳点连成一段假路程）
        if (d > maxStepM) {
            skippedJump++
            remember(tMs, lat, lon)
            return 0.0
        }
        // 位移推出来的速度离谱（瞬移）→ 同样当跳点，只是门限换成物理速度
        val dtSec = dt / 1000.0
        val impliedKmh = if (dtSec >= 0.5) d / dtSec * 3.6 else Double.NaN
        if (dtSec >= 0.5 && impliedKmh > maxSpeedKmh) {
            skippedJump++
            remember(tMs, lat, lon)
            return 0.0
        }
        // v1.0.4：有速度时用速度当尺子（高德那套一致性校验），挡住"54km/h 的 15m 瞬移"。
        // 只在"速度本身够得着在动"时才启用：停车漂移（速度 0.4km/h、位移 5m）要留给下面的
        // 抖动分支去判，否则计数器和诊断会乱。
        if (!impliedKmh.isNaN() && !speedKmh.isNaN() && speedKmh >= minSpeedKmh) {
            val cap = speedKmh * speedFactor + speedSlackKmh
            if (impliedKmh > cap) {
                skippedBySpeed++
                remember(tMs, lat, lon)
                return 0.0
            }
        }

        val moving = if (speedKmh.isNaN()) null else speedKmh >= minSpeedKmh

        // 判抖动：明确低速 + 位移很小 → 这一步先不计，**但不对表**（v1.0.1）。
        // 老逻辑每步都对表，等于把"3m→4m→5m 的真实慢速位移"一次次清零，
        // 慢骑（红绿灯、推车过路口）的里程会整段漏掉。
        // 现在让位移从上一个"已计入的点"开始累积，攒够 [minMoveM] 再一次性算进去。
        if (moving == false && d < minMoveM) {
            skippedJitter++
            return 0.0
        }
        // 速度未知时，只认明显超过噪声的位移（同样不对表，规则同上）
        if (moving == null && d < minMoveUnknownM) {
            skippedJitter++
            return 0.0
        }
        // 在动，但这一步几乎没位移（信号抖动）→ 不计
        if (d < 1.0) return 0.0

        accepted++
        totalM += d
        remember(tMs, lat, lon)
        return d
    }

    fun reset() {
        hasLast = false
        totalM = 0.0
        accepted = 0
        skippedAccuracy = 0
        skippedJitter = 0
        skippedJump = 0
        skippedBySpeed = 0
    }

    /**
     * 重新对表：把基准点挪到这个位置，**不计里程**。
     *
     * 用途：隧道/桥洞断档时，调用方已经用"断档前速度 × 时间"把这段距离补过了
     * （见 `GapFill`）。如果不重新对表，下一个点到来时这里会把同一段路**再算一次**，
     * 里程就翻倍了。所以补完里程必须调一次 rebind，再喂同一个点。
     */
    fun rebind(tMs: Long, lat: Double, lon: Double) {
        if (lat.isNaN() || lon.isNaN()) return
        remember(tMs, lat, lon)
    }

    private fun remember(tMs: Long, lat: Double, lon: Double) {
        hasLast = true
        lastT = tMs
        lastLat = lat
        lastLon = lon
    }
}
