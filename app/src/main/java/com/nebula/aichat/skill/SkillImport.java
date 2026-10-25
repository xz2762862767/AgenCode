package com.nebula.aichat.skill;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能导入 / 导出。
 *
 * 支持的来源：
 * 1. 单个 SKILL.md（带或不带 frontmatter）
 * 2. zip 技能包：包内每个含 SKILL.md 的目录算一个技能，可一次导入多个；
 *    包内同目录的其它文件（references/、scripts/ 等）会一起解出来
 * 3. 直接粘贴的文本
 */
public class SkillImport {

    /** 单个文件上限 */
    private static final int MAX_FILE = 512 * 1024;
    /** 整个压缩包上限 */
    private static final int MAX_ZIP = 4 * 1024 * 1024;

    public static class Result {
        public final List<String> names = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        public boolean ok() {
            return !names.isEmpty();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (!names.isEmpty()) {
                sb.append("导入成功 ").append(names.size()).append(" 个");
                if (names.size() <= 3) sb.append("：").append(join(names));
            }
            if (!errors.isEmpty()) {
                if (sb.length() > 0) sb.append("；");
                sb.append("失败 ").append(errors.size()).append(" 个：").append(errors.get(0));
            }
            return sb.length() == 0 ? "没有可导入的内容" : sb.toString();
        }

        private static String join(List<String> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(list.get(i));
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------ 读取 uri

    public static byte[] readUri(Context c, Uri uri, int limit) throws IOException {
        InputStream in = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("无法打开文件");
            return readAll(in, limit);
        } finally {
            close(in);
        }
    }

    public static String readTextUri(Context c, Uri uri) throws IOException {
        return new String(readUri(c, uri, MAX_FILE), "UTF-8");
    }

