package com.nebula.aichat.skill;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
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
 * 纯 Java 的 zip 技能包扫描器（不依赖 Android，方便单元测试）。
 *
 * 规则：包里每个含 SKILL.md（大小写不限）的目录算一个技能；
 * 该目录下的其它文件作为附带文件一起保留。
 */
public class ZipSkillScanner {

    public static final int MAX_FILE = 512 * 1024;
    public static final int MAX_TOTAL = 4 * 1024 * 1024;

    /** 一个技能包内技能 */
    public static class Pack {
        /** 包内目录，根目录为空串 */
        public String dir = "";
        /** SKILL.md 的原始条目名（大小写可能不同） */
        public String mdName = "SKILL.md";
        /** SKILL.md 文本内容 */
        public String text = "";
        /** 相对该目录的其它文件：路径 → 数据 */
        public final Map<String, byte[]> extras = new LinkedHashMap<>();

        /** 用于兜底命名的目录名（根目录技能返回空串） */
        public String baseName() {
            if (dir.isEmpty()) return "";
            int i = dir.lastIndexOf('/');
            return i >= 0 ? dir.substring(i + 1) : dir;
        }
    }

    /** 扫描压缩包，按出现顺序返回所有技能 */
    public static List<Pack> scan(byte[] data) throws IOException {
        Map<String, byte[]> files = readWithFallback(data);
        if (files.isEmpty()) throw new IOException("压缩包是空的");

        Set<String> dirs = new LinkedHashSet<>();
        for (String n : files.keySet()) {
            String low = n.toLowerCase(Locale.US);
            if (low.equals("skill.md")) dirs.add("");
            else if (low.endsWith("/skill.md")) {
                dirs.add(n.substring(0, n.length() - "skill.md".length() - 1));
            }
        }
        if (dirs.isEmpty()) throw new IOException("包里没找到 SKILL.md");

        List<Pack> out = new ArrayList<>();
        for (String dir : dirs) {
            Pack p = new Pack();
            p.dir = dir;
            String prefix = dir.isEmpty() ? "" : dir + "/";
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                String key = e.getKey();
                if (key.equalsIgnoreCase(prefix + "SKILL.md")) {
                    p.mdName = lastSeg(key);
                    p.text = new String(e.getValue(), "UTF-8");
                }
            }
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                String key = e.getKey();
                String rel;
                if (prefix.isEmpty()) {
                    // 根目录技能只收顶层文件，避免把别的技能目录吸进来
                    if (key.indexOf('/') >= 0) continue;
                    rel = key;
                } else {
                    if (!key.startsWith(prefix)) continue;
                    rel = key.substring(prefix.length());
                }
                if (rel.isEmpty() || rel.equalsIgnoreCase("SKILL.md")) continue;
                p.extras.put(rel, e.getValue());
            }
            out.add(p);
        }
        return out;
    }

    /**
     * 文件名编码自适应：先按 UTF-8 解，失败（或解出乱码）再用 GBK。
     * Windows 压缩工具打的包中文名是 GBK，且不带 UTF-8 标志，直接按 UTF-8 解会抛异常。
     */
    static Map<String, byte[]> readWithFallback(byte[] data) throws IOException {
        try {
            Map<String, byte[]> utf8 = readEntries(data, Charset.forName("UTF-8"));
            if (!hasMojibake(utf8)) return utf8;
        } catch (Exception ignored) {
            // 落到 GBK
        }
        try {
            return readEntries(data, Charset.forName("GBK"));
        } catch (Exception e) {
            throw new IOException("无法解析压缩包（文件名编码异常）");
        }
    }

    static Map<String, byte[]> readEntries(byte[] data, Charset cs) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data), cs);
        try {
            int total = 0;
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = normPath(e.getName());
                if (n.isEmpty() || n.startsWith("__MACOSX/")) continue;
                if (n.endsWith(".DS_Store") || n.startsWith(".")) continue;
                byte[] b = readAll(zis, MAX_FILE);
                total += b.length;
                if (total > MAX_TOTAL) throw new IOException("技能包过大（>4MB）");
                map.put(n, b);
            }
        } finally {
            try {
                zis.close();
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    private static boolean hasMojibake(Map<String, byte[]> files) {
        for (String k : files.keySet()) {
            if (k.indexOf('\uFFFD') >= 0) return true;
        }
        return false;
    }

    static String normPath(String name) {
        if (name == null) return "";
        String n = name.replace('\\', '/').trim();
        while (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) n = n.substring(1);
        if (n.contains("../")) return "";
        return n;
    }

    static String lastSeg(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
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
}
