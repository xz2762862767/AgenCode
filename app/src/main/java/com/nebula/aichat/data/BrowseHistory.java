package com.nebula.aichat.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器历史记录：SharedPreferences + JSON，最多留 MAX 条。
 * 同一个 URL 再次访问会被提到最前面，不重复占位。
 */
public final class BrowseHistory {

    private static final String SP = "nebula_browse_history";
    private static final String KEY = "items";
    private static final int MAX = 300;

    public static final class Item {
        public final String url;
        public final String title;
        public final long ts;

        Item(String url, String title, long ts) {
            this.url = url;
            this.title = title;
            this.ts = ts;
        }

        /** 列表里显示的名字：优先标题，退回域名 */
        public String label() {
            if (title != null && !title.trim().isEmpty()) return title.trim();
            try {
                String h = android.net.Uri.parse(url).getHost();
                if (h != null && !h.isEmpty()) return h;
            } catch (Throwable ignored) {
            }
            return url;
        }

        public String host() {
            try {
                String h = android.net.Uri.parse(url).getHost();
                return h == null ? "" : h;
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    private BrowseHistory() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static synchronized void add(Context c, String url, String title) {
        if (url == null || url.isEmpty()) return;
        if (url.startsWith("about:") || url.startsWith("data:")
                || url.startsWith("javascript:") || url.startsWith("blob:")) {
            return;
        }
        try {
            List<Item> list = all(c);
            for (int i = 0; i < list.size(); i++) {
                if (url.equals(list.get(i).url)) {
                    list.remove(i);
                    break;
                }
            }
            list.add(0, new Item(url, title == null ? "" : title, System.currentTimeMillis()));
            while (list.size() > MAX) list.remove(list.size() - 1);
            JSONArray arr = new JSONArray();
            for (Item it : list) {
                JSONObject o = new JSONObject();
                o.put("u", it.url);
                o.put("t", it.title);
                o.put("ts", it.ts);
                arr.put(o);
            }
            sp(c).edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            String s = sp(c).getString(KEY, "");
            if (s == null || s.isEmpty()) return out;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("u", "");
                if (u.isEmpty()) continue;
                out.add(new Item(u, o.optString("t", ""), o.optLong("ts", 0)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static synchronized void clear(Context c) {
        try {
            sp(c).edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }
}
