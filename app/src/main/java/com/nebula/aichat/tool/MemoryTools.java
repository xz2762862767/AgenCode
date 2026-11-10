package com.nebula.aichat.tool;

import android.content.Context;

import com.nebula.aichat.data.MemoryStore;
import com.nebula.aichat.data.PlanStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「自我管理」类工具：长期记忆 + 任务计划表。
 *
 * <p>这两个工具都不是为了「多一个功能」，而是为了解决长会话里两个必然出现的问题：
 * <ul>
 *   <li>记忆：用户说过的项目约定、偏好，一旦上下文被裁剪就丢，只能反复问；
 *   <li>计划：多步任务做到一半容易漏步、容易把「验证」这一步默默跳过。
 * </ul>
 *
 * <p>关键设计：工具只是入口，真正的效果靠主流程把内容注入提示词（见 ChatClient）。
 * 只提供工具、不注入，模型不会主动去看，等于没做。
 */
public class MemoryTools {

    public static void register(ToolRegistry reg, final Context ctx) {

        // ------------------------------------------------------------ memory
        JSONObject mProps = new JSONObject();
        JSONObject mDesc = new JSONObject();
        try {
            mDesc.put("action", str("save=记住一条 / search=按关键词找 / list=列出最近 / delete=删除"));
            mDesc.put("key", str("记忆的键，简短稳定，如 项目包名、构建命令、用户偏好_回答风格"));
            mDesc.put("value", str("记忆内容，一条事实一句话，不要塞长段落"));
            mDesc.put("kind", str("分类：preference(用户偏好) / project(项目约定) / rule(硬规则) / fact(其他事实)"));
            mDesc.put("query", str("search 用的关键词"));
            mProps.put("action", mDesc);
            mProps.put("key", str("记忆的键，简短稳定"));
            mProps.put("value", str("记忆内容，一条事实一句话"));
            mProps.put("kind", str("preference / project / rule / fact"));
            mProps.put("query", str("search 的关键词"));
        } catch (Exception ignored) {
        }

        reg.add(new ToolDef("memory",
                "长期记忆。跨会话记住稳定的项目约定和用户偏好（包名、构建命令、代码风格、"
                        + "交付要求、用户明确纠正过的做法）。"
                        + "什么时候该 save：用户说出「以后都这样」「这个项目约定是…」「别再…」这类"
                        + "跨轮次有效的信息时，立刻存下来，不要只在回复里答应。"
                        + "不要存：临时的中间结果、一次性的报错、当轮才有效的内容。"
                        + "同一个 key 会覆盖旧值，所以 key 要稳定。"
                        + "开工前如果涉及项目约定或用户习惯，先 search 一下再动手。",
                ToolDef.schema(mProps, "action"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        String action = a.optString("action", "list").trim();
                        if ("save".equals(action) || "add".equals(action)) {
                            String key = a.optString("key", "").trim();
                            String value = a.optString("value", "").trim();
                            if (key.isEmpty() || value.isEmpty()) {
                                return "保存失败：key 和 value 都不能为空。";
                            }
                            MemoryStore.Item it = MemoryStore.save(ctx, key, value,
                                    a.optString("kind", "fact"));
                            if (it == null) return "保存失败：内容无效。";
                            return "已记住：" + it.line() + "\n（共 " + MemoryStore.all(ctx).size() + " 条）";
                        }
                        if ("search".equals(action) || "query".equals(action)) {
                            List<MemoryStore.Item> list =
                                    MemoryStore.query(ctx, a.optString("query", ""), 10);
                            if (list.isEmpty()) return "没有匹配的记忆。";
                            StringBuilder sb = new StringBuilder("匹配到 " + list.size() + " 条：\n");
                            for (MemoryStore.Item it : list) sb.append("· ").append(it.line()).append('\n');
                            MemoryStore.touch(ctx, list);
                            return sb.toString().trim();
                        }
                        if ("delete".equals(action) || "remove".equals(action)) {
                            String k = a.optString("key", "").trim();
                            if (k.isEmpty()) k = a.optString("query", "").trim();
                            if (k.isEmpty()) return "删除失败：请给出 key。";
                            return MemoryStore.delete(ctx, k) ? "已删除：" + k : "没有找到：" + k;
                        }
                        // list
                        List<MemoryStore.Item> list = MemoryStore.query(ctx, "", 30);
                        if (list.isEmpty()) return "还没有任何长期记忆。";
                        StringBuilder sb = new StringBuilder("长期记忆（" + list.size() + " 条）：\n");
                        for (MemoryStore.Item it : list) sb.append("· ").append(it.line()).append('\n');
                        return sb.toString().trim();
                    }
                }));

