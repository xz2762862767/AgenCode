package com.nebula.aichat.apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 沙箱 ↔ 手机目录的桥。
 *
 * 原来的问题：proot 里只挂了应用私有的 exchange 目录，AI 做出来的 APK、图片、工程全落在
 * /data/data/... 深处 —— 用户在文件管理器里看不到，系统安装器也够不着，等于白做。
 *
 * 这里做三件事：
 * 1）给 proot 补两个挂载：/sdcard（手机公共存储）、/sdcard-app（应用专属外部目录，免权限可写）
 * 2）在 /exchange/out 建一个软链，指向「AgenCode」，AI 有个固定出口
 * 3）每条命令跑完，把工作区里新生成的成品自动复制进「AgenCode」，并回报给对话
 *
 * 第 3 条是关键：不管有没有存储权限、有没有挂 /sdcard，成品最终都会出现在同一个
 * 「AgenCode」里，APK 可以直接点安装，不会再出现「做出来了但装不上」。
 */
public final class SandboxBridge {

    private static final String TAG = "SandboxBridge";

    /** 沙箱内看到的手机公共存储（有「所有文件访问」时可写，否则只读/受限） */
    public static final String IN_PUBLIC = "/sdcard";
    /** 沙箱内看到的应用专属外部目录（任何权限下都能读写，但对文件管理器不可见） */
    public static final String IN_APP_EXT = "/sdcard-app";
    /** 沙箱内的产物出口，软链到「AgenCode」 */
    public static final String IN_OUT = "/exchange/out";

    /** 值得回报给用户的成品类型；.o/.class/.tmp 之类中间文件不打扰 */
    private static final String[] ARTIFACT_EXT = {
            "apk", "aab", "apks", "zip", "jar", "aar", "so",
            "png", "jpg", "jpeg", "webp", "gif", "svg",
            "mp4", "mp3", "wav", "pdf", "docx", "xlsx",
            "md", "log", "txt"
    };
    /** 这些目录是工具链/环境，不是产物 */
    private static final Set<String> SKIP_DIRS = new HashSet<String>(Arrays.asList(
            "bin", "buildkit", "rootfs", "tmp", "root", ".git", "node_modules",
            "__pycache__", ".gradle", ".cache", ".idea", "proc", "sys", "dev"));
    /** 单条命令最多回报几个产物，多了刷屏 */
    private static final int MAX_REPORT = 5;
    /** 扫描深度，太深会拖慢每条命令 */
    private static final int MAX_DEPTH = 5;
    private static final long MIN_SIZE = 512;
    private static final String PREF = "sandbox_sync";
    private static final int PREF_MAX = 200;

    private SandboxBridge() {
    }

    // ------------------------------------------------------------------ 挂载

    /**
     * proot 需要的额外 bind，格式 host:guest。
     * 只在宿主目录存在时才加，避免 proot 启动失败。
     */
    public static List<String> mounts(Context c) {
        List<String> list = new ArrayList<String>();
        try {
            File pub = Environment.getExternalStorageDirectory();
            if (pub != null && pub.isDirectory()) {
                list.add(pub.getAbsolutePath() + ":" + IN_PUBLIC);
            }
        } catch (Throwable t) {
            Log.w(TAG, "public mount skipped: " + t);
        }
        try {
            File ext = c == null ? null : c.getExternalFilesDir(null);
            if (ext != null && ext.isDirectory()) {
                list.add(ext.getAbsolutePath() + ":" + IN_APP_EXT);
            }
        } catch (Throwable t) {
            Log.w(TAG, "app-ext mount skipped: " + t);
        }
        return list;
    }

