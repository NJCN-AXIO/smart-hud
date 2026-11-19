package com.hud.delivery;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 把「高德 / 百度」导航通知里的那行字借过来，显示在 HUD 底部（v1.0.4）。
 *
 * 为什么用这个办法：高德没有开放"实时车速/剩余里程"给第三方 App 的公开接口
 * （要用它的导航 SDK、还要申请 key，等于自己再做一套导航）。但骑手开着导航时，
 * 系统通知栏里本来就有「剩余 3.2 公里 · 12 分钟 · 预计 21:30 到达」，
 * 用通知使用权（NotificationListenerService）把这行字读出来就够用了 ——
 * 不联网、不申请 key、不碰控制器。
 *
 * 拿不到（没授权 / 没开导航 / 文案变了）就什么都不显示，不影响任何主流程。
 */
public class NavBridge extends NotificationListenerService {

    private static final String TAG = "HudNav";
    public static final String PKG_GAODE = "com.autonavi.minimap";
    public static final String PKG_BAIDU = "com.baidu.BaiduMap";
    /** 文案超过这么久没更新就不再显示（导航早结束了，别挂着旧路线） */
    public static final long STALE_MS = 15L * 60_000L;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            String pkg = sbn.getPackageName();
            String label = labelOf(pkg);
            if (label == null) return;
            Bundle ex = sbn.getNotification().extras;
            if (ex == null) return;

            String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            String txt = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            String sub = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
            String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            // 优先大文本（高德展开态那行"剩余 xx 公里"通常在这里）
            String body = !big.isEmpty() ? big : (!txt.isEmpty() ? txt : sub);
            // v1.0.48：有些版本把"剩余 3.2 公里"放在标题里，正文只有"点击查看"
            if (!looksLikeNav(body) && looksLikeNav(title)) body = title;
            if (body.isEmpty()) {
                return;
            }
            // v1.0.48：诊断日志 —— 只有这几家导航包才记，且 5 秒最多一条。
            // 用户反馈"没有真正和高德联动"，有这行就能判断到底是"没收到通知"还是"收到了没认出来"。
            long now = System.currentTimeMillis();
            if (now - lastLogMs > 5_000L) {
                lastLogMs = now;
                RawLog.sys(now, "nav " + pkg + " t=" + shorten(title, 18) + " b=" + shorten(body, 30));
            }
            // v1.0.50：**先判"结束"，再判"像不像导航"**。
            // 旧版只看正文有没有"结束/到达"去清、且只在 `!looksLikeNav` 时清，
            // 于是"已到达，本次行程 3.2 公里"这种带数字的结束语既不会被清，
            // 还会被当成主文案挂上去 —— 到地方了屏幕还显示"剩余 3.2km"。
            if (body.contains("已到达") || body.contains("导航结束") || body.contains("结束导航")
                    || body.contains("已取消") || body.contains("已退出导航")) {
                HudState.clearNav(pkg);
                primaryTs = 0L;
                lastKey = null;
                return;
            }
            // 只认像"剩余/距离/公里/分钟"的那种文案，避免把"高德地图已更新"之类的推送挂上来
            if (!looksLikeNav(body)) {
                // 导航刚结束时会推一条"已结束/到达"，这时把手上的旧文案清掉
                if (body.contains("结束") || body.contains("到达")) {
                    HudState.clearNav(pkg);
                    // v1.0.50：导航结束就放掉"主文案保护期"。
                    // 否则下一单导航开始后的前 90 秒，转向提示全被这个旧时间戳挡掉。
                    primaryTs = 0L;
                    lastKey = null;
                }
                return;
            }

            // v1.0.17：高德导航时**会同时挂着多条通知**（转向提示"距路口 200 米右转"、
            // 违章提醒、测速提醒…）。旧版"最后一条匹配就覆盖"，于是底部那行字会在
            // "剩余 3.2 公里" 和 "200 米右转" 之间来回跳，看着像乱闪。
            // 现在分成主文案（剩余/预计/到达）和辅助文案（转向提示）两级：
            // 只要 90 秒内见过主文案，辅助文案就不许覆盖它。
            boolean primary = isPrimary(body) || isPrimary(title);
            if (!primary && now - primaryTs < PRIMARY_HOLD_MS) {
                return;                     // 有主文案在，别用转向提示把它顶掉
            }
            // v1.0.47：不是主文案，也不是"转向提示"，就别往 HUD 上挂。
            // 高德/百度会推一堆"前方 500 米有测速""您已超速 12 次"的通知，
            // 它们同样带数字带"米"，以前会被当成导航那行显示出来。
            if (!primary && !looksLikeTurn(body)) {
                return;
            }
            if (primary) primaryTs = now;

