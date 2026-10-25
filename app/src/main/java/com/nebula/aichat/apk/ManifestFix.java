package com.nebula.aichat.apk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * apktool 解包出来的 AndroidManifest.xml 兼容性修补。
 *
 * <p>Android 12（API 31）起，只要组件带了 intent-filter，就必须显式写 android:exported，
 * 否则系统在解析阶段直接拒装（报 INSTALL_FAILED_MANIFEST_MALFORMED / 需要显式声明），
 * 或者装上了但 launcher 图标不出现。拿老包（targetSdk 29/30）改一改再回编译，
 * 几乎必然踩到这条 —— 这也是「明明签名没问题却装不上」最常见的真实原因。</p>
 *
 * <p>规则按系统要求来：有 intent-filter 的给 true（否则桌面点不开），没有的给 false（更安全）。</p>
 */
public final class ManifestFix {

    private static final String[] TAGS = {
            "activity", "activity-alias", "service", "receiver", "provider"
    };

    private static final Pattern BLOCK = Pattern.compile(
            "<(activity|activity-alias|service|receiver|provider)\\b([^>]*?)(/>|>(.*?)</\\1\\s*>)",
            Pattern.DOTALL);

    private ManifestFix() {
    }

    /**
     * 就地修补解包目录里的 AndroidManifest.xml。
     *
     * @return 补写了 exported 的组件数量；0 表示本来就合规
     */
    public static int patchExported(File xml) throws IOException {
        if (xml == null || !xml.isFile()) return 0;
        byte[] raw = readAll(xml);
        String src = new String(raw, "UTF-8");
        int[] count = new int[1];
        String dst = patch(src, count);
        if (dst.equals(src)) return 0;
        OutputStream os = new FileOutputStream(xml);
        try {
            os.write(dst.getBytes("UTF-8"));
            os.flush();
        } finally {
            try {
                os.close();
            } catch (Exception ignored) {
            }
        }
        return count[0];
    }

    private static String patch(String s, int[] count) {
        Matcher m = BLOCK.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group(1);
            String attrs = m.group(2) == null ? "" : m.group(2);
            String tail = m.group(3) == null ? "" : m.group(3);
            // 已经声明过就不动，避免破坏厂商自定义的 exported 值
            if (attrs.contains("android:exported")) continue;

            String inner = m.group(4);
            boolean selfClosed = tail.startsWith("/");
            boolean hasFilter = !selfClosed && inner != null && inner.contains("<intent-filter");
            String value = hasFilter ? "true" : "false";

            String rep;
            if (selfClosed) {
                rep = "<" + tag + attrs + " android:exported=\"" + value + "\" />";
            } else {
                rep = "<" + tag + attrs + " android:exported=\"" + value + "\">"
                        + inner + "</" + tag + ">";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            count[0]++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        InputStream is = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try {
                is.close();
            } catch (Exception ignored) {
            }
        }
        return bos.toByteArray();
    }
}
