package com.nebula.aichat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nebula.aichat.R;

/** 轻量 UI 构建工具（避免引入 material 依赖） */
public class Ui {

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable roundRect(Context c, int fill, int strokeColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) d.setStroke(dp(c, 1f), strokeColor);
        return d;
    }

    /**
     * 圆角矩形，圆角和边框粗细都能指定。
     * 工具行用它：柔和圆角 + 清晰的深绿边。
     */
    public static GradientDrawable box(Context c, int fill, int strokeColor,
                                       float radiusDp, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) {
            d.setStroke(Math.max(1, Math.round(dp(c, strokeDp))), strokeColor);
        }
        return d;
    }

    /**
     * 直角矩形：不给圆角，边框可指定粗细。
     * 工具行用它——方角 + 通栏，才有终端输出的硬边感。
     */
    public static GradientDrawable rect(Context c, int fill, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(0f);
        if (strokeColor != 0) {
            d.setStroke(Math.max(1, Math.round(dp(c, strokeDp))), strokeColor);
        }
        return d;
    }

    /**
     * 圆角矩形 + 外发光边：两层叠出来的「亮边」。
     * 下层用更粗的同色半透明描边并向外扩出 glowDp，形成一圈柔光；
     * 上层是实心描边的本体。父容器要 setClipChildren(false)，否则外发光会被裁掉。
     */
    public static android.graphics.drawable.Drawable glowBox(Context c, int fill, int strokeColor,
            float radiusDp, float strokeDp, int glowColor, float glowDp) {
        GradientDrawable glow = new GradientDrawable();
        glow.setColor(0);
        glow.setCornerRadius(dp(c, radiusDp) + dp(c, glowDp));
        glow.setStroke(Math.max(1, Math.round(dp(c, glowDp) * 2)), glowColor);

        GradientDrawable core = new GradientDrawable();
        core.setColor(fill);
        core.setCornerRadius(dp(c, radiusDp));
        core.setStroke(Math.max(1, Math.round(dp(c, strokeDp))), strokeColor);

        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{glow, core});
        int g = dp(c, glowDp);
        ld.setLayerInset(0, -g, -g, -g, -g);
        return ld;
    }

    /**
     * 主操作按钮：垂直渐变实心 + 1dp 亮边，不套外发光。
     * 弹窗主按钮总贴着底边，外发光会被 padding/clip 裁掉一半，看起来像被切了一刀；
     * 改成「顶部亮 → 底部深」的内部渐变，立体感画在按钮里，怎么裁都露不出破绽。
     */
    public static android.graphics.drawable.Drawable primaryButton(Context c) {
        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Palette.c(0xFF5C93FF), Palette.c(0xFF3E78F2), Palette.c(0xFF2A61DA)});
        fill.setCornerRadius(dp(c, 14));

        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(0);
        stroke.setCornerRadius(dp(c, 14));
        stroke.setStroke(Math.max(1, dp(c, 1f)), Palette.c(0x6689BAFF));

        return new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{fill, stroke});
    }

    public static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(c, Palette.isLight(c) ? 0xFFFFFFFF : Palette.c(0xFF161B24),
                Palette.isLight(c) ? 0xFFE3E8F1 : Palette.c(0xFF2A3240), 12f));
        int p = dp(c, 12);
        box.setPadding(p, p, p, p);
        return box;
    }

    public static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    public static LinearLayout col(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        return r;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        return tv;
    }

    public static TextView sectionTitle(Context c, String s) {
        TextView tv = text(c, s, 12f, Palette.dim(c));
        tv.setPadding(0, dp(c, 14), 0, dp(c, 6));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    /** 圆角小按钮 */
    public static TextView chip(Context c, String s, int fill, int textColor, View.OnClickListener l) {
        TextView tv = text(c, s, 13f, textColor);
        tv.setPadding(dp(c, 12), dp(c, 7), dp(c, 12), dp(c, 7));
        tv.setBackground(pressable(c, roundRect(c, fill, 0, 16f)));
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    public static TextView primaryChip(Context c, String s, View.OnClickListener l) {
        return Palette.isLight(c)
                ? chip(c, s, 0xFFDCE8FF, 0xFF2A61DA, l)
                : chip(c, s, Palette.c(0xFF1E3A6B), Palette.c(0xFF9DC0FF), l);
    }

    /** 给背景加一层按下去的水波，按钮才有「点到了」的即时感 */
    public static android.graphics.drawable.Drawable pressable(Context c,
            android.graphics.drawable.Drawable base) {
        if (android.os.Build.VERSION.SDK_INT < 21) return base;
        try {
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Palette.c(0x40FFFFFF)), base, null);
        } catch (Throwable t) {
            return base;
        }
    }

    public static TextView ghostChip(Context c, String s, View.OnClickListener l) {
        return Palette.isLight(c)
                ? chip(c, s, 0xFFEDF1F8, 0xFF3C4A5E, l)
                : chip(c, s, Palette.c(0xFF1E2530), Palette.c(0xFFB9C5D6), l);
    }

    /** 统一的小胶囊按钮（正文里折叠/操作的标记都用它，保证视觉一致） */
    public static TextView pill(Context c, String s) {
        TextView tv = text(c, s, 11f, Palette.accent(c));
        tv.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        tv.setBackground(roundRect(c, Palette.c(0x1A5B93FF), Palette.c(0x4D5B93FF), 9f));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    /** 弱化的胶囊按钮（次要动作，比如「复制」） */
    public static TextView pillGhost(Context c, String s) {
        TextView tv = text(c, s, 11f, Palette.dim(c));
        tv.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        tv.setBackground(Palette.isLight(c)
                ? roundRect(c, 0x0F000000, 0xFFD8DFEA, 9f)
                : roundRect(c, Palette.c(0x14FFFFFF), 0x2E3A4C, 9f));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    /** 折叠标题左侧文字：字号/颜色统一，避免各处长文本、代码、思考过程各写一套 */
    public static TextView foldTitle(Context c, String s) {
        return text(c, s, 12f, Palette.dim(c));
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Palette.c(0xFF2A3240));
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(c, 0.6f))));
        return v;
    }

    public static View spacer(Context c, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, h)));
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpw(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.CHINA, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.CHINA, "%.1f MB", mb);
        return String.format(java.util.Locale.CHINA, "%.2f GB", mb / 1024.0);
    }

    // ===== 系统栏安全区 =====

    /** 状态栏高度（px）：优先真实窗口 insets，取不到再回落到系统资源 */
    public static int statusBarHeight(Context c) {
        return insetSize(c, true);
    }

    /** 底部导航栏高度（px，手势导航时通常 > 0 但很矮） */
    public static int navBarHeight(Context c) {
        return insetSize(c, false);
    }

    private static int insetSize(Context c, boolean status) {
        try {
            if (c instanceof android.app.Activity) {
                android.view.Window w = ((android.app.Activity) c).getWindow();
                View dv = w == null ? null : w.getDecorView();
                android.view.WindowInsets in = dv == null ? null : dv.getRootWindowInsets();
                if (in != null) {
                    int v = status ? in.getSystemWindowInsetTop() : in.getSystemWindowInsetBottom();
                    if (v > 0) return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return resSize(c, status ? "status_bar_height" : "navigation_bar_height");
    }

    private static int resSize(Context c, String name) {
        try {
            int id = c.getResources().getIdentifier(name, "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return 0;
    }
}
