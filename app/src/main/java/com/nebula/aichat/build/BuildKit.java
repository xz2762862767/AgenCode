package com.nebula.aichat.build;

import android.content.Context;
import android.util.Log;

import com.nebula.aichat.apk.ApkWorkbench;
import com.nebula.aichat.apk.ZipWriter;
import com.nebula.aichat.linux.LinuxEnv;
import com.nebula.aichat.linux.Toolchain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 源码构建 APK：javac + d8 + aapt2 + apksigner，<b>不依赖 Gradle</b>。
 * <p>
 * 为什么不用 Gradle：AGP 依赖几百 MB Maven 包，还要 aapt2 daemon 与 x86 工具，
 * 在 Alpine/musl + aarch64 上处处是坑。而编一个 APK 真正需要的只有 4 个东西：
 * <pre>
 *   aapt2    资源编译 + link（已随包内置 aarch64 静态版）
 *   javac    .java → .class（Alpine openjdk17-jdk）
 *   d8       .class → classes.dex（内置 d8.jar，纯 Java 可直接跑）
 *   apksigner 签名（内置 apksigner.jar）
 * </pre>
 * 本类负责：资产分发、JDK 探测/安装、路径归一化、构建脚本执行、dex 合包与签名。
 * <p>
 * 目录约定（proot 内）：{@code /exchange/buildkit}
 * 工作目录（Android 侧）：{@code files/linux/exchange/buildkit}
 */
public final class BuildKit {

    private static final String TAG = "BuildKit";

    /** proot 内的工具链根目录 */
    public static final String P_DIR = "/exchange/buildkit";
    /** 随包内置的 jar */
    private static final String[] JARS = {"android.jar", "d8.jar", "apksigner.jar"};
    /** 内置的 aarch64 静态 aapt2（与 APK 工作台共用同一份资产） */
    private static final String AAPT2_ASSET = "apkwork/aapt2";
    /** 签名口令（debug keystore，公开值，仅用于本机调试安装） */
    private static final String KS_PASS = "android";

    private BuildKit() {
    }

    // ------------------------------------------------------------ 目录

