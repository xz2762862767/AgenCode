package com.nebula.aichat.apk;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把生成的 APK 真正放到手机「下载」目录。
 *
 * 背景：targetSdk 34 = scoped storage，直接 new FileOutputStream("/sdcard/Download/x.apk")
 * 在没申请 MANAGE_EXTERNAL_STORAGE 的情况下必然抛 EACCES，而且很多人只在 catch 里写一行日志，
 * 用户看到「已签名」就以为文件已经导出，去文件管理器一看什么都没有。
 *
 * 这里按能力分级：
 *   1) 有「所有文件访问」权限（或 Android 9- 的写权限）→ 直接 File 写到公共下载目录，路径最直观
 *   2) 否则走 MediaStore 插入 Downloads 集合（Android 10+ 免权限，用户也能在下载目录看到）
 */
public final class ApkExporter {

    private ApkExporter() {
    }

    public static final String APK_MIME = "application/vnd.android.package-archive";

    /** 系统下载目录的 File（可能无写权限，仅用于取路径）。产物请用 ArtifactStore。 */
    public static File publicDownloads() {
        File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (d == null) d = new File("/sdcard/Download");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 是否可以直接用 File API 写公共下载目录 */
    public static boolean canWritePublicDir(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 是否需要引导用户去开「所有文件访问」 */
    public static boolean needAllFilesAccess() {
        return Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager();
    }

    /**
     * 导出到「AgenCode」目录，返回用户可见的真实路径。
     * 成功一定返回描述字符串，失败抛 IOException（调用方必须把异常显示给用户）。
     *
     * @param sub 产物分类子目录，如 ArtifactStore.SUB_APK
     */
    public static String exportToArtifacts(Context c, File src, String sub, String displayName)
            throws IOException {
        if (src == null || !src.isFile() || src.length() < 1024) {
            throw new IOException("源文件无效：" + (src == null ? "" : src.getAbsolutePath()));
        }
        String dir = (sub == null || sub.trim().isEmpty()) ? ArtifactStore.SUB_APK : sub.trim();

        // 1) 有「所有文件访问」→ 直接写 /sdcard/AgenCode/<分类>
        if (canWritePublicDir(c)) {
            File dst = new File(ArtifactStore.publicSub(dir), displayName);
            copy(src, dst);
            scan(c, dst);
            return dst.getAbsolutePath();
        }
        // 2) MediaStore：写 Download/AgenCode/<分类>（免权限，用户同样看得到「AgenCode」文件夹）
        if (Build.VERSION.SDK_INT >= 29) {
            String p = exportViaMediaStore(c, src, dir, displayName);
            if (p != null) return p;
        }
        // 3) 退回应用私有目录的「AgenCode」，再用「安装产物」按钮装
        File fallback = ArtifactStore.writableSub(c, dir);
        File dst = new File(fallback, displayName);
        copy(src, dst);
        return dst.getAbsolutePath() + "（应用私有目录，未授权存储，无法写进公共的「AgenCode」）";
    }

    /** 兼容旧调用：APK 产物固定放 AgenCode/APK */
    public static String exportToDownload(Context c, File src, String displayName) throws IOException {
        return exportToArtifacts(c, src, ArtifactStore.SUB_APK, displayName);
    }

    /**
     * 导出任意类型的成品（不限 APK），沙箱产物自动同步用。
     * 和 exportToArtifacts 的区别：不卡 1024 字节下限，MIME 按扩展名推断。
     */
    public static String exportAny(Context c, File src, String sub, String displayName)
            throws IOException {
        if (src == null || !src.isFile() || src.length() <= 0) {
            throw new IOException("源文件无效：" + (src == null ? "" : src.getAbsolutePath()));
        }
        String dir = sub == null ? "" : sub.trim();
        String mime = mimeOf(displayName);

        if (canWritePublicDir(c)) {
            File base = dir.isEmpty() ? ArtifactStore.publicDir() : ArtifactStore.publicSub(dir);
            File dst = new File(base, displayName);
            copy(src, dst);
            scan(c, dst, mime);
            return dst.getAbsolutePath();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            String p = exportViaMediaStore(c, src, dir, displayName, mime);
            if (p != null) return p;
        }
        File base = dir.isEmpty() ? ArtifactStore.writableDir(c) : ArtifactStore.writableSub(c, dir);
        File dst = new File(base, displayName);
        copy(src, dst);
        return dst.getAbsolutePath() + "（应用私有目录）";
    }

    /** 按扩展名猜 MIME，猜不出用二进制流 */
    public static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".aab")) return APK_MIME;
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".jar")) return "application/java-archive";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        return "application/octet-stream";
    }

    /** Android 10+ 免权限写 Download/AgenCode/<sub> */
    @SuppressLint("NewApi")   // 只在 SDK_INT >= 29 分支里被调用
    private static String exportViaMediaStore(Context c, File src, String sub, String displayName) {
        return exportViaMediaStore(c, src, sub, displayName, APK_MIME);
    }

    /** 同上，MIME 可指定：脚本产物如果是 png/mp4，别都写成 APK 类型 */
    @SuppressLint("NewApi")   // 同上：调用点已保证 29+
    private static String exportViaMediaStore(Context c, File src, String sub, String displayName,
                                              String mime) {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + ArtifactStore.NAME
                + (sub == null || sub.isEmpty() ? "" : "/" + sub);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = c.getContentResolver().insert(collection, v);
            if (uri == null) return null;
            OutputStream os = c.getContentResolver().openOutputStream(uri, "w");
            if (os == null) return null;
            InputStream is = new FileInputStream(src);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } finally {
                try { is.close(); } catch (Exception ignored) { }
                try { os.close(); } catch (Exception ignored) { }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            c.getContentResolver().update(uri, done, null, null);
            return rel + "/" + displayName;
        } catch (Throwable t) {
            if (uri != null) {
                try { c.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            }
            return null;
        }
    }

    private static void scan(Context c, File f) {
        scan(c, f, APK_MIME);
    }

    private static void scan(Context c, File f, String mime) {
        try {
            MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        } catch (Throwable ignored) {
        }
    }

    public static void copy(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileInputStream is = new FileInputStream(src);
        FileOutputStream os = new FileOutputStream(dst, false);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            try { is.close(); } catch (Exception ignored) { }
            try { os.close(); } catch (Exception ignored) { }
        }
        if (dst.length() != src.length()) {
            throw new IOException("写入不完整：期望 " + src.length() + " 字节，实际 " + dst.length() + " 字节");
        }
    }
}
