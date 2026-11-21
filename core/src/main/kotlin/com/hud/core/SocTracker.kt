package com.hud.core

/**
 * 电量估算（v1.0.0 的口径）。
 *
 * ## 为什么不再"每秒拿电压查表"
 *
 * 上一版是这么做的：端电压 → 压降补偿 → 30 秒平滑 → 查 SOC 表。
 * 实车暴露两个硬伤：
 *
 * 1. **端电压本身是噪声源**。控制器 67B 帧的电压字段是瞬时采样，
 *    同一分钟内实测在 58–69V 之间跳（见 analysis/volt_fields_*.txt）。
 *    拿它查表，电量就会 51% → 17% 地跳，骑手根本不敢信。
 * 2. **骑行时的端电压不是开路电压**。500W 电机在起步/爬坡时把电压压掉 3–6V，
 *    按表算就是"掉了 40% 电"，松油门又涨回来。
 *
 * ## 现在怎么做（无电流传感器时最靠谱的一条路）
 *
 * - **停车静置时校准**：停够 60 秒、电压用中位数，这时端电压≈开路电压，
 *   查表得到的电量是可信的，把它记成"锚点（电量, 里程）"。
 * - **骑行时按里程推算**：`电量 = 锚点电量 − 每公里耗电% × 骑行里程`。
 *   `每公里耗电%` 从"两次静置锚点"之间学出来（Δ电量 / Δ里程），越骑越准。
 * - **显示值限速**：每秒最多变 2%，任何来源的跳变（噪声、重连、重启）都不会让数字闪。
 *
 * 这条路等价于"没有库仑计的电动车仪表"做法：停着量电压，跑着数里程。
 * 它不是实验室精度，但**单调、可解释、不会从 51 跳到 17**，且能自动纠偏：
 * 每次停下来等单，电量都会按当时的静置电压重新对齐一次。
 */
