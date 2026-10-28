package com.nebula.aichat.skill;

import com.nebula.aichat.ui.Palette;

import android.app.Activity;
import android.app.AlertDialog;
import com.nebula.aichat.ui.NebAlert;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nebula.aichat.ui.Ui;

import java.io.File;
import java.util.List;

/** 技能管理面板：浏览、新建、导入、编辑、导出、启用/停用 */
public class SkillPanel {

    public static final int REQ_IMPORT = 0x5C1;

    private static SkillPanel current;

    public static void show(Activity act) {
        SkillPanel p = new SkillPanel(act);
        current = p;
        p.run();
    }

    /** 文件选择回调；返回 true 表示已处理 */
    public static boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (current == null) return false;
        return current.handleResult(requestCode, resultCode, data);
    }

    private final Activity act;
    private LinearLayout box;
    private Dialog dialog;

    public SkillPanel(Activity act) {
        this.act = act;
    }

    private void run() {
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        box = Ui.col(act);
        int p = Ui.dp(act, 20);
        box.setPadding(p, p, p, Ui.dp(act, 6));
        sv.addView(box);

        // 不用 setTitle：系统标题与自定义内容之间的留白由 AlertController 写死，
        // 标题会贴着正文。改由 dialogHeader 在内容区自绘，间距完全可控。
        dialog = new NebAlert.Builder(act)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        // 面板关掉后不要攥着 Activity 不放，同时让排队中的后台渲染作废
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                fillGen++;
                statView = null;
                currentList = null;
                if (current == SkillPanel.this) current = null;
            }
        });
        fill();
        showPanel();
    }

    // ------------------------------------------------------------ 列表

    /** 载入代次：快速连点「技能」时，旧的后台结果不许覆盖新界面 */
    private int fillGen = 0;
    private TextView statView;
    private List<SkillStore.Skill> currentList;
    private int[] currentExtras;
    /** 一次最多铺多少个卡片：技能几十上百个时，一口气建全部 View 也会卡主线程 */
    private static final int PAGE = 24;
    private int visibleCount = PAGE;

    private void fill() {
        final int gen = ++fillGen;
        box.removeAllViews();
        Ui.dialogHeader(box, act, "技能",
                "技能是工作区 skills/ 下的 SKILL.md。启用的技能会追加到系统提示词，"
                        + "让模型按固定流程回答。可以从文件、压缩包或内置库导入。");
        box.addView(Ui.text(act, "正在读取技能…", 12.5f, Palette.dim(act)));

        // 读盘、解析、统计附件数全部挪到后台：以前这些都压在点击那一下的主线程上，
        // 技能一多点点按钮就要卡住几百毫秒
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SkillStore.Skill> list = null;
                int[] extras = null;
                String err = null;
                try {
                    SkillStore.ensureSamples(act);
                    list = SkillStore.load(act);
                    extras = new int[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        extras[i] = extraFiles(list.get(i).file);
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage() == null ? t : t.getMessage());
                }
                final List<SkillStore.Skill> fl = list;
                final int[] fe = extras;
                final String ferr = err;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != fillGen || box == null) return;
                        if (fl == null) {
                            box.removeAllViews();
                            box.addView(Ui.text(act, "读取技能失败：" + ferr, 12f, Palette.c(0xFFFF8A80)));
                            return;
                        }
                        render(fl, fe);
                    }
                });
            }
        }, "nebula-skills-load").start();
    }

    private void applyToggle(TextView toggle, boolean on) {
        toggle.setText(on ? "已启用" : "启用");
        toggle.setTextColor(on ? Palette.c(0xFF7CD98F) : Palette.c(0xFF8B97A9));
        toggle.setBackground(Ui.rect(act, on ? Palette.c(0xFF16281C) : Palette.c(0xFF1A1D22), 0, 0f));
    }

    /** 只刷新统计文本，不重建整个列表 */
    private void updateStat() {
        if (statView == null || currentList == null) return;
        int enabled = 0;
        for (SkillStore.Skill s : currentList) if (s.enabled) enabled++;
        statView.setText("共 " + currentList.size() + " 个 · 已启用 " + enabled);
    }

    private void render(List<SkillStore.Skill> list, int[] extras) {
        box.removeAllViews();
        currentList = list;
        currentExtras = extras;
        Ui.dialogHeader(box, act, "技能",
                "技能是工作区 skills/ 下的 SKILL.md。启用的技能会追加到系统提示词，"
                        + "让模型按固定流程回答。可以从文件、压缩包或内置库导入。");

        int enabled = 0;
        for (SkillStore.Skill s : list) if (s.enabled) enabled++;

        // 统计 + 操作条
        LinearLayout bar = Ui.row(act);
        statView = Ui.text(act, "共 " + list.size() + " 个 · 已启用 " + enabled, 11.5f, Palette.c(0xFF8B97A9));
        bar.addView(statView);
        View spring = new View(act);
        bar.addView(spring, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(Ui.primaryChip(act, "＋ 新建", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createDialog();
            }
        }));
        bar.addView(gap(act, 6));
        bar.addView(Ui.ghostChip(act, "⬇ 导入", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importDialog();
            }
        }));
        box.addView(bar);

        LinearLayout bar2 = Ui.row(act);
        bar2.addView(Ui.ghostChip(act, "👁 预览注入内容", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                preview();
            }
        }));
        bar2.addView(gap(act, 6));
        bar2.addView(Ui.ghostChip(act, "📂 打开目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(act, "路径：" + SkillStore.skillsDir(act).getAbsolutePath(),
                        Toast.LENGTH_LONG).show();
            }
        }));
        box.addView(bar2);
        box.addView(Ui.spacer(act, 10));

        if (list.isEmpty()) {
            LinearLayout empty = Ui.cardRect(act);
            empty.addView(Ui.text(act, "还没有技能", 13f, Palette.c(0xFFE8EDF6)));
            empty.addView(Ui.text(act, "点上面「＋ 新建」自己写，或「⬇ 导入」从文件/内置库加进来",
                    11f, Palette.c(0xFF8B97A9)));
            box.addView(empty);
            return;
        }

        int shown = Math.min(list.size(), Math.max(visibleCount, PAGE));
        for (int i = 0; i < shown; i++) {
            final SkillStore.Skill s = list.get(i);
            LinearLayout card = Ui.cardRect(act);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = Ui.dp(act, 10);
            card.setLayoutParams(clp);

            LinearLayout titleRow = Ui.row(act);
            TextView name = Ui.text(act, s.name, 14.5f, Palette.text(act));
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            titleRow.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final TextView toggle = Ui.text(act, "", 11f, Palette.c(0xFF8B97A9));
            applyToggle(toggle, s.enabled);
            toggle.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 就地翻转：不再整表重建（重建＝重新读盘 + 重建所有卡片）
                    s.enabled = !s.enabled;
                    SkillStore.setEnabled(act, s.id, s.enabled);
                    applyToggle(toggle, s.enabled);
                    updateStat();
                }
            });
            titleRow.addView(toggle);
            card.addView(titleRow);

            if (s.description != null && !s.description.isEmpty()) {
                TextView d = Ui.para(act, s.description, 12f, Palette.dim(act));
                d.setPadding(0, Ui.dp(act, 7), 0, Ui.dp(act, 10));
                card.addView(d);
            }
            int extra = extras != null && i < extras.length ? extras[i] : 0;
            // 元信息用 alpha 压一档，比硬编码一个更暗的色号更安全（浅色主题下也不会发灰）
            TextView meta = Ui.text(act, "目录：" + s.id + " · 正文 " + s.body.length() + " 字"
                    + (extra > 0 ? " · 附带 " + extra + " 个文件" : ""), 11f, Palette.dim(act));
            meta.setAlpha(0.72f);
            card.addView(meta);

            // 操作按钮（等宽两行，避免挤压）
            LinearLayout actions = Ui.row(act);
            actions.setPadding(0, Ui.dp(act, 12), 0, 0);
            actions.addView(Ui.chip(act, "编辑", Palette.c(0xFF1E3A6B), Palette.c(0xFF9DC0FF), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editDialog(s);
                }
            }));
            actions.addView(gap(act, 6));
            actions.addView(Ui.chip(act, "复制", Palette.c(0xFF1A1D22), Palette.c(0xFFC8D3E5), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copy(SkillImport.exportText(s));
                    Toast.makeText(act, "已复制 SKILL.md 全文", Toast.LENGTH_SHORT).show();
                }
            }));
            actions.addView(gap(act, 6));
            actions.addView(Ui.chip(act, "删除", Palette.c(0xFF3A1E1E), Palette.c(0xFFFF8A80), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(s);
                }
            }));
            card.addView(actions);
            box.addView(card);
        }

        if (list.size() > shown) {
            final int rest = list.size() - shown;
            LinearLayout more = Ui.row(act);
            more.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 6));
            more.addView(Ui.ghostChip(act, "显示更多（还有 " + rest + " 个）",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            visibleCount = Math.max(visibleCount, PAGE) + PAGE;
                            render(currentList, currentExtras);
                        }
                    }));
            box.addView(more);
        }
    }

    private void confirmDelete(final SkillStore.Skill s) {
        new NebAlert.Builder(act)
                .setTitle("删除技能")
                .setMessage("「" + s.name + "」及其目录下的所有文件都会被删除，无法恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        SkillStore.delete(act, s);
                        fill();
                        showPanel();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showPanel();
                    }
                })
                .show();
        dialog.hide();
    }

    // ------------------------------------------------------------ 导入

    private void importDialog() {
        LinearLayout col = Ui.col(act);
        int p = Ui.dp(act, 16);
        col.setPadding(p, Ui.dp(act, 6), p, 0);

        final Dialog[] holder = new Dialog[1];
        col.addView(importOption("📄  从文件导入",
                "选一个 SKILL.md，或 Skills.zip —— 包里每个含 SKILL.md 的目录算一个技能，可整包一次导入",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        holder[0].dismiss();
                        pickFile();
                    }
                }));
        col.addView(importOption("📋  粘贴文本导入",
                "直接把 SKILL.md 内容贴进来，自动识别 frontmatter 里的名称和描述",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        holder[0].dismiss();
                        pasteDialog();
                    }
                }));
        List<SkillStore.Builtin> builtins = SkillStore.builtins(act);
        col.addView(importOption("🎁  内置技能库（" + builtins.size() + " 个）",
                "开箱可用：翻译、润色、摘要、纪要、周报、SQL、正则、代码讲解",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        holder[0].dismiss();
                        builtinDialog();
                    }
                }));

        holder[0] = new NebAlert.Builder(act)
                .setTitle("导入技能")
                .setView(col)
                .setNegativeButton("关闭", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showPanel();
                    }
                })
                .create();
        holder[0].show();
        dialog.hide();
    }

    private LinearLayout importOption(String title, String sub, View.OnClickListener l) {
        LinearLayout card = Ui.cardRect(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 8);
        card.setLayoutParams(lp);
        card.addView(Ui.text(act, title, 13.5f, Palette.c(0xFFE8EDF6)));
        TextView s = Ui.text(act, sub, 11f, Palette.c(0xFF8B97A9));
        s.setPadding(0, Ui.dp(act, 3), 0, 0);
        card.addView(s);
        card.setOnClickListener(l);
        return card;
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // 默认打开「下载」目录，多数人的技能包都在这儿
        try {
            i.putExtra("android.provider.extra.INITIAL_URI",
                    Uri.parse("content://com.android.providers.downloads.documents/root/downloads"));
        } catch (Exception ignored) {
        }
        try {
            act.startActivityForResult(Intent.createChooser(i, "选择 SKILL.md 或 .zip"), REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(act, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean handleResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            showPanel();
            return true;
        }
        final Uri uri = data.getData();
        final String name = SkillImport.displayName(act, uri);
        // 读文件 / 解压 zip 可能是几十兆的活，放主线程会直接卡住甚至 ANR
        fill();                       // 先让面板显示「正在读取」
        showPanel();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SkillImport.Result r = SkillImport.importUri(act, uri, name);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        fill();
                        showPanel();
                        Toast.makeText(act, r.summary(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "nebula-skill-import").start();
        return true;
    }

    /** 面板重新显示：Activity 已经销毁时不要把 BadTokenException 抛给用户 */
    private void showPanel() {
        if (act.isFinishing() || act.isDestroyed()) return;
        try {
            if (!dialog.isShowing()) dialog.show();
        } catch (Throwable ignored) {
        }
    }

    private void pasteDialog() {
        LinearLayout form = Ui.col(act);
        int p = Ui.dp(act, 16);
        form.setPadding(p, Ui.dp(act, 8), p, 0);

        final EditText etName = field("名称（留空则读 frontmatter / 用默认名）", "");
        final EditText etBody = new EditText(act);
        etBody.setHint("---\nname: 我的技能\ndescription: 什么情况下用\n---\n\n# 正文\n\n## 规则\n- ...");
        etBody.setTextSize(12f);
        etBody.setTypeface(Typeface.MONOSPACE);
        etBody.setTextColor(Palette.c(0xFFE8EDF6));
        etBody.setHintTextColor(Palette.c(0xFF5A6577));
        etBody.setBackgroundColor(Palette.c(0xFF0B0E14));
        etBody.setGravity(Gravity.TOP | Gravity.START);
        etBody.setMinLines(8);
        etBody.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etBody.setPadding(p, p, p, p);

        // 剪贴板里有内容就预填，省一次手动粘贴
        String clip = clipText();
        if (clip != null && clip.trim().length() > 10) {
            etBody.setText(clip);
            etBody.setSelection(etBody.getText().length());
        }

        form.addView(Ui.text(act, "名称", 11f, Palette.c(0xFF8B97A9)));
        form.addView(etName);
        form.addView(Ui.text(act, "内容", 11f, Palette.c(0xFF8B97A9)));
        ScrollView sv = new ScrollView(act);
        sv.addView(etBody);
        form.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 220)));

        new NebAlert.Builder(act)
                .setTitle("粘贴导入")
                .setView(form)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String text = etBody.getText().toString();
                        String nm = etName.getText().toString().trim();
                        if (text.trim().isEmpty()) {
                            Toast.makeText(act, "内容为空", Toast.LENGTH_SHORT).show();
                            showPanel();
                            return;
                        }
                        try {
                            SkillStore.Skill s = SkillImport.importMarkdown(act, text,
                                    nm.isEmpty() ? "导入的技能" : nm);
                            fill();
                            showPanel();
                            Toast.makeText(act, "已导入并启用：" + s.name, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(act, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            showPanel();
                        }
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showPanel();
                    }
                })
                .show();
        dialog.hide();
    }

    private void builtinDialog() {
        final List<SkillStore.Builtin> list = SkillStore.builtins(act);
        if (list.isEmpty()) {
            Toast.makeText(act, "内置库为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            SkillStore.Builtin b = list.get(i);
            items[i] = b.name + (b.installed(act) ? "（已添加）" : " — " + b.desc);
        }
        new NebAlert.Builder(act)
                .setTitle("内置技能库")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        SkillStore.Builtin b = list.get(which);
                        if (b.installed(act)) {
                            Toast.makeText(act, "「" + b.name + "」已经添加过了", Toast.LENGTH_SHORT).show();
                            showPanel();
                            return;
                        }
                        try {
                            SkillStore.installBuiltin(act, b);
                            fill();
                            showPanel();
                            Toast.makeText(act, "已添加并启用：" + b.name, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(act, "添加失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            showPanel();
                        }
                    }
                })
                .setNegativeButton("关闭", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showPanel();
                    }
                })
                .show();
        dialog.hide();
    }

    // ------------------------------------------------------------ 编辑 / 新建 / 预览

    private void editDialog(final SkillStore.Skill s) {
        String content;
        try {
            content = readAll(s.file);
        } catch (Exception e) {
            Toast.makeText(act, "读取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText et = new EditText(act);
        et.setText(content);
        et.setTextSize(11.5f);
        et.setTypeface(Typeface.MONOSPACE);
        et.setTextColor(Palette.c(0xFFE8EDF6));
        et.setBackgroundColor(Palette.c(0xFF0B0E14));
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int p = Ui.dp(act, 12);
        et.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(act);
        sv.addView(et);

        new NebAlert.Builder(act)
                .setTitle(s.name + " · SKILL.md")
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            writeAll(s.file, et.getText().toString());
                            fill();
                            Toast.makeText(act, "已保存", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(act, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                        showPanel();
                    }
                })
                .setNeutralButton("复制全文", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        copy(et.getText().toString());
                        Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
                        showPanel();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showPanel();
                    }
                })
                .show();
        dialog.hide();
    }

    private void createDialog() {
        LinearLayout form = Ui.col(act);
        int p = Ui.dp(act, 16);
        form.setPadding(p, Ui.dp(act, 8), p, 0);

        final EditText etName = field("显示名称，如 技术选型对比", "我的技能");
        final EditText etDesc = field("什么时候用它（一句话）", "");
        form.addView(Ui.text(act, "名称", 11f, Palette.c(0xFF8B97A9)));
        form.addView(etName);
        form.addView(Ui.text(act, "何时使用", 11f, Palette.c(0xFF8B97A9)));
        form.addView(etDesc);

        new NebAlert.Builder(act)
                .setTitle("新建技能")
                .setView(form)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = etName.getText().toString().trim();
                        String desc = etDesc.getText().toString().trim();
                        if (name.isEmpty()) name = "我的技能";
                        try {
                            SkillStore.Skill s = SkillImport.importMarkdown(act,
                                    "# " + name + "\n\n## 适用场景\n- \n\n## 输出要求\n- \n", name);
                            if (!desc.isEmpty()) {
                                // 补上描述，方便列表里看出来
                                writeAll(s.file, "---\nname: " + name + "\ndescription: " + desc
                                        + "\n---\n\n# " + name + "\n\n## 适用场景\n- \n\n## 输出要求\n- \n");
                            }
                            fill();
                            showPanel();
                            Toast.makeText(act, "已创建并启用，点「编辑」写正文", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(act, "创建失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            showPanel();
                        }
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showPanel();
                    }
                })
                .show();
        dialog.hide();
    }

    private void preview() {
        String inject = SkillStore.buildIndex(act);
        if (inject.isEmpty()) {
            Toast.makeText(act, "没有启用的技能", Toast.LENGTH_SHORT).show();
            return;
        }
        ScrollView sv = new ScrollView(act);
        TextView tv = Ui.text(act, inject, 11.5f, Palette.c(0xFFC8D3E5));
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        int p = Ui.dp(act, 14);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);
        new NebAlert.Builder(act)
                .setTitle("将注入提示词的内容（" + inject.length() + " 字）")
                .setView(sv)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        copy(inject);
                        Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showPanel();
                    }
                })
                .show();
        dialog.hide();
    }

    // ------------------------------------------------------------ 小工具

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("skill", text));
    }

    private String clipText() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            CharSequence cs = cd.getItemAt(0).coerceToText(act);
            return cs == null ? null : cs.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int extraFiles(File md) {
        if (md == null) return 0;
        File dir = md.getParentFile();
        if (dir == null) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f.isFile() && !f.getName().equalsIgnoreCase("SKILL.md")) n++;
        }
        return n;
    }

    private View gap(Activity act, int dp) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, dp), 1));
        return v;
    }

    private EditText field(String hint, String value) {
        EditText et = new EditText(act);
        et.setHint(hint);
        et.setText(value);
        et.setSingleLine(true);
        et.setTextSize(13f);
        et.setTextColor(Palette.c(0xFFE8EDF6));
        et.setHintTextColor(Palette.c(0xFF5A6577));
        return et;
    }

    private static String readAll(File f) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream is = new java.io.FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void writeAll(File f, String s) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        fos.write(s.getBytes("UTF-8"));
        fos.close();
    }
}
