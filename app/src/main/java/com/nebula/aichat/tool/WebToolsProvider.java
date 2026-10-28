package com.nebula.aichat.tool;

import android.content.Context;

import com.nebula.aichat.data.Settings;
import com.nebula.aichat.net.WebFetch;

import org.json.JSONObject;

/**
 * 「浏览器自动化」工具：让模型自己决定打开哪个网页、读全文。
 *
 * 只有设置里 browserEnabled 打开时才注册：关掉时模型根本看不到这个工具，
 * 也就不会出现「工具存在但被拒」的尴尬。
 */
final class WebToolsProvider {

    private WebToolsProvider() {
    }

    static void register(ToolRegistry reg, final Context c) {
        if (!Settings.load(c).browserEnabled) return;

        reg.add(new ToolDef("web_fetch",
                "打开一个具体网页并取出可读内容（相当于无界面浏览器）。"
                        + "联网搜索只给每条两三行的摘要，当摘要不够、需要原文细节/数据/接口返回时用它。"
                        + "用法：先用联网搜索拿到候选链接，再对最相关的 1-3 个链接调 web_fetch 读全文；"
                        + "不要一次打开一堆链接。取不到正文的页面（登录墙/需要 JS 渲染）就换来源或让用户手动看。"
                        + "返回内容带【标题】【链接】【正文】头，正文已自动去标签、去脚本、压缩空白。",
                ToolDef.schema(ToolDef.props(
                        "url", ToolDef.prop("string", "要打开的完整网址，例如 https://example.com/a/b"),
                        "max_chars", ToolDef.prop("integer", "正文最多返回多少字，默认 20000，上限 80000；细节多的页面可以调大"),
                        "links", ToolDef.prop("boolean", "为 true 时返回页面里的链接列表（配 url 用），用于找下一层页面")
                ), "url"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        if (!Settings.load(c).browserEnabled) {
                            return "浏览器自动化已关闭。请让用户在输入框上方的「🌐 联网」面板里打开它，"
                                    + "或者改用联网搜索的结果作答。";
                        }
                        String url = a.optString("url", "").trim();
                        if (url.isEmpty()) return "缺少 url 参数：请给出要打开的完整网址。";
                        int max = a.optInt("max_chars", 20000);
                        boolean links = a.optBoolean("links", false);
                        return WebFetch.fetch(url, max, links);
                    }
                }));
    }
}
