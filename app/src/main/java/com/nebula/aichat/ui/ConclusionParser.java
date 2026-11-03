package com.nebula.aichat.ui;

/**
 * 结论块协议解析。
 *
 * 模型被要求每轮回答结尾输出：
 * <pre>
 * %%CONCLUSION%%
 * 一句话结论
 * ---
 * 详细说明，2～4 句
 * %%END%%
 * </pre>
 *
 * 这里负责三件事：
 * 1. 把结论块从正文里剥掉（正文只留结论之前的部分）；
 * 2. 流式过程中识别「正在输入的半个标记」，提前截断，避免界面上闪过 %%CONCLU… 这种半截字符；
 * 3. 没闭合的块（断流、模型没写完）一律丢弃，不污染正文。
 *
 * <p>难点在于模型有时会把标记写进正文：用户问「你的提示词里有什么」时，它会把
 * 这个记号当例子复述出来。所以不能见到标记就当成结论块起点（那会把正文腰斩），
 * 而要区分两种写法：
 * <ul>
 *   <li>引用：标记后面紧跟着正文，中间没有 %%END%%，或者到 %%END%% 之间又出现了标记；</li>
 *   <li>真块：从标记到最近的 %%END%% 之间是一小段（标题 + --- + 详述），没有嵌套标记。</li>
 * </ul>
 * 引用只删标记本身、保留后续正文；真块整段删掉，交给结论卡渲染。
 */
public final class ConclusionParser {

    public static final String OPEN = "%%CONCLUSION%%";
    public static final String CLOSE = "%%END%%";

    /** 真结论块的合理长度上限：标题 + 详述，正常几十字，给足余量也不会误吞整段正文 */
    private static final int MAX_BLOCK = 600;

    /** 流式中标记后面已经堆了这么多字还没等到 %%END%%，就认定它是在正文里引用 */
    private static final int STREAM_PATIENCE = 240;

    private ConclusionParser() {
    }

    /** 解析结果：body=干净正文，title/detail=结论两段（无结论时为 null） */
    public static final class Result {
        public String body = "";
        public String title;
        public String detail;

        public boolean hasConclusion() {
            return title != null && !title.trim().isEmpty();
        }
    }

    /**
     * 流式显示用：返回可以安全渲染的正文。
     *
     * <p>已闭合的协议块整段删掉；正在输入的块从起点藏到结尾（等内容写完再统一出卡片），
     * 但如果迟迟等不到闭合，就认定是正文里的引用，把标记删掉、内容照常显示。
     */
    public static String visibleForStream(String full) {
        return hideProtocol(full, true);
    }

    /**
     * 渲染兜底：把结论块连同其中的内容整段删掉。
     *
     * <p>用于「已经落盘、来不及走 parse」的路径（历史消息、异常中止的那一轮），
     * 保证界面上任何时候都不会露出 %%CONCLUSION%% / %%END%%。
     */
    public static String stripAll(String src) {
        return trimTail(hideProtocol(src, false));
    }

    /**
     * 剥掉协议块。streaming=true 时对「迟迟不闭合的标记」更宽容，避免正文被藏住不显示。
     */
    private static String hideProtocol(String full, boolean streaming) {
        if (full == null || full.isEmpty()) return "";
        // 快路径：绝大多数回答里根本没有 % 字符
        if (full.indexOf("%%") < 0) return full;

        StringBuilder sb = new StringBuilder(full.length());
        int i = 0;
        while (i < full.length()) {
            int s = full.indexOf(OPEN, i);
            if (s < 0) {
                sb.append(full, i, full.length());
                break;
            }
            int e = full.indexOf(CLOSE, s + OPEN.length());
            if (e < 0) {
                // 没闭合：标记同一行后面还有字，说明是正文里引用它（真块的标记独占一行）；
                // 流式中标记独占一行时先忍着，等太久了也当成引用
                int nl = full.indexOf('\n', s + OPEN.length());
                String restOfLine = (nl < 0
                        ? full.substring(s + OPEN.length())
                        : full.substring(s + OPEN.length(), nl)).trim();
                boolean quoted = !restOfLine.isEmpty()
                        || (streaming && (full.length() - s > STREAM_PATIENCE));
                sb.append(full, i, s);
                if (!quoted) break;                // 未闭合的真块：后面全丢
                i = s + OPEN.length();
                continue;
            }
            String inner = full.substring(s + OPEN.length(), e);
            if (inner.indexOf(OPEN) >= 0 || inner.length() > MAX_BLOCK) {
                // 引用：只丢标记本身，后面的话是正文，不能吞
                sb.append(full, i, s);
                i = s + OPEN.length();
                continue;
            }
            // 真块：整段丢弃
            sb.append(full, i, s);
            i = e + CLOSE.length();
        }
        return sb.toString().replace(OPEN, "").replace(CLOSE, "");
    }

    /** 完整解析：剥离结论块并拆出标题 / 详述 */
    public static Result parse(String full) {
        Result r = new Result();
        if (full == null) {
            r.body = "";
            return r;
        }
        // 正文统一走一遍剥离：模型在正文里引用过标记时，这些标记都不许留下
        r.body = stripAll(full);
        if (full.indexOf("%%") < 0) return r;

        // 结论块永远是最后一段，所以从最后一个标记往前找真块
        int s = full.lastIndexOf(OPEN);
        if (s < 0) return r;
        int e = full.indexOf(CLOSE, s + OPEN.length());
        if (e < 0) return r;                       // 没闭合：整块丢弃，正文已在上面剥好
        String inner = full.substring(s + OPEN.length(), e).trim();
        if (inner.isEmpty() || inner.length() > MAX_BLOCK) return r;

        int sep = indexOfSeparator(inner);
        if (sep >= 0) {
            r.title = clean(inner.substring(0, sep));
            r.detail = clean(inner.substring(sep + 3));
        } else {
            int nl = inner.indexOf('\n');
            if (nl < 0) {
                r.title = clean(inner);
            } else {
                r.title = clean(inner.substring(0, nl));
                r.detail = clean(inner.substring(nl + 1));
            }
        }
        if (r.title != null && r.title.isEmpty()) r.title = null;
        if (r.detail != null && r.detail.isEmpty()) r.detail = null;
        return r;
    }

    /** 找 "---" 分隔行（允许前后带空格，也容忍只写了 "--"） */
    private static int indexOfSeparator(String s) {
        return s.indexOf("---");
    }

    /** 去掉结尾多余空行 / 分隔线，避免卡片上方空一大片 */
    private static String trimTail(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t' || c == '-' || c == '—') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /** 清理 Markdown 强调符号，卡片自己负责排版 */
    static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        while (t.startsWith("#")) t = t.substring(1).trim();
        // 去掉成对的 ** __
        t = t.replace("**", "").replace("__", "");
        // 开头的项目符号
        while (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("·")) {
            t = t.substring(t.indexOf(' ') + 1).trim();
            if (t.isEmpty()) break;
        }
        return t.trim();
    }
}
