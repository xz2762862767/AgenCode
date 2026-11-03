package com.nebula.aichat.linux;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置 Alpine Linux 环境（proot 方案）。
 *
 * 组成：
 *  - proot 二进制与 loader、libtalloc 打包在 jniLibs（安装为 libproot.so 等，
 *    放在 nativeLibraryDir 才能被 exec —— Android 10+ 禁止执行私有目录中的可执行文件）
 *  - Alpine minirootfs 打包在 assets/linux/rootfs.tar.gz，首次安装解压到 files/linux/rootfs
 *
 * 安装后可执行 apk add 安装 python3 / nodejs / gcc / git 等真实软件。
 */
public final class LinuxEnv {

    private static final String TAG = "LinuxEnv";
    /** 资产版本，升级 rootfs 时递增可触发重新安装 */
    private static final int ASSET_VERSION = 1;
    /** 资产名刻意不用 .tar.gz：aapt2 会把 .gz 资产自动解压成 .tar，导致体积翻倍 */
    private static final String ASSET_ROOTFS = "linux/rootfs.bin";

    public interface Progress {
        void onProgress(int percent, String message);
    }

    public interface Callback {
        void onOutput(String line);

        void onFinish(int code, String error);

        boolean isCancelled();
    }

    private static volatile LinuxEnv instance;
    private final Context app;

    private LinuxEnv(Context c) {
        this.app = c.getApplicationContext();
    }

    public static LinuxEnv get(Context c) {
        LinuxEnv l = instance;
        if (l == null) {
            synchronized (LinuxEnv.class) {
                if (instance == null) instance = new LinuxEnv(c);
                l = instance;
            }
        }
        return l;
    }

    // ---------------- 路径 ----------------

    public File baseDir() {
        return new File(app.getFilesDir(), "linux");
    }

    public File rootfs() {
        return new File(baseDir(), "rootfs");
    }

    public File tmpDir() {
        return new File(baseDir(), "tmp");
    }

    private File marker() {
        return new File(baseDir(), ".installed");
    }

    /** 供终端绑定的共享目录：手机下载目录 / 应用私有 linux/home */
    public File exchangeDir() {
        File d = new File(baseDir(), "exchange");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private String nativeLibDir() {
        return app.getApplicationInfo().nativeLibraryDir;
    }

    private File prootBin() {
        return new File(nativeLibDir(), "libproot.so");
    }

    public boolean isSupportedAbi() {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        return abi != null && abi.startsWith("arm64");
    }

    public boolean isInstalled() {
        if (!prootBin().exists()) return false;
        File m = marker();
        if (!m.isFile()) return false;
        File sh = new File(rootfs(), "bin/sh");
        return sh.exists() || new File(rootfs(), "bin/busybox").exists();
    }

    /** rootfs 体积缓存：-1 表示尚未计算 */
    private volatile long sizeCache = -1;

    /** 已安装时的体积（字节）。结果缓存，避免每次进页面都递归遍历整个 rootfs */
    public long installedSize() {
        long c = sizeCache;
        if (c >= 0) return c;
        return sizeCache = dirSize(rootfs());
    }

    /** 只读缓存版本（不做 IO，可在主线程安全调用）；未计算过返回 -1 */
    public long cachedSize() {
        return sizeCache;
    }

    /** 安装 / 重置 / 删除环境后调用，让体积缓存失效 */
    public void invalidateSize() {
        sizeCache = -1;
    }

    public String versionText() {
        try {
            File f = new File(rootfs(), "etc/alpine-release");
            if (f.isFile()) {
                byte[] b = TarGz.readAll(f);
                return "Alpine " + new String(b, StandardCharsets.UTF_8).trim();
            }
        } catch (Throwable ignored) {
        }
        return "Alpine";
    }

    // ---------------- 安装日志 ----------------
    // 安装失败时把全过程日志写到 Download/AgenCode-install.log（MediaStore，免权限），
    // 便于用户直接反馈、开发者定位问题。

    private final StringBuilder logBuf = new StringBuilder();

    public void log(String s) {
        Log.i(TAG, s);
        synchronized (logBuf) {
            logBuf.append(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date())).append(' ').append(s).append('\n');
            if (logBuf.length() > 400000) logBuf.delete(0, 200000);
        }
    }

    public String logText() {
        synchronized (logBuf) {
            return logBuf.toString();
        }
    }

