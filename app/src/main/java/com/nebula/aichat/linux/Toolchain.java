package com.nebula.aichat.linux;

import android.content.Context;

import com.nebula.aichat.apk.ApkWorkbench;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * 内置 Linux 工具链：探测 + 一键补齐。
 * <p>
 * 之前的「工具链探测」查的是 Android 系统 PATH，那里本来就没有 java / gcc / python3，
 * 所以永远是一片 ✗。真正能装工具的是内置 Alpine，这里负责：
 * · probe()   一次性在 proot 里批量 command -v，返回可用命令集合
 * · install() 用 apk 装齐基础/开发工具，并把内置静态 aapt2 链接进 PATH（不消耗流量）
 */
public final class Toolchain {

    private Toolchain() {
    }

    // ---- 安装状态（页面横幅轮询显示，弹窗关掉也能看到进度）
    public static volatile boolean running;

    /**
     * 真正的互斥用 CAS，不能靠读 running 判断：两个线程同时读到 false 会一起往下走，
     * 于是两个 apk 进程互相踩（首启自动补齐与用户手动点按钮就可能同时发生）。
     * running 保留给界面显示状态用。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean LOCK =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 抢占安装锁，抢不到返回 false。持有者必须 try/finally 里 release()。 */
    private static boolean acquire() {
        if (!LOCK.compareAndSet(false, true)) return false;
        running = true;
        return true;
    }

    private static void release() {
        running = false;
        LOCK.set(false);
    }
    public static volatile String step = "";
    public static volatile long startedAt;
    public static volatile String lastLine = "";
    public static volatile String doneMsg = "";

    /** 多个镜像按顺序回退：阿里云 HTTPS → 清华 → 官方 → 阿里云 HTTP（防 CA 证书缺失） */
    public static final String[] MIRRORS = {
            "https://mirrors.aliyun.com/alpine/v3.22",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.22",
            "https://dl-cdn.alpinelinux.org/alpine/v3.22",
            "http://mirrors.aliyun.com/alpine/v3.22"
    };

    /** 镜像域名（App 侧解析后写入 rootfs hosts，绕开 proot 内 DNS 被拦的问题） */
    private static final String[] MIRROR_HOSTS = {
            "mirrors.aliyun.com", "mirrors.tuna.tsinghua.edu.cn", "dl-cdn.alpinelinux.org"
    };

    private static final String HTTP_MIRROR = "http://mirrors.aliyun.com/alpine/v3.22";

    /**
     * 网络准备：App 侧解析镜像域名 → 写进 rootfs 的 /etc/hosts。
     * <p>
     * Android 上 proot 内的 UDP 53 经常被运营商/系统策略拦掉，
     * 表现就是“索引能拿到、下载全是 0K”。这里直接用 Android 自己的网络栈解析，
     * 把 IPv4 写进 hosts，apk 就不再需要发 DNS 查询。
     *
     * @return null 表示可用；非 null 为致命错误
     */
    public static String prepareNetwork(Context c, ApkWorkbench.Progress p) {
        if (p != null) p.onStep("配置 DNS / hosts…");
        File rootfs = LinuxEnv.get(c).rootfs();
        StringBuilder hosts = new StringBuilder("127.0.0.1 localhost nebula\n::1 localhost\n");
        int resolved = 0;
        StringBuilder fail = new StringBuilder();
        for (String d : MIRROR_HOSTS) {
            String ip = null;
            try {
                java.net.InetAddress[] arr = java.net.InetAddress.getAllByName(d);
                for (java.net.InetAddress a : arr) {
                    if (a instanceof java.net.Inet4Address) {
                        ip = a.getHostAddress();
                        break;
                    }
                }
            } catch (Exception e) {
                fail.append(d).append("(").append(e.getClass().getSimpleName()).append(") ");
            }
            if (ip != null) {
                hosts.append(ip).append(' ').append(d).append('\n');
                resolved++;
            }
        }
        if (resolved == 0) {
            return "设备当前网络解析不了镜像域名，请检查是否开了飞行模式/网络受限。\n失败：" + fail;
        }
        try {
            writeFile(new File(rootfs, "etc"), "hosts", hosts.toString());
            // 1.1.1.1 在国内基本不通，换成百度 DNS 兜底
            writeFile(new File(rootfs, "etc"), "resolv.conf",
                    "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 180.76.76.76\n");
        } catch (Exception e) {
            return "写入 hosts/resolv.conf 失败：" + e;
        }
        if (p != null) p.onLog("域名已解析 " + resolved + "/" + MIRROR_HOSTS.length + " 并写入 hosts");
        return null;
    }

