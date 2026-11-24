package com.hud.delivery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.hud.delivery.HudState
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 极简仪表盘：三个大数字（电压 / 电量 / 本次里程）。
 *
 * 颜色规则直接写在 Compose 里（不再散落在 Canvas 绘制代码里）：
 *   电压  带载 < 62.8V 红、< 65.8V 黄（v1.0.53：基线随设置的耗尽静置电压走，不再写死 67V）
 *   时速  超「超速提醒」阈值红、85% 黄（v1.0.53：不再写死 25/35 —— 那是电动自行车国标线）
 *   电量  ≤20% 红、≤40% 黄
 *   里程  50–60km 黄、>60km 红
 *   剩余  <8km 红、<15km 黄
 *
 * 数据来源是 HudState（服务写、界面读），这里 120ms 轮询一次触发重组。
 */
private val BG = Color(0xFF07090C)
/**
 * 主色四套（v1.0.5，参考 FarDriver 独立仪表的主题切换）。
 * 逻辑色（黄/红）不动，只换"正常值"的颜色：夜里青、强反光白天白、雾天琥珀。
 * 设置页可选，也可在顶部双击快速切。
 */
private val THEMES = listOf(
    Color(0xFF35E07A),   // 绿：默认，最亮眼
    Color(0xFF37D3E0),   // 青：夜里不刺眼
    Color(0xFFFFC24B),   // 琥珀：雾天/强反光对比最好
    Color(0xFFF2F5F8),   // 白：正午太阳直射
)

private fun accent(): Color = THEMES[HudState.themeIdx.coerceIn(0, THEMES.lastIndex)]
private val AMBER = Color(0xFFFFC24B)
private val RED = Color(0xFFFF5A5A)
private val MUTED = Color(0xFF9FB3C8)
private val DIM = Color(0xFF6B7480)
private val TEXT = Color(0xFFEDF1F5)
/** 底部按钮条底色：比纯黑略亮一档，既看得出是"可点的区域"，又不抢数字的注意力 */
private val BAR = Color(0xFF141A21)

/** 匹配 "11:22:33:44:55:66" 这种 MAC，用于在状态栏里换成设备名 */

/**
 * 电压颜色：按"离彻底没电还有多远"着色。
 *
 * v1.0.53 修正：`vEmpty` 的口径已经变成**静置**耗尽电压（63.8）了，而屏幕上显示的是
 * **带载**电压 —— 这车带载满跑时会比静置低 5V 左右。原来的 "empty+3 / empty+6" 于是
 * 变成 66.8V 就标红：那时其实还有 15–20%，等于一直在吓人。
 * 现在把基线挪到"带载起点 = vEmpty − 4"上（63.8 → 59.8），红 < 62.8、黄 < 65.8。
 */
private fun voltageColor(v: Float): Color {
    if (v.isNaN()) return MUTED
    val restEmpty = HudState.vEmpty.takeIf { it > 0f } ?: 63.8f
    val loadedEmpty = restEmpty - 4f
    return when {
        v < loadedEmpty + 3f -> RED
        v < loadedEmpty + 6f -> AMBER
        else -> accent()
    }
}

private fun socColor(s: Float): Color = when {
    s.isNaN() -> MUTED
    s <= 20f -> RED
    s <= 40f -> AMBER
    else -> accent()
}

private fun tripColor(km: Float): Color = when {
    km > 60f -> RED
    km >= 50f -> AMBER
    else -> TEXT
}

private fun rangeColor(km: Float): Color = when {
    km.isNaN() -> MUTED
    km < 8f -> RED
    km < 15f -> AMBER
    else -> accent()
}

/** 锚点是多久之前录的：骑手要知道"这个数是刚看的还是半小时前的" */
private fun anchorAge(): String {
    val ms = HudState.manualAgeMs
    if (ms <= 0L) return ""
    val min = ms / 60000L
    return when {
        min < 1L -> "刚录入"
        min < 60L -> " ${min} 分钟前"
        else -> " ${min / 60L} 小时 ${min % 60L} 分前"
    }
}

/** v1.0.44："这个电量是断开前几分几秒读的" —— 用来告诉骑手读数有多旧 */
private fun freezeAgeText(): String {
    val ms = HudState.freezeAgeMs
    if (ms <= 0L) return ""
    val s = ms / 1000L
    return when {
        s < 60L -> " ${s} 秒前"
        s < 3600L -> " ${s / 60L} 分钟前"
        else -> " ${s / 3600L} 小时 ${(s % 3600L) / 60L} 分前"
    }
}