    /** uri 的展示名（如 my-skill.zip），取不到时返回空串 */
    public static String displayName(Context c, Uri uri) {
        String name = "";
        try {
            android.database.Cursor cur = c.getContentResolver().query(uri, null, null, null, null);
            if (cur != null) {
                int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (cur.moveToFirst() && idx >= 0) name = cur.getString(idx);
                cur.close();
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = "";
        if (name.isEmpty() && uri.getLastPathSegment() != null) name = uri.getLastPathSegment();
        return name;
    }

    /** 按 uri 导入：自动识别 zip 包 / 单个 markdown */
    public static Result importUri(Context c, Uri uri, String fileName) {
        Result r = new Result();
        try {
            // 先看文件头判断是不是 zip，避免大压缩包被当成文本读而报「文件过大」
            byte[] head = readHead(c, uri, 4);
            boolean zip = isZipHead(head)
                    || (fileName != null && fileName.toLowerCase(Locale.US).endsWith(".zip"));
            if (zip) {
                return importZip(c, readUri(c, uri, MAX_ZIP), fileName);
            }
            String text = new String(readUri(c, uri, MAX_FILE), "UTF-8");
            String fallback = stripExt(fileName == null || fileName.isEmpty() ? "skill.md" : fileName);
            SkillStore.Skill s = importMarkdown(c, text, fallback);
            r.names.add(s.name);
            return r;
        } catch (Exception e) {
            r.errors.add(msg(e));
            return r;
        }
    }

    // ------------------------------------------------------------ 单技能

    /** 从 SKILL.md 文本导入一个技能（自动补 frontmatter、自动启用） */
    public static SkillStore.Skill importMarkdown(Context c, String text, String fallbackName)
            throws Exception {
        String body = text == null ? "" : text;
        if (body.trim().isEmpty()) throw new IOException("内容为空");

        SkillStore.Skill parsed = new SkillStore.Skill();
        SkillStore.parse(parsed, body);

        String name = parsed.name != null && !parsed.name.trim().isEmpty()
                ? parsed.name.trim() : safe(fallbackName);
        String desc = parsed.description == null ? "" : parsed.description.trim();

        String id = uniqueId(c, slug(name));
        File dir = new File(SkillStore.skillsDir(c), id);
        if (!dir.mkdirs()) throw new IOException("无法创建目录 " + id);

        // 原文自带 frontmatter 就原样保留，否则补一个（保证能显示名称/描述）
        String content = body.trim().startsWith("---")
                ? body
                : "---\nname: " + name + "\ndescription: " + desc + "\n---\n\n" + body.trim();
        writeFile(new File(dir, "SKILL.md"), content.getBytes("UTF-8"));

        SkillStore.setEnabled(c, id, true);
        SkillStore.Skill s = new SkillStore.Skill();
        s.id = id;
        s.name = name;
        s.description = desc;
        s.body = parsed.body == null ? "" : parsed.body;
        s.file = new File(dir, "SKILL.md");
        s.enabled = true;
        SkillStore.invalidate(c);
        return s;
    }

    // ------------------------------------------------------------ zip 技能包

    /** 导入 zip：包内每个含 SKILL.md 的目录算一个技能，可一次导入多个 */
    public static Result importZip(Context c, byte[] data, String zipName) {
        Result r = new Result();
        List<ZipSkillScanner.Pack> packs;
        try {
            packs = ZipSkillScanner.scan(data);
        } catch (Exception e) {
            r.errors.add(msg(e));
            return r;
        }
        for (ZipSkillScanner.Pack p : packs) {
            try {
                SkillStore.Skill parsed = new SkillStore.Skill();
                SkillStore.parse(parsed, p.text);

                String base = p.baseName().isEmpty() ? stripExt(zipName) : p.baseName();
                String name = parsed.name != null && !parsed.name.trim().isEmpty()
                        ? parsed.name.trim() : safe(base);
                String id = uniqueId(c, slug(base));

                File target = new File(SkillStore.skillsDir(c), id);
                if (!writeFile(new File(target, "SKILL.md"), p.text.getBytes("UTF-8"))) {
                    throw new IOException("写入失败：SKILL.md");
                }
                for (Map.Entry<String, byte[]> e : p.extras.entrySet()) {
                    if (!writeFile(new File(target, e.getKey()), e.getValue())) {
                        throw new IOException("写入失败：" + e.getKey());
                    }
                }
                SkillStore.setEnabled(c, id, true);
                r.names.add(name);
            } catch (Exception ex) {
                r.errors.add(msg(ex));
            }
        }
        SkillStore.invalidate(c);
        return r;
    }

    // ------------------------------------------------------------ 导出

    /** 导出为可分享的 SKILL.md 全文 */
    public static String exportText(SkillStore.Skill s) {
        if (s == null) return "";
        String desc = s.description == null ? "" : s.description;
        return "---\nname: " + s.name + "\ndescription: " + desc + "\n---\n\n"
                + (s.body == null ? "" : s.body) + "\n";
    }

    // ------------------------------------------------------------ 工具

    /** 名称 → 合法目录名（中文等无法转写时用短哈希兜底） */
    public static String slug(String name) {
        String src = name == null ? "" : name.trim().toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || isCjk(ch)) {
                sb.append(ch);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        String s = sb.toString().replaceAll("-+$", "");
        if (s.length() > 40) s = s.substring(0, 40);
        if (s.isEmpty()) s = "skill-" + Integer.toHexString(Math.abs(src.hashCode()));
        return s;
    }

    /** 中日韩字符直接保留，目录名对用户可读 */
    private static boolean isCjk(char ch) {
        return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF)
                || (ch >= 0x3040 && ch <= 0x30FF) || (ch >= 0xAC00 && ch <= 0xD7AF);
    }

    public static String uniqueId(Context c, String base) {
        File dir = SkillStore.skillsDir(c);
        String id = base;
        int i = 2;
        while (new File(dir, id).exists()) {
            id = base + "-" + i;
            i++;
        }
        return id;
    }

    private static byte[] readHead(Context c, Uri uri, int n) throws IOException {
        InputStream in = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("无法打开文件");
            byte[] buf = new byte[n];
            int got = 0;
            while (got < n) {
                int r = in.read(buf, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            return buf;
        } finally {
            close(in);
        }
    }

    private static boolean isZipHead(byte[] head) {
        return head != null && head.length >= 2 && head[0] == 'P' && head[1] == 'K';
    }

    private static boolean looksLikeZip(String text) {
        return text != null && text.length() > 1 && text.charAt(0) == 'P' && text.charAt(1) == 'K';
    }

    private static boolean writeFile(File f, byte[] data) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(data);
            } finally {
                close(fos);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String normPath(String name) {
        if (name == null) return "";
        String n = name.replace('\\', '/').trim();
        while (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) n = n.substring(1);
        if (n.contains("../")) return "";
        return n;
    }

    private static String lastSeg(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String stripExt(String name) {
        if (name == null) return "skill";
        int i = name.lastIndexOf('.');
        String s = i > 0 ? name.substring(0, i) : name;
        return s.trim().isEmpty() ? "skill" : s.trim();
    }

    private static String safe(String s) {
        return s == null || s.trim().isEmpty() ? "导入的技能" : s.trim();
    }

    private static String msg(Throwable e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.isEmpty() ? "未知错误" : m;
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) throw new IOException("文件过大");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
