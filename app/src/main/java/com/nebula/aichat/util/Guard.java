package com.nebula.aichat.util;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Debug;

import com.nebula.aichat.BuildConfig;

import java.io.BufferedReader;
import java.io.FileReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 安装包自检。
 *
 * 三件事，按可信度从高到低：
 *
 * 1）签名校验（最实在）。改 dex 加广告、换清单地址、塞木马，都必然要重签，
 *    签名就变了。构建期把本包会用到的证书 SHA-256 注入 BuildConfig，
 *    运行时算一遍真实签名比对，对不上就是被二次打包过。
 *
 * 2）注入特征。扫 /proc/self/maps 里的 frida / xposed / zygisk 之类痕迹，
 *    覆盖"不改包、直接 attach 进程"这条路（签名校验看不出来）。
 *
 * 3）调试器状态。只做记录与展示，不作为判定依据——开发者自己开调试很常见。
 *
 * 边界要说清楚：纯 Java 层的自检挡不住会反编译的人（把这段代码删掉就完事），
 * 它的作用是把"下载 apktool 无脑改一下再重签"的成本抬高，并给正版用户一个
 * 看得见的提示。真要硬防护得上 native + 服务端校验，那是另一个量级的事。
 */
public final class Guard {

    /** 签名与构建期注入的指纹一致 */
    public static final int SIGN_OK = 0;
    /** 签名不一致：安装包被改过并重签 */
    public static final int SIGN_MISMATCH = 1;
    /** 读不到签名（极少见） */
    public static final int SIGN_UNKNOWN = 2;
    /** 构建期没拿到指纹，本次不做校验（例如未配置 release 密钥） */
    public static final int SIGN_SKIP = 3;

    private static final List<Runnable> WAITERS = new ArrayList<>();

    private static volatile boolean ready;
    private static volatile int signState = SIGN_UNKNOWN;
    private static volatile String actualFp = "";
    private static volatile String expectedFp = "";
    private static volatile String signals = "";
    private static volatile boolean debuggerAttached;

    private Guard() {
    }

