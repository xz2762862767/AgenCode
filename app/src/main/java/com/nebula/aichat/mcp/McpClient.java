package com.nebula.aichat.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 极简 MCP 客户端：JSON-RPC 2.0 over HTTP（支持 application/json 与 text/event-stream 响应） */
public class McpClient {

    public static final String PROTOCOL_VERSION = "2024-11-05";

    private final String url;
    private final String token;
    private String sessionId;
    private int nextId = 1;
    private String serverInfo = "";
    private String serverVersion = "";

    public McpClient(String url, String token) {
        this.url = url == null ? "" : url.trim();
        this.token = token == null ? "" : token.trim();
    }

    public String getServerInfo() {
        return serverInfo;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    // ------------------------------------------------------------ 高层方法

    /** 建立会话，返回服务器名称 */
    public String initialize() throws Exception {
        JSONObject params = new JSONObject();
        params.put("protocolVersion", PROTOCOL_VERSION);
        JSONObject caps = new JSONObject();
        params.put("capabilities", caps);
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "NebulaAI");
        clientInfo.put("version", "1.1");
        params.put("clientInfo", clientInfo);

        JSONObject resp = rpc("initialize", params);
        JSONObject result = resultOf(resp);
        JSONObject info = result.optJSONObject("serverInfo");
        if (info != null) {
            serverInfo = info.optString("name", "");
            serverVersion = info.optString("version", "");
        }
        String pv = result.optString("protocolVersion", PROTOCOL_VERSION);

        // 通知服务器初始化完成（失败不致命）
        try {
            JSONObject n = new JSONObject();
            n.put("jsonrpc", "2.0");
            n.put("method", "notifications/initialized");
            post(n);
        } catch (Exception ignored) {
        }
        if (serverInfo.isEmpty()) serverInfo = "MCP 服务器";
        if (!serverVersion.isEmpty()) serverInfo += " " + serverVersion;
        return serverInfo + " · " + pv;
    }

    public JSONArray listTools() throws Exception {
        JSONObject resp = rpc("tools/list", new JSONObject());
        JSONObject result = resultOf(resp);
        JSONArray tools = result.optJSONArray("tools");
        return tools == null ? new JSONArray() : tools;
    }

    public JSONObject callTool(String name, JSONObject args) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", args == null ? new JSONObject() : args);
        JSONObject resp = rpc("tools/call", params);
        return resultOf(resp);
    }

    // ------------------------------------------------------------ 底层 RPC

    public JSONObject rpc(String method, JSONObject params) throws Exception {
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", nextId++);
        req.put("method", method);
        if (params != null) req.put("params", params);
        JSONObject resp = post(req);
        if (resp == null) throw new Exception("服务器无响应");
        JSONObject err = resp.optJSONObject("error");
        if (err != null) {
            throw new Exception("MCP 错误 " + err.optInt("code", -1) + ": "
                    + err.optString("message", ""));
        }
        return resp;
    }

    private static JSONObject resultOf(JSONObject resp) throws Exception {
        JSONObject result = resp.optJSONObject("result");
        if (result == null) throw new Exception("响应缺少 result 字段");
        return result;
    }

    private JSONObject post(JSONObject payload) throws Exception {
        if (url.isEmpty()) throw new Exception("地址为空");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (sessionId != null && !sessionId.isEmpty()) {
                conn.setRequestProperty("Mcp-Session-Id", sessionId);
            }
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            String sess = conn.getHeaderField("Mcp-Session-Id");
            if (sess != null && !sess.isEmpty()) sessionId = sess;

            int code = conn.getResponseCode();
            String ctype = conn.getContentType();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
            if (code >= 400) {
                throw new Exception("HTTP " + code + (text.isEmpty() ? "" : " · " + brief(text)));
            }
            if (text.trim().isEmpty()) return null; // 通知类请求无响应体

            if (ctype != null && ctype.toLowerCase().contains("event-stream")) {
                JSONObject fromSse = parseSse(text);
                if (fromSse != null) return fromSse;
                throw new Exception("SSE 响应中未找到 JSON-RPC 结果");
            }
            return new JSONObject(text);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 SSE 文本中提取第一个带 result/error 的 JSON-RPC 响应 */
    private static JSONObject parseSse(String text) {
        String[] lines = text.split("\n");
        StringBuilder data = new StringBuilder();
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.trim().isEmpty()) {
                JSONObject o = tryParse(data.toString());
                if (o != null) return o;
                data.setLength(0);
            }
        }
        return tryParse(data.toString());
    }

    private static JSONObject tryParse(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(s.trim());
            if (o.has("result") || o.has("error")) return o;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String brief(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** 把 tools/call 的 result 转成可读文本 */
    public static String contentToText(JSONObject result) {
        if (result == null) return "";
        JSONArray content = result.optJSONArray("content");
        if (content == null) return result.toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "");
            if ("text".equals(type)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(item.optString("text", ""));
            } else if ("resource".equals(type)) {
                JSONObject res = item.optJSONObject("resource");
                if (res != null) sb.append("\n[resource] ").append(res.optString("uri", ""));
            } else {
                if (sb.length() > 0) sb.append('\n');
                sb.append(item.toString());
            }
        }
        String out = sb.toString();
        boolean isError = result.optBoolean("isError", false);
        if (isError) out = "[工具返回错误] " + out;
        return out.isEmpty() ? result.toString() : out;
    }
}
