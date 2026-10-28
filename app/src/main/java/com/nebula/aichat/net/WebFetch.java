package com.nebula.aichat.net;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 「浏览器自动化」的实际执行层：真正打开一个网页，把 HTML 变成模型能读的纯文本。
 *
 * 定位：联网搜索只给摘要（每条两三行），说到具体细节就不够用了。
 * 这个能力让模型自己决定「打开哪一页、读全文」，可以连续开多页，
 * 相当于一个无界面的浏览器：取正文 / 取链接列表 / 判断是否 404。
 *
 * 不执行 JS，不点按钮——需要交互的站点（登录墙、无限滚动）请让用户手动操作。
 */
public class WebFetch {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;
    /** 原始响应上限：再大的页面读进来也是浪费上下文 */
    private static final int MAX_BYTES = 3 * 1024 * 1024;

    private static final Pattern P_TITLE =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern P_META_CHARSET = Pattern.compile(
            "<meta[^>]+charset=[\"']?([a-zA-Z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern P_SCRIPT =
            Pattern.compile("<script\\b[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern P_STYLE =
            Pattern.compile("<style\\b[^>]*>.*?</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern P_NOSCRIPT =
            Pattern.compile("<noscript\\b[^>]*>.*?</noscript>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SVG =
            Pattern.compile("<svg\\b[^>]*>.*?</svg>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern P_BREAK = Pattern.compile(
            "</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|blockquote|pre)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern P_BLANK_LINES = Pattern.compile("[ \\t]*\\n{2,}[ \\t]*");
    private static final Pattern P_SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern P_LINK =
            Pattern.compile("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** 抓取结果：正文或链接列表，已经是可直接塞进对话的文本 */
    public static String fetch(String rawUrl, int maxChars, boolean linksOnly) {
        String url = normalize(rawUrl);
        if (url == null) return "地址无效：请给出完整链接，例如 https://example.com/page";
        if (maxChars <= 0) maxChars = 20000;
        if (maxChars > 80000) maxChars = 80000;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "gzip");

            int code = conn.getResponseCode();
            String ctype = conn.getContentType();
            java.io.InputStream in;
            if (code >= 400) {
                in = conn.getErrorStream();
            } else {
                in = conn.getInputStream();
            }
            if (in == null) return "打开失败：HTTP " + code + "（服务器没有返回内容）";

            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip")) {
                try {
                    in = new GZIPInputStream(in);
                } catch (Exception ignored) {
                }
            }
            byte[] data = read(in, MAX_BYTES);
            in.close();

            // 有 gzip 头丢失的情况，靠魔数兜底
            if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
                try {
                    data = read(new GZIPInputStream(new java.io.ByteArrayInputStream(data)), MAX_BYTES);
                } catch (Exception ignored) {
                }
            }
            String body = new String(data, charsetOf(ctype, data));

            if (code >= 400) {
                String brief = body.length() > 1500 ? body.substring(0, 1500) : body;
                return "打开失败：HTTP " + code + "\n（页面返回内容片段，可用来判断是 404 还是拦截）\n"
                        + squeeze(brief);
            }

            boolean html = ctype == null
                    || ctype.toLowerCase(Locale.ROOT).contains("html")
                    || body.trim().startsWith("<");
            if (!html) {
                // JSON / 纯文本接口直接给原文
                String t = body.trim();
                if (t.length() > maxChars) t = t.substring(0, maxChars) + "\n…（已截断，原文 " + body.length() + " 字）";
                return "【类型】非 HTML（" + (ctype == null ? "未知" : ctype) + "）\n【正文】\n" + t;
            }

            String title = unescape(firstGroup(P_TITLE, body));
            StringBuilder out = new StringBuilder();
            out.append("【标题】").append(title.isEmpty() ? "（无标题）" : title).append('\n');
            out.append("【链接】").append(url).append('\n');

            if (linksOnly) {
                out.append("【页面链接】\n").append(extractLinks(body, url, maxChars));
            } else {
                String text = htmlToText(body);
                if (text.length() > maxChars) {
                    text = text.substring(0, maxChars) + "\n…（已截断，正文约 " + text.length() + " 字；"
                            + "需要更精准的内容可以对更具体的页面再取一次）";
                }
                out.append("【正文】\n").append(text);
            }
            return out.toString();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "打开失败：" + msg + "\n（可能原因：网络不通、域名解析失败、站点需要登录或有反爬拦截。"
                    + "换一个来源再试，或让用户手动打开这一页）\n【链接】" + url;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------- 内部实现 ----------------

    private static String normalize(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.isEmpty()) return null;
        if (u.startsWith("//")) u = "https:" + u;
        if (!u.matches("(?i)^https?://.*")) {
            if (u.matches("^[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+$")) u = "https://" + u;
            else return null;
        }
        return u;
    }

    private static byte[] read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            int take = Math.min(n, limit - total);
            if (take <= 0) break;
            bos.write(buf, 0, take);
            total += take;
            if (total >= limit) break;
        }
        return bos.toByteArray();
    }

    private static java.nio.charset.Charset charsetOf(String ctype, byte[] data) {
        String name = null;
        if (ctype != null) {
            int i = ctype.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                name = ctype.substring(i + 8).split("[;\\s]")[0].replace("\"", "").trim();
            }
        }
        if (name == null) {
            String head = new String(data, 0, Math.min(data.length, 4096),
                    java.nio.charset.Charset.forName("ISO-8859-1"));
            Matcher m = P_META_CHARSET.matcher(head);
            if (m.find()) name = m.group(1);
        }
        try {
            if (name != null && !name.isEmpty()) return java.nio.charset.Charset.forName(name);
        } catch (Exception ignored) {
        }
        return java.nio.charset.Charset.forName("UTF-8");
    }

