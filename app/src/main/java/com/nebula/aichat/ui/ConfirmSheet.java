package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化确认面板。
 *
 * 背景：以前模型需要用户拍板时（改哪个功能、保留哪些模块、包名、签名方案…）
 * 只能把问题写在正文里，说完这一轮就结束了，用户还得自己打字再发一次。
 *
 * 现在模型改用一个确认块：
 * <pre>
 * %%CONFIRM%%
 * 单选|要改哪个功能？|设置页|聊天页|启动图
 * 多选|保留哪些模块？|登录|支付|广告
 * 文本|包名改成什么？
 * %%END%%
 * </pre>
 * App 把它渲染成卡片，流式结束（或点卡片）时弹出本面板：
 * 每项可单选/多选/填空，选完点「确认并继续」，选择结果自动拼成一条消息发回模型。
 */
public class ConfirmSheet {

    /** 一个确认项 */
    public static class Item {
        public String mode = "单选";     // 单选 / 多选 / 文本
        public String question = "";
        public List<String> options = new ArrayList<String>();
        /** 用户给出的答案（单选=1 个、多选=多个、文本=1 个） */
        public List<String> answers = new ArrayList<String>();
        /** 文本项的输入控件 */
        EditText input;
        /** 选择题的每行控件，单选时要重置同组其它行 */
        final List<Opt> opts = new ArrayList<Opt>();

        public boolean isText() {
            return mode.contains("文本") || mode.contains("填空") || mode.contains("输入");
        }

        public boolean isMulti() {
            return mode.contains("多选");
        }
    }

    /** 一个选项行涉及的控件 */
    static class Opt {
        View dot;
        TextView label;
        String text;
    }

    public interface Callback {
        /** 用户点了「确认并继续」，text 是可以直接发回模型的完整消息 */
        void onConfirmed(String text);

        /**
         * 用户选了「不用继续」那一项并点了确认：本轮到此结束。
         * 不强求每个调用方都实现，默认什么也不做（弹窗关掉即可）。
         */
        default void onStop() { }
    }

    // ---- 配色，跟聊天区保持一致 ----
    private static final int ACCENT = 0xFF5B93FF;
    private static final int CARD = 0xFF0F1722;
    private static final int CARD_STROKE = 0xFF223047;
    private static final int ITEM_FILL = 0xFF141D2B;
    private static final int ITEM_STROKE = 0xFF1F2B3D;
    private static final int TEXT = 0xFFE6EDF8;
    private static final int DIM = 0xFF8B97A9;
    private static final int FIELD = 0xFF0B1220;
    private static final int OK = 0xFF3FB950;
    private static final int WARN = 0xFFE3B341;

    // ======================================================== 协议解析

    /**
     * 宽容识别确认块。
     *
     * 早期的严格版本要求同时出现 %%CONFIRM%% 和 %%END%%，结果模型只要写错一点
     * （全角％、忘记 %%END%%、被 ``` 包起来）就整块失效 —— 用户看到的就是
     * 「模型问了一句然后就没了」。现在只要求标记 + 至少一行有实质内容。
     */
    public static boolean hasBlock(String md) {
        if (md == null) return false;
        String n = md.replace('％', '%');
        if (!n.contains("%%CONFIRM")) return false;
        for (String line : extractRows(md)) {
            String s = normLine(line);
            if (s.contains("|")) return true;
            if (s.startsWith("单选") || s.startsWith("多选") || s.startsWith("文本")
                    || s.startsWith("填空") || s.startsWith("输入")) {
                return true;
            }
        }
        return false;
    }

    /** 单行归一：全角竖线/空格转半角，去掉围栏残留 */
    static String normLine(String s) {
        if (s == null) return "";
        return s.replace('％', '%').replace('｜', '|').replace('　', ' ')
                .replace("```", "").trim();
    }

    /**
     * 取出确认块内的行。没有 %%END%% 时一直取到文本末尾，
     * 因为模型经常忘记写结束标记。
     */
    public static List<String> extractRows(String md) {
        List<String> rows = new ArrayList<String>();
        if (md == null) return rows;
        String n = md.replace('％', '%').replace("\r", "");
        int i = n.indexOf("%%CONFIRM");
        if (i < 0) return rows;
        String body = n.substring(i);
        int nl = body.indexOf('\n');
        body = nl >= 0 ? body.substring(nl + 1) : "";
        int e = body.indexOf("%%END");
        if (e >= 0) body = body.substring(0, e);
        for (String line : body.split("\n")) {
            String t = normLine(line);
            if (t.isEmpty()) continue;
            if (t.startsWith("%%")) continue;       // 跳过标记行
            if (t.startsWith("```") || t.equals("```")) continue;
            rows.add(t);
        }
        return rows;
    }