        // ------------------------------------------------------------ plan
        JSONObject pProps = new JSONObject();
        try {
            pProps.put("action", str("set=建立计划(整体覆盖) / add=追加一步 / done=标记完成 / list=查看 / clear=清空"));
            pProps.put("steps", str("步骤列表，字符串数组，或一行一步的文本"));
            pProps.put("id", str("步骤编号（p1/p2…或序号 1/2…），done 时用"));
            pProps.put("note", str("done 时记一句话：做了什么、怎么验证的"));
        } catch (Exception ignored) {
        }

        reg.add(new ToolDef("plan",
                "任务计划表。任务需要三步以上、或包含「改完还要验证/构建/安装」这类步骤时，"
                        + "先用 action=set 把步骤列出来，再逐条做，每完成一步就 action=done 标记。"
                        + "作用：让你在长任务里不漏步、也不在没做完时提前收尾——"
                        + "收尾前系统会核对未完成步骤并把你打回继续。"
                        + "简单问题、一两步就能答完的任务不要建计划。",
                ToolDef.schema(pProps, "action"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        String action = a.optString("action", "list").trim();
                        if ("set".equals(action)) {
                            List<String> titles = readSteps(a);
                            if (titles.isEmpty()) return "建立失败：steps 为空。";
                            PlanStore.set(titles.toArray(new String[0]));
                            return "计划已建立（" + titles.size() + " 步）：\n" + PlanStore.render();
                        }
                        if ("add".equals(action)) {
                            List<String> titles = readSteps(a);
                            if (titles.isEmpty()) return "追加失败：steps 为空。";
                            for (String t : titles) PlanStore.add(t);
                            return "已追加：\n" + PlanStore.render();
                        }
                        if ("done".equals(action) || "complete".equals(action)) {
                            PlanStore.Step s = PlanStore.done(a.optString("id", ""), a.optString("note", ""));
                            if (s == null) {
                                return "标记失败：找不到这一步（现有编号见 list 输出）。\n" + PlanStore.render();
                            }
                            String extra = PlanStore.pendingCount() == 0
                                    ? "\n全部步骤已完成，可以收尾了。"
                                    : "\n还剩 " + PlanStore.pendingCount() + " 步：" + PlanStore.pendingTitles();
                            return "已完成 " + s.id + " " + s.title + extra;
                        }
                        if ("clear".equals(action)) {
                            PlanStore.reset();
                            return "计划已清空。";
                        }
                        if (PlanStore.isEmpty()) return "当前没有计划。";
                        return "计划进度（剩 " + PlanStore.pendingCount() + " 步）：\n" + PlanStore.render();
                    }
                }));
    }

    private static JSONObject str(String desc) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "string");
        o.put("description", desc);
        return o;
    }

    /** steps 支持：字符串数组 / 换行分隔文本 / 单个字符串 */
    private static List<String> readSteps(JSONObject a) {
        List<String> out = new ArrayList<String>();
        if (a == null) return out;
        Object raw = a.opt("steps");
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        String text = a.optString("steps", "");
        if (text.trim().isEmpty()) return out;
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            // 容忍模型写成 "1. xxx" / "- xxx"
            s = s.replaceAll("^\\s*\\d+[.、)）]\\s*", "").replaceAll("^[-*·]\\s*", "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
