package com.nebula.aichat.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.nebula.aichat.R;

/**
 * 轻量 Markdown 渲染：把一段 Markdown 文本渲染成一组 View 塞进容器。
 * 支持：标题、无序/有序列表、引用、分割线、围栏代码块（带复制）、表格兜底、行内粗体/斜体/代码/删除线/链接。
 */
public class MarkdownRenderer {

    private final Context ctx;
    private final LinearLayout container;
    private final float baseSp;
    private final int colorText;
    private final int colorDim;
    private final int colorAccent;
    private final int colorCodeBg;

    /** 加粗重点句超过这个字数就不铺底色：整段加粗铺出来是一大块色，反而更乱 */
    private static final int BOLD_HL_MAX = 40;

    /** 长回答整体折叠：超过约一屏的 60% 就只显示前面部分 */
    private boolean autoCollapse = false;

    /** 增量渲染状态：上一帧的块 key 与对应视图（一一对应） */
    private final java.util.List<String> blockKeys = new java.util.ArrayList<String>();
    /** 完整 key（含文本），代码块之外用它判断内容是否变化 */
    private final java.util.List<String> blockFull = new java.util.ArrayList<String>();
    private final java.util.List<View> blockViews = new java.util.ArrayList<View>();
    private View lastBlockView;
    /** 已折叠过：结构被改过，下次渲染必须整体重来 */
    private boolean collapsedOnce = false;
    private boolean doneCollapse = false;

    public MarkdownRenderer(Context ctx, LinearLayout container, float baseSp) {
        this.ctx = ctx;
        this.container = container;
        this.baseSp = baseSp;
        this.colorText = Palette.text(ctx);
        this.colorDim = Palette.dim(ctx);
        this.colorAccent = Palette.accent(ctx);
        this.colorCodeBg = Palette.code(ctx);
    }

    /**
     * 渲染入口（两阶段：先解析成块，再增量绘制）。
     * 流式时每次只重建「变化的那几块」，已完成的小节原样留在屏幕上，
     * 不再每 70ms 把整篇回答拆掉重搭——那是之前抖动的根源。
     */
    public void render(String md) {
        if (md == null) {
            resetBlocks();
            return;
        }
        // 折叠过的结构已经变了（多了「展开全文」按钮、部分块被隐藏），只能整体重来
        if (collapsedOnce) resetBlocks();
        applyBlocks(parseBlocks(md));
        // 完成态才折叠；流式期间 autoCollapse 为 false，不会打断阅读
        if (autoCollapse) applyCollapse();
    }

    private void resetBlocks() {
        container.removeAllViews();
        blockKeys.clear();
        blockFull.clear();
        blockViews.clear();
        lastBlockView = null;
        collapsedOnce = false;
        doneCollapse = false;
    }

    private java.util.List<Block> parseBlocks(String md) {
        java.util.List<Block> out = new java.util.ArrayList<Block>();
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        StringBuilder para = new StringBuilder();

        while (i < lines.length) {
            String raw = lines[i];
            String t = raw.trim();

            // 围栏代码块
            if (t.startsWith("```") || t.startsWith("~~~")) {
                flushPara(para, out);
                String fence = t.substring(0, 3);
                String lang = t.length() > 3 ? t.substring(3).trim() : "";
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith(fence)) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                if (i < lines.length) i++; // 跳过结束围栏
                // 模型有时把确认块包在围栏里，这种情况必须按确认卡片渲染，
                // 否则用户看到一堆 %%CONFIRM%% 原文，还得自己照着打字
                if (ConfirmSheet.hasBlock(code.toString())) {
                    out.add(Block.confirm(ConfirmSheet.extractRows(code.toString())));
                } else {
                    out.add(Block.code(lang, code.toString()));
                }
                continue;
            }

            // 空行 -> 段落结束
            if (t.isEmpty()) {
                flushPara(para, out);
                i++;
                continue;
            }

            // 分割线
            if (t.equals("---") || t.equals("***") || t.equals("___")) {
                flushPara(para, out);
                out.add(Block.divider());
                i++;
                continue;
            }

            // 标题
            if (t.startsWith("#")) {
                flushPara(para, out);
                int level = 0;
                while (level < t.length() && t.charAt(level) == '#') level++;
                if (level <= 6) {
                    out.add(Block.head(level, t.substring(level).trim()));
                    i++;
                    continue;
                }
            }

            // 无序列表
            if (isBullet(t)) {
                flushPara(para, out);
                while (i < lines.length && isBullet(lines[i].trim())) {
                    out.add(Block.bullet(lines[i].trim().substring(2).trim()));
                    i++;
                }
                continue;
            }

            // 有序列表
            if (isOrdered(t)) {
                flushPara(para, out);
                while (i < lines.length && isOrdered(lines[i].trim())) {
                    out.add(Block.ordered(lines[i].trim()));
                    i++;
                }
                continue;
            }

            // 引用
            if (t.startsWith(">")) {
                flushPara(para, out);
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String q = lines[i].trim().substring(1).trim();
                    out.add(Block.quote(q));
                    i++;
                }
                continue;
            }

