package com.nebula.aichat.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * 限长逐行读取（替代 BufferedReader.readLine）。
 *
 * 为什么不能用 readLine：它为了找到换行符必须把整行读进内存，而 shell 命令
 * 完全可以输出没有换行符的超大内容 ——
 *   base64 /sdcard/xxx.apk、dd 打到 stdout、cat 二进制文件、grep 超长单行日志。
 * 这时 readLine 会瞬间分配一个几百 MB 的 String，直接触发 OutOfMemoryError，
 * 表现就是"执行某个命令 App 直接闪退"，事后看堆栈只有一行 OutOfMemoryError，
 * 根本判断不出是哪个命令干的。
 *
 * 这里自己按字符流切行：单行超过 maxLineChars 后，本行剩余字符只计数不保存，
 * 因此内存占用只与 maxLineChars 有关，与命令输出总量无关。
 */
public final class LineReader implements Closeable {

    private final Reader in;
    private final int maxLineChars;
    private final char[] buf = new char[8192];
    private int pos = 0;
    private int len = 0;
    private final StringBuilder cur = new StringBuilder(256);
    private boolean eof = false;

    public LineReader(InputStream is, int maxLineChars) {
        this.in = new InputStreamReader(is, StandardCharsets.UTF_8);
        this.maxLineChars = maxLineChars <= 0 ? 8192 : maxLineChars;
    }

    /** 读下一行（不含换行符）；流结束返回 null */
    public String next() throws IOException {
        cur.setLength(0);
        boolean any = false;
        long dropped = 0;
        while (true) {
            if (pos >= len) {
                len = in.read(buf, 0, buf.length);
                pos = 0;
                if (len < 0) {
                    eof = true;
                    break;
                }
                if (len == 0) continue;   // 理论上不会发生，防死循环
            }
            char c = buf[pos++];
            any = true;
            if (c == '\n') break;
            if (c == '\r') continue;
            if (cur.length() < maxLineChars) cur.append(c);
            else dropped++;
        }
        if (!any && eof) return null;
        if (dropped > 0) {
            cur.append(" …（本行过长，已丢弃 ").append(dropped).append(" 字符）");
        }
        return cur.toString();
    }

    @Override
    public void close() throws IOException {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
