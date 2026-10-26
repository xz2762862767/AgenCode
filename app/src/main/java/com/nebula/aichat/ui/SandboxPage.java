package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
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
    private TextView tvPath;
    private TextView tvSummary;
    private ScrollView scroll;

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
        rootView.setBackgroundColor(0xFF0F1218);

        LinearLayout head = Ui.row(act);
        head.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8));

        LinearLayout info = Ui.col(act);
        tvPath = Ui.text(act, "", 12f, 0xFFE8EDF6);
        tvPath.setTypeface(Typeface.DEFAULT_BOLD);
        tvPath.setSingleLine(true);
        tvPath.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tvSummary = Ui.text(act, "", 11f, 0xFF8B97A9);
        info.addView(tvPath);
        info.addView(tvSummary);
        head.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 位置切换收进一个下拉：沙盒 / 手机存储 / 星云产物三种落点，
        // 不再在工具行里跟「APK 工作台」抢同一个「产物目录」入口
        TextView btnLoc = Ui.ghostChip(act, "位置 ▾", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLocationMenu();
            }
        });
        head.addView(btnLoc);

        TextView btnUp = Ui.ghostChip(act, "上级", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File parent = cur.getParentFile();
                if (parent != null && inScope(parent)) {
                    openDir(parent);
                } else {
                    openDir(root);
                }
            }
        });
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        upLp.leftMargin = Ui.dp(act, 6);
        head.addView(btnUp, upLp);

        TextView btnNew = Ui.ghostChip(act, "新建", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewDialog();
            }
        });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.leftMargin = Ui.dp(act, 6);
        head.addView(btnNew, np);

        rootView.addView(head);
        rootView.addView(Ui.divider(act));

        rootView.addView(Ui.divider(act));

        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        listBox = Ui.col(act);
        int p = Ui.dp(act, 8);
        listBox.setPadding(p, p, p, Ui.dp(act, 24));
        scroll.addView(listBox);
        rootView.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
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
        tvPath.setText(displayPath(dir));
        listBox.removeAllViews();
        tvSummary.setText("读取中…");
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
            listBox.addView(Ui.text(act, "无法读取该目录（权限限制）", 13f, 0xFFFF8A80));
            tvSummary.setText("");
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
            TextView empty = Ui.text(act, "空目录\n点右上「新建」添加文件或文件夹", 13f, 0xFF8B97A9);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 40), 0, 0);
            listBox.addView(empty);
        }

        int shown = Math.min(list.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            listBox.addView(buildRow(list.get(i), counts));
        }
        if (list.size() > shown) {
            TextView more = Ui.text(act, "还有 " + (list.size() - shown)
                    + " 项未显示（用终端 ls 查看更完整）", 12f, 0xFF8B97A9);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
            listBox.addView(more);
        }
        String total = Ui.humanSize(size[0]) + (size[1] == 1 ? "+" : "");
        tvSummary.setText(dirs + " 个目录 · " + fileCount + " 个文件 · " + total);
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

    private View buildRow(final File f, java.util.Map<String, Integer> counts) {
        LinearLayout row = Ui.row(act);
        row.setBackground(Ui.roundRect(act, 0xFF161B24, 0, 10f));
        int p = Ui.dp(act, 10);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(act, 6);
        row.setLayoutParams(rlp);

        TextView icon = Ui.text(act, f.isDirectory() ? "📁" : iconOf(f.getName()), 16f, 0xFFFFFFFF);
        row.addView(icon);

        LinearLayout mid = Ui.col(act);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(act, 10);
        TextView name = Ui.text(act, f.getName(), 13.5f, 0xFFE8EDF6);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Integer n = f.isDirectory() ? counts.get(f.getAbsolutePath()) : null;
        TextView meta = Ui.text(act, f.isDirectory()
                ? "目录" + (n == null ? "" : " · " + n + " 项")
                : Ui.humanSize(f.length()) + " · " + timeOf(f.lastModified()), 11f, 0xFF8B97A9);
        mid.addView(name);
        mid.addView(meta);
        row.addView(mid, mp);

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

    private String iconOf(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".apk")) return "📦";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".webp")) return "🖼";
        if (n.endsWith(".md")) return "📝";
        if (n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".java")
                || n.endsWith(".gradle") || n.endsWith(".sh") || n.endsWith(".py")) return "📄";
        if (n.endsWith(".zip") || n.endsWith(".jar")) return "🗜";
        return "📃";
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
        String[] items = new String[3];
        items[0] = "🗂 沙盒工作区" + (here.equals(root.getAbsolutePath()) ? "　·　当前" : "");
        items[1] = "📱 手机存储" + (sd != null && here.equals(sd.getAbsolutePath()) ? "　·　当前" : "");
        items[2] = "📦 星云产物" + (prod != null && here.equals(prod.getAbsolutePath()) ? "　·　当前" : "");
        new AlertDialog.Builder(act)
                .setTitle("跳转到")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        File t = targets[which];
                        if (t == null || !t.isDirectory()) {
                            Toast.makeText(act, which == 1
                                    ? "读不到手机存储，请先开启「所有文件访问」"
                                    : "目录还不可用", Toast.LENGTH_LONG).show();
                            return;
                        }
                        openDir(t);
                    }
                })
                .show();
    }

    private void showNewDialog() {
        final String[] items = {"新建文件夹", "新建文本文件", "新建 Markdown", "新建 shell 脚本", "导入文件…"};
        new AlertDialog.Builder(act)
                .setTitle("新建 / 导入")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) createNameDialog(true, "untitled", "");
                        else if (which == 1) createNameDialog(false, "note.txt", "");
                        else if (which == 2) createNameDialog(false, "note.md", "# 标题\n\n");
                        else if (which == 3) createNameDialog(false, "script.sh", "#!/system/bin/sh\n\n");
                        else importFile();
                    }
                })
                .show();
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
