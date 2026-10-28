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
 */
public final class ConclusionParser {

    public static final String OPEN = "%%CONCLUSION%%";
    public static final String CLOSE = "%%END%%";

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
     * 流式显示用：返回可以安全渲染的正文前缀。
     * 只要出现（哪怕是半截）结论标记，后面全部藏起来，等收尾时再统一渲染成卡片。
     */
    public static String visibleForStream(String full) {
        if (full == null) return "";
        int i = indexOfOpen(full);
        return i < 0 ? full : full.substring(0, i);
    }

    /**
     * 渲染兜底：把结论标记连同其中的内容整段删掉。
     *
     * 用于「已经落盘、来不及走 parse」的路径（历史消息、异常中止的那一轮），
     * 保证界面上任何时候都不会露出 %%CONCLUSION%% / %%END%%。
     * 未闭合的块一律删到结尾——宁可少显示一段，也不要把半截协议当成正文。
     */
    public static String stripAll(String src) {
        if (src == null) return "";
        String t = src;
        int guard = 0;
        while (guard++ < 50) {
            int i = t.indexOf(OPEN);
            if (i < 0) break;
            int e = t.indexOf(CLOSE, i + OPEN.length());
            if (e < 0) {
                t = t.substring(0, i);
                break;
            }
            t = t.substring(0, i) + t.substring(e + CLOSE.length());
        }
        // 正文里若还有孤立标记（模型重复输出/写错位置），一并清掉
        t = t.replace(OPEN, "").replace(CLOSE, "");
        return trimTail(t);
    }

    /** 完整解析：剥离结论块并拆出标题 / 详述 */
    public static Result parse(String full) {
        Result r = new Result();
        if (full == null) {
            r.body = "";
            return r;
        }
        int s = indexOfOpen(full);
        if (s < 0) {
            r.body = full;
            return r;
        }
        r.body = trimTail(full.substring(0, s));

        int e = full.indexOf(CLOSE, s + OPEN.length());
        if (e < 0) {
            // 没闭合：整块丢弃，只保留前面的正文
            return r;
        }
        String inner = full.substring(s + OPEN.length(), e).trim();
        if (inner.isEmpty()) return r;

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
        int idx = s.indexOf("---");
        return idx;
    }

    /**
     * 找结论标记的起点。除了完整标记，还要认「正在输出的半截标记」：
     * 例如 "%%CONCL"、"%"，否则流式时会闪一下乱码。
     */
    private static int indexOfOpen(String s) {
        int i = s.indexOf(OPEN);
        if (i >= 0) return i;
        // 半截：结尾处出现 "%%" 且其后是 OPEN 的前缀（只认双 %，单 % 不碰，
        // 否则正文里写 "提升 30%" 会被误吞）
        int p = s.lastIndexOf("%%");
        if (p >= 0 && s.length() - p < OPEN.length() && OPEN.startsWith(s.substring(p))) {
            return p;
        }
        return -1;
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
