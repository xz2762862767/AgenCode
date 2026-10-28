package com.nebula.aichat.apk;

import android.app.Activity;
import android.app.AlertDialog;
import com.nebula.aichat.ui.NebAlert;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nebula.aichat.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import com.nebula.aichat.ui.Palette;

/** APK 工具面板：信息解析、换图标、重签名 */
public class ApkToolsPanel {

    public static final int REQ_PICK_APK = 0xA01;
    public static final int REQ_PICK_ICON = 0xA02;
    public static final int REQ_EXPORT = 0xA03;

    private static ApkToolsPanel current;

    private final Activity act;
    private Dialog dialog;
    private LinearLayout infoBox;
    private LinearLayout iconBox;
    private TextView statusView;
    private ProgressBar bar;

    private File workApk;
    private File workIcon;
    private File pendingExport;
    private Bitmap iconPreview;
    private Bitmap apkIconBitmap;
    private ApkTools.Info info;

    public static void show(Activity act) {
        ApkToolsPanel p = new ApkToolsPanel(act);
        current = p;
        p.run();
    }

    public static void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (current != null) current.handleResult(requestCode, resultCode, data);
    }

    private ApkToolsPanel(Activity act) {
        this.act = act;
    }

    private void run() {
        ScrollView sv = new ScrollView(act);
        LinearLayout root = Ui.col(act);
        int p = Ui.dp(act, 16);
        root.setPadding(p, p, p, Ui.dp(act, 12));
        sv.addView(root);

        root.addView(Ui.text(act, "选择要处理的 APK，可解析信息或替换图标后重新签名。"
                + "生成的 APK 会放在工作区 apk-out/ 目录。", 11.5f, Palette.c(0xFF8B97A9)));
        root.addView(Ui.spacer(act, 10));

        LinearLayout tools = Ui.row(act);
        tools.addView(Ui.primaryChip(act, "选择 APK", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile(REQ_PICK_APK, "application/vnd.android.package-archive");
            }
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(act, 8);
        tools.addView(Ui.chip(act, "选择新图标", Palette.c(0xFF1E3A6B), Palette.c(0xFF9DC0FF), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile(REQ_PICK_ICON, "image/*");
            }
        }), lp);
        root.addView(tools);

        root.addView(Ui.spacer(act, 12));
        infoBox = Ui.card(act);
        infoBox.addView(Ui.text(act, "尚未选择 APK", 12f, Palette.c(0xFF8B97A9)));
        root.addView(infoBox);

        root.addView(Ui.spacer(act, 10));
        iconBox = Ui.card(act);
        iconBox.addView(Ui.text(act, "尚未选择新图标", 12f, Palette.c(0xFF8B97A9)));
        root.addView(iconBox);

        root.addView(Ui.spacer(act, 12));
        bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar);

        statusView = Ui.text(act, "", 11f, Palette.c(0xFF8B97A9));
        statusView.setPadding(0, Ui.dp(act, 6), 0, 0);
        root.addView(statusView);

        root.addView(Ui.spacer(act, 12));
        LinearLayout actions = Ui.row(act);
        actions.addView(Ui.primaryChip(act, "换图标并重签名", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doReplace();
            }
        }));
        root.addView(actions);

        dialog = new NebAlert.Builder(act)
                .setTitle("APK 工具")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                if (current == ApkToolsPanel.this) current = null;
            }
        });
        dialog.show();
    }

    private void pickFile(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        if ("application/vnd.android.package-archive".equals(mime)) {
            i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
            i.setType("*/*");
        }
        try {
            act.startActivityForResult(i, req);
        } catch (Throwable t) {
            Toast.makeText(act, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == REQ_PICK_APK) {
            File f = copyToWork(uri, "input.apk");
            if (f == null) return;
            workApk = f;
            loadInfo();
        } else if (requestCode == REQ_PICK_ICON) {
            File f = copyToWork(uri, "icon.png");
            if (f == null) return;
            workIcon = f;
            iconPreview = BitmapFactory.decodeFile(f.getAbsolutePath());
            renderIcon();
        } else if (requestCode == REQ_EXPORT && pendingExport != null) {
            exportTo(uri, pendingExport);
            pendingExport = null;
        }
    }

    private File copyToWork(Uri uri, String name) {
        try {
            File dir = workDir();
            File f = new File(dir, name);
            if (f.exists()) f.delete();
            InputStream in = act.getContentResolver().openInputStream(uri);
            if (in == null) {
                Toast.makeText(act, "无法读取文件", Toast.LENGTH_SHORT).show();
                return null;
            }
            FileOutputStream fos = new FileOutputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            in.close();
            fos.close();
            return f;
        } catch (Exception e) {
            Toast.makeText(act, "复制失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private File workDir() {
        File dir = new File(act.getExternalFilesDir(null), "apk-work");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File outDir() {
        File dir = new File(act.getFilesDir(), "workspace/apk-out");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void loadInfo() {
        info = ApkTools.inspect(act, workApk);
        infoBox.removeAllViews();
        if (info == null) {
            infoBox.addView(Ui.text(act, "无法解析该文件，可能不是有效的 APK", 12f, Palette.c(0xFFFF8A80)));
            return;
        }
        LinearLayout head = Ui.row(act);
        ImageView iv = new ImageView(act);
        if (info.icon != null) {
            iv.setImageDrawable(info.icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(act, 44), Ui.dp(act, 44));
            ilp.rightMargin = Ui.dp(act, 10);
            head.addView(iv, ilp);
        }
        LinearLayout titleCol = Ui.col(act);
        TextView tv = Ui.text(act, info.label.isEmpty() ? info.pkgName : info.label, 14f, Palette.c(0xFFE8EDF6));
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleCol.addView(tv);
        titleCol.addView(Ui.text(act, info.pkgName, 11f, Palette.c(0xFF8B97A9)));
        head.addView(titleCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        infoBox.addView(head);
        infoBox.addView(Ui.spacer(act, 8));

        infoBox.addView(line("版本", info.versionName + " (" + info.versionCode + ")"));
        infoBox.addView(line("SDK", "min " + info.minSdk + " / target " + info.targetSdk));
        infoBox.addView(line("大小", Ui.humanSize(info.size)));
        infoBox.addView(line("Activity", info.activityCount + " 个"));
        infoBox.addView(line("权限", info.permissions.length + " 项"));

        if (info.permissions.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String perm : info.permissions) {
                String s = perm.startsWith("android.permission.")
                        ? perm.substring("android.permission.".length()) : perm;
                if (sb.length() > 0) sb.append("、");
                sb.append(s);
            }
            TextView permTv = Ui.text(act, sb.toString(), 10.5f, Palette.c(0xFF7C879B));
            permTv.setPadding(0, Ui.dp(act, 6), 0, 0);
            infoBox.addView(permTv);
        }
    }

    private View line(String k, String v) {
        LinearLayout r = Ui.row(act);
        r.setPadding(0, Ui.dp(act, 3), 0, Ui.dp(act, 3));
        TextView kt = Ui.text(act, k, 11.5f, Palette.c(0xFF8B97A9));
        r.addView(kt, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f));
        TextView vt = Ui.text(act, v, 12f, Palette.c(0xFFD5DEEA));
        r.addView(vt, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.66f));
        return r;
    }

    private void renderIcon() {
        iconBox.removeAllViews();
        if (iconPreview == null) {
            iconBox.addView(Ui.text(act, "图标读取失败", 12f, Palette.c(0xFFFF8A80)));
            return;
        }
        LinearLayout r = Ui.row(act);
        ImageView iv = new ImageView(act);
        iv.setImageBitmap(iconPreview);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(act, 52), Ui.dp(act, 52));
        ilp.rightMargin = Ui.dp(act, 10);
        r.addView(iv, ilp);
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, "新图标", 13f, Palette.c(0xFFE8EDF6)));
        col.addView(Ui.text(act, iconPreview.getWidth() + " × " + iconPreview.getHeight()
                + " · " + Ui.humanSize(workIcon.length()), 11f, Palette.c(0xFF8B97A9)));
        r.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        iconBox.addView(r);
    }

    private void doReplace() {
        if (workApk == null) {
            Toast.makeText(act, "请先选择 APK", Toast.LENGTH_SHORT).show();
            return;
        }
        if (workIcon == null) {
            Toast.makeText(act, "请先选择新图标", Toast.LENGTH_SHORT).show();
            return;
        }
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(10);
        statusView.setText("开始处理…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ApkTools.Result r = ApkTools.replaceIconAndSign(act, workApk, workIcon,
                            workDir(), new ApkTools.Progress() {
                                @Override
                                public void onStep(final String msg) {
                                    act.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            statusView.setText(msg);
                                        }
                                    });
                                }
                            });
                    final ApkTools.Result fr = r;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            bar.setProgress(100);
                            statusView.setText("完成：替换 " + fr.iconsReplaced + " 个图标，签名 "
                                    + (fr.v1 ? "v1" : "") + (fr.v1 && fr.v2 ? "+" : "")
                                    + (fr.v2 ? "v2" : "") + "\n" + fr.output.getAbsolutePath());
                            askExport(fr.output);
                        }
                    });
                } catch (final Throwable t) {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            bar.setVisibility(View.GONE);
                            String m = t.getMessage() == null ? t.toString() : t.getMessage();
                            statusView.setText("失败：" + m);
                            Toast.makeText(act, "处理失败: " + m, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void askExport(final File out) {
        File pub = new File(outDir(), out.getName());
        try {
            if (!pub.getAbsolutePath().equals(out.getAbsolutePath())) {
                copyFile(out, pub);
            }
        } catch (Exception ignored) {
        }
        new NebAlert.Builder(act)
                .setTitle("处理完成")
                .setMessage("输出文件：\n" + pub.getAbsolutePath()
                        + "\n\n是否导出到手机下载目录？")
                .setPositiveButton("导出", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        pendingExport = pub;
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("application/vnd.android.package-archive");
                        i.putExtra(Intent.EXTRA_TITLE, out.getName());
                        try {
                            act.startActivityForResult(i, REQ_EXPORT);
                        } catch (Throwable t) {
                            Toast.makeText(act, "无法打开保存对话框", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("不用了", null)
                .show();
    }

    private void exportTo(Uri uri, File src) {
        try {
            java.io.OutputStream os = act.getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("无法写入");
            InputStream in = new java.io.FileInputStream(src);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            os.close();
            Toast.makeText(act, "已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(act, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        if (dst.exists()) dst.delete();
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    /** 供外部（如沙箱页）查询文件名 */
    public static String displayName(Activity act, Uri uri) {
        try {
            android.database.Cursor c = act.getContentResolver()
                    .query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) {
                    String s = c.getString(idx);
                    c.close();
                    return s;
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }
        return uri.getLastPathSegment();
    }
}
