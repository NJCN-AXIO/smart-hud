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
     * v1.0.63：静置电压比账本**高**时，一次停车允许向上抬多少点。
     *
     * 向上这一侧和向下不一样：能进静置闸门（停稳 20 秒、无油门、低速）的 OCV 是独立读数，
     * 旧锚点偏低（半充电后没重锚、长停放回弹）时就该抬上来。向下一侧仍由 [nudgePct] /
     * [nudgeBudgetPct] / 三次 reject 守着（带载压降、末端塌陷都在那一侧）。
     */
    var upStepPct: Float = 10f
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

    /** v1.0.68：上一次"大幅偏低"的静置读数（%），用来判断电压是否稳定停在同一处 */
    private var lastLowSoc: Float = Float.NaN

    /**
     * v1.0.69：**上一次"往上抬"留下的账**。
     *
     * 11-26 现场把这条的必要性钉死了：用户在家补完电再骑，充电器拔掉后的表面电荷让静置读到
     * 77.3–78.0V（查表 94），电量于是被一路抬到 94%；十几分钟后电压回落到 74.8V（真值 78），
     * 账本却掉不下来 —— 每停一次只准往下 4 点、一箱电总共 12 点，等它落回 78 要停五六次，
     * 这段时间屏幕一直"94% / 续航 60 多公里"，用户就是被这个数骗到推车的。
     *
     * 做法：向上抬时记下"抬到了哪个值"，下次静置若电压比这个值低 [upBackPct] 以上，
     * 说明当初抬的依据（浮电/表面电荷）已经不成立，**整段还回去**，不再走三次确认 + 4 点慢挪。
     * 只在 [upBackWindowMs] 内有效：40 分钟以前的旧账不再翻（那多半是真实掉电，该按里程走）。
     */
    private var upMarkSoc: Float = Float.NaN
    private var upMarkMs: Long = 0L
    /** 回落判据：比上抬时的锚点低多少点算"上抬依据失效" */
    var upBackPct: Float = 10f
    /** 回落有效期（毫秒）：超过这么久的上抬不再回滚 */
    var upBackWindowMs: Long = 40L * 60_000L

    /** v1.0.57：学习斜率的参考点（上一次被电压确认的锚点），[learnPerKmFromRest] 用 */
    private var learnRefSoc: Float = Float.NaN
    private var learnRefKm: Float = Float.NaN

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
        /**
         * v1.0.63：回升预算 10 → 16。半充电/长停放后旧账本偏低，低负载滑行时要能把欠的点还回来；
         * 门槛（死区/持续时长/间隔/单步）不变，噪声抬不上去。
         */
        const val UP_BUDGET = 16f

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
        // v1.0.69：**里程比锚点小，不是"往回充电"。**
        //
        // 11-26 两次都是这条把电量顶上去的：
        //   ① 10:21:29 对表 `trip set 55.89km <- 68.05km`（anchor shift −12.17），之后 tripKm 被清零到 0.46，
        //      而 anchorKm 还停在 56 —— `45 − 1.25×(0.46−56)` = 114，夹到 100，屏幕直接按电压 84 走；
        //   ② 14:02:46 重启，`gap since last run: 138 min (odometer may be short)`，tripKm 只有 16.82，
        //      anchorKm 仍在上一次会话的 40 多 —— 同样算出 90 多，于是"连上一看九十多"。
        // 里程只会往前走：跑过的距离一律按 ≥0 算，缺口由 [rebaseIfBehind] 去补。
        val ran = (km - anchorKm).coerceAtLeast(0f)
        return (anchorSoc - p * ran).coerceIn(0f, 100f)
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
        // v1.0.57：这个锚点是"电压给的"、可信度最高，学习斜率时以它为参考
        learnRefSoc = socRest
        learnRefKm = km
        return learned
    }

    /**
     * v1.0.57：**学"每公里掉多少电"这件事，不该被"能不能跳锚点"绑住。**
     *
     * 11-20/21 那一箱电的现场：17:38 停在 58.7km，静置电压查表 15%，里程账本 32%，
     * 差 17 点 > [maxAnchorJumpPct](12) → [anchorRestGuarded] 拒了。拒得对（这台车末端电压会塌陷），
     * 但副作用是**连"这一段到底耗了 1.45%/km"这个信息也一起丢了** —— perKm 于是永远停在 1.14，
     * 底部一路偏乐观到"21% / 还能跑 19km"。
     *
     * 做法（和手机库仑计"只在对数点上更新斜率"一个思路）：
     *  - 参考点 = 上一次**被电压确认过**的锚点（[learnRefSoc]/[learnRefKm]，由 [anchorRest] 维护）；
     *  - 段要够长（≥8km）差值要够大（≥5 点），否则是噪声；
     *  - 折算成满电续航必须落在 45–145km —— 这一条专门挡"末端塌陷"（26.8km 读到 4% 那种，
     *    折算出来只有 28km，直接丢）；
     *  - 一次不许跳太远（新旧斜率比 0.75–1.35），防止一个坏样本把模型带跑。
     *
     * @return 更新后的 %/km；没学到返回 NaN
     */
    fun learnPerKmFromRest(socRest: Float, km: Float): Float {
        if (socRest.isNaN() || km.isNaN()) return Float.NaN
        if (learnRefSoc.isNaN() || learnRefKm.isNaN()) {
            learnRefSoc = socRest
            learnRefKm = km
            return Float.NaN
        }
        val dKm = km - learnRefKm
        val dSoc = learnRefSoc - socRest
        if (dKm < 8f || dSoc < 5f) return Float.NaN
        val sample = dSoc / dKm
        if (sample <= 0.05f) return Float.NaN
        val impliedRange = 100f / sample
        if (impliedRange < 45f || impliedRange > 145f) return Float.NaN
        // 一次不许跳太远：段越长越可信，40km 的段允许斜率改 60%（1.14→1.61 就是这种），
        // 10km 的段只允许 25%（防止一个坏样本把模型带跑）
        val maxRatio = if (dKm >= 20f) 1.6f else 1.25f
        val minRatio = if (dKm >= 20f) 0.7f else 0.8f
        if (!perKmPct.isNaN() && (sample / perKmPct < minRatio || sample / perKmPct > maxRatio)) {
            return Float.NaN
        }
        val w = (0.05f * dKm).coerceIn(0.2f, 0.5f)
        perKmPct = if (perKmPct.isNaN()) sample else (1f - w) * perKmPct + w * sample
        perKmPct = perKmPct.coerceIn(0.2f, MAX_PER_KM)
        learnRefSoc = socRest
        learnRefKm = km
        return perKmPct
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

        // v1.0.63：向上（静置电压口径比账本高）放宽。
        // v1.0.68：**中段小幅整段对齐**。用户的使用方式是"骑一段就回家补电、补一会儿再骑"，
        // 补电把真实电量顶上去了，账本却还停在补电前的低位 —— 11-26 实测 74.9V 静置（查表 78%）
        // 账本只有 60%，差 18 点被 upStepPct(10) 截住，屏幕上长期偏低。
        // 现在：查表值 ≥20%（末端塌陷区之外）且差 ≤20 点 → 整段锚定到电压口径；
        // 差更大（>20 点，比如半电误判成满电后回弹）仍按 upStepPct 一点一点抬。
        if (diff > 0f) {
            rejects = 0
            lastLowSoc = Float.NaN
            if (diff <= maxAnchorJumpPct || (socRest >= 20f && diff <= 20f)) {
                val learned = anchorRest(nowMs, socRest, km)
                // v1.0.69：往上抬要留账 —— 这个值之后被电压证伪，就要能整段还回来
                markUp(nowMs)
                return learned
            }
            val upStep = minOf(diff, upStepPct)
            anchorRest(nowMs, pred + upStep, km, false)
            markUp(nowMs)
            return Float.NaN
        }

        if (-diff <= maxAnchorJumpPct) {
            // 差值为 0（电压口径和账本一致）：正常锚定，照常学斜率。
            if (diff >= 0f) {
                rejects = 0
                return anchorRest(nowMs, socRest, km)
            }
            // 相差不大但偏低：向下那一边也要限速/限额（v1.0.3）：
            // 每次停车最多往下 4 点、一箱电最多 12 点。控制器电压系统性偏低 1–2V（≈3–6 点），
            // 若每个红绿灯都整段对齐，一天停十几次就能把 60% 拖到 4% —— 这是"26.8km 只剩 4%"的真凶。
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
        // v1.0.69：**上抬留账，跌了就还。**
        //
        // 11-26 的链条：补完电浮电 77.3–78.0V（查表 94）→ 电量被抬到 94 → 十几分钟后电压回落到
        // 74.8V（真值 78）→ 账本想落，却要过"12 点死区 + 三次确认 + 4 点/次 + 12 点预算"，
        // 停五六次才落得下来；用户在这中间看到的一直是"94% / 60 多公里"，然后被这个数骗到推车。
        // 这里给一条专用通道：只要这次静置比"当初上抬时的锚点"低 [upBackPct] 以上，
        // 就认定那次上抬的依据（浮电/表面电荷）已经不成立，整段落回电压口径。
        // 不占 rejects、不搞慢挪；但照样记进纠偏预算（"一箱电纠偏有总额度"这条契约不能破）。
        val upBack = !upMarkSoc.isNaN() && (upMarkSoc - socRest) >= upBackPct
                && upMarkMs > 0L && nowMs - upMarkMs <= upBackWindowMs
        if (upBack) {
            upMarkSoc = Float.NaN
            upMarkMs = 0L
            lastLowSoc = Float.NaN
            rejects = 0
            nudgedTotal = minOf(nudgeBudgetPct, nudgedTotal + (-diff))
            return anchorRest(nowMs, socRest, km, false)
        }

        rejects++
        // v1.0.68：**中段账本偏高要能落下来，但必须是"电压两次说到同一处"。**
        // 11-26 现场：17:25 静置 68.99V（查表 23%），账本写着 60% —— 差 37 点被 12 点死区拒掉，
        // 结果"以为还有一半电"，骑到 19:05 控制器断电、推车回家，正是用户说的"误导"。
        // 规则：查表值 ≥20%（避开末端塌陷区，<20% 仍走三次确认 + 3 点慢挪），
        // 且和**上一次偏低读数**相差 ≤5 点（说明电压稳定停在同一处，不是噪声/带载压降），
        // 就整段落回电压口径。测试里"每次差 30 点、读数还一路往下走"的场景不满足这条。
        val stableLow = !lastLowSoc.isNaN() && kotlin.math.abs(socRest - lastLowSoc) <= 5f
        lastLowSoc = socRest
        if (socRest >= 20f && stableLow && -diff <= 40f) {
            rejects = 0
            lastLowSoc = Float.NaN
            // 整段落回也算"朝电压方向纠偏"，记进预算（并夹到上限），保证"一箱电纠偏有总额度"这条契约仍成立
            nudgedTotal = minOf(nudgeBudgetPct, nudgedTotal + (-diff))
            return anchorRest(nowMs, socRest, km, false)
        }
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
        val step = if (nudgePct < room) nudgePct else room
        nudgedTotal += step
        val nudged = (pred - step).coerceIn(0f, 100f)
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
        lastLowSoc = Float.NaN
        // v1.0.69：新箱电不背上一箱的"上抬账"
        upMarkSoc = Float.NaN
        upMarkMs = 0L
    }

    /** v1.0.69：记下"这次上抬的依据值"，供 [anchorRestGuarded] 判断要不要整段还回来 */
    private fun markUp(nowMs: Long) {
        upMarkSoc = anchorSoc
        upMarkMs = nowMs
    }

    /** v1.0.67：新一箱电同时清掉离群拒绝计数（否则上一箱的 reject 残留会把本次静置校准一直挡在拒绝通道） */
    fun clearRejects() {
        rejects = 0
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
        upMarkSoc = Float.NaN
        upMarkMs = 0L
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
