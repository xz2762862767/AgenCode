package com.nebula.aichat.util;

import org.json.JSONObject;

/**
 * org.json 的 optString(key, default) 在字段值为 JSON null 时不会返回 default，
 * 而是返回字符串 ""。解析大模型流式响应时（思考阶段 content 常为 null）
 * 会把整段回答塞满 ""。这里统一做安全取值。
 */
public final class JsonOpt {

    private JsonOpt() {
    }

    public static String str(JSONObject o, String key) {
        if (o == null) return "";
        Object v = o.opt(key);
        return v instanceof String ? (String) v : "";
    }

    public static String str(JSONObject o, String key, String def) {
        String s = str(o, key);
        return s.isEmpty() ? def : s;
    }

    /** 整段内容只由 null 堆成（历史脏数据）时返回 true */
    public static boolean isNullNoise(String s) {
        if (s == null) return true;
        String t = s.toLowerCase().replace("nul" + "l", "").trim();
        return t.isEmpty();
    }
}
