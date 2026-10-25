package com.nebula.aichat.apk;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * 统一产物目录：手机里叫「星云产物」的那个文件夹。
 *
 * 为什么要单独一个类：
 *  1) 以前产物散落在「下载」根目录里，和别人的文件混在一起，时间一长就找不到了；
 *  2) 有没有「所有文件访问」权限，能写到哪完全不同（公共根目录 / MediaStore / 应用私有），
 *     这些降级逻辑必须只有一份，否则各处调用会给出互相矛盾的路径。
 *
 * 目录结构：
 *   星云产物/APK/      打包签名后的安装包
 *   星云产物/工程/     反编译出来的可编辑工程
 *   星云产物/日志/     构建日志等
 *
 * 有权限 → /sdcard/星云产物
 * 没权限 → 用 MediaStore 写 Download/星云产物（文件管理器里同样叫「星云产物」）
 */
public final class ArtifactStore {

    private ArtifactStore() {
    }

    public static final String NAME = "星云产物";
    public static final String SUB_APK = "APK";
    public static final String SUB_PROJECT = "工程";
    public static final String SUB_LOG = "日志";

    /** 首选的公共产物目录：/sdcard/星云产物 */
    public static File publicDir() {
        return new File(Environment.getExternalStorageDirectory(), NAME);
    }

    public static File publicSub(String sub) {
        File d = new File(publicDir(), sub);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * 当前真正可写的产物目录。
     * 有「所有文件访问」→ /sdcard/星云产物；否则退回应用外部私有目录（同名的「星云产物」）。
     */
    public static File writableDir(Context c) {
        if (c != null && ApkExporter.canWritePublicDir(c)) {
            File d = publicDir();
            if (d.exists() || d.mkdirs()) return d;
        }
        File priv = null;
        try {
            priv = c == null ? null : c.getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        if (priv == null) priv = c == null ? new File("/sdcard") : c.getFilesDir();
        File d = new File(priv, NAME);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File writableSub(Context c, String sub) {
        File d = new File(writableDir(c), sub);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** 一句话说明产物现在存在哪儿，用于界面提示 */
    public static String describe(Context c) {
        if (c != null && ApkExporter.canWritePublicDir(c)) {
            return publicDir().getAbsolutePath();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return "Download/" + NAME + "（存到「下载」里的「星云产物」文件夹）";
        }
        return writableDir(c).getAbsolutePath() + "（应用私有目录）";
    }

    /** 产物目录是否已经就绪（公共目录可写 or 私有目录已建） */
    public static boolean ready(Context c) {
        File d = writableDir(c);
        return d != null && d.isDirectory();
    }

    /** 把工程目录复制进 星云产物/工程/，返回目标目录 */
    public static File exportProject(Context c, File projectDir) throws java.io.IOException {
        if (projectDir == null || !projectDir.isDirectory()) {
            throw new java.io.IOException("工程目录不存在");
        }
        File dst = new File(writableSub(c, SUB_PROJECT), projectDir.getName());
        copyTree(projectDir, dst);
        return dst;
    }

    private static void copyTree(File src, File dst) throws java.io.IOException {
        if (src.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            File[] fs = src.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                if (f == null) continue;
                copyTree(f, new File(dst, f.getName()));
            }
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            ApkExporter.copy(src, dst);
        }
    }

    /** 目录里文件总数 / 体积，用于导出前提示（避免用户不知情拷几百 MB） */
    public static long[] sizeOf(File dir) {
        long[] r = new long[]{0, 0};
        if (dir == null || !dir.exists()) return r;
        File[] fs = dir.listFiles();
        if (fs == null) return r;
        for (File f : fs) {
            if (f == null) continue;
            if (f.isDirectory()) {
                long[] sub = sizeOf(f);
                r[0] += sub[0];
                r[1] += sub[1];
            } else {
                r[0]++;
                r[1] += f.length();
            }
        }
        return r;
    }
}