    private static void writeFile(File dir, String name, String content) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    /** 输出里出现 TLS/证书类错误 → 说明 HTTPS 走不通，改用 HTTP 源 */
    private static boolean tlsProblem(String out) {
        if (out == null) return false;
        String s = out.toLowerCase(java.util.Locale.ROOT);
        return s.contains("tls") || s.contains("certificate") || s.contains("handshake")
                || s.contains("ssl") || s.contains("bad address") || s.contains("unable to connect");
    }

    /** 基础工具包（约 15MB） */
    public static final String CORE_PKGS = "zip unzip xz curl sqlite git xxd jq rsync gdb tree";

    /** 开发工具包（约 158MB：python3 + nodejs + gcc 工具链 + Java 运行时） */
    public static final String DEV_PKGS = "python3 nodejs make gcc musl-dev openjdk17-jre-headless";

    /** Python 数据档：pip + numpy（约 29MB，随 APK 内置，零流量） */
    public static final String PY_PKGS = "py3-pip py3-numpy";

    /** 媒体档：ffmpeg 6.1 与全套编解码（约 45MB，随 APK 内置，零流量） */
    public static final String MEDIA_PKGS = "ffmpeg";

    /** 只要 Java 运行时（约 60MB）：内置的 jadx / apktool / baksmali / smali 全部依赖它 */
    public static final String JRE_PKGS = "openjdk17-jre-headless";

    // ------------------------------------------------------------------ 探测