            // v1.0.48：压成一行能读的短文案（右下角只有 20dp 高、13sp）。
            // 原文常常是「剩余 3.2 公里 · 12 分钟 · 预计 21:30 到达」——直接摆上去会被省略号吃掉一半，
            // 现在统一成「高德 · 剩余 3.2km · 12分」。
            String line = compact(label, body);
            if (line.isEmpty()) line = label + " · " + shorten(body, 24);
            HudState.setNav(pkg, line.replace('\n', ' '));
            lastKey = sbn.getKey();       // v1.0.49：记住"这行字是哪条通知来的"
            HudState.navPerm = true;      // 能收到通知，说明权限确实是开的
            HudState.touch();
        } catch (Exception e) {
            Log.w(TAG, "read nav notification failed", e);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        // 有这一行，日志就能证明"通知使用权真的连上了"（没有这行 = 系统没给权限/服务被停）
        RawLog.sys(System.currentTimeMillis(), "nav listener connected");
        HudState.navPerm = true;
        HudState.touch();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        RawLog.sys(System.currentTimeMillis(), "nav listener disconnected");
        HudState.navPerm = false;
        HudState.clearNav(null);
    }

    /** 诊断日志节流 */
    private static volatile long lastLogMs = 0L;

    /** 把长文案压成一行：距离 + 时间，读得完也看得清 */
    private static String compact(String label, String body) {
        String kmDist = null;
        java.util.regex.Matcher km = PAT_KM.matcher(body);
        if (km.find()) kmDist = trimNum(km.group(1)) + "km";
        String mDist = null;
        java.util.regex.Matcher m = PAT_M.matcher(body);
        if (m.find()) mDist = m.group(1) + "m";
        String dist;
        // v1.0.50：一条通知里同时出现"剩余 3.2 公里"和"200 米后右转"时，
        // 旧版永远取公里 —— 转向提示就变成"前方 3.2km"，那是目的地距离，会把人带错。
        // 主文案取公里，转向提示取米。
        boolean primary = isPrimary(body);
        if (primary) dist = kmDist != null ? kmDist : mDist;
        else dist = mDist != null ? mDist : kmDist;
        String min = null;
        java.util.regex.Matcher mi = PAT_MIN.matcher(body);
        if (mi.find()) min = mi.group(1) + "分";
        if (dist == null && min == null) return "";
        StringBuilder sb = new StringBuilder(label);
        // v1.0.49：转向提示不是"剩余"，写"剩余 200m"会把人带错方向（那是到路口的距离）。
        // 只有主文案（剩余/预计/到达）才配"剩余"两个字。
        if (dist != null) sb.append(primary ? " · 剩余 " : " · 前方 ").append(dist);
        if (min != null) sb.append(" · ").append(min);
        return sb.toString();
    }

    private static String trimNum(String s) {
        if (s == null) return "";
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').trim();
        return one.length() <= n ? one : one.substring(0, Math.max(0, n - 1)) + "…";
    }

    private static final java.util.regex.Pattern PAT_KM =
            java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:公里|km|KM)");
    private static final java.util.regex.Pattern PAT_M =
            java.util.regex.Pattern.compile("(\\d+)\\s*米");
    /** v1.0.50：高德有时写"12分"不是"12分钟"，两种都要认 */
    private static final java.util.regex.Pattern PAT_MIN =
            java.util.regex.Pattern.compile("(\\d+)\\s*分(?:钟)?");

    /** 最近一次"主文案"出现的时刻（剩余/预计/到达） */
    private static volatile long primaryTs = 0L;
    /** 主文案的"保护期"：这段时间内转向提示不许覆盖 */
    private static final long PRIMARY_HOLD_MS = 90_000L;

    private static boolean isPrimary(String s) {
        return s.contains("剩余") || s.contains("预计") || s.contains("到达")
                || s.contains("全程") || s.contains("总里程");
    }

    /** 转向提示的固定词：只有这些才算"导航辅助文案"，测速/违章/广告推送不算 */
    private static boolean looksLikeTurn(String s) {
        return s.contains("左转") || s.contains("右转") || s.contains("直行")
                || s.contains("掉头") || s.contains("左前方") || s.contains("右前方")
                || s.contains("路口") || s.contains("变道") || s.contains("转弯")
                || s.contains("靠左") || s.contains("靠右");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        // v1.0.17：高德刷新常驻通知时是"先撤再发"（remove + notify），
        // 旧版无条件 clearNav → 底部那行字每刷新一次就闪一下空白。
        // 刚更新过（800ms 内）就说明这只是刷新周期，不清。
        if (System.currentTimeMillis() - HudState.navTs < 800L) return;
        // v1.0.49：高德导航时同时挂着多条通知（常驻"剩余 3.2 公里" + 转向"200 米右转"）。
        // 旧版按"包名"清 → 转向那条被撤掉时，把常驻那条的"剩余里程"也一起清了，
        // 屏幕上的导航行就凭空消失几秒。现在只清"我们正在显示的那条通知"。
        if (lastKey != null && !lastKey.equals(sbn.getKey())) return;
        lastKey = null;
        HudState.clearNav(pkg);
    }

    /** v1.0.49：当前显示的那行导航文案来自哪条通知（用来判断"撤掉的到底是不是它"） */
    private static volatile String lastKey = null;

    private static String labelOf(String pkg) {
        if (PKG_GAODE.equals(pkg)) return "高德";
        if (PKG_BAIDU.equals(pkg)) return "百度";
        // v1.0.48：腾讯地图（骑手群里用得也不少），文案格式和高德一致
        if ("com.tencent.map".equals(pkg)) return "腾讯";
        return null;
    }

    private static boolean looksLikeNav(String s) {
        // v1.0.14：要求"有单位 + 有数字"两条同时成立。
        // 旧版只看关键词，"高德地图已更新到 8.2 公里外的版本" / 广告推送里的"分钟"都会被当成导航，
        // 屏幕上就挂一行莫名其妙的字。真实的导航文案一定是"剩余 3.2 公里 · 12 分钟"这种带数字的。
        boolean unit = s.contains("剩余") || s.contains("公里") || s.contains("km")
                || s.contains("KM") || s.contains("分钟") || s.contains("米") || s.contains("到达");
        boolean hasDigit = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) { hasDigit = true; break; }
        }
        return unit && hasDigit;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    // ---------------- 授权状态（给设置页显示用） ----------------

    /** 本应用的"通知使用权"是否已开 */
    public static boolean enabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) return false;
            ComponentName me = new ComponentName(ctx, NavBridge.class);
            return flat.contains(me.flattenToString())
                    || flat.contains(ctx.getPackageName() + "/" + NavBridge.class.getName())
                    || flat.contains(ctx.getPackageName() + "/.NavBridge");
        } catch (Exception e) {
            return false;
        }
    }
}
