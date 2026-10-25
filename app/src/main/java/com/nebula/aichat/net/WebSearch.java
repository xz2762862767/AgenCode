package com.nebula.aichat.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 联网搜索：抓取搜索引擎结果页并整理成可注入提示词的纯文本。
 *
 * 无需 API Key，支持两种引擎：
 *  - bing（默认，国内可直连）
 *  - ddg（DuckDuckGo html 版）
 */
public class WebSearch {

    public static final String ENGINE_BING = "bing";
    public static final String ENGINE_DDG = "ddg";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 结果块标记（按出现位置切段，避免 <li> 嵌套截断） */
    private static final Pattern BING_BLOCK_MARK = Pattern.compile("class=\"b_algo\"");
    /** 移动版结构：<div class="b_algoheader"><a href><h2>标题</h2></a> */
    private static final Pattern BING_HEAD = Pattern.compile(
            "<div class=\"b_algoheader\">\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    /** 桌面版结构兜底：<h2><a href>标题</a></h2> */
    private static final Pattern BING_TITLE =
            Pattern.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern BING_SNIPPET =
            Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    private static final Pattern DDG_TITLE =
            Pattern.compile("class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern DDG_SNIPPET =
            Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    public static class Item {
        public String title = "";
        public String url = "";
        public String snippet = "";
    }

    /** 搜索结果；失败或无结果返回 null */
    public static String search(String query, String engine) {
        if (query == null || query.trim().isEmpty()) return null;
        List<Item> items;
        try {
            items = ENGINE_DDG.equalsIgnoreCase(engine) ? ddg(query) : bing(query);
        } catch (Exception e) {
            return null;
        }
        if (items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Item it : items) {
            sb.append(i++).append(". ").append(it.title).append('\n');
            if (!it.snippet.isEmpty()) sb.append("   ").append(it.snippet).append('\n');
            if (!it.url.isEmpty()) sb.append("   来源: ").append(it.url).append('\n');
        }
        return sb.toString().trim();
    }

    private static List<Item> bing(String q) throws IOException {
        String url = "https://cn.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8")
                + "&ensearch=0&count=10";
        String html = httpGet(url);
        List<Item> out = new ArrayList<>();

        List<Integer> starts = new ArrayList<>();
        Matcher mark = BING_BLOCK_MARK.matcher(html);
        while (mark.find()) starts.add(mark.start());

        for (int i = 0; i < starts.size() && out.size() < 5; i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1)
                    : Math.min(html.length(), from + 8000);
            String block = html.substring(from, to);

            Item it = new Item();
            Matcher ht = BING_HEAD.matcher(block);
            if (ht.find()) {
                it.url = clean(ht.group(1));
                it.title = html2text(ht.group(2));
            }
            if (it.title.isEmpty()) {
                Matcher t2 = BING_TITLE.matcher(block);
                if (t2.find()) {
                    it.url = clean(t2.group(1));
                    it.title = html2text(t2.group(2));
                }
            }
            Matcher ms = BING_SNIPPET.matcher(block);
            if (ms.find()) it.snippet = html2text(ms.group(1));
            if (!it.title.isEmpty()) out.add(it);
        }
        return out;
    }

    private static List<Item> ddg(String q) throws IOException {
        String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8");
        String html = httpGet(url);
        List<Item> out = new ArrayList<>();
        Matcher mt = DDG_TITLE.matcher(html);
        Matcher ms = DDG_SNIPPET.matcher(html);
        while (mt.find() && out.size() < 5) {
            Item it = new Item();
            it.url = clean(mt.group(1));
            it.title = html2text(mt.group(2));
            if (ms.find()) it.snippet = html2text(ms.group(1));
            if (!it.title.isEmpty()) out.add(it);
        }
        return out;
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 3 * 1024 * 1024) break; // 结果页不可能更大
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.trim().replace("&amp;", "&");
    }

    /** 去标签 + 反转义 + 压空白 */
    private static String html2text(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&middot;", "·");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }
}
