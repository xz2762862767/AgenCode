package com.nebula.aichat.net;

import android.os.Handler;
import android.os.Looper;

import com.nebula.aichat.data.Settings;

import org.json.JSONArray;
import com.nebula.aichat.util.JsonOpt;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过 GET {baseUrl}/models 拉取当前账号可用的模型列表（OpenAI 兼容接口）。
 * 支持 DeepSeek / OpenAI / 各类兼容网关返回的 {"data":[{"id":"..."}]} 或 {"models":[...]} 结构。
 */
public class ModelListClient {

    public interface Callback {
        void onSuccess(List<String> models);

        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile HttpURLConnection conn;
    private volatile boolean cancelled = false;

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = conn;
        conn = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public void fetch(final Settings settings, final Callback callback) {
        cancelled = false;
        Thread t = new Thread(() -> {
            try {
                final List<String> models = doFetch(settings);
                if (cancelled) return;
                main.post(() -> {
                    if (!cancelled) callback.onSuccess(models);
                });
            } catch (final Exception e) {
                if (cancelled) return;
                final String msg = friendly(e);
                main.post(() -> {
                    if (!cancelled) callback.onError(msg);
                });
            }
        }, "model-list");
        t.setDaemon(true);
        t.start();
    }

    private List<String> doFetch(Settings s) throws Exception {
        String base = s.baseUrl == null ? "" : s.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = Settings.DEFAULT_BASE_URL;
        }
        String url = base + "/models";

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        conn = c;
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + (s.apiKey == null ? "" : s.apiKey.trim()));
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "NebulaAI/1.0");

        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String body = readAll(in);
        c.disconnect();
        conn = null;

        if (code < 200 || code >= 300) {
            String detail = extractError(body);
            throw new RuntimeException("HTTP " + code + (detail.isEmpty() ? "" : " · " + detail));
        }
        return parse(body);
    }

    private List<String> parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("data");
        if (arr == null) arr = root.optJSONArray("models");
        if (arr == null) {
            throw new RuntimeException("返回内容里没有模型列表字段");
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = JsonOpt.str(o, "id");
            if (id.isEmpty()) id = JsonOpt.str(o, "name");
            id = id.trim();
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        if (out.isEmpty()) {
            throw new RuntimeException("模型列表为空");
        }
        return out;
    }

    private String extractError(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject err = root.optJSONObject("error");
            if (err != null) {
                String m = JsonOpt.str(err, "message");
                if (!m.isEmpty()) return m;
            }
            String m = JsonOpt.str(root, "message");
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) {
        }
        String t = body.trim();
        if (t.length() > 120) t = t.substring(0, 120) + "…";
        return t;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }

    private String friendly(Exception e) {
        if (e instanceof UnknownHostException) {
            return "无法解析域名，请检查网络或接口地址";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接超时，请检查网络后重试";
        }
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