class SocTracker(
    /** 每公里耗掉多少个百分点；NaN = 还没学到 */
    var perKmPct: Float = Float.NaN,
    /** 锚点电量（%） */
    var anchorSoc: Float = Float.NaN,
    /** 锚点里程（km，同 tripKm 口径） */
    var anchorKm: Float = Float.NaN,
) {

    /** 当前显示电量（%） */
    var shown: Float = Float.NaN
        private set

    private var lastMs: Long = 0L

    /** 静置电量与里程推算差超过这么多点，就认为电压读数不可信（只做缓慢纠偏） */
    var maxAnchorJumpPct: Float = 12f
    /** 不可信时每次停车最多朝电压方向挪多少点 */
    var nudgePct: Float = 3f
    /**
     * 一箱电里"朝不信电压方向纠偏"的**总预算**（百分点）。
     *
     * 为什么要有预算（v1.0.3）：骑手一天在商场门口停十几次，若每次停车都因为"控制器电压
     * 比开路电压低 1–2V"而往下挪 3 点，一天下来能白掉 30 点 —— 这正是"26.8km 只剩 4%"
     * 里那条缓慢下滑的路。给一箱电设 6 点预算，满电锚点时清零：允许纠偏，但不允许被拖走。
     */
    var nudgeBudgetPct: Float = 12f
    /** 本箱电已经用掉多少纠偏预算 */
    var nudgedTotal: Float = 0f
        private set
    /** 满电续航（km）：没有学习值时按 100/满电续航 折算 %/km。设置页改了要同步过来 */
    var defaultRangeKm: Float = 80f
    var rejects: Int = 0
        private set

    /** 行驶中"里程口径 vs 电压口径"分歧的门槛（点），不到这个差就当噪声 */
    var deadBandPct: Float = 12f
    /** 分歧要持续多久才动手（毫秒），一次长坡/猛加速能临时压掉 6–8 点，不能立刻追 */
    var sustainMs: Long = 180_000L
    /** 分歧计时起点（0 = 当前没有分歧） */
    private var divergeSinceMs: Long = 0L

    companion object {
        /**
         * 每公里耗电的上限（百分点/公里）。
         *
         * v1.0.16：2.6 → 5.0。理由不是"放宽"，而是**和既有的采信区间对齐**：
         * [anchorRest] 只在"折算成满电续航 20–150km"的样本才采信（即最高 5%/km），
         * 可是存进 perKmPct 时又被 coerceIn 夹到 2.6 —— 等于"采信了 4%/km，却按 2.6%/km 用"。
         *
         * 实车数据把这颗雷踩实了：`tools/ride_slope.py` 跑 12 份日志，这台车从满电 78.3V
         * 跑到 66V 时只走了 26.8km。但注意（v1.0.20 用户澄清）：那是**带载压降**被当成了真实掉电，
         * 车本身是全新 72V/21Ah、满电约 80km。所以真正要修的是"把压降当成掉电"，
         * 上限只保留"电池彻底报废"的边界（5%/km = 满电 20km）。
         *
         * 上限 5.0 只是安全边界，不再是"典型值"；正常车会学到 1.2–1.3%/km。
         */
        private const val MAX_PER_KM = 5.0f

        /** v1.0.20：电量回升的四个门槛（见 [recoverUp]） */
        const val UP_DEAD_BAND = 4f
        const val UP_SUSTAIN_MS = 45_000L
        const val UP_STEP = 1f
        const val UP_MIN_INTERVAL_MS = 5_000L
        const val UP_BUDGET = 10f

        /**
         * v1.0.56（已废弃，保留常量供后续调整）：
         * 曾想用"静置电量 ≤ 25% 就由电压接管"，但单测里的"控制器末端塌陷"场景证明
         * 这样做会退回"0% 还能骑十公里"。真正的空电判据是控制器断电保护（见 app 侧 emptyLatch）。
         */
        const val BOTTOM_TRUST_SOC = 25f
    }

    /** 每秒最多变化多少个百分点（2%/分钟）：既能跟上真实消耗，又挡掉噪声跳变 */
    var maxStepPctPerSec: Float = 0.033f

    /** 恢复上次会话的锚点（进程重启后不要又从噪声电压开始猜） */
    fun restore(pct: Float, aSoc: Float, aKm: Float, shownSoc: Float) {
        perKmPct = pct
        anchorSoc = aSoc
        anchorKm = aKm
        shown = shownSoc
        lastMs = 0L
    }

    /**
     * 每公里耗电%，没有学习值时按"满电续航"折算（80km ≈ 1.25%/km）。
     *
     * v1.0.37：学到的值必须落在"用户给的满电续航"的 ±2 倍内。
     * 旧版只做绝对封顶（≤5%/km），于是手机里躺着 3.3%/km —— 折合满电只有 30km，
     * 界面就显示"100% · 还能跑 ~30km"，和实际 80km 差两倍多。
     * 满电续航是用户自己填的硬事实（80km），它比任何历史样本都可信：
     * 样本再离谱也不允许超过 2 倍（2.5%/km），再乐观也不低于一半（0.63%/km）。
     */
    fun perKmOrDefault(estRangeKm: Float): Float {
        val r = if (estRangeKm > 10f) estRangeKm else 80f
        val expected = 100f / r
        if (!perKmPct.isNaN() && perKmPct > 0.05f) {
            return perKmPct.coerceIn(expected * 0.4f, minOf(MAX_PER_KM, expected * 2.0f))
        }
        return expected.coerceIn(0.2f, MAX_PER_KM)
    }

    /** 按锚点 + 里程推算电量；没有锚点时返回 NaN */
    fun predict(km: Float): Float {
        if (anchorSoc.isNaN() || anchorKm.isNaN() || km.isNaN()) return Float.NaN
        val p = perKmOrDefault(defaultRangeKm)
        return (anchorSoc - p * (km - anchorKm)).coerceIn(0f, 100f)
    }

    /**
     * 停车静置校准：用"静置电压查出来的电量"作为新锚点。
     *
     * 同时用「上一次锚点 → 这一次锚点」的差值学习每公里耗电%：
     * 两次锚点至少隔 2km、电量至少掉 1%，否则样本不可信（那是噪声）。
     *
     * @return 本次学到的样本值；没学到返回 NaN
     */
    @JvmOverloads
    fun anchorRest(nowMs: Long, socRest: Float, km: Float, allowLearn: Boolean = true): Float {
        if (socRest.isNaN() || km.isNaN()) return Float.NaN
        var learned = Float.NaN
        if (allowLearn && !anchorSoc.isNaN() && !anchorKm.isNaN()) {
            val dKm = km - anchorKm
            val dSoc = anchorSoc - socRest
            if (dKm >= 2f && dSoc >= 1f) {
                val sample = dSoc / dKm
                // 样本只在"物理上可能"的区间内采信：折算成满电续航要落在 20–150km
                // （21Ah/72V 实测 45–80km；<20km 或 >150km 的样本只可能是错锚点/噪声）。
                // v1.0.3：以前是"超区间就夹到 3.0"，等于把一份坏样本当成"最高耗电"焊死。
                val impliedRange = 100f / sample
                if (sample > 0.05f && impliedRange in 20f..150f) {
                    learned = sample
                    // 段越长越可信：2km 的段只给 15% 权重，10km 以上给满 50%
                    val w = (0.05f * dKm).coerceIn(0.15f, 0.5f)
                    perKmPct = if (perKmPct.isNaN()) sample else (1f - w) * perKmPct + w * sample
                    perKmPct = perKmPct.coerceIn(0.2f, MAX_PER_KM)
                }
            }
        }
        anchorSoc = socRest
        anchorKm = km
        shown = socRest          // 停车时的校正是"正当跳变"，允许直接对齐
        lastMs = 0L
        return learned
    }

    /**
     * 静置锚点（带"离群拒绝"）。
     *
     * 实车问题（v1.0.0 → v1.0.1）：控制器报的端电压本身偏低/带噪（26.8km 时静置读数只有 65V，
     * 按表就是 5%），如果每次停车都无条件把电压当锚点，电量会被一路按到 4%，
     * 而骑手明明还能跑 —— 这正是用户说的"里程和电量对不上"。
     *
     * 做法（和手机电量计的 OCV 纠正同一个思路）：**里程口径是主口径，电压只做缓慢纠正**。
     *  - 静置电量与里程推算相差 ≤ [maxAnchorJumpPct] 点 → 正常锚定（真校准）
     *  - 相差太大 → 说明这一路的电压读数不可信（老化电池压降大/表偏低），
     *    只朝电压方向挪 [nudgePct] 点，绝不整段跳过去
     *
     * @return 学到的 %/km（没学到返回 NaN）；[rejects] 记录"被拒了几次"
     */
    fun anchorRestGuarded(nowMs: Long, socRest: Float, km: Float): Float {
        if (socRest.isNaN() || km.isNaN()) return Float.NaN
        val pred = predict(km)
        if (pred.isNaN()) {
            rejects = 0
            return anchorRest(nowMs, socRest, km)
        }
        val diff = socRest - pred
        if (kotlin.math.abs(diff) <= maxAnchorJumpPct) {
            // 相差不大 = 正常锚定；但**向下**那一边也要限速/限额（v1.0.3）：
            // 每次停车最多往下 4 点、一箱电最多 12 点。控制器电压系统性偏低 1–2V（≈3–6 点），
            // 若每个红绿灯都整段对齐，一天停十几次就能把 60% 拖到 4% —— 这是"26.8km 只剩 4%"的真凶。
            if (diff < 0f) {
                val room = nudgeBudgetPct - nudgedTotal
                val step = minOf(-diff, 4f, if (room > 0f) room else 0f)
                nudgedTotal += step
                // v1.0.7：**纠偏这一下不参与 %/km 的学习**（allowLearn = false）。
                // 旧版这里调 anchorRest(pred - step) 且照常学习：这一步被"往下挪了 4 点"
                // 也会被记成"这几公里真的耗了这 4 点"，每停一次就把 perKmPct 往上推一点，
                // 十几次停靠就能把它顶到 3.0 上限 —— 于是出现"26.8km 只剩 4%、还能跑 1km"。
                // 学习只该学"真实的消耗"，不该学"我们自己的修正量"。
                return anchorRest(nowMs, pred - step, km, false)
            }
            rejects = 0
            return anchorRest(nowMs, socRest, km)
        }
        rejects++
        // v1.0.56 曾经试过"底部一律信电压"，被单测否掉了（见 `一箱电的纠偏预算用完就不再往下拉`）：
        // 这台车的末端电压会塌陷（11-17 在 26.8km 时静置只有 66.2V≈4%），若底部无条件信电压，
        // 就会退回"0% 还能骑十公里"。真正的"到底了"只有一个硬证据 —— 控制器断电保护，
        // 那条走空电硬着陆（emptyLatch），不在这里。
        // 前两次先不动（单次异常电压不能改口径），连续三次都偏低才慢慢纠
        if (rejects < 3) {
            anchorKm = km
            anchorSoc = pred
            return Float.NaN
        }
        // v1.0.3：一箱电里的纠偏总预算用完就只守住里程口径，不再往下拉
        val room = nudgeBudgetPct - nudgedTotal
        if (room <= 0.01f) {
            anchorKm = km
            anchorSoc = pred
            return Float.NaN
        }
        val dir = if (socRest < pred) -1f else 1f
        val step = if (nudgePct < room) nudgePct else room
        nudgedTotal += step
        val nudged = (pred + dir * step).coerceIn(0f, 100f)
        anchorSoc = nudged
        anchorKm = km
        shown = nudged
        lastMs = 0L
        return Float.NaN
    }

    /** 满电（新的一箱电）时清空纠偏预算 */
    fun resetNudgeBudget() {
        nudgedTotal = 0f
        divergeSinceMs = 0L
    }

    /**
     * v1.0.56：**空电硬着陆**。
     *
     * 控制器已经报"低电压保护、拧油门不走"的时候，账本说什么都不重要了 —— 电池确实到头了。
     * 直接把锚点按到 [soc]（默认 3%）并同步显示值，避免"明明推车、屏幕还写 21% / 还能跑 19km"。
     * 这个动作不参与 %/km 学习，也不占纠偏预算。
     */
    fun hardLand(nowMs: Long, km: Float, soc: Float) {
        if (km.isNaN()) return
        val s = soc.coerceIn(0f, 100f)
        anchorSoc = s
        anchorKm = km
        shown = s
        lastMs = nowMs
        nudgedTotal = 0f
        divergeSinceMs = 0L
        rejects = 0
    }

    /**
     * v1.0.56：把锚点里程平移 [delta] 公里。
     *
     * 对表时"本次里程"被补了一块（仪表口径），锚点里程也要跟着挪同样的距离，
     * 否则下一秒 `predict` 就会因为里程突变而让电量跳一下。
     */
    fun shiftAnchorKm(delta: Float) {
        if (delta == 0f || anchorKm.isNaN()) return
        anchorKm = (anchorKm + delta).coerceAtLeast(0f)
    }

    /**
     * 满电时的"耗电率回摆"（v1.0.18）。
     *
     * 场景：换了新电瓶，或者旧电瓶老化被换掉。旧电瓶可能学到 3.7%/km（满电只能跑 27km），
     * 这个值会一直存在手机里。换新电瓶后（满电能跑 80km ≈ 1.25%/km），如果还按 3.7%/km 推算，
     * 第一箱电会显示"电量掉得飞快"，骑手会以为新电瓶也是坏的。
     *
     * 做法不是"清零重学"（那样第一箱电又完全没有依据、只能靠电压），而是**向默认值回摆**：
     * 每次真正充满，把学到的值往 [defaultPerKm] 拉 [factor]（0.35 = 拉回 35%）。
     * 如果电瓶真老化，后面几次停车校准会立刻把它学回去；如果换了好电瓶，两三箱电就回正。
     *
     * @return 回摆后的值（没学过则原样返回 NaN）
     */
    @JvmOverloads
    fun relaxPerKm(factor: Float, defaultPerKm: Float = 1.25f): Float {
        if (perKmPct.isNaN() || perKmPct <= 0f) return Float.NaN
        val f = factor.coerceIn(0f, 1f)
        perKmPct = (perKmPct * (1f - f) + defaultPerKm * f).coerceIn(0.2f, MAX_PER_KM)
        return perKmPct
    }

    // ---------------- v1.0.20：允许电量"往回涨" ----------------

    /** 本箱电已回升的点数、以及"电压口径偏高"持续了多久 */
    private var recoveredTotal = 0f
    private var recoverSinceMs = 0L
    /** 上一次"真的涨上去"的时刻。
     *  🔴 不能复用 [lastMs] —— 它每次 [update] 都会被刷新，于是"两次回升至少隔 5 秒"
     *  这个判断会永远成立，回升一次都触发不了（整段骑行重放单测抓到的）。 */
    private var recoverLastMs = 0L

    /**
     * 电压口径明显比显示值高时，允许把电量**往回抬**（v1.0.20）。
     *
     * 为什么必须有这一步：以前的纠偏是"只降不升"。用户实测到的场景是：
     * 上坡满油门 → 端电压被拉低 → 电量被压下去几档；到平路松油门 → 电压回到 OCV，
     * **电量却再也不回来**，续航也跟着少一截。等于"爬一次坡，永久扣电"。
     *
     * 规则（保守，防噪声把数字抬得忽高忽低）：
     *  - 电压口径比显示值高 ≥ [UP_DEAD_BAND] 才算"该涨"
     *  - 要连续维持 [UP_SUSTAIN_MS]（45 秒）才动
     *  - 每次最多 +[UP_STEP]（1 点），两次之间至少隔 [UP_MIN_INTERVAL_MS]（5 秒）
     *  - 一箱电总共回升不超过 [UP_BUDGET]（10 点）
     *
     * 调用方必须保证"此刻是**低负载**"（松油门/滑行/停车）；带载电压偏低，不该触发。
     *
     * @return 本次实际抬高的点数；没动则为 0
     */
    fun recoverUp(nowMs: Long, km: Float, socByVolt: Float): Float {
        if (socByVolt.isNaN() || shown.isNaN() || km.isNaN()) return 0f
        if (socByVolt - shown < UP_DEAD_BAND) {
            recoverSinceMs = 0L
            return 0f
        }
        if (recoverSinceMs == 0L) {
            recoverSinceMs = nowMs
            return 0f
        }
        if (nowMs - recoverSinceMs < UP_SUSTAIN_MS) return 0f
        if (recoverLastMs != 0L && nowMs - recoverLastMs < UP_MIN_INTERVAL_MS) return 0f
        if (recoveredTotal >= UP_BUDGET) return 0f
        val step = minOf(UP_STEP, UP_BUDGET - recoveredTotal, socByVolt - shown)
        if (step <= 0f) return 0f
        shown = (shown + step).coerceAtMost(100f)
        // 🔴 关键：**锚点必须跟着抬**。否则下一秒 [update] 就按 `predict(锚点)` 把 shown 拉回去，
        // 这个回升等于白做（v1.0.20 首版就是漏了这一步）。
        // 把锚点按同一步长抬高、锚点里程挪到当前里程，等于承认"我们之前把电量估低了"。
        if (!anchorSoc.isNaN() && !anchorKm.isNaN()) {
            anchorSoc = (anchorSoc + step).coerceAtMost(100f)
            anchorKm = km
        }
        recoveredTotal += step
        recoverLastMs = nowMs           // 记在独立字段上，别让 update() 覆盖
        recoverSinceMs = nowMs          // 再涨要重新等一个 sustain，避免一路猛冲
        return step
    }

    /** 新的一箱电：回升预算归零 */
    fun resetRecoverBudget() {
        recoveredTotal = 0f
        recoverSinceMs = 0L
        recoverLastMs = 0L
    }

    /** 本箱电已经回升了多少点（日志/诊断用） */
    fun getRecoveredTotal(): Float = recoveredTotal

    /**
     * 行驶中把"里程口径"往"电压口径"拉一次（v1.0.10）。
     *
     * 为什么必须要有这一步：一旦有了锚点，[update] 的 target 就**完全不看电压**，
     * 只按 `锚点 − %/km × 里程` 走。于是 `%/km` 只要学偏一点（比如把一次满电骑行
     * 学成 2.2%/km 而实际是 1.4%/km），数字就会一路偏离真实电压，
     * 而"停车静置校准"不一定有机会发生（骑手一整天都在跑，或者闸门一直不满足）。
     * 实测就是这样：26.8km 显示 4%，电压却是 65V 带载 —— 两个口径各说各的。
     *
     * 做法（和商用 BMS 的 SOC 融合同思路，但更保守）：
     *  - 分歧 < 12 点不动（带载压降补偿本身就有 3–5 点残差，追噪声没意义）
     *  - 分歧必须**持续 3 分钟**才认（一次长坡、一次猛加速都能临时压下去 6–8 点）
     *  - 一次最多挪 3 点，一箱电总共最多 12 点（复用 [nudgedTotal] 预算）
     *  - **不参与 %/km 学习**（学到的必须是真的骑行消耗，不能是我们自己的修正量）
     *
     * @return 调整后的下界（没调整返回 NaN）
     */
    fun fuseWithVoltage(nowMs: Long, km: Float, socByVolt: Float): Float {
        if (socByVolt.isNaN() || km.isNaN()) return Float.NaN
        val pred = predict(km)
        if (pred.isNaN()) return Float.NaN
        val diff = socByVolt - pred
        if (kotlin.math.abs(diff) < deadBandPct) { divergeSinceMs = 0L; return Float.NaN }
        if (divergeSinceMs == 0L) { divergeSinceMs = nowMs; return Float.NaN }
        if (nowMs - divergeSinceMs < sustainMs) return Float.NaN
        val room = nudgeBudgetPct - nudgedTotal
        if (room <= 0.01f) return Float.NaN
        val step = minOf(nudgePct, room)
        nudgedTotal += step
        val dir = if (diff > 0f) 1f else -1f
        val nudged = (pred + dir * step).coerceIn(0f, 100f)
        anchorSoc = nudged
        anchorKm = km
        shown = nudged
        divergeSinceMs = 0L                 // 挪过就重新计时，下一次还要再攒 3 分钟
        lastMs = nowMs
        return nudged
    }

    /**
     * 每秒调用一次，返回要显示的电量。
     *
     * @param socByVolt 本次静置/瞬时电压查表得到的电量（仅在完全没有锚点时用作起点）
     */
    fun update(nowMs: Long, km: Float, socByVolt: Float): Float {
        val dt = if (lastMs <= 0L) 0f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 5f)
        lastMs = nowMs

        val target = predict(km).let { if (it.isNaN()) socByVolt else it }
        if (target.isNaN()) return shown
        val t = target.coerceIn(0f, 100f)
        if (shown.isNaN()) {
            shown = t
            return shown
        }
        val step = maxStepPctPerSec * dt
        val delta = (t - shown).coerceIn(-step, step)
        shown = (shown + delta).coerceIn(0f, 100f)
        return shown
    }

    /** 剩余续航：电量% ÷ 每公里耗电%；上限就是用户给的满电续航（别让学偏的 %/km 算出 96km） */
    fun rangeKm(estRangeKm: Float): Float {
        if (shown.isNaN()) return Float.NaN
        val p = perKmOrDefault(estRangeKm)
        val cap = if (estRangeKm > 10f) estRangeKm else 80f
        return (shown / p).coerceIn(0f, cap)
    }

    /** 里程被清零（或里程比锚点还小）时把锚点拉到当前位，别让推算变成负数 */
    fun rebaseIfBehind(km: Float) {
        if (anchorKm.isNaN() || km.isNaN()) return
        if (km + 0.3f < anchorKm) {
            anchorKm = km
            anchorSoc = shown.let { if (it.isNaN()) anchorSoc else it }
        }
    }
}
