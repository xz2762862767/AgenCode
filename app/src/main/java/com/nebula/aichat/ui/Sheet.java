package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 底部面板（bottom sheet）。菜音、标签、历史这些「一堆操作/一堆条目」的界面都走它。
 *
 * <p>换掉旧样式的理由：以前用 AlertDialog.setItems / PopupMenu，出来是一个白底方块、
 * 一行行挤在一起的系统列表 —— 没有图标、没有分组、没有主次，动作项和列表项长得一模一样。
 * 现在统一成：贴底的圆角面板 + 一条拖拽条 + 标题行（右侧可挂动作）+ 若干「组卡片」
 * （组内行与行之间是一条缩进的分隔线，组与组之间靠留白分开）+ 左侧线性图标底纹方块。
 *
 * <p>颜色全部走 Palette，深色/纯黑/默认白三套主题都成立；不写死色值。
 */
public class Sheet {

    /** 危险动作（清空、关闭、删除）用的红：不用主题强调色，避免和「可点」混淆 */
    public static final int DANGER = 0xFFE5484D;

    private final Activity act;
    private final Dialog dlg;
    private final LinearLayout root;
    private final LinearLayout header;
    private final TextView tvTitle;
    private final TextView tvAction;
    private final TextView tvSub;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final LinearLayout footer;

    private LinearLayout curGroup;

    /**
     * 面板总高上限占屏幕的比例。默认 0.72：普通操作面板行数少，不该顶到状态栏；
     * 选取文字这种要看大量正文的面板可以调到 0.9。
     */
    private float maxHeightRatio = 0.72f;

    public static Sheet of(Activity a) {
        return new Sheet(a);
    }

    private Sheet(Activity a) {
        act = a;
        dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCanceledOnTouchOutside(true);

        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(topRounded(a));
        root.setPadding(dp(14), dp(8), dp(14), dp(12) + Ui.navBarHeight(a));

        // ── 拖拽条：告诉用户「这是可以拽下去的」，比一个死板的弹窗友好
        View handle = new View(a);
        handle.setBackground(Ui.roundRect(a, alpha(Palette.text(a), 0x30), 0, 2f));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(12);
        root.addView(handle, handleLp);

        // ── 标题行：标题 + 右上角动作（新建 / 清空）
        header = new LinearLayout(a);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(a);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(6), 0, dp(6), 0);