            // 修改清单卡片：%%CHANGES%% … %%END%%
            if (t.startsWith("%%CHANGES%%")) {
                flushPara(para, out);
                java.util.List<String> rows = new java.util.ArrayList<String>();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("%%END%%")) {
                    String r = lines[i].trim();
                    if (!r.isEmpty()) rows.add(r);
                    i++;
                }
                if (i < lines.length) i++;
                out.add(Block.changes(rows));
                continue;
            }

            // 确认块：%%CONFIRM%% … %%END%%，渲染成可点开的确认卡片
            if (t.replace('％', '%').startsWith("%%CONFIRM")) {
                flushPara(para, out);
                java.util.List<String> rows = new java.util.ArrayList<String>();
                boolean closed = false;
                i++;
                while (i < lines.length) {
                    String r = lines[i].trim();
                    if (r.replace('％', '%').startsWith("%%END")) {
                        closed = true;
                        i++;
                        break;
                    }
                    if (!r.isEmpty()) rows.add(r);
                    i++;
                }
                // 流式输出中途块还没闭合：先给个占位提示，
                // 不要把 %%CONFIRM%% 这类协议标记直接暴露给用户。
                // 但模型常常压根不写 %%END%%，所以只要攒到内容就直接出卡片。
                if (!rows.isEmpty()) out.add(Block.confirm(rows));
                else if (!closed) out.add(Block.para("（正在整理确认项…）"));
                continue;
            }

            // 表格兜底：连续包含 | 的行按等宽文本展示
            if (t.startsWith("|") && t.endsWith("|")) {
                flushPara(para, out);
                StringBuilder tbl = new StringBuilder();
                while (i < lines.length
                        && lines[i].trim().startsWith("|")
                        && lines[i].trim().endsWith("|")) {
                    tbl.append(lines[i].trim()).append('\n');
                    i++;
                }
                out.add(Block.table(tbl.toString()));
                continue;
            }

            // 普通段落
            if (para.length() > 0) para.append('\n');
            para.append(raw.trim().isEmpty() ? "" : raw);
            i++;
        }
        flushPara(para, out);
        // 给确认块编号：同一段回答里第几张确认卡，用它绑定/还原选择状态
        int cfmSeq = 0;
        for (Block b : out) {
            if ("cfm".equals(b.kind)) b.seq = cfmSeq++;
        }
        return out;
    }

    /** 完成态（非流式）时开启整体折叠，避免长回答一次铺满好多屏 */
    public void setAutoCollapse(boolean on) {
        this.autoCollapse = on;
    }

    /**
     * 收尾时只把代码块从「跟着流式展开」切成「N 行预览」，
     * 不做整篇折叠；时间线分段渲染也走这里，保证两种路径表现一致。
     */
    public void collapseCodeBlocks() {
        if (doneCollapse) return;
        doneCollapse = true;
        for (int i = 0; i < blockViews.size(); i++) {
            Object tag = blockViews.get(i).getTag();
            if (tag instanceof CodeBlockHolder) {
                CodeBlockHolder h = (CodeBlockHolder) tag;
                if (h.expanded) {
                    h.expanded = false;
                    h.dirty = true;
                    h.relayout();
                }
            }
        }
    }

    /** 重新计算折叠；渲染是异步布局，需等一帧拿到实际高度 */
    public void applyCollapse() {
        if (!autoCollapse) return;
        collapseCodeBlocks();
        container.post(new Runnable() {
            @Override
            public void run() {
                collapseIfLong();
            }
        });
    }

    private void collapseIfLong() {
        final int n = container.getChildCount();
        if (n < 4) return;
        // 有确认卡就不折叠：选项被「展开全文」藏起来，用户根本没法继续
        for (int i = 0; i < n; i++) {
            View v = container.getChildAt(i);
            if (v != null && "nebula_confirm".equals(v.getTag())) return;
        }
        int limit = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 1.15f);
        int total = 0;
        for (int i = 0; i < n; i++) total += blockHeight(i);
        if (total <= limit) return;

        int acc = 0;
        int cut = n;
        for (int i = 0; i < n; i++) {
            int h = blockHeight(i);
            if (acc + h > limit) {
                cut = i;
                break;
            }
            acc += h;
        }
        // 别把一组「说明 + 代码」切开：若第一个被隐藏的块是代码块，把它前面那一段也一起收起
        while (cut > 2 && cut < n && isCodeAt(cut)) cut--;
        if (cut < 2) cut = 2;
        if (cut >= n) return;

        final int hidden = n - cut;
        collapsedOnce = true;
        final java.util.List<View> hiddenViews = new java.util.ArrayList<>();
        for (int i = cut; i < n; i++) {
            View v = container.getChildAt(i);
            v.setVisibility(View.GONE);
            hiddenViews.add(v);
        }

        TextView more = new TextView(ctx);
        more.setText("展开全文（还有 " + hidden + " 段）▾");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12f, baseSp - 3f));
        more.setTextColor(colorAccent);
        more.setGravity(Gravity.CENTER);
        more.setBackgroundResource(R.drawable.card_bg);
        int px = (int) (10 * ctx.getResources().getDisplayMetrics().density);
        int py = (int) (7 * ctx.getResources().getDisplayMetrics().density);
        more.setPadding(px, py, px, py);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (View hv : hiddenViews) hv.setVisibility(View.VISIBLE);
                container.removeView(v);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        container.addView(more, lp);
    }

    private boolean isCodeAt(int i) {
        if (i < 0 || i >= blockViews.size()) return false;
        return blockViews.get(i).getTag() instanceof CodeBlockHolder;
    }

    private int blockHeight(int i) {
        View v = container.getChildAt(i);
        if (v == null || v.getVisibility() == View.GONE) return 0;
        int h = v.getHeight();
        if (h <= 0) h = v.getMeasuredHeight();
        int bm = 0;
        ViewGroup.LayoutParams gp = v.getLayoutParams();
        if (gp instanceof LinearLayout.LayoutParams) {
            bm = ((LinearLayout.LayoutParams) gp).bottomMargin;
        }
        return h + bm;
    }

    private static boolean isBullet(String t) {
        return t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ");
    }

    private static boolean isOrdered(String t) {
        int d = 0;
        while (d < t.length() && Character.isDigit(t.charAt(d))) d++;
        return d > 0 && d < t.length() && (t.charAt(d) == '.' || t.charAt(d) == ')')
                && d + 1 < t.length() && t.charAt(d + 1) == ' ';
    }

    // ---------- 块级元素 ----------

    private void flushPara(StringBuilder para, java.util.List<Block> out) {
        if (para.length() == 0) return;
        String text = para.toString();
        para.setLength(0);
        if (text.trim().isEmpty()) return;
        out.add(Block.para(text));
    }

    /**
     * 启发式判断一段普通文本其实是不是代码/日志/命令输出。
     * 命中条件（满足其一）：
     *   1) 3 行以上且超过一半的行带明显代码特征（分号结尾、花括号、import、$ 提示符等）
     *   2) 4 行以上且整段没有任何中日韩文字——中文回答里的纯英文长块基本都是代码或日志
     */
    private boolean looksLikeCode(String text) {
        if (text == null) return false;
        String[] ls = text.replace("\r", "").split("\n", -1);
        int nonEmpty = 0;
        int codey = 0;
        boolean hasCjk = false;
        for (int i = 0; i < ls.length; i++) {
            String t = ls[i].trim();
            if (t.isEmpty()) continue;
            nonEmpty++;
            for (int k = 0; k < t.length() && !hasCjk; k++) {
                char ch = t.charAt(k);
                if (ch >= 0x4E00 && ch <= 0x9FFF) hasCjk = true;
            }
            boolean hit = t.endsWith(";") || t.endsWith("{") || t.endsWith("}")
                    || t.endsWith(");") || t.endsWith(":")
                    || t.startsWith("$ ") || t.startsWith("# ")
                    || t.startsWith("//") || t.startsWith("/*")
                    || t.startsWith("import ") || t.startsWith("#include")
                    || t.startsWith("def ") || t.startsWith("class ")
                    || t.startsWith("func ") || t.startsWith("package ")
                    || t.startsWith("<?xml") || t.startsWith("</")
                    || t.contains(" = ") || t.contains("=>") || t.contains("();")
                    || t.contains("&&") || t.contains("||") || t.contains(" != ");
            if (hit) codey++;
        }
        if (nonEmpty < 3) return false;
        if (!hasCjk && nonEmpty >= 4) return true;
        return codey * 2 >= nonEmpty;
    }

    private void addPara(String text) {
        int lines = 1;
        for (int k = 0; k < text.length(); k++) {
            if (text.charAt(k) == '\n') lines++;
        }
        // 没加围栏、但明显是代码/日志/命令输出的段落：直接当代码块处理，
        // 用等宽字体 + 默认折叠，绝不把一坨英文摊在正文里。
        if (looksLikeCode(text)) {
            addCodeBlock("代码", text);
            return;
        }
        // 其余长段落（大段说明）也收起成按钮框，点开才铺内容。
        if (lines >= 7 || text.length() > 560) {
            addFoldedPara(text, lines);
            return;
        }
        TextView tv = baseText();
        tv.setText(span(text, colorText, baseSp, false));
        addBlock(tv, 11);
    }

    /**
     * 可折叠长文本：默认只显示一条「长文本 · N 行  展开」的按钮框，
     * 点一下才把正文铺出来，再点收起。正文延迟到首次展开时才做行内格式化，
     * 避免流式过程中对长文本反复跑正则。
     */
    private void addFoldedPara(final String text, final int lines) {
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.code_bg);

        final TextView body = baseText();
        body.setVisibility(View.GONE);
        body.setPadding(dp(12), 0, dp(12), dp(10));

        final TextView label = Ui.foldTitle(ctx, "长文本 · " + lines + " 行");

        final TextView more = Ui.pill(ctx, "\u25be 展开");

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        header.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(more);

        final boolean[] opened = {false};
        final boolean[] filled = {false};
        View.OnClickListener toggle = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                opened[0] = !opened[0];
                if (opened[0] && !filled[0]) {
                    body.setText(span(text, colorText, baseSp, false));
                    filled[0] = true;
                }
                body.setVisibility(opened[0] ? View.VISIBLE : View.GONE);
                more.setText(opened[0] ? "\u25b4 收起" : "\u25be 展开");
            }
        };
        header.setOnClickListener(toggle);
        more.setOnClickListener(toggle);

        box.addView(header);
        box.addView(body);
        addBlock(box, 11);
    }

    /**
     * 增量绘制：找出新旧块的公共前缀，前缀部分原样保留，
     * 只把从第一个不同之处开始的部分删掉重画。
     */
    private void applyBlocks(java.util.List<Block> blocks) {
        int same = 0;
        while (same < blocks.size() && same < blockKeys.size()
                && blocks.get(same).structKey.equals(blockKeys.get(same))
                && sameContent(blocks.get(same), same)) {
            same++;
        }

        // 前缀里的代码块：结构没变但内容变了，就地刷新（不拆视图、不丢滚动位置）
        boolean contentDirty = false;
        for (int i = 0; i < same && i < blockViews.size(); i++) {
            Object tag = blockViews.get(i).getTag();
            if (tag instanceof CodeBlockHolder) {
                CodeBlockHolder h = (CodeBlockHolder) tag;
                h.update(blocks.get(i));
                blockFull.set(i, blocks.get(i).key);
                contentDirty = true;
            }
        }

        // 内容完全没变（比如只多了一个空格）：什么都不用做
        if (same == blocks.size() && same == blockKeys.size()) {
            if (contentDirty) container.requestLayout();
            return;
        }

        for (int i = blockViews.size() - 1; i >= same; i--) {
            container.removeView(blockViews.get(i));
        }
        if (same < blockViews.size()) {
            blockViews.subList(same, blockViews.size()).clear();
        }
        if (same < blockKeys.size()) {
            blockKeys.subList(same, blockKeys.size()).clear();
            blockFull.subList(same, blockFull.size()).clear();
        }
        for (int i = same; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            lastBlockView = null;
            renderBlock(b);
            if (lastBlockView == null) {
                // 兜底：保证 key 与 view 一一对应，否则下次增量会错位
                View filler = new View(ctx);
                container.addView(filler, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0));
                lastBlockView = filler;
            }
            blockViews.add(lastBlockView);
            blockKeys.add(b.structKey);
            blockFull.add(b.key);
        }
    }

    /** 代码块只比结构（内容由 holder 就地更新），其它块必须整块一致才算没变 */
    private boolean sameContent(Block b, int index) {
        if ("code".equals(b.kind)) return true;
        return b.key.equals(blockFull.get(index));
    }

    private void renderBlock(Block b) {
        if ("h".equals(b.kind)) addHeading(b.level, b.text);
        else if ("p".equals(b.kind)) addPara(b.text);
        else if ("ul".equals(b.kind)) addBullet(b.text);
        else if ("ol".equals(b.kind)) addOrdered(b.text);
        else if ("q".equals(b.kind)) addQuote(b.text);
        else if ("hr".equals(b.kind)) addDivider();
        else if ("code".equals(b.kind)) addCodeBlock(b.lang, b.text);
        else if ("tbl".equals(b.kind)) addTableFallback(b.text);
        else if ("chg".equals(b.kind)) addChangeCard(b.rows);
        else if ("cfm".equals(b.kind)) addConfirmCard(b.rows, b.seq);
    }

    /**
     * 一个块级元素：kind + 数据 + 相等性 key。
     * key 用来判断「这一节和上次渲染的是不是同一节」，相同就复用已有视图。
     */
    private static final class Block {
        final String kind;
        final String key;
        /** 不含文本的结构 key：代码块靠它判断「还是那一块」，从而就地更新而不是重建 */
        final String structKey;
        final int level;
        final String text;
        final String lang;
        final java.util.List<String> rows;
        /** 确认块序号（同一段回答里的第几张确认卡），用来绑定选择状态 */
        int seq = -1;

        private Block(String kind, int level, String text, String lang,
                      java.util.List<String> rows) {
            this.kind = kind;
            this.level = level;
            this.text = text;
            this.lang = lang;
            this.rows = rows;
            StringBuilder k = new StringBuilder(kind).append('\u0001')
                    .append(level).append('\u0001')
                    .append(text == null ? "" : text);
            if (lang != null) k.append('\u0001').append(lang);
            if (rows != null) for (String r : rows) k.append('\u0001').append(r);
            this.key = k.toString();
            this.structKey = kind + '\u0001' + level + '\u0001' + (lang == null ? "" : lang);
        }

        static Block head(int lv, String t) { return new Block("h", lv, t, null, null); }
        static Block para(String t) { return new Block("p", 0, t, null, null); }
        static Block bullet(String t) { return new Block("ul", 0, t, null, null); }
        static Block ordered(String t) { return new Block("ol", 0, t, null, null); }
        static Block quote(String t) { return new Block("q", 0, t, null, null); }
        static Block divider() { return new Block("hr", 0, "", null, null); }
        static Block code(String lg, String c) { return new Block("code", 0, c, lg, null); }
        static Block table(String t) { return new Block("tbl", 0, t, null, null); }
        static Block changes(java.util.List<String> rows) { return new Block("chg", 0, null, null, rows); }
        static Block confirm(java.util.List<String> rows) { return new Block("cfm", 0, null, null, rows); }
    }

    private void addHeading(int level, String text) {
        float sz = baseSp + (level == 1 ? 6 : level == 2 ? 4 : level == 3 ? 2 : 1);
        TextView tv = baseText();
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        SpannableStringBuilder sb = span(text, colorText, sz, true);
        tv.setText(sb);
        addBlock(tv, level <= 2 ? 12 : 8);
    }

    private void addBullet(String text) {
        TextView tv = baseText();
        tv.setPadding(0, 0, 0, 0);
        SpannableStringBuilder sb = new SpannableStringBuilder("•    ");
        sb.setSpan(new ForegroundColorSpan(colorAccent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // 悬挂缩进：换行后的文字与首行文字左对齐，不会跑到圆点下面
        sb.setSpan(new LeadingMarginSpan.Standard(0, dp(14)), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(span(text, colorText, baseSp, false));
        tv.setText(sb);
        addBlock(tv, 6);
    }

    private void addOrdered(String text) {
        TextView tv = baseText();
        int d = 0;
        while (d < text.length() && (Character.isDigit(text.charAt(d)) || text.charAt(d) == '.'
                || text.charAt(d) == ')')) d++;
        String marker = text.substring(0, Math.min(d, text.length())).trim();
        String rest = text.substring(Math.min(d, text.length())).trim();
        SpannableStringBuilder sb = new SpannableStringBuilder(marker + "    ");
        sb.setSpan(new ForegroundColorSpan(colorAccent), 0, marker.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new LeadingMarginSpan.Standard(0, dp(18)), 0, marker.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(span(rest, colorText, baseSp, false));
        tv.setText(sb);
        addBlock(tv, 4);
    }

    private void addQuote(String text) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View bar = new View(ctx);
        bar.setBackgroundColor(colorAccent);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT);
        barLp.setMargins(0, dp(2), dp(10), dp(2));
        row.addView(bar, barLp);

        TextView tv = baseText();
        tv.setText(span(text, colorDim, baseSp, false));
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tv, tvLp);
        addBlock(row, 6);
    }

    private void addDivider() {
        View v = new View(ctx);
        v.setBackgroundColor(Palette.stroke(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(8), 0, dp(8));
        container.addView(v, lp);
        lastBlockView = v;   // 同样要登记，否则增量渲染回退时会残留分隔线
    }

    /**
     * 修改清单卡片：每条一行「类型标签 + 文件」，下面跟一行说明。
     * 比表格兜底（一堆竖线）清楚得多，也让「改了什么」一眼可见。
     */
    /** 点击确认卡片时通知宿主（MainActivity）弹出可勾选面板 */
    public interface OnConfirmClick {
        void onConfirm(java.util.List<String> rows);
    }

    private OnConfirmClick onConfirmClick;

    public void setOnConfirmClick(OnConfirmClick l) {
        this.onConfirmClick = l;
    }

    /** 用户在卡片里点「确认」后把拼好的消息交回宿主直接发出去 */
    public interface OnConfirmSubmit {
        /** @return true 宿主已接收（卡片转只读） */
        boolean onSubmit(String message);
    }

    private OnConfirmSubmit onConfirmSubmit;

    public void setOnConfirmSubmit(OnConfirmSubmit l) {
        this.onConfirmSubmit = l;
    }

    /**
     * 每张确认卡的选择状态，按「第几个确认块」存。
     * 流式输出时卡片会随内容增长反复重建，靠它把用户已经勾好的选项还原回来。
     */
    private final java.util.HashMap<Integer, ConfirmCard.State> confirmStates =
            new java.util.HashMap<Integer, ConfirmCard.State>();

    /**
     * 确认卡片：模型需要用户拍板时出现。
     * 所有确认项一次性摊在同一张卡里（问题 / 选项 / 自定义答案 / 补充说明 / 确认），
     * 点一次就走完；不再「先点卡片、再在弹窗里勾」两段式。
     */
    private void addConfirmCard(final java.util.List<String> rows, int seq) {
        final java.util.List<String> data = rows;
        final java.util.List<ConfirmSheet.Item> items = ConfirmSheet.parseRows(rows);
        if (items.isEmpty()) return;

        ConfirmCard.State st = confirmStates.get(seq);
        if (st == null) {
            st = new ConfirmCard.State();
            confirmStates.put(seq, st);
        }

        View card = ConfirmCard.build(ctx, items, st, new ConfirmCard.Listener() {
            @Override public boolean onSubmit(String message) {
                return onConfirmSubmit != null && onConfirmSubmit.onSubmit(message);
            }
            @Override public void onOpenSheet() {
                if (onConfirmClick != null) onConfirmClick.onConfirm(data);
            }
        }, baseSp);
        // 折叠逻辑靠这个标记跳过确认卡：选项被折起来用户就没法继续了
        card.setTag("nebula_confirm");
        addBlock(card, 12, 4);
    }

    private void addChangeCard(java.util.List<String> rows) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.code_bg);
        int pad = dp(12);
        box.setPadding(pad, dp(10), pad, dp(10));

        // 标题
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("修改清单");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12f, baseSp - 2f));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(colorAccent);
        titleRow.addView(title);

        TextView badge = new TextView(ctx);
        badge.setText(" " + rows.size() + " 处改动");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11f, baseSp - 4f));
        badge.setTextColor(colorDim);
        titleRow.addView(badge);
        box.addView(titleRow);

        for (int k = 0; k < rows.size(); k++) {
            String[] seg = rows.get(k).split("\\|", 3);
            String kind = seg.length > 0 ? seg[0].trim() : "";
            String path = seg.length > 1 ? seg[1].trim() : "";
            String note = seg.length > 2 ? seg[2].trim() : "";

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(10);
            row.setLayoutParams(rlp);

            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            // 序号
            TextView idx = new TextView(ctx);
            idx.setText(String.valueOf(k + 1));
            idx.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10f, baseSp - 5f));
            idx.setTextColor(colorDim);
            idx.setMinWidth(dp(14));
            head.addView(idx);

            // 类型标签（带底色的小块）
            TextView chip = new TextView(ctx);
            chip.setText(kind);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10f, baseSp - 4f));
            chip.setTextColor(colorAccent);
            chip.setPadding(dp(6), dp(1), dp(6), dp(1));
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setColor((colorAccent & 0x00FFFFFF) | Palette.c(0x22000000));
            gd.setCornerRadius(0f);   // 与正文标记保持一致：直角
            chip.setBackground(gd);
            head.addView(chip);

            // 文件 / 对象
            TextView file = new TextView(ctx);
            file.setText(path);
            file.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11f, baseSp - 3f));
            file.setTextColor(colorText);
            file.setTypeface(Typeface.MONOSPACE);
            file.setSingleLine(true);
            file.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            flp.leftMargin = dp(8);
            file.setLayoutParams(flp);
            head.addView(file);

            row.addView(head);

            if (!note.isEmpty()) {
                TextView tvNote = new TextView(ctx);
                tvNote.setText(note);
                tvNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10f, baseSp - 4f));
                tvNote.setTextColor(colorDim);
                tvNote.setLineSpacing(dp(2), 1.25f);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                nlp.leftMargin = dp(22);
                nlp.topMargin = dp(2);
                tvNote.setLayoutParams(nlp);
                row.addView(tvNote);
            }
            box.addView(row);
        }
        addBlock(box, 10);
    }

    private void addTableFallback(String table) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        // 内容比屏窄时把底色撑满，左右边缘与正文对齐；内容超宽仍可横滑
        hsv.setFillViewport(true);
        StringBuilder cleaned = new StringBuilder();
        for (String row : table.split("\n")) {
            String r = row.trim();
            if (r.isEmpty()) continue;
            // 跳过 |---|---| 这类分隔行，避免正文里出现一堆横线
            if (r.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty()) continue;
            cleaned.append(r).append('\n');
        }
        TextView tv = new TextView(ctx);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11f, baseSp - 2f));
        tv.setTextColor(colorText);
        tv.setText(cleaned.toString().trim());
        tv.setLineSpacing(dp(2), 1.2f);
        int p = dp(10);
        tv.setPadding(p, p, p, p);
        tv.setBackgroundResource(R.drawable.code_bg);
        hsv.addView(tv);
        addBlock(hsv, 8);
    }

    private void addCodeBlock(String lang, String code) {
        CodeBlockHolder h = new CodeBlockHolder(lang, code);
        h.box.setTag(h);
        addBlock(h.box, 14, 5);
    }

    /**
     * 代码块：卡片式，默认只露 6 行，需要时展开。
     * 支持就地更新——流式时同一段代码只改文本，不重建视图，所以不会闪、不会跳。
     */
    private final class CodeBlockHolder {
        final LinearLayout box;
        final TextView tvLang;
        final TextView tvToggle;
        final TextView tvCode;
        final CodeGutter gutter;
        final HorizontalScrollView hsv;
        final View codeRow;
        final View divider;
        String lang;
        String code;
        boolean expanded;
        boolean dirty = true;
        int lineCount = 1;

        static final int CODE_PREVIEW_LINES = 6;

        CodeBlockHolder(String language, String source) {
            this.lang = language == null ? "" : language;
            this.code = source == null ? "" : source;
            this.lineCount = this.code.split("\n", -1).length;
            // 流式期间直接展开：代码一行行长出来，别等写完才蹦出一大坨
            this.expanded = !MarkdownRenderer.this.autoCollapse;

            box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.code_bg);

            View.OnClickListener toggle = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    expanded = !expanded;
                    dirty = true;
                    relayout();
                }
            };

            // 一行标题栏：语言 · 行数 ｜ 展开 ｜ 复制。默认状态整块就这么高
            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(12), dp(6), dp(10), dp(6));
            header.setOnClickListener(toggle);

            tvLang = Ui.foldTitle(ctx, "");
            tvLang.setSingleLine(true);
            tvLang.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.addView(tvLang, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            tvToggle = Ui.pill(ctx, "\u25be 展开");
            tvToggle.setOnClickListener(toggle);
            header.addView(tvToggle);

            TextView tvCopy = Ui.pillGhost(ctx, "复制");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(6);
            tvCopy.setLayoutParams(clp);
            tvCopy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copy(code);
                }
            });
            header.addView(tvCopy);
            box.addView(header);

            divider = new View(ctx);
            divider.setBackgroundColor(Palette.c(0xFF171E29));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
            dlp.leftMargin = dp(12);
            dlp.rightMargin = dp(12);
            box.addView(divider, dlp);

            tvCode = new TextView(ctx);
            tvCode.setTypeface(Typeface.MONOSPACE);
            tvCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11.5f, baseSp - 1.5f));
            tvCode.setTextColor(Palette.c(0xFFC8D3F5));
            tvCode.setLineSpacing(dp(4), 1.35f);
            tvCode.setTextIsSelectable(true);
            tvCode.setPadding(dp(10), dp(2), dp(10), dp(10));

            gutter = new CodeGutter(ctx, tvCode);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(gutter, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));

            hsv = new HorizontalScrollView(ctx);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(tvCode);
            row.addView(hsv, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            codeRow = row;
            box.addView(codeRow);

            relayout();
        }

        void update(Block b) {
            String nl = b.lang == null ? "" : b.lang;
            String nc = b.text == null ? "" : b.text;
            if (nl.equals(lang) && nc.equals(code)) return;
            lang = nl;
            code = nc;
            lineCount = code.split("\n", -1).length;
            dirty = true;
            relayout();
        }

        /** 折叠预览只算前几行；流式超长时先走纯文本，收尾再上高亮，避免每段增量都全量着色把 UI 拖死 */
        private void refreshCode() {
            if (!dirty) return;
            int sx = hsv.getScrollX();
            String show = expanded ? code : previewText(code);
            boolean plain = show.length() > 20000
                    || (!MarkdownRenderer.this.autoCollapse && lineCount > 400);
            tvCode.setText(plain ? show : CodeHighlighter.highlight(show, lang));
            dirty = false;
            if (sx > 0) {
                hsv.post(new Runnable() {
                    @Override
                    public void run() {
                        hsv.scrollTo(sx, 0);
                    }
                });
            }
        }

        /** 折叠态只取前几行做预览，既能看出是什么代码，又不会一条铺满屏 */
        private String previewText(String src) {
            int n = 0;
            for (int i = 0; i < src.length(); i++) {
                if (src.charAt(i) == '\n') {
                    n++;
                    if (n >= CODE_PREVIEW_LINES) return src.substring(0, i);
                }
            }
            return src;
        }

        private void relayout() {
            // 折叠态也要露出前几行预览，整块隐藏会让人以为「写了半天什么都没有」
            codeRow.setVisibility(View.VISIBLE);
            divider.setVisibility(View.VISIBLE);
            refreshCode();
            tvLang.setText((lang.isEmpty() ? "text" : lang.toLowerCase(java.util.Locale.ROOT))
                    + " · " + lineCount + " 行");
            tvToggle.setText(expanded ? "\u25b4 收起" : "\u25be 展开");
            gutter.setTotalLines(expanded ? lineCount : Math.min(lineCount, CODE_PREVIEW_LINES));
            gutter.invalidate();
        }
    }

    /**
     * 行内代码胶囊：自定义 ReplacementSpan 画圆角底，比系统 BackgroundColorSpan
     * 那种贴着字形的硬方框好看得多，且左右留白让中英混排不挤。
     */
    static final class CodeSpan extends ReplacementSpan {
        private final int colorTxt;
        private final int colorFill;
        private final int colorLine;
        private final float radius;
        private final float padH;
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private CharSequence src;
        private int s0 = -1;
        private int s1 = -1;
        private float size = -1f;
        private int width;

        CodeSpan(int colorTxt, int colorFill, int colorLine, float radius, float padH, float hintSize) {
            this.colorTxt = colorTxt;
            this.colorFill = colorFill;
            this.colorLine = colorLine;
            this.radius = radius;
            this.padH = padH;
            this.size = hintSize;
        }

        private void ensure(CharSequence text, int start, int end, float textSize) {
            float fs = textSize * 0.92f;
            if (src == text && s0 == start && s1 == end && Math.abs(tp.getTextSize() - fs) < 0.01f) return;
            src = text;
            s0 = start;
            s1 = end;
            tp.setTypeface(Typeface.MONOSPACE);
            tp.setTextSize(fs);
            tp.setColor(colorTxt);
            width = (int) Math.ceil(tp.measureText(text, start, end)) + Math.round(padH * 2);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            ensure(text, start, end, paint.getTextSize());
            if (fm != null) {
                Paint.FontMetricsInt m = tp.getFontMetricsInt();
                fm.ascent = m.ascent;
                fm.descent = m.descent;
                fm.top = m.top;
                fm.bottom = m.bottom;
            }
            return width;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            ensure(text, start, end, paint.getTextSize());
            Paint.FontMetricsInt m = tp.getFontMetricsInt();
            float half = (m.descent - m.ascent) / 2f;
            float cy = y + (m.ascent + m.descent) / 2f;
            rect.set(x, cy - half - padH * 0.55f, x + width, cy + half + padH * 0.55f);

            bp.setStyle(Paint.Style.FILL);
            bp.setColor(colorFill);
            canvas.drawRoundRect(rect, radius, radius, bp);

            if (colorLine != 0) {
                bp.setStyle(Paint.Style.STROKE);
                bp.setStrokeWidth(Math.max(1f, radius / 8f));
                bp.setColor(colorLine);
                canvas.drawRoundRect(rect, radius, radius, bp);
            }
            canvas.drawText(text, start, end, x + padH, y, tp);
        }
    }

    /**
     * 重点句标记：比 BackgroundColorSpan 那种硬方框轻得多。
     * 画一块极淡的圆角底（左右留白，不贴字），文字本身用强调色，
     * 换行时每行各自一段 —— 像荧光笔扫过，而不是给句子套个色块。
     */
    static final class MarkSpan extends ReplacementSpan {
        private final int colorTxt;
        private final int colorFill;
        private final float radius;
        private final float padH;
        private final float padV;
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        MarkSpan(int colorTxt, int colorFill, float radius, float padH, float padV) {
            this.colorTxt = colorTxt;
            this.colorFill = colorFill;
            this.radius = radius;
            this.padH = padH;
            this.padV = padV;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            tp.set(paint);
            tp.setFakeBoldText(true);
            if (fm != null) {
                Paint.FontMetricsInt m = tp.getFontMetricsInt();
                fm.ascent = m.ascent;
                fm.descent = m.descent;
                fm.top = m.top;
                fm.bottom = m.bottom;
            }
            return (int) Math.ceil(tp.measureText(text, start, end)) + Math.round(padH * 2);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            tp.set(paint);
            tp.setFakeBoldText(true);
            // 宽度必须先算：下面的 setColor 之后不能再 set(paint)，否则颜色被重置
            float w = (float) Math.ceil(tp.measureText(text, start, end)) + padH * 2;
            Paint.FontMetricsInt m = tp.getFontMetricsInt();
            rect.set(x, y + m.ascent - padV, x + w, y + m.descent + padV);
            tp.setColor(colorTxt);

            bp.setStyle(Paint.Style.FILL);
            bp.setColor(colorFill);
            canvas.drawRoundRect(rect, radius, radius, bp);

            canvas.drawText(text, start, end, x + padH, y, tp);
        }
    }

    // ---------- 工具 ----------

    private void copy(String text) {
        if (text == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("code", text));
                android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private TextView baseText() {
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp);
        tv.setTextColor(colorText);
        // 行距放宽，长段落不挤在一起，长时间阅读更舒服
        tv.setLineSpacing(dp(5), 1.55f);
        // 不设可选文本：气泡长按菜单（复制/重新生成）需要拿到长按事件
        tv.setTextIsSelectable(false);
        return tv;
    }

    private void addBlock(View v, int marginBottomDp) {
        addBlock(v, marginBottomDp, 0);
    }

    private void addBlock(View v, int marginBottomDp, int marginTopDp) {
        lastBlockView = v;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(marginBottomDp);
        if (marginTopDp > 0) lp.topMargin = dp(marginTopDp);
        container.addView(v, lp);
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 同色不同透明度：底纹、描边都从强调色派生，保证不跳色 */
    private static int soft(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /**
     * 拉开对比：f=0 原色，f=1 纯白/纯黑。
     * 深色主题向白提亮（深底上才看得清），浅色主题向黑压暗（白底上才看得清）。
     */
    private int mixFg(int color, float f) {
        int target = Palette.isLight(ctx) ? 0 : 255;
        int r = (int) (((color >> 16) & 0xFF) * (1 - f) + target * f);
        int g = (int) (((color >> 8) & 0xFF) * (1 - f) + target * f);
        int b = (int) ((color & 0xFF) * (1 - f) + target * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 行内格式：**粗** *斜* `代码` ~~删除~~ [链接](url)
     * 支持一层递归（例如 **粗体里的 `代码`**）。
     */
    private SpannableStringBuilder span(String s, int color, float sp, boolean bold) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);

            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = out.length();
                    out.append(s, i + 1, end);
                    // 胶囊颜色跟着强调色走：换紫/绿主题时行内代码不该还是蓝的
                    int ac = Palette.accent(ctx);
                    out.setSpan(new CodeSpan(mixFg(ac, 0.55f), soft(ac, 0x1C), soft(ac, 0x38),
                            0f, dp(5.5f), sp), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            if (c == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i + 1) {
                    String inner = s.substring(i + 2, end);
                    int st = out.length();
                    out.append(span(inner, Palette.c(0xFFF2F7FF), sp, true));
                    int en = out.length();
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    // 短重点句才给标记，整段加粗不铺（否则一片色块反而没重点）；
                    // 段内含 `行内代码` 时也不铺 —— 两层 ReplacementSpan 重叠会把代码胶囊吃掉
                    if (en > st && en - st <= BOLD_HL_MAX && inner.indexOf('`') < 0) {
                        int ac = Palette.accent(ctx);
                        out.setSpan(new MarkSpan(mixFg(ac, 0.35f), soft(ac, 0x10),
                                0f, dp(3f), dp(0.5f)), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    i = end + 2;
                    continue;
                }
            }

            if (c == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 1) {
                    int st = out.length();
                    out.append(s, i + 2, end);
                    out.setSpan(new StrikethroughSpan(), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            if (c == '*' || c == '_') {
                // 下划线必须是"独立"的强调符号：前后不为字母数字，
                // 否则 max_tokens / user_id / reasoning_content 这类词会被误吃并串成斜体
                if (c == '_') {
                    boolean prevOk = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
                    if (!prevOk) {
                        out.append(c);
                        i++;
                        continue;
                    }
                }
                int end = s.indexOf(c, i + 1);
                if (c == '_' && end > 0 && end + 1 < n
                        && Character.isLetterOrDigit(s.charAt(end + 1))) {
                    end = -1;
                }
                if (end > i + 1 && end - i < 200 && !Character.isWhitespace(s.charAt(i + 1))) {
                    int st = out.length();
                    out.append(span(s.substring(i + 1, end), color, sp, true));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            if (c == '[') {
                int rb = s.indexOf(']', i + 1);
                if (rb > i && rb + 1 < n && s.charAt(rb + 1) == '(') {
                    int rp = s.indexOf(')', rb + 2);
                    if (rp > rb) {
                        int st = out.length();
                        out.append(s, i + 1, rb);
                        out.setSpan(new ForegroundColorSpan(colorAccent), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = rp + 1;
                        continue;
                    }
                }
            }

            out.append(c);
            i++;
        }
        if (bold) return out;
        return out;
    }
}
