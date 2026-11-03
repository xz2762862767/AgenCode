package com.nebula.aichat.tool;

import android.content.Context;

import com.nebula.aichat.skill.SkillStore;

import org.json.JSONObject;

/**
 * 技能按需读取工具。system 里只放技能索引，正文靠这个工具取，
 * 避免每轮把没用到的技能正文重复发送。
 */
final class SkillTools {

    private SkillTools() {
    }

    static void register(ToolRegistry reg, final Context c) {
        reg.add(new ToolDef("read_skill",
                "读取某个技能的完整做法（步骤、输出格式、规则）。"
                        + "当用户需求与「可用技能」列表中某一项明确对应时，先调用它取得全文再照着做；不相关就别调。"
                        + "参数是技能 id 或名称，例如 text-organize。",
                ToolDef.schema(ToolDef.props(
                        "skill", ToolDef.prop("string", "技能 id 或名称，例如 text-organize")), "skill"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) {
                        return SkillStore.readSkillBody(c, a.optString("skill", ""));
                    }
                }));
    }
}