/**
 * 时速颜色：跟着设置里的「超速提醒」走。
 *
 * v1.0.53 修正：原来写死 25 黄 / 35 红 —— 那是电动自行车的国标线，
 * 而这台 72V 电摩实测能上 50，设置里也可以把阈值改到 50，两边早就对不上。
 * 现在：超过阈值红；到阈值的 85% 黄。
 * v1.0.61：阈值为 0（提醒关闭）时不再按 25/35 变色，时速一律用主题色，不做超速提示。
 */
private fun speedColor(kmh: Float): Color {
    if (kmh.isNaN()) return DIM
    val lim = HudState.speedLimitKmh
    if (lim <= 0) return accent()
    return when {
        kmh > lim.toFloat() -> RED
        kmh >= lim * 0.85f -> AMBER
        else -> accent()
    }
}

private fun one(v: Float): String =
    if (v.isNaN()) "--.-" else String.format(Locale.US, "%.1f", v)

private fun pct(v: Float): String =
    if (v.isNaN()) "--" else String.format(Locale.US, "%.0f", v)

private fun num(v: Long): String = String.format(Locale.US, "%,d", v)

@Composable
fun HudScreen(
    onManual: () -> Unit,
    onMark: () -> Unit,
    onSettings: () -> Unit,
    onResetTrip: () -> Unit,
    onToggleUi: () -> Unit,
    onRetry: () -> Unit,
    onCycleBright: () -> Unit = {},
    onOdo: () -> Unit = {},
    /** v1.0.33：左右滑动切换「极简模式」（只留时速 + 电量两个超大数字） */
    onToggleLite: () -> Unit = {},
) {
    // HudState 是普通静态字段：靠 seq 自增触发重组
    var seq by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            seq = HudState.seq
            delay(120)
        }
    }
    // 读一次 seq：Compose 靠它知道该重组了
    @Suppress("UNUSED_VARIABLE")
    val tick = seq

    // ---- OLED 防烧屏 ----
    // 这块屏一天要在同样的位置亮 8 小时，三个固定大字最容易把像素烙印。
    // 每 90 秒把内容整体挪 3dp（背景不动），慢到人看不出来，但像素确实换过位置。
    var shiftStep by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(90_000)
            shiftStep = (shiftStep + 1) % 4
        }
    }
    val shift = when (shiftStep) {
        1 -> 3 to 2
        2 -> -3 to 2
        3 -> 2 to -2
        else -> 0 to 0
    }

    // 诊断详情默认不显示：长按底部状态行才出来（给排查问题用的，骑手不需要）
    var showDiag by remember { mutableStateOf(false) }

    val v = HudState.voltage
    // 链路 14–17 秒被踢一次 → 严格 8 秒过期会让屏幕一半时间是空白。
    // 这里保留"上次真实读数 + 多久之前"，大字变暗显示，既不骗人也看得见。
    val lastV = HudState.lastGoodV
    val lastVAgeS = if (HudState.lastGoodVTs > 0L)
        ((System.currentTimeMillis() - HudState.lastGoodVTs) / 1000L) else -1L
    val showStale = v.isNaN() && !lastV.isNaN() && lastVAgeS in 0..300
    val shownV = if (!v.isNaN()) v else if (showStale) lastV else Float.NaN
    val soc = HudState.soc
    val km = HudState.tripKm
    val range = HudState.rangeKm
    val src = HudState.voltageSource
    val note = HudState.note
    val frames = HudState.frames
    val mapSuspect = HudState.mapSuspect
    val speedNow = HudState.gpsKmh

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            // 背景铺满不动；底部按钮条固定贴底（不跟着防烧屏位移一起动，否则手指刚记住的位置会跑）
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 12.dp)
    ) {
        // 只有"数据区"做防烧屏位移：三个大数字和顶部时速/状态
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // v1.0.37：这里原本是 `.padding(horizontal = shift.first.dp, ...)`，
                // 而 shift 有一档是 **-3**（防烧屏要往左上挪）。Compose 的 padding 不允许负值，
                // 到点就抛 IllegalArgumentException: Padding must be non-negative → 开机约 3 分钟必闪退。
                // 挪位置本来就该用 offset（可以负），语义也对：只移不改布局尺寸。
                .offset(x = shift.first.dp, y = shift.second.dp)
                // v1.0.33：镜像模式 —— 夜间把手机平放在仪表台上、靠挡风玻璃反射来看（DigiHUD 的 HUD Mode）。
                // 只翻"数字区"，底部三个按钮和状态行保持正向：反射时按钮在远处无所谓，
                // 但手要能按得着，不能跟着一起翻。
                .graphicsLayer(scaleY = if (HudState.mirrorMode) -1f else 1f)
                .pointerInput(Unit) {
                    // v1.0.33：左右滑动切极简模式。要滑过 60dp 才算数，
                    // 否则骑车时手指蹭一下屏幕就换版式了。
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onHorizontalDrag = { _, d -> acc += d },
                        onDragEnd = { if (acc > 60f || acc < -60f) onToggleLite() }
                    )
                }
        ) {
        // ---- 顶部状态（点一下重拉服务；长按切回自绘界面：Compose 排错时的逃生门） ----
        //
        // v1.0.43：用户反馈"左上角文案太长、位置还跳来跳去"。
        // 之前 Row 用 SpaceBetween + 文本 weight(fill=false)，文字一变长，整行就重新分配空位，
        // 于是状态词一会儿在左、一会儿被挤到右边，甚至压到时速上。
        // 现在改成固定槽位：圆点 + 6dp + **固定宽 96dp** 的短文案（单行、超出省略）+ Spacer 撑开 + 时速。
        // 文案只留四档：已连接 / 连接中 / 等待连接 / 未连接；详细原因放到底部状态行（骑手能据此动作）。
        val connShort = when (HudState.connState) {
            HudState.CONNECTED -> "已连接"
            HudState.CONNECTING -> "连接中"
            HudState.SCANNING -> "等待连接"
            else -> "未连接"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.0.48：**固定 48dp**。这一行里有时速 34sp，高度会随系统字体缩放变，
                // 一旦变，下面的 weight(1f) 数字区就跟着变，三个大数字（字号还按格子高度算）会整体上下跳。
                .heightIn(min = 52.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (frames == 0L) onRetry() },
                        // v1.0.5：双击切亮度（白天/夜间/自动），对标 FarDriver 的 DIM 按钮。
                        // 放顶部是为了不和三个数字格的长按/点击打架，骑车时也够大。
                        onDoubleTap = { onCycleBright() },
                        onLongPress = { onToggleUi() }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // v1.0.33：GPS 信号灯（绿=有定位、黄=刚丢/精度差、红=没权限/没信号）——
            // 借鉴 Simple Speedometer 的卫星质量指示：一眼就知道"这个时速能不能信"。
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            HudState.gpsWarn.isNotEmpty() -> RED
                            speedNow.isNaN() -> AMBER
                            else -> accent()
                        }
                    )
                    .size(7.dp)
            )
            BasicText(
                text = connShort,
                modifier = Modifier.width(96.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = if (HudState.connState == HudState.CONNECTED) accent() else DIM,
                    fontSize = 12.sp
                )
            )
            Spacer(Modifier.weight(1f))
            // 时速放右上：骑行时最常瞄的就是它。
            // v1.0.21（收尾审计）：删掉"控制器报了就用控制器"的死分支 ——
            // 67 字节帧里没有轮速字段（3820 帧逐字节穷举已确认），`HudState.speedKmh` 永远是 NaN，
            // 留着只会让人误以为存在控制器时速。现在唯一信源就是 GPS。
            val speed = speedNow
            // v1.0.33：极简模式下时速已经是大数字，右上角就不用再重复一遍
            if (!HudState.liteMode) Row(verticalAlignment = Alignment.Bottom) {
                BasicText(
                    text = if (speed.isNaN()) "--" else String.format(Locale.US, "%.0f", speed),
                    style = TextStyle(
                        color = speedColor(speed),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                )
                BasicText(
                    text = if (speed.isNaN()) "km/h" else "km/h GPS",
                    modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                    style = TextStyle(color = speedColor(speed), fontSize = 12.sp)
                )
            }
        }
        // ---- 三个大数字：同一套字号与等高格子 ----
        // 参照 Garmin 码表的多字段网格（每个字段同一字号、同一基线）和
        // Google "Design for Driving" 的排版建议（4dp 网格对齐、主/次文本只用两档字号）。
        // 之前三个数字分别是 100/60/74sp、三块高度也不一样，所以看着不协调。
        val cellWeight = 1f

        // v1.0.33：极简模式（DigiHUD 的 Lite Mode）—— 只留"时速 + 电量"两个超大数字。
        // 强光、下雨、戴着手套的时候，三个数字反而看不清；两页滑动切换，各取所需。
        if (HudState.liteMode) {
            MetricCell(
                modifier = Modifier.weight(cellWeight),
                value = if (speedNow.isNaN()) "--" else String.format(Locale.US, "%.0f", speedNow),
                unit = "km/h",
                color = speedColor(speedNow),
                sub = "GPS 时速 · 左右滑动切回",
                subColor = DIM
            )
            SocPanelCell(
                modifier = Modifier.weight(cellWeight),
                pct = soc,
                sub = when {
                    // v1.0.53：同上 —— 骑行中电量走里程账本，不是电压直读
                    src == HudState.SRC_BLE && HudState.socAtRest -> "停稳 · 按电压"
                    src == HudState.SRC_BLE -> "骑行 · 按里程"
                    // v1.0.50：这四档原来写反了 —— 断开后（SRC_FROZEN）落到 else 显示"等待电压"，
                    // 而真正靠推算的（SRC_EST）反而写"断开前读数"。按来源逐档说清。
                    src == HudState.SRC_FROZEN -> "断开前读数"
                    src == HudState.SRC_EST || src == HudState.SRC_MANUAL -> "按里程推算"
                    else -> "等待电压"
                },
                subColor = DIM
            )
        } else {

        MetricCell(
            modifier = Modifier.weight(cellWeight),
            value = one(shownV),
            unit = "V",
            color = if (showStale) DIM else voltageColor(shownV),
            sub = when {
                showStale -> "${lastVAgeS}s 前 · 重连中"
                // v1.0.17：停车时显示的是静置电压（和小程序同一口径），骑行时是带载端电压。
                // 不写清楚的话，两个时刻看到的读数差 2–3V 会让人以为 App 不准。
                com.hud.delivery.HudState.voltIsOcv -> "静置电压"
                else -> ""
            },
            subColor = DIM
        )

        SocPanelCell(
            modifier = Modifier.weight(cellWeight),
            pct = soc,
            // 电量口径必须写清楚，但别写出和上面数字打架的话
            // v1.0.30：连上就是控制器电压直读（和小程序同一口径）；断开只冻结、不再按里程往下猜。
            sub = when {
                // v1.0.53：口径变了 —— 骑行中已经**不拿电压算电量**了（电压被压降打乱），
                // 这里再写"电压直读"就是骗人：骑起来那个数其实是里程账本推的。
                src == HudState.SRC_BLE && HudState.socAtRest -> "停稳 · 按电压查表"
                src == HudState.SRC_BLE -> "骑行 · 按里程推算"
                // v1.0.50：同上一格，来源口径逐档写清（这一档原来把"推算"写成"上次读数"）
                src == HudState.SRC_FROZEN -> "已断开 · 断开前读数"
                src == HudState.SRC_EST || src == HudState.SRC_MANUAL -> "已断开 · 按里程推算"
                else -> "等待电压"
            },
            subColor = DIM
        )

        MetricCell(
            modifier = Modifier
                .weight(cellWeight)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onResetTrip() }) },
            value = one(km),
            unit = "km",
            color = tripColor(km),
            // v1.0.5：副标题带上平均速度（码表的 AVS 口径，停车不计时）。
            // 详细统计（时长/最高速/Wh/km/上次骑行）在长按弹窗里，不挤占这一行。
            // v1.0.46：电量到底了就别说"还能跑 ~0km"（那读起来像还有希望，实际是推车）。
            // 这一格说人话：还剩多少能跑 → 快没电 → 没电。
            sub = when {
                !range.isNaN() && range < 1f -> "电量已耗尽 · 请充电"
                !range.isNaN() && range < 5f -> "只剩一点点 · 尽快充电"
                !range.isNaN() && !HudState.avgKmh.isNaN() ->
                    "~${pct(range)}km · 均速 ${pct(HudState.avgKmh)}"
                !range.isNaN() -> "~${pct(range)}km · 长按清零"
                !HudState.avgKmh.isNaN() -> "本次里程 · 均速 ${pct(HudState.avgKmh)} · 长按清零"
                else -> "本次里程 · 长按清零"
            },
            subColor = if (range.isNaN()) DIM else rangeColor(range)
        )
        }

        // ---- 整车累计（v1.0.25）----
        // 用户提的："把 11244 也显示在本次里程下面，行驶时两个数一起涨，方便和车上仪表校准"。
        // 这个主意是对的：车上的里程是轮速计的，长期比 GPS 准；两个数并排跑，
        // 一眼就能看出"App 这次多算/少算了多少"，比事后凭记忆对账可靠得多。
        // 注意：整车累计 = 仪表基准 + App 累计 + 本次，所以它跟着 GPS 里程涨，
        // 和车上仪表会缓慢漂移 —— 这正是它存在的意义（漂了就长按这里进设置改基准）。
        val odo = com.hud.delivery.HudState.odoKm
        // v1.0.48：**固定 26dp 槽位，永远占位**（没有数值时留空）。
        // 之前是 `if (!odo.isNaN())` 条件渲染：这一行在/不在，会改掉下面 weight(1f) 的高度，
        // 三个大数字连字号都会跟着重算 —— 这就是"刹车一下整页跳一下"的根源。
        Box(
            modifier = Modifier.fillMaxWidth().height(26.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!odo.isNaN()) {
                val odoText = buildAnnotatedString {
                    withStyle(SpanStyle(color = DIM, fontSize = 12.sp)) { append("总里程 ") }
                    withStyle(
                        SpanStyle(
                            color = Color(0xFFD6E2EC),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    ) { append(String.format(java.util.Locale.US, "%.1f", odo)) }
                    withStyle(SpanStyle(color = DIM, fontSize = 12.sp)) { append(" km") }
                }
                BasicText(
                    text = odoText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { onOdo() }) },
                    maxLines = 1,
                    style = TextStyle(
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                )
            }
        }

        // ---- 导航行：固定 20dp 槽位（没导航也占位，不塌陷）----
        // v1.0.48：用户反馈"没有真正和高德联动"。这一行现在做两件事：
        //  1) 槽位固定 —— 导航文案来/走不再把上面三个大数字顶上去；
        //  2) 没拿到通知使用权时，直接把原因写在这一行，而不是默默什么都不显示。
        val nav = com.hud.delivery.HudState.navLine
        val navFresh = nav.isNotEmpty() &&
                System.currentTimeMillis() - com.hud.delivery.HudState.navTs < com.hud.delivery.NavBridge.STALE_MS
        val navAllowed = com.hud.delivery.HudState.navPerm
        Box(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (navFresh) {
                BasicText(
                    text = nav,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = accent(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                )
            } else if (!navAllowed && System.currentTimeMillis() <= com.hud.delivery.HudState.navHintUntil) {
                // v1.0.48：只在启动后 90 秒内提示一次"怎么开"。
                // 一直挂着这行会变成用户说的"按钮上面那行小字太长了"，反而添乱。
                BasicText(
                    text = "导航联动未开 · 设置里打开通知使用权",
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = DIM, fontSize = 11.sp)
                )
            }
        }

        // ---- 底部信息区：**固定 50dp**（2 行 × 16dp + 2dp 间距 + 余量）----
        // v1.0.48：这一块是"刹车一下整页跳一下"的真凶。
        // 之前状态行 / 定位警告 / 控制器状态位 / 导航行全是"有内容才渲染"，
        // 它们一出现就吃掉高度，上面 weight(1f) 的数字区被压缩，
        // 而 MetricCell 的字号又按格子高度算（vSp = maxHeight × 0.42），
        // 于是不仅位置变，字号也变 —— 看起来就是整页抖一下。
        // 现在每一行都固定占位，内容有没有都不改高度。
        val voiceWarn = if (HudState.voiceOn && !HudState.voiceReady) "语音没就绪" else ""
        val phoneWarn = if (HudState.phonePct in 1..25) "手机 ${HudState.phonePct}%" else ""
        val srcLine = when {
            // v1.0.12：定位权限/开关的问题排第一 —— 里程时速全指它
            HudState.gpsWarn.isNotEmpty() -> HudState.gpsWarn
            mapSuspect -> "⚠ 电压不动 · 映射可能不对"
            HudState.lowVoltWarn.isNotEmpty() -> HudState.lowVoltWarn
            !HudState.voltage.isNaN() -> ""
            src == HudState.SRC_FROZEN -> "已断开 · 上次读数" + freezeAgeText()
            src == HudState.SRC_EST -> anchorAge().let {
                if (it.isEmpty()) "未连接 · 电压为推算值" else "未连接 · 按$it 读数推算"
            }
            src == HudState.SRC_MANUAL -> anchorAge().let {
                if (it.isEmpty()) "电压是手动填的" else "电压是手动填的（$it）"
            }
            else -> "正在等控制器数据…"
        }
        // 第一行只说"电压/电量是哪来的"，第二行说"要你动手或要你知道的"。
        // 断档提示放到第二行 —— 它带着"长按总里程对齐"这个动作，跟报警是一类，不该被来源那句挤掉。
        val infoLine1 = srcLine
        // v1.0.52：**定位真的停了才提示**，而且只在"后台定位没给（而且有救）"时提示。
        // 上一版让用户"去日志里搜 fgs type=" 才知道有没有后台取到点 —— 太绕。
        // 这里直接把结果摆出来：连着车、权限是"使用时"、定位点 25 秒没有 → 这行就是那句话。
        // 一旦在设置里改成「始终允许」，这行永远不会再出现（不添乱）。
        val gpsDead = com.hud.delivery.HudState.connState == com.hud.delivery.HudState.CONNECTED
                && com.hud.delivery.HudState.gpsOk
                && !com.hud.delivery.HudState.bgLocOk
                && (com.hud.delivery.HudState.gpsFixAgeS < 0L
                || com.hud.delivery.HudState.gpsFixAgeS > 25L)
        val infoLine2 = buildList {
            if (gpsDead) add("定位停了 · 设置里改「始终允许」")
            if (HudState.gapWarn.isNotEmpty()) add(HudState.gapWarn)
            if (HudState.faultsText.isNotEmpty()) add("控制器：" + HudState.faultsText)
            if (!HudState.gpsOk) add("定位未开启")
            if (voiceWarn.isNotEmpty()) add(voiceWarn)
            if (phoneWarn.isNotEmpty()) add(phoneWarn)
        }.joinToString(" · ")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { showDiag = !showDiag }) }
        ) {
            Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
                if (infoLine1.isNotEmpty()) {
                    BasicText(
                        text = infoLine1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (mapSuspect || HudState.gpsWarn.isNotEmpty()) AMBER else DIM,
                            fontSize = 11.sp
                        )
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
                if (infoLine2.isNotEmpty()) {
                    BasicText(
                        text = infoLine2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = AMBER, fontSize = 11.sp)
                    )
                }
            }
        }

        // 帧开头几个字节 + 内部参数：只在长按后出现。
        // 这一段故意允许它撑高布局 —— 它是"你自己要看"的调试信息，不骑车时才看。
        if (showDiag) {
            val headHex = HudState.lastFrameHex
            BasicText(
                text = buildString {
                    append("diag · src=").append(src)
                    append(" vPerKm=").append(String.format(Locale.US, "%.2f", HudState.vPerKmUsed))
                    if (!HudState.odoKm.isNaN()) {
                        append(" 整车=").append(String.format(Locale.US, "%.0fkm", HudState.odoKm))
                    }
                    append(" Hz=").append(String.format(Locale.US, "%.1f", HudState.rateHz))
                    if (!HudState.avgKmh.isNaN()) {
                        append(" avs=").append(String.format(Locale.US, "%.1f", HudState.avgKmh))
                        append(" mxs=").append(String.format(Locale.US, "%.1f", HudState.maxKmh))
                        append(" t=").append(HudState.rideSec / 60).append("min")
                    }
                    if (!HudState.whPerKm.isNaN()) {
                        append(" wh/km=").append(String.format(Locale.US, "%.1f", HudState.whPerKm))
                    }
                    if (note.isNotEmpty()) append(" · ").append(note)
                    if (headHex.isNotEmpty()) {
                        append("\n帧 ").append(headHex.take(29))
                        append(" …(").append(HudState.lastFrameLen).append("B)")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(color = DIM, fontSize = 10.sp)
            )
        }
        } // 数据区结束（防烧屏位移只作用到这里）

        // ---- 底部操作条 ----
        // 骑车时戴手套、手在抖，Material 规范的最小可点尺寸 48dp 都不够用；
        // 这里每个按钮 72dp 高、三等分整宽，靠 Fitts 定律吃"贴边 + 大目标"的便宜。
        // v1.0.23：64 → 72dp，并和手势区留出更大间距（之前贴着底部，单手够不到）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BAR)
        ) {
            BarButton("电压", Modifier.weight(1f).fillMaxHeight(), onManual)
            BarDivider()
            BarButton("标记", Modifier.weight(1f).fillMaxHeight(), onMark)
            BarDivider()
            BarButton("设置", Modifier.weight(1f).fillMaxHeight(), onSettings)
        }
    }
}

