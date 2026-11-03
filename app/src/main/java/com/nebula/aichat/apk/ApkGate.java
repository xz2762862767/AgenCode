package com.nebula.aichat.apk;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK 出厂质检：一个包到底装不装得上，在这里一次判完。
 *
 * 为什么要把关卡在产物目录前面：改包链路里 AI 会跑 apktool、gradle、apksigner，
 * 这一路上会落下各种半成品 —— 只打了壳没塞 dex 的、回编译没签名的、arsc 被压过的。
 * 以前这些文件只要超过 512 字节就会被自动同步进「AgenCode/APK」，
 * 用户拿到一个点了装不上的包，还得自己去猜为什么。
 *
 * 现在的规矩：能装才叫成品。过不了质检的包不落产物目录，但文件仍留在工作区，
 * 并且把「缺什么、怎么补」原样回报给 AI 和用户 —— 该修就修，不静默丢弃。
 *
 * 检查顺序按「越致命越先报」排：坏了 → 缺清单 → 缺代码 → 解析不了 → 没签名 → 版本不符。
 * 一次只报第一处问题，因为后面几项在结构坏了的情况下没有意义，而且人一次只能修一件。
 */
public final class ApkGate {

    private ApkGate() {
    }

    public static final class Verdict {
        /** 能装上机 */
        public boolean ok = true;
        /** 不能装的原因（一句话，直接给人看） */
        public String problem = "";
        /** 该怎么办 */
        public String fix = "";
        public String pkg = "";
        public long versionCode;
        public long size;
        /** 有签名（判断不了时按有算，宁可放过不误拦） */
        public boolean signed = true;

        /** 拼成一行回报文案 */
        public String line() {
            if (ok) return "";
            return problem + (fix.isEmpty() ? "" : " —— " + fix);
        }
    }

    /** 只关心结论 */
    public static boolean pass(Context c, File apk) {
        return inspect(c, apk).ok;
    }

