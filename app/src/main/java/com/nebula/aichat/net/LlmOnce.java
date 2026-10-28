package com.nebula.aichat.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 一次性的非流式问答。
 *
 * <p>给"只要最终结果"的内部功能用（网页翻译、短文本处理），不复用 {@link ChatClient}：
 * 那边是整个对话引擎，带工具调用、流式解析、上下文裁剪，为了翻译一段文字把它请出来
 * 属于用牛刀砍柴，而且流式回调在主线程，翻译这种批量任务反而更麻烦。
 *
 * <p>调用方必须在工作线程里调 —— 这个方法同步阻塞。
 */
public final class LlmOnce {

    private LlmOnce() {
    }

    /**
     * @param system   系统指令
     * @param user     用户内容
     * @param maxTokens 期望的最大输出 token
     * @return 模型回复正文（已去首尾空白），失败抛异常
     */
    public static String ask(com.nebula.aichat.data.Settings s, String system, String user,
                             int maxTokens) throws Exception {
        if (s == null || s.apiKey == null || s.apiKey.trim().isEmpty()) {
            throw new IllegalStateException("未配置 API Key");
        }

        JSONObject body = new JSONObject();
        body.put("model", com.nebula.aichat.data.Settings.effectiveModel(s));
        body.put("max_tokens", Math.max(256, maxTokens));
        // 翻译要的是稳定，不是创意
        body.put("temperature", 0.2);
        body.put("stream", false);
        JSONArray msgs = new JSONArray();
        if (system != null && !system.trim().isEmpty()) {
            msgs.put(new JSONObject().put("role", "system").put("content", system));
        }
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(s.endpoint()).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Authorization", "Bearer " + s.apiKey.trim());
            c.setRequestProperty("User-Agent", "AgenCode/1.0 (Android)");
            c.getOutputStream().write(payload);

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = decode(readAll(in));
            if (code >= 400) {
                String tip = text;
                try {
                    JSONObject err = new JSONObject(text);
                    JSONObject e = err.optJSONObject("error");
                    if (e != null && e.optString("message") != null) tip = e.optString("message");
                } catch (Throwable ignored) {
                }
                throw new IllegalStateException("HTTP " + code + "：" + clip(tip, 200));
            }
            JSONObject root = new JSONObject(text);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new IllegalStateException("模型没有返回内容");
            }
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            String content = msg == null ? "" : msg.optString("content", "");
            return content == null ? "" : content.trim();
        } finally {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * 字节 → 文本，顺带处理压缩。
     * 就算请求里写了 identity，有些网关照样 gzip（省流量是硬指标），
     * 所以这里按魔数判断而不是按响应头判断 —— 响应头偶尔不给。
     */
    private static String decode(byte[] raw) throws Exception {
        if (raw == null || raw.length == 0) return "";
        InputStream in = new java.io.ByteArrayInputStream(raw);
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            in = new GZIPInputStream(in);
        } else if (raw.length > 2 && (raw[0] & 0xFF) == 0x78) {
            in = new InflaterInputStream(in, new Inflater());
        }
        return new String(readAll(in), StandardCharsets.UTF_8);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
