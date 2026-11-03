package com.nebula.aichat.apk;

import android.content.Context;

import com.nebula.aichat.linux.LinuxEnv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;

/**
 * 内置逆向工具箱：jadx / apktool / baksmali / smali / unzip。
 *
 * <p>设计要点：
 * <ul>
 *   <li>资产随包内置在 assets/revtools，安装后第一次用到时释放到
 *       {@code LinuxEnv.exchangeDir()/revtools}，proot 内路径是 {@code /exchange/revtools}。</li>
 *   <li>释放后生成 {@code bin/} 下的包装脚本，proot 里直接敲 {@code jadx xxx.apk} 就能用，
 *       不需要模型自己拼 java -cp 长命令（拼错一个字符就白跑一轮）。</li>
 *   <li>apktool 直接复用 {@code /exchange/apkwork/bin/apktool.jar}，不再重复占体积。</li>
 *   <li>unzip 用 Alpine 自带的 busybox unzip，无需额外资产。</li>
 * </ul>
 */
public final class RevToolbox {

    public interface Progress {
        void onStep(String step);
    }

    private static final String ASSET_DIR = "revtools";
    /** 资产版本：资产内容变了就加一，手机上升级 APK 后会自动重放 */
    private static final String ASSET_VERSION = "1";
    private static final String STAMP = ".stamp";

    /** jadx 单次可用内存。手机内存有限，太大反而会被系统 OOM 杀掉 */
    private static final String JADX_XMX = "768m";

    private static final Object LOCK = new Object();
    private static volatile String lastError = null;

    private RevToolbox() {
    }

    // ---------------------------------------------------------------- 路径

