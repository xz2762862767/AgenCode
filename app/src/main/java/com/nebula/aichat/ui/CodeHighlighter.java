package com.nebula.aichat.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 轻量代码语法高亮：不引第三方库，单遍扫描 + Spannable 上色。
 *
 * 目标是「一眼看出结构」——关键字、字符串、注释、数字、类型、函数调用各一色，
 * 而不是精确的语法分析。因此对未闭合的流式代码（缺右引号/右括号）也能安全处理。
 */
public final class CodeHighlighter {

    // Tokyo Night 配色（深色底）。浅色主题下由 Palette.syntax() 压暗，
    // 否则浅绿/浅黄/浅青画在白底上几乎不可见。
    // 注意不能写成 static final int —— 那样类加载时求值，之后切主题也不会跟着变。
    private static int C_KEYWORD() { return Palette.syntax(0xFFBB9AF7); } // 紫
    private static int C_STRING()  { return Palette.syntax(0xFF9ECE6A); } // 绿
    private static int C_COMMENT() { return Palette.syntax(0xFF565F89); } // 暗蓝灰
    private static int C_NUMBER()  { return Palette.syntax(0xFFFF9E64); } // 橙
    private static int C_TYPE()    { return Palette.syntax(0xFF2AC3DE); } // 青
    private static int C_FUNC()    { return Palette.syntax(0xFF7AA2F7); } // 蓝
    private static int C_ANNOT()   { return Palette.syntax(0xFFE0AF68); } // 黄
    private static int C_TAG()     { return Palette.syntax(0xFF7DCFFF); } // 标签
    private static int C_PROP()    { return Palette.syntax(0xFFE0AF68); } // 属性名

    /** 超过这个长度就不上色，避免超长代码卡顿 */
    private static final int MAX_LEN = 60000;

    private CodeHighlighter() {
    }

    // ---------------- 关键字表 ----------------

    private static final Set<String> JAVA_KT = set(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "void", "volatile", "while", "null", "true", "false",
            "fun", "val", "var", "object", "override", "suspend", "companion", "lateinit",
            "internal", "open", "when", "typealias", "inline", "reified", "data", "by", "init");

    private static final Set<String> JS = set(
            "function", "const", "let", "var", "if", "else", "for", "while", "do", "return",
            "class", "extends", "import", "export", "from", "default", "new", "this", "null",
            "undefined", "async", "await", "try", "catch", "finally", "throw", "typeof",
            "instanceof", "switch", "case", "break", "continue", "delete", "in", "of", "yield",
            "static", "get", "set", "super", "interface", "type", "enum", "implements", "as",
            "true", "false");

    private static final Set<String> PY = set(
            "def", "class", "import", "from", "as", "if", "elif", "else", "for", "while",
            "return", "try", "except", "finally", "with", "lambda", "pass", "break", "continue",
            "and", "or", "not", "in", "is", "async", "await", "yield", "global", "nonlocal",
            "del", "assert", "raise", "self", "None", "True", "False", "print");

    private static final Set<String> C_LIKE = set(
            "int", "char", "float", "double", "void", "long", "short", "signed", "unsigned",
            "struct", "union", "enum", "typedef", "static", "const", "if", "else", "for",
            "while", "do", "return", "switch", "case", "break", "continue", "goto", "sizeof",
            "class", "public", "private", "protected", "virtual", "override", "new", "delete",
            "namespace", "using", "template", "typename", "auto", "true", "false",
            "NULL", "include", "define", "extern", "inline", "bool");

    private static final Set<String> GO = set(
            "func", "package", "import", "var", "const", "type", "struct", "interface", "map",
            "chan", "go", "defer", "select", "case", "default", "if", "else", "for", "range",
            "return", "break", "continue", "switch", "fallthrough", "nil", "true", "false");

    private static final Set<String> RUST = set(
            "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "for",
            "while", "loop", "if", "else", "match", "return", "break", "continue", "use",
            "mod", "pub", "crate", "self", "super", "as", "where", "move", "ref", "unsafe",
            "async", "await", "dyn", "Some", "None", "Ok", "Err");

