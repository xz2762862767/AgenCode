package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 沙箱页：工作区文件浏览 / 编辑 / 新建 / 删除 / 导入导出 */
public class SandboxPage {

    public static final int REQ_IMPORT = 0x501;
    public static final int REQ_EXPORT = 0x502;

    private final Activity act;
    private final File root;
    private File cur;

    private LinearLayout listBox;
    /** 面包屑：路径拆成可点的段，工作区 › projects › app */
    private LinearLayout crumbBox;
    private android.widget.HorizontalScrollView crumbScroll;
    /** 统计行：目录数 / 文件数 / 总大小，三个小胶囊 */
    private LinearLayout statBox;
    private ScrollView scroll;
    /** 上一级按钮，到顶时淡化并禁用 */
    private TextView btnUp;

    private LinearLayout rootView;
    /** 列表刷新的世代号：快速连点切换时，只认最后一次的结果 */
    private volatile int refreshToken;
    /** 一次最多渲染多少行，几千个 View 光布局就要好几秒 */
    private static final int MAX_ROWS = 300;
    private File pendingExport;

    public SandboxPage(Activity act) {
        this.act = act;
        this.root = new File(act.getFilesDir(), "sandbox");
        //noinspection ResultOfMethodCallIgnored
        this.root.mkdirs();
        this.cur = root;
        build();
        refresh();
    }

    public View getView() {
        return rootView;
    }

    public File getRoot() {
        return root;
    }

    public File getCurrent() {
        return cur;
    }

    public void onShown() {
        // 延后一帧刷新，先保证点击反馈即时
        if (rootView != null) {
            rootView.post(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
        }
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        rootView = Ui.col(act);
        rootView.setBackgroundColor(Palette.bg(act));

        LinearLayout head = Ui.col(act);
        head.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 8));

        // ---- 第一行：返回上级 + 面包屑 + 位置切换
        LinearLayout topRow = Ui.row(act);

