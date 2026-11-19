package com.hud.delivery;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * v1.0.23：三个面板（电压 / 标记 / 设置）统一的外观。
 *
 * 之前三个弹窗是系统 AlertDialog + 一堆裸控件：标题一堆小字、按钮挤在底部、
 * 说明文字和设置项混在一起，看着像调试工具而不是仪表。
 *
 * 现在按成熟项目的做法（Material 3 深色 BottomSheet / DigiHUD / OpenTracks 的设置页）：
 *  - 底部升起的圆角面板，顶部有抓手和标题，内容区自己滚
 *  - 内容按"卡片分组"，每组一个 12sp 小标题 + 若干行
 *  - 每行 = 左侧标签（+ 可选小字说明），右侧控件
 *  - 主操作是通栏实心圆角按钮，次操作是描边按钮，触控高度 ≥52dp
 *  - 所有文字颜色/圆角/间距都在这里集中定义，三个面板不会各写一套
 */
final class PanelUi {

    static final int BG = 0xFF0B0F13;
    static final int CARD = 0xFF141A21;
    static final int FIELD = 0xFF1A232C;
    static final int LINE = 0xFF202A34;
    static final int TXT = 0xFFE9EEF4;
    static final int SUB = 0xFF8E9BA8;
    static final int ACC = 0xFF35E07A;
    static final int ACC_INK = 0xFF06210F;

    final Activity a;
    final Dialog dialog;
    final LinearLayout content;
    private final LinearLayout footer;

    private PanelUi(Activity a, String title, String subtitle, boolean withFooter,
                    boolean centered, float fillFrac) {
        this.a = a;
        // v1.0.49：用深色对话框主题（res/values/styles.xml 的 Theme.Hud.Dialog），
        // 别再继承 Activity 的浅色 Material 主题 —— 输入框光标/选中手柄/粘贴菜单才不会白底。
        dialog = new Dialog(a, R.style.Theme_Hud_Dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        if (centered) {
            // v1.0.39：居中卡片四角都圆；底部面板只圆上两角（像 BottomSheet）
            bg.setCornerRadius(dp(a, 22));
        } else {
            bg.setCornerRadii(new float[]{dp(a, 22), dp(a, 22), dp(a, 22), dp(a, 22), 0, 0, 0, 0});
        }
        root.setBackground(bg);

        // 抓手
        View grab = new View(a);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0xFF2C3742);
        gb.setCornerRadius(dp(a, 3));
        grab.setBackground(gb);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(a, 40), dp(a, 4));
        glp.topMargin = dp(a, 8);
        glp.bottomMargin = dp(a, 2);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(grab, glp);