    private static final Set<String> PHP = set(
            "function", "class", "public", "private", "protected", "static", "const", "if",
            "else", "elseif", "for", "foreach", "while", "do", "return", "echo", "print",
            "new", "use", "namespace", "extends", "implements", "try", "catch", "finally",
            "throw", "null", "true", "false", "array", "as", "break", "continue");

    private static final Set<String> RUBY = set(
            "def", "class", "module", "end", "if", "elsif", "else", "unless", "while", "until",
            "for", "in", "do", "return", "yield", "begin", "rescue", "ensure", "raise", "new",
            "nil", "true", "false", "self", "require", "puts", "lambda");

    private static final Set<String> SH = set(
            "if", "then", "elif", "else", "fi", "for", "in", "do", "done", "while", "until",
            "case", "esac", "function", "return", "export", "local", "readonly", "echo",
            "printf", "cd", "source", "exit", "set", "unset", "shift", "eval", "exec");

    private static final Set<String> SQL = set(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "alter", "drop", "index", "view", "join", "left", "right",
            "inner", "outer", "on", "group", "by", "order", "having", "limit", "offset",
            "and", "or", "not", "null", "as", "distinct", "union", "all", "case", "when",
            "then", "else", "end", "primary", "key", "foreign", "references", "default",
            "varchar", "int", "text", "date", "timestamp", "boolean", "count", "sum", "avg");

    /** 语言未知时的并集（保守，避免太多误判） */
    private static final Set<String> COMMON = set(
            "if", "else", "for", "while", "return", "class", "function", "def", "import",
            "from", "new", "try", "catch", "finally", "throw", "break", "continue", "switch",
            "case", "const", "var", "let", "static", "public", "private", "void", "int", "null", "true", "false", "None", "nil", "print", "echo", "end");

    private static Set<String> set(String... items) {
        return new HashSet<String>(Arrays.asList(items));
    }

    // ---------------- 缓存（流式时同一段代码会反复渲染） ----------------

    private static String lastCode;
    private static String lastLang;
    private static CharSequence lastResult;

    public static CharSequence highlight(String code, String lang) {
        if (code == null || code.isEmpty()) return "";
        if (code.length() > MAX_LEN) return code;
        String key = lang == null ? "" : lang;
        if (code.equals(lastCode) && key.equals(lastLang)) {
            return lastResult;
        }
        CharSequence result = doHighlight(code, key.trim().toLowerCase(java.util.Locale.ROOT));
        lastCode = code;
        lastLang = key;
        lastResult = result;
        return result;
    }

    private static CharSequence doHighlight(String code, String l) {
        if (l.startsWith("html") || l.startsWith("xml") || l.startsWith("svg")) {
            return markup(code);
        }
        if (l.startsWith("json")) {
            return json(code);
        }
        if (l.startsWith("css") || l.startsWith("scss") || l.startsWith("less")) {
            return generic(code, COMMON, false, false, false, true);
        }
        Set<String> kw = keywordsFor(l);
        boolean hashComment = l.startsWith("py") || l.startsWith("sh") || l.startsWith("ba")
                || l.startsWith("zsh") || l.startsWith("yaml") || l.startsWith("yml")
                || l.startsWith("rb") || l.startsWith("ruby") || l.startsWith("gradle")
                || l.startsWith("groovy") || l.startsWith("docker") || l.startsWith("toml")
                || l.startsWith("conf") || l.startsWith("propert") || l.startsWith("make")
                || l.startsWith("perl") || l.startsWith("nginx");
        boolean dashComment = l.startsWith("sql") || l.startsWith("lua");
        boolean ci = l.startsWith("sql");
        // 只有配置类语言才把 "name:" 里的 name 当属性名，避免 python 的 "as f:" 误判
        boolean colonProp = l.startsWith("yaml") || l.startsWith("yml") || l.startsWith("toml")
                || l.startsWith("conf") || l.startsWith("propert") || l.startsWith("ini")
                || l.startsWith("docker") || l.startsWith("nginx");
        return generic(code, kw, hashComment, dashComment, ci, colonProp);
    }