/**
 * 三个大数字共用同一个"数据格"：同字号、同基线、同副标题槽位高度。
 *
 * 抄的是 Garmin 码表多字段页的做法（每格同字号、同一基线，靠格子而不是靠字号表达层次），
 * 以及 Google Design for Driving 的排版建议（4dp 网格、只用主/次两档字号）。
 * 副标题固定 18dp 槽位：没有内容也不塌陷，三个数字才不会一个高一个低。
 */
/**
 * v1.0.64：A 方案电量格 —— 电池外框（带正极凸起）+ 内部 10 段格子 + 右侧大百分比。
 * 样板见 mockup/battery-panel-sample.html（用户选定 A）。已充段与外框同色，空段暗灰。
 */
@Composable
private fun SocPanelCell(
    modifier: Modifier,
    pct: Float,
    sub: String,
    subColor: Color
) {
    val color = socColor(pct)
    val frac = if (pct.isNaN()) 0f else (pct / 100f).coerceIn(0f, 1f)
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 电池壳：里面装一条横向进度填充，百分比数字居中叠在上面（手机电池样式）
                // v1.0.66：去掉 weight(1f) 满宽拉伸，改成固定紧凑宽度，和左右电压/里程大数字块对齐，
                // 不再把格子整条撑满。
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(48.dp)
                        .border(2.dp, color, RoundedCornerShape(12.dp))
                        .padding(5.dp)
                ) {
                    // 空槽底 + 左起填充条
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BAR)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(frac)
                                .background(color)
                        )
                    }
                    BasicText(
                        text = if (pct.isNaN()) "--" else String.format(Locale.US, "%.0f%%", pct),
                        modifier = Modifier.align(Alignment.Center),
                        style = TextStyle(
                            color = TEXT,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
                // 正极小凸起
                Spacer(Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .background(color, RoundedCornerShape(0.dp, 3.dp, 3.dp, 0.dp))
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                if (sub.isNotEmpty()) {
                    BasicText(
                        text = sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = subColor, fontSize = 12.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(
    modifier: Modifier,
    value: String,
    unit: String,
    color: Color,
    sub: String,
    subColor: Color
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // v1.0.3：字号跟着格子高度走，不再死写 80sp。
        // 状态栏恢复显示、分屏、系统大字体都会压掉格子高度，固定字号会被裁掉半个数字；
        // Garmin 码表的做法也是"格子多大、字多大"，而不是让字去挤格子。
        val vSp = (maxHeight.value * 0.42f).coerceIn(44f, 80f)
        val uSp = (vSp * 0.33f).coerceIn(16f, 26f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(
                    text = value,
                    style = TextStyle(
                        color = color,
                        fontSize = vSp.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                )
                BasicText(
                    text = unit,
                    modifier = Modifier.padding(start = 4.dp, bottom = (uSp * 0.38f).dp),
                    style = TextStyle(color = color, fontSize = uSp.sp, fontWeight = FontWeight.Bold)
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                if (sub.isNotEmpty()) {
                    BasicText(
                        text = sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = subColor, fontSize = 12.sp)
                    )
                }
            }
        }
    }
}

/** 底部条上的一个按钮：整块可点，不是只有文字可点 */@Composable
private fun BarButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = TEXT, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        )
    }
}

/** 两条竖细线把三个按钮分开，避免"到底点的是哪个" */
@Composable
private fun BarDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 14.dp)
            .background(Color(0xFF1C232B))
    )
}
