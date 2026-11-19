package com.hud.delivery;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * v1.0.49：进程级入口，只做两件事 —— 初始化日志、装全局崩溃钩子。
 *
 * 为什么需要它：以前的崩溃钩子装在 MainActivity.onCreate 里，
 * 所以"服务自己在后台崩""开机自启起来就崩"这两种情况一条堆栈都留不下，
 * 用户只能看到闪退。现在钩子在进程一启动就装上，两种路径都能抓到。
 */
public class HudApp extends Application {

    private static volatile boolean installed = false;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            RawLog.init(this);
        } catch (Throwable t) {
            Log.w("HudApp", "log init failed", t);
        }
        install(this);
    }

    /** 全局崩溃落盘（幂等：重复调用不会叠加多个 handler） */
    public static void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                RawLog.init(ctx);
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String s = sw.toString();
                RawLog.sys(System.currentTimeMillis(),
                        "CRASH thread=" + t.getName() + " " +
                                (s.length() > 1600 ? s.substring(0, 1600) : s));
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
