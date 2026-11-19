package com.hud.delivery.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Java 侧入口：Compose 的 setContent 是 Kotlin 扩展，Java 不好直接调，这里包一层。 */
interface HudCallbacks {
    fun onManual()
    fun onMark()
    fun onSettings()
    fun onResetTrip()
    fun onToggleUi()
    /** 顶部状态行点一下：把服务重新拉起来（演示模式没数据时的救命按钮） */
    fun onRetry()
    /** v1.0.5：顶部双击切亮度档（自动 / 常亮 / 常暗） */
    fun onCycleBright()
    /** v1.0.25：长按"整车 xxx km"→ 输入仪表读数，一键对齐总里程 */
    fun onOdo()
    /** v1.0.33：左右滑动切换极简模式（时速 + 电量） */
    fun onToggleLite()
}

object ComposeHud {
    @JvmStatic
    fun attach(activity: ComponentActivity, cb: HudCallbacks) {
        activity.setContent {
            HudScreen(
                onManual = { cb.onManual() },
                onMark = { cb.onMark() },
                onSettings = { cb.onSettings() },
                onResetTrip = { cb.onResetTrip() },
                onToggleUi = { cb.onToggleUi() },
                onRetry = { cb.onRetry() },
                onCycleBright = { cb.onCycleBright() },
                onOdo = { cb.onOdo() },
                onToggleLite = { cb.onToggleLite() },
            )
        }
    }
}