    /** 公共存储当前可写吗（决定 /exchange/out 该指向哪儿） */
    public static boolean publicWritable(Context c) {
        try {
            return ApkExporter.canWritePublicDir(c);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String lastOutTarget;

    /**
     * 在 exchange 目录里准备 /exchange/out 软链。
     * 链的目标必须是「沙箱内路径」，所以要按权限挑 /sdcard 或 /sdcard-app。
     */
    public static synchronized void prepare(Context c, File exchange) {
        if (exchange == null || !exchange.isDirectory()) return;
        String target = publicWritable(c)
                ? IN_PUBLIC + "/" + ArtifactStore.NAME
                : IN_APP_EXT + "/" + ArtifactStore.NAME;
        String stamp = target;
        File mark = new File(exchange, ".out-target");
        if (stamp.equals(lastOutTarget) && readText(mark).equals(stamp)) return;
        // 目标目录先建出来，软链不至于悬空
        try {
            ArtifactStore.writableDir(c).mkdirs();
        } catch (Throwable ignored) {
        }
        File link = new File(exchange, "out");
        try {
            if (link.exists()) deleteRecursive(link);
            Process p = new ProcessBuilder("ln", "-s", target, link.getAbsolutePath())
                    .redirectErrorStream(true).start();
            p.waitFor();
            lastOutTarget = target;
            try {
                java.io.OutputStream os = new java.io.FileOutputStream(mark, false);
                try {
                    os.write(stamp.getBytes("UTF-8"));
                } finally {
                    os.close();
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "out link failed: " + t);
        }
    }

    /** 一句话说明沙箱里能往哪儿放东西，拼进系统提示词 */
    public static String describe(Context c) {
        boolean pub = publicWritable(c);
        StringBuilder sb = new StringBuilder();
        sb.append("【产物落盘】你在终端里做的成品，请直接放到用户能看到的地方：\n");
        if (pub) {
            sb.append("· ").append(IN_PUBLIC).append(" 就是手机存储根目录，")
                    .append(IN_PUBLIC).append("/Download 就是「下载」文件夹，可以读写。\n");
        } else {
            sb.append("· 用户还没开「所有文件访问」，").append(IN_PUBLIC)
                    .append(" 下写文件可能失败；这种情况请放到 ").append(IN_OUT).append("。\n");
        }
        sb.append("· ").append(IN_OUT).append(" 是固定产物出口，等于手机上的「AgenCode」文件夹。\n");
        sb.append("· 构建出 APK 就放进 ").append(IN_OUT).append(" 或 ")
                .append(IN_PUBLIC).append("/Download，然后告诉用户文件名 —— 系统会自动同步进")
                .append("「AgenCode」，用户点一下就能安装。\n");
        sb.append("· 不要把成品只留在工作目录里，那样用户在文件管理器里找不到。");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 产物同步

    /**
     * 把 dir 下自 sinceMs 之后新出现的成品，复制进「AgenCode」。
     *
     * @return 每行形如「app-release.apk → /sdcard/AgenCode/APK/app-release.apk」
     */
    public static List<String> syncArtifacts(Context c, File dir, long sinceMs) {
        List<String> notes = new ArrayList<String>();
        if (c == null || dir == null || !dir.isDirectory()) return notes;
        List<File> found = new ArrayList<File>();
        collect(dir, sinceMs, found, 0, true);
        if (found.isEmpty()) return notes;
        Collections.sort(found, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                return x == y ? 0 : (x < y ? 1 : -1); // 新的排前面
            }
        });
        SharedPreferences sp = prefs(c);
        int n = 0;
        for (File f : found) {
            if (n >= MAX_REPORT) break;
            String fp = fingerprint(f);
            if (sp.getBoolean(fp, false)) continue; // 已经同步过，不重复导入
            try {
                String where = deliver(c, f);
                if (where != null) {
                    notes.add(f.getName() + " → " + where);
                    sp.edit().putBoolean(fp, true).apply();
                    n++;
                }
            } catch (Throwable t) {
                Log.w(TAG, "sync " + f.getName() + " failed: " + t);
            }
        }
        trimPrefs(sp);
        return notes;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String fingerprint(File f) {
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    /** 记录条目别无限涨 */
    private static void trimPrefs(SharedPreferences sp) {
        try {
            if (sp.getAll().size() <= PREF_MAX) return;
            SharedPreferences.Editor e = sp.edit();
            int i = 0;
            for (String k : sp.getAll().keySet()) {
                if (i++ % 2 == 0) e.remove(k);
            }
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    private static void collect(File dir, long sinceMs, List<File> out, int depth, boolean rootLevel) {
        if (depth > MAX_DEPTH || out.size() >= 40) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f == null || f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (SKIP_DIRS.contains(f.getName().toLowerCase(Locale.ROOT))) continue;
                // 工作区根的 out 是指向产物目录的软链，跟着走会自吞
                if (rootLevel && "out".equals(f.getName())) continue;
                collect(f, sinceMs, out, depth + 1, false);
                continue;
            }
            if (!f.isFile() || f.length() < MIN_SIZE) continue;
            if (f.lastModified() < sinceMs) continue;
            if (!isArtifact(f.getName())) continue;
            out.add(f);
        }
    }

    private static boolean isArtifact(String name) {
        String ext = extOf(name);
        for (String e : ARTIFACT_EXT) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i <= 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** 复制进「AgenCode」，返回落点描述；已是最新则返回 null */
    private static String deliver(Context c, File src) throws IOException {
        String name = src.getName();
        String ext = extOf(name);
        String sub;
        if ("apk".equals(ext) || "apks".equals(ext) || "aab".equals(ext)) {
            sub = ArtifactStore.SUB_APK;
        } else if ("log".equals(ext) || "md".equals(ext) || "txt".equals(ext)) {
            sub = ArtifactStore.SUB_LOG;
        } else {
            sub = "";
        }
        // 公共目录可写时先查同体积同名文件，避免每条命令都重写一遍
        if (publicWritable(c)) {
            File dstDir = sub.isEmpty() ? ArtifactStore.publicDir() : ArtifactStore.publicSub(sub);
            File dst = new File(dstDir, name);
            if (dst.isFile() && dst.length() == src.length()) return null;
        }
        return ApkExporter.exportAny(c, src, sub, name);
    }

    private static String readText(File f) {
        if (f == null || !f.isFile()) return "";
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            java.io.InputStream is = new java.io.FileInputStream(f);
            try {
                int n = is.read(b);
                return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
            } finally {
                is.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File x : fs) deleteRecursive(x);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
