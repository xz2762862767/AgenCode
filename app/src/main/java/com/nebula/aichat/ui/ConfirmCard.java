package com.nebula.aichat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 内联确认卡片：「AI 需要你的确认」这件事整体收在一个框里。
 *
 * <p>以前是「卡片只做提示 → 点一下弹出面板 → 在面板里勾选」的两段式，
 * 用户得点两次、还要在弹窗和聊天区之间来回看。现在改成一段式：
 * 问题、选项、自定义输入、补充说明、确认按钮全在同一张卡里，一次点完。</p>
 *
 * <p>卡片是纯 View 拼的，不依赖 Activity，可以放进消息流跟正文一起滚动。
 * 选择状态存在 {@link State} 里，由渲染器按「第几个确认块」持有——流式输出时
 * 卡片会被重建（内容在增长），但重建不会丢用户已经勾好的选项。</p>
 */
public class ConfirmCard {

    /** 卡片回调 */
    public interface Listener {
        /**
         * 用户点了「确认」，message 是拼好的、可直接发回模型的整段文字。
         *
         * @return true = 宿主收下了（AI 正在生成、或这份选择刚发过），卡片转只读；
         *         false = 宿主没接（流式还没停），卡片保持可点，别让用户白勾一场
         */
        boolean onSubmit(String message);

        /** 长按卡片：打开完整面板（逐项精修，兼容旧交互） */
        void onOpenSheet();
    }

    /** 一张确认卡的选择状态。可跨视图重建存活。 */
    public static class State {
        /** 每题已选中的选项文本（单选最多 1 个） */
        final List<List<String>> picked = new ArrayList<List<String>>();
        /** 每题的「其他」自定义输入 */
        final List<String> custom = new ArrayList<String>();
        /** 用户补充说明 */
        String note = "";
        /** 是否已确认（确认后卡片转只读） */
        boolean confirmed;
        /** 确认后的答案摘要，重建时用来还原只读态 */
        String summary = "";

        void ensure(int n) {
            while (picked.size() < n) picked.add(new ArrayList<String>());
            while (custom.size() < n) custom.add("");
        }

        List<String> at(int i) {
            ensure(i + 1);
            return picked.get(i);
        }

        String customAt(int i) {
            ensure(i + 1);
            String s = custom.get(i);
            return s == null ? "" : s;
        }

        /** 这一题的答案：选项 + 自定义输入 */
        List<String> answerOf(int i, ConfirmSheet.Item it) {
            List<String> out = new ArrayList<String>();
            if (it.isText()) {
                String v = customAt(i).trim();
                if (!v.isEmpty()) out.add(v);
                return out;
            }
            for (String s : at(i)) if (s != null && !s.trim().isEmpty()) out.add(s.trim());
            String c = customAt(i).trim();
            if (!c.isEmpty()) out.add(c);
            return out;
        }

        boolean complete(List<ConfirmSheet.Item> items) {
            for (int i = 0; i < items.size(); i++) {
                if (answerOf(i, items.get(i)).isEmpty()) return false;
            }
            return true;
        }
    }

    // ---------- 配色：跟工具框（亮绿）、翻译框（灰）错开，走「待你拍板」的金色 ----------
    private static final int CARD_TOP = 0xFF121821;
    private static final int CARD_BOTTOM = 0xFF0B1017;
    private static final int STROKE = 0x3DE8C06A;
    private static final int GOLD = 0xFFE8C06A;
    private static final int GOLD_DEEP = 0xFF241C05;
    private static final int TEXT = 0xFFE4ECF7;
    private static final int DIM = 0xFF8B97A9;
    private static final int TITLE = 0xFFDDE7F4;
    private static final int OPT_BG = 0x0DFFFFFF;
    private static final int OPT_STROKE = 0x1CFFFFFF;
    private static final int OPT_SEL_BG = 0x1FE8C06A;
    private static final int OPT_SEL_STROKE = 0x99E8C06A;
    private static final int FIELD = 0xFF0A1017;
    private static final int FIELD_STROKE = 0xFF243141;
    private static final int GREEN = 0xFF3FB950;
    private static final int CHIP_BG = 0x1FC3B0FF;
    private static final int CHIP_TX = 0xFFC3B0FF;

