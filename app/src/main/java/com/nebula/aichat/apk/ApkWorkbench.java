package com.nebula.aichat.apk;

import android.content.Context;

import com.nebula.aichat.linux.LinuxEnv;
import com.nebula.aichat.linux.Toolchain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * APK 工作台：在内置 Alpine 环境里驱动 apktool 完成反编译 / 回编译，
 * 再由 {@link ApkTools} 做 v1+v2 签名，产出可安装的 APK。
 *
 * 关键设计
 *  · 工作目录放在 LinuxEnv.exchangeDir()/apkwork，proot 内路径是 /exchange/apkwork，
 *    这样 Android 侧可以直接读写反编译出来的 smali / res 文件，不必经过 proot。
 *  · apktool 自带的 aapt2 是 x86 二进制，在 arm64 上会 SIGILL，
 *    因此随包附带一个静态链接的 aarch64 aapt2，用 --aapt 指定。
 *  · JVM 用 Alpine 的 openjdk17-jre-headless，缺失时自动安装（需要联网）。
 */
public final class ApkWorkbench {

    public interface Progress {
        void onStep(String step);

        void onLog(String line);
    }

    private static final String ASSET_DIR = "apkwork";
    private static final String APKTOOL_JAR = "apktool.jar";
    /** 资产里的 aapt2 文件名（随包内置的 aarch64 静态版） */
    private static final String AAPT2_ASSET = "aapt2";
    /** 真正干活的 aapt2，释放到 bin/aapt2.real */
    private static final String AAPT2_REAL = "aapt2.real";
    /** 兼容包装脚本：apktool 需要的 bin/aapt2 */
    private static final String AAPT2 = "aapt2";
    private static final String AAPT2_SHIM = "aapt2shim";

    private ApkWorkbench() {
    }

    // ---------------------------------------------------------------- 目录