    /**
     * 逐行解析。宽容处理模型少写模式的情况：
     * 第一段不是「单选/多选/文本」时，把它当问题，后面几段当选项，默认单选。
     */
    public static List<Item> parseRows(List<String> rows) {
        List<Item> items = new ArrayList<Item>();
        if (rows == null) return items;
        for (String raw : rows) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("-") || line.startsWith("*")) line = line.substring(1).trim();
            String[] seg = line.split("\\|");
            if (seg.length == 0) continue;

            Item it = new Item();
            String head = seg[0].trim();
            boolean hasMode = head.equals("单选") || head.equals("多选") || head.equals("文本")
                    || head.equals("填空") || head.equals("输入");
            int first = hasMode ? 1 : 0;
            if (hasMode) {
                it.mode = head;
            } else {
                it.mode = "单选";
            }
            it.question = seg.length > first ? seg[first].trim() : "";
            if (it.question.isEmpty()) continue;
            for (int k = first + 1; k < seg.length; k++) {
                String o = seg[k].trim();
                if (!o.isEmpty()) it.options.add(o);
            }
            // 没有选项就不是选择题，按填空处理
            if (it.options.isEmpty()) it.mode = "文本";
            else if (!hasMode) it.mode = "单选";
            items.add(it);
        }
        return items;
    }

    /** 直接用候选行弹窗（卡片按钮走这里） */
    public static void showRows(Activity act, List<String> rows, Callback cb) {
        show(act, parseRows(rows), cb);
    }

    /** 「不用继续」这类收尾选项的判定词。只认明确的否定式，
     *  不放单独的「停止」——它可能是「停止某个服务」，会误伤。 */
    private static final String[] STOP_WORDS = {
            "不用继续", "不需要继续", "不再继续", "不继续了", "不继续",
            "不用了", "到此为止", "就到这里", "就此结束", "结束本轮", "收工"
    };

    /** 一段文本是不是在说「这事到此为止」 */
    public static boolean textMeansStop(String t) {
        if (t == null) return false;
        String v = t.replaceAll("\\s+", "");
        if (v.isEmpty()) return false;
        for (String w : STOP_WORDS) {
            if (v.contains(w)) return true;
        }
        return false;
    }

    /** 已经选中的答案里，有没有「不用继续」 */
    public static boolean isStopChoice(List<Item> items) {
        if (items == null) return false;
        for (Item it : items) {
            if (it == null) continue;
            if (it.isText()) {
                if (it.input != null && textMeansStop(it.input.getText().toString())) return true;
            } else if (it.answers != null) {
                for (String a : it.answers) {
                    if (textMeansStop(a)) return true;
                }
            }
        }
        return false;
    }

    /** 把用户的选择拼成一条可直接发送的消息 */
    public static String buildResult(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("【我的确认】以下是我对刚才几个问题的选择。"
                + "请直接开始执行（该调工具就调工具、该改代码就改代码），"
                + "不要只解释计划，也不要再问一遍：");
        int n = 0;
        for (Item it : items) {
            List<String> ans = new ArrayList<String>();
            if (it.isText()) {
                String v = it.input == null ? "" : it.input.getText().toString().trim();
                if (!v.isEmpty()) ans.add(v);
            } else {
                ans.addAll(it.answers);
            }
            if (ans.isEmpty()) continue;
            n++;
            sb.append('\n').append(it.question).append(" → ");
            for (int i = 0; i < ans.size(); i++) {
                if (i > 0) sb.append('、');
                sb.append(ans.get(i));
            }
        }
        if (n == 0) return null;
        return sb.toString();
    }

    // ======================================================== 弹窗

    public static void show(final Activity act, final List<Item> items, final Callback cb) {
        if (act == null || act.isFinishing()) return;
        if (items == null || items.isEmpty()) return;

        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.roundRect(act, Palette.c(CARD), Palette.c(CARD_STROKE), 18f));
        int p = dp(act, 16);
        root.setPadding(p, dp(act, 14), p, dp(act, 12));

        // ---------- 标题 ----------
        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(act);
        title.setText("需要你确认");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Palette.c(TEXT));
        titleRow.addView(title);

        TextView count = new TextView(act);
        count.setText("  " + items.size() + " 项");
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        count.setTextColor(Palette.c(ACCENT));
        count.setPadding(dp(act, 7), dp(act, 2), dp(act, 7), dp(act, 2));
        count.setBackground(Ui.roundRect(act, Palette.c(0x225B93FF), Palette.c(0x4D5B93FF), 8f));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.leftMargin = dp(act, 8);
        titleRow.addView(count, countLp);
        root.addView(titleRow);

        TextView hint = new TextView(act);
        hint.setText("选好后点底部「确认并继续」，我会带着这些选择接着做，不用你再打字。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hint.setTextColor(Palette.c(DIM));
        hint.setLineSpacing(dp(act, 3), 1f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(act, 6);
        hintLp.bottomMargin = dp(act, 4);
        root.addView(hint, hintLp);

        // ---------- 选项列表 ----------
        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < items.size(); i++) {
            list.addView(buildItem(act, items.get(i), i + 1), itemLp(act));
        }

        int maxH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.52f);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(act, 6);
        root.addView(scroll, scrollLp);
        scroll.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        // 内容高过上限再限制，避免小面板被拉满
        scroll.post(new Runnable() {
            @Override
            public void run() {
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                if (scroll.getHeight() > maxH) {
                    lp.height = maxH;
                    scroll.setLayoutParams(lp);
                }
            }
        });

        // ---------- 底部按钮 ----------
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(act, 14);
        root.addView(bar, barLp);

        TextView cancel = new TextView(act);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        cancel.setTextColor(Palette.c(DIM));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(act, 16), dp(act, 9), dp(act, 16), dp(act, 9));
        cancel.setBackground(Ui.roundRect(act, 0x00000000, Palette.c(0xFF2A3A4D), 12f));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        bar.addView(cancel);

        TextView ok = new TextView(act);
        ok.setText("确认并继续");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setTextColor(Palette.c(0xFFFFFFFF));
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), dp(act, 10));
        ok.setBackground(Ui.roundRect(act, Palette.c(ACCENT), 0x00000000, 12f));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = dp(act, 10);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 校验：每道选择题至少选一个
                for (int i = 0; i < items.size(); i++) {
                    Item it = items.get(i);
                    if (it.isText()) continue;
                    if (it.answers.isEmpty()) {
                        android.widget.Toast.makeText(act,
                                "第 " + (i + 1) + " 项还没选：" + it.question,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                // 选了「不用继续」并确认：本轮就地结束，不拼消息、不发给模型，
                // 否则模型收到「不用继续」还会再解释一遍，等于没停
                boolean stop = isStopChoice(items);
                String text = buildResult(items);
                if (text == null && !stop) {
                    android.widget.Toast.makeText(act, "还没有任何选择",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                if (cb == null) return;
                if (stop) cb.onStop();
                else cb.onConfirmed(text);
            }
        });
        bar.addView(ok, okLp);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.92f);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
        dialog.show();
    }

    private static LinearLayout.LayoutParams itemLp(Activity act) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 8);
        return lp;
    }

    /** 单个确认项：序号 + 模式标签 + 问题 + 选择控件 */
    private static View buildItem(final Activity act, final Item it, int index) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.roundRect(act, Palette.c(ITEM_FILL), Palette.c(ITEM_STROKE), 13f));
        int ip = dp(act, 12);
        box.setPadding(ip, dp(act, 10), ip, dp(act, 11));

        // 第一行：序号 + 模式
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView no = new TextView(act);
        no.setText(String.valueOf(index));
        no.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        no.setTypeface(Typeface.DEFAULT_BOLD);
        no.setTextColor(Palette.c(ACCENT));
        no.setGravity(Gravity.CENTER);
        no.setWidth(dp(act, 17));
        no.setHeight(dp(act, 17));
        no.setBackground(Ui.roundRect(act, Palette.c(0x225B93FF), Palette.c(0x4D5B93FF), 6f));
        head.addView(no);

        String modeText;
        int modeColor;
        if (it.isText()) {
            modeText = "填空";
            modeColor = Palette.c(WARN);
        } else if (it.isMulti()) {
            modeText = "多选";
            modeColor = Palette.c(OK);
        } else {
            modeText = "单选";
            modeColor = Palette.c(ACCENT);
        }
        TextView mode = new TextView(act);
        mode.setText(modeText);
        mode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        mode.setTextColor(modeColor);
        mode.setPadding(dp(act, 6), dp(act, 1), dp(act, 6), dp(act, 1));
        mode.setBackground(Ui.roundRect(act, (modeColor & 0x00FFFFFF) | Palette.c(0x22000000),
                (modeColor & 0x00FFFFFF) | Palette.c(0x55000000), 5f));
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.leftMargin = dp(act, 7);
        head.addView(mode, modeLp);
        box.addView(head);

        // 问题
        TextView q = new TextView(act);
        q.setText(it.question);
        q.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        q.setTextColor(Palette.c(TEXT));
        q.setLineSpacing(dp(act, 3), 1f);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = dp(act, 8);
        box.addView(q, qLp);

        if (it.isText()) {
            // 填空
            EditText et = new EditText(act);
            it.input = et;
            et.setHint("在这里输入…");
            et.setHintTextColor(Palette.c(0xFF5D6A7E));
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            et.setTextColor(Palette.c(TEXT));
            et.setTypeface(Typeface.MONOSPACE);
            et.setBackground(Ui.roundRect(act, Palette.c(FIELD), Palette.c(0xFF26364D), 10f));
            int ep = dp(act, 10);
            et.setPadding(ep, dp(act, 8), ep, dp(act, 8));
            et.setSingleLine(false);
            et.setMaxLines(3);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            etLp.topMargin = dp(act, 9);
            box.addView(et, etLp);
        } else {
            // 选项：每行一个，左侧自绘指示器（不依赖 ☑ 之类字形，避免变方框）
            for (int k = 0; k < it.options.size(); k++) {
                box.addView(buildOption(act, it, it.options.get(k)), choiceLp(act, k == 0));
            }
        }
        return box;
    }

    private static LinearLayout.LayoutParams choiceLp(Activity act, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, first ? 9 : 6);
        return lp;
    }

    /** 一个可选行：指示器 + 文字，点整行切换 */
    private static View buildOption(final Activity act, final Item it, final String option) {
        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = dp(act, 10), ph = dp(act, 10);
        row.setPadding(ph, pv, ph, pv);

        final View dot = new View(act);
        final boolean multi = it.isMulti();
        int size = dp(act, 16);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(size, size);
        dotLp.rightMargin = dp(act, 9);
        row.addView(dot, dotLp);

        final TextView label = new TextView(act);
        label.setText(option);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTextColor(Palette.c(0xFFC9D6E8));
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Opt rec = new Opt();
        rec.dot = dot;
        rec.label = label;
        rec.text = option;
        it.opts.add(rec);

        final boolean[] on = {it.answers.contains(option)};
        paintOption(dot, label, multi, on[0]);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (multi) {
                    if (on[0]) {
                        on[0] = false;
                        it.answers.remove(option);
                    } else {
                        on[0] = true;
                        it.answers.add(option);
                    }
                    paintOption(dot, label, true, on[0]);
                } else {
                    // 单选：按登记表重置同组其它行（不能靠遍历父容器判断，
                    // 标题行也是两子视图的 LinearLayout，会被误伤）
                    for (Opt o : it.opts) paintOption(o.dot, o.label, false, false);
                    on[0] = true;
                    it.answers.clear();
                    it.answers.add(option);
                    paintOption(dot, label, false, true);
                }
            }
        });
        return row;
    }

    /** 指示器样式：单选用圆、多选用圆角方；选中填充主色并淡染整行 */
    private static void paintOption(View dot, TextView label, boolean multi,
                                    boolean checked) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(multi ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
        d.setCornerRadius(dp(dot.getContext(), multi ? 4 : 0));
        if (checked) {
            d.setColor(Palette.c(ACCENT));
            d.setStroke(dp(dot.getContext(), 1.5f), Palette.c(ACCENT));
        } else {
            d.setColor(Palette.c(0x00000000));
            d.setStroke(dp(dot.getContext(), 1.5f), Palette.c(0xFF3A4A61));
        }
        dot.setBackground(d);
        label.setTextColor(checked ? Palette.c(0xFFE9F0FF) : Palette.c(0xFFC9D6E8));
        // 整行底色淡染，选中更明显
        View row = (View) label.getParent();
        if (row != null) {
            row.setBackground(checked
                    ? Ui.roundRect(row.getContext(), Palette.c(0x225B93FF), Palette.c(0x4D5B93FF), 10f)
                    : Ui.roundRect(row.getContext(), 0x00000000, 0x00000000, 10f));
        }
    }

    private static int dp(Activity act, float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int dp(android.content.Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
