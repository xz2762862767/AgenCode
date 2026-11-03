package com.nebula.aichat.apk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 安装前的兼容性体检 + 拉起系统安装器。
 *
 * 重打包出来的 APK 装不上，八成跑不出这几种：
 *   1. 签名被换掉了（重签名必然换），覆盖安装会 INSTALL_FAILED_UPDATE_INCOMPATIBLE → 必须先卸载
 *   2. targetSdk 太低：Android 14 起，targetSdk &lt; 23 的包被系统直接拒装
 *   3. minSdk 高于手机系统版本
 *   4. 包本身损坏 / 没签好（缺 v2，Android 7+ 会拒）
 * 这里把它们提前查出来，用中文讲清楚，而不是让用户对着系统弹窗猜。
 */
public final class ApkInstaller {

    private ApkInstaller() {
    }

    public static final String AUTHORITY = "com.nebula.aichat.fileprovider";

    /**
     * 最近一次成功打包出来的 APK。
     * 存在的意义：模型回答完，界面上要直接把「安装包」摆到正文末尾，
     * 不能指望模型自己在正文里写出完整路径（它经常只写文件名，或者写的是私有目录）。
     * 公开路径（AgenCode/APK）排前面，私有目录那份兜底。
     */
    private static final List<File> LAST_BUILT = new ArrayList<>();
    public static volatile long lastBuiltAt = 0L;

    /** 应用级 Context：质检要在没有 Activity 的地方也能跑（产物同步、后台收尾） */
    private static volatile Context appCtx;

