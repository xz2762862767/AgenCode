package com.nebula.aichat.apk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 修改清单（改动履历）。
 *
 * <p>每改一处就往工程目录下 .nebula_changes.json 追加一条，
 * 回编译时汇总成一张表：改了哪个文件、改成了什么、为什么改。
 * 用户拿到包的同时能看到「这个包到底动了哪里」。
 */
public class ChangeLog {

    private static final String FILE = ".nebula_changes.json";
    private static final int MAX_ITEMS = 300;

    /** 改动类型 */
    public static final String KIND_UNPACK = "解包";
    public static final String KIND_WRITE = "改文件";
    public static final String KIND_CREATE = "新建文件";
    public static final String KIND_APPNAME = "应用名";
    public static final String KIND_REPLACE = "替换文件";
    public static final String KIND_REPACK = "回编译";

    private static File file(Context c, String project) {
        return new File(ApkWorkbench.projectDir(c, project), FILE);
    }

    /**
     * 记录一条改动。
     *
     * @param kind 改动类型，见上面的常量
     * @param path 涉及的文件 / 对象
     * @param note 一句话说明（改成了什么、为什么）
     */
    public static void record(Context c, String project, String kind, String path, String note) {
        if (project == null || project.trim().isEmpty()) return;
        try {
            JSONArray arr = readArr(c, project);
            JSONObject o = new JSONObject();
            o.put("time", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            o.put("kind", kind == null ? "" : kind);
            o.put("path", path == null ? "" : path);
            o.put("note", note == null ? "" : note);
            arr.put(o);
            while (arr.length() > MAX_ITEMS) arr.remove(0);
            ApkWorkbench.writeText(file(c, project), arr.toString(2));
        } catch (Throwable ignored) {
            // 记录失败不影响主流程
        }
    }

    public static JSONArray list(Context c, String project) {
        return readArr(c, project);
    }

    public static int count(Context c, String project) {
        return readArr(c, project).length();
    }

    private static JSONArray readArr(Context c, String project) {
        try {
            File f = file(c, project);
            if (!f.exists()) return new JSONArray();
            String s = ApkWorkbench.readText(f, 300000);
            if (s == null || s.trim().isEmpty()) return new JSONArray();
            return new JSONArray(s);
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    /**
     * 生成给人看的清单（Markdown 表格）。没有改动时返回空串。
     */
    public static String render(Context c, String project) {
        JSONArray arr = readArr(c, project);
        if (arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("| # | 时间 | 类型 | 对象 | 说明 |\n");
        sb.append("|---|------|------|------|------|\n");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            sb.append("| ").append(i + 1)
                    .append(" | ").append(esc(o.optString("time")))
                    .append(" | ").append(esc(o.optString("kind")))
                    .append(" | ").append(esc(o.optString("path")))
                    .append(" | ").append(esc(o.optString("note")))
                    .append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 卡片块格式：App 端会把它渲染成「修改清单」列表，而不是普通文字。
     * 每行 类型|对象|说明
     */
    public static String renderBlock(Context c, String project) {
        JSONArray arr = readArr(c, project);
        if (arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder("%%CHANGES%%\n");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            sb.append(esc(o.optString("kind"))).append('|')
                    .append(esc(o.optString("path"))).append('|')
                    .append(esc(o.optString("note")));
            String t = o.optString("time");
            if (!t.isEmpty()) sb.append(" · ").append(t);
            sb.append('\n');
        }
        sb.append("%%END%%\n");
        return sb.toString();
    }

    /**
     * 回编译后追加在结果末尾的完整报告。清单用卡片标记块，
     * App 直接渲染成列表（模型原样保留即可）。
     */
    public static String report(Context c, String project) {
        String block = renderBlock(c, project);
        if (block.isEmpty()) {
            return "\n\n本工程没有记录到文件改动（可能是仅重新打包）。\n";
        }
        int n = readArr(c, project).length();
        return "\n\n以上改动共 " + n + " 处，清单如下（系统生成，%%CHANGES%% 到 %%END%% 之间请原样保留，不要改写）：\n"
                + block
                + "清单同时保存为工程目录下的 CHANGES.md，可随时查看。\n";
    }

    /** 把清单写成 CHANGES.md，方便用户在沙箱页直接打开 */
    public static void writeMarkdown(Context c, String project) {
        try {
            String table = render(c, project);
            if (table.isEmpty()) return;
            String md = "# 修改清单\n\n工程：" + project + "\n\n生成时间："
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\n\n" + table;
            ApkWorkbench.writeText(new File(ApkWorkbench.projectDir(c, project), "CHANGES.md"), md);
        } catch (Throwable ignored) {
        }
    }

    /** 表格里不能出现竖线和换行 */
    private static String esc(String s) {
        if (s == null) return "";
        String r = s.replace("|", "／").replace("\r", " ").replace("\n", " ");
        if (r.length() > 80) r = r.substring(0, 80) + "…";
        return r;
    }
}
