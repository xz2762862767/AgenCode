package com.nebula.aichat.data;

import org.json.JSONArray;
import org.json.JSONException;
import com.nebula.aichat.util.JsonOpt;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 一个会话 */
public class Conversation {
    private static final AtomicLong SEQ = new AtomicLong(0);

    public String id;
    public String title;
    public long updatedAt;
    public final List<Message> messages = new ArrayList<>();

    public Conversation() {
        this.id = newId();
        this.title = "新对话";
        this.updatedAt = System.currentTimeMillis();
    }

    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + "-" + SEQ.incrementAndGet()
                + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    public void touch() {
        updatedAt = System.currentTimeMillis();
    }

    /** 根据首条用户消息自动生成标题 */
    public void autoTitle() {
        for (Message m : messages) {
            if (m.isUser() && m.content != null && !m.content.trim().isEmpty()) {
                String t = cleanTitle(m.content);
                if (!t.isEmpty()) {
                    title = t.length() > 22 ? t.substring(0, 22) + "…" : t;
                }
                return;
            }
        }
    }

    /**
     * 标题清洗：消息正文里带联网标记（\uFFFCnet）、附件行图标，
     * 直接用会显示成方框 + “net”，所以标题只保留人能读的文字。
     */
    public static String cleanTitle(String raw) {
        if (raw == null) return "";
        String t = raw.replace("\uFFFCnet", " ").replace("\uFFFC", " ").replace("\u200b", "");
        StringBuilder sb = new StringBuilder();
        for (String line : t.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (l.equalsIgnoreCase("net")) continue;
            if (l.startsWith("📄") || l.startsWith("🖼")) {
                // 附件行：只留文件名，图标不进标题
                String name = l.substring(2).trim();
                if (!name.isEmpty()) sb.append(name).append(' ');
                continue;
            }
            sb.append(l).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    public int messageCount() {
        return messages.size();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title);
            o.put("updatedAt", updatedAt);
            JSONArray arr = new JSONArray();
            for (Message m : messages) arr.put(m.toJson());
            o.put("messages", arr);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Conversation fromJson(JSONObject o) {
        Conversation c = new Conversation();
        c.id = JsonOpt.str(o, "id", c.id);
        c.title = JsonOpt.str(o, "title", "新对话");
        c.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
        JSONArray arr = o.optJSONArray("messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject mo = arr.optJSONObject(i);
                if (mo != null) c.messages.add(Message.fromJson(mo));
            }
        }
        return c;
    }
}