        btnUp = Ui.text(act, "‹", 19f, Palette.text(act));
        btnUp.setGravity(Gravity.CENTER);
        GradientDrawable upBg = new GradientDrawable();
        upBg.setColor(Palette.surface2(act));
        upBg.setCornerRadius(Ui.dp(act, 10));
        btnUp.setBackground(Ui.pressable(act, upBg));
        btnUp.setClickable(true);
        btnUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File parent = cur.getParentFile();
                openDir(parent != null && inScope(parent) ? parent : root);
            }
        });
        topRow.addView(btnUp, new LinearLayout.LayoutParams(Ui.dp(act, 32), Ui.dp(act, 32)));

        // 面包屑横向滚动：路径再深也不会把「位置」按钮挤出去
        crumbScroll = new android.widget.HorizontalScrollView(act);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.setFillViewport(false);
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        csLp.leftMargin = Ui.dp(act, 8);
        csLp.rightMargin = Ui.dp(act, 8);
        crumbBox = Ui.row(act);
        crumbScroll.addView(crumbBox);
        topRow.addView(crumbScroll, csLp);

        TextView btnLoc = Ui.ghostChip(act, "位置 ▾", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLocationMenu();
            }
        });
        topRow.addView(btnLoc);

        head.addView(topRow);

        // ---- 第二行：统计胶囊 + 新建 / 刷新
        LinearLayout statRow = Ui.row(act);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = Ui.dp(act, 9);

        statBox = Ui.row(act);
        statRow.addView(statBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnRefresh = statChip("⟳", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        statRow.addView(btnRefresh);

        TextView btnNew = statChip("＋ 新建", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewDialog();
            }
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = Ui.dp(act, 6);
        statRow.addView(btnNew, nlp);

        head.addView(statRow, srLp);
        rootView.addView(head);
        rootView.addView(Ui.divider(act));

        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        listBox = Ui.col(act);
        int p = Ui.dp(act, 10);
        listBox.setPadding(p, p, p, Ui.dp(act, 24));
        scroll.addView(listBox);
        rootView.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /**
     * 统计行 / 工具行的统一胶囊。
     * primary=true 时用强调色描边，用来突出「新建」这个主动作。
     */
    private TextView statChip(String s, boolean primary, View.OnClickListener l) {
        TextView tv = Ui.text(act, s, 11.5f,
                primary ? Palette.accent(act) : Palette.dim(act));
        tv.setPadding(Ui.dp(act, 10), Ui.dp(act, 5), Ui.dp(act, 10), Ui.dp(act, 5));
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? Palette.accentSoft(act, 0x1F) : 0x00000000);
        bg.setCornerRadius(Ui.dp(act, 10));
        if (primary) bg.setStroke(Math.max(1, Ui.dp(act, 1)), Palette.accentSoft(act, 0x55));
        else bg.setStroke(Math.max(1, Ui.dp(act, 0.8f)), Palette.stroke(act));
        tv.setBackground(Ui.pressable(act, bg));
        tv.setClickable(true);
        if (l != null) tv.setOnClickListener(l);
        return tv;
    }

    /** 统计行里的只读小标签（目录数 / 文件数 / 总大小） */
    private TextView infoPill(String s, int color) {
        TextView tv = Ui.text(act, s, 11f, color);
        tv.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.surface2(act));
        bg.setCornerRadius(Ui.dp(act, 8));
        tv.setBackground(bg);
        return tv;
    }

    /** 统计胶囊：小矢量图标 + 数字，和列表里的类型图标同一套语言 */
    private View statPill(int kind, String count) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(act, 8), Ui.dp(act, 5), Ui.dp(act, 9), Ui.dp(act, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.surface2(act));
        bg.setCornerRadius(Ui.dp(act, 8));
        box.setBackground(bg);
        ImageView ic = new ImageView(act);
        ic.setImageDrawable(new FileGlyph(kind, tintOf(kind)));
        box.addView(ic, new LinearLayout.LayoutParams(Ui.dp(act, 13), Ui.dp(act, 13)));
        TextView tv = Ui.text(act, count, 11f, Palette.dim(act));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(act, 4);
        box.addView(tv, lp);
        return box;
    }

    /** 重建面包屑：每一段都能点回那一级 */
    private void renderCrumbs(File dir) {
        if (crumbBox == null) return;
        crumbBox.removeAllViews();
        List<File> chain = chainOf(dir);
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            boolean last = i == chain.size() - 1;
            if (i > 0) {
                TextView sep = Ui.text(act, "›", 12f, Palette.stroke(act));
                sep.setPadding(Ui.dp(act, 4), 0, Ui.dp(act, 4), 0);
                crumbBox.addView(sep);
            }
            TextView tv = Ui.text(act, crumbName(f), last ? 13f : 12.5f,
                    last ? Palette.text(act) : Palette.dim(act));
            if (last) tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setSingleLine(true);
            tv.setPadding(Ui.dp(act, 2), 0, Ui.dp(act, 2), 0);
            if (!last) {
                tv.setClickable(true);
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openDir(f);
                    }
                });
            }
            crumbBox.addView(tv);
        }
        // 滚动到最右，深目录时默认看到当前层
        if (crumbScroll != null) {
            crumbScroll.post(new Runnable() {
                @Override
                public void run() {
                    crumbScroll.fullScroll(View.FOCUS_RIGHT);
                }
            });
        }

        File parent = dir.getParentFile();
        boolean atTop = parent == null || !inScope(parent);
        btnUp.setEnabled(!atTop);
        btnUp.setAlpha(atTop ? 0.3f : 1f);
    }

    /** 从根(工作区或手机存储)到当前目录的链路 */
    private List<File> chainOf(File dir) {
        List<File> chain = new ArrayList<File>();
        if (dir == null) return chain;
        String sd = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        File stop = dir.getAbsolutePath().startsWith(root.getAbsolutePath()) ? root : new File(sd);
        File f = dir;
        while (f != null) {
            chain.add(0, f);
            if (f.equals(stop)) break;
            if (!f.getAbsolutePath().startsWith(stop.getAbsolutePath())) break;
            f = f.getParentFile();
        }
        return chain;
    }

    private String crumbName(File f) {
        if (f.equals(root)) return "工作区";
        String sd = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (f.getAbsolutePath().equals(sd)) return "手机存储";
        String n = f.getName();
        return n == null || n.isEmpty() ? "/" : n;
    }

    // -------------------------------------------------------------- 文件列表

    public void refresh() {
        if (listBox == null) return;
        // 万一被后台线程调到，别在这儿直接 setText
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
            return;
        }
        final int token = ++refreshToken;
        final File dir = cur;

        // 先给出即时反馈：点按钮后马上看到路径变了、正在读，而不是站在原地不动
        renderCrumbs(dir);
        listBox.removeAllViews();
        showStats(-1, -1, null);
        TextView loading = Ui.text(act, "读取中…", 13f, 0xFF8B97A9);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, Ui.dp(act, 40), 0, 0);
        listBox.addView(loading);

        // /sdcard 根有几千项，逐个 list() 数子项、递归算总大小，放主线程就是几秒白屏
        new Thread(new Runnable() {
            @Override
            public void run() {
                File[] files = null;
                try {
                    files = dir.listFiles();
                } catch (Throwable ignored) {
                }
                final File[] raw = files;
                final long[] size = new long[2];   // [0]=字节数 [1]=是否因超时/超量截断
                dirSizeBudget(dir, System.currentTimeMillis() + 400, size);
                final java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
                if (raw != null) {
                    for (File f : raw) {
                        if (f == null || !f.isDirectory()) continue;
                        counts.put(f.getAbsolutePath(), countChildren(f));
                    }
                }
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (token != refreshToken) return;   // 期间又切了目录，丢弃旧结果
                        render(raw, counts, size);
                    }
                });
            }
        }, "sandbox-list").start();
    }

    private void render(File[] files, java.util.Map<String, Integer> counts, long[] size) {
        if (files == null) {
            listBox.removeAllViews();
            listBox.addView(emptyState("🔒", "无法读取该目录", "可能是系统权限限制，换个位置再试", 0xFFFF8A80));
            if (statBox != null) {
                statBox.removeAllViews();
                statBox.addView(infoPill("不可读", 0xFFFF8A80));
            }
            return;
        }
        List<File> list = new ArrayList<File>(Arrays.asList(files));
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        listBox.removeAllViews();
        int dirs = 0, fileCount = 0;
        for (File f : list) {
            if (f.isDirectory()) dirs++;
            else fileCount++;
        }

        if (list.isEmpty()) {
            listBox.addView(emptyState("📂", "这里还是空的",
                    "点右上角「＋ 新建」写个文件，或「导入」从手机里挑", Palette.dim(act)));
        }

        int shown = Math.min(list.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            listBox.addView(buildRow(list.get(i), counts));
        }
        if (list.size() > shown) {
            TextView more = Ui.text(act, "还有 " + (list.size() - shown)
                    + " 项未显示（用终端 ls 查看更完整）", 11.5f, Palette.dim(act));
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
            listBox.addView(more);
        }
        String total = Ui.humanSize(size[0]) + (size[1] == 1 ? "+" : "");
        showStats(dirs, fileCount, total);
    }

    /** 统计行：dirs&lt;0 表示还在读取 */
    private void showStats(int dirs, int files, String size) {
        if (statBox == null) return;
        statBox.removeAllViews();
        if (dirs < 0) {
            statBox.addView(infoPill("读取中…", Palette.dim(act)));
            return;
        }
        boolean any = false;
        if (dirs > 0) {
            statBox.addView(statPill(FileGlyph.DIR, String.valueOf(dirs)));
            any = true;
        }
        if (files > 0) {
            View p = statPill(FileGlyph.TEXT, String.valueOf(files));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(act, 6);
            statBox.addView(p, lp);
            any = true;
        }
        if (size != null) {
            TextView p = infoPill(size, Palette.dim(act));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(act, 6);
            statBox.addView(p, lp);
            any = true;
        }
        if (!any) statBox.addView(infoPill("空", Palette.dim(act)));
    }

    /** 空态：大图标 + 一行说明，比原来干巴巴一行字好认 */
    private View emptyState(String icon, String title, String desc, int titleColor) {
        LinearLayout box = Ui.col(act);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Ui.dp(act, 56), 0, Ui.dp(act, 24));

        TextView ic = Ui.text(act, icon, 32f, Palette.dim(act));
        ic.setGravity(Gravity.CENTER);
        ic.setAlpha(0.55f);
        box.addView(ic);

        TextView t = Ui.text(act, title, 13.5f, titleColor);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(act, 10);
        box.addView(t, tlp);

        if (desc != null) {
            TextView d = Ui.text(act, desc, 11.5f, Palette.dim(act));
            d.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Ui.dp(act, 5);
            box.addView(d, dlp);
        }
        return box;
    }

    /** 带时间预算的目录总大小；超时就不算了，返回的已统计部分是下界 */
    private void dirSizeBudget(File f, long deadline, long[] out) {
        if (System.currentTimeMillis() > deadline) {
            out[1] = 1;
            return;
        }
        if (f == null) return;
        if (f.isFile()) {
            out[0] += f.length();
            return;
        }
        File[] fs;
        try {
            fs = f.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (fs == null) return;
        for (File c : fs) {
            if (System.currentTimeMillis() > deadline) {
                out[1] = 1;
                return;
            }
            if (c == null) continue;
            if (c.isDirectory()) dirSizeBudget(c, deadline, out);
            else out[0] += c.length();
        }
    }

    private String displayPath(File f) {
        String p = f.getAbsolutePath();
        String r = root.getAbsolutePath();
        if (p.equals(r)) return "工作区 /";
        if (p.startsWith(r)) return "工作区 /" + p.substring(r.length() + 1);
        return p;
    }

    /** 半透明色：给图标块、选中态用 */
    private static int soft(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** 类型色：目录跟随主题强调色，其余固定色，深色/纯黑主题下都够亮 */
    private int tintOf(int kind) {
        switch (kind) {
            case FileGlyph.DIR:     return Palette.accent(act);
            case FileGlyph.CODE:    return 0xFF5AD8F0;
            case FileGlyph.TEXT:    return 0xFF8FB4FF;
            case FileGlyph.IMAGE:   return 0xFFB388FF;
            case FileGlyph.MEDIA:   return 0xFFFF8AB0;
            case FileGlyph.ARCHIVE: return 0xFFFFD166;
            case FileGlyph.APK:     return 0xFFFFA85C;
            case FileGlyph.DOC:     return 0xFFFF7B72;
            default:                return Palette.dim(act);
        }
    }

    private View buildRow(final File f, java.util.Map<String, Integer> counts) {
        LinearLayout row = Ui.row(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.surface(act));
        bg.setCornerRadius(Ui.dp(act, 12));
        bg.setStroke(Math.max(1, Ui.dp(act, 0.8f)), Palette.stroke(act));
        row.setBackground(Ui.pressable(act, bg));
        int p = Ui.dp(act, 10);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(act, 8);
        row.setLayoutParams(rlp);

        // 图标块：矢量描边图标 + 类型色底纹，替代原来的 emoji（深色底上会糊、且各 ROM 字形不一）
        int kind = FileGlyph.kindOf(f.getName(), f.isDirectory());
        int tint = tintOf(kind);
        ImageView icon = new ImageView(act);
        icon.setImageDrawable(new FileGlyph(kind, tint));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ip = Ui.dp(act, 9);
        icon.setPadding(ip, ip, ip, ip);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(soft(tint, 0x24));
        ib.setCornerRadius(Ui.dp(act, 11));
        icon.setBackground(ib);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(act, 38), Ui.dp(act, 38)));

        LinearLayout mid = Ui.col(act);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(act, 12);
        TextView name = Ui.text(act, f.getName(), 13.5f, Palette.text(act));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Integer n = f.isDirectory() ? counts.get(f.getAbsolutePath()) : null;
        TextView meta = Ui.text(act, f.isDirectory()
                ? "文件夹" + (n == null ? "" : " · " + n + " 项")
                : Ui.humanSize(f.length()) + " · " + timeOf(f.lastModified()), 11f, Palette.dim(act));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(act, 3);
        mid.addView(name);
        mid.addView(meta, mlp);
        row.addView(mid, mp);

        // 只有目录带箭头，明确「点进去」而不是「打开文件」
        TextView arrow = Ui.text(act, f.isDirectory() ? "›" : "", 17f, Palette.stroke(act));
        arrow.setPadding(Ui.dp(act, 6), 0, 0, 0);
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (f.isDirectory()) openDir(f);
                else openFile(f);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showFileMenu(f);
                return true;
            }
        });
        return row;
    }

    private String timeOf(long ts) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(ts));
    }

    private int countChildren(File dir) {
        String[] l = dir.list();
        return l == null ? 0 : l.length;
    }

    private long dirSize(File f) {
        if (f.isFile()) return f.length();
        File[] fs = f.listFiles();
        if (fs == null) return 0;
        long t = 0;
        for (File c : fs) t += c.isDirectory() ? dirSize(c) : c.length();
        return t;
    }

    /** 上一层是否还在允许浏览的范围里（工作区 / 手机存储 / 产物目录） */
    private boolean inScope(File dir) {
        if (dir == null) return false;
        String p = dir.getAbsolutePath();
        if (p.startsWith(root.getAbsolutePath())) return true;
        String sd = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        // /sdcard 自身可以停，但不许爬上 /storage/emulated 乃至 /
        return p.equals(sd) || p.startsWith(sd + "/");
    }

    public void openDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        cur = dir;
        refresh();
    }

    // ------------------------------------------------------------ 文件操作

    private void openFile(File f) {
        if (f.length() > 2 * 1024 * 1024) {
            Toast.makeText(act, "文件较大（" + Ui.humanSize(f.length()) + "），建议用系统应用打开", Toast.LENGTH_SHORT).show();
            openExternal(f);
            return;
        }
        String content;
        try {
            content = readText(f);
        } catch (Exception e) {
            openExternal(f);
            return;
        }
        if (content == null) {
            openExternal(f);
            return;
        }
        showEditor(f, content);
    }

    private void showEditor(final File f, String content) {
        final EditText et = new EditText(act);
        et.setText(content);
        et.setTextSize(12.5f);
        et.setTypeface(Typeface.MONOSPACE);
        et.setTextColor(0xFFE8EDF6);
        et.setBackgroundColor(0xFF0B0E14);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int p = Ui.dp(act, 10);
        et.setPadding(p, p, p, p);

        ScrollView sv = new ScrollView(act);
        sv.addView(et, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(act)
                .setTitle(f.getName())
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            writeText(f, et.getText().toString());
                            Toast.makeText(act, "已保存", Toast.LENGTH_SHORT).show();
                            refresh();
                        } catch (Exception e) {
                            Toast.makeText(act, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("导出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        exportFile(f);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFileMenu(final File f) {
        boolean isApk = !f.isDirectory()
                && f.getName().toLowerCase(java.util.Locale.US).endsWith(".apk");
        final String[] items;
        if (f.isDirectory()) {
            items = new String[]{"打开", "重命名", "删除"};
        } else if (isApk) {
            items = new String[]{"安装 / 用系统应用打开", "📦 送去反编译", "编辑", "导出到…", "重命名", "删除"};
        } else {
            items = new String[]{"编辑", "用系统应用打开", "导出到…", "重命名", "删除"};
        }
        new AlertDialog.Builder(act)
                .setTitle(f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String it = items[which];
                        if (it.startsWith("打开")) openDir(f);
                        else if (it.startsWith("📦")) com.nebula.aichat.apk.ApkWorkbenchPanel.showFor(act, f);
                        else if (it.startsWith("安装") || it.equals("用系统应用打开")) openExternal(f);
                        else if (it.startsWith("编辑")) openFile(f);
                        else if (it.startsWith("导出到")) exportFile(f);
                        else if (it.startsWith("重命名")) renameDialog(f);
                        else if (it.startsWith("删除")) confirmDelete(f);
                    }
                })
                .show();
    }

    private void showLocationMenu() {
        final File sd = android.os.Environment.getExternalStorageDirectory();
        File prod = null;
        try {
            prod = com.nebula.aichat.apk.ArtifactStore.writableDir(act);
        } catch (Throwable ignored) {
        }
        final File[] targets = {root, sd, prod};
        String here = cur == null ? "" : cur.getAbsolutePath();

        String[] icons = {"🗂", "📱", "📦"};
        String[] titles = {"沙盒工作区", "手机存储", "AgenCode 产物"};
        String[] descs = {
                root.getAbsolutePath(),
                sd == null ? "当前不可用" : sd.getAbsolutePath(),
                prod == null ? "当前不可用，先跑一次构建" : prod.getAbsolutePath()
        };

        List<Picker.Opt> opts = new ArrayList<Picker.Opt>();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            final File t = targets[i];
            boolean isCur = t != null && here.equals(t.getAbsolutePath());
            opts.add(new Picker.Opt(icons[i], titles[i], descs[i], isCur, new Runnable() {
                @Override
                public void run() {
                    if (t == null || !t.isDirectory()) {
                        Toast.makeText(act, idx == 1
                                ? "读不到手机存储，请先开启「所有文件访问」"
                                : "目录还不可用", Toast.LENGTH_LONG).show();
                        return;
                    }
                    openDir(t);
                }
            }));
        }
        Picker.show(act, "跳转到", displayPath(cur), opts);
    }

    private void showNewDialog() {
        List<Picker.Opt> opts = new ArrayList<Picker.Opt>();
        opts.add(new Picker.Opt("📁", "新建文件夹", "在当前目录建一个空文件夹", new Runnable() {
            @Override
            public void run() {
                createNameDialog(true, "untitled", "");
            }
        }));
        opts.add(new Picker.Opt("📄", "新建文本文件", "note.txt", new Runnable() {
            @Override
            public void run() {
                createNameDialog(false, "note.txt", "");
            }
        }));
        opts.add(new Picker.Opt("📝", "新建 Markdown", "note.md，自带一个标题", new Runnable() {
            @Override
            public void run() {
                createNameDialog(false, "note.md", "# 标题\n\n");
            }
        }));
        opts.add(new Picker.Opt("⌨️", "新建 shell 脚本", "script.sh，带 shebang", new Runnable() {
            @Override
            public void run() {
                createNameDialog(false, "script.sh", "#!/system/bin/sh\n\n");
            }
        }));
        opts.add(new Picker.Opt("📥", "从手机导入…", "选一个文件复制到当前目录", new Runnable() {
            @Override
            public void run() {
                importFile();
            }
        }));
        Picker.show(act, "新建 / 导入", displayPath(cur), opts);
    }

    private void createNameDialog(final boolean dir, String defName, final String initial) {
        final EditText et = new EditText(act);
        et.setText(defName);
        et.setTextColor(0xFFE8EDF6);
        et.setSelectAllOnFocus(true);
        int p = Ui.dp(act, 16);
        et.setPadding(p, Ui.dp(act, 8), p, Ui.dp(act, 8));

        new AlertDialog.Builder(act)
                .setTitle(dir ? "新建文件夹" : "新建文件")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        File target = new File(cur, name);
                        try {
                            if (dir) {
                                if (!target.mkdirs()) throw new Exception("创建失败");
                            } else {
                                if (!target.createNewFile()) throw new Exception("文件已存在");
                                if (!initial.isEmpty()) writeText(target, initial);
                            }
                            refresh();
                        } catch (Exception e) {
                            Toast.makeText(act, "失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameDialog(final File f) {
        final EditText et = new EditText(act);
        et.setText(f.getName());
        et.setSelectAllOnFocus(true);
        et.setTextColor(0xFFE8EDF6);
        int p = Ui.dp(act, 16);
        et.setPadding(p, Ui.dp(act, 8), p, Ui.dp(act, 8));

        new AlertDialog.Builder(act)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        File target = new File(f.getParentFile(), name);
                        if (!f.renameTo(target)) {
                            Toast.makeText(act, "重命名失败", Toast.LENGTH_SHORT).show();
                        }
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(act)
                .setTitle("删除")
                .setMessage("确定删除「" + f.getName() + "」？" + (f.isDirectory() ? "\n（包含其中所有内容）" : ""))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        deleteRecursive(f);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void openExternal(File f) {
        try {
            String mime = f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")
                    ? "application/vnd.android.package-archive" : "*/*";
            Intent i = new Intent(Intent.ACTION_VIEW);
            // 私有目录必须用 FileProvider 才能共享给其他应用，这里退化为提示
            Toast.makeText(act, "私有目录文件无法直接交给系统应用，请先「导出到…」", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(act, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void importFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            act.startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(act, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    public void exportFile(File f) {
        if (!f.isFile()) {
            Toast.makeText(act, "只能导出文件", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExport = f;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_TITLE, f.getName());
        try {
            act.startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(act, "无法打开保存对话框", Toast.LENGTH_SHORT).show();
        }
    }

    /** 由 MainActivity 转发 */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_IMPORT) {
                String name = queryName(uri);
                if (name == null || name.isEmpty()) name = "imported_" + System.currentTimeMillis();
                File target = new File(cur, name);
                InputStream is = act.getContentResolver().openInputStream(uri);
                if (is == null) throw new Exception("无法读取");
                FileOutputStream fos = new FileOutputStream(target);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                is.close();
                Toast.makeText(act, "已导入 " + name + "（" + Ui.humanSize(target.length()) + "）",
                        Toast.LENGTH_SHORT).show();
                refresh();
            } else if (requestCode == REQ_EXPORT && pendingExport != null) {
                OutputStream os = act.getContentResolver().openOutputStream(uri);
                if (os == null) throw new Exception("无法写入");
                InputStream is = new java.io.FileInputStream(pendingExport);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                is.close();
                os.close();
                Toast.makeText(act, "已导出", Toast.LENGTH_SHORT).show();
                pendingExport = null;
            }
        } catch (Exception e) {
            Toast.makeText(act, "操作失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String queryName(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = act.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String p = uri.getPath();
        return p == null ? null : new File(p).getName();
    }

    // ---------------------------------------------------------- 读写工具

    public static String readText(File f) throws Exception {
        if (!f.isFile()) return null;
        InputStream is = new java.io.FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        byte[] data = bos.toByteArray();
        for (byte b : data) {
            if (b == 0) return null; // 二进制
        }
        String s = new String(data, "UTF-8");
        // 简单判断替换字符过多视为非文本
        int bad = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\uFFFD') bad++;
        if (s.length() > 0 && bad > s.length() / 20) return null;
        return s;
    }

    public static void writeText(File f, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(content.getBytes("UTF-8"));
        fos.flush();
        fos.close();
    }
}
