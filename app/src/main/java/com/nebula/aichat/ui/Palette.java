package com.nebula.aichat.ui;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;

import com.nebula.aichat.R;
import com.nebula.aichat.data.Settings;

/**
 * 主题与配色中心。
 * 主题只有两档（深色 / 纯黑 OLED），强调色五档；
 * 布局与 drawable 一律引用 ?attr/neb_*，运行时通过 applyStyle 覆盖，无需重建布局。
 */
public final class Palette {

    public static final int THEME_DARK = 0;
    public static final int THEME_BLACK = 1;

    public static final String[] THEME_NAMES = {"深色", "纯黑 OLED"};
    public static final String[] ACCENT_NAMES = {"经典蓝", "雾紫", "薄荷绿", "暖橙", "青蓝"};
    public static final int[] ACCENT_COLORS = {
            0xFF5B93FF, 0xFFA97BFF, 0xFF3DDC97, 0xFFFFA85C, 0xFF4CD7E8
    };

    private Palette() {
    }

    /** 必须在 setContentView 之前调用（放在 onCreate 第一行） */
    public static void apply(Activity a) {
        Settings s = Settings.load(a);
        a.setTheme(s.theme == THEME_BLACK ? R.style.AppTheme_Black : R.style.AppTheme);
        int idx = Math.max(0, Math.min(ACCENT_NAMES.length - 1, s.accent));
        if (idx != 0) a.getTheme().applyStyle(accentStyle(idx), true);
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