    private static String htmlToText(String html) {
        String h = P_COMMENT.matcher(html).replaceAll(" ");
        h = P_SCRIPT.matcher(h).replaceAll(" ");
        h = P_STYLE.matcher(h).replaceAll(" ");
        h = P_NOSCRIPT.matcher(h).replaceAll(" ");
        h = P_SVG.matcher(h).replaceAll(" ");
        h = P_BREAK.matcher(h).replaceAll("\n");
        h = P_TAG.matcher(h).replaceAll(" ");
        h = unescape(h);
        h = P_SPACES.matcher(h).replaceAll(" ");
        h = P_BLANK_LINES.matcher(h).replaceAll("\n\n");
        return h.trim();
    }

    private static String extractLinks(String html, String base, int maxChars) {
        StringBuilder sb = new StringBuilder();
        Matcher m = P_LINK.matcher(html);
        int count = 0;
        while (m.find() && sb.length() < maxChars) {
            String href = m.group(1).trim();
            String label = unescape(P_TAG.matcher(m.group(2)).replaceAll(" ")).replaceAll("\\s+", " ").trim();
            if (href.isEmpty() || href.startsWith("#") || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                continue;
            }
            String abs = absolutize(base, href);
            if (abs == null) continue;
            if (label.isEmpty()) label = "(无文字)";
            if (label.length() > 120) label = label.substring(0, 120) + "…";
            sb.append("- ").append(label).append("  ").append(abs).append('\n');
            if (++count >= 120) break;
        }
        return sb.length() == 0 ? "（这一页没有可提取的链接）" : sb.toString();
    }

    private static String absolutize(String base, String href) {
        try {
            URL b = new URL(base);
            if (href.startsWith("//")) return b.getProtocol() + ":" + href;
            URL r = new URL(b, href);
            String p = r.getProtocol();
            if (!"http".equals(p) && !"https".equals(p)) return null;
            return r.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String squeeze(String s) {
        return P_BLANK_LINES.matcher(P_SPACES.matcher(s).replaceAll(" ")).replaceAll("\n\n").trim();
    }

    private static String unescape(String s) {
        if (s == null || s.isEmpty()) return "";
        String r = s;
        r = r.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
                .replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&middot;", "·");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(r);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