    public static File root(Context c) {
        File d = new File(LinuxEnv.get(c).exchangeDir(), "apkwork");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File binDir(Context c) {
        File d = new File(root(c), "bin");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File inDir(Context c) {
        File d = new File(root(c), "in");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File outDir(Context c) {
        File d = new File(root(c), "out");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** 某个工程的反编译目录（Android 侧真实路径） */
    public static File projectDir(Context c, String name) {
        File d = new File(root(c), "projects/" + safeName(name));
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "apk";
        String s = name.trim().replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (s.length() > 48) s = s.substring(0, 48);
        return s.isEmpty() ? "apk" : s;
    }

    /** 把 Android 侧绝对路径转成 proot 内可见路径（工作目录内 → /exchange/...） */
    private static String toProot(Context c, File f) {
        String base = root(c).getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.startsWith(base)) {
            return "/exchange/apkwork" + p.substring(base.length());
        }
        File ex = LinuxEnv.get(c).exchangeDir();
        String exBase = ex.getAbsolutePath();
        if (p.startsWith(exBase)) {
            return "/exchange" + p.substring(exBase.length());
        }
        // 外部包（下载目录）单独绑定见 exec()，这里只用于工作目录内
        return p;
    }

    // ---------------------------------------------------------------- 资源准备

    /**
     * 释放 apktool.jar、aapt2 与 aapt2 兼容包装脚本（幂等）。
     *
     * <p>为什么需要一个包装脚本：apktool 自带的 aapt2 是它自己打的补丁版，
     * 会传一个官方 aapt2 不认识的 {@code -e <file>}（等价于 --emit-ids）。
     * 而且 apktool.json 里那些 prebuilt 二进制全是 x86/ELF，在 arm64 手机
     * 上根本 exec 不了，所以本项目把 prebuilt 全部裁掉，改为把内置的
     * aarch64 静态 aapt2 放到 PATH 里供 apktool 使用，中间垫一层脚本做参数翻译。
     */
    public static void ensureAssets(Context c, Progress p) throws IOException {
        File jar = new File(binDir(c), APKTOOL_JAR);
        File real = new File(binDir(c), AAPT2_REAL);
        File shim = new File(binDir(c), AAPT2);
        if (!jar.exists() || jar.length() < 1024 * 1024) {
            if (p != null) p.onStep("正在释放 apktool…");
            copyAsset(c, ASSET_DIR + "/" + APKTOOL_JAR, jar);
        }
        // 老版本直接把 aapt2 放在 bin/aapt2，先迁移成 aapt2.real，免得被包装脚本盖掉
        if ((!real.exists() || real.length() < 1024 * 1024)
                && shim.exists() && shim.length() > 1024 * 1024) {
            if (shim.renameTo(real)) {
                //noinspection ResultOfMethodCallIgnored
                shim.delete();
            }
        }
        if (!real.exists() || real.length() < 1024 * 1024) {
            if (p != null) p.onStep("正在释放 aapt2…");
            copyAsset(c, ASSET_DIR + "/" + AAPT2_ASSET, real);
        }
        if (!shim.exists() || shim.length() < 100 || shim.length() > 1024 * 1024) {
            if (p != null) p.onStep("正在准备 aapt2 兼容层…");
            copyAsset(c, ASSET_DIR + "/" + AAPT2_SHIM, shim);
        }
        //noinspection ResultOfMethodCallIgnored
        real.setExecutable(true, false);
        //noinspection ResultOfMethodCallIgnored
        shim.setExecutable(true, false);
        //noinspection ResultOfMethodCallIgnored
        binDir(c).setExecutable(true, false);
    }

    private static void copyAsset(Context c, String asset, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        InputStream in = c.getAssets().open(asset);
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            fos.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            fos.close();
        }
    }

    // ---------------------------------------------------------------- 环境检查

    public static boolean linuxReady(Context c) {
        return LinuxEnv.get(c).isInstalled();
    }

    /** 检测 proot 里是否有 java，返回 null 表示可用，否则返回原因 */
    public static String javaIssue(Context c) {
        if (!linuxReady(c)) return "还没有安装内置 Linux 环境";
        String out = shell(c, "command -v java >/dev/null 2>&1 && java -version 2>&1 | head -1", 60000);
        if (out == null || out.trim().isEmpty()) return "Linux 环境里没有 Java 运行时";
        if (out.contains("not found") || out.contains("Error")) return "Linux 环境里没有 Java 运行时";
        return null;
    }

    /** 自动安装 JRE（需要联网），返回 null 表示成功 */
    public static String installJava(Context c, Progress p) {
        if (!linuxReady(c)) {
            return "还没有安装内置 Linux 环境，请先到「环境」页点「安装内置 Linux」";
        }
        if (javaIssue(c) == null) return null;
        if (p != null) p.onStep("准备 Java 运行时（约 60MB，请保持网络与前台）…");
        String err = Toolchain.apkAdd(c, "openjdk17-jre-headless", p);
        if (err != null) return err;
        String issue = javaIssue(c);
        if (issue != null) writeJavaLog(issue);
        return issue;
    }

    /** 确保 Java 可用：缺失时自动安装一次 */
    public static String ensureJava(Context c, Progress p) {
        String issue = javaIssue(c);
        if (issue == null) return null;
        if (issue.contains("Linux")) {
            return issue + "（请先到「环境」页安装内置 Linux）";
        }
        return installJava(c, p);
    }

    private static void writeJavaLog(String text) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(new File("/sdcard/Download/nebula-java.log"), false);
            fos.write(text.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 在 proot 里执行命令，并把 /sdcard 与工作目录都绑定进去。
     * LinuxEnv 已经把 exchange 绑到 /exchange；下载目录这里额外用符号链接方式访问。
     */
    public static String shell(Context c, String cmd, long timeoutMs) {
        return LinuxEnv.get(c).execSync(cmd, timeoutMs);
    }

    /** 反编译：apk → projects/<name>/ */
    public static String unpack(Context c, File apk, String name, Progress p) throws Exception {
        return unpack(c, apk, name, false, p);
    }

    /**
     * @param fast true 表示只反编译资源、跳过 dex（apktool -s），速度快 3~4 倍，
     *             适合只改应用名/图标/布局文案的场景；改 smali 时必须用完整模式。
     */
    public static String unpack(Context c, File apk, String name, boolean fast, Progress p) throws Exception {
        ensureAssets(c, p);
        String issue = ensureJava(c, p);
        if (issue != null) throw new IOException(issue);

        File dst = projectDir(c, name);
        // 清空旧内容，避免 apktool 因残留文件报错
        if (dst.exists()) LinuxEnv.deleteRecursive(dst);
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();

        // 把待改的 APK 放进工作区（proot 内可见）
        File local = new File(inDir(c), safeName(name) + ".apk");
        copyFile(apk, local);

        String srcP = "/exchange/apkwork/in/" + local.getName();
        String outP = "/exchange/apkwork/projects/" + safeName(name);

        if (p != null) p.onStep("正在反编译（大包可能需要几分钟）…");
        String cmd = "cd /exchange/apkwork && PATH=/exchange/apkwork/bin:$PATH java -Xmx512m -jar bin/" + APKTOOL_JAR
                + " d " + q(srcP) + " -o " + q(outP) + " -f" + (fast ? " -s" : "")
                + " --aapt /exchange/apkwork/bin/" + AAPT2 + " 2>&1";
        String out = shell(c, cmd, 900000);
        if (p != null && out != null) p.onLog(tail(out, 1500));

        File manifest = new File(dst, "AndroidManifest.xml");
        if (!manifest.exists()) {
            throw new IOException("反编译失败：\n" + tail(out, 800));
        }
        if (p != null) p.onStep("反编译完成：" + countFiles(dst) + " 个文件");
        return dst.getAbsolutePath();
    }

    /** 回编译并签名，返回最终 APK（Android 侧路径） */
    public static File repack(Context c, String name, Progress p) throws Exception {
        ensureAssets(c, p);
        String issue = ensureJava(c, p);
        if (issue != null) throw new IOException(issue);

        File proj = projectDir(c, name);
        if (!new File(proj, "apktool.yml").exists()) {
            throw new IOException("项目不存在或未反编译：" + proj.getAbsolutePath());
        }

        File unsigned = new File(outDir(c), safeName(name) + "-unsigned.apk");
        if (unsigned.exists()) {
            //noinspection ResultOfMethodCallIgnored
            unsigned.delete();
        }

        String projP = "/exchange/apkwork/projects/" + safeName(name);
        String outP = "/exchange/apkwork/out/" + unsigned.getName();

        // Android 12+ 要求带 intent-filter 的组件显式写 android:exported，
        // 老包改完必然缺这一项，回编译出来会被系统直接拒装 —— 编译前统一补齐
        try {
            int fixed = ManifestFix.patchExported(new File(proj, "AndroidManifest.xml"));
            if (fixed > 0 && p != null) {
                p.onStep("已补齐 " + fixed + " 个组件的 android:exported（Android 12+ 硬要求）");
            }
        } catch (Throwable t) {
            if (p != null) p.onLog("修补 AndroidManifest 失败（继续回编译）：" + t.getMessage());
        }

        if (p != null) p.onStep("正在回编译…");
        String cmd = "cd /exchange/apkwork && PATH=/exchange/apkwork/bin:$PATH java -Xmx512m -jar bin/" + APKTOOL_JAR
                + " b " + q(projP) + " -o " + q(outP) + " --aapt /exchange/apkwork/bin/" + AAPT2 + " 2>&1";
        String out = shell(c, cmd, 900000);
        if (p != null && out != null) p.onLog(tail(out, 1500));

        if (!unsigned.exists() || unsigned.length() < 1024) {
            throw new IOException("回编译失败：\n" + tail(out, 800));
        }

        File toSign;
        if (p != null) p.onStep("正在校验 resources.arsc 对齐…");
        try {
            toSign = ApkTools.ensureInstallable(unsigned,
                    new File(outDir(c), safeName(name) + "-aligned.apk"));
        } catch (Throwable t) {
            if (p != null) p.onLog("对齐重写失败（直接签回编译产物）：" + t.getMessage());
            toSign = unsigned;
        }

        if (p != null) p.onStep("正在签名…");
        File signed = new File(outDir(c), safeName(name) + "-signed.apk");
        ApkTools.signFile(c, toSign, signed);
        if (toSign != unsigned) {
            //noinspection ResultOfMethodCallIgnored
            toSign.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();

        if (p != null) p.onStep("完成：" + signed.getName() + "（" + fmtSize(signed.length()) + "）");
        return signed;
    }

    /** 直接修改 Android 侧的文本文件（模型改 smali / xml 走这里） */
    public static void writeText(File f, String content) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } finally {
            fos.close();
        }
    }

    public static String readText(File f, int maxBytes) throws IOException {
        if (!f.exists()) throw new IOException("文件不存在：" + f.getAbsolutePath());
        if (f.length() > maxBytes) {
            // 只读前面一段，避免把超大文件塞进上下文
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[maxBytes];
                int n = 0;
                while (n < maxBytes) {
                    int r = fis.read(buf, n, maxBytes - n);
                    if (r <= 0) break;
                    n += r;
                }
                return new String(buf, 0, n, "UTF-8") + "\n…（文件较大，已截断，共 "
                        + fmtSize(f.length()) + "）";
            } finally {
                fis.close();
            }
        }
        byte[] data = new byte[(int) f.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < data.length) {
                int r = fis.read(data, off, data.length - off);
                if (r <= 0) break;
                off += r;
            }
        } finally {
            fis.close();
        }
        return new String(data, "UTF-8");
    }

    // ---------------------------------------------------------------- 工具

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String tail(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    public static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", b / 1048576.0);
    }

    private static int countFiles(File dir) {
        int n = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isDirectory()) n += countFiles(f);
            else n++;
        }
        return n;
    }

    public static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        InputStream in = new java.io.FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            out.close();
        }
    }

    /** 列出工作区里的工程名 */
    public static List<String> listProjects(Context c) {
        List<String> out = new ArrayList<String>();
        File d = new File(root(c), "projects");
        File[] fs = d.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory() && new File(f, "apktool.yml").exists()) out.add(f.getName());
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 从任意路径解析出工程内的相对路径，防止越界访问 */
    public static File resolveInProject(Context c, String project, String relPath) throws IOException {
        File base = projectDir(c, project);
        File f = new File(base, relPath == null ? "" : relPath);
        String basePath = base.getCanonicalPath();
        String fPath = f.getCanonicalPath();
        if (!fPath.equals(basePath) && !fPath.startsWith(basePath + File.separator)) {
            throw new IOException("路径越界：" + relPath);
        }
        return f;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