    public static File root(Context c) {
        File d = new File(LinuxEnv.get(c).exchangeDir(), "buildkit");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File androidJar(Context c) {
        return new File(root(c), "android.jar");
    }

    private static File binDir(Context c) {
        File d = new File(root(c), "bin");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File keystore(Context c) {
        return new File(root(c), "debug.jks");
    }

    /** 工具链是否已就绪（jar + 包装脚本 + javac） */
    public static boolean isReady(Context c) {
        return androidJar(c).length() > 0 && hasJavac(c);
    }

    // ------------------------------------------------------------ 资产分发

    /**
     * 幂等分发内置资产：3 个 jar + aapt2 + PATH 包装脚本。
     *
     * @return null 表示成功，否则为错误描述
     */
    public static synchronized String ensureAssets(Context c) {
        LinuxEnv env = LinuxEnv.get(c);
        if (env == null || !env.isInstalled()) {
            return "内置 Alpine 环境尚未安装，请先到「环境」页安装";
        }
        File r = root(c);
        for (String n : JARS) {
            File f = new File(r, n);
            if (f.length() > 0) continue;
            try {
                copyAsset(c, "buildkit/" + n, f);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return "解压 " + n + " 失败：" + e;
            }
        }
        // aapt2
        File aapt2 = new File(binDir(c), "aapt2");
        if (aapt2.length() == 0) {
            try {
                copyAsset(c, AAPT2_ASSET, aapt2);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                aapt2.delete();
                return "解压 aapt2 失败：" + e;
            }
        }
        // 包装脚本：让终端里直接敲 d8 / apksigner / apktool
        writeText(new File(binDir(c), "d8"),
                "#!/bin/sh\nexec java -cp " + P_DIR + "/d8.jar com.android.tools.r8.D8 \"$@\"\n");
        writeText(new File(binDir(c), "apksigner"),
                "#!/bin/sh\nexec java -jar " + P_DIR + "/apksigner.jar \"$@\"\n");
        writeText(new File(binDir(c), "apktool"),
                "#!/bin/sh\nexec java -jar /exchange/apkwork/bin/apktool.jar \"$@\"\n");
        // 构建脚本（每次覆盖，改脚本即时生效）
        writeText(new File(root(c), "stage.sh"), stageScript());
        // proot 以 -0 运行，chmod 走 shell 最稳（Android FUSE 下 setExecutable 不保证生效）
        // 另外把工具暴露到 /usr/local/bin，并把 PATH 写进 /etc/profile：
        // 终端/execSync 都用 sh -lc（login shell），会重读 profile，仅靠 env 传 PATH 不可靠。
        env.execSync("chmod -R 755 " + P_DIR + "/bin " + P_DIR + "/aapt2 2>/dev/null; "
                + "chmod 755 " + P_DIR + "/stage.sh 2>/dev/null; "
                + "chmod 644 " + P_DIR + "/*.jar 2>/dev/null; "
                + "mkdir -p /usr/local/bin; "
                + "for t in aapt2 d8 apksigner apktool; do "
                + "ln -sf " + P_DIR + "/bin/$t /usr/local/bin/$t 2>/dev/null; done; "
                + "grep -q 'exchange/buildkit/bin' /etc/profile 2>/dev/null "
                + "|| echo 'export PATH=" + P_DIR + "/bin:$PATH' >> /etc/profile; "
                + "true", 60000);
        return null;
    }

    // ------------------------------------------------------------ JDK

    /** Alpine 里是否有 javac（只装 jre 时没有） */
    public static boolean hasJavac(Context c) {
        String out = LinuxEnv.get(c).execSync(
                "command -v javac >/dev/null 2>&1 && javac -version 2>&1 || echo MISSING", 30000);
        return out != null && out.contains("javac") && !out.contains("MISSING");
    }

    /**
     * 确保有 javac。缺则用 apk 装 openjdk17-jdk（自动多镜像回退，约 180MB）。
     *
     * @return null 表示可用
     */
    public static String ensureJdk(Context c, ApkWorkbench.Progress p) {
        if (hasJavac(c)) return null;
        if (p != null) p.onStep("安装 JDK（openjdk17-jdk，约 180MB）…");
        String err = Toolchain.apkAdd(c, "openjdk17-jdk", p);
        if (err != null) return "安装 JDK 失败：" + err;
        if (!hasJavac(c)) return "JDK 装完仍找不到 javac";
        return null;
    }

    /** 首次构建时生成 debug keystore */
    private static String ensureKeystore(Context c) {
        File ks = keystore(c);
        if (ks.length() > 0) return null;
        String cmd = "keytool -genkeypair -keystore " + P_DIR + "/debug.jks"
                + " -alias androiddebugkey -storepass " + KS_PASS + " -keypass " + KS_PASS
                + " -keyalg RSA -keysize 2048 -validity 10000"
                + " -dname 'CN=Nebula Debug,O=Nebula,C=CN' 2>&1";
        String out = LinuxEnv.get(c).execSync(cmd, 120000);
        if (ks.length() == 0) {
            return "生成签名证书失败：" + (out == null ? "超时" : tail(out, 600));
        }
        return null;
    }

    // ------------------------------------------------------------ 构建

    /** 构建结果 */
    public static final class Result {
        public boolean ok;
        public String message = "";
        public String apkPath;     // Android 侧绝对路径
        public long apkSize;
        public String log = "";
    }

    /**
     * 从源码构建 APK。
     *
     * @param project 工程目录，接受两种写法：
     *                · proot 路径（/exchange/xxx、/root/xxx）
     *                · Android 绝对路径（会自动复制到 exchange 下构建）
     * @param outName 产物文件名（不含 .apk）， 则用工程目录名
     */
    public static Result build(Context c, String project, int minSdk, int targetSdk,
                               String outName, ApkWorkbench.Progress p) {
        Result res = new Result();
        LinuxEnv env = LinuxEnv.get(c);

        String err = ensureAssets(c);
        if (err != null) {
            res.message = err;
            return res;
        }
        if (p != null) p.onStep("检查 JDK…");
        err = ensureJdk(c, p);
        if (err != null) {
            res.message = err;
            return res;
        }
        err = ensureKeystore(c);
        if (err != null) {
            res.message = err;
            return res;
        }

        // ---- 路径归一化
        String projP;
        File projAndroid = null;
        boolean tempCopy = false;
        String trimmed = project == null ? "" : project.trim();
        if (trimmed.length() == 0) {
            res.message = "project 不能为空";
            return res;
        }
        if (trimmed.startsWith("/exchange/") || trimmed.equals("/exchange")
                || trimmed.startsWith("/root") || trimmed.startsWith("/home")) {
            projP = trimmed;
        } else {
            File src = new File(trimmed);
            if (!src.isDirectory() && !trimmed.startsWith("/")) {
                // 模型常传相对目录名（如 "myapp"），在几个常见位置找一遍
                File exch = env.exchangeDir();
                File[] cands = {
                        new File(exch, trimmed),
                        new File(env.rootfs(), "root/" + trimmed),
                        new File(env.rootfs(), "home/" + trimmed)
                };
                for (File cand : cands) {
                    if (cand.isDirectory()) {
                        src = cand;
                        break;
                    }
                }
            }
            if (!src.isDirectory()) {
                res.message = "工程目录不存在：" + trimmed
                        + "\n提示：proot 里能直接看到的是 /exchange 和 /root。";
                return res;
            }
            File exch = env.exchangeDir();
            String exchangeAbs = exch.getAbsolutePath();
            String rootfsAbs = env.rootfs().getAbsolutePath();
            if (trimmed.startsWith(rootfsAbs + "/")) {
                // 工程就在 rootfs 内 → 直接用 proot 路径，无需复制
                projP = trimmed.substring(rootfsAbs.length());
                projAndroid = src;
            } else if (trimmed.startsWith(exchangeAbs)) {
                projP = "/exchange" + trimmed.substring(exchangeAbs.length());
                projAndroid = src;
            } else {
                // 外部路径（如 App 私有 home）→ 复制到 exchange 下构建
                File dst = new File(root(c), "_src");
                try {
                    deleteRecursive(dst);
                    copyDir(src, dst);
                } catch (Exception e) {
                    res.message = "复制工程到构建区失败：" + e;
                    return res;
                }
                projP = P_DIR + "/_src";
                projAndroid = dst;
                tempCopy = true;
            }
        }
        if (p != null) p.onStep("编译中…");

        // ---- 1. aapt2 + javac + d8（proot 内脚本）
        String stage = stageScript();
        String cmd = "sh " + P_DIR + "/stage.sh '" + projP + "' " + minSdk + " " + targetSdk + " 2>&1";
        String out = env.execSync(cmd, 600000L);
        if (out == null) {
            res.message = "构建超时（10 分钟）。工程过大或首次下载 JDK 太慢。";
            return res;
        }
        res.log = out;
        if (out.contains("NEBULA_FAIL")) {
            res.message = "编译失败：\n" + tail(out, 3000);
            return res;
        }

        // ---- 2. Java 侧把 dex 合进 apk（Alpine 没有 zip 命令，顺带保证 resources.arsc 不压缩）
        File projAndroidFile = tempCopy ? new File(root(c), "_src") : androidPathOf(c, projP);
        File buildDir = new File(projAndroidFile, ".nebula-build");
        File baseApk = new File(buildDir, "base.apk");
        File dexDir = new File(buildDir, "dex");
        if (!baseApk.exists()) {
            res.message = "没有产出 base.apk：\n" + tail(out, 2000);
            return res;
        }
        String name = (outName == null || outName.trim().length() == 0)
                ? new File(projP).getName() : outName.trim();
        if (name.endsWith(".apk")) name = name.substring(0, name.length() - 4);
        File unsigned = new File(root(c), name + "-unsigned.apk");
        try {
            repack(baseApk, dexDir, unsigned);
        } catch (Exception e) {
            res.message = "dex 合包失败：" + e;
            return res;
        }

        // ---- 3. 签名
        File signed = new File(root(c), name + ".apk");
        //noinspection ResultOfMethodCallIgnored
        signed.delete();
        String signCmd = "java -jar " + P_DIR + "/apksigner.jar sign"
                + " --ks " + P_DIR + "/debug.jks --ks-pass pass:" + KS_PASS
                + " --key-pass pass:" + KS_PASS
                + " --out \"" + P_DIR + "/" + name + ".apk\""
                + " \"" + P_DIR + "/" + name + "-unsigned.apk\" 2>&1";
        // apksigner 同样会碰 $HOME/.android，proot 里 /root 不可写就会报得莫名其妙
        String signOut = env.execSync("mkdir -p " + P_DIR + "/home " + P_DIR + "/tmp 2>/dev/null; "
                + "HOME=" + P_DIR + "/home TMPDIR=" + P_DIR + "/tmp " + signCmd, 300000L);
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
        if (!signed.exists() || signed.length() == 0) {
            res.message = "签名失败：" + (signOut == null ? "超时" : tail(signOut, 1200));
            return res;
        }

        res.ok = true;
        res.apkPath = signed.getAbsolutePath();
        res.apkSize = signed.length();

        // 产物分发：这里过去只往 buildkit/ 写，而面板列的是 apkwork/out、用户看的是
        // 「AgenCode/APK」，两头都扫不到，于是「构建成功但列表里空着」。各补一份。
        StringBuilder dist = new StringBuilder();
        try {
            File toPanel = new File(ApkWorkbench.outDir(c), name + ".apk");
            if (!toPanel.getAbsolutePath().equals(signed.getAbsolutePath())) {
                ApkWorkbench.copyFile(signed, toPanel);
                dist.append("\n面板产物目录：").append(toPanel.getAbsolutePath());
            }
        } catch (Throwable e) {
            Log.w("nebula", "复制到面板产物目录失败", e);
        }
        try {
            File pubDir = com.nebula.aichat.apk.ArtifactStore
                    .publicSub(com.nebula.aichat.apk.ArtifactStore.SUB_APK);
            File toPub = new File(pubDir, name + ".apk");
            if (!toPub.getAbsolutePath().equals(signed.getAbsolutePath())) {
                ApkWorkbench.copyFile(signed, toPub);
                dist.append("\nAgenCode：").append(toPub.getAbsolutePath());
            }
        } catch (Throwable e) {
            // 没有「所有文件访问」时公共目录写不进去，不算构建失败
            Log.w("nebula", "复制到AgenCode目录失败", e);
        }

        res.message = "构建成功：" + name + ".apk（" + (signed.length() / 1024) + " KB）\n"
                + "APK 位置（终端 /exchange/buildkit 内可见）：" + P_DIR + "/" + name + ".apk"
                + dist;
        return res;
    }

    /**
     * 把 base.apk 与 dex 目录合包。
     * resources.arsc 必须 STORED + 4 字节对齐，否则 targetSdk 30+ 装不上。
     */
    private static void repack(File baseApk, File dexDir, File out) throws IOException {
        ZipFile zip = new ZipFile(baseApk);
        OutputStream os = new FileOutputStream(out);
        ZipWriter zw = new ZipWriter(os);
        try {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                boolean arsc = "resources.arsc".equals(e.getName());
                byte[] data = readAll(zip.getInputStream(e));
                zw.add(e.getName(), data, arsc ? 0 : 6, arsc ? 4 : 1);
            }
            File[] dexes = dexDir.listFiles();
            if (dexes == null) throw new IOException("dex 目录为空");
            for (File d : dexes) {
                if (!d.getName().endsWith(".dex")) continue;
                zw.add(d.getName(), readAll(new FileInputStream(d)), 6, 4);
            }
            zw.finish();
        } finally {
            try {
                zip.close();
            } catch (Exception ignored) {
            }
            try {
                os.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 构建脚本：aapt2 → javac → d8，失败时输出 NEBULA_FAIL 标记 */
    private static String stageScript() {
        return "#!/bin/sh\n"
                + "PROJ=$1; MIN=$2; TGT=$3\n"
                + "BK=" + P_DIR + "\n"
                // login shell 会重读 /etc/profile 把 PATH 冲掉，这里自己兜一层
                + "PATH=\"$BK/bin:$PATH\"; export PATH\n"
                // proot 里是以 uid 0 运行的，$HOME 默认就是 /root：
                // javac / d8 / aapt2 都会往 $HOME 写缓存（.java、.gradle 等），
                // /root 只读或不可写时会冒出一堆看着像 root 权限的错误，统一改到可写目录
                + "mkdir -p \"$BK/tmp\" \"$BK/home\" 2>/dev/null\n"
                + "HOME=\"$BK/home\"; export HOME\n"
                + "TMPDIR=\"$BK/tmp\"; export TMPDIR\n"
                + "JAVA_TOOL_OPTIONS=; export JAVA_TOOL_OPTIONS\n"
                // 工具链缺失要在这一步就讲清楚，否则只会得到一句没有细节的「编译错误」
                + "if ! command -v javac >/dev/null 2>&1; then\n"
                + "  echo 'NEBULA_FAIL: 环境里没有 javac（JDK 没装好）。'\n"
                + "  echo '  去「环境 → 内置 Linux」点一次安装/修复 JDK17，再回来重新编译。'\n"
                + "  echo \"  PATH=$PATH\"\n"
                + "  exit 28\n"
                + "fi\n"
                + "if ! command -v java >/dev/null 2>&1; then\n"
                + "  echo 'NEBULA_FAIL: 环境里没有 java（JDK 没装好），d8 / apksigner 都跑不了。'\n"
                + "  exit 29\n"
                + "fi\n"
                + "AJ=$BK/android.jar\n"
                + "B=\"$PROJ/.nebula-build\"\n"
                + "rm -rf \"$B\"; mkdir -p \"$B/gen\" \"$B/classes\" \"$B/dex\" || exit 20\n"
                + "if [ ! -f \"$PROJ/AndroidManifest.xml\" ]; then\n"
                + "  echo \"NEBULA_FAIL: 找不到 $PROJ/AndroidManifest.xml\"; exit 21\n"
                + "fi\n"
                // 补全 manifest：没有 versionCode 的包在系统眼里是无效包，装上会被拒；
                // 没有 package 的 manifest 连 aapt2 都过不去。都在临时副本上补，不污染工程源文件。
                + "MF=\"$B/AndroidManifest.xml\"; cp \"$PROJ/AndroidManifest.xml\" \"$MF\" || exit 21\n"
                + "if ! grep -q 'android:versionCode' \"$MF\"; then\n"
                + "  sed -i 's#<manifest#<manifest android:versionCode=\"1\" android:versionName=\"1.0\"#' \"$MF\"\n"
                + "  echo 'NEBULA_NOTE:manifest 里没有 android:versionCode，已按 1 / 1.0 补上（否则系统拒绝安装）'\n"
                + "fi\n"
                + "if ! grep -q 'android:versionName' \"$MF\"; then\n"
                + "  sed -i 's#<manifest#<manifest android:versionName=\"1.0\"#' \"$MF\"\n"
                + "fi\n"
                + "if ! grep -q 'package=' \"$MF\"; then\n"
                + "  PKG=\"com.nebula.$(basename \"$PROJ\" | tr -cd 'a-z0-9')\"; [ -n \"$PKG\" ] || PKG=com.nebula.app\n"
                + "  sed -i \"s#<manifest#<manifest package=\\\"$PKG\\\"#\" \"$MF\"\n"
                + "  echo \"NEBULA_NOTE:manifest 缺 package，已补成 $PKG\"\n"
                + "fi\n"
                + "echo '=== [1/3] aapt2 编译资源 ==='\n"
                + "ROPT=''\n"
                + "if [ -d \"$PROJ/res\" ]; then\n"
                + "  aapt2 compile --dir \"$PROJ/res\" -o \"$B/res.zip\" || { echo NEBULA_FAIL:资源编译失败; exit 22; }\n"
                + "  ROPT=\"$B/res.zip\"\n"
                + "fi\n"
                + "echo '=== [2/3] aapt2 link ==='\n"
                + "aapt2 link -o \"$B/base.apk\" -I \"$AJ\" --manifest \"$MF\" "
                + "--min-sdk-version \"$MIN\" --target-sdk-version \"$TGT\" --java \"$B/gen\" "
                + "--auto-add-overlay $ROPT "
                + "|| { echo NEBULA_FAIL:aapt2 link 失败; exit 23; }\n"
                + "echo '=== [3/3] javac + d8 ==='\n"
                + "SRC=$(find \"$PROJ/src\" \"$B/gen\" -name '*.java' 2>/dev/null)\n"
                + "if [ -z \"$SRC\" ]; then echo \"NEBULA_FAIL:找不到 .java（应放在 $PROJ/src）\"; exit 24; fi\n"
                + "if ! javac -nowarn -source 8 -target 8 -bootclasspath \"$AJ\" -encoding UTF-8 "
                + "-d \"$B/classes\" $SRC > \"$B/javac.log\" 2>&1; then\n"
                + "  echo NEBULA_FAIL:javac 编译错误; grep -v 'bootstrap class path' \"$B/javac.log\" | head -40; exit 25\n"
                + "fi\n"
                + "CLS=$(find \"$B/classes\" -name '*.class' 2>/dev/null)\n"
                + "if [ -z \"$CLS\" ]; then echo NEBULA_FAIL:没有产出 class; exit 26; fi\n"
                + "if ! d8 --lib \"$AJ\" --min-api \"$MIN\" --output \"$B/dex\" $CLS > \"$B/d8.log\" 2>&1; then\n"
                + "  echo NEBULA_FAIL:d8 失败; head -40 \"$B/d8.log\"; exit 27\n"
                + "fi\n"
                + "echo NEBULA_OK\n";
    }

    // ------------------------------------------------------------ 工具

    /** proot 路径 → Android 侧 File */
    private static File androidPathOf(Context c, String prootPath) {
        File exch = LinuxEnv.get(c).exchangeDir();
        if (prootPath.startsWith("/exchange")) {
            return new File(exch.getAbsolutePath() + prootPath.substring("/exchange".length()));
        }
        // /root、/home 落在 rootfs 内，Android 侧不可直读，返回 exchange（仅用于兜底）
        return exch;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void copyAsset(Context c, String asset, File dst) throws IOException {
        InputStream in = c.getAssets().open(asset);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.flush();
                out.getFD().sync();
            } finally {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void writeText(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "write " + f.getName() + " failed: " + e);
        }
    }

    public static void copyDir(File src, File dst) throws IOException {
        if (!dst.exists() && !dst.mkdirs()) throw new IOException("mkdirs " + dst);
        File[] fs = src.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.equals(".nebula-build") || n.equals(".gradle")) continue;
            File t = new File(dst, n);
            if (f.isDirectory()) {
                copyDir(f, t);
            } else {
                InputStream in = new FileInputStream(f);
                try {
                    OutputStream out = new FileOutputStream(t);
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
            }
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

    public static String tail(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s;
        return "…\n" + s.substring(s.length() - n);
    }

    /** 供 UI 展示：APK 是否就绪、体积 */
    public static String statusText(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append(isReady(c) ? "就绪" : "未就绪");
        if (!hasJavac(c)) sb.append(" · 缺 JDK");
        if (androidJar(c).length() == 0) sb.append(" · 缺 android.jar");
        return sb.toString();
    }

    /** 待构建工程清单（供面板选择） */
    public static List<File> recentProjects(Context c) {
        List<File> out = new ArrayList<File>();
        File[] fs = LinuxEnv.get(c).exchangeDir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory() && new File(f, "AndroidManifest.xml").exists()) out.add(f);
            }
        }
        return out;
    }
}
