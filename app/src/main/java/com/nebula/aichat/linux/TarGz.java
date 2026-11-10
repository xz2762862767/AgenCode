package com.nebula.aichat.linux;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * 纯 Java 的 tar.gz 解压器。
 * 支持 ustar 头、prefix 长路径、PAX 扩展头（x/g）、符号链接、硬链接、文件权限。
 * 不依赖任何第三方库，可在 Android 上直接使用。
 */
public final class TarGz {

    /** 解压进度回调（0~100），可为 null。 */
    public interface Progress {
        void onProgress(int percent, String currentEntry);
    }

    private TarGz() {
    }

    /**
     * 解压 gz 压缩的 tar 到 destDir。
     *
     * @param in        压缩包输入流（内部会包裹 GZIP）
     * @param destDir   目标目录
     * @param totalHint 未压缩总字节数的预估（用于进度，小于等于 0 则不报进度）
     * @param cb        进度回调
     */
    public static void extract(InputStream in, File destDir, long totalHint, Progress cb)
            throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir);
        }
        Path root = destDir.getCanonicalFile().toPath();

        // 用多段 gzip 流：Alpine 的 .apk 是三段 gzip 首尾相接拼的（签名/控制/数据），
        // 系统自带的 GZIPInputStream 读完第一段就返回 EOF，会把 180MB 数据段整个丢掉。
        // 普通单段 .tar.gz 走同一条路径，行为与原来一致。
        InputStream gz = new MultiGzipInputStream(new BufferedInputStream(in, 64 * 1024));
        byte[] header = new byte[512];
        byte[] buf = new byte[64 * 1024];
        long written = 0;
        // PAX 扩展头携带的覆盖字段
        Map<String, String> pax = new HashMap<String, String>();

        try {
            while (true) {
                if (!readFully(gz, header, 512)) break;
                // 全 0 块表示归档结束
                boolean empty = true;
                for (int i = 0; i < 512; i++) {
                    if (header[i] != 0) {
                        empty = false;
                        break;
                    }
                }
                if (empty) break;

                String name = str(header, 0, 100);
                String modeStr = str(header, 100, 8).trim();
                String sizeStr = str(header, 124, 12).trim();
                char type = (char) (header[156] == 0 ? '0' : (header[156] & 0xFF));
                String linkName = str(header, 157, 100);
                String magic = str(header, 257, 6);
                String prefix = "ustar".equals(magic.trim()) ? str(header, 345, 155) : "";

                long size = parseSize(sizeStr);
                if (prefix.length() > 0 && name.length() > 0) {
                    name = prefix + "/" + name;
                }

                // PAX 头：记录格式为 "%d %s=%s\n"
                if (type == 'x' || type == 'g') {
                    byte[] data = new byte[(int) Math.min(size, 1024 * 1024)];
                    readFully(gz, data, data.length);
                    skipFully(gz, size - data.length);
                    skipFully(gz, pad(size));
                    if (type == 'x') pax.putAll(parsePax(data));
                    continue;
                }
                // GNU 长文件名（L=长名 / K=长链接名）
                if (type == 'L' || type == 'K') {
                    byte[] data = new byte[(int) Math.min(size, 64 * 1024)];
                    readFully(gz, data, data.length);
                    skipFully(gz, size - data.length);
                    skipFully(gz, pad(size));
                    String longName = new String(data, StandardCharsets.UTF_8).trim();
                    if (type == 'L') pax.put("path", longName);
                    else pax.put("linkpath", longName);
                    continue;
                }

                if (pax.containsKey("path")) name = pax.get("path");
                if (pax.containsKey("linkpath")) linkName = pax.get("linkpath");
                pax.clear();

                name = clean(name);
                if (name.length() == 0) {
                    skipFully(gz, size + pad(size));
                    continue;
                }

                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    // 防目录穿越
                    skipFully(gz, size + pad(size));
                    continue;
                }
                File tf = target.toFile();

                if (cb != null) cb.onProgress(percent(written, totalHint), name);

                if (type == '5') {
                    if (!tf.exists()) //noinspection ResultOfMethodCallIgnored
                        tf.mkdirs();
                    applyMode(tf, modeStr, true);
                    skipFully(gz, pad(size));
                } else if (type == '2') {
                    mkParent(tf);
                    deleteQuietly(tf);
                    boolean ok = false;
                    try {
                        Files.createSymbolicLink(target, Paths.get(linkName));
                        ok = true;
                    } catch (Throwable t) {
                        // API 24/25 无 java.nio.file，或 ROM 限制，降级到 ln -s
                        ok = lnSymlink(linkName, tf);
                    }
                    if (!ok) {
                        // 最后兜底：写占位文件，保证解压不中断（自检会提示环境异常）
                        writeStub(tf, linkName);
                    }
                    skipFully(gz, pad(size));
                } else if (type == '1') {
                    mkParent(tf);
                    deleteQuietly(tf);
                    try {
                        Path src = root.resolve(clean(linkName)).normalize();
                        Files.createLink(target, src);
                    } catch (Throwable t) {
                        try {
                            Path src = root.resolve(clean(linkName)).normalize();
                            if (Files.isRegularFile(src)) Files.copy(src, target);
                        } catch (Throwable ignored) {
                        }
                    }
                    skipFully(gz, pad(size));
                } else if (type == '0' || type == '\0' || type == '7') {
                    mkParent(tf);
                    FileOutputStream fos = new FileOutputStream(tf);
                    try {
                        long left = size;
                        while (left > 0) {
                            int n = (int) Math.min(buf.length, left);
                            int r = gz.read(buf, 0, n);
                            if (r <= 0) throw new IOException("tar 数据截断: " + name);
                            fos.write(buf, 0, r);
                            left -= r;
                            written += r;
                        }
                    } finally {
                        try {
                            fos.close();
                        } catch (IOException ignored) {
                        }
                    }
                    applyMode(tf, modeStr, false);
                    skipFully(gz, pad(size));
                } else {
                    // 其他类型（设备节点等）直接跳过
                    skipFully(gz, size + pad(size));
                }
            }
        } finally {
            try {
                gz.close();
            } catch (IOException ignored) {
            }
        }
        if (cb != null) cb.onProgress(100, "");
    }

    // ---------------- 内部工具 ----------------

    private static String clean(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        if (n.equals(".") || n.equals("..") || n.startsWith("../") || n.contains("/../")) return "";
        return n;
    }

    private static void mkParent(File f) {
        File p = f.getParentFile();
        if (p != null && !p.exists()) //noinspection ResultOfMethodCallIgnored
            p.mkdirs();
    }

    private static void deleteQuietly(File f) {
        try {
            if (f.exists() || Files.isSymbolicLink(f.toPath())) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void writeStub(File f, String target) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(("SYMLINK->" + target).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (IOException ignored) {
        }
    }

    /** mode 字符串为八进制；目录必须可进入，可执行位对 shell 运行很重要。 */
    private static void applyMode(File f, String modeStr, boolean dir) {
        try {
            int mode = modeStr.length() == 0 ? (dir ? 0755 : 0644) : Integer.parseInt(modeStr, 8);
            boolean canExec = (mode & 0100) != 0 || dir;
            f.setReadable(true, false);
            f.setWritable(true, true);
            f.setExecutable(canExec, false);
        } catch (Throwable ignored) {
        }
    }

    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> map = new HashMap<String, String>();
        int i = 0;
        while (i < data.length) {
            // 读取 "长度 key=value\n"
            int sp = -1;
            for (int k = i; k < data.length && k < i + 20; k++) {
                if (data[k] == ' ') {
                    sp = k;
                    break;
                }
            }
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, i, sp - i, StandardCharsets.US_ASCII).trim());
            } catch (Exception e) {
                break;
            }
            if (len <= 0 || i + len > data.length) break;
            String rec = new String(data, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) map.put(rec.substring(0, eq), rec.substring(eq + 1));
            i += len;
        }
        return map;
    }

    private static long parseSize(String s) {
        if (s.length() == 0) return 0;
        try {
            return Long.parseLong(s.trim(), 8);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 用系统 ln 创建符号链接，失败返回 false */
    private static boolean lnSymlink(String linkName, File target) {
        try {
            Process pr = new ProcessBuilder("ln", "-s", linkName, target.getAbsolutePath())
                    .redirectErrorStream(true).start();
            pr.waitFor();
            return pr.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long pad(long size) {
        long r = size % 512;
        return r == 0 ? 0 : 512 - r;
    }

    private static int percent(long done, long total) {
        if (total <= 0) return 0;
        long p = done * 100 / total;
        return (int) Math.max(0, Math.min(99, p));
    }

    private static boolean readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] tmp = null;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (tmp == null) tmp = new byte[8192];
                int r = in.read(tmp, 0, (int) Math.min(tmp.length, left));
                if (r < 0) return;
                left -= r;
            } else {
                left -= s;
            }
        }
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /** 仅用于调试：统计归档条目数。 */
    public static int countEntries(InputStream in) throws IOException {
        GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(in, 8192), 8192);
        byte[] header = new byte[512];
        int n = 0;
        try {
            while (readFully(gz, header, 512)) {
                boolean empty = true;
                for (int i = 0; i < 512; i++) {
                    if (header[i] != 0) {
                        empty = false;
                        break;
                    }
                }
                if (empty) break;
                long size = parseSize(str(header, 124, 12).trim());
                char type = (char) (header[156] == 0 ? '0' : (header[156] & 0xFF));
                skipFully(gz, size + pad(size));
                if (type != 'x' && type != 'g' && type != 'L' && type != 'K') n++;
            }
        } finally {
            gz.close();
        }
        return n;
    }

    /** 把字节数组写到文件（供小文件使用）。 */
    public static void writeFile(File f, byte[] data) throws IOException {
        mkParent(f);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
            fos.flush();
        } finally {
            fos.close();
        }
    }

    /** 读全部字节。 */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    /**
     * 读整个文件，由本方法负责关闭流。
     * 调用方直接传 new FileInputStream(f) 会漏文件句柄（fd 耗尽后所有 IO 都会失败），
     * 统一走这个重载可以避免再犯。
     */
    public static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    /**
     * 支持「多段 gzip 首尾相接」的输入流。
     * <p>
     * Java 自带的 GZIPInputStream 读到第一个 member 的尾部就返回 EOF；而 Alpine 的 .apk 包
     * 恰好是三段 gzip 拼起来的（签名 2KB / 控制信息 2.5KB / 数据段 180MB），直接用它只能
     * 拿到签名那 2KB，数据段整个丢掉，装出来是个空环境。
     * <p>
     * 单段 .tar.gz（比如 rootfs.bin）走这里的行为与 GZIPInputStream 完全一致。
     */
    private static final class MultiGzipInputStream extends InputStream {
        private final PushbackInputStream src;
        private final Inflater inf = new Inflater(true);
        private final byte[] inBuf = new byte[64 * 1024];
        private final byte[] outBuf = new byte[64 * 1024];
        private int inLen;
        private int outPos;
        private int outLen;
        private long memberBytes;
        private boolean headerRead;
        private boolean eof;

        MultiGzipInputStream(InputStream in) {
            // 一段解完时 inflater 的输入缓冲里可能还留着下一段的开头，得能推回去
            src = new PushbackInputStream(in, 128 * 1024);
        }

        @Override
        public int read() throws IOException {
            if (!ensure()) return -1;
            return outBuf[outPos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!ensure()) return -1;
            int n = Math.min(len, outLen - outPos);
            System.arraycopy(outBuf, outPos, b, off, n);
            outPos += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            try {
                inf.end();
            } catch (Throwable ignored) {
            }
            src.close();
        }

        private boolean ensure() throws IOException {
            while (outPos >= outLen) {
                if (!fill()) return false;
            }
            return true;
        }

        private boolean fill() throws IOException {
            // 开头那一段的 gzip 头也得先吃掉：Inflater 期待的是裸 deflate 数据
            if (!headerRead) {
                headerRead = true;
                if (!readHeader()) {
                    eof = true;
                    return false;
                }
            }
            while (true) {
                if (eof) return false;
                if (inf.finished()) {
                    // 本段结束：尾部 8 字节（CRC32 + ISIZE）还在 inflater 的输入缓冲里没被取走
                    int rem = inf.getRemaining();
                    int tail = inLen - rem;
                    if (rem > 8 && tail >= 0) src.unread(inBuf, tail + 8, rem - 8);
                    inf.reset();
                    memberBytes = 0L;
                    inLen = 0;
                    if (!readHeader()) {
                        eof = true;
                        return false;
                    }
                    continue;
                }
                if (inf.needsInput()) {
                    int n = src.read(inBuf, 0, inBuf.length);
                    if (n <= 0) {
                        eof = true;
                        return false;
                    }
                    inLen = n;
                    inf.setInput(inBuf, 0, n);
                }
                int got;
                try {
                    got = inf.inflate(outBuf, 0, outBuf.length);
                } catch (DataFormatException e) {
                    throw new IOException("gzip 数据损坏: " + e.getMessage());
                }
                if (got > 0) {
                    outPos = 0;
                    outLen = got;
                    memberBytes += got;
                    return true;
                }
                if (inf.needsInput()) continue;      // 这一段的输入刚好用完，下一轮再读
                if (!inf.finished()) throw new IOException("gzip 解压卡死");
            }
        }

        /** 读下一段的 gzip 头；返回 false 表示后面没有数据了（正常结束） */
        private boolean readHeader() throws IOException {
            byte[] h = new byte[10];
            int n = readFullyOrEof(src, h, 10);
            if (n == 0) return false;
            if (n < 10) throw new IOException("gzip 头不完整");
            if ((h[0] & 0xff) != 0x1f || (h[1] & 0xff) != 0x8b || h[2] != 8) {
                throw new IOException("不是 gzip 数据");
            }
            int flg = h[3] & 0xff;
            if ((flg & 0x04) != 0) {                        // FEXTRA
                byte[] x = new byte[2];
                if (readFullyOrEof(src, x, 2) != 2) throw new IOException("gzip 头损坏");
                skipExactly(src, (x[0] & 0xff) | ((x[1] & 0xff) << 8));
            }
            if ((flg & 0x08) != 0) skipToZero(src);         // FNAME
            if ((flg & 0x10) != 0) skipToZero(src);         // FCOMMENT
            if ((flg & 0x02) != 0) skipExactly(src, 2);     // FHCRC
            return true;
        }
    }

    private static int readFullyOrEof(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static void skipExactly(InputStream in, int n) throws IOException {
        byte[] b = new byte[Math.min(Math.max(n, 1), 256)];
        int left = n;
        while (left > 0) {
            int r = in.read(b, 0, Math.min(left, b.length));
            if (r < 0) throw new IOException("gzip 头被截断");
            left -= r;
        }
    }

    private static void skipToZero(InputStream in) throws IOException {
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("gzip 头被截断");
            if (c == 0) return;
        }
    }
}