    private static Set<String> keywordsFor(String l) {
        if (l.isEmpty()) return COMMON;
        if (l.startsWith("java") || l.startsWith("kt") || l.startsWith("gradle")
                || l.startsWith("groovy")) {
            return JAVA_KT;
        }
        if (l.startsWith("js") || l.startsWith("ts") || l.startsWith("node")
                || l.startsWith("react") || l.startsWith("vue")) {
            return JS;
        }
        if (l.startsWith("py")) return PY;
        if (l.startsWith("go")) return GO;
        if (l.startsWith("rs") || l.startsWith("rust")) return RUST;
        if (l.startsWith("php")) return PHP;
        if (l.startsWith("rb") || l.startsWith("ruby")) return RUBY;
        if (l.startsWith("sh") || l.startsWith("ba") || l.startsWith("zsh")
                || l.startsWith("shell") || l.startsWith("console")) {
            return SH;
        }
        if (l.startsWith("sql")) return SQL;
        if (l.startsWith("c") || l.startsWith("h") || l.startsWith("objc")
                || l.startsWith("cpp")) {
            return C_LIKE;
        }
        return COMMON;
    }

    // ---------------- 通用分词上色 ----------------

    private static CharSequence generic(String code, Set<String> kw, boolean hashComment,
                                        boolean dashComment, boolean caseInsensitive,
                                        boolean colonIsProp) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);

            // 块注释 /* ... */
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                mark(sb, i, end, C_COMMENT(), true);
                i = end;
                continue;
            }
            // 行注释 //
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                int end = code.indexOf('\n', i);
                end = end < 0 ? n : end;
                mark(sb, i, end, C_COMMENT(), true);
                i = end;
                continue;
            }
            // 行注释 #
            if (c == '#' && hashComment) {
                int end = code.indexOf('\n', i);
                end = end < 0 ? n : end;
                mark(sb, i, end, C_COMMENT(), true);
                i = end;
                continue;
            }
            // 行注释 --
            if (c == '-' && dashComment && i + 1 < n && code.charAt(i + 1) == '-') {
                int end = code.indexOf('\n', i);
                end = end < 0 ? n : end;
                mark(sb, i, end, C_COMMENT(), true);
                i = end;
                continue;
            }
            // 字符串
            if (c == '"' || c == '\'' || c == '`') {
                int end = scanString(code, i, c);
                mark(sb, i, end, C_STRING(), false);
                i = end;
                continue;
            }
            // 注解 / 装饰器 @Foo
            if (c == '@' && i + 1 < n && isIdentStart(code.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && isIdentPart(code.charAt(j))) j++;
                mark(sb, i, j, C_ANNOT(), false);
                i = j;
                continue;
            }
            // 数字
            if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(code.charAt(j))
                        || code.charAt(j) == '.' || code.charAt(j) == '_')) {
                    j++;
                }
                mark(sb, i, j, C_NUMBER(), false);
                i = j;
                continue;
            }
            // 标识符
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < n && isIdentPart(code.charAt(j))) j++;
                String w = code.substring(i, j);
                String probe = caseInsensitive ? w.toLowerCase(java.util.Locale.ROOT) : w;
                if (kw.contains(probe)) {
                    mark(sb, i, j, C_KEYWORD(), false);
                } else if ("true".equals(probe) || "false".equals(probe) || "null".equals(probe)
                        || "none".equals(probe) || "nil".equals(probe)
                        || "undefined".equals(probe)) {
                    mark(sb, i, j, C_NUMBER(), false);
                } else {
                    // 看后面第一个非空格字符，判断是函数调用还是属性名
                    int k = j;
                    while (k < n && (code.charAt(k) == ' ' || code.charAt(k) == '\t')) k++;
                    char next = k < n ? code.charAt(k) : 0;
                    if (next == '(') {
                        mark(sb, i, j, C_FUNC(), false);
                    } else if (next == ':' && colonIsProp) {
                        mark(sb, i, j, C_PROP(), false);
                    } else if (isConstLike(w)) {
                        // MAX_LEN / NULL / PI 这类全大写常量用橙色，和类型区分开
                        mark(sb, i, j, C_NUMBER(), false);
                    } else if (Character.isUpperCase(w.charAt(0)) && w.length() > 1) {
                        mark(sb, i, j, C_TYPE(), false);
                    }
                }
                i = j;
                continue;
            }
            i++;
        }
        return sb;
    }

    // ---------------- JSON ----------------

    private static CharSequence json(String code) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int end = scanString(code, i, c);
                // 后面跟冒号就是键
                int k = end;
                while (k < n && Character.isWhitespace(code.charAt(k))) k++;
                boolean isKey = k < n && code.charAt(k) == ':';
                mark(sb, i, end, isKey ? C_TAG() : C_STRING(), false);
                i = end;
                continue;
            }
            if (Character.isDigit(c) || c == '-') {
                int j = i + 1;
                while (j < n && (Character.isDigit(code.charAt(j)) || code.charAt(j) == '.'
                        || code.charAt(j) == 'e' || code.charAt(j) == 'E'
                        || code.charAt(j) == '+' || code.charAt(j) == '-')) {
                    j++;
                }
                mark(sb, i, j, C_NUMBER(), false);
                i = j;
                continue;
            }
            if (Character.isLetter(c)) {
                int j = i + 1;
                while (j < n && Character.isLetter(code.charAt(j))) j++;
                String w = code.substring(i, j);
                if ("true".equals(w) || "false".equals(w)) {
                    mark(sb, i, j, C_KEYWORD(), false);
                } else if ("null".equals(w)) {
                    mark(sb, i, j, C_NUMBER(), false);
                }
                i = j;
                continue;
            }
            i++;
        }
        return sb;
    }

    // ---------------- HTML / XML ----------------

    private static CharSequence markup(String code) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (c == '<') {
                if (code.startsWith("<!--", i)) {
                    int end = code.indexOf("-->", i);
                    end = end < 0 ? n : end + 3;
                    mark(sb, i, end, C_COMMENT(), true);
                    i = end;
                    continue;
                }
                int end = code.indexOf('>', i);
                end = end < 0 ? n : end + 1;
                int j = i + 1;
                if (j < n && (code.charAt(j) == '/' || code.charAt(j) == '!'
                        || code.charAt(j) == '?')) {
                    j++;
                }
                int ns = j;
                while (j < end && (Character.isLetterOrDigit(code.charAt(j))
                        || code.charAt(j) == ':' || code.charAt(j) == '_'
                        || code.charAt(j) == '-')) {
                    j++;
                }
                if (j > ns) mark(sb, i, j, C_TAG(), false);
                // 标签内的属性名/属性值
                int k = j;
                while (k < end) {
                    char d = code.charAt(k);
                    if (d == '"' || d == '\'') {
                        int q = Math.min(scanString(code, k, d), end);
                        mark(sb, k, q, C_STRING(), false);
                        k = q;
                        continue;
                    }
                    if (Character.isLetter(d)) {
                        int as = k;
                        while (k < end && (Character.isLetterOrDigit(code.charAt(k))
                                || code.charAt(k) == '-' || code.charAt(k) == ':'
                                || code.charAt(k) == '_' || code.charAt(k) == '.')) {
                            k++;
                        }
                        mark(sb, as, k, C_PROP(), false);
                        continue;
                    }
                    k++;
                }
                i = end;
                continue;
            }
            i++;
        }
        return sb;
    }

    // ---------------- 辅助 ----------------

    /** 扫描字符串，返回结束位置（不含）。允许流式未闭合：遇到换行即结束。 */
    private static int scanString(String code, int start, char quote) {
        int n = code.length();
        int i = start + 1;
        while (i < n) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) return i + 1;
            if (c == '\n' && quote != '`') return i;
            i++;
        }
        return n;
    }

    private static void mark(SpannableStringBuilder sb, int s, int e, int color, boolean italic) {
        if (s < 0 || e > sb.length() || s >= e) return;
        sb.setSpan(new ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (italic) {
            sb.setSpan(new StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 全大写常量名（MAX_LEN、NULL、PI） */
    private static boolean isConstLike(String w) {
        if (w.length() < 2) return false;
        boolean hasUpper = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                hasUpper = true;
            } else if (!Character.isDigit(c) && c != '_') {
                return false;
            }
        }
        return hasUpper;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
