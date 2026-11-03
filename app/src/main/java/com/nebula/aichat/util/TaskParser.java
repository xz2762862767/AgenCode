package com.nebula.aichat.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型回答里抽取「执行步骤」清单。
 *
 * <p>约定模型按 Markdown 任务列表输出，这样人读起来是清单，程序也能稳定解析：</p>
 * <pre>
 * - [ ] 第一步要改什么
 * - [x] 第二步（已完成）
 * </pre>
 *
 * <p>兼容性放宽到：有序列表（1. / 1)）、星号/加号项目符、以及不带项目符的裸 [ ]。
 * 只有整段里至少出现 1 条才算任务；正文里的清单行由 {@link #strip(String)} 剥掉，
 * 避免"文字里一份、卡片里又一份"。</p>
 */
public final class TaskParser {

    /** - [ ] xxx / * [x] xxx / + [ ] xxx / 1. [ ] xxx / 1) [x] xxx */
    private static final Pattern ITEM = Pattern.compile(
            "^\\s*(?:[-*+]|\\d{1,3}[.)])?\\s*\\[([ xX\\u2713\\u2714])]\\s+(.+?)\\s*$");

    /** 只有 [ ] 开头、没有列表符的裸写法 */
    private static final Pattern BARE = Pattern.compile(
            "^\\s*\\[([ xX])]\\s+(.+?)\\s*$");

    private TaskParser() {
    }

    /** 解析出全部步骤文本（已完成/未完成都收，顺序保持原文）。没有则返回 null */
    public static List<String> parse(String text) {
        if (text == null || text.isEmpty()) return null;
        List<String> out = new ArrayList<String>();
        for (String line : text.split("\n", -1)) {
            Matcher m = ITEM.matcher(line);
            if (!m.matches()) m = BARE.matcher(line);
            if (!m.matches()) continue;
            String body = clean(m.group(2));
            if (!body.isEmpty()) out.add(body);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 把清单行从正文里剥掉（其余行原样保留）。
     * 剥完如果只剩空白，就退回原文——宁可重复一次，也不要让气泡空掉。
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        boolean removed = false;
        boolean written = false;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = ITEM.matcher(line);
            if (!m.matches()) m = BARE.matcher(line);
            if (m.matches()) {
                removed = true;
                continue;
            }
            if (written) sb.append('\n');
            sb.append(line);
            written = true;
        }
        if (!removed) return text;
        String out = sb.toString().replaceAll("\n{3,}", "\n\n").trim();
        return out.isEmpty() ? text : out;
    }


    /**
     * 把清单行里的复选框标记去掉，保留列表符本身：
     * {@code - [ ] 改 A} → {@code - 改 A}。
     *
     * <p>卡片停用后正文里直接显示清单，复选框既点不动也没人同步它的状态，
     * 留着只是个装饰性的空框，反而像没渲染完的控件，所以定稿时抹掉。</p>
     */
    public static String uncheck(String text) {
        if (text == null || text.isEmpty() || text.indexOf('[') < 0) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i];
            Matcher m = ITEM.matcher(line);
            if (m.matches()) {
                int br = line.indexOf('[');
                String prefix = br > 0 ? line.substring(0, br) : "";
                if (!prefix.trim().isEmpty()) {
                    sb.append(prefix.endsWith(" ") ? prefix : prefix + " ");
                    sb.append(m.group(2));
                } else {
                    sb.append(prefix).append(m.group(2));
                }
                changed = true;
            } else {
                Matcher b = BARE.matcher(line);
                if (b.matches()) {
                    sb.append(b.group(2));
                    changed = true;
                } else {
                    sb.append(line);
                }
            }
        }
        return changed ? sb.toString() : text;
    }

    /** 去掉行内残留的 Markdown 强调符与多余空白 */
    private static String clean(String s) {
        if (s == null) return "";
        String r = s.replace("**", "").replace("__", "").trim();
        if (r.endsWith("\\")) r = r.substring(0, r.length() - 1).trim();
        return r;
    }
}
