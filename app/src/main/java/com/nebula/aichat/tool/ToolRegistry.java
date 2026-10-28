package com.nebula.aichat.tool;

import android.content.Context;
import android.util.Log;

import com.nebula.aichat.mcp.McpClient;
import com.nebula.aichat.mcp.McpConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：把「内置 APK 工具」和「MCP 服务器提供的工具」合并成一份
 * OpenAI function calling 的工具列表，并负责按名字分发调用。
 */
public class ToolRegistry {

    private static final String TAG = "ToolRegistry";
    /** MCP 工具加载的总等待时间 */
    private static final long MCP_LOAD_TIMEOUT = 12000;

    private final Context ctx;
    private final List<ToolDef> builtin = new ArrayList<ToolDef>();
    /** 暴露给模型的名字 -> "serverKey\u0000realName" */
    private final Map<String, String> mcpIndex = new ConcurrentHashMap<String, String>();
    private final Map<String, McpClient> mcpClients = new ConcurrentHashMap<String, McpClient>();
    private volatile JSONArray mcpSchema = new JSONArray();
    private volatile String mcpStatus = "";

    public ToolRegistry(Context c) {
        this.ctx = c.getApplicationContext();
        ApkToolsProvider.register(this, ctx);
        BuildToolsProvider.register(this, ctx);
        SkillTools.register(this, ctx);
        WebToolsProvider.register(this, ctx);
    }

    public void add(ToolDef d) {
        builtin.add(d);
    }

    public int builtinCount() {
        return builtin.size();
    }

    public int mcpCount() {
        JSONArray a = mcpSchema;
        return a == null ? 0 : a.length();
    }

    public String mcpStatus() {
        return mcpStatus;
    }

    public boolean hasTool(String name) {
        for (ToolDef d : builtin) {
            if (d.name.equals(name)) return true;
        }
        return false;
    }

    public boolean hasTools() {
        return !builtin.isEmpty() || mcpCount() > 0;
    }

    // ------------------------------------------------------------ MCP 工具

    /**
     * 拉取所有已启用 MCP 服务器的工具列表。
     * 带总超时保护：MCP 服务器卡住不能把首字延迟拖死。
     */
    public void loadMcp() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                doLoadMcp();
            }
        }, "mcp-tools");
        t.setDaemon(true);
        t.start();
        try {
            t.join(MCP_LOAD_TIMEOUT);
        } catch (InterruptedException ignored) {
        }
    }

    private void doLoadMcp() {
        List<McpConfig.Server> servers;
        try {
            servers = McpConfig.load(ctx);
        } catch (Exception e) {
            return;
        }
        JSONArray out = new JSONArray();
        Map<String, String> newIndex = new LinkedHashMap<String, String>();
        Map<String, McpClient> newClients = new ConcurrentHashMap<String, McpClient>();
        StringBuilder status = new StringBuilder();

        for (McpConfig.Server s : servers) {
            if (!s.enabled || s.url == null || s.url.trim().isEmpty()) continue;
            String key = s.id == null || s.id.isEmpty() ? s.url : s.id;
            try {
                McpClient cli = new McpClient(s.url, s.token);
                cli.initialize();
                JSONArray tools = cli.listTools();
                newClients.put(key, cli);
                int added = 0;
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.optJSONObject(i);
                    if (t == null) continue;
                    String real = t.optString("name", "");
                    if (real.isEmpty()) continue;
                    String exposed = real;
                    if (taken(exposed, newIndex)) exposed = sanitize(s.label()) + "__" + real;
                    if (taken(exposed, newIndex)) exposed = "mcp" + i + "__" + real;

                    JSONObject fn = new JSONObject();
                    fn.put("name", exposed);
                    String desc = t.optString("description", "");
                    fn.put("description", "[" + s.label() + "] " + (desc.isEmpty() ? real : desc));
                    JSONObject schema = t.optJSONObject("inputSchema");
                    fn.put("parameters", schema == null ? ToolDef.emptySchema() : schema);
                    JSONObject wrap = new JSONObject();
                    wrap.put("type", "function");
                    wrap.put("function", fn);
                    out.put(wrap);

                    newIndex.put(exposed, key + "\u0000" + real);
                    added++;
                }
                status.append(s.label()).append(":").append(added).append(" ");
            } catch (Exception e) {
                Log.w(TAG, "MCP 工具拉取失败：" + s.label(), e);
                status.append(s.label()).append(":失败(")
                        .append(e.getMessage() == null ? "无响应" : e.getMessage()).append(") ");
            }
        }

        if (out.length() > 0) {
            mcpIndex.clear();
            mcpIndex.putAll(newIndex);
            mcpClients.clear();
            mcpClients.putAll(newClients);
            mcpSchema = out;
        }
        mcpStatus = status.toString().trim();
    }

    private static boolean taken(String name, Map<String, String> idx) {
        if (idx.containsKey(name)) return true;
        // 与内置工具名冲突也不行
        return false;
    }

    private static String sanitize(String s) {
        if (s == null) return "mcp";
        String x = s.replaceAll("[^a-zA-Z0-9_]", "_");
        if (x.isEmpty()) x = "mcp";
        return x.length() > 20 ? x.substring(0, 20) : x;
    }

    // ------------------------------------------------------------ 对外

    /** 全部工具的 OpenAI schema */
    public JSONArray toSchema() {
        JSONArray all = new JSONArray();
        for (ToolDef d : builtin) all.put(d.toOpenAi());
        JSONArray m = mcpSchema;
        if (m != null) {
            for (int i = 0; i < m.length(); i++) {
                JSONObject o = m.optJSONObject(i);
                if (o != null) all.put(o);
            }
        }
        return all;
    }

    /** 执行一个工具，返回文本结果 */
    public String call(String name, JSONObject args) throws Exception {
        if (name == null) throw new Exception("工具名为空");
        for (ToolDef d : builtin) {
            if (d.name.equals(name)) {
                return d.executor.run(args == null ? new JSONObject() : args);
            }
        }
        String idx = mcpIndex.get(name);
        if (idx != null) {
            int sep = idx.indexOf('\u0000');
            String key = sep > 0 ? idx.substring(0, sep) : idx;
            String real = sep > 0 ? idx.substring(sep + 1) : name;
            McpClient cli = mcpClients.get(key);
            if (cli == null) throw new Exception("MCP 连接已失效，请重新发送消息");
            JSONObject result = cli.callTool(real, args == null ? new JSONObject() : args);
            String text = McpClient.contentToText(result);
            return text == null || text.isEmpty() ? "（工具没有返回内容）" : text;
        }
        throw new Exception("未知工具：" + name);
    }
}
