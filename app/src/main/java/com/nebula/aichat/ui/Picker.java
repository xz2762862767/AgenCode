package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 底部选择面板（Bottom Sheet）。
 *
 * 原来「位置 ▾」「新建」这类只有几个选项的地方用的是系统 AlertDialog.setItems：
 * 白色分割线 + 居中方块列表，跟 App 的深色圆角风格完全两套语言，而且选项多时
 * 看不出层级。这里统一换成从底部升起的圆角面板：
 *
 * - 顶部一条短把手，暗示可以往下拖（视觉暗示，实际点空白或取消关闭）
 * - 每个选项左侧一个带底色的图标块，右侧标出「当前」的那一项
 * - 入场从下方滑入 220ms，不靠 windowAnimations（省掉一个动画资源）
 *
 * 用法：Picker.show(act, "标题", "副标题", opts)；Opt.action 在面板关闭后执行。
 */
public final class Picker {

    public static final class Opt {
        public final String icon;
        public final String title;
        public final String desc;
        public final boolean current;
        public final Runnable action;

        public Opt(String icon, String title, String desc, boolean current, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.desc = desc;
            this.current = current;
            this.action = action;
        }

        public Opt(String icon, String title, String desc, Runnable action) {
            this(icon, title, desc, false, action);
        }
    }

    private Picker() {
    }

    public static void show(final Activity act, String title, String subtitle, final List<Opt> opts) {
        if (act == null || act.isFinishing() || opts == null || opts.isEmpty()) return;

        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout stage = new FrameLayout(act);
        // 面板外的空白区点一下就走，符合「随手点开随手关」的预期
        stage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });

        LinearLayout sheet = Ui.col(act);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Palette.surface(act));
        float r = Ui.dp(act, 22);
        // 只圆上面两个角：贴底的面板下方是屏幕边缘，圆了反而露黑边
        sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setClickable(true);   // 吃掉点击，避免透传到 stage 把自己关了

        // 把手
        View handle = new View(act);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Palette.stroke(act));
        hb.setCornerRadius(Ui.dp(act, 2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(act, 36), Ui.dp(act, 4));
        hlp.topMargin = Ui.dp(act, 10);
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, hlp);

        // 标题区
        LinearLayout head = Ui.col(act);
        head.setPadding(Ui.dp(act, 20), Ui.dp(act, 12), Ui.dp(act, 20), Ui.dp(act, 8));
        TextView tvTitle = Ui.text(act, title, 15f, Palette.text(act));
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(tvTitle);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = Ui.text(act, subtitle, 11.5f, Palette.dim(act));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(act, 3);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            head.addView(tvSub, slp);
        }
        sheet.addView(head);

        // 选项可能很多（工作区里目录深），套一层滚动，最多占屏幕 60%
        ScrollView sv = new ScrollView(act);
        LinearLayout list = Ui.col(act);
        list.setPadding(0, 0, 0, Ui.dp(act, 4));
        for (int i = 0; i < opts.size(); i++) {
            final Opt o = opts.get(i);
            if (i > 0) {
                View dv = Ui.divider(act);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(act, 0.6f)));
                dlp.leftMargin = Ui.dp(act, 68);   // 与文字左缘对齐，不横穿图标
                list.addView(dv, dlp);
            }
            list.addView(optionRow(act, o, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    // 等面板收起再跳，避免目录切换的刷新与退场动画抢主线程
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (o.action != null) o.action.run();
                        }
                    }, 80);
                }
            }));
        }
        sv.addView(list);
        sheet.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 小屏 / 大字号下选项可能撑出屏幕：超过半屏就让列表内部滚动，
        // 标题和「取消」始终留在原位
        final int maxListH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.52f);
        list.post(new Runnable() {
            @Override
            public void run() {
                if (list.getHeight() > maxListH) {
                    ViewGroup.LayoutParams lp = sv.getLayoutParams();
                    lp.height = maxListH;
                    sv.setLayoutParams(lp);
                }
            }
        });

        // 取消
        View topLine = new View(act);
        topLine.setBackgroundColor(Palette.stroke(act));
        LinearLayout.LayoutParams tllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(act, 0.6f)));
        tllp.topMargin = Ui.dp(act, 4);
        sheet.addView(topLine, tllp);

        TextView cancel = Ui.text(act, "取消", 14f, Palette.dim(act));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, Ui.dp(act, 15), 0, Ui.dp(act, 18));
        cancel.setBackground(Ui.pressable(act, new ColorDrawable(0x00000000)));
        cancel.setClickable(true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        sheet.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部导航栏(手势条)留白，别让「取消」压在手势区上
        sheet.setPadding(0, 0, 0, Ui.dp(act, 10));

        stage.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        d.setContentView(stage);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            w.setAttributes(lp);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            }
        }
        d.show();

        // 入场：从下方 120dp 处滑上来 + 淡入
        sheet.setTranslationY(Ui.dp(act, 120));
        sheet.setAlpha(0f);
        sheet.animate().translationY(0f).alpha(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private static View optionRow(Activity act, Opt o, View.OnClickListener l) {
        LinearLayout row = Ui.row(act);
        row.setPadding(Ui.dp(act, 18), Ui.dp(act, 11), Ui.dp(act, 18), Ui.dp(act, 11));
        row.setClickable(true);

        // 按下态：整行铺一层很淡的强调色，比系统默认的灰框自然
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Palette.accentSoft(act, 0x14));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x00000000);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
        bg.addState(new int[]{}, normal);
        row.setBackground(bg);

        // 图标块
        TextView icon = Ui.text(act, o.icon == null ? "·" : o.icon, 16f, Palette.text(act));
        icon.setGravity(Gravity.CENTER);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(Palette.accentSoft(act, 0x1F));
        ib.setCornerRadius(Ui.dp(act, 11));
        icon.setBackground(ib);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(act, 36), Ui.dp(act, 36));
        row.addView(icon, ilp);

        LinearLayout mid = Ui.col(act);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = Ui.dp(act, 13);
        TextView title = Ui.text(act, o.title, 14f, Palette.text(act));
        title.setSingleLine(true);
        mid.addView(title);
        if (o.desc != null && !o.desc.isEmpty()) {
            TextView desc = Ui.text(act, o.desc, 11f, Palette.dim(act));
            desc.setSingleLine(true);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Ui.dp(act, 2);
            mid.addView(desc, dlp);
        }
        row.addView(mid, mlp);

        if (o.current) {
            TextView cur = Ui.text(act, "当前", 10.5f, Palette.accent(act));
            cur.setPadding(Ui.dp(act, 8), Ui.dp(act, 3), Ui.dp(act, 8), Ui.dp(act, 3));
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(Palette.accentSoft(act, 0x24));
            cb.setCornerRadius(Ui.dp(act, 9));
            cur.setBackground(cb);
            row.addView(cur);
        } else {
            TextView arrow = Ui.text(act, "›", 17f, Palette.dim(act));
            arrow.setPadding(Ui.dp(act, 6), 0, 0, 0);
            row.addView(arrow);
        }

        row.setOnClickListener(l);
        return row;
    }
}