    /** 启动时调一次，探测在后台线程做，不占启动时间 */
    public static void install(Context ctx) {
        if (ready) return;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                probe(app);
            }
        }, "guard-probe");
        t.setDaemon(true);
        t.start();
    }

    /** 探测完成后回调；已经完成则立即执行。回调线程不保证是主线程 */
    public static void whenReady(Runnable r) {
        if (r == null) return;
        synchronized (WAITERS) {
            if (ready) {
                r.run();
                return;
            }
            WAITERS.add(r);
        }
    }

    public static boolean isTampered() {
        return signState == SIGN_MISMATCH;
    }

    public static int signState() {
        return signState;
    }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    private static void probe(Context c) {
        if (ready) return;
        try {
            debuggerAttached = safeDebuggerCheck();

            List<String> fps = certFingerprints(c);
            actualFp = fps.isEmpty() ? "" : fps.get(0);
            expectedFp = (BuildConfig.SIGN_FP_A + BuildConfig.SIGN_FP_B).toLowerCase(Locale.ROOT);

            if (expectedFp.isEmpty()) {
                signState = SIGN_SKIP;
            } else if (fps.isEmpty()) {
                signState = SIGN_UNKNOWN;
            } else {
                boolean hit = false;
                for (String f : fps) {
                    if (f.equalsIgnoreCase(expectedFp)) {
                        hit = true;
                        break;
                    }
                }
                signState = hit ? SIGN_OK : SIGN_MISMATCH;
                if (!hit) {
                    CrashLog.d("guard", "签名不一致: 实际=" + actualFp + " 期望=" + expectedFp);
                }
            }

            signals = scanInjection();
            if (!signals.isEmpty()) {
                CrashLog.d("guard", "注入特征: " + signals);
            }
        } catch (Throwable t) {
            CrashLog.d("guard", "自检异常: " + t);
        } finally {
            ready = true;
            List<Runnable> run;
            synchronized (WAITERS) {
                run = new ArrayList<>(WAITERS);
                WAITERS.clear();
            }
            for (Runnable r : run) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean safeDebuggerCheck() {
        try {
            return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前安装包的签名证书 SHA-256（小写 hex）。
     * 有多个签名者（极少见）或密钥轮换时会有多条，任意一条命中即算官方。
     */
    private static List<String> certFingerprints(Context c) {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            String pkg = c.getPackageName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = pi == null ? null : pi.signingInfo;
                if (si != null) {
                    if (si.hasMultipleSigners()) {
                        addHashes(out, si.getApkContentsSigners());
                    } else {
                        // 先看当前生效的签名，再看轮换历史（两者都对才算官方）
                        addHashes(out, si.getApkContentsSigners());
                        addHashes(out, si.getSigningCertificateHistory());
                    }
                }
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (pi != null) addHashes(out, pi.signatures);
            }
        } catch (Throwable t) {
            CrashLog.d("guard", "读取签名失败: " + t);
        }
        return out;
    }

    private static void addHashes(List<String> out, Signature[] arr) {
        if (arr == null) return;
        for (Signature s : arr) {
            if (s == null) continue;
            String h = sha256(s.toByteArray());
            if (!h.isEmpty() && !out.contains(h)) out.add(h);
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 扫本进程的内存映射，找 hook 框架的库名/文件痕迹 */
    private static String scanInjection() {
        // 只在 maps 的路径列里找，避免误伤：这些都是特定框架的文件名特征
        String[] keys = {
                "frida", "gum-js-loop", "gadget", "gumv8", "linjector",
                "xposed", "lsposed", "edxposed", "riru", "zygisk",
                "substrate", "magisk", "libhook", "libinject"
        };
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/self/maps"), 8192);
            String line;
            int guard = 0;
            while ((line = br.readLine()) != null && guard++ < 60000) {
                String low = line.toLowerCase(Locale.ROOT);
                for (String k : keys) {
                    if (sb.indexOf(k) >= 0) continue;
                    if (low.contains(k)) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(k);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    private static String installer(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            String pkg = c.getPackageName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.InstallSourceInfo info = pm.getInstallSourceInfo(pkg);
                String s = info == null ? null : info.getInstallingPackageName();
                return s == null ? "未知（应用商店或阿德布/本地安装）" : s;
            }
            @SuppressWarnings("deprecation")
            String s = pm.getInstallerPackageName(pkg);
            return s == null ? "未知（本地安装）" : s;
        } catch (Throwable t) {
            return "读取失败";
        }
    }

    // ------------------------------------------------------------------
    // 对外展示
    // ------------------------------------------------------------------

    public static String signLabel() {
        switch (signState) {
            case SIGN_OK:
                return "官方签名（与构建记录一致）";
            case SIGN_MISMATCH:
                return "签名不一致 —— 这个包被改过并重新签名了";
            case SIGN_SKIP:
                return "未启用（构建期没取到签名指纹）";
            default:
                return "无法读取";
        }
    }

    /** 诊断页用的一段报告 */
    public static String report(Context c) {
        if (!ready) probe(c.getApplicationContext());
        StringBuilder sb = new StringBuilder("----- 安装包完整性 -----\n");
        sb.append("签名校验: ").append(signLabel()).append('\n');
        sb.append("实际指纹: ").append(actualFp.isEmpty() ? "?" : actualFp).append('\n');
        sb.append("期望指纹: ").append(expectedFp.isEmpty() ? "(未注入)" : expectedFp).append('\n');
        sb.append("调试器: ").append(debuggerAttached ? "已附加" : "未附加").append('\n');
        sb.append("注入特征: ").append(signals.isEmpty() ? "无" : signals).append('\n');
        sb.append("安装来源: ").append(installer(c)).append('\n');
        return sb.toString();
    }

    /** 关于页用：一行状态 + 是否正常 */
    public static String aboutLine() {
        switch (signState) {
            case SIGN_OK:
                return "官方签名";
            case SIGN_MISMATCH:
                return "非官方修改版";
            case SIGN_SKIP:
                return "自检未启用";
            default:
                return "签名未知";
        }
    }

    /**
     * 签名对不上时提示一次。不强制退出——自用改包的人被强退会很烦，
     * 而且能改包的人本来也能删掉这段代码，硬退只会误伤普通用户。
     */
    public static void showTamperWarning(final android.app.Activity host) {
        if (host == null || host.isFinishing() || !isTampered()) return;
        try {
            new AlertDialog.Builder(host)
                    .setTitle("这不是官方安装包")
                    .setMessage("当前安装包的签名与官方构建不一致，说明它被第三方修改并重新签过名。\n\n"
                            + "这类包可能被塞入广告、替换了服务地址或夹带了其他代码，"
                            + "请不要在其中输入账号、密钥等敏感信息。\n\n"
                            + "建议卸载后从官方渠道重新安装。")
                    .setPositiveButton("我知道了", null)
                    .setCancelable(true)
                    .show();
        } catch (Throwable ignored) {
        }
    }
}
