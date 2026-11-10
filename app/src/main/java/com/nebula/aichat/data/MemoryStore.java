package com.nebula.aichat.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 长期记忆：跨会话记住「项目约定」和「用户偏好」。
 *
 * <p>要解决的具体问题是：用户每次开新会话都得把同样的话再说一遍——
 * 「这个项目用 Gradle 编译」「包名是 com.x.y」「我不喜欢长篇大论」。
 * 这些不属于当前这一轮对话，但决定了后面每一轮的判断；
 * 只存在历史消息里的话，一旦上下文被裁剪就丢了，模型又会问一遍。
 *
 * <p>存在 SharedPreferences 的一串 JSON 里，不做数据库：条目是几十条量级，
 * 全量读出再筛比 SQL 简单得多，也不会有升级迁移的麻烦。
 *
 * <p>淘汰策略：超过 {@link #MAX_ITEMS} 条时丢「最久没被用到」的那条。
 * 用「最近命中时间」而不是「创建时间」，是因为项目约定这类事实会被反复引用，
 * 按创建时间淘汰会把最有用、最老的那些先扔掉。
 */
public class MemoryStore {

    private static final String TAG = "MemoryStore";
    private static final String PREF = "nebula_memory";
    private static final String KEY_ITEMS = "items";
    /** 条目上限：再多也塞不进提示词，留 60 条是「够用且不至于让提示词爆炸」的平衡点 */
    private static final int MAX_ITEMS = 60;
    private static final int MAX_VALUE_LEN = 600;

    public static class Item {
        public String id;
        public String key;
        public String value;
        /** preference / project / rule / fact */
        public String kind = "fact";
        public long createdAt;
        public long usedAt;
        public int hits;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("key", key);
                o.put("value", value);
                o.put("kind", kind);
                o.put("createdAt", createdAt);
                o.put("usedAt", usedAt);
                o.put("hits", hits);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Item from(JSONObject o) {
            if (o == null) return null;
            Item it = new Item();
            it.id = o.optString("id", "");
            it.key = o.optString("key", "");
            it.value = o.optString("value", "");
            it.kind = o.optString("kind", "fact");
            it.createdAt = o.optLong("createdAt", 0L);
            it.usedAt = o.optLong("usedAt", it.createdAt);
            it.hits = o.optInt("hits", 0);
            return it.key.isEmpty() ? null : it;
        }

        /** 给人看的一行 */
        public String line() {
            return "[" + kind + "] " + key + "：" + value;
        }
    }

    private MemoryStore() {
    }

    /** 全局 Context：ChatClient 在非 UI 线程拼提示词时也要读记忆，那里拿不到 Activity */
    private static Context APP;

    public static void attach(Context c) {
        if (c != null) APP = c.getApplicationContext();
    }

    private static SharedPreferences sp(Context c) {
        Context use = c == null ? APP : c;
        if (use == null) return null;
        return use.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static synchronized List<Item> all(Context c) {
        List<Item> out = new ArrayList<Item>();
        try {
            SharedPreferences p = sp(c);
            if (p == null) return out;
            String raw = p.getString(KEY_ITEMS, "");
            if (raw == null || raw.trim().isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Item it = Item.from(arr.optJSONObject(i));
                if (it != null) out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "读记忆失败", e);
        }
        return out;
    }

    private static synchronized void persist(Context c, List<Item> items) {
        SharedPreferences p = sp(c);
        if (p == null) return;
        JSONArray arr = new JSONArray();
        for (Item it : items) arr.put(it.toJson());
        p.edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    /**
     * 存一条。同 key 直接覆盖——记忆是「当前事实」，不是流水账，
     * 同一个 key 留两个互相矛盾的版本，模型只会更糊涂。
     */
    public static synchronized Item save(Context c, String key, String value, String kind) {
        if (key == null || key.trim().isEmpty()) return null;
        if (value == null || value.trim().isEmpty()) return null;
        String k = key.trim();
        String v = value.trim();
        if (v.length() > MAX_VALUE_LEN) v = v.substring(0, MAX_VALUE_LEN);

        List<Item> items = all(c);
        long now = System.currentTimeMillis();
        Item hit = null;
        for (Item it : items) {
            if (k.equalsIgnoreCase(it.key)) {
                hit = it;
                break;
            }
        }
        if (hit == null) {
            hit = new Item();
            hit.id = "m" + now;
            hit.key = k;
            hit.createdAt = now;
            items.add(hit);
        }
        hit.value = v;
        if (kind != null && !kind.trim().isEmpty()) hit.kind = kind.trim();
        hit.usedAt = now;
        hit.hits++;

        if (items.size() > MAX_ITEMS) {
            Collections.sort(items, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return Long.compare(a.usedAt, b.usedAt);
                }
            });
            while (items.size() > MAX_ITEMS) items.remove(0);
        }
        persist(c, items);
        return hit;
    }

    public static synchronized boolean delete(Context c, String keyOrId) {
        if (keyOrId == null || keyOrId.trim().isEmpty()) return false;
        String q = keyOrId.trim();
        List<Item> items = all(c);
        boolean removed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (q.equalsIgnoreCase(it.key) || q.equals(it.id)) {
                items.remove(i);
                removed = true;
            }
        }
        if (removed) persist(c, items);
        return removed;
    }

    /**
     * 按关键词找。除了字面包含，还按字符命中数排序——
     * 中文没有空格分词，"编译慢" 这种查询靠整串匹配基本查不到东西。
     */
    public static synchronized List<Item> query(Context c, String q, int limit) {
        List<Item> items = all(c);
        if (q == null || q.trim().isEmpty()) {
            Collections.sort(items, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return Long.compare(b.usedAt, a.usedAt);
                }
            });
            if (items.size() > limit) return new ArrayList<Item>(items.subList(0, limit));
            return items;
        }
        final String needle = q.trim().toLowerCase(java.util.Locale.US);
        List<Item> scored = new ArrayList<Item>();
        final java.util.Map<Item, Integer> score = new java.util.HashMap<Item, Integer>();
        for (Item it : items) {
            String hay = (it.key + " " + it.value + " " + it.kind).toLowerCase(java.util.Locale.US);
            int s = 0;
            if (hay.contains(needle)) s += 100;
            for (int i = 0; i < needle.length(); i++) {
                if (hay.indexOf(needle.charAt(i)) >= 0) s++;
            }
            if (s > 0) {
                scored.add(it);
                score.put(it, s);
            }
        }
        Collections.sort(scored, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                int sa = score.containsKey(a) ? score.get(a) : 0;
                int sb = score.containsKey(b) ? score.get(b) : 0;
                if (sa != sb) return sb - sa;
                return Long.compare(b.usedAt, a.usedAt);
            }
        });
        if (scored.size() > limit) scored = new ArrayList<Item>(scored.subList(0, limit));
        return scored;
    }

    /** 标记「被用过了」，淘汰时优先保住这些 */
    public static synchronized void touch(Context c, List<Item> used) {
        if (used == null || used.isEmpty()) return;
        List<Item> items = all(c);
        long now = System.currentTimeMillis();
        for (Item u : used) {
            for (Item it : items) {
                if (it.id != null && it.id.equals(u.id)) {
                    it.usedAt = now;
                    it.hits++;
                    break;
                }
            }
        }
        persist(c, items);
    }

    /** 注入提示词用：按最近使用取前 n 条，压成一段文本 */
    public static String promptBlock(Context c, int n) {
        List<Item> items = query(c, "", n);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            sb.append("· ").append(it.key).append("：").append(it.value).append('\n');
        }
        return sb.toString().trim();
    }
}