    /** 把日志写到 Download/AgenCode-install.log */
    public void dumpLog() {
        String text = logText();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "AgenCode-install.log");
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = app.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = app.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(text.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        os.close();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dumpLog failed: " + t);
        }
    }

    // ---------------- 安装 ----------------

    /** 安装（解压 rootfs + 写网络配置）。阻塞调用，请在后台线程执行。 */
    public synchronized void install(Progress p) throws IOException {
        synchronized (logBuf) {
            logBuf.setLength(0);
        }
        log("==== install start ====");
        log("sdk=" + android.os.Build.VERSION.SDK_INT
                + " abi=" + android.os.Build.SUPPORTED_ABIS[0]
                + " nativeLibDir=" + nativeLibDir());
        try {
            doInstall(p);
            log("==== install OK ====");
        } catch (IOException e) {
            log("==== install FAILED: " + e.getMessage());
            log(Log.getStackTraceString(e));
            dumpLog();
            throw e;
        } catch (Throwable t) {
            log("==== install FAILED(throwable): " + t);
            log(Log.getStackTraceString(t));
            dumpLog();
            throw new IOException(t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
        dumpLog();
    }

    private void doInstall(Progress p) throws IOException {
        if (!isSupportedAbi()) {
            throw new IOException("当前设备架构 " + android.os.Build.SUPPORTED_ABIS[0]
                    + " 不支持内置 Linux（需要 arm64-v8a）");
        }
        log("proot bin=" + prootBin().getAbsolutePath() + " exists=" + prootBin().exists()
                + " len=" + prootBin().length());
        if (!prootBin().exists()) {
            throw new IOException("缺少 proot 组件：" + prootBin().getAbsolutePath());
        }
        File base = baseDir();
        log("baseDir=" + base.getAbsolutePath() + " exists=" + base.exists()
                + " canWrite=" + base.canWrite() + " free=" + humanSize(freeBytes()));
        if (!base.isDirectory() && !base.mkdirs() && !base.isDirectory()) {
            throw new IOException("无法创建目录 " + base);
        }
        File tmp = tmpDir();
        if (!tmp.exists()) //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
        exchangeDir();

        if (p != null) p.onProgress(2, "正在清理旧环境…");
        // 清理历史安装残留（rename-then-delete 可能留下 trash-* 目录）
        File[] olds = baseDir().listFiles();
        if (olds != null) {
            for (File f : olds) {
                if (f.getName().startsWith("trash-")) {
                    log("clean leftover " + f.getName());
                    deleteRecursive(f);
                }
            }
        }
        // 先改名再删：即使删除不彻底（只读文件/损坏链接）也不会挡住新环境创建
        File rf = rootfs();
        log("rootfs=" + rf.getAbsolutePath() + " exists=" + rf.exists()
                + (rf.exists() ? " isDir=" + rf.isDirectory() : ""));
        if (rf.exists()) {
            File trash = new File(baseDir(), "trash-" + System.currentTimeMillis());
            if (rf.renameTo(trash)) {
                deleteRecursive(trash);
            } else {
                deleteRecursive(rf);
                //noinspection ResultOfMethodCallIgnored
                rf.delete();
            }
        }
        log("mkdir rootfs -> isDir=" + rf.isDirectory());
        if (!rf.isDirectory() && !rf.mkdirs() && !rf.isDirectory()) {
            throw new IOException("无法创建目录 " + rf
                    + "（父目录可写=" + baseDir().canWrite()
                    + "，可用空间=" + humanSize(freeBytes()) + "）");
        }

        if (p != null) p.onProgress(5, "正在解压 Alpine 根文件系统…");
        InputStream in = null;
        try {
            in = app.getAssets().open(ASSET_ROOTFS);
            log("asset " + ASSET_ROOTFS + " opened, available=" + in.available());
            TarGz.extract(in, rootfs(), 14L * 1024 * 1024, new TarGz.Progress() {
                @Override
                public void onProgress(int percent, String currentEntry) {
                    if (p != null) p.onProgress(5 + percent * 85 / 100, "解压 " + currentEntry);
                }
            });
        } finally {
            if (in != null) try {
                in.close();
            } catch (IOException ignored) {
            }
        }

        log("extract done, rootfs size=" + humanSize(installedSize())
                + " busybox=" + new File(rootfs(), "bin/busybox").exists()
                + " sh=" + new File(rootfs(), "bin/sh").exists());
        if (p != null) p.onProgress(92, "正在配置网络与目录…");
        configureRootfs();
        log("configureRootfs done");

        if (p != null) p.onProgress(97, "正在校验环境…");
        String check = execSync("cat /etc/alpine-release; uname -m; echo OK-NEBULA", 60000);
        log("self-check output: " + check);
        // 必须真的打印出版本号才算成功，否则 proot 可能已报错退出
        if (check == null || !check.contains("OK-NEBULA")) {
            throw new IOException("环境校验失败：" + (check == null || check.trim().length() == 0
                    ? "proot 无任何输出（可能被系统安全策略阻止执行）" : check.trim()));
        }

        TarGz.writeFile(marker(), String.valueOf(ASSET_VERSION).getBytes(StandardCharsets.UTF_8));
        if (p != null) p.onProgress(100, "安装完成");
        Log.i(TAG, "alpine installed: " + check.trim());
    }

    /** 写 DNS、apk 镜像源、基础目录 */
    private void configureRootfs() throws IOException {
        File etc = new File(rootfs(), "etc");
        //noinspection ResultOfMethodCallIgnored
        etc.mkdirs();
        // DNS：国内可用公共解析
        TarGz.writeFile(new File(etc, "resolv.conf"),
                "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 1.1.1.1\n"
                        .getBytes(StandardCharsets.UTF_8));
        TarGz.writeFile(new File(etc, "hosts"),
                "127.0.0.1 localhost nebula\n::1 localhost\n".getBytes(StandardCharsets.UTF_8));
        // apk 镜像：默认换阿里云，国内拉包快很多
        TarGz.writeFile(new File(etc, "apk/repositories"),
                ("https://mirrors.aliyun.com/alpine/v3.20/main\n"
                        + "https://mirrors.aliyun.com/alpine/v3.20/community\n")
                        .getBytes(StandardCharsets.UTF_8));
        // 基础目录
        for (String d : new String[]{"root", "tmp", "var/tmp", "dev", "proc", "sys", "home", "mnt"}) {
            File f = new File(rootfs(), d);
            //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
        }
        File profile = new File(etc, "profile");
        String add = "\nexport LANG=C.UTF-8\nexport PS1='\\u@nebula:\\w\\$ '\n"
                + "alias ll='ls -alF'\nalias apk-up='apk update && apk upgrade'\n";
        String old = "";
        if (profile.isFile()) {
            old = new String(TarGz.readAll(profile), StandardCharsets.UTF_8);
        }
        if (!old.contains("nebula")) {
            TarGz.writeFile(profile, (old + add).getBytes(StandardCharsets.UTF_8));
        }
    }

    public synchronized void uninstall() {
        deleteRecursive(rootfs());
        //noinspection ResultOfMethodCallIgnored
        marker().delete();
    }

    // ---------------- 执行 ----------------

    /**
     * Android 宿主上的 setsid（toybox applet）。存在才用。
     * 注意：proot 沙盒里的 /system 是 rootfs，跟 Android 的不是一回事，
     * 所以只能在运行时探测宿主机路径，不能在沙盒里查。
     */
    private static String setsidPath() {
        String[] cands = {"/system/bin/setsid", "/system/xbin/setsid"};
        for (String p : cands) {
            try {
                File f = new File(p);
                if (f.exists() && f.canExecute()) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 组装 proot 启动命令 */
    private List<String> buildCommand(String script, boolean login) {
        List<String> cmd = new ArrayList<String>();
        // 让 proot 独立成一个会话/进程组。
        // 原因：proot 带 --kill-on-exit，退出（正常结束、超时被 destroy、用户点停止）
        // 时会清理自己的子进程；万一它按进程组清理，而 proot 又与 App 同组，
        // 就会连 App 一起带走 —— 表现正是"命令跑完/一点停止，App 直接消失"，
        // 而且没有任何 Java 堆栈（历史闪退事故的头号嫌疑）。
        // setsid 把 proot 挪到新会话，它的清理动作就只影响自己。
        String setsid = setsidPath();
        if (setsid != null) cmd.add(setsid);
        cmd.add(prootBin().getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs().getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add("/proc/self/fd:/dev/fd");
        cmd.add("-b");
        cmd.add(exchangeDir().getAbsolutePath() + ":/exchange");
        // 沙箱 ↔ 手机目录：把公共存储 / 应用专属外部目录挂进去，AI 才能直接读写用户看得到的位置，
        // 同时在 /exchange 里准备好 out 软链（指向「AgenCode」）
        try {
            com.nebula.aichat.apk.SandboxBridge.prepare(app, exchangeDir());
            for (String m : com.nebula.aichat.apk.SandboxBridge.mounts(app)) {
                cmd.add("-b");
                cmd.add(m);
            }
        } catch (Throwable t) {
            android.util.Log.w("LinuxEnv", "sandbox mount skipped: " + t);
        }
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("PATH=/exchange/revtools/bin:/exchange/buildkit/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("TERM=xterm-256color");
        cmd.add("LANG=C.UTF-8");
        cmd.add("PWD=/root");
        cmd.add("/bin/sh");
        cmd.add(login ? "-lc" : "-c");
        cmd.add(script);
        return cmd;
    }

    private ProcessBuilder newBuilder(String script, boolean login) {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(script, login));
        pb.redirectErrorStream(true);
        pb.directory(baseDir());
        java.util.Map<String, String> env = pb.environment();
        env.put("PROOT_LOADER", new File(nativeLibDir(), "libprootloader.so").getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmpDir().getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        String lib = nativeLibDir();
        String old = env.get("LD_LIBRARY_PATH");
        env.put("LD_LIBRARY_PATH", old == null || old.length() == 0 ? lib : lib + ":" + old);
        env.put("HOME", "/root");
        env.put("TMPDIR", tmpDir().getAbsolutePath());
        return pb;
    }

    /** 同步执行，返回合并输出（失败返回 null） */
    /**
     * 当前正在执行的进程。工具调用（解包、重打包）动辄几十秒，
     * 用户点「停止」时要能立刻杀掉它，而不是等它自己跑完。
     */
    private static volatile Process currentProc;

    /** 中断当前正在执行的命令，可安全地在任意线程调用 */
    public static void abortCurrent() {
        Process p = currentProc;
        currentProc = null;
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
    }

    public String execSync(String script, long timeoutMs) {
        Process proc = null;
        try {
            proc = newBuilder(script, true).start();
            currentProc = proc;
            log("exec: " + script);
            final Process p = proc;
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream is = p.getInputStream();
                        byte[] buf = new byte[4096];
                        int r;
                        while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
                    } catch (IOException ignored) {
                    }
                }
            });
            reader.start();
            boolean done = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                log("exec TIMEOUT after " + timeoutMs + "ms");
                proc.destroy();
                return null;
            }
            reader.join(2000);
            String out = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            log("exit=" + proc.exitValue() + " out=" + (out.length() > 4000
                    ? out.substring(0, 4000) + "..." : out));
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "execSync failed: " + t);
            return null;
        } finally {
            currentProc = null;
            if (proc != null) try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 异步流式执行（供终端使用），回调在工作线程 */
    public void runAlpine(final String script, final Callback cb) {
        // 逆向工具箱（jadx/apktool/baksmali/smali）首次使用时释放，幂等且很快返回
        try {
            com.nebula.aichat.apk.RevToolbox.ensure(app, null);
        } catch (Throwable t) {
            android.util.Log.w("LinuxEnv", "revtools ensure skipped: " + t);
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Process proc = null;
                int code = -1;
                String err = null;
                try {
                    proc = newBuilder(script, true).start();
            log("exec: " + script);
                    // 限长读行：proot 里的命令同样可能吐出一行几百 MB 的无换行输出
                    // （base64/dd/cat 二进制），用 readLine 会直接把堆撑爆导致闪退
                    com.nebula.aichat.util.LineReader r =
                            new com.nebula.aichat.util.LineReader(proc.getInputStream(), 8192);
                    String line;
                    while ((line = r.next()) != null) {
                        if (cb.isCancelled()) break;
                        cb.onOutput(line);
                    }
                    code = proc.waitFor();
                } catch (Throwable e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    if (proc != null) try {
                        proc.destroy();
                    } catch (Throwable ignored) {
                    }
                    if (cb.isCancelled()) {
                        code = 130;
                    } else if (code > 128 && err == null) {
                        // 137/143 这类被信号杀掉的情况要解释清楚，
                        // 否则用户只看到"退出码 137"，不知道该降内存还是改命令
                        err = com.nebula.aichat.shell.ShellRunner.explainExit(code);
                    }
                    cb.onFinish(code, err);
                }
            }
        }, "alpine-exec");
        t.start();
    }

    /** 直接拿到底层进程（供终端实现交互式中断） */
    public Process startProcess(String script) throws IOException {
        return newBuilder(script, true).start();
    }

    // ---------------- 工具 ----------------

    public static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] fs = f.listFiles();
        if (fs != null) {
            for (File c : fs) sum += dirSize(c);
        }
        return sum;
    }

    /**
     * 递归删除。注意：必须识别符号链接，否则 rootfs 里的 /bin/sh -> /bin/busybox
     * 会被解析成宿主机的真实路径，导致误删系统文件或删除不彻底。
     */
    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (isSymlink(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 判断是否为符号链接（Java 无法直接 lstat，用规范化路径比较） */
    public static boolean isSymlink(File f) {
        try {
            File canon;
            File parent = f.getParentFile();
            if (parent == null) {
                canon = f;
            } else {
                canon = new File(parent.getCanonicalFile(), f.getName());
            }
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    /** 应用数据分区可用空间 */
    public long freeBytes() {
        try {
            android.os.StatFs st = new android.os.StatFs(app.getFilesDir().getAbsolutePath());
            return st.getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
    }
}
