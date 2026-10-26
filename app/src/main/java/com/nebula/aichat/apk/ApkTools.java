package com.nebula.aichat.apk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** APK 信息解析 / 换图标 / 重签名 */
public class ApkTools {

    private static final long IN_MEMORY_LIMIT = 24L * 1024 * 1024;
    private static final String KS_ASSET = "apk-tools.p12";
    private static final String KS_PASS = "nebula123";
    private static final String KS_ALIAS = "nebula";

    public interface Progress {
        void onStep(String msg);
    }

    public static class Info {
        public String pkgName = "";
        public String versionName = "";
        public String label = "";
        public long versionCode;
        public int minSdk;
        public int targetSdk;
        public long size;
        public int activityCount;
        public String[] permissions = new String[0];
        public Drawable icon;
    }

    public static class Result {
        public File output;
        public int iconsReplaced;
        public boolean v1;
        public boolean v2;
        public List<String> iconNames = new ArrayList<>();
    }

    public static Info inspect(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_PERMISSIONS);
        if (pi == null) return null;
        Info info = new Info();
        info.pkgName = pi.packageName;
        info.versionName = pi.versionName == null ? "" : pi.versionName;
        info.versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        info.size = apk.length();
        info.permissions = pi.requestedPermissions == null ? new String[0] : pi.requestedPermissions;
        info.activityCount = pi.activities == null ? 0 : pi.activities.length;
        ApplicationInfo ai = pi.applicationInfo;
        if (ai != null) {
            ai.sourceDir = apk.getAbsolutePath();
            ai.publicSourceDir = apk.getAbsolutePath();
            info.minSdk = ai.minSdkVersion;
            info.targetSdk = ai.targetSdkVersion;
            try {
                info.label = pm.getApplicationLabel(ai).toString();
            } catch (Throwable t) {
                info.label = ai.packageName;
            }
            try {
                info.icon = pm.getApplicationIcon(ai);
            } catch (Throwable ignored) {
            }
        }
        return info;
    }

    /** 换图标 + 重签名，输出到 outDir，返回输出文件 */
    public static Result replaceIconAndSign(Context c, File src, File iconPng, File outDir,
                                           Progress cb) throws Exception {
        if (cb != null) cb.onStep("正在分析 APK 结构…");
        Result result = new Result();
        Set<String> targets = findIconEntries(src, result.iconNames);
        if (targets.isEmpty()) {
            throw new IOException("未在 APK 中找到图标资源（res 下无 launcher 相关图片）");
        }
        if (cb != null) cb.onStep("找到 " + targets.size() + " 个图标条目，准备替换…");

        byte[] newIcon = readFile(iconPng);

        File aligned = new File(outDir, "aligned.tmp.apk");
        if (aligned.exists()) aligned.delete();

        ZipFile zip = new ZipFile(src);
        int replaced = 0;
        try {
            FileOutputStream fos = new FileOutputStream(aligned);
            ZipWriter zw = new ZipWriter(fos);
            Enumeration<? extends ZipEntry> en = zip.entries();
            byte[] buf = new byte[65536];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (isOldSignature(name)) continue;

                if (targets.contains(name)) {
                    zw.addImage(name, newIcon);
                    replaced++;
                    continue;
                }

                long size = e.getSize();
                if (size < 0) size = 0;
                if ("resources.arsc".equals(name)) {
                    zw.add(name, readEntry(zip, e), 0, 4);
                } else if (name.endsWith(".so")) {
                    InputStream in = zip.getInputStream(e);
                    zw.addStreamed(name, in, size, e.getCrc(), 4096);
                    in.close();
                } else if (size > IN_MEMORY_LIMIT) {
                    InputStream in = zip.getInputStream(e);
                    zw.addStreamed(name, in, size, e.getCrc(), 4);
                    in.close();
                } else {
                    byte[] data = readEntry(zip, e);
                    zw.add(name, data, 6, 4);
                }
            }
            if (cb != null) cb.onStep("已完成 " + replaced + " 处图标替换，正在写入…");
            zw.finish();
            fos.flush();
            fos.close();
        } finally {
            zip.close();
        }
        result.iconsReplaced = replaced;

        if (cb != null) cb.onStep("正在生成签名（v1 + v2）…");
        File ks = ensureKeystore(c);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        FileInputStream kfis = new FileInputStream(ks);
        keyStore.load(kfis, KS_PASS.toCharArray());
        kfis.close();
        PrivateKey pk = (PrivateKey) keyStore.getKey(KS_ALIAS, KS_PASS.toCharArray());
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(KS_ALIAS);
        List<X509Certificate> chain = Arrays.asList(cert);

        Info info = inspect(c, src);
        int minSdk = info == null || info.minSdk <= 0 ? 24 : info.minSdk;

        File out = new File(outDir, baseName(src.getName()) + "-mod.apk");
        if (out.exists()) out.delete();

        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("Nebula", pk, chain).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(aligned)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(minSdk)
                .setCreatedBy("Nebula APK Tools")
                .build()
                .sign();

        result.output = out;
        result.v1 = hasV1(out);
        result.v2 = hasV2Block(out);
        aligned.delete();
        if (cb != null) cb.onStep("完成：" + out.getName());
        return result;
    }

    /**
     * 对任意 APK 做 v1 + v2 签名（供 apktool 回编译产物使用）。
     */
    /**
     * 保证 resources.arsc 不压缩且 4 字节对齐（Android 11 起的硬要求）。
     * apktool 回编译的包偶尔会把 arsc 压进去，装到新系统上就是
     * INSTALL_FAILED_RESOURCES_ARSC_COMPRESSED；手机上没有 zipalign，只能自己重写一遍 zip。
     * 已经合规时直接返回原文件，不做多余拷贝。
     */
    public static File ensureInstallable(File in, File out) throws Exception {
        boolean need = false;
        ZipFile probe = new ZipFile(in);
        try {
            ZipEntry arsc = probe.getEntry("resources.arsc");
            if (arsc != null && arsc.getMethod() != ZipEntry.STORED) need = true;
        } finally {
            try {
                probe.close();
            } catch (Exception ignored) {
            }
        }
        if (!need) return in;

        ZipFile zip = new ZipFile(in);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            ZipWriter zw = new ZipWriter(fos);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (isOldSignature(name)) continue;

                if ("resources.arsc".equals(name)) {
                    zw.add(name, readEntry(zip, e), 0, 4);
                } else if (name.endsWith(".so")) {
                    InputStream is = zip.getInputStream(e);
                    zw.addStreamed(name, is, e.getSize(), e.getCrc(), 4096);
                    is.close();
                } else if (e.getSize() > IN_MEMORY_LIMIT) {
                    InputStream is = zip.getInputStream(e);
                    zw.addStreamed(name, is, e.getSize(), e.getCrc(), 4);
                    is.close();
                } else {
                    zw.add(name, readEntry(zip, e), e.getMethod(), 4);
                }
            }
            zw.finish();
            fos.flush();
        } finally {
            try {
                fos.close();
            } catch (Exception ignored) {
            }
            try {
                zip.close();
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static File signFile(Context c, File in, File out) throws Exception {
        if (in == null || !in.exists() || in.length() < 1024) {
            throw new IOException("待签名文件无效");
        }
        File ks = ensureKeystore(c);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        FileInputStream kfis = new FileInputStream(ks);
        keyStore.load(kfis, KS_PASS.toCharArray());
        kfis.close();
        PrivateKey pk = (PrivateKey) keyStore.getKey(KS_ALIAS, KS_PASS.toCharArray());
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(KS_ALIAS);
        List<X509Certificate> chain = Arrays.asList(cert);

        int minSdk = 24;
        try {
            Info info = inspect(c, in);
            if (info != null && info.minSdk > 0) minSdk = info.minSdk;
        } catch (Exception ignored) {
        }

        File parent = out.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (out.exists()) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }

        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("Nebula", pk, chain).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(minSdk)
                .setCreatedBy("Nebula APK Tools")
                .build()
                .sign();
        return out;
    }

    /** APK 内是否存在 JAR 签名（META-INF/*.SF） */
    private static boolean hasV1(File apk) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().toUpperCase(java.util.Locale.ROOT);
                if (n.startsWith("META-INF/") && n.endsWith(".SF")) return true;
            }
        } catch (Throwable ignored) {
        } finally {
            if (zip != null) try { zip.close(); } catch (Exception ignored) { }
        }
        return false;
    }

    /** APK 尾部是否存在 APK Signature Scheme v2/v3 签名块 */
    private static boolean hasV2Block(File apk) {
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(apk, "r");
            byte[] magic = "APK Sig Block 42".getBytes("US-ASCII");
            long len = raf.length();
            int win = (int) Math.min(len, 64 * 1024);
            raf.seek(len - win);
            byte[] tail = new byte[win];
            raf.readFully(tail);
            raf.close();
            for (int i = 0; i + magic.length <= tail.length; i++) {
                boolean ok = true;
                for (int j = 0; j < magic.length; j++) {
                    if (tail[i + j] != magic[j]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String u = name.toUpperCase(java.util.Locale.ROOT);
        return u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA")
                || u.endsWith(".EC") || u.endsWith("MANIFEST.MF");
    }

    /** 收集需要替换的图标条目名 */
    private static Set<String> findIconEntries(File apk, List<String> found) throws IOException {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> adaptiveRefs = new LinkedHashSet<>();
        ZipFile zip = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zip.entries();
            List<String> all = new ArrayList<>();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                all.add(e.getName());
            }
            // 1) 自适应图标 xml 引用的资源名
            for (String n : all) {
                if (!n.startsWith("res/") || !n.endsWith(".xml")) continue;
                String ln = n.toLowerCase(java.util.Locale.ROOT);
                if (!(ln.contains("mipmap-anydpi") || ln.contains("drawable-anydpi"))) continue;
                if (!ln.contains("launcher") && !ln.contains("icon")) continue;
                try {
                    byte[] data = readEntry(zip, zip.getEntry(n));
                    String text = new String(data, "UTF-8");
                    collectRefs(text, adaptiveRefs);
                } catch (Throwable ignored) {
                }
            }
            // 2) 直接命名的图片条目
            for (String n : all) {
                if (!n.startsWith("res/")) continue;
                String ln = n.toLowerCase(java.util.Locale.ROOT);
                boolean img = ln.endsWith(".png") || ln.endsWith(".webp");
                if (!img) continue;
                String file = ln.substring(ln.lastIndexOf('/') + 1);
                boolean hit = file.contains("ic_launcher") || file.contains("ic_launch")
                        || file.contains("app_icon") || file.contains("appicon")
                        || file.contains("launcher_icon");
                if (!hit) {
                    for (String ref : adaptiveRefs) {
                        if (file.startsWith(ref + ".") || file.equals(ref + ".png")
                                || file.equals(ref + ".webp")) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (hit) targets.add(n);
            }
        } finally {
            zip.close();
        }
        if (found != null) found.addAll(targets);
        return targets;
    }

    private static void collectRefs(String text, Set<String> out) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("@(?:mipmap|drawable)/([A-Za-z0-9_]+)").matcher(text);
        while (m.find()) out.add(m.group(1));
    }

    private static File ensureKeystore(Context c) throws IOException {
        File dir = new File(c.getFilesDir(), "apk-tools");
        if (!dir.exists()) dir.mkdirs();
        File ks = new File(dir, "keystore.p12");
        if (ks.length() > 0) return ks;
        InputStream in = c.getAssets().open(KS_ASSET);
        FileOutputStream fos = new FileOutputStream(ks);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        in.close();
        fos.close();
        return ks;
    }

    private static String baseName(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry e) throws IOException {
        InputStream in = zip.getInputStream(e);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(
                e.getSize() > 0 && e.getSize() < Integer.MAX_VALUE ? (int) e.getSize() : 8192);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    static byte[] readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