        tvTitle = new TextView(a);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Palette.text(a));
        tvTitle.setIncludeFontPadding(false);
        titleRow.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvAction = new TextView(a);
        tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvAction.setTextColor(Palette.accent(a));
        tvAction.setIncludeFontPadding(false);
        tvAction.setPadding(dp(10), dp(4), dp(10), dp(4));
        tvAction.setVisibility(View.GONE);
        titleRow.addView(tvAction);
        header.addView(titleRow);

        tvSub = new TextView(a);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvSub.setTextColor(Palette.dim(a));
        tvSub.setIncludeFontPadding(false);
        tvSub.setPadding(dp(6), dp(5), dp(6), 0);
        tvSub.setVisibility(View.GONE);
        header.addView(tvSub);

        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.bottomMargin = dp(12);
        root.addView(header, headLp);

        // ── 内容区（可滚）
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 底部按钮区
        footer = new LinearLayout(a);
        footer.setOrientation(LinearLayout.VERTICAL);
        root.addView(footer);
    }

    // ======================================================== 头部

    public Sheet title(String t) {
        tvTitle.setText(t == null ? "" : t);
        return this;
    }

    public Sheet subtitle(String s) {
        if (s == null || s.trim().isEmpty()) {
            tvSub.setVisibility(View.GONE);
        } else {
            tvSub.setText(s);
            tvSub.setVisibility(View.VISIBLE);
        }
        return this;
    }

    /** 标题右侧的文字动作，例如「新建」「清空」 */
    public Sheet action(String text, int color, final Runnable r) {
        if (text == null || text.isEmpty()) {
            tvAction.setVisibility(View.GONE);
            return this;
        }
        tvAction.setText(text);
        tvAction.setTextColor(color);
        tvAction.setBackground(Ui.pressable(act, Ui.roundRect(act, 0, 0, 10f)));
        tvAction.setVisibility(View.VISIBLE);
        tvAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (r != null) r.run();
            }
        });
        return this;
    }

    // ======================================================== 组与行

    /** 开一个新组（一张圆角卡片），行与行之间自动加缩进分隔线 */
    public Sheet group() {
        curGroup = new LinearLayout(act);
        curGroup.setOrientation(LinearLayout.VERTICAL);
        curGroup.setBackground(Ui.roundRect(act,
                Palette.isLight(act) ? 0xFFFFFFFF : Palette.surface2(act),
                Palette.stroke(act), 15f));
        curGroup.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(content.getChildCount() == 0 ? 0 : 10);
        content.addView(curGroup, lp);
        return this;
    }

    private LinearLayout ensureGroup() {
        if (curGroup == null) group();
        return curGroup;
    }

    private void addToGroup(View v) {
        LinearLayout g = ensureGroup();
        if (g.getChildCount() > 0) {
            View line = new View(act);
            line.setBackgroundColor(Palette.stroke(act));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
            lp.leftMargin = dp(58);          // 从文字起始处开始缩进，视觉上不切断图标列
            g.addView(line, lp);
        }
        g.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 普通动作行：图标 + 标题 +（可选）副标题 + 右侧箭头 */
    public Sheet row(int icon, String title, String sub, View.OnClickListener l) {
        return row(icon, title, sub, l, true, false);
    }

    public Sheet row(int icon, String title, String sub, View.OnClickListener l,
                     boolean enabled, boolean danger) {
        addToGroup(buildRow(icon, null, title, sub, null, null, enabled, danger, l));
        return this;
    }

    /** 用汉字代替图标（例如「译」） */
    public Sheet rowGlyph(String glyph, String title, String sub, View.OnClickListener l) {
        addToGroup(buildRow(0, glyph, title, sub, null, null, true, false, l));
        return this;
    }

    /** 危险动作行：红字 + 红底图标，跟普通行一眼分开 */
    public Sheet rowDanger(int icon, String title, String sub, View.OnClickListener l) {
        addToGroup(buildRow(icon, null, title, sub, null, null, true, true, l));
        return this;
    }

    /** 带开关的行：整行可点，点一下切换 */
    public Sheet rowSwitch(int icon, String title, String sub, boolean on,
                           final Toggle.Cb cb) {
        return switchRow(icon, null, title, sub, on, cb);
    }

    /** 同 rowSwitch，但图标用汉字（「译」这种线性画不出意思的） */
    public Sheet rowSwitchGlyph(String glyph, String title, String sub, boolean on,
                                final Toggle.Cb cb) {
        return switchRow(0, glyph, title, sub, on, cb);
    }

    private Sheet switchRow(int icon, String glyph, String title, String sub, boolean on,
                            final Toggle.Cb cb) {
        final Switch sw = new Switch(act);
        sw.setChecked(on, false);
        View.OnClickListener toggler = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean v2 = !sw.isChecked();
                sw.setChecked(v2, true);
                if (cb != null) cb.onChanged(v2);
            }
        };
        sw.setOnClickListener(toggler);
        addToGroup(buildRow(icon, glyph, title, sub, null, sw, true, false, toggler));
        return this;
    }

    /** 右侧塞任意控件（标签页那种带 × 的行） */
    public Sheet rowTrailing(int icon, String title, String sub, View trailing,
                             View.OnClickListener l) {
        addToGroup(buildRow(icon, null, title, sub, null, trailing, true, false, l));
        return this;
    }

    /** 右侧显示一段灰字（如「当前」「2 天前」），整行可点 */
    public Sheet rowValue(int icon, String title, String sub, String value,
                          View.OnClickListener l) {
        addToGroup(buildRow(icon, null, title, sub, value, null, true, false, l));
        return this;
    }

    /** 直接塞一个自定义行（标签页那种要带关闭按钮的） */
    public Sheet rowCustom(View v) {
        addToGroup(v);
        return this;
    }

    /**
     * 一段说明正文：左对齐、带行距，用来解释口径 / 规则。
     * 和 row() 的区别是它不带图标、不可点，就是一段话。
     */
    public Sheet note(String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(alpha(Palette.text(act), 0xD0));
        tv.setLineSpacing(dp(4), 1f);
        tv.setIncludeFontPadding(false);
        tv.setPadding(dp(4), dp(14), dp(4), dp(4));
        content.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** 空状态提示：居中的一行灰字，别让用户看到一张空卡片 */
    public Sheet empty(String text) {        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTextColor(Palette.dim(act));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(26), dp(16), dp(26));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(tv, lp);
        return this;
    }

    /** 放宽/收紧面板高度上限（占屏幕比例，0.6 ~ 0.95 之间比较合适） */
    public Sheet heightRatio(float r) {
        if (r > 0.3f) maxHeightRatio = Math.min(r, 0.95f);
        return this;
    }

    public Sheet spacer(float h) {
        content.addView(Ui.spacer(act, h));
        return this;
    }

    // ======================================================== 底部按钮

    /** 满宽主按钮 */
    public Sheet primary(String text, final Runnable r) {
        return primaryWith(text, Palette.accent(act), r);
    }

    /** 满宽主按钮（红）：确认清空、删除这类不可撤销的动作 */
    public Sheet primaryDanger(String text, final Runnable r) {
        return primaryWith(text, DANGER, r);
    }

    private Sheet primaryWith(String text, int color, final Runnable r) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pressable(act, Ui.roundRect(act, color, 0, 13f)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (r != null) r.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(14);
        footer.addView(b, lp);
        return this;
    }

    /** 次要按钮：只有描边，不抢主按钮的注意力 */
    public Sheet secondary(String text, final Runnable r) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTextColor(Palette.text(act));
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pressable(act, Ui.roundRect(act, 0, Palette.stroke(act), 13f)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (r != null) r.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(14);
        footer.addView(b, lp);
        return this;
    }

    // ======================================================== 显示

    public Dialog show() {
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
        }
        dlg.show();
        // 内容太长时限制滚动高度，别让面板顶到状态栏。
        // 这里按「整块面板」而不是「内容区」算比例：内容区高度是结果，
        // 直接限制它会让 header/footer 的高度把面板顶出屏幕。
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int screenH = act.getResources().getDisplayMetrics().heightPixels;
                int inner = scroll.getHeight();
                if (inner <= 0) return;
                int chrome = root.getHeight() - inner;
                int avail = (int) (screenH * maxHeightRatio) - chrome;
                if (avail < dp(170)) avail = dp(170);
                if (inner <= avail) return;
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = avail;
                scroll.setLayoutParams(lp);
            }
        });
        // 进场：轻微上移 + 淡入，比系统默认的「突然出现」顺眼
        root.setAlpha(0f);
        root.setTranslationY(dp(26));
        root.animate().alpha(1f).translationY(0f)
                .setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        return dlg;
    }

    public void dismiss() {
        try {
            if (dlg.isShowing()) dlg.dismiss();
        } catch (Throwable ignored) {
        }
    }

    public Dialog dialog() {
        return dlg;
    }

    // ======================================================== 行构建

    /**
     * 一行：左图标方块 + 中间两级文字 + 右侧（箭头 / 值 / 开关）。
     *
     * @param glyph 非空时用文字当图标
     */
    private View buildRow(int iconType, String glyph, String title, String sub,
                          String value, View right, boolean enabled, boolean danger,
                          View.OnClickListener l) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(Ui.pressable(act, Ui.roundRect(act, 0, 0, 15f)));
        if (enabled) row.setClickable(true);
        row.setAlpha(enabled ? 1f : 0.45f);
        if (l != null && enabled) row.setOnClickListener(l);

        int tint = danger ? DANGER : Palette.accent(act);
        int tileBg = alpha(tint, danger ? 0x1C : 0x1A);
        View ic = (glyph != null) ? Icons.glyph(act, glyph, tint) : Icons.of(act, iconType, tint);
        row.addView(Icons.inTile(act, ic, tint, tileBg));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = dp(12);
        row.addView(col, colLp);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        t.setTextColor(danger ? DANGER : Palette.text(act));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);

        if (sub != null && !sub.trim().isEmpty()) {
            TextView s = new TextView(act);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            s.setTextColor(Palette.dim(act));
            s.setIncludeFontPadding(false);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(3);
            col.addView(s, slp);
        }

        if (right != null) {
            row.addView(right);
        } else if (value != null && !value.isEmpty()) {
            TextView v = new TextView(act);
            v.setText(value);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            v.setTextColor(Palette.dim(act));
            v.setIncludeFontPadding(false);
            v.setSingleLine(true);
            row.addView(v);
        } else if (enabled) {
            TextView chev = new TextView(act);
            chev.setText("›");
            chev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            chev.setTextColor(alpha(Palette.dim(act), 0xB0));
            chev.setIncludeFontPadding(false);
            chev.setPadding(dp(6), 0, 0, 0);
            row.addView(chev);
        }
        return row;
    }

    // ======================================================== 小工具

    public interface Toggle {
        void setChecked(boolean on, boolean animate);

        boolean isChecked();

        interface Cb {
            void onChanged(boolean on);
        }
    }

    /** 自绘开关：比系统 Switch 小一圈，色值跟面板统一 */
    public static class Switch extends View implements Toggle {
        private boolean on;
        private float pos;          // 0=关 1=开，用于动画插值
        private final android.graphics.Paint p = new android.graphics.Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();

        public Switch(Activity a) {
            super(a);
            int w = Ui.dp(a, 40), h = Ui.dp(a, 23);
            setLayoutParams(new LinearLayout.LayoutParams(w, h));
            pos = 0f;
        }

        @Override
        public void setChecked(boolean v, boolean animate) {
            on = v;
            float target = v ? 1f : 0f;
            if (!animate) {
                pos = target;
                invalidate();
                return;
            }
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(pos, target);
            va.setDuration(160);
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    pos = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            va.start();
        }

        @Override
        public boolean isChecked() {
            return on;
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            int w = getWidth(), h = getHeight();
            float rad = h / 2f;
            int offC = alpha(Palette.dim(getContext()), 0x55);
            int onC = Palette.accent(getContext());
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(blend(offC, onC, pos));
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, p);

            float kr = rad - Ui.dp(getContext(), 2.5f);
            float cx = rad + (w - 2 * rad) * pos;
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, h / 2f, kr, p);
        }

        /** 两个颜色按 t 混合 */
        private static int blend(int a, int b, float t) {
            int aa = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t);
            int rr = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
            int gg = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
            int bb = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
            return Color.argb(aa, rr, gg, bb);
        }
    }

    private int dp(float v) {
        return Ui.dp(act, v);
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /** 顶部两角圆、底部直角：贴底面板的正确形状 */
    private static GradientDrawable topRounded(Activity a) {
        GradientDrawable d = new GradientDrawable();
        float r = Ui.dp(a, 22);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        d.setColor(Palette.surface(a));
        d.setStroke(Math.max(1, Ui.dp(a, 0.8f)), Palette.stroke(a));
        return d;
    }
}
