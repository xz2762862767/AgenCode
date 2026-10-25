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
    public static volatile String step = "";
    public static volatile long startedAt;
    public static volatile String lastLine = "";
    public static volatile String doneMsg = "";

    /** 多个镜像按顺序回退：阿里云 HTTPS → 清华 → 官方 → 阿里云 HTTP（防 CA 证书缺失） */
    public static final String[] MIRRORS = {
            "https://mirrors.aliyun.com/alpine/v3.20",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20",
            "https://dl-cdn.alpinelinux.org/alpine/v3.20",
            "http://mirrors.aliyun.com/alpine/v3.20"
    };

    /** 镜像域名（App 侧解析后写入 rootfs hosts，绕开 proot 内 DNS 被拦的问题） */
    private static final String[] MIRROR_HOSTS = {
            "mirrors.aliyun.com", "mirrors.tuna.tsinghua.edu.cn", "dl-cdn.alpinelinux.org"
    };

    private static final String HTTP_MIRROR = "http://mirrors.aliyun.com/alpine/v3.20";

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
        String s = out.toLowerCase();
        return s.contains("tls") || s.contains("certificate") || s.contains("handshake")
                || s.contains("ssl") || s.contains("bad address") || s.contains("unable to connect");
    }

    /** 基础工具包（约 15MB） */
    public static final String CORE_PKGS = "zip unzip xz curl sqlite git xxd";

    /** 开发工具包（约 245MB：python3 + nodejs + gcc 工具链 + Java 运行时） */
    public static final String DEV_PKGS = "python3 nodejs make gcc musl-dev openjdk17-jre-headless";

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
        LinuxEnv env = LinuxEnv.get(c);
        if (!env.isInstalled()) {
            return "还没有安装内置 Linux 环境，请先点上面「安装内置 Linux」";
        }

        running = true;
        startedAt = System.currentTimeMillis();
        lastLine = "";
        doneMsg = "";
        try {
            return installInner(c, full, p);
        } finally {
            running = false;
            doneMsg = (System.currentTimeMillis() - startedAt) / 1000 + "s";
        }
    }

    /** 内置包清单（assets：repo \t 包名 \t 版本 \t 字节数 \t 文件名） */
    private static final String MANIFEST_FULL = "alpacks.tsv";
    private static final String MANIFEST_CORE = "alpacks-core.tsv";

    /**
     * 离线安装：由 App 自己（走 Android 网络栈）下载 apk 包，再让 proot 里的 apk 本地安装。
     * <p>
     * 为什么这么做：部分机型/网络下 proot 内部的下载会一直 0K（DNS 被拦、IPv6 黑洞、
     * TLS 被劫持都可能）。App 自己的网络是确定能用的，所以把“下载”搬到 App 侧，
     * proot 只负责解包安装，完全不碰网络。
     *
     * @return null 表示装好了；非 null 为错误说明
     */
    public static String installOffline(Context c, boolean full, ApkWorkbench.Progress p) {
        LinuxEnv env = LinuxEnv.get(c);
        File cache = new File(env.exchangeDir(), "apkcache");
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();

        java.util.List<String[]> list = readManifest(c, full ? MANIFEST_FULL : MANIFEST_CORE);
        if (list.isEmpty()) return "包清单缺失（assets/" + (full ? MANIFEST_FULL : MANIFEST_CORE) + "）";

        long total = 0;
        int need = 0;
        for (String[] r : list) {
            long size = Long.parseLong(r[3]);
            total += size;
            File f = new File(cache, r[4]);
            if (!f.exists() || f.length() != size) need++;
        }
        if (need == 0) {
            step = "包已全部缓存（" + list.size() + " 个），直接安装…";
            if (p != null) p.onStep(step);
        } else {
            step = "需下载 " + need + " 个包（" + LinuxEnv.humanSize(total) + "），由 App 直接下载";
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
            if (allInstalled(c, full ? DEV_PKGS : CORE_PKGS)) {
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
            conn.setRequestProperty("User-Agent", "NebulaAI/1.4");
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

    private static String installInner(Context c, boolean full, ApkWorkbench.Progress p) {
        // 1) 内置静态 aapt2 零流量可用
        linkBuiltinTools(c, p);

        // 2) 首选：App 侧下载 + 离线安装（不依赖 proot 网络，避免“下载全 0K”）
        String err = installOffline(c, full, p);
        if (err == null) return null;
        step = "离线安装失败，改试直连镜像";
        if (p != null) p.onStep(step);

        // 3) 回退：proot 内在线 apk add
        err = apkAdd(c, CORE_PKGS, p);
        if (err != null) return err;
        if (full) {
            err = apkAdd(c, DEV_PKGS, p);
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
