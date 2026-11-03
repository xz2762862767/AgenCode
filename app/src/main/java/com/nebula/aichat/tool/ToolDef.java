package com.nebula.aichat.tool;

import org.json.JSONObject;

/** 一个可被模型调用的工具 */
public class ToolDef {

    public interface Executor {
        String run(JSONObject args) throws Exception;
    }

    public final String name;
    public final String description;
    public final JSONObject parameters;
    public final Executor executor;

    public ToolDef(String name, String description, JSONObject parameters, Executor executor) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.executor = executor;
    }

    /** OpenAI function calling 的单个工具描述 */
    public JSONObject toOpenAi() {
        JSONObject fn = new JSONObject();
        JSONObject o = new JSONObject();
        try {
            fn.put("name", name);
            fn.put("description", description);
            fn.put("parameters", parameters == null ? emptySchema() : parameters);
            o.put("type", "function");
            o.put("function", fn);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static JSONObject emptySchema() {
        JSONObject s = new JSONObject();
        try {
            s.put("type", "object");
            s.put("properties", new JSONObject());
        } catch (Exception ignored) {
        }
        return s;
    }

    /** 快速构造一个 object schema */
    public static JSONObject schema(JSONObject props, String... required) {
        JSONObject s = new JSONObject();
        try {
            s.put("type", "object");
            s.put("properties", props);
            if (required != null && required.length > 0) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String r : required) arr.put(r);
                s.put("required", arr);
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    /** 成对构造参数表：props("path", prop("string","…"), "name", prop("string","…")) */
    public static JSONObject props(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public static JSONObject prop(String type, String desc) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            o.put("description", desc);
        } catch (Exception ignored) {
        }
        return o;
    }
}
