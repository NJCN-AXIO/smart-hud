package com.hud.delivery;

import android.app.Notification;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

/**
 * 小米「焦点通知 / 超级岛」客户端接入（HyperOS 2 / 3）。
 *
 * 依据是小米官方《超级岛开发指南》的**客户端实现**方式：
 *  1. 照常发一条原生常驻通知（前台服务那条）；
 *  2. 在 `notification.extras` 里塞一个 key = `miui.focus.param` 的 JSON（`param_v2`）；
 *  3. 系统若支持且本 App 有焦点通知权限，会把它显示成状态栏焦点通知/岛；否则**就是普通通知**，
 *     这一点官方写明由 `filterWhenNoPermission`（默认 false）决定 —— 所以这里可以放心塞。
 *
 * 两个查询接口（官方文档「五、查询接口」）：
 *  - `Settings.System.notification_focus_protocol`：1=OS1、2=OS2、3=OS3（只有 OS3 才有"岛"）
 *  - `content://miui.statusbar.notification.public` 的 `canShowFocus`：本应用有没有焦点通知权限
 *
 * 权限是白名单制（要发邮件给 mipush-permission@xiaomi.com 申请）。没有权限时本类只写日志，
 * 不改变通知的常规行为。
 *
 * 红线和这里无关：只是给**系统通知**加 extras，一个字节都不往控制器写。
 */
public final class FocusIsland {

    private static final String TAG = "HudFocus";
    public static final String KEY_PARAM = "miui.focus.param";
    private static final Uri MIUI_FOCUS_URI =
            Uri.parse("content://miui.statusbar.notification.public");

    private FocusIsland() {
    }

    /** 1=OS1、2=OS2、3=OS3（超级岛），0=系统不支持 */
    public static int protocol(Context ctx) {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), "notification_focus_protocol", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 本应用是否被系统授予了焦点通知权限（白名单） */
    public static boolean hasPermission(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Bundle args = new Bundle();
            args.putString("package", ctx.getPackageName());
            Bundle b = cr.call(MIUI_FOCUS_URI, "canShowFocus", null, args);
            return b != null && b.getBoolean("canShowFocus", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把"岛/焦点通知"参数挂到通知上。
     *
     * @param head   大标题（如 "电量 65%"）
     * @param line   一行内容（如 "72.3V · 12.4km · 还能 43km"）
     * @param ticker 状态栏那一行（尽量短，如 "72.3V · 65%"）
     */
    public static void attach(Context ctx, Notification n, String head, String line, String ticker) {
        int ver = protocol(ctx);
        if (ver <= 0 || n == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            JSONObject v2 = new JSONObject();
            root.put("param_v2", v2);

            v2.put("protocol", 1);
            v2.put("business", "ebike");
            v2.put("updatable", true);      // 持续性通知：同一通知号更新
            v2.put("enableFloat", false);   // 更新时不要每次都弹开，骑车时挡视线
            v2.put("ticker", ticker);       // OS2 状态栏焦点那一行
            v2.put("aodTitle", head);       // 息屏显示

            // 焦点通知本体（OS2 走这段）
            JSONObject base = new JSONObject();
            base.put("title", head);
            base.put("content", line);
            base.put("colorTitle", "#3ADE71");
            base.put("type", 2);
            v2.put("baseInfo", base);

            // 超级岛（只有 OS3 有）。字段按官方模板库的最小集，纯文本不带图：
            // 图要 miui.focus.pic_* 的 Icon 资源，这里没有合适的图，宁可不传。
            if (ver >= 3) {
                JSONObject island = new JSONObject();
                island.put("islandProperty", 1);
                JSONObject left = new JSONObject();
                left.put("type", 1);
                JSONObject text = new JSONObject();
                text.put("frontTitle", "电量");
                text.put("title", head);
                text.put("content", line);
                text.put("useHighLight", true);
                left.put("textInfo", text);
                JSONObject big = new JSONObject();
                big.put("imageTextInfoLeft", left);
                island.put("bigIslandArea", big);
                JSONObject small = new JSONObject();
                small.put("textInfo", text);
                island.put("smallIslandArea", small);
                v2.put("param_island", island);
            }

            n.extras.putString(KEY_PARAM, root.toString());
        } catch (Exception e) {
            Log.w(TAG, "attach focus param failed", e);
        }
    }

    /** 诊断用：把"系统版本 / 有没有权限"拼成一句，写进日志 */
    public static String describe(Context ctx) {
        int ver = protocol(ctx);
        boolean perm = hasPermission(ctx);
        String name = ver >= 3 ? "OS3(超级岛)" : ver == 2 ? "OS2(焦点通知)" : ver == 1 ? "OS1(焦点通知)" : "不支持";
        return name + " perm=" + perm;
    }
}
