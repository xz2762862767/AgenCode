package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 项目页：工程目录管理 / 模板创建 / 一键在终端打开 */
public class ProjectPage {

    public interface Callback {
        void onOpenTerminal(File dir);

        void onBrowseSandbox(File dir);
    }

    /** 视图模式：本应用工程 / 我的工程 */
    private static final int MODE_SELF = 0;
    private static final int MODE_MINE = 1;

    /** 网格列数 */
    private static final int COLS = 2;
    private static final int TEXT_MAIN = 0xFFE8EDF6;
    private static final int TEXT_SUB = 0xFF8B97A9;

    private final Activity act;
    private final Callback cb;
    private final File root;

    private LinearLayout listBox;
    private TextView tvSummary;
    private TextView tvTitle;
    private TextView btnSwitch;
    private LinearLayout rootView;
    private int mode = MODE_SELF;

    public ProjectPage(Activity act, Callback cb) {
        this.act = act;
        this.cb = cb;
        this.root = new File(new File(act.getFilesDir(), "sandbox"), "projects");
        //noinspection ResultOfMethodCallIgnored
        this.root.mkdirs();
        build();
        refresh();
    }

    public View getView() {
        return rootView;
    }

    public void onShown() {
        // 延后一帧刷新：先让底栏切换的点击反馈立刻出现，再做列表重建
        if (rootView != null) {
            rootView.post(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
        }
    }

    private void build() {
        rootView = Ui.col(act);
        rootView.setBackgroundColor(Palette.c(0xFF0F1218));

        LinearLayout head = Ui.row(act);
        head.setPadding(Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 10));

        LinearLayout info = Ui.col(act);
        tvTitle = Ui.text(act, "本应用工程", 19f, Palette.c(TEXT_MAIN));
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvSummary = Ui.text(act, "", 11f, Palette.c(TEXT_SUB));
        tvSummary.setPadding(0, Ui.dp(act, 3), 0, 0);
        tvSummary.setSingleLine(true);
        tvSummary.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        info.addView(tvTitle);
        info.addView(tvSummary);
        head.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnSwitch = Ui.chip(act, "我的工程", Palette.c(0xFF1C2532), Palette.c(0xFF9DC0FF), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mode = (mode == MODE_SELF) ? MODE_MINE : MODE_SELF;
                refresh();
            }
        });
        head.addView(btnSwitch);

        rootView.addView(head);
        rootView.addView(Ui.divider(act));

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        listBox = Ui.col(act);
        int p = Ui.dp(act, 12);
        listBox.setPadding(p, p, p, Ui.dp(act, 28));
        sv.addView(listBox);
        rootView.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public void refresh() {
        if (listBox == null) return;
        if (btnSwitch != null) btnSwitch.setText(mode == MODE_SELF ? "我的工程" : "本应用");
        if (mode == MODE_SELF) renderSelf();
        else renderMine();
    }

    /** 我的工程：sandbox/projects 下的用户工程 */
    private void renderMine() {
        listBox.removeAllViews();
        if (tvTitle != null) tvTitle.setText("我的工程");

        File[] fs = root.listFiles();
        final List<File> list = new ArrayList<>();
        if (fs != null) {
            for (File f : fs) if (f.isDirectory()) list.add(f);
        }
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        tvSummary.setText(list.size() + " 个项目 · " + root.getAbsolutePath());
        if (list.isEmpty()) {
            LinearLayout empty = Ui.col(act);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 60), 0, 0);
            TextView icon = Ui.text(act, "🗂", 34f, Palette.c(0xFF39445A));
            icon.setGravity(Gravity.CENTER);
            TextView e1 = Ui.text(act, "还没有项目", 15f, Palette.c(TEXT_MAIN));
            e1.setGravity(Gravity.CENTER);
            e1.setPadding(0, Ui.dp(act, 10), 0, 0);
            TextView e2 = Ui.text(act, "点下方按钮从模板创建工程", 12f, Palette.c(TEXT_SUB));
            e2.setGravity(Gravity.CENTER);
            e2.setPadding(0, Ui.dp(act, 6), 0, 0);
            TextView e3 = Ui.chip(act, "＋ 从模板新建", Palette.c(0xFF1E3A6B), Palette.c(0xFF9DC0FF),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showTemplateDialog();
                        }
                    });
            LinearLayout.LayoutParams e3lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            e3lp.topMargin = Ui.dp(act, 16);
            empty.addView(icon);
            empty.addView(e1);
            empty.addView(e2);
            empty.addView(e3, e3lp);
            listBox.addView(empty);
            return;
        }

        TextView addNew = Ui.chip(act, "＋ 从模板新建工程", Palette.c(0xFF17233A), Palette.c(0xFF9DC0FF),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTemplateDialog();
                    }
                });
        LinearLayout.LayoutParams anlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        anlp.bottomMargin = Ui.dp(act, 12);
        listBox.addView(addNew, anlp);
        renderGrid(list);
    }

    // ───────────────────────── 本应用工程 ─────────────────────────

    /** AgenCode 自身工程：应用信息 + 源码树 + 资源 + 构建配置 */
    private void renderSelf() {
        listBox.removeAllViews();
        List<SelfSource.Entry> all = SelfSource.entries(act);
        if (tvTitle != null) tvTitle.setText("本应用工程");
        if (all.isEmpty()) {
            tvSummary.setText("源码索引缺失，请安装完整构建的 APK");
            LinearLayout tip = Ui.card(act);
            TextView t = Ui.text(act, "未找到 assets/selfsrc/index.tsv\n构建时 genSelfSrc 任务未执行或资源被裁剪。", 11.5f, Palette.c(TEXT_SUB));
            tip.addView(t);
            listBox.addView(tip);
            return;
        }

        int nFiles = 0, nLines = 0;
        long nBytes = 0;
        List<SelfSource.Entry> javaList = new ArrayList<>();
        List<SelfSource.Entry> resList = new ArrayList<>();
        List<SelfSource.Entry> buildList = new ArrayList<>();
        for (SelfSource.Entry e : all) {
            nFiles++;
            nLines += e.lines;
            nBytes += e.bytes;
            if (SelfSource.KIND_JAVA.equals(e.kind)) javaList.add(e);
            else if (SelfSource.KIND_RES.equals(e.kind)) resList.add(e);
            else buildList.add(e);
        }
        String version = appVersion();
        if (tvSummary != null) {
            tvSummary.setText(nFiles + " 个文件 · " + nLines + " 行 · " + Ui.humanSize(nBytes)
                    + " · v" + version);
        }

        // 1) 应用信息
        listBox.addView(buildAppCard(nFiles, nLines, nBytes));

        // 2) 源码
        addSelfSection("源码", "app/src/main/java", javaList.size() + " 个文件 · " + sumLines(javaList) + " 行");
        LinkedHashMap<String, List<SelfSource.Entry>> javaGroups = SelfSource.group(javaList);
        int gi = 0;
        for (Map.Entry<String, List<SelfSource.Entry>> en : javaGroups.entrySet()) {
            String g = en.getKey();
            List<SelfSource.Entry> files = en.getValue();
            if (files == null) continue;
            String label = g.isEmpty() ? "com.nebula.aichat" : "com.nebula.aichat." + g.replace('/', '.');
            addSelfGroup(label, files, gi == 0);
            gi++;
        }

        // 3) 资源
        if (!resList.isEmpty()) {
            addSelfSection("资源", "app/src/main/res", resList.size() + " 个文件");
            LinkedHashMap<String, List<SelfSource.Entry>> resGroups = SelfSource.group(resList);
            for (Map.Entry<String, List<SelfSource.Entry>> en : resGroups.entrySet()) {
                String g = en.getKey();
                List<SelfSource.Entry> files = en.getValue();
                if (files == null) continue;
                addSelfGroup("res/" + (g.isEmpty() ? "(根)" : g), files, false);
            }
        }

        // 4) 构建配置
        if (!buildList.isEmpty()) {
            addSelfSection("构建配置", "gradle", "");
            addSelfGroup("工程配置", buildList, true);
        }
    }

    private int sumLines(List<SelfSource.Entry> list) {
        int n = 0;
        for (SelfSource.Entry e : list) n += e.lines;
        return n;
    }

    private String appVersion() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 应用信息卡片：图标字母 + 名称/包名 + 版本、APK、目录等键值行 */
    private View buildAppCard(int nFiles, int nLines, long nBytes) {
        LinearLayout card = Ui.card(act);

        LinearLayout top = Ui.row(act);
        TextView logo = new TextView(act);
        // 品牌首字母徽标（旧版本这里放的是旧中文名首字）
        logo.setText("A");
        logo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 21f);
        logo.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setTextColor(Palette.c(0xFFBFD4FF));
        logo.setBackground(Ui.roundRect(act, Palette.c(0xFF1B2B4A), Palette.c(0xFF2E4A78), 14f));
        int sq = Ui.dp(act, 46);
        top.addView(logo, new LinearLayout.LayoutParams(sq, sq));

        LinearLayout nameBox = Ui.col(act);
        TextView name = Ui.text(act, "AgenCode", 16f, Palette.c(TEXT_MAIN));
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        TextView pkg = Ui.text(act, act.getPackageName(), 10.5f, Palette.c(TEXT_SUB));
        pkg.setPadding(0, Ui.dp(act, 3), 0, 0);
        nameBox.addView(name);
        nameBox.addView(pkg);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(act, 11);
        top.addView(nameBox, nlp);
        card.addView(top);

        View sep = new View(act);
        sep.setBackgroundColor(Palette.c(0xFF1E2632));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        slp.topMargin = Ui.dp(act, 12);
        slp.bottomMargin = Ui.dp(act, 6);
        card.addView(sep, slp);

        String apkPath = "";
        String apkSize = "?";
        int targetSdk = 0;
        try {
            ApplicationInfo ai = act.getApplicationInfo();
            apkPath = ai.sourceDir;
            apkSize = Ui.humanSize(new File(ai.sourceDir).length());
            targetSdk = ai.targetSdkVersion;
        } catch (Throwable ignore) {
        }
        card.addView(kvRow("版本", "v" + appVersion() + " · code " + appCode()));
        card.addView(kvRow("APK", apkSize + (apkPath.isEmpty() ? "" : " · 已安装")));
        card.addView(kvRow("SDK", "target " + targetSdk + " · min 24"));
        card.addView(kvRow("源码", nFiles + " 文件 · " + nLines + " 行 · " + Ui.humanSize(nBytes)));
        card.addView(kvRow("数据目录", shorten(act.getFilesDir().getParent())));
        return card;
    }

    private String appCode() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            // API 28+ 上 versionCode 字段恒为 0，必须走 getLongVersionCode
            long vc = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return String.valueOf(vc);
        } catch (Throwable t) {
            return "?";
        }
    }

    private String shorten(String path) {
        if (path == null) return "";
        return path.replace("/data/user/0/", "…/");
    }

    private View kvRow(String k, String v) {
        LinearLayout r = Ui.row(act);
        r.setPadding(0, Ui.dp(act, 5), 0, Ui.dp(act, 5));
        TextView kt = Ui.text(act, k, 11f, Palette.c(0xFF74839A));
        TextView vt = Ui.text(act, v, 11f, Palette.c(0xFFCBD6E6));
        vt.setSingleLine(true);
        vt.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        vt.setGravity(Gravity.RIGHT);
        r.addView(kt, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = Ui.dp(act, 12);
        r.addView(vt, vlp);
        return r;
    }

    /** 分区标题：左标题 + 右注脚 */
    private void addSelfSection(String title, String sub, String right) {
        LinearLayout r = Ui.row(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 14);
        lp.bottomMargin = Ui.dp(act, 9);
        r.setPadding(Ui.dp(act, 2), 0, Ui.dp(act, 2), 0);

        TextView t = Ui.text(act, title, 12.5f, Palette.c(0xFF9FB0C8));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        r.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView st = Ui.text(act, sub, 9.5f, Palette.c(0xFF5D6B80));
            st.setSingleLine(true);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            slp.leftMargin = Ui.dp(act, 8);
            r.addView(st, slp);
        }
        if (right != null && !right.isEmpty()) {
            r.addView(Ui.text(act, right, 9.5f, Palette.c(0xFF5D6B80)));
        }
        listBox.addView(r, lp);
    }

    /** 可折叠分组：组头（包名 + 统计）+ 文件列表 */
    private void addSelfGroup(String label, final List<SelfSource.Entry> files, boolean expanded) {
        LinearLayout wrap = Ui.col(act);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = Ui.dp(act, 8);

        LinearLayout head = Ui.row(act);
        head.setBackground(Ui.roundRect(act, Palette.c(0xFF161B24), Palette.c(0xFF242C39), 11f));
        head.setPadding(Ui.dp(act, 11), Ui.dp(act, 10), Ui.dp(act, 11), Ui.dp(act, 10));
        head.setClickable(true);

        final TextView arrow = Ui.text(act, expanded ? "▾" : "▸", 10f, Palette.c(0xFF7E8B9E));
        arrow.setIncludeFontPadding(false);
        head.addView(arrow);

        TextView name = Ui.text(act, label, 12f, Palette.c(0xFFDCE5F2));
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(act, 8);
        head.addView(name, nlp);

        TextView meta = Ui.text(act, files.size() + " 文件", 9.5f, Palette.c(0xFF6E7B8E));
        head.addView(meta);

        final LinearLayout body = Ui.col(act);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        for (SelfSource.Entry e : files) body.addView(selfFileRow(e));

        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                arrow.setText(show ? "▾" : "▸");
            }
        });

        wrap.addView(head);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(act, 5);
        wrap.addView(body, blp);
        listBox.addView(wrap, wlp);
    }

    /** 文件行：文件名 + 右侧行数/大小，点击查看源码 */
    private View selfFileRow(final SelfSource.Entry e) {
        LinearLayout r = Ui.row(act);
        r.setBackground(Ui.roundRect(act, Palette.c(0xFF12171F), Palette.c(0xFF1C232E), 9f));
        r.setPadding(Ui.dp(act, 11), Ui.dp(act, 9), Ui.dp(act, 11), Ui.dp(act, 9));
        r.setClickable(true);

        TextView dot = Ui.text(act, "•", 13f, fileColor(e));
        dot.setIncludeFontPadding(false);
        r.addView(dot);

        TextView name = Ui.text(act, e.name(), 11.5f, Palette.c(0xFFD5DFEE));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(act, 7);
        r.addView(name, nlp);

        TextView meta = Ui.text(act, e.lines > 0 ? e.lines + " 行" : Ui.humanSize(e.bytes),
                9.5f, Palette.c(0xFF66748A));
        r.addView(meta);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 5);
        r.setLayoutParams(lp);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SourceViewer.show(act, e.name(), e.kind + "/" + e.path,
                        SelfSource.read(act, e), e.lang());
            }
        });
        return r;
    }

    private int fileColor(SelfSource.Entry e) {
        if (SelfSource.KIND_JAVA.equals(e.kind)) return Palette.c(0xFF6F9BFF);
        if (SelfSource.KIND_RES.equals(e.kind)) return Palette.c(0xFF6FE3A0);
        return Palette.c(0xFFFFC46B);
    }

    /** 两列等宽网格，末行不足时补占位，保证卡片不被拉伸 */
    private void renderGrid(List<File> list) {
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % COLS == 0) {
                row = Ui.row(act);
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = Ui.dp(act, 10);
                listBox.addView(row, rp);
            }
            View card = buildCard(list.get(i));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % COLS == 0) {
                cp.rightMargin = Ui.dp(act, 5);
            } else {
                cp.leftMargin = Ui.dp(act, 5);
            }
            if (row != null) row.addView(card, cp);
        }
        if (row != null && list.size() % COLS != 0) {
            View pad = new View(act);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, 1, 1f);
            pp.leftMargin = Ui.dp(act, 5);
            row.addView(pad, pp);
        }
    }

    private View buildCard(final File dir) {
        final LinearLayout card = Ui.card(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setMinimumHeight(Ui.dp(act, 148));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(dir);
            }
        });

        // 顶部：类型色块（工程名首字母） + 右上「⋯」
        LinearLayout top = Ui.row(act);
        top.setGravity(Gravity.TOP);

        String kind = kindOf(dir);
        TextView icon = new TextView(act);
        icon.setText(initialOf(dir));
        icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f);
        icon.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(kindFg(kind));
        icon.setBackground(Ui.roundRect(act, kindBg(kind), 0, 13f));
        int sq = Ui.dp(act, 42);
        top.addView(icon, new LinearLayout.LayoutParams(sq, sq));

        View gap = new View(act);
        top.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView more = Ui.text(act, "⋯", 20f, Palette.c(0xFF7E8B9E));
        more.setGravity(Gravity.CENTER);
        int mh = Ui.dp(act, 30);
        more.setLayoutParams(new LinearLayout.LayoutParams(mh, mh));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(dir, v);
            }
        });
        top.addView(more);
        card.addView(top);

        // 工程名
        TextView name = Ui.text(act, dir.getName(), 13.5f, Palette.c(TEXT_MAIN));
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, Ui.dp(act, 11), 0, 0);
        card.addView(name);

        // 状态小标签
        LinearLayout tags = Ui.row(act);
        tags.setPadding(0, Ui.dp(act, 8), 0, 0);
        tags.addView(badge(kind, kindFg(kind), kindBg(kind)));
        card.addView(tags);

        // 底部信息（占满剩余空间，让不同卡片底部对齐）
        View filler = new View(act);
        card.addView(filler, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView meta = Ui.text(act,
                countFiles(dir) + " 文件 · " + ago(dir.lastModified()), 10.5f, Palette.c(TEXT_SUB));
        meta.setPadding(0, Ui.dp(act, 8), 0, 0);
        card.addView(meta);
        return card;
    }

    /** 圆角小胶囊标签 */
    private TextView badge(String s, int fg, int bg) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
        tv.setTextColor(fg);
        tv.setBackground(Ui.roundRect(act, bg, 0, 7f));
        int ph = Ui.dp(act, 7), pv = Ui.dp(act, 2);
        tv.setPadding(ph, pv, ph, pv);
        return tv;
    }

    /** 右上角「⋯」菜单 */
    private void showMenu(final File dir, View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "详情");
        pm.getMenu().add(0, 2, 1, "终端打开");
        pm.getMenu().add(0, 3, 2, "浏览文件");
        pm.getMenu().add(0, 4, 3, "删除");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        showDetail(dir);
                        return true;
                    case 2:
                        cb.onOpenTerminal(dir);
                        return true;
                    case 3:
                        cb.onBrowseSandbox(dir);
                        return true;
                    case 4:
                        confirmDelete(dir);
                        return true;
                    default:
                        return false;
                }
            }
        });
        pm.show();
    }

    private void confirmDelete(final File dir) {
        new NebAlert.Builder(act)
                .setTitle("删除项目")
                .setMessage("确定删除「" + dir.getName() + "」及其全部文件？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        deleteRecursive(dir);
                        refresh();
                        Toast.makeText(act, "已删除", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 工程类型（决定图标配色与状态标签文字） */
    private String kindOf(File dir) {
        if (new File(dir, "AndroidManifest.xml").exists()) return "Android";
        if (new File(dir, "package.json").exists()) return "Node";
        if (new File(dir, "requirements.txt").exists() || hasExt(dir, ".py")) return "Python";
        if (new File(dir, "index.html").exists() || hasExt(dir, ".html")) return "Web";
        return "通用";
    }

    private boolean hasExt(File dir, String ext) {
        String[] ns = dir.list();
        if (ns == null) return false;
        for (String n : ns) if (n.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) return true;
        return false;
    }

    private int kindFg(String kind) {
        if ("Android".equals(kind)) return Palette.c(0xFF6FE3A0);
        if ("Node".equals(kind)) return Palette.c(0xFF9FE07A);
        if ("Python".equals(kind)) return Palette.c(0xFFFFD166);
        if ("Web".equals(kind)) return Palette.c(0xFF7FD1FF);
        return Palette.c(0xFFB9C5D6);
    }

    private int kindBg(String kind) {
        if ("Android".equals(kind)) return Palette.c(0xFF17352A);
        if ("Node".equals(kind)) return Palette.c(0xFF202F1C);
        if ("Python".equals(kind)) return Palette.c(0xFF3A2F1B);
        if ("Web".equals(kind)) return Palette.c(0xFF16293A);
        return Palette.c(0xFF232A36);
    }

    /** 工程名首字母（跳过符号） */
    private String initialOf(File dir) {
        String n = dir.getName();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetterOrDigit(c)) return String.valueOf(Character.toUpperCase(c));
        }
        return "P";
    }

    /** 相对时间 */
    private String ago(long ts) {
        long d = Math.max(0, System.currentTimeMillis() - ts);
        if (d < 60000L) return "刚刚";
        if (d < 3600000L) return (d / 60000L) + " 分钟前";
        if (d < 86400000L) return (d / 3600000L) + " 小时前";
        if (d < 30L * 86400000L) return (d / 86400000L) + " 天前";
        return new SimpleDateFormat("MM-dd", java.util.Locale.CHINA).format(new Date(ts));
    }

    private void showDetail(final File dir) {
        StringBuilder sb = new StringBuilder();
        tree(dir, "", sb, 0);
        ScrollView sv = new ScrollView(act);
        TextView tv = Ui.text(act, sb.toString(), 12f, Palette.c(0xFFC8D3E5));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = Ui.dp(act, 14);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);

        new NebAlert.Builder(act)
                .setTitle(dir.getName())
                .setView(sv)
                .setPositiveButton("终端打开", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cb.onOpenTerminal(dir);
                    }
                })
                .setNeutralButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        deleteRecursive(dir);
                        refresh();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void tree(File f, String prefix, StringBuilder sb, int depth) {
        if (depth > 3) return;
        File[] fs = f.listFiles();
        if (fs == null) return;
        List<File> list = new ArrayList<>(Arrays.asList(fs));
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (int i = 0; i < list.size(); i++) {
            File c = list.get(i);
            boolean last = i == list.size() - 1;
            sb.append(prefix).append(last ? "└─ " : "├─ ")
                    .append(c.isDirectory() ? c.getName() + "/" : c.getName())
                    .append(c.isDirectory() ? "" : "  " + Ui.humanSize(c.length()))
                    .append('\n');
            if (c.isDirectory()) tree(c, prefix + (last ? "   " : "│  "), sb, depth + 1);
        }
    }

    private int countFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) n += f.isDirectory() ? countFiles(f) : 1;
        return n;
    }

    private long sizeOf(File f) {
        if (f.isFile()) return f.length();
        File[] fs = f.listFiles();
        if (fs == null) return 0;
        long t = 0;
        for (File c : fs) t += sizeOf(c);
        return t;
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------------------------------------------------------- 新建项目

    private void showTemplateDialog() {
        final String[] items = {"空项目", "Android 工程骨架", "网页（HTML/CSS/JS）",
                "Python 脚本", "Shell 工具箱"};
        new NebAlert.Builder(act)
                .setTitle("选择模板")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        askName(which);
                    }
                })
                .show();
    }

    private void askName(final int template) {
        final EditText et = new EditText(act);
        et.setText(defaultName(template));
        et.setSelectAllOnFocus(true);
        et.setTextColor(Palette.c(0xFFE8EDF6));
        int p = Ui.dp(act, 16);
        et.setPadding(p, Ui.dp(act, 8), p, Ui.dp(act, 8));

        new NebAlert.Builder(act)
                .setTitle("项目名称")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = et.getText().toString().trim().replaceAll("[/\\\\:*?\"<>|]", "_");
                        if (name.isEmpty()) return;
                        File dir = new File(root, name);
                        if (dir.exists()) {
                            Toast.makeText(act, "同名项目已存在", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            createTemplate(dir, template);
                            refresh();
                            Toast.makeText(act, "已创建 " + name, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(act, "创建失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String defaultName(int template) {
        String base = template == 1 ? "android-app" : template == 2 ? "web-page"
                : template == 3 ? "py-script" : template == 4 ? "shell-kit" : "new-project";
        int i = 1;
        String n = base;
        while (new File(root, n).exists()) n = base + "-" + (++i);
        return n;
    }

    private void createTemplate(File dir, int template) throws Exception {
        if (!dir.mkdirs()) throw new Exception("无法创建目录");
        switch (template) {
            case 0:
                write(new File(dir, "README.md"), "# " + dir.getName() + "\n\n在这里记录项目说明。\n");
                break;
            case 1:
                androidTemplate(dir);
                break;
            case 2:
                webTemplate(dir);
                break;
            case 3:
                write(new File(dir, "main.py"), "#!/usr/bin/env python3\n# -*- coding: utf-8 -*-\n\n"
                        + "def main():\n    print(\"Hello, AgenCode!\")\n\n\n"
                        + "if __name__ == \"__main__\":\n    main()\n");
                write(new File(dir, "README.md"), "# Python 脚本\n\n运行：`python3 main.py`\n");
                break;
            case 4:
                write(new File(dir, "hello.sh"), "#!/system/bin/sh\n"
                        + "echo \"Hello from $(uname -a)\"\n");
                write(new File(dir, "list.sh"), "#!/system/bin/sh\n"
                        + "# 列出当前目录详细信息\nls -la\n");
                write(new File(dir, "README.md"), "# Shell 工具箱\n\n用 `sh hello.sh` 运行。\n");
                break;
        }
    }

    private void androidTemplate(File dir) throws Exception {
        File jdir = new File(dir, "app/src/main/java/com/example/app");
        File res = new File(dir, "app/src/main/res/values");
        //noinspection ResultOfMethodCallIgnored
        jdir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        res.mkdirs();

        write(new File(dir, "settings.gradle"),
                "pluginManagement {\n    repositories {\n"
                        + "        google()\n        mavenCentral()\n    }\n}\n"
                        + "dependencyResolutionManagement {\n    repositories {\n"
                        + "        google()\n        mavenCentral()\n    }\n}\n"
                        + "rootProject.name = \"" + dir.getName() + "\"\n"
                        + "include ':app'\n");

        write(new File(dir, "build.gradle"),
                "plugins {\n    id 'com.android.application' version '8.2.0' apply false\n}\n");

        write(new File(dir, "app/build.gradle"),
                "plugins {\n    id 'com.android.application'\n}\n\n"
                        + "android {\n    namespace 'com.example.app'\n    compileSdk 34\n\n"
                        + "    defaultConfig {\n        applicationId \"com.example.app\"\n"
                        + "        minSdk 24\n        targetSdk 34\n"
                        + "        versionCode 1\n        versionName \"1.0\"\n    }\n\n"
                        + "    buildTypes {\n        release {\n            minifyEnabled false\n        }\n    }\n"
                        + "    compileOptions {\n        sourceCompatibility JavaVersion.VERSION_17\n"
                        + "        targetCompatibility JavaVersion.VERSION_17\n    }\n}\n\n"
                        + "dependencies {\n    implementation 'androidx.appcompat:appcompat:1.6.1'\n}\n");

        write(new File(dir, "app/src/main/AndroidManifest.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n\n"
                        + "    <application\n        android:allowBackup=\"true\"\n"
                        + "        android:label=\"@string/app_name\"\n"
                        + "        android:theme=\"@android:style/Theme.Material.NoActionBar\">\n\n"
                        + "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n"
                        + "            <intent-filter>\n"
                        + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                        + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                        + "            </intent-filter>\n"
                        + "        </activity>\n"
                        + "    </application>\n\n</manifest>\n");

        write(new File(jdir, "MainActivity.java"),
                "package com.example.app;\n\nimport android.app.Activity;\nimport android.os.Bundle;\n"
                        + "import android.widget.TextView;\n\npublic class MainActivity extends Activity {\n"
                        + "    @Override\n    protected void onCreate(Bundle savedInstanceState) {\n"
                        + "        super.onCreate(savedInstanceState);\n"
                        + "        TextView tv = new TextView(this);\n"
                        + "        tv.setText(\"Hello from " + dir.getName() + "\");\n"
                        + "        setContentView(tv);\n    }\n}\n");

        write(new File(res, "strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                        + "    <string name=\"app_name\">" + dir.getName() + "</string>\n</resources>\n");

        write(new File(dir, "BUILD.md"),
                "# 构建说明\n\n本工程需要 JDK 17 + Android SDK 34。\n\n"
                        + "在具备环境的主机或远程执行服务上运行：\n\n```\n"
                        + "gradle assembleRelease\n```\n\n"
                        + "然后把 `app/build/outputs/apk/release/app-release.apk` 导入本应用，\n"
                        + "用「环境 → APK 工具」重新签名或换图标。\n");
    }

    private void webTemplate(File dir) throws Exception {
        write(new File(dir, "index.html"),
                "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n"
                        + "  <meta charset=\"utf-8\" />\n"
                        + "  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\" />\n"
                        + "  <title>" + dir.getName() + "</title>\n"
                        + "  <link rel=\"stylesheet\" href=\"style.css\" />\n</head>\n<body>\n"
                        + "  <h1>" + dir.getName() + "</h1>\n"
                        + "  <p>双击打开或部署到静态服务器。</p>\n"
                        + "  <script src=\"app.js\"></script>\n</body>\n</html>\n");
        write(new File(dir, "style.css"),
                ":root { color-scheme: dark; }\n"
                        + "body { margin:0; font-family: system-ui, sans-serif; background:#0f1218; color:#e8edf6;\n"
                        + "       display:grid; place-items:center; min-height:100vh; }\n"
                        + "h1 { font-size:28px; letter-spacing:.5px; }\n");
        write(new File(dir, "app.js"), "console.log('hello from " + dir.getName() + "');\n");
        write(new File(dir, "README.md"), "# 网页项目\n\n直接用浏览器打开 `index.html`。\n");
    }

    private void write(File f, String content) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        SandboxPage.writeText(f, content);
    }
}
