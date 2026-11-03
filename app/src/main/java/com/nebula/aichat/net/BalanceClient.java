package com.nebula.aichat.net;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.nebula.aichat.data.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * DeepSeek 账户余额查询（GET {origin}/user/balance）。
 *
 * 只有 DeepSeek 官方端点提供这个接口，所以先用 isSupported() 判断：
 * baseUrl 的 host 必须属于 deepseek.com，否则全程静默，绝不影响聊天主流程。
 *
 * 两个容易踩的点：
 * · 余额接口不在 /v1 下面 —— baseUrl 是 https://api.deepseek.com/v1 时，
 *   要取 scheme://host[:port] 再拼 /user/balance，不能直接 baseUrl + "/user/balance"；
 * · 任何异常都不向上抛，只在 Result 里留 ok=false，由 UI 决定是隐藏还是保留旧值。
 */
public class BalanceClient {

    /** 查询结果。失败时 ok=false，raw 里带原因（仅用于详情弹窗排查） */
    public static class Result {
        public boolean ok;
        public boolean available;
        /** 已带货币符号，如 ¥12.34 */
        public String total = "";
        public String topUp = "";
        public String granted = "";
        public String raw = "";
        public long at = 0L;
    }

    public interface Callback {
        void onResult(Result r);
    }

    /** 当前配置是否指向 DeepSeek 官方端点（只有它才有余额接口） */
    public static boolean isSupported(Settings s) {
        if (s == null) return false;
        if (TextUtils.isEmpty(s.apiKey) || TextUtils.isEmpty(s.apiKey.trim())) return false;
        if (TextUtils.isEmpty(s.baseUrl)) return false;
        String b = s.baseUrl.toLowerCase(Locale.ROOT);
        return b.contains("deepseek.com");
    }

    /** scheme://host[:port]，去掉 /v1 这类路径 */
    public static String origin(String baseUrl) {
        try {
            URL u = new URL(baseUrl.trim());
            int port = u.getPort();
            return u.getProtocol() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
        } catch (Throwable e) {
            return "https://api.deepseek.com";
        }
    }

    public static String endpoint(String baseUrl) {
        return origin(baseUrl) + "/user/balance";
    }

    public static void fetch(final Settings s, final Callback cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        final Result r = new Result();
        r.at = System.currentTimeMillis();
        if (!isSupported(s)) {
            h.post(() -> cb.onResult(r));
            return;
        }
        final String key = s.apiKey.trim();
        final String url = endpoint(s.baseUrl);
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setRequestProperty("Accept", "application/json");
                int code = conn.getResponseCode();
                InputStream in = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                String body = readAll(in);
                if (code != 200) {
                    r.raw = "HTTP " + code + " " + oneLine(body);
                } else {
                    JSONObject o = new JSONObject(body);
                    r.available = o.optBoolean("is_available", false);
                    JSONArray arr = o.optJSONArray("balance_infos");
                    if (arr != null && arr.length() > 0) {
                        JSONObject b = arr.optJSONObject(0);
                        if (b != null) {
                            String cur = b.optString("currency", "");
                            r.total = money(cur, b.optString("total_balance", ""));
                            r.topUp = money(cur, b.optString("topped_up_balance", ""));
                            r.granted = money(cur, b.optString("granted_balance", ""));
                        }
                    }
                    r.ok = true;
                }
            } catch (Throwable e) {
                r.raw = String.valueOf(e);
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
            h.post(() -> cb.onResult(r));
        }, "nebula-balance").start();
    }

    /** 把数值转成「¥12.34」这种紧凑展示，未知币种就直接前缀原币种码 */
    public static String money(String currency, String value) {
        if (value == null) value = "";
        value = value.trim();
        if (value.isEmpty()) return "";
        String c = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        String sym;
        if (c.equals("CNY") || c.equals("RMB")) {
            sym = "¥";
        } else if (c.equals("USD")) {
            sym = "$";
        } else {
            sym = c.isEmpty() ? "" : c + " ";
        }
        return sym + value;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
