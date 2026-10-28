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

    public static synchronized void rememberBuilt(File privateFile, String publicPath) {
        LAST_BUILT.clear();
        if (publicPath != null && !publicPath.trim().isEmpty()) {
            File pub = new File(publicPath.trim());
            if (pub.isFile() && pub.length() > 1024) LAST_BUILT.add(pub);
        }
        if (privateFile != null && privateFile.isFile() && privateFile.length() > 1024) {
            String pv = privateFile.getAbsolutePath();
            boolean dup = false;
            for (File f : LAST_BUILT) {
                if (f.getAbsolutePath().equals(pv)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) LAST_BUILT.add(privateFile);
        }
        lastBuiltAt = android.os.SystemClock.uptimeMillis();
    }

    public static synchronized List<File> lastBuilt() {
        return new ArrayList<>(LAST_BUILT);
    }

    public static class Check {
        public final List<String> problems = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public String pkg = "";
        public int minSdk;
        public int targetSdk;
        /** 只有 true 才建议直接点安装 */
        public boolean installable = true;
        /** 手机上已装同包名（用于给「卸载后重装」入口） */
        public boolean installed;
        /** 已装的那份签名不同：改包必然换签名，只能先卸载 */
        public boolean signMismatch;
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
        PackageInfo pi;
        try {
            pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES | PackageManager.GET_PERMISSIONS);
        } catch (Throwable t) {
            pi = null;
        }
        if (pi == null) {
            r.installable = false;
            r.problems.add("系统无法解析这个包。常见原因：manifest 没写 versionCode、dex 损坏、"
                    + "或文件拷贝时被截断。重新打包一次通常就能解决。");
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
            PackageInfo installed = pm.getPackageInfo(r.pkg, PackageManager.GET_SIGNATURES);
            if (installed != null) {
                r.installed = true;
                long ivc = Build.VERSION.SDK_INT >= 28
                        ? installed.getLongVersionCode() : installed.versionCode;
                String a = sigOf(pi.signatures);
                String b = sigOf(installed.signatures);
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
            why = "安装被取消，或者确认框超时没点。";
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