    public static Verdict inspect(Context c, File apk) {
        Verdict v = new Verdict();
        if (apk == null || !apk.isFile()) {
            return bad(v, "文件不存在", "重新打包");
        }
        v.size = apk.length();
        if (v.size < 2048) {
            return bad(v, "只有 " + v.size + " 字节，正常安装包不可能这么小",
                    "打包或签名那一步没真正跑完，回去看构建日志");
        }

        // ---- 1. 包结构：能不能当压缩包打开，里面有没有该有的东西 ----
        boolean hasManifest = false;
        boolean hasDex = false;
        boolean hasArsc = false;
        boolean arscStored = false;
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e == null) continue;
                String n = e.getName();
                if ("AndroidManifest.xml".equals(n)) {
                    hasManifest = true;
                } else if (n.startsWith("classes") && n.endsWith(".dex")) {
                    if (e.getSize() != 0) hasDex = true;
                } else if ("resources.arsc".equals(n)) {
                    hasArsc = true;
                    arscStored = e.getMethod() == ZipEntry.STORED;
                }
            }
        } catch (Throwable t) {
            return bad(v, "这个包连压缩包都打不开（" + t.getClass().getSimpleName() + "）",
                    "文件是坏的或被写截断了，重新打包一次");
        } finally {
            try {
                if (z != null) z.close();
            } catch (Throwable ignored) {
            }
        }

        if (!hasManifest) {
            return bad(v, "包里没有 AndroidManifest.xml，这不是一个能安装的 APK",
                    "可能是把回编译的目录当成了安装包，要跑完 pack 再签名");
        }
        if (!hasDex) {
            return bad(v, "包里没有 classes.dex，只有清单和资源，一点代码都没有",
                    "编译那一步没跑完、或者 dex 没塞进去，重新打包");
        }

        // ---- 2. 系统能不能解析这份清单 ----
        PackageManager pm = c == null ? null : c.getPackageManager();
        if (pm == null) {
            return bad(v, "拿不到系统的包管理器，无法校验", "在正常界面里重新打包一次");
        }
        PackageInfo pi = null;
        boolean sigKnown = true;
        try {
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        } catch (Throwable ignored) {
        }
        if (pi == null) {
            // 带签名 flags 解析失败时退一步：也许只是取签名信息这一项不支持，
            // 包本身还能解析。这种情况拿不到签名，所以不再下「没签名」的结论。
            try {
                pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                sigKnown = false;
            } catch (Throwable ignored) {
            }
        }
        if (pi == null) {
            return bad(v, "系统解析不了这个包的清单（结构损坏，或 manifest 不是合法二进制）",
                    "重新编译；回编译出来的包别手改二进制清单，让工具重新生成");
        }

        v.pkg = pi.packageName == null ? "" : pi.packageName.trim();
        v.versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        if (v.pkg.isEmpty() || v.pkg.indexOf('.') < 0) {
            return bad(v, "包名不合法（" + (v.pkg.isEmpty() ? "空" : v.pkg) + "）",
                    "清单的 manifest 标签要写 package，形如 com.example.app");
        }
        if (v.versionCode <= 0) {
            return bad(v, "versionCode 是 " + v.versionCode + "，系统不认",
                    "在 manifest 标签上补 android:versionCode，从 1 开始");
        }

        // ---- 3. 签名：没签名的包系统一律拒装 ----
        if (sigKnown) {
            v.signed = hasSigner(pi);
            if (!v.signed) {
                return bad(v, "包没有签名，Android 会直接拒装",
                        "跑一遍签名（v1 + v2），签完再导出");
            }
        }

        // ---- 4. 版本门槛 ----
        if (pi.applicationInfo != null) {
            int min = pi.applicationInfo.minSdkVersion;
            int tgt = pi.applicationInfo.targetSdkVersion;
            if (min > 0 && min > Build.VERSION.SDK_INT) {
                return bad(v, "要求 Android API " + min + "，本机只有 " + Build.VERSION.SDK_INT,
                        "换一份适配低版本系统的包，或把 minSdk 调低");
            }
            if (tgt > 0 && tgt < 23) {
                return bad(v, "targetSdk 是 " + tgt + "，Android 14 起系统拒装低于 23 的应用",
                        "把 targetSdk 提到 23 以上再打包");
            }
        }

        // ---- 5. arsc 压缩与对齐：Android 11+ 的硬要求 ----
        // 这两项 apksigner verify 都不查，验签全过、装的时候照样被拒。
        if (hasArsc) {
            if (!arscStored) {
                return bad(v, "resources.arsc 被压缩了，Android 11 起系统拒装",
                        "按「先 zipalign -p -f 4、后签名」的顺序重做一遍");
            }
            int tgtSdk = pi.applicationInfo == null ? 0 : pi.applicationInfo.targetSdkVersion;
            if (tgtSdk >= 30) {
                long off = dataOffsetOf(apk, "resources.arsc");
                if (off > 0 && (off % 4) != 0) {
                    return bad(v, "resources.arsc 在包里没有按 4 字节对齐（文件偏移 " + off
                                    + "），targetSdk " + tgtSdk + " 的包系统会拒装",
                            "按「先 zipalign -p -f 4、后签名」的顺序重做一遍，顺序反了会破坏签名");
                }
            }
        }

        return v;
    }

    /** 从解析结果里看有没有签名证书 */
    private static boolean hasSigner(PackageInfo pi) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                if (pi.signingInfo == null) return false;
                if (pi.signingInfo.hasMultipleSigners()) {
                    return pi.signingInfo.getApkContentsSigners() != null;
                }
                return pi.signingInfo.getSigningCertificateHistory() != null;
            }
            return pi.signatures != null && pi.signatures.length > 0;
        } catch (Throwable t) {
            return true;   // 判断不了就别拦，宁可放过
        }
    }

    /**
     * 读某个条目在 APK 文件里的真实数据偏移（用来算对齐）。
     *
     * 为什么要手撕 zip：ZipEntry 只给压缩后大小，不给偏移，而对齐恰恰是看偏移。
     * 走中央目录找到条目的 local header 位置，再跳过固定头 + 文件名 + extra，
     * 落到数据区开头 —— 这就是 zipalign 校验的那个数字。
     *
     * @return 数据偏移；找不到 / 结构异常返回 -1
     */
    private static long dataOffsetOf(File apk, String want) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(apk, "r");
            long len = raf.length();
            int tail = (int) Math.min(len, 22 + 65535);
            byte[] buf = new byte[tail];
            raf.seek(len - tail);
            raf.readFully(buf);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (buf[i] == 0x50 && buf[i + 1] == 0x4b && buf[i + 2] == 0x05 && buf[i + 3] == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return -1;
            int count = u16(buf, eocd + 10);
            long cdOff = u32(buf, eocd + 16);
            if (cdOff <= 0 || cdOff >= len) return -1;

            raf.seek(cdOff);
            byte[] h = new byte[46];
            for (int k = 0; k < count; k++) {
                if (raf.read(h) != 46) break;
                if (!(h[0] == 0x50 && h[1] == 0x4b && h[2] == 0x01 && h[3] == 0x02)) break;
                int nameLen = u16(h, 28);
                int extraLen = u16(h, 30);
                int cmtLen = u16(h, 32);
                long localOff = u32(h, 42);
                byte[] nb = new byte[nameLen];
                raf.readFully(nb);
                String name = new String(nb, "UTF-8");
                long next = raf.getFilePointer() + extraLen + cmtLen;
                if (name.equals(want)) {
                    raf.seek(localOff);
                    byte[] lh = new byte[30];
                    if (raf.read(lh) != 30) return -1;
                    if (!(lh[0] == 0x50 && lh[1] == 0x4b && lh[2] == 0x03 && lh[3] == 0x04)) return -1;
                    return localOff + 30 + u16(lh, 26) + u16(lh, 28);
                }
                raf.seek(next);
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8)
                | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static Verdict bad(Verdict v, String problem, String fix) {
        v.ok = false;
        v.problem = problem;
        v.fix = fix;
        return v;
    }
}
