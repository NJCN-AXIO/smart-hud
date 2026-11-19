package com.hud.core

/**
 * 电量百分比的"仪表显示层"（v1.0.44）。
 *
 * 为什么还要一层：v1.0.30 把电量改成"连上就按电压直读"，实测暴露出两类毛病——
 *   1) 查表值在 0 和 2 之间来回跳（`vm` 在 64.59 与 62.00 之间切源，一格 2 分）；
 *   2) 45 秒窗一换气就整段 75 → 69 → 64（电压几乎没动，屏幕掉了 11 分）。
 * 实测日志（raw_20261118_200840.csv）里 27 次 ≥5 分的跳变，全是这两类。
 *
 * 做法照车规/两轮电动车的 SOC 显示惯例：
 *   - **死区（dead band）**：目标值和显示值差在 1.5 分内不动 —— 压掉查表抖动；
 *   - **限速（rate limit）**：下降最多 2 分/秒、上升最多 6 分/秒 —— 一段 11 分的变化
 *     会摊成 5–6 秒的平滑下滑，而不是一帧跳完；
 *   - **重连直跳（snap）**：链路断了几分钟以上再回来（可能刚充过电），差 20 分以上时
 *     允许直接落位 —— 这时候慢慢爬才是骗人（充完电 2%→100% 就该是一个数）。
 *
 * 温度/内阻/老化都不在这个类里：它只负责"把上游给的目标值，变成屏幕上不会抽动的数字"。
 */
class SocDisplay @JvmOverloads constructor(
    /** 死区：目标与显示的差在这个范围内保持不动（分）。2 分 = 屏幕上不到一格 */
    private val deadBand: Float = 2.0f,
    /** 允许的最快下降速度（分/秒） */
    private val downPerSec: Float = 2.0f,
    /** 允许的最快上升速度（分/秒）—— 松油门回升比掉电快一点更符合直觉 */
    private val upPerSec: Float = 2.0f,
    /** 重连时超过这个差就直跳，不做斜坡（分） */
    private val snapDelta: Float = 20f,
    /**
     * 直跳前的"确认时长"：目标值必须连续这么久都停在同一个位置（±2 分），才允许落位。
     *
     * v1.0.45 加的这道闸，专门对付"重连瞬间 45 秒窗还没收敛"：日志里 63→76→67 那种
     * 一闪而过的中间值，6 秒内自己就散了；真正充过电的 100% 会稳稳停住，6 秒后落位。
     */
    private val confirmMs: Long = 6000L,
    /**
     * v1.0.49：**骑行档**的死区与上升限速。
     *
     * 实车日志（raw_20261118_150145.csv）里，30km/h 巡航时端电压在 30 秒内能摆 4–5V
     *（油门几乎没变，`rv 71.2..76.1 sag 4.1..4.5`），查表值就会在 85–95 之间来回。
     * 2 分的死区挡不住 3 分的摆动，屏幕上就能看到"骑得好好的电量自己往上爬 2 格"。
     * 骑行者只看得到这一格一格的变化，所以骑行中把死区放大到 3 分、上升压到 1 分/秒；
     * 停车（电压真正静下来）时仍用 2 分死区 + 2 分/秒，校正起来不至于慢吞吞。
     */
    private val ridingDeadBand: Float = 3.0f,
    private val ridingUpPerSec: Float = 1.0f,
    /** v1.0.51：确认期最长等多久 —— 目标一直小幅移动时不能把屏幕冻死 */
    private val snapHardMs: Long = 60_000L
) {

    private var shown = Float.NaN
    private var lastMs = 0L
    /** seed 过或已经收过样本 —— 不能只看 lastMs==0，调用方可以合法地从 0 时刻起手 */
    private var hasValue = false
    /** 直跳的"待确认目标"与它第一次出现的时间 */
    private var pendTarget = Float.NaN
    private var pendSince = 0L

    fun value(): Float = shown

    fun reset() {
        shown = Float.NaN
        lastMs = 0L
        hasValue = false
        pendTarget = Float.NaN
        pendSince = 0L
    }

    /** 用持久化的值起手，避免"断开后重开"从 NaN 开始飘 */
    fun seed(value: Float, nowMs: Long) {
        if (value.isNaN()) return
        shown = value.coerceIn(0f, 100f)
        lastMs = nowMs
        hasValue = true
    }

    /**
     * 喂一个目标值。
     *
     * @param nowMs     当前时刻
     * @param target    上游算出来的电量（NaN 表示这一拍没有输入，保持不动）
     * @param allowSnap 是否允许"直跳"（只在链路刚恢复且断档较久时为 true）
     */
    fun onEstimate(nowMs: Long, target: Float, allowSnap: Boolean): Float =
        onEstimate(nowMs, target, allowSnap, false)

    /**
     * @param riding 是否正在骑（速度 ≥ 8km/h 或油门明显有量）。
     *               true 时用更大的死区、更慢的上升速度 —— 骑行中端电压本身就在摆，
     *               这时候"跟着电压往上爬"看着就像电量在乱跳。
     */
    fun onEstimate(nowMs: Long, target: Float, allowSnap: Boolean, riding: Boolean): Float {
        if (target.isNaN()) return shown
        val t = target.coerceIn(0f, 100f)
        if (!hasValue || shown.isNaN()) {
            shown = t
            lastMs = nowMs
            hasValue = true
            return shown
        }
        val dtSec = ((nowMs - lastMs) / 1000f).coerceIn(0f, 5f)
        lastMs = nowMs
        val d = t - shown
        if (allowSnap && kotlin.math.abs(d) >= snapDelta) {
            // 需要"确认"：同一个目标值稳住 6 秒才落位，闪一下就散的中间值不许上屏
            if (pendTarget.isNaN() || kotlin.math.abs(t - pendTarget) > 2f) {
                pendTarget = t
                pendSince = nowMs
                // v1.0.51：**确认期内先冻住，不许走斜坡。**
                // 旧版在这里只是"重新计时"就落到下面的限速逻辑，于是目标 20 分的跳变
                // 会以 1–2 分/秒的速度往上爬 —— 确认闸名义上拦住了"直跳"，
                // 屏幕上却照样有一段十几秒的假爬升，中间值还是被看见了。
                return shown
            } else if (nowMs - pendSince >= confirmMs) {
                shown = t
                pendTarget = Float.NaN
                pendSince = 0L
                return shown
            } else if (nowMs - pendSince >= snapHardMs) {
                // 目标一直小幅移动、6 秒确认永远凑不够（例如正在充电一路往上）：
                // 60 秒后不再等，走正常斜坡（甚至是直跳），别把屏幕冻死。
                pendTarget = Float.NaN
                pendSince = 0L
                shown = t
                return shown
            } else {
                return shown    // 确认期未到：原地不动
            }
        } else {
            pendTarget = Float.NaN
            pendSince = 0L
        }
        val band = if (riding) ridingDeadBand else deadBand
        if (kotlin.math.abs(d) <= band) return shown
        // dt 可能为 0（同一毫秒连调两次）：给一个最小步长，别把变化吞掉
        val rate = if (d > 0f && riding) ridingUpPerSec else if (d > 0f) upPerSec else downPerSec
        val step = rate * if (dtSec <= 0f) 0.25f else dtSec
        shown = if (d > 0f) kotlin.math.min(t, shown + step) else kotlin.math.max(t, shown - step)
        return shown
    }
}