    /** 在 proot 里批量探测命令是否存在 */
    public static Set<String> probe(Context c, String[] names) {
        Set<String> has = new HashSet<>();
        LinuxEnv env = LinuxEnv.get(c);
        if (!env.isInstalled()) return has;
        // 进页面时顺手把随包附带的 aapt2 / apktool 链接进 PATH（幂等、不耗流量）
        linkBuiltinTools(c, null);

        StringBuilder sb = new StringBuilder("for c in");
        for (String n : names) sb.append(' ').append(n);
        sb.append("; do command -v $c >/dev/null 2>&1 && echo Y$c; done");
        String out = env.execSync(sb.toString(), 60000);
        if (out == null) return has;
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("Y") && line.length() > 1) has.add(line.substring(1));
        }
        return has;
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 补齐工具链。
     *
     * @param full true 时额外装 python3 / nodejs / gcc 工具链
     * @return null 表示成功，否则返回错误说明
     */
    public static String install(Context c, boolean full, ApkWorkbench.Progress p) {
        return install(c, full ? MANIFEST_FULL : MANIFEST_CORE, full ? DEV_PKGS : CORE_PKGS, p);
    }

    /** 只装 Java 运行时（约 60MB），给内置反编译工具用，不必拖 245MB 全家桶 */
    public static String installJre(Context c, ApkWorkbench.Progress p) {
        return install(c, MANIFEST_JRE, JRE_PKGS, p);
    }

    // ------------------------- JDK 17 编译器档 -------------------------
    // 为什么不走 apk add：官方 openjdk17-jdk 声明依赖 openjdk17-jre（会拖 libX11/libXext/libXi
    // 一串图形库，proot 里没有 X，纯废体积）和 openjdk17-jmods（70MB，只有 jlink 用得上）。
    // 而我们的离线安装是「--no-network + 空仓库」，依赖纯本地解析，缺一个就整档装不上。
    // javac 真正需要的只有已经装好的 jre-headless（Java 本体 lib/modules 全在里面），
    // 所以这里不重打包、不改 datahash，直接把官方包里的 usr/ 树解出来，绕开依赖，零额外体积。

    /** 原样内置的官方 JDK 包名（5.6MB，含 javac/javadoc/jar/javap/jshell 等 25 个工具 + JNI 头文件） */
    public static final String JDK_ASSET = "openjdk17-jdk-17.0.19_p10-r0.apk";
    public static final String JDK_HOME = "/usr/lib/jvm/java-17-openjdk";
    /** JDK 自带的开发工具，装完统一软链进 /usr/bin（官方包内 usr/bin 是空的，不建链敲 javac 会 not found） */
    private static final String JDK_BINS = "javac javadoc jar jarsigner javap jshell jdb jdeps jdeprscan"
            + " jlink jmod jpackage jimage serialver jcmd jconsole jhsdb jinfo";

    /** JDK 档是否就位：javac 可执行即算装好 */
    public static boolean jdkInstalled(Context c) {
        LinuxEnv env = LinuxEnv.get(c);
        if (!env.isInstalled()) return false;
        String out = env.execSync("test -x " + JDK_HOME + "/bin/javac && echo YJAVAC", 30000);
        return out != null && out.contains("YJAVAC");
    }

    public static String installJdk(Context c, ApkWorkbench.Progress p) {
        if (!acquire()) return "已有安装任务在跑，稍后再试";
        try {
            return installJdkInner(c, p);
        } finally {
            release();
        }
    }

    /** 真正干活的版本：调用方必须已经持有安装锁（provision 就是持锁后直接调它） */
    private static String installJdkInner(Context c, ApkWorkbench.Progress p) {
        final LinuxEnv env = LinuxEnv.get(c);
        if (!env.isInstalled()) return "还没有安装内置 Linux 环境，请先点上面「安装内置 Linux」";
        if (jdkInstalled(c)) {
            linkJdkBins(c);
            return null;
        }
        // javac 只是 jre-headless 上的一层 launcher，必须先有 Java 本体；
        // 也避免先解 JDK 再装 jre 时 apk 撞到同名文件报冲突。
        if (!allInstalled(c, JRE_PKGS)) {
            // 不能调 installJre()：它会再抢一次锁，必然失败
            String e0 = installInner(c, MANIFEST_JRE, JRE_PKGS, p);
            if (e0 != null) return "JDK 需要先有 Java 运行时：" + e0;
        }
        startedAt = System.currentTimeMillis();
        lastLine = "";
        doneMsg = "";
        try {
            step = "释放 JDK 17 编译器（5.6MB 随 APK 内置，不联网）…";
            if (p != null) p.onStep(step);
            java.io.InputStream in = null;
            try {
                in = c.getAssets().open("alpack/" + JDK_ASSET);
                TarGz.extract(in, env.rootfs(), 10059653L, new TarGz.Progress() {
                    @Override
                    public void onProgress(int percent, String currentEntry) {
                        lastLine = "解包 " + percent + "%　" + currentEntry;
                    }
                });
            } catch (Exception e) {
                return "释放 JDK 失败：" + e;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            // apk 包自带的元数据/签名会一并落到根目录，清掉免得碍眼
            //noinspection ResultOfMethodCallIgnored
            new java.io.File(env.rootfs(), ".PKGINFO").delete();
            java.io.File[] junk = env.rootfs().listFiles();
            if (junk != null) {
                for (java.io.File f : junk) {
                    if (f.getName().startsWith(".SIGN.")) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                }
            }
            step = "补 /usr/bin 软链…";
            if (p != null) p.onStep(step);
            linkJdkBins(c);
            if (!jdkInstalled(c)) return "JDK 已解出但 javac 不可执行，请重试";
            String ver = env.execSync(JDK_HOME + "/bin/javac -version 2>&1", 60000);
            lastLine = ver == null ? "" : ver.trim();
            return null;
        } finally {
            doneMsg = (System.currentTimeMillis() - startedAt) / 1000 + "s";
        }
    }

    private static void linkJdkBins(Context c) {
        LinuxEnv env = LinuxEnv.get(c);
        env.execSync("J=" + JDK_HOME + "/bin; mkdir -p /usr/bin; for b in " + JDK_BINS
                + "; do [ -x \"$J/$b\" ] && ln -sf \"$J/$b\" /usr/bin/$b; done; echo OK", 60000);
    }

    /**
     * 装 Python 数据档（pip / numpy）。包全部随 APK 内置，一点就来，不耗流量。
     *
     * @return null 表示成功，否则返回错误说明
     */
    public static String installPython(Context c, ApkWorkbench.Progress p) {
        return install(c, MANIFEST_PY, PY_PKGS, p);
    }

    /**
     * 装 FFmpeg 媒体档（转码 / 抽帧 / 压片）。包全部随 APK 内置，不耗流量。
     *
     * @return null 表示成功，否则返回错误说明
     */
    public static String installMedia(Context c, ApkWorkbench.Progress p) {
        return install(c, MANIFEST_MEDIA, MEDIA_PKGS, p);
    }

    /** Python 数据档是否已装（会起一次 proot，务必在后台线程调用） */
    public static boolean pythonReady(Context c) {
        if (!LinuxEnv.get(c).isInstalled()) return false;
        return allInstalled(c, PY_PKGS);
    }

    /** FFmpeg 是否已装（会起一次 proot，务必在后台线程调用） */
    public static boolean mediaReady(Context c) {
        if (!LinuxEnv.get(c).isInstalled()) return false;
        return allInstalled(c, MEDIA_PKGS);
    }

    /** 基础工具是否已装齐（会起一次 proot，务必在后台线程调用） */
    public static boolean coreReady(Context c) {
        if (!LinuxEnv.get(c).isInstalled()) return false;
        return allInstalled(c, CORE_PKGS);
    }

    /** Java 运行时是否已装（会起一次 proot，务必在后台线程调用） */
    public static boolean jreReady(Context c) {
        if (!LinuxEnv.get(c).isInstalled()) return false;
        return allInstalled(c, JRE_PKGS);
    }

    // ---- 自动开箱的对外进度：主界面输入框上方的进度条直接读这几个值 ----
    /** 是否正在自动开箱（四档） */
    public static volatile boolean provisioning;
    /** 当前正在装的档序号（0 基，已装完的档数） */
    public static volatile int planStep;
    /** 这一轮实际需要装的档总数（老用户可能只缺一两档） */
    public static volatile int planStepTotal = 4;
    /** 加权总进度 0-100（按各档压缩包体积分档推进） */
    public static volatile int planPct;
    /** 当前档的说明，如「Java 运行时 61MB」 */
    public static volatile String planLabel = "";

    /**
     * 一键开箱：基础命令包 + Java 运行时 + JDK 编译器 + Python 数据档 + FFmpeg，五档全上。
     *
     * 各档必须串行装（同一个 apk 进程不能并发跑），已装好的档自动跳过，
     * 所以重复调用很便宜。阻塞调用，务必放后台线程。
     *
     * @return 都齐了返回 null，否则返回第一处错误说明
     */
    public static String provision(Context c, ApkWorkbench.Progress p) {
        if (!LinuxEnv.get(c).isInstalled()) return "还没有安装内置 Linux 环境";

        // 老用户的环境是早期版本解的（仓库源指向 v3.20），而随包内置的软件包是 v3.22 编译的，
        // 用 apk add 装进去就成了两代混装。这里只把源那两行改成 v3.22，不动已装的包，幂等。
        migrateRepos(c);

        // 先探一遍缺哪几档：全齐就直接返回，别让进度条闪一下又消失
        final boolean[] need = {
                !allInstalled(c, CORE_PKGS), !allInstalled(c, JRE_PKGS), !jdkInstalled(c),
                !allInstalled(c, PY_PKGS), !allInstalled(c, MEDIA_PKGS)
        };
        int total = 0;
        for (boolean b : need) if (b) total++;
        if (total == 0) return null;

        // 到这里才真动手，此时才抢锁：上面的探测是只读的，不该拦住别的手动安装
        if (!acquire()) return "已有安装任务在跑，稍后再试";

        final String[] labels = {"基础工具 29MB", "Java 运行时 61MB", "JDK 编译器 5.6MB",
                "Python 数据档 29MB", "FFmpeg 45MB"};
        // 各档装完时的加权百分比（按压缩包体积摊，共 169MB）
        final int[] afterPct = {17, 51, 54, 71, 100};

        provisioning = true;
        planStepTotal = total;
        planStep = 0;
        planPct = 0;
        planLabel = "准备中…";
        try {
            int idx = 0;
            int basePct = 0;
            for (int i = 0; i < need.length; i++) {
                if (!need[i]) {
                    basePct = afterPct[i];
                    planPct = basePct;
                    continue;
                }
                idx++;
                planStep = idx - 1;
                planLabel = labels[i];
                planPct = basePct;
                String err = installTier(c, i, p);
                if (err != null) return labels[i] + "：" + err;
                planStep = idx;
                planPct = afterPct[i];
                basePct = afterPct[i];
            }
            planLabel = "完成";
            return null;
        } finally {
            release();
            provisioning = false;
        }
    }

    /**
     * 把老用户环境里的 apk 源从 v3.20 改成 v3.22。
     *
     * <p>只重写 /etc/apk/repositories 里的版本号，不碰 /lib/apk/db 里已装的包，
     * 也不联网；重复调用无副作用。改完用户自己 apk add 拉到的就是与内置包同代的包。
     */
    public static void migrateRepos(Context c) {
        LinuxEnv env = LinuxEnv.get(c);
        if (!env.isInstalled()) return;
        env.execSync("G=/etc/apk/repositories; if grep -q 'v3\\.20' \"$G\" 2>/dev/null; then"
                + " sed -i 's#v3\\.20#v3.22#g' \"$G\" && echo MIGRATED; fi", 30000);
    }

    /** 分档执行器：JDK 走自解 tar 树，其余走内置 apk 离线安装 */
    private static String installTier(Context c, int tier, ApkWorkbench.Progress p) {
        // 调用方（provision）已经持锁，这里只能走 inner 版本，再 acquire 一次必然失败
        startedAt = System.currentTimeMillis();
        lastLine = "";
        doneMsg = "";
        try {
            switch (tier) {
                case 0:
                    return installInner(c, MANIFEST_CORE, CORE_PKGS, p);
                case 1:
                    return installInner(c, MANIFEST_JRE, JRE_PKGS, p);
                case 2:
                    return installJdkInner(c, p);
                case 3:
                    return installInner(c, MANIFEST_PY, PY_PKGS, p);
                default:
                    return installInner(c, MANIFEST_MEDIA, MEDIA_PKGS, p);
            }
        } finally {
            doneMsg = (System.currentTimeMillis() - startedAt) / 1000 + "s";
        }
    }

    /**
     * 按清单装一档包。
     *
     * @param manifest assets 里的包清单文件名
     * @param pkgs     这一档的目标包集合（用于判定是否装全）
     */
    public static String install(Context c, String manifest, String pkgs, ApkWorkbench.Progress p) {
        // 首启自动补齐 与 装完环境自动补齐 可能撞车，第二个直接让路，避免两个 apk 进程互踩
        if (!acquire()) return "已有安装任务在跑，稍后再试";
        try {
            LinuxEnv env = LinuxEnv.get(c);
            if (!env.isInstalled()) {
                return "还没有安装内置 Linux 环境，请先点上面「安装内置 Linux」";
            }
            startedAt = System.currentTimeMillis();
            lastLine = "";
            doneMsg = "";
            return installInner(c, manifest, pkgs, p);
        } finally {
            release();
            doneMsg = (System.currentTimeMillis() - startedAt) / 1000 + "s";
        }
    }

    /** 内置包清单（assets：repo \t 包名 \t 版本 \t 字节数 \t 文件名） */
    private static final String MANIFEST_FULL = "alpacks.tsv";
    private static final String MANIFEST_CORE = "alpacks-core.tsv";
    private static final String MANIFEST_JRE = "alpacks-jre.tsv";
    private static final String MANIFEST_PY = "alpacks-py.tsv";
    private static final String MANIFEST_MEDIA = "alpacks-media.tsv";

    /**
     * 离线安装：由 App 自己（走 Android 网络栈）下载 apk 包，再让 proot 里的 apk 本地安装。
     * <p>
     * 为什么这么做：部分机型/网络下 proot 内部的下载会一直 0K（DNS 被拦、IPv6 黑洞、
     * TLS 被劫持都可能）。App 自己的网络是确定能用的，所以把“下载”搬到 App 侧，
     * proot 只负责解包安装，完全不碰网络。
     *
     * @return null 表示装好了；非 null 为错误说明
     */
    public static String installOffline(Context c, String manifest, String pkgs, ApkWorkbench.Progress p) {
        LinuxEnv env = LinuxEnv.get(c);
        File cache = new File(env.exchangeDir(), "apkcache");
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();

        java.util.List<String[]> list = readManifest(c, manifest);
        if (list.isEmpty()) return "包清单缺失（assets/" + manifest + "）";

        long total = 0;
        long needBytes = 0;
        int need = 0;
        int builtin = 0;
        for (String[] r : list) {
            long size = Long.parseLong(r[3]);
            total += size;
            File f = new File(cache, r[4]);
            if (f.exists() && f.length() == size) continue;
            // 随 APK 内置的离线包：不用下载，安装时直接从 assets 释放
            if (hasAsset(c, r[4])) {
                builtin++;
                continue;
            }
            need++;
            needBytes += size;
        }
        if (need == 0 && builtin == 0) {
            step = "包已全部缓存（" + list.size() + " 个），直接安装…";
            if (p != null) p.onStep(step);
        } else if (need == 0) {
            step = "全部 " + list.size() + " 个包随 APK 内置，正在释放（零流量）…";
            if (p != null) p.onStep(step);
        } else {
            step = "内置 " + builtin + " 个包，还需下载 " + need + " 个（"
                    + LinuxEnv.humanSize(needBytes) + "），由 App 直接下载";
            if (p != null) p.onStep(step);
        }

        StringBuilder log = new StringBuilder();
        long bytes = 0;
        for (int i = 0; i < list.size(); i++) {
            String[] r = list.get(i);
            File f = new File(cache, r[4]);
            long size = Long.parseLong(r[3]);
            if (f.exists() && f.length() == size) {
                bytes += size;
                continue;
            }
            // 内置包优先：直接拷贝，不消耗任何流量
            if (copyFromAssets(c, r[4], f)) {
                bytes += size;
                if (p != null) {
                    p.onStep("释放内置包 " + (i + 1) + "/" + list.size() + "  " + r[1]
                            + "（" + LinuxEnv.humanSize(size) + "）");
                }
                continue;
            }
            boolean ok = false;
            for (String m : MIRRORS) {
                step = "下载 " + (i + 1) + "/" + list.size() + "  " + r[1] + "（" + LinuxEnv.humanSize(size) + "）";
                if (p != null) p.onStep(step);
                if (fetch(m + "/" + r[0] + "/aarch64/" + r[4], f, size)) {
                    ok = true;
                    break;
                }
                log.append("下载失败: ").append(m).append("/").append(r[0]).append("/aarch64/").append(r[4]).append('\n');
                lastLine = "换源重试 " + r[4];
            }
            if (!ok) {
                writeLog(log.toString());
                return "下载失败：" + r[4] + "（" + MIRRORS.length + " 个镜像都不行）\n"
                        + "已下好的包会留在缓存里，重试不用重下。\n"
                        + "请确认 App 能上网（试试聊天是否正常）。";
            }
            bytes += size;
            if (p != null) {
                p.onStep("已下载 " + LinuxEnv.humanSize(bytes) + " / " + LinuxEnv.humanSize(total)
                        + "（" + (bytes * 100 / Math.max(1L, total)) + "%）");
            }
        }

        // 安装：失败就就地重试（包已在缓存里，不重新下载）。
        // proot 假 root 下 apk 写超大文件（node/jdk modules/lto-dump）偶发 I/O error，
        // 重试通常就能过；已装好的包 apk 会自动跳过，所以重试很便宜。
        String out = "";
        for (int attempt = 1; attempt <= 4; attempt++) {
            step = attempt == 1
                    ? "本地安装 " + list.size() + " 个包（不联网）…"
                    : "重试安装（第 " + attempt + " 次，只补没装上的）…";
            if (p != null) p.onStep(step);
            out = runStreaming(c,
                    "cd /exchange/apkcache && apk add --allow-untrusted --no-network "
                            + "--repositories-file /dev/null *.apk 2>&1", p, log);
            if (allInstalled(c, pkgs)) {
                writeLog(log.toString());
                return null;
            }
            lastLine = "第 " + attempt + " 次后仍有包未装好（I/O error 常见），重试中…";
            try {
                Thread.sleep(1500L * attempt);
            } catch (InterruptedException ignored) {
            }
        }
        writeLog(log.toString());
        return "本地安装未完成（已重试 4 次，包都在缓存里没重下）\n" + ApkWorkbench.tail(out, 700);
    }

    /** assets/alpack 里是否带了这个包（随 APK 内置的离线包） */
    private static boolean hasAsset(Context c, String name) {
        java.io.InputStream in = null;
        try {
            in = c.getAssets().open("alpack/" + name);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 把内置的 apk 包释放到缓存目录。
     * <p>
     * 内置的意义就是「装上就能用，不联网」：Java 运行时 57MB、基础工具 11MB 都在 APK 里，
     * 首次进环境页不用等下载。写 .part 再改名，避免中断留下半截文件被当成已缓存。
     */
    private static boolean copyFromAssets(Context c, String name, File dest) {
        File tmp = new File(dest.getAbsolutePath() + ".part");
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = c.getAssets().open("alpack/" + name);
            out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** 读 assets 里的包清单 */
    private static java.util.List<String[]> readManifest(Context c, String name) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getAssets().open(name), "UTF-8"));
            String ln;
            while ((ln = r.readLine()) != null) {
                if (ln.trim().isEmpty()) continue;
                String[] f = ln.split("\t");
                if (f.length >= 5) out.add(f);
            }
            r.close();
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 用 App 的网络栈下载单个文件，长度校验不通过就当失败 */
    private static boolean fetch(String url, File dest, long expect) {
        File tmp = new File(dest.getAbsolutePath() + ".part");
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "AgenCode/1.4");
            if (conn.getResponseCode() != 200) return false;
            java.io.InputStream in = conn.getInputStream();
            java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            long got = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
            }
            os.close();
            in.close();
            if (expect > 0 && got != expect) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                lastLine = "长度不符（" + got + "/" + expect + "）";
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return tmp.renameTo(dest);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            lastLine = "下载异常: " + e.getClass().getSimpleName();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String installInner(Context c, String manifest, String pkgs, ApkWorkbench.Progress p) {
        // 1) 内置静态 aapt2 零流量可用
        linkBuiltinTools(c, p);

        // 2) 首选：App 侧下载 + 离线安装（不依赖 proot 网络，避免“下载全 0K”）
        String err = installOffline(c, manifest, pkgs, p);
        if (err == null) return null;
        step = "离线安装失败，改试直连镜像";
        if (p != null) p.onStep(step);

        // 3) 回退：proot 内在线 apk add
        //    CORE / DEV 都要先把基础包装上（DEV 不含 zip/curl 等）；
        //    JRE 只补 java，不拖基础包。
        if (CORE_PKGS.equals(pkgs) || DEV_PKGS.equals(pkgs)) {
            err = apkAdd(c, CORE_PKGS, p);
            if (err != null) return err;
        }
        if (!CORE_PKGS.equals(pkgs)) {
            err = apkAdd(c, pkgs, p);
            if (err != null) return err;
        }
        return null;
    }

    /**
     * apk add，多镜像自动回退、流式输出（边下载边刷日志，不会“一直没显示”）。
     *
     * @return null 表示这批包都装上了
     */
    public static String apkAdd(Context c, String pkgs, ApkWorkbench.Progress p) {
        LinuxEnv env = LinuxEnv.get(c);
        if (allInstalled(c, pkgs)) return null;

        // 先解决“下载 0K”的根源：proot 内 DNS 解析不了镜像
        String netErr = prepareNetwork(c, p);
        if (netErr != null) return netErr;

        StringBuilder log = new StringBuilder();
        java.util.List<String> order = new java.util.ArrayList<>();
        for (String m : MIRRORS) order.add(m);

        int idx = 0;
        boolean httpRetry = false;
        int attempts = 0;
        boolean sawTls = false;
        while (attempts < 6) {
            attempts++;
            String m = (httpRetry && attempts <= 2) ? HTTP_MIRROR : order.get(idx);
            String host = m.replaceFirst("^https?://", "");
            int slash = host.indexOf('/');
            if (slash > 0) host = host.substring(0, slash);

            step = "连接 " + host + "（第 " + attempts + " 次）";
            if (p != null) p.onStep(step);

            // 1) 单独 update：网络通不通立刻可见
            String cmdUpdate = "printf '%s\\n' '" + m + "/main' '" + m + "/community' > /etc/apk/repositories; "
                    + "apk update 2>&1";
            String upOut = runStreaming(c, cmdUpdate, p, log);

            // 2) add：逐行打印下载/解包进度
            step = "下载安装 " + pkgs + "（" + host + "）";
            if (p != null) p.onStep(step);
            String addOut = runStreaming(c, "apk add " + pkgs + " 2>&1", p, log);

            if (allInstalled(c, pkgs)) {
                writeLog(log.toString());
                return null;
            }

            boolean tls = tlsProblem(upOut + addOut);
            sawTls = sawTls || tls;
            log.append("!!! ").append(m).append(" 未成功").append(tls ? "（TLS/连接错误）" : "").append('\n');

            if (tls && !httpRetry && !m.startsWith("http://")) {
                httpRetry = true;
                step = "HTTPS 握手失败，改用 HTTP 源重试";
                if (p != null) p.onStep(step);
                continue;
            }
            idx = (idx + 1) % order.size();
        }

        writeLog(log.toString());
        String reason = sawTls
                ? "镜像 HTTPS 连接失败，HTTP 源也没成功——可能是网络限制或代理问题"
                : "4 个镜像都没装上，请看日志里的 apk 原始报错";
        return "安装失败：" + pkgs + "\n" + reason + "\n"
                + "完整日志：/sdcard/Download/nebula-java.log\n---\n"
                + ApkWorkbench.tail(log.toString(), 700);
    }

    /** 流式执行：每读到一行就回调 onLog，30 分钟看门狗兜底 */
    private static String runStreaming(Context c, String cmd, ApkWorkbench.Progress p, StringBuilder log) {
        StringBuilder local = new StringBuilder();
        Process proc = null;
        Thread wd = null;
        java.io.BufferedReader r = null;
        try {
            proc = LinuxEnv.get(c).startProcess(cmd);
            final Process fp = proc;
            wd = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(30 * 60 * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        fp.destroy();
                    } catch (Exception ignored) {
                    }
                }
            });
            wd.setDaemon(true);
            wd.start();

            final int[] lines = new int[]{0};
            final long t0 = System.currentTimeMillis();
            Thread hb = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            Thread.sleep(3000);
                            long sec = (System.currentTimeMillis() - t0) / 1000;
                            lastLine = "已运行 " + sec + "s，收到 " + lines[0] + " 行输出（下载中，别锁屏）";
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
            });
            hb.setDaemon(true);
            hb.start();
            try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) continue;
                log.append(line).append('\n');
                local.append(line).append('\n');
                lines[0]++;
                lastLine = line;
                if (p != null) p.onLog(line);
            }
            proc.waitFor();
            } finally {
                hb.interrupt();
            }
        } catch (Exception e) {
            log.append("执行出错: ").append(e).append('\n');
            local.append("执行出错: ").append(e).append('\n');
            lastLine = "执行出错: " + e;
        } finally {
            if (wd != null) wd.interrupt();
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
            if (proc != null) proc.destroy();
        }
        return local.toString();
    }

    /** 包是否都已安装（apk info -e 打印包名表示已装） */
    private static boolean allInstalled(Context c, String pkgs) {
        String out = LinuxEnv.get(c).execSync(
                "for p in " + pkgs + "; do apk info -e $p >/dev/null 2>&1 || echo MISS$p; done", 60000);
        return out == null || !out.contains("MISS");
    }

    /** 把随包附带的静态 aapt2 链接到 /usr/local/bin，并顺带放一个 apktool 快捷命令 */
    public static void linkBuiltinTools(Context c, ApkWorkbench.Progress p) {
        try {
            if (p != null) p.onStep("准备内置 aapt2 / apktool…");
            ApkWorkbench.ensureAssets(c, p);
            LinuxEnv.get(c).execSync(
                    "mkdir -p /usr/local/bin /usr/local/share/apktool && "
                            + "ln -sf /exchange/apkwork/bin/aapt2 /usr/local/bin/aapt2 && "
                            + "chmod +x /exchange/apkwork/bin/aapt2 /exchange/apkwork/bin/apktool.jar && "
                            + "printf '#!/bin/sh\\nexec java -jar /exchange/apkwork/bin/apktool.jar \"$@\"\\n' > /usr/local/bin/apktool && "
                            + "chmod +x /usr/local/bin/apktool && "
                            + "aapt2 version 2>&1 | head -1", 120000);
        } catch (Exception ignored) {
        }
    }

    private static void writeLog(String text) {
        try {
            java.io.FileOutputStream fos =
                    new java.io.FileOutputStream(new java.io.File("/sdcard/Download/nebula-java.log"), false);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }
}
