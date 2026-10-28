package com.nebula.aichat.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Window;
import android.view.WindowManager;

import com.nebula.aichat.R;

/**
 * 统一封装的 AlertDialog.Builder：自动修正对话框背景，使其随主题变化。
 *
 * <p>为什么必须在这里改，而不能在 themes.xml 里配：
 * <ol>
 *   <li>{@code android.app.AlertDialog} 的窗口背景由 {@code AlertController} 从
 *       {@code alertDialogStyle} 的 {@code android:background} 读取，该属性的系统默认值
 *       指向 {@code @android:drawable/dialog_background_material}，其内部 solid 写死
 *       {@code @color/background_material_light}（浅灰白），<b>不随主题变化</b>。</li>
 *   <li>AppCompat 的 {@code @drawable/abc_dialog_material_background} 同样写死浅色 selector，
 *       {@code colorBackgroundFloating} / {@code colorBackground} 都穿不透它。</li>
 *   <li>想覆盖 {@code android:alertDialogStyle} 也不行：AOSP 里对应的
 *       {@code @android:style/AlertDialog.Material} 是 <b>私有资源</b>，aapt2 链接期直接报错
 *       （resource android:style/AlertDialog.Material is private）。</li>
 * </ol>
 *
 * <p>所以只能在代码层用 {@code setBackgroundDrawableResource} 覆盖窗口背景。
 * 资源里用 {@code ?attr/neb_surface} 引用，会按当前 Dialog 所在 Activity 的主题解析，
 * 深色 #161B24 / 黑色 #0A0A0D / 浅色 #FFFFFF 自动跟随。
 *
 * <p>用法与原生完全一致，只是把 {@code new AlertDialog.Builder(ctx)} 换成
 * {@code new NebAlert.Builder(ctx)}：所有 {@code setXxx} 链式调用、{@code create()}、
 * {@code show()} 的签名和返回值都保持不变。
 */
public final class NebAlert {

    private NebAlert() {
    }

    public static class Builder extends AlertDialog.Builder {

        public Builder(Context context) {
            super(context);
        }

        public Builder(Context context, int themeResId) {
            super(context, themeResId);
        }

        @Override
        public AlertDialog create() {
            AlertDialog d = super.create();
            applyBackground(d);
            applyWidth(d);
            return d;
        }

        @Override
        public AlertDialog show() {
            AlertDialog d = super.show();
            applyBackground(d);
            applyWidth(d);
            return d;
        }
    }

    /** 显式 setBackground 供已创建好的对话框复用（如外部传入的 Dialog 实例） */
    public static void applyBackground(AlertDialog d) {
        if (d == null) return;
        Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawableResource(R.drawable.neb_dialog_bg);
    }

    /**
     * 统一收窄对话框窗口，两侧留出固定边距。
     *
     * <p>AlertDialog 的宽度由主题里的 {@code windowMinWidthMajor} 决定，各 ROM 表现不一，
     * 部分机型会直接铺满屏幕宽度。这里不用百分比，而是「屏幕宽度 - 两侧固定边距」，
     * 无论屏幕多宽，左右都能稳定留出同样一道缝。
     *
     * <p>高度保持 WRAP_CONTENT，由内容自己决定——内容少的确认框不会出现大片空白。
     */
    public static void applyWidth(AlertDialog d) {
        if (d == null) return;
        final Window w = d.getWindow();
        if (w == null) return;
        Context ctx = d.getContext();
        if (ctx == null) return;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int width = dm.widthPixels - dp(ctx, 36);
        width = Math.min(width, dp(ctx, 560));
        final int finalW = width;
        w.setLayout(finalW, WindowManager.LayoutParams.WRAP_CONTENT);
        // create() 阶段窗口还没 attach 到 WindowManager，这次 setLayout 可能被随后的
        // show() 覆盖（技能面板就是 create 后手动 show）。往 decorView 的消息队列再投一次，
        // 等窗口真正上屏后再压一遍宽度；不用 setOnShowListener，避免顶掉业务侧的回调。
        w.getDecorView().post(() -> {
            if (d.isShowing()) {
                w.setLayout(finalW, WindowManager.LayoutParams.WRAP_CONTENT);
            }
        });
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
