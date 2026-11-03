package com.nebula.aichat.data;

import org.json.JSONException;
import com.nebula.aichat.util.JsonOpt;

import org.json.JSONArray;
import org.json.JSONObject;

/** 一条消息 */
public class Message {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    public String role;
    public String content;
    public long time;
    /** 是否为失败消息（仅 UI 用，不参与持久化语义） */
    public boolean error;
    /** 用户反馈：1 = 有帮助，-1 = 需改进，0 = 未标记 */
    public int rating;

    /**
     * 本条回答给出的执行步骤（任务清单）。
     * 模型按约定用「- [ ] 步骤」输出，解析后单独渲染成可勾选的卡片，
     * 正文里的清单行会被剥掉，避免同一份步骤出现两遍。
     */
    public java.util.List<String> tasks;
    /** 与 tasks 一一对应的完成状态；长度始终与 tasks 对齐（缺省视为未完成） */
    public java.util.List<Boolean> taskDone;

    /**
     * 结论卡：每轮回答结尾那块「结论 + 详述」。
     * 正文里的 %%CONCLUSION%% 协议块在收尾时被剥离，正文存 content，
     * 结论两段单独存这里，历史回放时直接渲染成卡片，不必再解析。
     */
    public String conclusionTitle;
    public String conclusionDetail;

    /** 已完成的步骤数 */
    public int taskDoneCount() {
        if (tasks == null || tasks.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < tasks.size(); i++) {
            if (taskDone != null && i < taskDone.size() && Boolean.TRUE.equals(taskDone.get(i))) n++;
        }
        return n;
    }

    /** 切换某一步的完成状态，返回切换后的值 */
    public boolean toggleTask(int index) {
        if (tasks == null || index < 0 || index >= tasks.size()) return false;
        if (taskDone == null) taskDone = new java.util.ArrayList<Boolean>();
        while (taskDone.size() < tasks.size()) taskDone.add(Boolean.FALSE);
        boolean now = !Boolean.TRUE.equals(taskDone.get(index));
        taskDone.set(index, now);
        return now;
    }

    /** 本次请求实际发送给模型的内容（可含附件全文、联网结果），不持久化 */
    public transient String apiContent;
    /** 附件路径摘要（<file .../> 标签行）。会持久化：重开会话后 AI 仍知道文件在哪 */
    public String attachNote;

    /** 本次请求携带的图片（data URL），不持久化 */
    public transient java.util.List<String> imageUrls;

    /**
     * 本轮执行时间线：正文片段与工具调用按真实发生顺序交错。
     * 有了它，重新打开会话时还能还原「文字 → 工具行 → 文字」的原始过程，
     * 而不是把工具调用丢掉只剩一段散文。仅助手消息使用，可能为 null。
     */
    public java.util.List<Step> steps;

    /** 时间线上的一步：一段正文，或一次工具调用 */
    public static class Step {
        public static final String TEXT = "t";
        public static final String TOOL = "c";

        public String type;
        /** TEXT 当正文用；TOOL 当工具名用 */
        public String text;
        /** TOOL：参数摘要（单行） */
        public String arg;
        /** TOOL：结果摘要（单行） */
        public String res;
        /** TOOL：是否成功 */
        public boolean ok = true;
        /** TOOL：耗时毫秒 */
        public long ms;

        public boolean isTool() {
            return TOOL.equals(type);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("t", type == null ? TEXT : type);
                if (text != null && !text.isEmpty()) o.put("x", text);
                if (arg != null && !arg.isEmpty()) o.put("a", arg);
                if (res != null && !res.isEmpty()) o.put("r", res);
                o.put("k", ok);
                if (ms > 0) o.put("m", ms);
            } catch (JSONException ignored) {
            }
            return o;
        }

        public static Step fromJson(JSONObject o) {
            if (o == null) return null;
            Step s = new Step();
            s.type = JsonOpt.str(o, "t", TEXT);
            s.text = JsonOpt.str(o, "x");
            s.arg = JsonOpt.str(o, "a");
            s.res = JsonOpt.str(o, "r");
            s.ok = o.optBoolean("k", true);
            s.ms = o.optLong("m", 0);
            return s;
        }
    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
        this.time = System.currentTimeMillis();
    }

    public boolean isUser() {
        return ROLE_USER.equals(role);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("role", role);
            o.put("content", content == null ? "" : content);
            o.put("time", time);
            if (rating != 0) o.put("rating", rating);
            if (attachNote != null && !attachNote.trim().isEmpty()) o.put("attach", attachNote);
            if (tasks != null && !tasks.isEmpty()) {
                JSONArray ta = new JSONArray();
                for (int i = 0; i < tasks.size(); i++) {
                    JSONObject it = new JSONObject();
                    it.put("t", tasks.get(i) == null ? "" : tasks.get(i));
                    it.put("d", taskDone != null && i < taskDone.size()
                            && Boolean.TRUE.equals(taskDone.get(i)));
                    ta.put(it);
                }
                o.put("tasks", ta);
            }
            if (conclusionTitle != null && !conclusionTitle.trim().isEmpty()) {
                o.put("ct", conclusionTitle);
                if (conclusionDetail != null && !conclusionDetail.trim().isEmpty()) {
                    o.put("cd", conclusionDetail);
                }
            }
            if (steps != null && !steps.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (Step st : steps) {
                    if (st != null) arr.put(st.toJson());
                }
                if (arr.length() > 0) o.put("steps", arr);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Message fromJson(JSONObject o) {
        String c = JsonOpt.str(o, "content");
        // 清洗历史脏数据：整段只有 null 的回答（旧版本解析 bug 产生）
        if (JsonOpt.isNullNoise(c)) c = "";
        Message m = new Message(JsonOpt.str(o, "role", ROLE_USER), c);
        m.time = o.optLong("time", System.currentTimeMillis());
        m.rating = o.optInt("rating", 0);
        m.attachNote = JsonOpt.str(o, "attach");
        JSONArray tk = o.optJSONArray("tasks");
        if (tk != null && tk.length() > 0) {
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<Boolean> done = new java.util.ArrayList<>();
            for (int i = 0; i < tk.length(); i++) {
                JSONObject it = tk.optJSONObject(i);
                if (it == null) continue;
                String t = JsonOpt.str(it, "t");
                if (t == null || t.trim().isEmpty()) continue;
                names.add(t);
                done.add(it.optBoolean("d", false));
            }
            if (!names.isEmpty()) {
                m.tasks = names;
                m.taskDone = done;
            }
        }
        m.conclusionTitle = JsonOpt.str(o, "ct");
        m.conclusionDetail = JsonOpt.str(o, "cd");
        JSONArray sa = o.optJSONArray("steps");
        if (sa != null && sa.length() > 0) {
            java.util.List<Step> list = new java.util.ArrayList<>();
            for (int i = 0; i < sa.length(); i++) {
                Step st = Step.fromJson(sa.optJSONObject(i));
                if (st != null && ((st.text != null && !st.text.isEmpty())
                        || st.arg != null || st.res != null)) {
                    list.add(st);
                }
            }
            if (!list.isEmpty()) m.steps = list;
        }
        return m;
    }
}
