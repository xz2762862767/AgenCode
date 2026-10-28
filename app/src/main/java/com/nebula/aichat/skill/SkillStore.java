package com.nebula.aichat.skill;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 技能仓库：从沙箱 skills/ 目录加载 SKILL.md */
public class SkillStore {

    private static final String PREF = "nebula_skills";
    private static final String KEY_ENABLED = "enabled";

    public static class Skill {
        public String id = "";          // 目录名
        public String name = "";        // frontmatter name
        public String description = ""; // frontmatter description
        public String body = "";        // 去掉 frontmatter 的正文
        public File file;
        public boolean enabled;
    }

    public static File skillsDir(Context c) {
        File dir = new File(new File(c.getFilesDir(), "sandbox"), "skills");
        // 已存在就别再 mkdirs：这个方法每次取路径都会被调，别让一次多余的系统调用拖慢点击
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 首次使用时写入两个示例技能，并默认启用 */
    public static void ensureSamples(Context c) {
        SharedPreferences sp0 = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        // 已经初始化过就直接返回：这一步过去每次打开面板都要 list 目录、查文件，
        // 技能一多就全压在点击那一下上
        if (sp0.getBoolean("samples_ready", false)) {
            dedupeOnce(c);
            return;
        }

        File dir = skillsDir(c);
        String[] existing = dir.list();
        boolean empty = existing == null || existing.length == 0;
        if (!empty) {
            sp0.edit().putBoolean("samples_ready", true).apply();
            seedDefaultsOnce(c, dir);
            return;
        }

        writeSample(dir, "code-review", "代码审查",
                "当用户要求审查代码、检查 bug 或改进建议时使用。输出结构化的问题清单与修复补丁。",
                "# 代码审查\n\n"
                        + "## 输出结构\n"
                        + "1. **结论**：一段话说清整体质量与最大风险\n"
                        + "2. **问题清单**：按严重程度排序，每条包含\n"
                        + "   - 位置（文件:行号或函数名）\n"
                        + "   - 问题描述\n"
                        + "   - 修复建议（给出可直接粘贴的代码）\n"
                        + "3. **可读性改进**：命名、注释、拆分长函数\n\n"
                        + "## 检查重点\n"
                        + "- 空指针 / 越界 / 未捕获异常\n"
                        + "- 资源泄漏（流、连接、线程）\n"
                        + "- 并发安全与线程切换\n"
                        + "- 边界条件与空输入\n"
                        + "- 明显的性能问题（循环内 IO、重复计算）\n");

        // 说明：这里原来还硬编码了一个「文本精修」技能，和 assets/skills 里的
        // 「文字润色」(polish-writing) 是同一个东西，装完等于同功能两份。
        // 现在只留内置库那一份，示例技能不再重复造。

        sp0.edit().putBoolean("samples_ready", true).apply();
        invalidate(c);
        seedDefaultsOnce(c, dir);
        installBuiltinQuiet(c, "polish-writing");
    }

    /** 静默安装一个内置技能（已存在就跳过），用于补齐默认能力 */
    private static void installBuiltinQuiet(Context c, String id) {
        try {
            if (new File(skillsDir(c), id).isDirectory()) return;
            for (Builtin b : builtins(c)) {
                if (id.equals(b.id)) {
                    installBuiltin(c, b);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 一次性去重：老版本把「文本精修」和「文字润色」各装了一份，
     * 升级后删掉重复的那份，并从内置库补回唯一的一份，功能不减、上下文变干净。
     */
    private static void dedupeOnce(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("dedupe_skills_v2", false)) return;
        File dir = skillsDir(c);
        File dup = new File(dir, "tighten-writing");
        boolean had = dup.isDirectory();
        deleteRecursive(dup);
        if (had) installBuiltinQuiet(c, "polish-writing");
        invalidate(c);
        sp.edit().putBoolean("dedupe_skills_v2", true).apply();
        // v3：摘要 / 会议纪要 / 周报 三种技能已合并为「文本整理」，
        // 老设备上把这三个旧目录清掉，避免技能列表里重复出现同类条目。
        if (!sp.getBoolean("dedupe_skills_v3", false)) {
            boolean hadOrganize = false;
            for (String old : new String[]{"doc-summary", "meeting-notes", "weekly-report"}) {
                File d = new File(dir, old);
                if (d.isDirectory()) hadOrganize = true;
                deleteRecursive(d);
            }
            if (hadOrganize) installBuiltinQuiet(c, "text-organize");
            invalidate(c);
            sp.edit().putBoolean("dedupe_skills_v3", true).apply();
        }
    }

    /**
     * 示例技能默认开启：内置技能本来就是为了开箱能用，
     * 停用状态会让人以为坏了。只补一次，之后尊重用户的手动开关。
     */
    private static void seedDefaultsOnce(Context c, File dir) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.getBoolean("seeded_defaults", false)) return;
        for (String id : new String[]{"code-review"}) {
            File md = new File(new File(dir, id), "SKILL.md");
            if (md.isFile()) setEnabled(c, id, true);
        }
        sp.edit().putBoolean("seeded_defaults", true).apply();
    }

    private static void writeSample(File dir, String id, String name, String desc, String body) {
        File d = new File(dir, id);
        if (!d.mkdirs()) return;
        String content = "---\nname: " + name + "\ndescription: " + desc + "\n---\n\n" + body;
        try {
            File f = new File(d, "SKILL.md");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    // ===== 缓存 =====
    // 面板每次打开、每次发消息取注入内容，都会走 load()。过去每次都把所有 SKILL.md
    // 从头读一遍，技能一多，点「技能」按钮就要卡上几百毫秒。
    // 现在只 stat 每个 SKILL.md 的 (修改时间,大小) 做指纹，命中就直接复用解析结果。
    private static final class CacheEntry {
        long dirStamp;
        long[] stamps;
        List<Skill> skills;
    }

    private static final Object CACHE_LOCK = new Object();
    private static final java.util.HashMap<String, CacheEntry> CACHE = new java.util.HashMap<>();

    /**
     * 内存告急时把整份技能缓存丢掉。
     * 技能正文是纯文本（单个技能可达几十 KB），每个入口一次全量解析的副本
     * 常驻在堆里，内存紧张时先还给它。失效是安全的：下次访问会按指纹重建。
     */
    public static void clearCache() {
        try {
            synchronized (CACHE_LOCK) {
                CACHE.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 技能目录有改动（新建/导入/编辑/删除）后调用，让缓存失效 */
    public static void invalidate(Context c) {
        try {
            synchronized (CACHE_LOCK) {
                CACHE.remove(skillsDir(c).getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }

    private static File mdOf(File d) {
        File md = new File(d, "SKILL.md");
        if (!md.isFile()) md = new File(d, "skill.md");
        return md.isFile() ? md : null;
    }

    private static List<Skill> withEnabled(List<Skill> src, Set<String> enabled) {
        List<Skill> out = new ArrayList<>(src.size());
        for (Skill s : src) {
            Skill d = new Skill();
            d.id = s.id;
            d.name = s.name;
            d.description = s.description;
            d.body = s.body;
            d.file = s.file;
            d.enabled = enabled.contains(s.id);
            out.add(d);
        }
        return out;
    }

    public static List<Skill> load(Context c) {
        List<Skill> list = new ArrayList<>();
        Set<String> enabled = enabledIds(c);
        File dir = skillsDir(c);
        File[] dirs = dir.listFiles();
        if (dirs == null) return list;
        Arrays.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        // 指纹：只 stat，不读内容
        long[] stamps = new long[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            File md = mdOf(dirs[i]);
            stamps[i] = md == null ? 0 : (md.lastModified() * 31L + md.length());
        }

        String key = dir.getAbsolutePath();
        long dirStamp = dir.lastModified();
        CacheEntry hit;
        synchronized (CACHE_LOCK) {
            hit = CACHE.get(key);
        }
        if (hit != null && hit.dirStamp == dirStamp && Arrays.equals(hit.stamps, stamps)) {
            return withEnabled(hit.skills, enabled);   // 命中：零文件读取
        }

        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File md = mdOf(d);
            if (md == null) continue;
            Skill s = new Skill();
            s.id = d.getName();
            s.file = md;
            try {
                parse(s, readText(md));
            } catch (Exception e) {
                s.name = d.getName();
                s.body = "";
            }
            if (s.name == null || s.name.isEmpty()) s.name = d.getName();
            list.add(s);
        }

        CacheEntry ne = new CacheEntry();
        ne.dirStamp = dirStamp;
        ne.stamps = stamps;
        ne.skills = list;
        synchronized (CACHE_LOCK) {
            CACHE.put(key, ne);
        }
        return withEnabled(list, enabled);
    }

    public static void parse(Skill s, String text) {
        // 记事本 / Windows 导出的文件常带 UTF-8 BOM，会让 startsWith("---") 失配，
        // 结果 frontmatter 全被当成正文、技能名丢失
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String body = text;
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                String fm = text.substring(3, end);
                body = text.substring(end + 4);
                for (String line : fm.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("name:")) s.name = strip(t.substring(5));
                    else if (t.startsWith("description:")) s.description = strip(t.substring(12));
                }
            }
        }
        s.body = body.trim();
    }

    private static String strip(String v) {
        String t = v.trim();
        if (t.length() > 1) {
            boolean dq = t.startsWith("\"") && t.endsWith("\"");
            boolean sq = t.startsWith("'") && t.endsWith("'");
            if (dq || sq) t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static String readText(File f) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream is = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            // 读一半抛异常时也要关，否则文件句柄会泄漏
            try { is.close(); } catch (Exception ignored) { }
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    public static Set<String> enabledIds(Context c) {
        Set<String> set = new HashSet<>();
        try {
            String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY_ENABLED, "");
            if (s == null || s.trim().isEmpty()) return set;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) set.add(arr.optString(i));
        } catch (Exception ignored) {
        }
        return set;
    }

    public static void setEnabled(Context c, String id, boolean on) {
        Set<String> set = enabledIds(c);
        if (on) set.add(id);
        else set.remove(id);
        JSONArray arr = new JSONArray();
        for (String s : set) arr.put(s);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENABLED, arr.toString()).apply();
    }

    /** 生成要注入系统提示词的技能文本（无启用技能时返回空串） */
    public static String buildInjection(Context c) {
        ensureSamples(c);
        List<Skill> all = load(c);
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Skill s : all) {
            if (!s.enabled) continue;
            String body = s.body;
            if (body.length() > 4000) {
                int cut = 4000;
                // 别把 emoji（代理对）劈成半个字符，那会变成乱码
                if (Character.isHighSurrogate(body.charAt(cut - 1))) cut--;
                body = body.substring(0, cut) + "\n…（已截断）";
            }
            String block = "### 技能：" + s.name + "\n" + body + "\n\n";
            if (total + block.length() > 12000) break;
            sb.append(block);
            total += block.length();
        }
        if (sb.length() == 0) return "";
        return "以下是已启用的技能说明，请在实际回答中遵循：\n\n" + sb;
    }

    /**
     * 渐进式注入：system 里只放「技能索引」（一行一个），技能正文由 read_skill 工具按需读取。
     * 过去是把每个启用技能的正文整段塞进 system（上限 12000 字），每轮都要重发，
     * 长对话下这是最大的一笔固定开销；改成索引后每轮只占几百字。
     */
    public static String buildIndex(Context c) {
        ensureSamples(c);
        List<Skill> all = load(c);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Skill s : all) {
            if (!s.enabled) continue;
            String d = s.description == null ? "" : s.description.trim();
            if (d.length() > 60) {
                int cut = 60;
                // 别把 emoji（代理对）劈成半个字符
                if (Character.isHighSurrogate(d.charAt(cut - 1))) cut--;
                d = d.substring(0, cut) + "…";
            }
            sb.append("- ").append(s.id).append('\uFF5C').append(s.name);
            if (d.length() > 0) sb.append('\uFF1A').append(d);
            sb.append('\n');
            n++;
        }
        if (n == 0) return "";
        return "可用技能（" + n + " 个，此处仅列索引，正文需用 read_skill 读取）\n" + sb
                + "\n用户需求与上面某一项明确对应时，先调用 read_skill(技能id) 拿到完整做法再执行；"
                + "不对应就直接回答，不要为了保险把不相关技能也读一遍。\n";
    }

    /** read_skill 工具的实现：按 id 或名称取技能全文 */
    public static String readSkillBody(Context c, String key) {
        ensureSamples(c);
        String k = key == null ? "" : key.trim();
        if (k.length() == 0) {
            return "缺少技能标识。用法：read_skill(skill=\"技能id 或 名称\")";
        }
        List<Skill> all = load(c);
        Skill hit = null;
        for (Skill s : all) {
            if (s.id.equalsIgnoreCase(k)) { hit = s; break; }
        }
        if (hit == null) {
            for (Skill s : all) {
                if (s.name != null && s.name.equals(k)) { hit = s; break; }
            }
        }
        if (hit == null) {
            String lk = k.toLowerCase(java.util.Locale.ROOT);
            for (Skill s : all) {
                String sid = s.id.toLowerCase(java.util.Locale.ROOT);
                if (sid.contains(lk) || (s.name != null && s.name.contains(k))) { hit = s; break; }
            }
        }
        if (hit == null) {
            StringBuilder sb = new StringBuilder("没有找到技能「" + k + "」。当前可用技能：\n");
            for (Skill s : all) sb.append("- ").append(s.id).append(' ').append(s.name).append('\n');
            return sb.toString().trim();
        }
        String body = hit.body == null ? "" : hit.body;
        if (body.length() > 3800) {
            int cut = 3800;
            if (Character.isHighSurrogate(body.charAt(cut - 1))) cut--;
            body = body.substring(0, cut) + "\n…（已截断）";
        }
        return "### 技能：" + hit.name + "\n" + body;
    }

    public static void delete(Context c, Skill s) {
        if (s.file == null) return;
        File dir = s.file.getParentFile();
        if (dir != null) deleteRecursive(dir);
        setEnabled(c, s.id, false);
        invalidate(c);
    }

    public static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 按名称创建技能目录骨架 */
    public static File create(Context c, String dirName, String name, String desc, String body)
            throws Exception {
        File d = new File(skillsDir(c), dirName);
        if (!d.mkdirs()) throw new Exception("目录已存在或无法创建");
        String content = "---\nname: " + name + "\ndescription: " + desc + "\n---\n\n" + body;
        File f = new File(d, "SKILL.md");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
        invalidate(c);
        return f;
    }


    // ---------------------------------------------------------- 内置技能库

    /** 内置技能条目（打包在 assets/skills/ 里） */
    public static class Builtin {
        public String id, name, desc;

        public boolean installed(Context c) {
            return new File(skillsDir(c), id).isDirectory();
        }
    }

    /** index.tsv 每行：id \t 名称 \t 简介 */
    public static List<Builtin> builtins(Context c) {
        List<Builtin> out = new ArrayList<>();
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    c.getAssets().open("skills/index.tsv"), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p.length < 2) continue;
                Builtin b = new Builtin();
                b.id = p[0].trim();
                b.name = p[1].trim();
                b.desc = p.length > 2 ? p[2].trim() : "";
                out.add(b);
            }
        } catch (Exception ignored) {
        } finally {
            if (br != null) try {
                br.close();
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 从内置库安装一个技能；已存在时返回 null */
    public static Skill installBuiltin(Context c, Builtin b) throws Exception {
        File dir = new File(skillsDir(c), b.id);
        if (dir.exists()) return null;
        if (!dir.mkdirs()) throw new Exception("无法创建目录");
        String body = readAsset(c, "skills/" + b.id + "/SKILL.md");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(dir, "SKILL.md"));
        try {
            fos.write(body.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
        setEnabled(c, b.id, true);
        invalidate(c);
        Skill s = new Skill();
        s.id = b.id;
        s.name = b.name;
        s.description = b.desc;
        s.file = new File(dir, "SKILL.md");
        parse(s, body);
        s.enabled = true;
        return s;
    }

    private static String readAsset(Context c, String path) throws Exception {
        java.io.InputStream in = c.getAssets().open(path);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 已启用技能名列表（用于状态展示） */
    public static List<String> enabledNames(Context c) {
        List<String> out = new ArrayList<>();
        for (Skill s : load(c)) if (s.enabled) out.add(s.name);
        Collections.sort(out);
        return out;
    }

    @SuppressWarnings("unused")
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
