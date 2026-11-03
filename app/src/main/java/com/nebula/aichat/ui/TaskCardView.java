package com.nebula.aichat.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行步骤卡片。
 *
 * <p>模型给出「要改什么、怎么改」的清单后，正文里只保留说明，步骤单独渲染成这张卡，
 * 并且放在正文<b>上方</b>——它是这轮要做的事的索引，读者先看到它，再往下看怎么做的。</p>
 *
 * <p>卡片既能手点勾选，也能被外部驱动（{@link #tickNext()}）：每跑完一个工具调用，
 * MainActivity 就勾掉下一个未完成项，于是整轮过程中看到的是一步一个 ✓ 地往前走。</p>
 */
public class TaskCardView extends LinearLayout {

    /** 勾选回调：第 index 步被切换成 done */
    public interface OnToggle {
        void onToggle(int index, boolean done);
    }

    private static final int C_DONE = 0xFF6FCB9E;   // 完成：柔绿
    private static final int C_TEXT = 0xFFDCE6F2;   // 未完成正文
    private static final int C_DIM = 0xFF7C8A9C;    // 已完成正文 / 序号
    private static final int C_LINE = 0x1FFFFFFF;   // 进度条底
    private static final int C_BOX = 0x10FFFFFF;    // 卡片底

    private final Context ctx;
    private final List<String> tasks;
    private final List<Boolean> state = new ArrayList<>();
    private final List<TextView> marks = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private TextView count;
    private View fill;
    private View rest;
    private OnToggle cb;

    public TaskCardView(Context c, List<String> items, List<Boolean> done, OnToggle callback) {
        super(c);
        this.ctx = c;
        this.cb = callback;
        this.tasks = new ArrayList<>(items);
        int n = tasks.size();
        for (int i = 0; i < n; i++) {
            state.add(done != null && i < done.size() && Boolean.TRUE.equals(done.get(i)));
        }

        setOrientation(VERTICAL);
        setBackground(Ui.roundRect(c, Palette.c(C_BOX), 0, 10));
        int pad = Ui.dp(c, 10);
        setPadding(pad, Ui.dp(c, 9), pad, Ui.dp(c, 8));

        // ---- 头部：标题 + 进度数字 ----
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = Ui.text(c, "执行步骤", 12f, Palette.c(C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        count = Ui.text(c, "", 11f, Palette.c(C_DIM));
        count.setTypeface(Typeface.MONOSPACE);
        head.addView(count);
        addView(head);

        // ---- 进度条：两段，宽度按完成比例分配 ----
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(HORIZONTAL);
        LayoutParams barLp = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 3));
        barLp.topMargin = Ui.dp(c, 7);
        bar.setLayoutParams(barLp);
        bar.setBackground(Ui.roundRect(c, Palette.c(C_LINE), 0, 2));
        bar.setClipToOutline(true);

        fill = new View(c);
        fill.setBackground(Ui.roundRect(c, Palette.c(C_DONE), 0, 2));
        bar.addView(fill, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        rest = new View(c);
        bar.addView(rest, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        addView(bar);

        // ---- 步骤行 ----
        for (int i = 0; i < n; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.TOP);
            LayoutParams rlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Ui.dp(c, i == 0 ? 8 : 2);
            row.setLayoutParams(rlp);
            int p = Ui.dp(c, 4);
            row.setPadding(p, p, p, p);
            row.setBackground(Ui.pressable(c, Ui.roundRect(c, 0x00000000, 0, 8)));

            // 勾选框：18dp 圆角方块，完成时填绿并显示 ✓
            TextView box = new TextView(c);
            box.setText("\u2713");
            box.setTextSize(12f);
            box.setTypeface(Typeface.DEFAULT_BOLD);
            box.setGravity(Gravity.CENTER);
            box.setTextColor(Color.TRANSPARENT);
            LayoutParams blp = new LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
            blp.topMargin = Ui.dp(c, 1);
            blp.rightMargin = Ui.dp(c, 8);
            row.addView(box, blp);

            TextView label = Ui.text(c, tasks.get(i), 12.5f, Palette.c(C_TEXT));
            label.setLineSpacing(Ui.dp(c, 2), 1f);
            row.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            marks.add(box);
            labels.add(label);

            row.setOnClickListener(v -> {
                boolean now = !Boolean.TRUE.equals(state.get(idx));
                state.set(idx, now);
                paint();
                v.animate().scaleX(0.99f).scaleY(0.99f).setDuration(60)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(90).start()).start();
                if (cb != null) cb.onToggle(idx, now);
            });
            addView(row);
        }

        paint();
    }

    public void setOnToggle(OnToggle callback) {
        this.cb = callback;
    }

    public int size() {
        return tasks.size();
    }

    public int doneCount() {
        int k = 0;
        for (int i = 0; i < state.size(); i++) if (Boolean.TRUE.equals(state.get(i))) k++;
        return k;
    }

    public List<Boolean> snapshot() {
        List<Boolean> out = new ArrayList<>(state.size());
        for (int i = 0; i < state.size(); i++) {
            out.add(Boolean.TRUE.equals(state.get(i)));
        }
        return out;
    }

    /**
     * 勾掉第一个未完成项。整轮执行时每跑完一个工具调用就叫一次，
     * 于是勾选跟着执行进度一步一步往前走。
     *
     * @return 是否真的勾上了（全部完成时返回 false）
     */
    public boolean tickNext() {
        for (int i = 0; i < state.size(); i++) {
            if (!Boolean.TRUE.equals(state.get(i))) {
                state.set(i, Boolean.TRUE);
                paint();
                // 刚勾上的那一行轻轻弹一下，让"又完成一步"这件事被看见
                final TextView label = labels.get(i);
                if (label != null) {
                    label.setAlpha(0.35f);
                    label.animate().alpha(1f).setDuration(240).start();
                }
                if (cb != null) cb.onToggle(i, true);
                return true;
            }
        }
        return false;
    }

    /** 外部状态覆盖（历史回放、落盘状态回填时用） */
    public void syncDone(List<Boolean> done) {
        for (int i = 0; i < state.size(); i++) {
            state.set(i, done != null && i < done.size() && Boolean.TRUE.equals(done.get(i)));
        }
        paint();
    }

    /** 按当前状态刷新勾选框、文字样式与进度条 */
    private void paint() {
        int n = state.size();
        int doneCount = 0;
        for (int i = 0; i < n; i++) {
            boolean d = Boolean.TRUE.equals(state.get(i));
            if (d) doneCount++;

            TextView box = i < marks.size() ? marks.get(i) : null;
            if (box != null) {
                box.setTextColor(d ? Color.WHITE : Color.TRANSPARENT);
                GradientDrawable gd = Ui.roundRect(ctx, d ? Palette.c(C_DONE) : 0x00000000, 0, 5);
                gd.setStroke(Ui.dp(ctx, 1), d ? Palette.c(C_DONE) : Palette.c(0x3AFFFFFF));
                box.setBackground(gd);
            }

            TextView label = i < labels.size() ? labels.get(i) : null;
            if (label != null) {
                label.setTextColor(d ? Palette.c(C_DIM) : Palette.c(C_TEXT));
                label.setPaintFlags(d
                        ? (label.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG)
                        : (label.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG));
            }
        }

        count.setText(doneCount + "/" + n);
        count.setTextColor(doneCount == n ? Palette.c(C_DONE) : Palette.c(C_DIM));

        LayoutParams flp = (LayoutParams) fill.getLayoutParams();
        LayoutParams rlp = (LayoutParams) rest.getLayoutParams();
        flp.weight = Math.max(doneCount, 0);
        rlp.weight = Math.max(n - doneCount, 0);
        fill.setLayoutParams(flp);
        rest.setLayoutParams(rlp);
    }

    // ---------------------------------------------------------------- 兼容旧调用

    /** 旧入口：直接拿到 View，行为等同于 new TaskCardView(...) */
    public static View build(Context c, List<String> tasks, List<Boolean> done, OnToggle cb) {
        if (c == null || tasks == null || tasks.isEmpty()) return null;
        return new TaskCardView(c, tasks, done, cb);
    }
}
