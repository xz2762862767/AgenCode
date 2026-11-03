package com.nebula.aichat.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** MCP 服务器配置存储（SharedPreferences 里的 JSON 数组） */
public class McpConfig {

    private static final String PREF = "nebula_mcp";
    private static final String KEY = "servers";

    public static class Server {
        public String id = "";
        public String name = "";
        public String url = "";
        public String token = "";
        public boolean enabled = true;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("url", url);
                o.put("token", token);
                o.put("enabled", enabled);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Server fromJson(JSONObject o) {
            Server s = new Server();
            s.id = o.optString("id", "");
            s.name = o.optString("name", "");
            s.url = o.optString("url", "");
            s.token = o.optString("token", "");
            s.enabled = o.optBoolean("enabled", true);
            if (s.name.isEmpty()) s.name = s.url;
            return s;
        }

        public String label() {
            return name == null || name.isEmpty() ? url : name;
        }
    }

    public static List<Server> load(Context c) {
        List<Server> list = new ArrayList<>();
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String s = sp.getString(KEY, "");
            if (s == null || s.trim().isEmpty()) return list;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(Server.fromJson(o));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void save(Context c, List<Server> list) {
        JSONArray arr = new JSONArray();
        for (Server s : list) arr.put(s.toJson());
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}
