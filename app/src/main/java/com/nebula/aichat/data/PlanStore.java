package com.nebula.aichat.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前任务的计划表（会话级，存内存）。
 *
 * <p>为什么要有这个：模型在长任务里最容易出的问题不是「不会做」，而是「做着做着忘了」——
 * 前面说要改三处，改完第一处就开始写总结；或者把「验证」这一步默默吞掉。
 * 这些步骤在提示词里是「要求」，在上下文里却没有痕迹，上下文一裁剪就没了。
 *
 * <p>做成显式的表之后，每一步的完成与否是可见状态：
 * 收尾前能核对「还有两步没做」，也就能拦住「做一半就汇报」。
 *
 * <p>只存在内存、不落盘：计划是这一轮任务的工作台，任务结束就该消失；
 * 落盘反而会让下次会话带着上一次的旧计划，把新任务带偏。
 */
public class PlanStore {

    public static class Step {
        public String id;
        public String title;
        public boolean done;
        /** 完成时记一句话：做了什么/怎么验证的 */
        public String note;

        public Step(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private static final List<Step> STEPS = new ArrayList<Step>();
    private static int seq = 0;

    public static synchronized void reset() {
        STEPS.clear();
        seq = 0;
    }

    /** 建计划：换掉旧的，id 依次 p1、p2… */
    public static synchronized List<Step> set(String[] titles) {
        STEPS.clear();
        seq = 0;
        if (titles != null) {
            for (String t : titles) {
                if (t == null || t.trim().isEmpty()) continue;
                seq++;
                STEPS.add(new Step("p" + seq, t.trim()));
            }
        }
        return new ArrayList<Step>(STEPS);
    }

    /** 追加一步 */
    public static synchronized Step add(String title) {
        if (title == null || title.trim().isEmpty()) return null;
        seq++;
        Step s = new Step("p" + seq, title.trim());
        STEPS.add(s);
        return s;
    }

    /** 按 id 或序号（1 开始）标记完成 */
    public static synchronized Step done(String idOrIndex, String note) {
        Step s = find(idOrIndex);
        if (s == null) return null;
        s.done = true;
        s.note = note == null ? "" : note.trim();
        return s;
    }

    public static synchronized Step find(String idOrIndex) {
        if (idOrIndex == null) return null;
        String q = idOrIndex.trim();
        if (q.isEmpty()) return null;
        for (Step s : STEPS) {
            if (q.equalsIgnoreCase(s.id)) return s;
        }
        try {
            int n = Integer.parseInt(q);
            if (n >= 1 && n <= STEPS.size()) return STEPS.get(n - 1);
        } catch (Exception ignored) {
        }
        for (Step s : STEPS) {
            if (s.title != null && s.title.contains(q)) return s;
        }
        return null;
    }

    public static synchronized List<Step> list() {
        return new ArrayList<Step>(STEPS);
    }

    public static synchronized boolean isEmpty() {
        return STEPS.isEmpty();
    }

    public static synchronized int pendingCount() {
        int n = 0;
        for (Step s : STEPS) {
            if (!s.done) n++;
        }
        return n;
    }

    public static synchronized String pendingTitles() {
        StringBuilder sb = new StringBuilder();
        for (Step s : STEPS) {
            if (s.done) continue;
            if (sb.length() > 0) sb.append("；");
            sb.append(s.title);
        }
        return sb.toString();
    }

    /** 给模型看的一行式清单 */
    public static synchronized String render() {
        if (STEPS.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Step s : STEPS) {
            sb.append(s.done ? "[x] " : "[ ] ").append(s.id).append(' ').append(s.title);
            if (s.done && s.note != null && !s.note.isEmpty()) {
                sb.append(" —— ").append(s.note);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
