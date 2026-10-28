package com.nebula.aichat.linux;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.nebula.aichat.ui.Ui;
import com.nebula.aichat.ui.Palette;

/**
 * 内置 Linux 环境安装/重置对话框。
 * 安装过程：解压 Alpine rootfs（约 4 MB 资产，解压后约 8 MB），无需联网。
 */
public final class InstallDialog {

    private InstallDialog() {
    }

    /** 显示安装日志（可复制），失败的完整原因都在这里 */
    /**
     * 环境刚装好，就把「开箱即用」需要的东西一次补齐：
     * 基础命令包（≈5MB）+ Java 运行时（≈60MB）。
     *
     * 为什么连 Java 也自动装：内置的 jadx / apktool / baksmali / smali 全是 jar，
     * 没有 java 就是几个跑不起来的空壳，而用户根本不会知道要去环境页单独点那个按钮。
     * 两档串行、已装自动跳过，失败静默（环境页仍可手动重试，不阻塞任何主流程）。
     */
    private static void autoProvision(final Activity act) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (com.nebula.aichat.linux.Toolchain.running) return;
                    final boolean needCore = !com.nebula.aichat.linux.Toolchain.coreReady(act);
                    final boolean needJre = !com.nebula.aichat.linux.Toolchain.jreReady(act);
                    if (!needCore && !needJre) return;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(act,
                                    needJre ? "正在后台准备反编译环境（约 65MB），可以继续使用"
                                            : "正在后台补齐基础命令（约 5MB）",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                    String err = com.nebula.aichat.linux.Toolchain.provision(act, null);
                    if (err == null) {
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                android.widget.Toast.makeText(act,
                                        "环境已就绪：常用命令 + jadx/apktool 都能用了",
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "nebula-provision").start();
    }

    public static void showLog(final Activity act, final LinuxEnv env) {
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        TextView tv = Ui.text(act, env.logText(), 10.5f, Palette.c(0xFFAEB9C9));
        tv.setTypeface(Typeface.MONOSPACE);
        int p2 = Ui.dp(act, 14);
        tv.setPadding(p2, p2, p2, p2);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        new AlertDialog.Builder(act)
                .setTitle("安装日志")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("log", env.logText()));
                        android.widget.Toast.makeText(act, "日志已复制", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    public static void show(final Activity act, final Runnable onDone) {
        final LinuxEnv env = LinuxEnv.get(act);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(act, 18);
        box.setPadding(p, Ui.dp(act, 8), p, 0);

        // 体积可能还没算过：只有已有缓存才写数字，避免弹窗时同步遍历 rootfs
        long knownSize = env.cachedSize();
        TextView desc = Ui.text(act, env.isInstalled()
                ? "当前状态：" + env.versionText() + "，占用 "
                + (knownSize >= 0 ? LinuxEnv.humanSize(knownSize) : "计算中…") + "\n\n"
                + "终端可直接执行 apk add 安装软件，例如：\n"
                + "  apk add python3 git\n  apk add nodejs npm\n  apk add gcc musl-dev make\n\n"
                + "重置会删除已安装的所有软件包。"
                : "将在手机内置一个真实的 Alpine Linux 环境（proot 方案）。\n\n"
                + "· 解压后占用约 8 MB（内置资产，无需联网下载）\n"
                + "· 装好后可执行 apk add 安装软件：\n"
                + "  python3 / nodejs / gcc / git / vim …\n"
                + "· 文件可通过 /exchange 与沙箱互通\n"
                + "· 不修改系统，卸载 App 即清除", 12.5f, Palette.c(0xFF9AA7BA));
        desc.setLineSpacing(Ui.dp(act, 3f), 1f);
        box.addView(desc);

        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 6));
        bp.topMargin = Ui.dp(act, 14);
        box.addView(bar, bp);

        final TextView status = Ui.text(act, env.isInstalled() ? "环境已就绪" : "点击「立即安装」开始", 11.5f,
                Palette.c(0xFF8B97A9));
        status.setTypeface(Typeface.MONOSPACE);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        status.setGravity(Gravity.START);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(act, 6);
        box.addView(status, sp);

        bar.setVisibility(env.isInstalled() ? View.GONE : View.VISIBLE);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("内置 Linux 环境")
                .setView(box)
                .setPositiveButton("立即安装", null)
                .setNegativeButton("关闭", null)
                .setNeutralButton("", null)
                .create();
        dlg.show();

        final Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        final Button neu = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        pos.setText(env.isInstalled() ? "重新安装" : "立即安装");
        neu.setText(env.isInstalled() ? "删除环境" : "");
        neu.setVisibility(env.isInstalled() ? View.VISIBLE : View.GONE);

        neu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                env.uninstall();
                dlg.dismiss();
                if (onDone != null) onDone.run();
            }
        });

        pos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pos.setEnabled(false);
                neu.setEnabled(false);
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(0);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String err = null;
                        try {
                            env.install(new LinuxEnv.Progress() {
                                @Override
                                public void onProgress(int percent, String message) {
                                    final int pc = percent;
                                    final String msg = message;
                                    act.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            bar.setProgress(pc);
                                            status.setText(pc + "%  " + msg);
                                        }
                                    });
                                }
                            });
                        } catch (Throwable t) {
                            err = t.getClass().getSimpleName() + ": " + t.getMessage();
                        }
                        final String e = err;
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                pos.setEnabled(true);
                                pos.setText("重新安装");
                                neu.setEnabled(true);
                                if (e == null) {
                                    status.setText("安装完成：" + env.versionText());
                                    bar.setProgress(100);
                                    dlg.dismiss();
                                    autoProvision(act);
                                    if (onDone != null) onDone.run();
                                } else {
                                    status.setText("安装失败：" + e);
                                    bar.setVisibility(View.GONE);
                                    neu.setText("查看日志");
                                    neu.setVisibility(View.VISIBLE);
                                    neu.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v2) {
                                            showLog(act, env);
                                        }
                                    });
                                }
                            }
                        });
                    }
                }, "alpine-install").start();
            }
        });
    }
}
