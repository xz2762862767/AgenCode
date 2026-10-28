package com.nebula.aichat.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;

import com.nebula.aichat.R;
import com.nebula.aichat.data.Settings;

/**
 * 主题与配色中心。
 * 主题三档（深色 / 纯黑 OLED / 默认白），强调色五档；
 * 布局与 drawable 一律引用 ?attr/neb_*，运行时通过 applyStyle 覆盖，无需重建布局。
 *
 * 另提供 {@link #c(int)}：把历史上写死在代码里的十六进制色，在浅色主题下映射成对应色。
 * 深色/纯黑主题下原样返回，所以调用点包裹它不会有任何副作用。
 */
public final class Palette {

    public static final int THEME_DARK = 0;
    public static final int THEME_BLACK = 1;
    public static final int THEME_LIGHT = 2;

    public static final String[] THEME_NAMES = {"深色", "纯黑 OLED", "默认白"};

    /** 当前进程是否浅色主题：由 {@link #apply(Activity)} 更新，供 {@link #c(int)} 使用 */
    private static boolean light = false;

    public static final String[] ACCENT_NAMES = {"经典蓝", "雾紫", "薄荷绿", "暖橙", "青蓝"};
    public static final int[] ACCENT_COLORS = {
            0xFF5B93FF, 0xFFA97BFF, 0xFF3DDC97, 0xFFFFA85C, 0xFF4CD7E8
    };

    private Palette() {
    }

    /** 必须在 setContentView 之前调用（放在 onCreate 第一行） */
    public static void apply(Activity a) {
        Settings s = Settings.load(a);
        light = (s.theme == THEME_LIGHT);
        int themeRes;
        if (s.theme == THEME_BLACK) themeRes = R.style.AppTheme_Black;
        else if (s.theme == THEME_LIGHT) themeRes = R.style.AppTheme_Light;
        else themeRes = R.style.AppTheme;
        a.setTheme(themeRes);
        int idx = Math.max(0, Math.min(ACCENT_NAMES.length - 1, s.accent));
        if (idx != 0) a.getTheme().applyStyle(accentStyle(idx), true);
    }

    /** 当前是否浅色主题（供非 Activity 的静态上下文判断） */
    public static boolean isLight() {
        return light;
    }

    public static boolean isLight(Context c) {
        return light || isLightAttr(c);
    }

    /** 直接看主题里的背景亮度，避免静态标志与实际主题不一致 */
    private static boolean isLightAttr(Context c) {
        if (c == null) return light;
        TypedValue tv = new TypedValue();
        if (!c.getTheme().resolveAttribute(R.attr.neb_bg, tv, true)) return light;
        return luminance(tv.data) > 0.5f;
    }

    private static float luminance(int argb) {
        return (0.299f * Color.red(argb) + 0.587f * Color.green(argb) + 0.114f * Color.blue(argb)) / 255f;
    }

    /**
     * 把写死在代码里的颜色映射到当前主题。
     * 深色/纯黑主题：原样返回（老外观零变化）；
     * 浅色主题：无彩色（文字/描边/背景）做亮度反转，彩色（强调/状态色）压暗保持色相，alpha 不变。
     */
    public static int c(int argb) {
        return light ? map(argb) : argb;
    }

    /** 需要按 context 判断主题时用这个版本（跨主题缓存的视图） */
    public static int c(Context ctx, int argb) {
        return isLightAttr(ctx) ? map(argb) : argb;
    }

    /** 纯函数：深色色值 → 浅色对应色 */
    private static int map(int argb) {
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) return argb;                       // 全透明保留
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        float h = hsv[0], s = hsv[1], v = hsv[2];
        if (s > 0.30f && v > 0.35f) {
            // 彩色：压暗一档，让它在白底上有足够对比，又不会变成纯黑
            v = Math.max(0.32f, Math.min(0.90f, v * 0.62f));
            s = Math.min(1f, s * 1.05f);
        } else {
            // 灰阶：亮度反转（深色主题里的浅字 → 深字，深底 → 浅底）
            v = 1f - v * 0.90f;
            if (v < 0.10f) v = 0.10f;
            if (v > 0.96f) v = 0.96f;
            s = Math.min(s, 0.10f);
        }
        int rgb = Color.HSVToColor(new float[]{h, s, v});
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    private static int accentStyle(int idx) {
        switch (idx) {
            case 1:
                return R.style.AccentPurple;
            case 2:
                return R.style.AccentGreen;
            case 3:
                return R.style.AccentOrange;
            case 4:
                return R.style.AccentCyan;
            default:
                return R.style.AccentBlue;
        }
    }

    public static int accent(Context c) {
        return attr(c, R.attr.neb_accent);
    }

    public static int bg(Context c) {
        return attr(c, R.attr.neb_bg);
    }

    public static int surface(Context c) {
        return attr(c, R.attr.neb_surface);
    }

    public static int surface2(Context c) {
        return attr(c, R.attr.neb_surface2);
    }

    public static int stroke(Context c) {
        return attr(c, R.attr.neb_stroke);
    }

    public static int text(Context c) {
        return attr(c, R.attr.neb_text);
    }

    public static int dim(Context c) {
        return attr(c, R.attr.neb_dim);
    }

    /** 半透明强调色底（选中行、标签背景） */
    public static int accentSoft(Context c, int alpha) {
        return (accent(c) & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static int attr(Context c, int attrId) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attrId, tv, true)) return tv.data;
        return 0xFF5B93FF;
    }
}
