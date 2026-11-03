package com.nebula.aichat.mcp;

import com.nebula.aichat.ui.Palette;

import android.app.Activity;
import android.app.AlertDialog;
import com.nebula.aichat.ui.NebAlert;
import android.app.Dialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nebula.aichat.ui.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MCP 服务器管理面板 */
public class McpPanel {

    public static void show(Activity act) {
        new McpPanel(act).run();
    }

    private final Activity act;
    private final List<McpConfig.Server> servers;
    private final Map<String, JSONArray> toolsCache = new HashMap<>();
    private final Map<String, String> infoCache = new HashMap<>();
    private LinearLayout box;
    private Dialog dialog;

    public McpPanel(Activity act) {
        this.act = act;
        this.servers = McpConfig.load(act);
    }

    private void run() {
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        box = Ui.col(act);
        int p = Ui.dp(act, 20);
        box.setPadding(p, Ui.dp(act, 28), p, Ui.dp(act, 6));
        sv.addView(box);

        dialog = new NebAlert.Builder(act)
                .setView(sv)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        editServer(null);
                    }
                })
                .setNegativeButton("关闭", null)
                .create();
        fill();
        dialog.show();
    }

    private void fill() {
        box.removeAllViews();

        Ui.dialogHeader(box, act, "MCP 服务器",
                "接入实现 MCP 协议的服务器（JSON-RPC over HTTP / SSE），"
                        + "可浏览其工具并手动调用。");

        if (servers.isEmpty()) {
            LinearLayout empty = Ui.cardRect(act);
            empty.addView(Ui.text(act, "尚未添加服务器", 13.5f, Palette.text(act)));
            TextView hint = Ui.para(act, "常见地址形如 https://example.com/mcp", 12f, Palette.dim(act));
            hint.setPadding(0, Ui.dp(act, 7), 0, 0);
            empty.addView(hint);
            box.addView(empty);
        }

        for (final McpConfig.Server s : servers) {
            LinearLayout card = Ui.cardRect(act);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = Ui.dp(act, 10);
            card.setLayoutParams(clp);

            LinearLayout titleRow = Ui.row(act);
            TextView name = Ui.text(act, s.label(), 14.5f, Palette.text(act));
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            titleRow.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final TextView toggle = Ui.text(act, s.enabled ? "已启用" : "已停用", 11.5f,
                    s.enabled ? Palette.c(0xFF7CD98F) : Palette.c(0xFF8B97A9));
            toggle.setBackground(Ui.rect(act, s.enabled ? Palette.c(0xFF16281C) : Palette.c(0xFF1A1D22), 0, 0f));
            toggle.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s.enabled = !s.enabled;
                    McpConfig.save(act, servers);
                    fill();
                }
            });
            titleRow.addView(toggle);
            card.addView(titleRow);

            TextView url = Ui.text(act, s.url, 11.5f, Palette.dim(act));
            url.setSingleLine(true);
            url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            url.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 10));
            card.addView(url);

            String cached = infoCache.get(s.id);
            if (cached != null) {
                TextView res = Ui.para(act, cached, 11.5f, Palette.accent(act));
                res.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 4));
                card.addView(res);
            }

            // 信息区（名称/地址/测试结果）与操作区断开，卡片内部形成「上读下管」两层
            card.addView(Ui.rowDivider(act, 0));

            LinearLayout actions = Ui.row(act);
            actions.setPadding(0, Ui.dp(act, 12), 0, 0);
            actions.addView(Ui.chip(act, "连接测试", Palette.c(0xFF1E3A6B), Palette.c(0xFF9DC0FF), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    testServer(s);
                }
            }));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp2.leftMargin = Ui.dp(act, 6);
            actions.addView(Ui.chip(act, "工具", Palette.c(0xFF1E2530), Palette.c(0xFFB9C5D6), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTools(s);
                }
            }), lp2);
            actions.addView(Ui.chip(act, "编辑", Palette.c(0xFF1E2530), Palette.c(0xFFB9C5D6), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editServer(s);
                }
            }), lp2);
            actions.addView(Ui.chip(act, "删除", Palette.c(0xFF3A1E1E), Palette.c(0xFFFF8A80), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    servers.remove(s);
                    McpConfig.save(act, servers);
                    toolsCache.remove(s.id);
                    infoCache.remove(s.id);
                    fill();
                }
            }), lp2);
            card.addView(actions);
            box.addView(card);
        }

        box.addView(Ui.spacer(act, 14));
        TextView note = Ui.para(act, "说明：连接测试会依次调用 initialize 与 tools/list。", 11.5f, Palette.dim(act));
        note.setAlpha(0.7f);
        box.addView(note);
    }

    /** 表单字段标签：和上方控件拉开距离、和下方输入框贴近，形成「标签属于输入框」的视觉分组 */
    private TextView fieldLabel(String s) {
        TextView t = Ui.text(act, s, 12f, Palette.dim(act));
        t.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 6));
        return t;
    }

    // ------------------------------------------------------------ 编辑

    private void editServer(final McpConfig.Server existing) {
        LinearLayout form = Ui.col(act);
        int p = Ui.dp(act, 20);
        form.setPadding(p, p, p, Ui.dp(act, 6));
        Ui.dialogHeader(form, act, existing == null ? "添加 MCP 服务器" : "编辑服务器",
                "地址需指向实现了 MCP 协议的 HTTP / SSE 端点，保存后会自动启用。");

        final EditText etName = field("名称（可留空）", existing == null ? "" : existing.name, false);
        final EditText etUrl = field("服务器地址，如 https://host/mcp", existing == null ? "" : existing.url, true);
        final EditText etToken = field("访问令牌（可留空）", existing == null ? "" : existing.token, false);

        form.addView(fieldLabel("名称"));
        form.addView(etName);
        form.addView(fieldLabel("地址"));
        form.addView(etUrl);
        form.addView(fieldLabel("令牌"));
        form.addView(etToken);

        new NebAlert.Builder(act)
                .setView(form)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String url = etUrl.getText().toString().trim();
                        if (url.isEmpty()) {
                            Toast.makeText(act, "地址不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String name = etName.getText().toString().trim();
                        McpConfig.Server s = existing;
                        if (s == null) {
                            s = new McpConfig.Server();
                            s.id = "mcp_" + System.currentTimeMillis();
                            servers.add(s);
                        }
                        s.name = name.isEmpty() ? url : name;
                        s.url = url;
                        s.token = etToken.getText().toString().trim();
                        s.enabled = true;
                        McpConfig.save(act, servers);
                        fill();
                        dialog.show(); // 保持面板
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        dialog.show();
                    }
                })
                .show();
        dialog.hide();
    }

    private EditText field(String hint, String value, boolean uri) {
        EditText et = new EditText(act);
        et.setHint(hint);
        et.setText(value);
        et.setTextSize(13f);
        et.setTextColor(Palette.c(0xFFE8EDF6));
        et.setHintTextColor(Palette.c(0xFF5A6577));
        et.setSingleLine(true);
        et.setInputType(uri ? InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_CLASS_TEXT);
        return et;
    }

    // ------------------------------------------------------------ 测试

    private void testServer(final McpConfig.Server s) {
        infoCache.put(s.id, "连接中…");
        fill();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    McpClient c = new McpClient(s.url, s.token);
                    String info = c.initialize();
                    JSONArray tools = c.listTools();
                    toolsCache.put(s.id, tools);
                    result = "✓ " + info + " · " + tools.length() + " 个工具";
                } catch (final Exception e) {
                    result = "✗ " + shorten(e.getMessage());
                }
                final String msg = result;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        infoCache.put(s.id, msg);
                        fill();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ 工具

    private void showTools(final McpConfig.Server s) {
        JSONArray cached = toolsCache.get(s.id);
        if (cached == null) {
            Toast.makeText(act, "请先执行「连接测试」", Toast.LENGTH_SHORT).show();
            return;
        }
        final ScrollView sv = new ScrollView(act);
        final LinearLayout list = Ui.col(act);
        int p = Ui.dp(act, 14);
        list.setPadding(p, p, p, p);
        sv.addView(list);

        if (cached.length() == 0) {
            list.addView(Ui.text(act, "该服务器未提供工具", 12f, Palette.c(0xFF8B97A9)));
        }
        for (int i = 0; i < cached.length(); i++) {
            final JSONObject tool = cached.optJSONObject(i);
            if (tool == null) continue;
            final String name = tool.optString("name", "tool" + i);
            String desc = tool.optString("description", "");

            LinearLayout card = Ui.cardRect(act);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = Ui.dp(act, 7);
            card.setLayoutParams(clp);
            TextView t = Ui.text(act, name, 13f, Palette.c(0xFFE8EDF6));
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            card.addView(t);
            if (!desc.isEmpty()) {
                TextView d = Ui.text(act, desc, 11f, Palette.c(0xFF8B97A9));
                d.setPadding(0, Ui.dp(act, 3), 0, 0);
                card.addView(d);
            }
            card.addView(Ui.chip(act, "调用", Palette.c(0xFF1E3A6B), Palette.c(0xFF9DC0FF), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    callToolDialog(s, tool);
                }
            }));
            list.addView(card);
        }

        new NebAlert.Builder(act)
                .setTitle("工具 · " + s.label())
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void callToolDialog(final McpConfig.Server s, final JSONObject tool) {
        String name = tool.optString("name", "");

        final EditText et = new EditText(act);
        et.setText(sampleArgs(tool));
        et.setTextSize(12f);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextColor(Palette.c(0xFFE8EDF6));
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setMinLines(4);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int p = Ui.dp(act, 14);
        et.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(act);
        sv.addView(et);

        new NebAlert.Builder(act)
                .setTitle("调用 " + name)
                .setMessage("参数（JSON 对象）")
                .setView(sv)
                .setPositiveButton("执行", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String text = et.getText().toString().trim();
                        JSONObject args;
                        try {
                            args = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                        } catch (Exception e) {
                            Toast.makeText(act, "参数不是合法 JSON", Toast.LENGTH_LONG).show();
                            return;
                        }
                        runTool(s, tool.optString("name", ""), args);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runTool(final McpConfig.Server s, final String name, final JSONObject args) {
        Toast.makeText(act, "执行中…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    McpClient c = new McpClient(s.url, s.token);
                    c.initialize();
                    JSONObject result = c.callTool(name, args);
                    out = McpClient.contentToText(result);
                } catch (Exception e) {
                    out = "调用失败：" + e.getMessage();
                }
                final String text = out;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ScrollView sv = new ScrollView(act);
                        TextView tv = Ui.text(act, text, 12f, Palette.c(0xFFC8D3E5));
                        tv.setTextIsSelectable(true);
                        int p = Ui.dp(act, 14);
                        tv.setPadding(p, p, p, p);
                        sv.addView(tv);
                        new NebAlert.Builder(act)
                                .setTitle(name + " 结果")
                                .setView(sv)
                                .setPositiveButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 根据 inputSchema 生成参数模板 */
    private String sampleArgs(JSONObject tool) {
        try {
            JSONObject schema = tool.optJSONObject("inputSchema");
            JSONObject props = schema == null ? null : schema.optJSONObject("properties");
            JSONObject out = new JSONObject();
            if (props != null) {
                JSONArray names = props.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String k = names.optString(i);
                        JSONObject def = props.optJSONObject(k);
                        String type = def == null ? "string" : def.optString("type", "string");
                        if ("number".equals(type) || "integer".equals(type)) out.put(k, 0);
                        else if ("boolean".equals(type)) out.put(k, false);
                        else if ("array".equals(type)) out.put(k, new JSONArray());
                        else if ("object".equals(type)) out.put(k, new JSONObject());
                        else out.put(k, "");
                    }
                }
            }
            return out.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String shorten(String s) {
        if (s == null) return "未知错误";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** 汇总所有启用服务器的工具数量（供提示词/调试用） */
    public static int enabledCount(Activity act) {
        int n = 0;
        for (McpConfig.Server s : McpConfig.load(act)) if (s.enabled) n++;
        return n;
    }

    public static List<String> enabledNames(Activity act) {
        List<String> out = new ArrayList<>();
        for (McpConfig.Server s : McpConfig.load(act)) if (s.enabled) out.add(s.label());
        return out;
    }
}