    public static void attach(Context c) {
        try {
            appCtx = c == null ? null : c.getApplicationContext();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void rememberBuilt(File privateFile, String publicPath) {
        LAST_BUILT.clear();
        File pub = null;
        if (publicPath != null && !publicPath.trim().isEmpty()) {
            File t = new File(publicPath.trim());
            if (t.isFile() && t.length() > 1024) pub = t;
        }
        File pri = null;
        if (privateFile != null && privateFile.isFile() && privateFile.length() > 1024) {
            pri = privateFile;
        }
        if (pub != null && pri != null
                && !pub.getAbsolutePath().equals(pri.getAbsolutePath())) {
            // 同一个包通常被分发到两处（私有 buildkit + 公共 AgenCode/APK），做成两张卡片的后果
            // 是用户不知道点哪个，很可能会点到那次没拷全的 —— 于是「一个能装、一个装不上」。
            // 先比大小：一致就是同一份，只留公共目录那份（用户自己能在文件管理器里找到）。
            if (pub.length() == pri.length()) {
                pri = null;
            } else {
                boolean pubOk = probe(pub).readable;
                boolean priOk = probe(pri).readable;
                if (pubOk && !priOk) {
                    pri = null;
                } else if (priOk && !pubOk) {
                    pub = null;
                } else if (!pubOk) {
                    pri = null;   // 两份都读不通，留一张就够，点开体检会说清坏在哪
                }
            }
        }
        // 出厂质检：装不上的包连安装卡片都不挂。
        // 卡片是成品最后一道窗口 —— 挂上去用户就会点，点一次就是一次「这个包装不上」，
        // 而这时候用户已经不知道是包的问题还是手机的问题了。
        if (appCtx != null) {
            if (pub != null && !ApkGate.pass(appCtx, pub)) pub = null;
            if (pri != null && !ApkGate.pass(appCtx, pri)) pri = null;
        }
        if (pub != null) LAST_BUILT.add(pub);
        if (pri != null) LAST_BUILT.add(pri);
        lastBuiltAt = android.os.SystemClock.uptimeMillis();
    }

    public static synchronized List<File> lastBuilt() {
        return new ArrayList<>(LAST_BUILT);
    }

    public static class Check {
        public final List<String> problems = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public String pkg = "";
        public long versionCode;
        public int minSdk;
        public int targetSdk;
        /** 只有 true 才建议直接点安装 */
        public boolean installable = true;
        /** 手机上已装同包名（用于给「卸载后重装」入口） */
        public boolean installed;
        /** 已装的那份签名不同：改包必然换签名，只能先卸载 */
        public boolean signMismatch;
    }

    /**
     * APK 的骨架：只按 zip 目录看里面有什么，不经过 PackageManager。
     *
     * 系统解析失败时只会返回 null，不告诉你为什么；而按 zip 读一遍能分清三件完全不同的事：
     * 文件被截断（读不通）、拿错文件（没有 manifest）、打包只跑了一半（空壳）。
     * 这三种的处理办法互不相同，混成一句「重新打包一次」等于让用户抓瞎。
     */
    public static class Skeleton {
        public boolean readable;
        public boolean manifest;
        public boolean arsc;
        public int dexCount;
        public long size;
        public String error = "";
    }

    public static Skeleton probe(File apk) {
        Skeleton s = new Skeleton();
        s.size = apk == null ? 0 : apk.length();
        if (apk == null || !apk.isFile()) {
            s.error = "文件不存在";
            return s;
        }
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apk);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String n = e == null ? null : e.getName();
                if (n == null) continue;
                if (n.equals("AndroidManifest.xml")) {
                    s.manifest = true;
                } else if (n.equals("resources.arsc")) {
                    s.arsc = true;
                } else if (n.startsWith("classes") && n.endsWith(".dex")) {
                    s.dexCount++;
                }
            }
            s.readable = true;
        } catch (Throwable t) {
            String m = t.getMessage();
            s.error = t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : "：" + m);
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) { }
        }
        return s;
    }

    private static String kb(long n) {
        if (n < 1024) return n + " 字节";
        if (n < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0));
    }

    @SuppressWarnings("deprecation")
    public static Check check(Context c, File apk) {
        Check r = new Check();
        if (apk == null || !apk.isFile() || apk.length() < 1024) {
            r.installable = false;
            r.problems.add("文件不存在或过小（" + (apk == null ? 0 : apk.length()) + " 字节），打包没真正完成");
            return r;
        }

        PackageManager pm = c.getPackageManager();
        // 解析用哪组 flags 很关键。老代码传 GET_SIGNATURES | GET_PERMISSIONS：GET_SIGNATURES 在新系统
        // 上已废弃（API 28 起由 GET_SIGNING_CERTIFICATES 取代），而且只要证书收集这一步受限，
        // getPackageArchiveInfo 就整个返回 null —— 于是包明明好的（aapt2 读得出清单、apksigner 验得过），
        // 却被判成「系统无法解析」。这里逐级回退：先新 flags，再旧 flags，最后只要能解析清单就行。
        PackageInfo pi = null;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_PERMISSIONS);
            } catch (Throwable ignored) {
                pi = null;
            }
        }
        if (pi == null) {
            try {
                pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNATURES | PackageManager.GET_PERMISSIONS);
            } catch (Throwable ignored) {
                pi = null;
            }
        }
        if (pi == null) {
            try {
                pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            } catch (Throwable ignored) {
                pi = null;
            }
        }
        if (pi == null) {
            // 系统解析不出来，不等于包装不了：getPackageArchiveInfo 带上 GET_SIGNATURES 之后，
            // 只要证书收集这一步受限就直接返回 null，而包本身可能完全正常（用 apksigner 验得过）。
            // 以前这里一律判死刑，等于 App 自己把用户挡在门外。先做结构体检，按真实原因分情况。
            Skeleton sk = probe(apk);
            if (!sk.readable) {
                r.installable = false;
                r.problems.add("这个文件打不开成压缩包（" + sk.error + "）。APK 本质是个 zip，"
                        + "读不通就说明它已经不完整了——多半是拷贝或下载时被截断。"
                        + "回原目录重新拷一次，这个文件修不回来。");
                return r;
            }
            if (!sk.manifest) {
                r.installable = false;
                r.problems.add("这个包里没有 AndroidManifest.xml。APK 必须有它，没有就只是个改了后缀的"
                        + "压缩包——多半是拿错了文件（构建目录、中间产物都长这样）。"
                        + "去构建真正输出的那个 apk 再试。");
                return r;
            }
            if (sk.dexCount == 0 && !sk.arsc) {
                r.installable = false;
                r.problems.add("这个包是空壳：" + kb(sk.size) + "，只有 AndroidManifest.xml，"
                        + "没有 classes.dex（编译后的代码），也没有 resources.arsc（资源表）。"
                        + "说明打包只跑了一半——清单生成了、代码还没合进去。重新完整打包再装。");
                return r;
            }
            // 清单、代码、资源都在 → 结构没问题，只是本机预解析不了，交给系统安装器去判
            r.notes.add("本机预解析失败（多为签名收集受限），但包内结构完整：manifest"
                    + (sk.dexCount > 0 ? "、" + sk.dexCount + " 个 dex" : "")
                    + (sk.arsc ? "、资源表" : "") + "，共用 " + kb(sk.size)
                    + "，直接交给系统安装器");
            r.installable = true;
            return r;
        }

        r.pkg = pi.packageName == null ? "" : pi.packageName;
        ApplicationInfo ai = pi.applicationInfo;
        int deviceSdk = Build.VERSION.SDK_INT;
        if (ai != null) {
            r.minSdk = ai.minSdkVersion;
            r.targetSdk = ai.targetSdkVersion;
        }
        r.notes.add("包名 " + r.pkg);

        // 0) versionCode：缺失时系统把它当无效包，装不上
        long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        r.versionCode = vc;
        if (vc <= 0) {
            r.installable = false;
            r.problems.add("这个包缺 android:versionCode（读出来是 " + vc + "）。"
                    + "系统会直接判为无效包、拒绝安装 —— 打包时必须写上 versionCode，重新编译后再装。");
        } else {
            r.notes.add("版本 " + (pi.versionName == null ? "(未命名)" : pi.versionName) + "，versionCode " + vc);
        }
        r.notes.add("minSdk " + r.minSdk + " / targetSdk " + r.targetSdk + "，本机 API " + deviceSdk);

        // 1) minSdk 高于本机
        if (r.minSdk > deviceSdk) {
            r.installable = false;
            r.problems.add("这个包要求 Android API " + r.minSdk + "，本机只有 " + deviceSdk
                    + "，系统版本不够，装不上。");
        }

        // 2) targetSdk 过低：门槛随系统版本抬高（Android 14 起 23，Android 15 起 24）
        int floor = deviceSdk >= 35 ? 24 : 23;
        if (deviceSdk >= 34 && r.targetSdk > 0 && r.targetSdk < floor) {
            r.installable = false;
            r.problems.add("targetSdk 只有 " + r.targetSdk + "。本机是 Android API " + deviceSdk
                    + "，系统不接受 targetSdk 低于 " + floor + " 的应用，会直接拒装。"
                    + "在工程 manifest 里把 targetSdkVersion 提到 " + floor + " 以上再重新编译。");
        }

        // 2.5) ABI：只有 32 位 .so 的包，在新 64 位机型上会报「与设备不兼容」
        List<String> apkAbis = apkAbis(apk);
        if (!apkAbis.isEmpty()) {
            List<String> devAbis = new ArrayList<>();
            for (String s : android.os.Build.SUPPORTED_ABIS) {
                if (s != null) devAbis.add(s.toLowerCase(java.util.Locale.US));
            }
            boolean abiMatch = false;
            for (String a : apkAbis) {
                if (devAbis.contains(a)) {
                    abiMatch = true;
                    break;
                }
            }
            if (!abiMatch) {
                r.installable = false;
                r.problems.add("包里的 native 库只有 " + join(apkAbis) + "，本机只支持 "
                        + join(devAbis) + "。系统会报「与设备不兼容」（无匹配 ABI）。"
                        + "要能装上，得把 lib/ 里的 .so 换成 arm64-v8a 版本重新打包。");
            } else {
                r.notes.add("ABI 匹配：" + join(apkAbis));
            }
        }

        // 3) 已安装同包名 → 比对签名/版本，判断能不能覆盖安装
        try {
            PackageInfo installed = pm.getPackageInfo(r.pkg,
                    Build.VERSION.SDK_INT >= 28
                            ? PackageManager.GET_SIGNING_CERTIFICATES
                            : PackageManager.GET_SIGNATURES);
            if (installed != null) {
                r.installed = true;
                long ivc = Build.VERSION.SDK_INT >= 28
                        ? installed.getLongVersionCode() : installed.versionCode;
                String a = sigOf(pi);
                String b = sigOf(installed);
                if (a != null && b != null && !a.equals(b)) {
                    r.installable = false;
                    r.signMismatch = true;
                    r.problems.add("手机上已经装了「" + r.pkg + "」，但签名和这个包不一致。"
                            + "只要改过包，签名就必然换掉，系统不允许覆盖安装 —— 先卸载原应用，再装这个。");
                } else if (vc > 0 && ivc > vc) {
                    r.installable = false;
                    r.problems.add("手机上装的是更高版本（versionCode " + ivc
                            + "，这个包是 " + vc + "），系统会判为「版本降级」拒绝安装。"
                            + "把工程的 versionCode 改到 " + (ivc + 1) + " 以上重新打包，或先卸载原应用。");
                } else {
                    r.notes.add("已安装同包名应用（versionCode " + ivc + "），签名一致，可直接覆盖安装");
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 没装过，正常
        } catch (Throwable ignored) {
        }

        // 4) 签名方案
        if (!hasV2(apk)) {
            r.problems.add("没有检测到 v2 签名块。Android 7 及以上要求 v2/v3 签名，"
                    + "这个包装上去会被判为「未签名或签名无效」。");
            r.installable = false;
        }

        // 4.5) resources.arsc 被压缩 → Android 11+ 直接拒装
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apk);
            java.util.zip.ZipEntry arsc = zf.getEntry("resources.arsc");
            if (arsc != null && arsc.getMethod() != java.util.zip.ZipEntry.STORED) {
                r.installable = false;
                r.problems.add("resources.arsc 是压缩存放的。Android 11 起系统要求它必须不压缩且 4 字节对齐，"
                        + "否则安装被拒（INSTALL_FAILED_RESOURCES_ARSC_COMPRESSED）。"
                        + "重新打包时用 zipalign -p -f 4，或改用系统工具链重新编译。");
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) { }
        }

        // 5) 存储位置说明
        if (apk.getAbsolutePath().startsWith(c.getFilesDir().getAbsolutePath())) {
            r.notes.add("文件在应用私有目录，安装时会通过 FileProvider 授权给系统安装器");
        }
        return r;
    }

    /**
     * 核实「到底装上没有」。
     *
     * 安装完成后返回 App 时，系统并不总是回传 SUCCESS：
     *   - 确认框开着的时候又点了一次，会开第二个会话，系统把前一个判成「用户放弃」→ ABORTED
     *   - 部分机型在用户装完直接返回时，也会把会话报成 ABORTED / TIMEOUT
     * 这两种情况下包都已经躺在手机里了。照系统状态弹一句「安装没成功」，用户看到的是
     * 一个装好的应用加一句装不上，比不提示更让人糊涂。所以最终以真实安装记录为准。
     */
    public static boolean alreadyInstalled(Context c, Check ck) {
        if (c == null || ck == null || ck.pkg == null || ck.pkg.isEmpty()) return false;
        try {
            PackageInfo cur = c.getPackageManager().getPackageInfo(ck.pkg, 0);
            if (cur == null) return false;
            long ivc = Build.VERSION.SDK_INT >= 28 ? cur.getLongVersionCode() : cur.versionCode;
            // 目标版本读不出来时，包名对上就算装上了：这条路本来就是「系统没解析成功但结构完好」
            return ck.versionCode <= 0 || ivc >= ck.versionCode;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按当前系统能用的方式取签名摘要：API 28+ 走 SigningInfo，老系统退回 signatures 字段 */
    private static String sigOf(PackageInfo pi) {
        if (pi == null) return null;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.content.pm.SigningInfo si = pi.signingInfo;
                if (si != null) {
                    Signature[] ss = si.hasMultipleSigners()
                            ? si.getApkContentsSigners()
                            : si.getSigningCertificateHistory();
                    String d = sigOf(ss);
                    if (d != null) return d;
                }
            } catch (Throwable ignored) {
            }
        }
        return sigOf(pi.signatures);
    }

    private static String sigOf(Signature[] sigs) {
        if (sigs == null || sigs.length == 0) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读 APK 里 lib/<abi>/*.so 涉及哪些 ABI；纯 Java（没有 .so）的包返回空列表 */
    public static List<String> apkAbis(File apk) {
        List<String> out = new ArrayList<>();
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(apk);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.startsWith("lib/") || !n.endsWith(".so")) continue;
                int slash = n.indexOf('/', 4);
                if (slash <= 4) continue;
                String abi = n.substring(4, slash).toLowerCase(java.util.Locale.US);
                if (!abi.isEmpty() && !out.contains(abi)) out.add(abi);
            }
        } catch (Throwable ignored) {
        } finally {
            if (zip != null) try { zip.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    private static String join(List<String> v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(v.get(i));
        }
        return sb.toString();
    }

    /** 尾部是否含 APK Signature Scheme v2/v3 块 */
    public static boolean hasV2(File apk) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(apk, "r");
            byte[] magic = "APK Sig Block 42".getBytes("US-ASCII");
            long len = raf.length();
            int win = (int) Math.min(len, 128 * 1024);
            raf.seek(len - win);
            byte[] tail = new byte[win];
            raf.readFully(tail);
            for (int i = 0; i + magic.length <= tail.length; i++) {
                boolean ok = true;
                for (int j = 0; j < magic.length; j++) {
                    if (tail[i + j] != magic[j]) { ok = false; break; }
                }
                if (ok) return true;
            }
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) { }
        }
        return false;
    }

    /** 拉起系统安装器；返回 null 表示已发起，否则返回错误说明 */
    public static String install(Context c, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(c, AUTHORITY, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, ApkExporter.APK_MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            // 没有「安装未知应用」权限时系统会抛 ActivityNotFoundException 或 SecurityException
            if (t instanceof SecurityException) {
                return "被系统拦下了：需要给本应用「安装未知应用」权限。\n"
                        + "设置 → 应用 → AgenCode → 安装未知应用 → 允许。\n（" + m + "）";
            }
            return "打不开系统安装器：" + m
                    + "\n可以手动到「" + apk.getParent() + "」找到文件点击安装。";
        }
    }

    /** 跳到「安装未知应用」授权页 */
    public static void openInstallPermission(Context c) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + c.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }
    // ------------------------------------------------------------ 会话安装（能拿到失败原因）

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    /**
     * 走 PackageInstaller 会话安装。
     * 好处：安装失败时系统会把 INSTALL_FAILED_xxx 回传给我们，
     * 而 ACTION_VIEW 拉起安装器后完全是黑盒，用户只能对着「应用未安装」发呆。
     *
     * @return null 表示已发起（结果走 callback），否则返回同步错误
     */
    public static String installSession(Context c, File apk, final Callback cb) {
        if (Build.VERSION.SDK_INT < 21) return "系统版本过低，不支持会话安装";
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        int id;
        try {
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            try {
                sp.setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_AUTO);
            } catch (Throwable ignored) {
            }
            id = pi.createSession(sp);
        } catch (Throwable t) {
            return "无法创建安装会话：" + t.getMessage();
        }

        try {
            PackageInstaller.Session session = pi.openSession(id);
            java.io.OutputStream os = session.openWrite("base.apk", 0, apk.length());
            java.io.InputStream is = new java.io.FileInputStream(apk);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                session.fsync(os);
                os.flush();
            } finally {
                try { is.close(); } catch (Exception ignored) { }
                try { os.close(); } catch (Exception ignored) { }
            }

            final String action = c.getPackageName() + ".INSTALL_RESULT_" + id;
            final android.content.BroadcastReceiver[] holder = new android.content.BroadcastReceiver[1];
            holder[0] = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        // 系统要弹确认框，把它的 Intent 拉起来
                        Intent confirm = null;
                        try {
                            Object o = i.getParcelableExtra(Intent.EXTRA_INTENT);
                            if (o instanceof Intent) confirm = (Intent) o;
                        } catch (Throwable ignored) {
                        }
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try { ctx.startActivity(confirm); } catch (Throwable ignored) { }
                        }
                        return;
                    }
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    boolean ok = status == PackageInstaller.STATUS_SUCCESS;
                    String text = ok ? "安装成功，可以直接打开使用了" : explain(status, msg);
                    try { ctx.unregisterReceiver(this); } catch (Throwable ignored) { }
                    if (cb != null) cb.onResult(ok, text);
                }
            };

            Intent res = new Intent(action).setPackage(c.getPackageName());
            if (Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(holder[0], new android.content.IntentFilter(action),
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                c.registerReceiver(holder[0], new android.content.IntentFilter(action));
            }
            int pflags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) pflags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pend = PendingIntent.getBroadcast(c, id, res, pflags);
            session.commit(pend.getIntentSender());
            return null;
        } catch (Throwable t) {
            try { pi.abandonSession(id); } catch (Throwable ignored) { }
            return "安装会话写入失败：" + t.getMessage();
        }
    }

    /** 把系统的 INSTALL_FAILED_xxx 翻译成能照着做的中文 */
    public static String explain(int status, String raw) {
        String m = raw == null ? "" : raw;
        String code = "";
        int i = m.indexOf("INSTALL_");
        if (i >= 0) {
            String rest = m.substring(i);
            int j = 0;
            while (j < rest.length() && (Character.isLetterOrDigit(rest.charAt(j)) || rest.charAt(j) == '_')) j++;
            code = rest.substring(0, j);
        }
        String head = code.isEmpty() ? "系统返回状态 " + status : code;
        String why;
        if (code.contains("UPDATE_INCOMPATIBLE")) {
            why = "签名和手机上已安装的版本不一致。改包/重签名必然换签名，系统不允许覆盖 —— 先在设置里卸载原应用，再装这个包。";
        } else if (code.contains("VERSION_DOWNGRADE")) {
            why = "这个包的 versionCode 比已安装的版本低，系统拒绝降级。要么把 versionCode 改大，要么先卸载。";
        } else if (code.contains("DEPRECATED_SDK_VERSION")) {
            why = "targetSdk 太低，Android 14 起系统直接拒绝安装 targetSdk < 23 的应用，需要在工程里提高 targetSdk。";
        } else if (code.contains("OLDER_SDK")) {
            why = "这个包要求的最低 Android 版本高于本机系统版本。";
        } else if (code.contains("NO_MATCHING_ABIS")) {
            why = "包里的 native 库没有本机 CPU 架构（arm64-v8a）的版本。";
        } else if (code.contains("INSUFFICIENT_STORAGE")) {
            why = "手机存储空间不足，清理后再试。";
        } else if (code.contains("RESOURCES_ARSC_COMPRESSED")) {
            why = "resources.arsc 被压缩或没有 4 字节对齐，Android 11+ 会拒装（需要 zipalign -p 4）。";
        } else if (code.contains("INVALID_APK") || code.contains("PARSE_FAILED")) {
            why = "包结构损坏，通常是回编译过程出错或文件写入不完整，建议重新打包。";
        } else if (code.contains("ABORTED") || status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            why = "安装被取消，或者确认框超时没点。如果应用列表里已经能看到这个应用，"
                    + "说明它其实装好了——本机把这次会话报成了「用户放弃」，这条可以忽略。";
        } else if (code.contains("CONFLICTING_PROVIDER") || code.contains("DUPLICATE_PERMISSION")) {
            why = "包内的 provider/权限和手机上已有的应用冲突。";
        } else if (status == PackageInstaller.STATUS_FAILURE_BLOCKED) {
            why = "被系统策略拦截，检查是否开启了「安装未知应用」权限，或设备管理策略禁止安装。";
        } else {
            why = "系统没有给出更具体的原因，可以看下面的原始信息。";
        }
        return why + (m.isEmpty() ? "" : "\n原始信息：" + m);
    }
}
