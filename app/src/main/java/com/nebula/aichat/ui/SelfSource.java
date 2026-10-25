package com.nebula.aichat.ui;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本应用自身源码索引。
 *
 * 构建时 gradle 的 genSelfSrc 任务把 app/src/main/java、res、build.gradle、AndroidManifest.xml
 * 复制进 assets/selfsrc/ 并生成 index.tsv（kind、path、lines、bytes），
 * App 内「项目」页据此展示本应用工程结构，并可直接查看源码。
 */
public final class SelfSource {

    public static final String KIND_JAVA = "java";
    public static final String KIND_RES = "res";
    public static final String KIND_BUILD = "build";

    public static final class Entry {
        public final String kind;
        public final String path;
        public final int lines;
        public final long bytes;

        Entry(String kind, String path, int lines, long bytes) {
            this.kind = kind;
            this.path = path;
            this.lines = lines;
            this.bytes = bytes;
        }

        public String name() {
            int i = path.lastIndexOf('/');
            return i < 0 ? path : path.substring(i + 1);
        }

        /** 分组名：java 为包名（根为 ""），res 为资源类型目录 */
        public String group() {
            int i = path.lastIndexOf('/');
            return i < 0 ? "" : path.substring(0, i);
        }

        public String assetPath() {
            return "selfsrc/" + kind + "/" + path;
        }

        public String lang() {
            String n = path.toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(".java")) return "java";
            if (n.endsWith(".xml")) return "xml";
            if (n.endsWith(".gradle")) return "gradle";
            if (n.endsWith(".json")) return "json";
            return "";
        }
    }

    private static List<Entry> cache;

    private SelfSource() {
    }

    public static synchronized List<Entry> entries(Context c) {
        if (cache != null) return cache;
        List<Entry> out = new ArrayList<>();
        try {
            String txt = readAsset(c, "selfsrc/index.tsv");
            if (txt != null) {
                String[] rows = txt.split("\n");
                for (int i = 0; i < rows.length; i++) {
                    String row = rows[i].trim();
                    if (row.isEmpty()) continue;
                    String[] p = row.split("\t");
                    if (p.length < 4 || "kind".equals(p[0])) continue;
                    out.add(new Entry(p[0], p[1], parseInt(p[2]), parseLong(p[3])));
                }
            }
        } catch (Throwable ignore) {
        }
        cache = out;
        return out;
    }

    public static List<Entry> of(Context c, String kind) {
        List<Entry> all = entries(c);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (kind.equals(e.kind)) out.add(e);
        return out;
    }

    /** 按目录分组，根组排最前，其余按名称排序 */
    public static LinkedHashMap<String, List<Entry>> group(List<Entry> list) {
        List<String> keys = new ArrayList<>();
        Map<String, List<Entry>> map = new LinkedHashMap<>();
        for (Entry e : list) {
            String g = e.group();
            if (!map.containsKey(g)) {
                keys.add(g);
                map.put(g, new ArrayList<Entry>());
            }
            List<Entry> bucket = map.get(g);
            if (bucket != null) bucket.add(e);
        }
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                if (a.isEmpty()) return -1;
                if (b.isEmpty()) return 1;
                return a.compareToIgnoreCase(b);
            }
        });
        LinkedHashMap<String, List<Entry>> out = new LinkedHashMap<>();
        for (String k : keys) out.put(k, map.get(k));
        return out;
    }

    public static String read(Context c, Entry e) {
        try {
            String s = readAsset(c, e.assetPath());
            return s == null ? "// 读取失败：资源不存在" : s;
        } catch (Throwable t) {
            return "// 读取失败：" + t;
        }
    }

    private static String readAsset(Context c, String path) throws Exception {
        InputStream in = c.getAssets().open(path);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }
}