        // 标题行
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(a, 20), dp(a, 10), dp(a, 12), dp(a, 10));
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView tvTitle = new TextView(a);
        tvTitle.setText(title);
        tvTitle.setTextColor(TXT);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
        titles.addView(tvTitle);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(a);
            tvSub.setText(subtitle);
            tvSub.setTextColor(SUB);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvSub.setPadding(0, dp(a, 3), 0, 0);
            titles.addView(tvSub);
        }
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(a);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setTextColor(SUB);
        close.setBackground(ghostBg(a));
        close.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 40));
        head.addView(close, clp);
        root.addView(head);

        // 分隔线
        View sep = new View(a);
        sep.setBackgroundColor(0xFF161E26);
        root.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1)));

        // 内容区
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 16));
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 居中卡片：默认高度跟着内容走；给了 fillFrac 就撑到屏幕的那个比例（长列表用）
        boolean scrollFill = !centered || fillFrac > 0f;
        root.addView(sv, scrollFill
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        footer = new LinearLayout(a);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 16));
        footer.setBackgroundColor(0xFF0F1419);
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        footer.setVisibility(withFooter ? View.VISIBLE : View.GONE);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.62f;
            if (centered) {
                // 居中小卡片：宽度占屏 92%、高度随内容（长列表给出 fillFrac 撑固定高度，内部滚动）
                lp.width = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.92f);
                lp.height = fillFrac > 0f
                        ? (int) (a.getResources().getDisplayMetrics().heightPixels * fillFrac)
                        : WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.CENTER;
            } else {
                lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                lp.height = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.88f);
                lp.gravity = Gravity.BOTTOM;
            }
            w.setAttributes(lp);
        }
    }

    static PanelUi open(Activity a, String title, String subtitle) {
        return new PanelUi(a, title, subtitle, true, false, 0f);
    }

    /** 不要底部按钮区的面板（内容自带操作） */
    static PanelUi openBare(Activity a, String title, String subtitle) {
        return new PanelUi(a, title, subtitle, false, false, 0f);
    }

    /**
     * v1.0.39：居中小卡片（带底部按钮）。
     * 用来替掉剩下那几个系统 AlertDialog —— 首次引导、本次骑行这种"读一段话 + 点按钮"的场景。
     * 之前它们走原生 AlertDialog：白底/灰底、圆角方角、按钮是系统蓝字，和深色 HUD 完全两套皮。
     */
    static PanelUi card(Activity a, String title, String subtitle) {
        return new PanelUi(a, title, subtitle, true, true, 0f);
    }

    /** 居中小卡片，不要底部按钮区（自己往内容里塞控件） */
    static PanelUi cardBare(Activity a, String title, String subtitle) {
        return new PanelUi(a, title, subtitle, false, true, 0f);
    }

    /** 居中小卡片，撑到屏幕高度的 fillFrac（0–1），内部滚动 —— 设备列表这种不定长的用 */
    static PanelUi cardTall(Activity a, String title, String subtitle, float fillFrac) {
        return new PanelUi(a, title, subtitle, false, true, fillFrac);
    }

    /** 一段说明文字（15sp、行距 1.3、正文色），直接塞进内容区 */
    TextView body(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(TXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(a, 4), 1.25f);
        t.setPadding(0, dp(a, 6), 0, dp(a, 8));
        content.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 设备列表这种"每 0.7 秒重建一次"的场景：先清空再填 */
    void clearContent() {
        if (content != null) content.removeAllViews();
    }

    /** 列表里的一行可点项（左对齐、52dp 高、描边） */
    Button listItem(String text, View.OnClickListener onClick) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(ghostBg(a));
        b.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52));
        lp.topMargin = dp(a, 8);
        content.addView(b, lp);
        return b;
    }

    /** 内容区里的一行小字（列表表头/空态提示） */
    TextView hintLine(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(a, 4), 0, dp(a, 2));
        content.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    void show() {
        // v1.0.49：防 BadTokenException。
        // 这几个面板会从"蓝牙扫描回调 / 服务广播 / 定时刷新"这些异步路径里弹出来，
        // Activity 已经 finish 的时候再 show()，系统直接抛 BadToken → 用户看到"点了就闪退"。
        if (a.isFinishing() || a.isDestroyed()) return;
        try {
            dialog.show();
        } catch (Throwable ignore) {
            // 窗口已不可用（切后台/被系统收起）：静默放弃，不要连累主流程
        }
    }

    void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable rrect(Context c, int color, float radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) d.setStroke(dp(c, 1), strokeColor);
        return d;
    }

    static GradientDrawable ghostBg(Context c) {
        return rrect(c, 0x00000000, 12, LINE);
    }

    static GradientDrawable fieldBg(Context c) {
        return rrect(c, FIELD, 12, 0);
    }

    // ---------------- 内容构建 ----------------

    /** 一张卡片 + 组标题 */
    LinearLayout section(String name) {
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(a, 14);

        TextView tvName = new TextView(a);
        tvName.setText(name);
        tvName.setTextColor(SUB);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvName.setLetterSpacing(0.08f);
        tvName.setPadding(dp(a, 6), 0, 0, dp(a, 7));
        outer.addView(tvName);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rrect(a, CARD, 18, 0));
        card.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        outer.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(outer, olp);
        return card;
    }

    /** 一行：左标签（可带小字说明），右控件 */
    LinearLayout row(LinearLayout card, String label, String hint, View control) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(a, 9), 0, dp(a, 9));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextColor(TXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        texts.addView(tv);
        if (hint != null && !hint.isEmpty()) {
            TextView tvHint = new TextView(a);
            tvHint.setText(hint);
            tvHint.setTextColor(SUB);
            tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvHint.setPadding(0, dp(a, 2), 0, 0);
            texts.addView(tvHint);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (control != null) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    dp(a, 108), ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(a, 12);
            r.addView(control, clp);
        }
        card.addView(r);
        return r;
    }

    /** 小型数值/单位展示（不是输入框） */
    TextView value(String text, boolean accent) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(accent ? ACC : TXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.END);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    EditText input(String value, boolean decimal) {
        EditText e = new EditText(a);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(TXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setHintTextColor(SUB);
        e.setBackground(fieldBg(a));
        e.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8));
        e.setInputType(decimal
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    CheckBox check(String label, boolean checked) {
        CheckBox c = new CheckBox(a);
        c.setText(label);
        c.setChecked(checked);
        c.setAllCaps(false);
        c.setTextColor(TXT);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return c;
    }

    /** 整行控件（复选框这种要占满宽度的）：避免被右侧 108dp 槽位挤成竖排文字 */
    void checkRow(LinearLayout card, CheckBox cb, String hint) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(a, 4), 0, dp(a, 4));
        cb.setPadding(0, dp(a, 4), 0, dp(a, 2));
        r.addView(cb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (hint != null && !hint.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(hint);
            t.setTextColor(SUB);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setPadding(dp(a, 32), 0, 0, dp(a, 4));
            r.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 卡片里的一整块说明文字（比组标题更淡） */
    TextView note(LinearLayout card, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setLineSpacing(dp(a, 3), 1f);
        t.setPadding(0, dp(a, 2), 0, dp(a, 6));
        card.addView(t);
        return t;
    }

    /** 卡片内的一条按钮（通栏、描边） */
    Button inlineButton(LinearLayout card, String text) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(ghostBg(a));
        b.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48));
        lp.topMargin = dp(a, 8);
        card.addView(b, lp);
        return b;
    }

    /** 底部主操作（实心） */
    Button primary(String text, View.OnClickListener onClick) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(ACC_INK);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        GradientDrawable bg = rrect(a, ACC, 16, 0);
        b.setBackground(bg);
        if (onClick != null) b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 54));
        footer.addView(b, lp);
        return b;
    }

    /** 底部次操作（描边） */
    Button secondary(String text, View.OnClickListener onClick) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(ghostBg(a));
        if (onClick != null) b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48));
        lp.topMargin = dp(a, 8);
        footer.addView(b, lp);
        return b;
    }

    /** 一排可点选项（配色、亮度这种），选中项高亮 */
    LinearLayout chips(LinearLayout card, String[] labels, int selected,
                       ChipsListener listener) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setPadding(0, dp(a, 6), 0, dp(a, 8));
        final Button[] btns = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(a);
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            final int idx = i;
            applyChip(b, i == selected);
            b.setOnClickListener(v -> {
                for (int k = 0; k < btns.length; k++) applyChip(btns[k], k == idx);
                listener.onPick(idx);
            });
            btns[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(a, 42), 1f);
            lp.rightMargin = (i == labels.length - 1) ? 0 : dp(a, 8);
            wrap.addView(b, lp);
        }
        card.addView(wrap);
        return wrap;
    }

    private void applyChip(Button b, boolean on) {
        GradientDrawable bg = on ? rrect(a, ACC, 12, 0) : rrect(a, 0x00000000, 12, LINE);
        b.setBackground(bg);
        b.setTextColor(on ? ACC_INK : SUB);
        b.setTypeface(b.getTypeface(), on ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
    }

    interface ChipsListener {
        void onPick(int index);
    }

    static int colorOf(int c) {
        return Color.argb(255, Color.red(c), Color.green(c), Color.blue(c));
    }
}
