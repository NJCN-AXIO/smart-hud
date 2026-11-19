package com.hud.delivery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** 手机开机后自动把 HUD 拉起来，实现“上车就显示、不用每次点开 App”。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        try {
            // v1.0.49：先初始化日志再起服务 —— 旧版是先 startForegroundService 再 RawLog.init，
            // 万一系统抛 ForegroundServiceStartNotAllowedException（红米自启白名单没给、Android 14 收紧），
            // 异常只会进 Log.w，日志文件里一个字都没有，下次复盘只能猜。
            RawLog.init(context);
            Intent svc = new Intent(context, BleService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            RawLog.sys(System.currentTimeMillis(), "boot -> service start");
        } catch (Exception e) {
            Log.w("HudBoot", "start after boot failed", e);
            try {
                RawLog.sys(System.currentTimeMillis(),
                        "boot -> service start FAILED: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } catch (Throwable ignored) {
            }
        }
    }
}
