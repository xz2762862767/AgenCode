package com.nebula.aichat.apk;

import com.nebula.aichat.ui.Palette;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nebula.aichat.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * APK 修改工作台（手动模式）。
 * <p>
 * 对话里模型也能调同样的能力（apk_unpack / apk_repack …），
 * 这个面板用于模型不配合或想自己动手时兜底：
 * 选 APK → 反编译 → 直接改 projects/xxx 里的文件 → 一键回编译签名。
 */
public class ApkWorkbenchPanel {

    private static ApkWorkbenchPanel current;
    private static final int REQ_SAF_APK = 9101;

    /** 再开一次前先收掉旧面板，否则连点两下会叠出两个同样的窗 */
    private static void closeExisting() {
        if (current != null && current.dialog != null && current.dialog.isShowing()) {
            try {
                current.dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        current = null;
    }

    public static void show(Activity act) {
        closeExisting();
        ApkWorkbenchPanel p = new ApkWorkbenchPanel(act);
        current = p;
        p.build();
    }

    /** 别处（如沙箱里长按 APK）带着文件直接进来，省掉再选一次 */
    public static void showFor(Activity act, File apk) {
        closeExisting();
        ApkWorkbenchPanel p = new ApkWorkbenchPanel(act);
        current = p;
        p.build();
        if (apk != null && apk.isFile()) {
            p.startUnpack(apk);
        }
    }

    public static void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (current != null) current.handleResult(requestCode, resultCode, data);
    }

    private final Activity act;
    private Dialog dialog;
    private LinearLayout projBox;
    private TextView logView;
    private TextView envView;
    private ScrollView logScroll;
    private CheckBox fastBox;
    private volatile boolean running;

    private ApkWorkbenchPanel(Activity act) {
        this.act = act;
    }

    private void build() {
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 16);
        box.setPadding(p, p, p, Ui.dp(act, 12));
        sv.addView(box);

        envView = Ui.text(act, envText(), 12f, 0xFF8A8F98);
        box.addView(envView);
        box.addView(Ui.spacer(act, 10));

        LinearLayout ops = Ui.row(act);
        ops.addView(Ui.primaryChip(act, "📦 选择 APK", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApk();
            }
        }));
        ops.addView(gapW(8));
        ops.addView(Ui.ghostChip(act, "⚡ 补齐 Java", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installJava();
            }
        }));
        box.addView(ops);

        LinearLayout ops2 = Ui.row(act);
        ops2.addView(Ui.ghostChip(act, "📥 安装 APK", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBuiltApk();
            }
        }));
        ops2.addView(gapW(8));
        ops2.addView(Ui.ghostChip(act, "🔓 存储权限", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestStorage();
            }
        }));
        ops2.addView(gapW(8));
        ops2.addView(Ui.ghostChip(act, "📂 系统里打开", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showArtifactDir();
            }
        }));
        box.addView(Ui.spacer(act, 6));
        box.addView(ops2);
        box.addView(Ui.spacer(act, 8));

        fastBox = new CheckBox(act);
        fastBox.setText("快速模式（只解资源不解 dex，快 3~4 倍）");
        fastBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        fastBox.setTextColor(Palette.c(0xFF8A8F98));
        fastBox.setChecked(true);
        box.addView(fastBox);

        box.addView(Ui.spacer(act, 12));
        box.addView(Ui.sectionTitle(act, "已解包工程"));
        projBox = Ui.col(act);
        box.addView(projBox);
        box.addView(Ui.spacer(act, 12));

        box.addView(Ui.sectionTitle(act, "运行日志"));
        logView = Ui.text(act, "", 11.5f, 0xFF8A8F98);
        logView.setLineSpacing(Ui.dp(act, 2), 1.25f);
        logScroll = new ScrollView(act);
        logScroll.addView(logView);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 170));
        box.addView(logScroll, llp);

        dialog = new AlertDialog.Builder(act)
                .setTitle("APK 工作台")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        // 面板关掉就放开静态引用：它握着 Activity，不放开会一直泄漏下去
        dialog.setOnDismissListener(d -> {
            if (current == ApkWorkbenchPanel.this) current = null;
        });
        dialog.show();
        refreshProjects();
    }

    // ------------------------------------------------------------ 环境状态

    private String envText() {
        StringBuilder sb = new StringBuilder();
        sb.append("工作目录：").append(ApkWorkbench.root(act).getAbsolutePath()).append('\n');
        boolean jar = new File(ApkWorkbench.root(act), "bin/apktool.jar").exists();
        sb.append(jar ? "✓ apktool 已就绪" : "· apktool 首次解包时自动释放（约 14MB）");
        sb.append('\n');
        String issue = ApkWorkbench.javaIssue(act);
        sb.append("产物目录：").append(ArtifactStore.describe(act));
        sb.append('\n');
        sb.append(ApkExporter.canWritePublicDir(act)
                ? "✓ 存储权限就绪（产物直接写进「AgenCode」）"
                : "⚠ 没有「所有文件访问」权限：产物会写进「下载/AgenCode」，或退回应用私有目录");
        sb.append('\n');
        sb.append(issue == null ? "✓ Java 运行时就绪（可以解包了）" : "⚠ " + issue
                + (issue.contains("Linux") ? "（先去「环境」页安装内置 Linux）"
                : "（点「准备环境」自动下载安装，约 60MB，失败会写日志到 /sdcard/Download/nebula-java.log）"));
        return sb.toString();
    }

    /** 把工程整个复制进 AgenCode/工程/，体积大会先问一声 */
    private void exportProject(final String name) {
        final File src = new File(new File(ApkWorkbench.root(act), "projects"), name);
        if (!src.isDirectory()) {
            toast("工程不存在：" + name);
            return;
        }
        final long[] size = ArtifactStore.sizeOf(src);
        new AlertDialog.Builder(act)
                .setTitle("导出工程到「AgenCode」")
                .setMessage(name + "\n" + size[0] + " 个文件，" + ApkWorkbench.fmtSize(size[1])
                        + "\n\n会整个复制到：" + ArtifactStore.writableSub(act, ArtifactStore.SUB_PROJECT).getAbsolutePath()
                        + "\n（只是副本，原工程不动）")
                .setPositiveButton("开始复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doExportProject(name, src);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doExportProject(final String name, final File src) {
        toast("正在复制工程…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String m;
                try {
                    File dst = ArtifactStore.exportProject(act, src);
                    m = "✅ 已导出：" + dst.getAbsolutePath();
                } catch (Throwable t) {
                    m = "⚠ 导出失败：" + shortMsg(new Exception(t));
                }
                final String msg = m;
                ui(new Runnable() {
                    @Override
                    public void run() {
                        log(msg);
                        toast(msg.startsWith("✅") ? "工程已导出到「AgenCode」" : "导出失败，见日志");
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ 产物目录

    /** 「AgenCode」在哪、里面有什么、怎么打开 */
    private void showArtifactDir() {
        final File dir = ArtifactStore.writableDir(act);
        boolean pub = ApkExporter.canWritePublicDir(act);
        StringBuilder sb = new StringBuilder();
        sb.append(pub ? "公共目录：" : "目录：").append(dir.getAbsolutePath()).append('\n');
        if (!pub) {
            sb.append("（本机没开「所有文件访问」，当前写的是应用私有目录；").append('\n')
                    .append("  开启后产物会改存到 ").append(ArtifactStore.publicDir().getAbsolutePath()).append("）\n");
        }
        sb.append('\n');
        String[] subs = {ArtifactStore.SUB_APK, ArtifactStore.SUB_PROJECT, ArtifactStore.SUB_LOG};
        String[] desc = {"打包签名后的安装包", "反编译出来的可编辑工程", "构建日志"};
        for (int i = 0; i < subs.length; i++) {
            File d = new File(dir, subs[i]);
            long[] n = ArtifactStore.sizeOf(d);
            sb.append("· ").append(subs[i]).append("/  ").append(desc[i]);
            if (n[0] > 0) sb.append("（").append(n[0]).append(" 个文件，").append(ApkWorkbench.fmtSize(n[1])).append("）");
            sb.append('\n');
        }
        new AlertDialog.Builder(act)
                .setTitle("AgenCode")
                .setMessage(sb.toString())
                .setPositiveButton("打开文件夹", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openFolder(dir);
                    }
                })
                .setNeutralButton("复制路径", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("path", dir.getAbsolutePath()));
                                toast("路径已复制");
                            }
                        } catch (Throwable t) {
                            toast("复制失败");
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 尽量用系统文件管理器打开这个目录；打不开就告诉用户去哪找 */
    private void openFolder(File dir) {
        try {
            android.net.Uri uri = android.net.Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3A"
                            + android.net.Uri.encode(ArtifactStore.NAME));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "vnd.android.document/directory");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setType("resource/folder");
                act.startActivity(i);
            } catch (Throwable t2) {
                toast("这台手机没有能打开文件夹的应用，路径：" + dir.getAbsolutePath());
            }
        }
    }

    // ------------------------------------------------------------ 选择 / 解包

    private void pickApk() {
        final List<File> apks = scanApks();
        if (apks.isEmpty()) {
            boolean noPerm = !ApkExporter.canWritePublicDir(act);
            new AlertDialog.Builder(act)
                    .setTitle("没扫描到 APK")
                    .setMessage(noPerm
                            ? "当前没有「所有文件访问」权限，应用读不到「下载」目录里的文件，所以列表是空的。\n\n"
                              + "两个办法：\n1) 用文件选择器手动挑一个 APK（不需要任何权限，推荐）\n"
                              + "2) 去开启「所有文件访问」，之后会自动列出下载目录里的所有 APK"
                            : "「下载」目录里没有 .apk 文件。\n\n可以先放一个安装包到「下载」，"
                              + "或者用文件选择器从其他位置挑一个。")
                    .setPositiveButton("用文件选择器选", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            pickFileSaf();
                        }
                    })
                    .setNeutralButton(noPerm ? "开存储权限" : "知道信息", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            if (!ApkExporter.canWritePublicDir(act)) requestStorage();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        String[] names = new String[apks.size()];
        for (int i = 0; i < apks.size(); i++) {
            File f = apks.get(i);
            names[i] = f.getName() + "  (" + ApkWorkbench.fmtSize(f.length()) + ")";
        }
        new AlertDialog.Builder(act)
                .setTitle("选择要修改的 APK")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startUnpack(apks.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<File> scanApks() {
        List<File> out = new ArrayList<>();
        // 公共产物目录 + 私有回退目录（没开「所有文件访问」时产物落在这儿）
        String[] dirs = {
                ArtifactStore.publicSub(ArtifactStore.SUB_APK).getAbsolutePath(),
                ArtifactStore.writableSub(act, ArtifactStore.SUB_APK).getAbsolutePath(),
                "/sdcard/Download", "/sdcard/Documents", "/sdcard"};
        for (String d : dirs) {
            File[] fs = new File(d).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".apk")
                        && f.length() > 4096 && !out.contains(f)) {
                    out.add(f);
                }
            }
        }
        // 沙箱/本地工作区里刚构建出来、还来不及同步的 APK
        pickApk(new File(act.getFilesDir(), "sandbox"), out, 0);
        try {
            pickApk(com.nebula.aichat.linux.LinuxEnv.get(act).exchangeDir(), out, 0);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 在工程目录里找 APK（限两层，别把整个工作区翻一遍） */
    private void pickApk(File dir, List<File> out, int depth) {
        if (dir == null || depth > 2 || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                pickApk(f, out, depth + 1);
            } else if (f.getName().toLowerCase(Locale.US).endsWith(".apk")
                    && f.length() > 4096 && !out.contains(f)) {
                out.add(f);
            }
        }
    }

    private void startUnpack(final File apk) {
        if (running) {
            toast("正在处理中，请等当前任务结束");
            return;
        }
        final boolean fast = fastBox != null && fastBox.isChecked();
        final String name = apk.getName().replaceAll("(?i)\\.apk$", "");
        log("▶ 反编译 " + apk.getName() + (fast ? "（快速模式）" : "（完整模式）"));
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String dir = ApkWorkbench.unpack(act, apk, name, fast, progress());
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            log("✅ 工程已就绪：" + dir);
                            log("   改完文件后点下面的「编译签名」");
                            refreshProjects();
                        }
                    });
                } catch (final Exception e) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            log("❌ 反编译失败：" + shortMsg(e));
                        }
                    });
                } finally {
                    running = false;
                }
            }
        }, "apk-unpack").start();
    }

    // ------------------------------------------------------------ 回编译

    private void repack(final String name) {
        if (running) {
            toast("正在处理中，请等当前任务结束");
            return;
        }
        log("▶ 回编译 " + name);
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File signed = ApkWorkbench.repack(act, name, progress());
                    String note;
                    try {
                        String where = ApkExporter.exportToDownload(act, signed, signed.getName());
                        note = "✅ 已导出到「AgenCode」：" + where;
                    } catch (Exception e) {
                        note = "⚠ 没能导出到「AgenCode」：" + shortMsg(e)
                                + "\n   文件仍在应用内：" + signed.getAbsolutePath();
                    }
                    final String tailNote = note;
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            log("✅ 已签名：" + signed.getAbsolutePath());
                            log(tailNote);
                            showResult(signed);
                        }
                    });
                } catch (final Exception e) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            log("❌ 回编译失败：" + shortMsg(e));
                        }
                    });
                } finally {
                    running = false;
                }
            }
        }, "apk-repack").start();
    }

    private void installJava() {
        if (running) {
            toast("正在处理中");
            return;
        }
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String issue = ApkWorkbench.installJava(act, progress());
                ui(new Runnable() {
                    @Override
                    public void run() {
                        log(issue == null ? "✅ Java 安装完成，现在可以解包了" : "❌ " + issue);
                        if (envView != null) envView.setText(envText());
                    }
                });
                running = false;
            }
        }, "apk-java").start();
    }

    // ------------------------------------------------------------ SAF 选包（免权限）

    private void pickFileSaf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        try {
            act.startActivityForResult(i, REQ_SAF_APK);
        } catch (Throwable t) {
            toast("打不开文件选择器");
        }
    }

    private void handleResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_SAF_APK) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        try {
            String name = "picked.apk";
            android.database.Cursor cur = act.getContentResolver().query(
                    data.getData(), null, null, null, null);
            if (cur != null) {
                try {
                    int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && cur.moveToFirst() && cur.getString(idx) != null) {
                        name = cur.getString(idx);
                    }
                } finally {
                    cur.close();
                }
            }
            File tmp = new File(act.getCacheDir(), name);
            java.io.InputStream is = act.getContentResolver().openInputStream(data.getData());
            if (is == null) throw new java.io.IOException("读不到文件内容");
            java.io.FileOutputStream os = new java.io.FileOutputStream(tmp, false);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } finally {
                try { is.close(); } catch (Exception ignored) { }
                try { os.close(); } catch (Exception ignored) { }
            }
            if (tmp.length() < 4096) {
                toast("选中的文件太小，不像 APK");
                return;
            }
            if (!name.toLowerCase(Locale.US).endsWith(".apk")) {
                File renamed = new File(act.getCacheDir(), "picked.apk");
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(renamed);
                tmp = renamed;
            }
            log("· 已选：" + tmp.getName() + "（" + ApkWorkbench.fmtSize(tmp.length()) + "）");
            startUnpack(tmp);
        } catch (Throwable t) {
            toast("读取失败：" + shortMsg(new Exception(t)));
        }
    }

    // ------------------------------------------------------------ 产物：导出 / 体检 / 安装

    /** 打包完成后直接给出可点的结果，而不是丢一行日志让人自己去翻目录 */
    private void showResult(final File apk) {
        final ApkInstaller.Check ck = ApkInstaller.check(act, apk);
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(apk.getName()).append('\n');
        sb.append("大小：").append(ApkWorkbench.fmtSize(apk.length())).append('\n');
        for (String n : ck.notes) sb.append("· ").append(n).append('\n');
        if (!ck.problems.isEmpty()) {
            sb.append('\n');
            int i = 1;
            for (String p : ck.problems) {
                sb.append(i++).append(") ").append(p).append('\n');
            }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(ck.installable ? "✅ 打包完成，可以直接安装" : "✅ 打包完成，但系统会拒装")
                .setMessage(sb.toString().trim())
                .setNegativeButton("关闭", null);
        if (ck.installable) {
            b.setPositiveButton("立即安装", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    String err = ApkInstaller.installSession(act, apk,
                            new ApkInstaller.Callback() {
                                @Override
                                public void onResult(final boolean ok, final String message) {
                                    act.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            new AlertDialog.Builder(act)
                                                    .setTitle(ok ? "✅ 安装成功" : "❌ 装不上，原因在这里")
                                                    .setMessage(message)
                                                    .setPositiveButton("知道了", null)
                                                    .show();
                                        }
                                    });
                                }
                            });
                    if (err != null) toast(err);
                }
            });
        } else {
            b.setPositiveButton("安装授权设置", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    ApkInstaller.openInstallPermission(act);
                }
            });
        }
        b.show();
    }

    /** 从已生成的产物里挑一个安装（老产物也能装，不用重新编译） */
    private void pickBuiltApk() {
        File dir = ApkWorkbench.outDir(act);
        File[] fs = dir.listFiles();
        final List<File> list = new ArrayList<>();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".apk")
                        && !f.getName().endsWith("-unsigned.apk") && f.length() > 4096) {
                    list.add(f);
                }
            }
        }
        if (list.isEmpty()) {
            toast("还没有生成过 APK，先用「编译签名」打一个");
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).getName() + "  (" + ApkWorkbench.fmtSize(list.get(i).length()) + ")";
        }
        new AlertDialog.Builder(act)
                .setTitle("选择要安装的产物")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showResult(list.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 申请「所有文件访问」，没有它 File API 写不进公共下载目录 */
    private void requestStorage() {
        if (!ApkExporter.needAllFilesAccess()) {
            boolean ok = ApkExporter.canWritePublicDir(act);
            toast(ok ? "存储权限已就绪" : "当前系统不需要额外授权（Android 9 及以下走运行时权限）");
            if (!ok && android.os.Build.VERSION.SDK_INT < 30) {
                act.requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9001);
            }
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("需要「所有文件访问」权限")
                .setMessage("Android 11 起，向公共「下载」目录直接写文件必须授予这个权限。\n\n"
                        + "不开也能用：产物会通过 MediaStore 写进下载目录，只是可能延迟几秒才出现在文件管理器里。")
                .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.Intent i = new android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(i);
                        } catch (Throwable t) {
                            toast("请手动到 设置 → 应用 → AgenCode → 所有文件访问 打开");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 工程列表

    private void refreshProjects() {
        projBox.removeAllViews();
        final List<String> names = ApkWorkbench.listProjects(act);
        if (names.isEmpty()) {
            projBox.addView(Ui.text(act, "还没有解包的工程", 12.5f, 0xFF8A8F98));
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            LinearLayout row = Ui.row(act);
            row.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));
            row.addView(Ui.text(act, name, 13f, 0xFFFFFFFF), Ui.lpw(1f));
            row.addView(Ui.ghostChip(act, "编译签名", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    repack(name);
                }
            }));
            row.addView(gapW(6));
            row.addView(Ui.ghostChip(act, "导出", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportProject(name);
                }
            }));
            row.addView(gapW(6));
            row.addView(Ui.ghostChip(act, "删除", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(name);
                }
            }));
            projBox.addView(row);
        }
    }

    private void confirmDelete(final String name) {
        new AlertDialog.Builder(act)
                .setMessage("删除工程 " + name + "？（不会影响已导出的 APK）")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        deleteDir(ApkWorkbench.projectDir(act, name));
                        log("已删除工程 " + name);
                        refreshProjects();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteDir(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------------------------ 工具

    private ApkWorkbench.Progress progress() {
        return new ApkWorkbench.Progress() {
            @Override
            public void onStep(final String step) {
                ui(new Runnable() {
                    @Override
                    public void run() {
                        log("· " + step);
                    }
                });
            }

            @Override
            public void onLog(String line) {
                if (line == null || line.trim().isEmpty()) return;
                final String t = ApkWorkbench.tail(line, 300);
                ui(new Runnable() {
                    @Override
                    public void run() {
                        log("  " + t);
                    }
                });
            }
        };
    }

    private void log(final String line) {
        if (logView == null) return;
        logView.append(line + "\n");
        if (logScroll != null) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void ui(Runnable r) {
        if (act.isFinishing()) return;
        act.runOnUiThread(r);
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    private View gapW(int dp) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, dp), 1));
        return v;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 500 ? m.substring(0, 500) + "…" : m;
    }
}
