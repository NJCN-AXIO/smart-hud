package com.hud.delivery.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * v1.0.49：把"系统灰药丸 Toast"换成和界面同一套皮肤的提示条。
 *
 * 为什么不用 Toast：Android 的 Toast 是系统绘制的，白字灰底、圆角也由 ROM 决定，
 * 在满屏深色的 HUD 上弹一下特别突兀（用户实测反馈"弹窗跟整体不统一"）。
 * 这里直接把一个深色小条贴在屏幕下方（三个按钮上方），淡入淡出，样式和设置面板一致。
 */
public final class HudToast {

    private static final Handler H = new Handler(Looper.getMainLooper());
    /** 弱引用：提示条挂在 Activity 的 content 上，别用静态强引用把 Activity 拖住（StaticFieldLeak） */
    private static java.lang.ref.WeakReference<View> current = new java.lang.ref.WeakReference<>(null);

    private HudToast() {
    }

    public static void show(final Activity act, final String msg) {
        if (act == null || msg == null || act.isFinishing()) return;
        act.runOnUiThread(() -> {
            try {
                ViewGroup root = act.findViewById(android.R.id.content);
                if (root == null) return;
                View prev = current.get();
                if (prev != null && prev.getParent() == root) {
                    root.removeView(prev);
                }
                TextView tv = new TextView(act);
                tv.setText(msg);
                tv.setTextColor(Color.parseColor("#E8F0F7"));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                tv.setGravity(Gravity.CENTER);
                int padH = dp(act, 16), padV = dp(act, 9);
                tv.setPadding(padH, padV, padH + dp(act, 2), padV);   // 右边多 2dp：中文末尾不贴边
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#E6141A21"));           // 卡片的深色背板（带一点透明）
                bg.setCornerRadius(dp(act, 22));
                bg.setStroke(dp(act, 1), Color.parseColor("#334150"));
                tv.setBackground(bg);
                tv.setAlpha(0f);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = dp(act, 92);                        // 落在 72dp 三按钮上方
                root.addView(tv, lp);
                current = new java.lang.ref.WeakReference<>(tv);
                tv.animate().alpha(1f).setDuration(140).start();
                H.postDelayed(() -> {
                    if (current.get() != tv) return;
                    tv.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                        if (tv.getParent() instanceof ViewGroup) {
                            ((ViewGroup) tv.getParent()).removeView(tv);
                        }
                        if (current.get() == tv) current = new java.lang.ref.WeakReference<>(null);
                    }).start();
                }, 1600L);
            } catch (Throwable ignored) {
            }
        });
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