    public static View build(Context ctx, List<ConfirmSheet.Item> items, State st,
                             Listener l, float baseSp) {
        return new Card(ctx, items, st, l, baseSp).root;
    }

    /** 把选择拼成一条可直接发送的消息；没选任何东西返回 null */
    public static String buildMessage(List<ConfirmSheet.Item> items, State st) {
        StringBuilder sb = new StringBuilder();
        sb.append("【我的确认】以下是我对刚才几个问题的选择。"
                + "请直接开始执行（该调工具就调工具、该改代码就改代码），"
                + "不要只解释计划，也不要再问一遍：");
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            ConfirmSheet.Item it = items.get(i);
            List<String> ans = st.answerOf(i, it);
            if (ans.isEmpty()) continue;
            n++;
            sb.append('\n').append(it.question).append(" → ");
            for (int k = 0; k < ans.size(); k++) {
                if (k > 0) sb.append('、');
                sb.append(ans.get(k));
            }
        }
        if (n == 0) return null;
        String note = st.note == null ? "" : st.note.trim();
        if (!note.isEmpty()) sb.append("\n补充说明：").append(note);
        return sb.toString();
    }

    /** 一行选项的控件组，切换选中态时原地改样式，不重建视图 */
    private static final class OptRow {
        LinearLayout row;
        View dot;
        TextView title;
        TextView desc;
        String text;
    }

    private static final class Card {
        private final Context ctx;
        private final List<ConfirmSheet.Item> items;
        private final State st;
        private final Listener listener;
        private final float baseSp;

        private final LinearLayout root;
        private final LinearLayout bodyBox;
        private final LinearLayout actionRow;
        private final LinearLayout doneRow;
        private final TextView chip;
        private final TextView okBtn;
        private final List<List<OptRow>> rows = new ArrayList<List<OptRow>>();
        private LinearLayout noteBox;
        private EditText noteEt;

        Card(Context ctx, List<ConfirmSheet.Item> items, State st, Listener l, float baseSp) {
            this.ctx = ctx;
            this.items = items == null ? new ArrayList<ConfirmSheet.Item>() : items;
            this.st = st == null ? new State() : st;
            this.listener = l;
            this.baseSp = baseSp;
            this.st.ensure(this.items.size());

            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(cardBg());
            int p = dp(14);
            root.setPadding(p, dp(12), p, dp(12));

            // ---------- 标题行：金点 + 「AI 需要你的确认」 + 事件名 chip ----------
            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            View dot = new View(ctx);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(GOLD);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
            dotLp.rightMargin = dp(8);
            head.addView(dot, dotLp);

            TextView t = new TextView(ctx);
            t.setText("AI 需要你的确认");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12.5f, baseSp - 1.5f));
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(GOLD);
            head.addView(t);

            head.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));

            chip = new TextView(ctx);
            chip.setText("askUserQuestion");
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(9.5f, baseSp - 4.5f));
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setTextColor(CHIP_TX);
            chip.setPadding(dp(7), dp(2), dp(7), dp(3));
            chip.setBackground(Ui.roundRect(ctx, CHIP_BG, 0x33C3B0FF, 6f));
            head.addView(chip);
            root.addView(head);

            // ---------- 题目区 ----------
            bodyBox = new LinearLayout(ctx);
            bodyBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = dp(11);
            root.addView(bodyBox, bodyLp);
            for (int i = 0; i < this.items.size(); i++) buildItem(i, this.items.get(i));

            // ---------- 补充说明（默认收起） ----------
            noteBox = buildNoteBox();
            root.addView(noteBox);

            // ---------- 底部：补充 / 确认 ----------
            actionRow = new LinearLayout(ctx);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actLp.topMargin = dp(12);
            root.addView(actionRow, actLp);

            TextView more = new TextView(ctx);
            more.setText("补充");
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11.5f, baseSp - 2.5f));
            more.setTextColor(0xFFB9C6D6);
            more.setGravity(Gravity.CENTER);
            more.setBackground(Ui.roundRect(ctx, 0x14FFFFFF, 0x26FFFFFF, 11f));
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleNote(); }
            });
            actionRow.addView(more, new LinearLayout.LayoutParams(dp(76), dp(38)));

            okBtn = new TextView(ctx);
            okBtn.setText("确认");
            okBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12.5f, baseSp - 1.5f));
            okBtn.setTypeface(Typeface.DEFAULT_BOLD);
            okBtn.setTextColor(GOLD_DEEP);
            okBtn.setGravity(Gravity.CENTER);
            okBtn.setBackground(Ui.roundRect(ctx, GOLD, 0, 11f));
            okBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { submit(); }
            });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            olp.leftMargin = dp(9);
            actionRow.addView(okBtn, olp);

            // ---------- 已确认只读行 ----------
            doneRow = new LinearLayout(ctx);
            doneRow.setOrientation(LinearLayout.HORIZONTAL);
            doneRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView okMark = new TextView(ctx);
            okMark.setText("✓");
            okMark.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11f, baseSp - 3f));
            okMark.setTypeface(Typeface.DEFAULT_BOLD);
            okMark.setTextColor(GREEN);
            okMark.setPadding(0, 0, dp(7), 0);
            doneRow.addView(okMark);
            TextView okText = new TextView(ctx);
            okText.setText("已确认，正在按你的选择继续");
            okText.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11f, baseSp - 3f));
            okText.setTextColor(0xFF7FD08B);
            okText.setLineSpacing(dp(2), 1f);
            doneRow.addView(okText, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(11);
            root.addView(doneRow, dlp);

            // 长按整卡：打开完整面板（喜欢逐项精修的走这条路）
            root.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (listener != null) listener.onOpenSheet();
                    return true;
                }
            });

            refreshFooter();
        }

        // ================================================== 单题

        private void buildItem(int index, ConfirmSheet.Item it) {
            rows.add(new ArrayList<OptRow>());

            if (items.size() > 1) {
                LinearLayout h = new LinearLayout(ctx);
                h.setOrientation(LinearLayout.HORIZONTAL);
                h.setGravity(Gravity.CENTER_VERTICAL);
                if (index > 0) h.setPadding(0, dp(6), 0, 0);

                TextView no = new TextView(ctx);
                no.setText(String.valueOf(index + 1));
                no.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(9.5f, baseSp - 4.5f));
                no.setTypeface(Typeface.DEFAULT_BOLD);
                no.setTextColor(GOLD);
                no.setGravity(Gravity.CENTER);
                no.setBackground(Ui.roundRect(ctx, 0x1FE8C06A, 0x4DE8C06A, 5f));
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(16), dp(16));
                nlp.rightMargin = dp(7);
                h.addView(no, nlp);

                TextView mode = new TextView(ctx);
                mode.setText(it.isText() ? "填空" : (it.isMulti() ? "多选" : "单选"));
                mode.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(9.5f, baseSp - 4.5f));
                mode.setTextColor(DIM);
                h.addView(mode);
                bodyBox.addView(h);
            }

            TextView q = new TextView(ctx);
            q.setText(it.question);
            q.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12.5f, baseSp - 1.5f));
            q.setTextColor(TITLE);
            q.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.topMargin = items.size() > 1 ? dp(5) : dp(9);
            bodyBox.addView(q, qlp);

            if (it.isText()) {
                // 纯填空项：一个多行输入框就是全部交互
                EditText et = field("输入你的答案…", 2);
                bindField(et, index);
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                elp.topMargin = dp(9);
                bodyBox.addView(et, elp);
                return;
            }

            for (int k = 0; k < it.options.size(); k++) {
                OptRow or = buildOption(index, it, it.options.get(k));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(k == 0 ? 9 : 7);
                bodyBox.addView(or.row, lp);
                rows.get(index).add(or);
            }

            // 「其他」：始终留一个自定义入口，模型没想到的选项也不用打第二轮
            EditText other = field("其他 / 输入自定义答案", 1);
            bindField(other, index);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.topMargin = dp(7);
            bodyBox.addView(other, olp);
        }

        private OptRow buildOption(final int index, final ConfirmSheet.Item it, String raw) {
            final OptRow or = new OptRow();
            or.text = raw;

            String[] parts = splitOption(raw);
            or.row = new LinearLayout(ctx);
            or.row.setOrientation(LinearLayout.HORIZONTAL);
            or.row.setGravity(Gravity.CENTER_VERTICAL);
            int hp = dp(11);
            or.row.setPadding(hp, dp(9), hp, dp(9));

            // 指示器全部自绘，不依赖字形（单选圆点 / 多选圆角方块）
            or.dot = new View(ctx);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(15), dp(15));
            dlp.rightMargin = dp(10);
            or.row.addView(or.dot, dlp);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);

            or.title = new TextView(ctx);
            or.title.setText(parts[0]);
            or.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12.3f, baseSp - 1.8f));
            or.title.setTextColor(TEXT);
            or.title.setLineSpacing(dp(2), 1f);
            col.addView(or.title);

            or.desc = null;
            if (parts[1].length() > 0) {
                or.desc = new TextView(ctx);
                or.desc.setText(parts[1]);
                or.desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10.8f, baseSp - 3.2f));
                or.desc.setTextColor(DIM);
                or.desc.setLineSpacing(dp(2), 1f);
                or.desc.setMaxLines(3);
                or.desc.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams djlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                djlp.topMargin = dp(3);
                col.addView(or.desc, djlp);
            }
            or.row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            paintOption(or, it.isMulti(), st.at(index).contains(raw));

            or.row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (st.confirmed) return;
                    toggle(index, it, or.text);
                }
            });
            return or;
        }

        /** 选项小卡的选中/未选样式 */
        private void paintOption(OptRow or, boolean multi, boolean sel) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(11));
            bg.setColor(sel ? OPT_SEL_BG : OPT_BG);
            bg.setStroke(Math.max(1, dp(1)), sel ? OPT_SEL_STROKE : OPT_STROKE);
            or.row.setBackground(bg);

            or.title.setTextColor(sel ? 0xFFF3E6C4 : TEXT);
            if (or.desc != null) or.desc.setTextColor(sel ? 0xFFB6A780 : DIM);

            if (!sel) {
                GradientDrawable d = new GradientDrawable();
                d.setShape(multi ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
                d.setCornerRadius(multi ? dp(4) : 0);
                d.setColor(0x00000000);
                d.setStroke(Math.max(1, dp(1.4f)), 0x4DFFFFFF);
                or.dot.setBackground(d);
            } else {
                // 选中：金色实心 + 深色内芯（圆点/方块都是同一套画法，大小一致）
                GradientDrawable fill = new GradientDrawable();
                fill.setShape(multi ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
                fill.setCornerRadius(multi ? dp(4) : 0);
                fill.setColor(GOLD);
                GradientDrawable inner = new GradientDrawable();
                inner.setShape(GradientDrawable.OVAL);
                inner.setColor(GOLD_DEEP);
                LayerDrawable ld = new LayerDrawable(new GradientDrawable[]{fill, inner});
                int inset = dp(4);
                ld.setLayerInset(1, inset, inset, inset, inset);
                or.dot.setBackground(ld);
            }
        }

        private void toggle(int index, ConfirmSheet.Item it, String text) {
            List<String> cur = st.at(index);
            if (cur.contains(text)) {
                cur.remove(text);
            } else {
                if (!it.isMulti()) cur.clear();
                cur.add(text);
            }
            // 只改样式，不重建视图：滚动位置和输入框内容都不会跳
            for (OptRow r : rows.get(index)) {
                paintOption(r, it.isMulti(), cur.contains(r.text));
            }
            refreshFooter();
        }

        // ================================================== 输入

        private EditText field(String hint, int maxLines) {
            EditText et = new EditText(ctx);
            et.setHint(hint);
            et.setHintTextColor(0xFF5C6A7E);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11.8f, baseSp - 2.3f));
            et.setTextColor(TEXT);
            et.setBackground(Ui.roundRect(ctx, FIELD, FIELD_STROKE, 10f));
            int p = dp(10);
            et.setPadding(p, dp(8), p, dp(8));
            et.setSingleLine(maxLines <= 1);
            et.setMaxLines(maxLines);
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | (maxLines > 1 ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
            return et;
        }

        private void bindField(EditText et, final int index) {
            String saved = st.customAt(index);
            if (!saved.isEmpty()) {
                et.setText(saved);
                et.setSelection(saved.length());
            }
            if (st.confirmed) {
                et.setEnabled(false);
                et.setTextColor(0xFF98A5B6);
            }
            et.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    st.ensure(index + 1);
                    st.custom.set(index, s == null ? "" : s.toString());
                }
            });
        }

        // ================================================== 补充说明

        private LinearLayout buildNoteBox() {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(9);
            box.setLayoutParams(lp);

            noteEt = field("补充说明（会一起发给 AI）…", 3);
            noteEt.setText(st.note == null ? "" : st.note);
            if (st.confirmed) {
                noteEt.setEnabled(false);
                noteEt.setTextColor(0xFF98A5B6);
            }
            noteEt.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    st.note = s == null ? "" : s.toString();
                }
            });
            box.addView(noteEt, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return box;
        }

        private void toggleNote() {
            if (noteBox == null || st.confirmed) return;
            boolean show = noteBox.getVisibility() != View.VISIBLE;
            noteBox.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && noteEt != null) {
                noteEt.requestFocus();
                noteEt.setSelection(noteEt.getText().length());
            }
        }

        // ================================================== 底部状态

        private void refreshFooter() {
            boolean done = st.confirmed;
            actionRow.setVisibility(done ? View.GONE : View.VISIBLE);
            doneRow.setVisibility(done ? View.VISIBLE : View.GONE);
            if (done) {
                chip.setText("已确认");
                chip.setTextColor(GREEN);
                chip.setBackground(Ui.roundRect(ctx, 0x1F3FB950, 0x333FB950, 6f));
            } else {
                // 还没选齐：确认键压暗，选齐后点亮，用户一眼知道还差什么
                boolean ready = st.complete(items);
                okBtn.setAlpha(ready ? 1f : 0.45f);
                okBtn.setText(ready ? "确认" : "请先选择");
            }
        }

        private void submit() {
            if (st.confirmed) return;
            if (!st.complete(items)) {
                // 找出第一个没答的，直接说出是第几题，别只丢一句「请选择」
                for (int i = 0; i < items.size(); i++) {
                    if (st.answerOf(i, items.get(i)).isEmpty()) {
                        String where = items.size() > 1 ? ("第 " + (i + 1) + " 题") : "上面的选项";
                        android.widget.Toast.makeText(ctx, "还没选：" + where, android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                return;
            }
            String msg = buildMessage(items, st);
            if (msg == null) return;
            if (listener == null) return;
            if (!listener.onSubmit(msg)) return;

            st.confirmed = true;
            StringBuilder sum = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                List<String> ans = st.answerOf(i, items.get(i));
                if (ans.isEmpty()) continue;
                if (sum.length() > 0) sum.append(" · ");
                sum.append(ans.get(0));
                if (ans.size() > 1) sum.append(" 等 ").append(ans.size()).append(" 项");
            }
            st.summary = sum.toString();

            if (noteBox != null) noteBox.setVisibility(View.GONE);
            if (okBtn != null) okBtn.setAlpha(1f);
            refreshFooter();
        }

        // ================================================== 底图 / 单位

        private GradientDrawable cardBg() {
            GradientDrawable g = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{CARD_TOP, CARD_BOTTOM});
            g.setCornerRadius(dp(16));
            g.setStroke(Math.max(1, dp(1)), STROKE);
            return g;
        }

        private int dp(float v) {
            return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    /**
     * 选项文本拆成「标题 + 描述」。模型约定用 {@code 标题::描述} 写，
     * 也兼容它偷懒写成「标题 — 描述」或长句逗号分隔的情况。
     */
    static String[] splitOption(String raw) {
        String s = raw == null ? "" : raw.trim();
        String[] out = new String[]{s, ""};
        int i = s.indexOf("::");
        if (i > 0) {
            out[0] = s.substring(0, i).trim();
            out[1] = s.substring(i + 2).trim();
            return out;
        }
        for (String sep : new String[]{" —— ", " ——", "——", " — ", " － "}) {
            int k = s.indexOf(sep);
            if (k > 0 && k < s.length()) {
                out[0] = s.substring(0, k).trim();
                out[1] = s.substring(k + sep.length()).trim();
                return out;
            }
        }
        // 没分隔符但很长：前半句当标题，后半句当描述，避免一行糊成一片
        if (s.length() > 24) {
            int k = s.indexOf('，');
            if (k < 4) k = s.indexOf(',');
            if (k >= 4 && k < 20 && s.length() - k > 6) {
                out[0] = s.substring(0, k).trim();
                out[1] = s.substring(k + 1).trim();
            }
        }
        return out;
    }
}