    public static File root(Context c) {
        File d = new File(LinuxEnv.get(c).exchangeDir(), ASSET_DIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File binDir(Context c) {
        File d = new File(root(c), "bin");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** proot 内的工具箱根目录 */
    public static String prootRoot() {
        return "/exchange/" + ASSET_DIR;
    }

    /** 拼在命令前面，让 jadx/apktool 等命令直接可用 */
    public static String pathPrefix() {
        return "PATH=" + prootRoot() + "/bin:/exchange/apkwork/bin:$PATH ";
    }

    // ---------------------------------------------------------------- 释放

    /** 是否已经释放好（只看标记，不碰大文件） */
    public static boolean ready(Context c) {
        if (c == null) return false;
        File stamp = new File(root(c), STAMP);
        if (!stamp.isFile() || stamp.length() == 0) return false;
        String want = stampText(c);
        return want.equals(readSmall(stamp));
    }

    /**
     * 幂等释放全部资产并生成包装脚本。
     *
     * @return null 表示可用，否则是失败原因
     */
    public static String ensure(Context c, Progress p) {
        if (c == null) return "上下文为空";
        // 早期版本内置过 dex2jar，现已移除；升级上来的用户磁盘里会残留 20MB，顺手清掉
        File staleD2j = new File(root(c), "dex2jar");
        if (staleD2j.exists()) deleteRec(staleD2j);
        if (ready(c)) return null;
        synchronized (LOCK) {
            if (ready(c)) return null;
            try {
                File root = root(c);
                File bin = binDir(c);
                if (p != null) p.onStep("正在释放内置逆向工具（jadx / smali）…");

                // 清掉旧内容，避免升级后残留旧 jar
                deleteRec(new File(root, "smali"));
                File oldJadx = new File(root, "jadx.jar");
                //noinspection ResultOfMethodCallIgnored
                oldJadx.delete();

                copyAsset(c, ASSET_DIR + "/jadx.jar", new File(root, "jadx.jar"), p);
                copyAssetDir(c, ASSET_DIR + "/smali", new File(root, "smali"), p);

                writeWrappers(bin);
                writeStamp(c, new File(root, STAMP));
                lastError = null;
                return null;
            } catch (Throwable t) {
                lastError = "释放逆向工具失败：" + t;
                return lastError;
            }
        }
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * 内置的 jadx / apktool / baksmali / smali 都是 jar，必须靠 java 跑；
     * 而 java 运行时（openjdk17-jre-headless，约 60MB）不在基础工具链里，
     * 所以每个命令前面都要先自检一次，缺了就给出能看懂的中文指引，
     * 而不是抛一句 "/bin/sh: java: not found" 让人猜。
     */
    private static final String JAVA_GUARD =
            "if ! command -v java >/dev/null 2>&1; then\n"
                    + "  echo \"[内置工具] 缺少 Java 运行时：jadx / apktool / baksmali / smali 都需要它。\" >&2\n"
                    + "  echo \"请到 App『环境』页点『只装 Java 运行时』（约 60MB），装完再执行。\" >&2\n"
                    + "  exit 127\n"
                    + "fi\n";

    private static void writeWrappers(File bin) throws IOException {
        // jadx：反编译 dex/apk 到 java 源码
        sh(bin, "jadx",
                "#!/bin/sh\n"
                        + "# 内置 jadx（反编译 APK/dex → Java 源码）\n"
                        + JAVA_GUARD
                        + "XMX=${REV_XMX:-" + JADX_XMX + "}\n"
                        + "exec java -Xmx$XMX -XX:+UseSerialGC -Dfile.encoding=UTF-8 \\\n"
                        + "  -cp /exchange/revtools/jadx.jar jadx.cli.JadxCLI \"$@\"\n");

        // baksmali：dex → smali
        sh(bin, "baksmali",
                "#!/bin/sh\n"
                        + "# 内置 baksmali（dex → smali 汇编）\n"
                        + JAVA_GUARD
                        + "exec java -Xmx512m -cp '/exchange/revtools/smali/*' \\\n"
                        + "  org.jf.baksmali.Main \"$@\"\n");

        // smali：smali → dex
        sh(bin, "smali",
                "#!/bin/sh\n"
                        + "# 内置 smali（smali → dex 汇编）\n"
                        + JAVA_GUARD
                        + "exec java -Xmx512m -cp '/exchange/revtools/smali/*' \\\n"
                        + "  org.jf.smali.Main \"$@\"\n");

        // apktool：复用已内置的那份 jar，省体积
        sh(bin, "apktool",
                "#!/bin/sh\n"
                        + "# 内置 apktool（APK 解包 / 回打包），jar 复用 /exchange/apkwork\n"
                        + "JAR=/exchange/apkwork/bin/apktool.jar\n"
                        + "if [ ! -f \"$JAR\" ]; then\n"
                        + "  echo \"apktool.jar 尚未释放，请先在 App「环境」页初始化一次\" >&2\n"
                        + "  exit 1\n"
                        + "fi\n"
                        + JAVA_GUARD
                        + "exec java -Xmx512m -jar \"$JAR\" \"$@\"\n");

        // 便捷入口：一键看包信息
        sh(bin, "apkinfo",
                "#!/bin/sh\n"
                        + "# 概览：manifest + 体积构成 + dex 列表\n"
                        + "f=\"$1\"\n"
                        + "if [ -z \"$f\" ] || [ ! -f \"$f\" ]; then echo \"用法: apkinfo <apk>\" >&2; exit 1; fi\n"
                        + "echo \"== 文件 ==\"; ls -lh \"$f\"\n"
                        + "echo \"== dex ==\"; unzip -l \"$f\" | grep -E 'classes[0-9]*\\\\.dex' || echo '（无 dex）'\n"
                        + "echo \"== 体积 top15 ==\"; unzip -l \"$f\" | sort -k1 -n -r | head -16\n"
                        + "echo \"== manifest ==\"\n"
                        + "command -v java >/dev/null 2>&1 || { echo '（未装 Java 运行时，跳过 manifest；到『环境』页点『只装 Java 运行时』）'; exit 0; }\n"
                        + "java -Xmx256m -jar /exchange/apkwork/bin/apktool.jar d -f -s -o /tmp/apkinfo_$$ \"$f\" >/dev/null 2>&1 \\\n"
                        + "  && sed -n '1,60p' /tmp/apkinfo_$$/AndroidManifest.xml && rm -rf /tmp/apkinfo_$$\n");
    }

    private static void sh(File bin, String name, String body) throws IOException {
        File f = new File(bin, name);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(body.getBytes("UTF-8"));
            fos.flush();
        } finally {
            fos.close();
        }
        //noinspection ResultOfMethodCallIgnored
        f.setExecutable(true, false);
    }

    // ---------------------------------------------------------------- 资产拷贝

    private static void copyAssetDir(Context c, String assetDir, File dst, Progress p) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();
        String[] names = c.getAssets().list(assetDir);
        if (names == null || names.length == 0) throw new IOException("资产缺失：" + assetDir);
        for (String n : names) {
            if (!n.endsWith(".jar")) continue;
            copyAsset(c, assetDir + "/" + n, new File(dst, n), p);
        }
    }

    private static void copyAsset(Context c, String asset, File dst, Progress p) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        InputStream in = c.getAssets().open(asset);
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[262144];
            int n;
            long total = 0;
            long lastStep = 0;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                total += n;
                if (p != null && total - lastStep > 24L * 1024 * 1024) {
                    lastStep = total;
                    p.onStep("正在释放 " + new File(asset).getName()
                            + "（" + (total / (1024 * 1024)) + " MB）…");
                }
            }
            fos.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            fos.close();
        }
    }

    // ---------------------------------------------------------------- 杂项

    private static String stampText(Context c) {
        return ASSET_VERSION + "|" + appVer(c);
    }

    private static String appVer(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    private static void writeStamp(Context c, File f) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(stampText(c).getBytes("UTF-8"));
            fos.flush();
        } finally {
            fos.close();
        }
    }

    private static String readSmall(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] b = new byte[512];
                int n;
                while ((n = in.read(b)) != -1) bo.write(b, 0, n);
            } finally {
                in.close();
            }
            return new String(bo.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 删除目录（识别符号链接，不跟随） */
    static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory() && !isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean isSymlink(File f) {
        try {
            File p = f.getParentFile();
            if (p == null) return false;
            File in = new File(p.getCanonicalFile(), f.getName());
            return !in.getCanonicalFile().equals(in.getAbsoluteFile());
        } catch (Throwable t) {
            return false;
        }
    }
}
