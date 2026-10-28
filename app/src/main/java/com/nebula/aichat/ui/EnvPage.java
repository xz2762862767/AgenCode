package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nebula.aichat.apk.ApkWorkbench;
import com.nebula.aichat.apk.ApkWorkbenchPanel;
import com.nebula.aichat.data.Settings;
import com.nebula.aichat.linux.InstallDialog;
import com.nebula.aichat.linux.LinuxEnv;
import com.nebula.aichat.linux.Toolchain;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;

/** 环境页：工具链探测 / 环境变量 / 设备信息 / 功能入口 */
public class EnvPage {

    public interface Callback {
        void onOpenSettings();

        void onOpenMcp();

        void onOpenSkills();

        void onOpenApkTools();

        void onOpenRemoteConfig();
    }

    /** 探测命令（顺序即展示顺序） */
    public static final String[] PROBE = {
            "sh", "ls", "cat", "grep", "sed", "awk", "find", "cp", "mv", "rm", "mkdir", "chmod",
            "ln", "tar", "gzip", "xz", "zip", "unzip", "ps", "df", "du", "top", "date", "wc",
            "head", "tail", "sort", "xxd", "sha256sum", "sqlite3", "curl", "wget",
            "java", "python3", "node", "git", "make", "gcc", "aapt2", "apktool"
    };

    private final Activity act;
    private final Callback cb;
    private final Settings settings;
    private LinearLayout rootView;
    private LinearLayout probeBox;
    private TextView probeSum;
    private TextView tcBanner;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 需要重建整页（设置变更 / 安装完成）；平时切回本页只做轻量刷新 */
    private boolean dirty;
    /** 命令探测结果缓存：避免每次切到本页都拉起 proot 进程重跑一遍 */
    private static Set<String> probeCache;
    /** 安装进度轮询是否已在跑（防止多次 build 叠加出多个循环） */
    private boolean tickActive;
    /** 顶部「已安装 · 体积」那一行，体积在后台算好后回填 */
    private TextView sizeTv;

    public EnvPage(Activity act, Callback cb) {
        this.act = act;
        this.cb = cb;
        this.settings = Settings.load(act);
        build();
    }

    public View getView() {
        return rootView;
    }

    /** 设置变更 / 环境安装完成时调用：作废缓存并重建 */
    public void invalidate() {
        dirty = true;
        probeCache = null;
        LinuxEnv.get(act).invalidateSize();
        if (rootView != null && rootView.getVisibility() == View.VISIBLE) {
            dirty = false;
            rootView.removeAllViews();
            build();
        }
    }

    public void onShown() {
        if (dirty) {
            dirty = false;
            rootView.removeAllViews();
            build();
        } else {
            // 只刷新安装进度横幅，避免整页重建（卡顿 + 滚动位置丢失）
            startTick();
        }
    }

    private void build() {
        if (rootView == null) rootView = Ui.col(act);
        rootView.setBackgroundColor(Palette.c(0xFF0F1218));

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 14);
        box.setPadding(p, p, p, Ui.dp(act, 28));
        sv.addView(box);

        // ---- 功能入口
        box.addView(Ui.sectionTitle(act, "能力"));
        LinearLayout grid = Ui.col(act);
        grid.addView(entryRow("🧩", "MCP 服务器", "接入外部 MCP 工具（JSON-RPC / SSE）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onOpenMcp();
            }
        }));
        grid.addView(entryRow("🧠", "技能（Skills）", "加载 SKILL.md，注入到系统提示词", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onOpenSkills();
            }
        }));
        grid.addView(entryRow("📦", "APK 工具", "解析信息 / 换图标 / 重新签名", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onOpenApkTools();
            }
        }));
        grid.addView(entryRow("🌐", "远程执行器", "把 shell 命令发到服务器执行", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onOpenRemoteConfig();
            }
        }));
        grid.addView(entryRow("⚙️", "模型设置", "API Key / 模型 / 系统提示词", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onOpenSettings();
            }
        }));
        box.addView(grid);

        // ---- 内置 Linux 环境
        box.addView(Ui.sectionTitle(act, "内置 Linux 环境"));
        final LinuxEnv linux = LinuxEnv.get(act);
        LinearLayout linuxCard = Ui.card(act);
        boolean alpineReady = linux.isInstalled();
        sizeTv = Ui.text(act, linuxTitle(linux, alpineReady), 13.5f,
                alpineReady ? 0xFF7FE3A1 : 0xFFFFB74D);
        TextView lTitle = sizeTv;
        linuxCard.addView(lTitle);
        TextView lDesc = Ui.text(act, alpineReady
                ? "终端可执行 apk add 安装真实软件包：python3 / nodejs / gcc / git / vim"
                : "在 App 内运行真正的 Alpine Linux，支持 apk add 安装软件，无需 Root。内置资产约 4 MB。",
                11.5f, 0xFF8B97A9);
        lDesc.setLineSpacing(Ui.dp(act, 2f), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 6);
        linuxCard.addView(lDesc, lp);

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brp.topMargin = Ui.dp(act, 10);
        TextView btnInstall = Ui.primaryChip(act, alpineReady ? "管理 / 重置" : "立即安装", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InstallDialog.show(act, new Runnable() {
                    @Override
                    public void run() {
                        invalidate();
                    }
                });
            }
        });
        btnRow.addView(btnInstall);
        if (alpineReady) {
            TextView btnTerm = Ui.ghostChip(act, "去终端", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    settings.execMode = "alpine";
                    settings.save(act);
                    android.widget.Toast.makeText(act, "已切换为 Alpine 执行方式", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = Ui.dp(act, 8);
            btnRow.addView(btnTerm, tp);
        }
        linuxCard.addView(btnRow, brp);
        box.addView(linuxCard);

        // ---- 执行模式
        box.addView(Ui.sectionTitle(act, "当前执行方式"));
        LinearLayout modeCard = Ui.card(act);
        boolean remote = "remote".equals(settings.execMode);
        boolean alpineMode = "alpine".equals(settings.execMode);
        modeCard.addView(Ui.text(act, alpineMode ? "内置 Alpine Linux"
                : (remote ? "远程执行" : "本地执行（手机 shell）"), 13.5f, 0xFFE8EDF6));
        String url = settings.remoteExecUrl == null ? "" : settings.remoteExecUrl.trim();
        modeCard.addView(Ui.text(act, alpineMode
                        ? (linux.isInstalled() ? "proot + " + linux.versionText() + " · /root"
                        : "⚠ 环境未安装，请先在上方安装")
                        : (remote
                        ? (url.isEmpty() ? "⚠ 未配置地址，将回退本地" : url)
                        : "shell: " + com.nebula.aichat.shell.ShellRunner.shellPath()), 11.5f,
                (remote && !alpineMode && url.isEmpty()) || (alpineMode && !linux.isInstalled())
                        ? 0xFFFFB74D : 0xFF8B97A9));
        box.addView(modeCard);

        // ---- 工具链（探测的是内置 Linux，Android 系统本身不含 java/gcc/python3）
        box.addView(Ui.sectionTitle(act, "工具链探测"));
        LinearLayout toolCard = Ui.card(act);
        probeSum = Ui.text(act, "正在探测内置 Linux 环境…", 12f, 0xFF5B93FF);
        toolCard.addView(probeSum);

        tcBanner = Ui.text(act, "", 11.5f, 0xFFFFD180);
        tcBanner.setBackground(Ui.roundRect(act, 0xFF2A2218, 0, 8f));
        tcBanner.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(act, 8);
        tcBanner.setVisibility(View.GONE);
        toolCard.addView(tcBanner, blp);
        probeBox = Ui.col(act);
        probeBox.setPadding(0, Ui.dp(act, 8), 0, 0);
        toolCard.addView(probeBox);

        LinearLayout tcOps = Ui.row(act);
        tcOps.setPadding(0, Ui.dp(act, 10), 0, 0);
        tcOps.addView(Ui.ghostChip(act, "补齐基础工具", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installToolchain(false);
            }
        }));
        TextView devChip = Ui.ghostChip(act, "全部工具链", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installToolchain(true);
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(act, 8);
        tcOps.addView(devChip, tlp);
        toolCard.addView(tcOps);

        // 反编译工具（jadx / apktool / baksmali / smali）只要一个 Java 运行时就能跑，
        // 不该逼用户为它拖 245MB 全家桶，所以单列一个按钮。
        LinearLayout tcOps2 = Ui.row(act);
        tcOps2.setPadding(0, Ui.dp(act, 8), 0, 0);
        tcOps2.addView(Ui.ghostChip(act, "只装 Java 运行时（60MB）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installToolchain(1);
            }
        }));
        toolCard.addView(tcOps2);

        TextView tcHint = Ui.text(act,
                "基础 ≈15MB（zip/curl/git/sqlite/xxd/xz…）；只装 Java 运行时 ≈60MB，内置 jadx/apktool/baksmali/smali 靠它才能跑；"
                        + "全部 ≈245MB（再加 python3、node、gcc）。内置 aapt2 与逆向 jar 随包附带，零流量。安装时请保持前台、别锁屏。",
                11f, 0xFF7A8494);
        tcHint.setPadding(0, Ui.dp(act, 6), 0, 0);
        toolCard.addView(tcHint);
        box.addView(toolCard);
        refreshProbe();
        startTick();

        // ---- APK 修改工作台
        box.addView(Ui.sectionTitle(act, "APK 修改"));
        LinearLayout apkCard = Ui.card(act);
        apkCard.addView(entryRow("📦", "APK 工作台",
                "反编译 · 改资源/smali · 一键回编译签名，也可以在对话里让 AI 直接改",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ApkWorkbenchPanel.show(act);
                    }
                }));
        box.addView(apkCard);

        // ---- 设备信息
        box.addView(Ui.sectionTitle(act, "设备"));
        LinearLayout dev = Ui.card(act);
        dev.addView(kv("型号", Build.MANUFACTURER + " " + Build.MODEL));
        dev.addView(kv("系统", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"));
        dev.addView(kv("架构", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
        dev.addView(kv("沙箱目录", new File(act.getFilesDir(), "sandbox").getAbsolutePath()));
        Runtime rt = Runtime.getRuntime();
        dev.addView(kv("堆内存", Ui.humanSize(rt.maxMemory())));
        try {
            StatFs sf = new StatFs(act.getFilesDir().getAbsolutePath());
            dev.addView(kv("可用空间", Ui.humanSize(sf.getAvailableBytes())));
        } catch (Exception ignored) {
        }
        box.addView(dev);

        // ---- 环境变量
        box.addView(Ui.sectionTitle(act, "环境变量"));
        LinearLayout envCard = Ui.card(act);
        Map<String, String> env = new TreeMap<>(System.getenv());
        if (env.isEmpty()) {
            envCard.addView(Ui.text(act, "（无）", 12f, 0xFF8B97A9));
        } else {
            for (Map.Entry<String, String> e : env.entrySet()) {
                envCard.addView(kv(e.getKey(), e.getValue()));
            }
        }
        box.addView(envCard);

        rootView.addView(sv);
    }

    private LinearLayout entryRow(String icon, String title, String desc, View.OnClickListener l) {
        LinearLayout row = Ui.row(act);
        row.setBackground(Ui.roundRect(act, 0xFF161B24, 0, 12f));
        int p = Ui.dp(act, 12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 7);
        row.setLayoutParams(lp);

        row.addView(Ui.text(act, icon, 17f, 0xFFFFFFFF));
        LinearLayout mid = Ui.col(act);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(act, 10);
        TextView t = Ui.text(act, title, 13.5f, 0xFFE8EDF6);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mid.addView(t);
        mid.addView(Ui.text(act, desc, 11f, 0xFF8B97A9));
        row.addView(mid, mp);
        row.addView(Ui.text(act, "›", 18f, 0xFF5A6577));
        row.setOnClickListener(l);
        return row;
    }

    private LinearLayout kv(String k, String v) {
        LinearLayout row = Ui.row(act);
        row.setPadding(0, Ui.dp(act, 3), 0, Ui.dp(act, 3));
        TextView key = Ui.text(act, k, 11.5f, 0xFF8B97A9);
        key.setSingleLine(true);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f);
        TextView val = Ui.text(act, v == null ? "" : v, 11.5f, 0xFFC8D3E5);
        val.setSingleLine(true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(key, kp);
        row.addView(val, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f));
        return row;
    }

    // ---------------------------------------------------------------- 工具链

    /** 异步探测内置 Linux 的命令可用性（结果缓存，装/卸工具后才重新探测） */
    private void refreshProbe() {
        if (!LinuxEnv.get(act).isInstalled()) {
            renderProbe(new HashSet<String>());
            return;
        }
        if (probeCache != null) {
            renderProbe(probeCache);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Set<String> has = Toolchain.probe(act, PROBE);
                probeCache = has;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        renderProbe(has);
                    }
                });
            }
        }).start();
    }

    private void renderProbe(Set<String> has) {
        if (probeBox == null || probeSum == null) return;
        probeBox.removeAllViews();
        int ok = 0;
        LinearLayout line = null;
        for (int i = 0; i < PROBE.length; i++) {
            String c = PROBE[i];
            boolean yes = has.contains(c);
            if (yes) ok++;
            if (i % 4 == 0) {
                line = Ui.row(act);
                probeBox.addView(line);
            }
            TextView chip = Ui.text(act, (yes ? "✓ " : "✗ ") + c, 11.5f,
                    yes ? 0xFF7CD98F : 0xFF5A6577);
            chip.setBackground(Ui.roundRect(act, yes ? 0xFF16281C : 0xFF1A1D22, 0, 8f));
            chip.setPadding(Ui.dp(act, 8), Ui.dp(act, 5), Ui.dp(act, 8), Ui.dp(act, 5));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(act, 5);
            clp.bottomMargin = Ui.dp(act, 5);
            line.addView(chip, clp);
        }
        if (!LinuxEnv.get(act).isInstalled()) {
            probeSum.setText("内置 Linux 未安装　（先点上方「安装内置 Linux」）");
            return;
        }
        probeSum.setText(ok == PROBE.length
                ? "可用 " + ok + " / " + PROBE.length + "　全部就绪 ✓"
                : "可用 " + ok + " / " + PROBE.length + "　（点下面按钮补齐）");
    }

    /** 一键补齐工具链：弹窗看细节，页面横幅看总体进度（关掉弹窗也有） */
    private void installToolchain(final boolean full) {
        installToolchain(full ? 2 : 0);
    }

    /**
     * @param mode 0=补齐基础包  1=只装 Java 运行时（约60MB）  2=全部工具链
     */
    private void installToolchain(final int mode) {
        if (!LinuxEnv.get(act).isInstalled()) {
            Toast.makeText(act, "请先安装内置 Linux 环境", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Toolchain.running) {
            Toast.makeText(act, "正在安装中，看下面的进度条就行", Toast.LENGTH_SHORT).show();
            return;
        }
        final TextView logView = Ui.text(act, "开始…\n", 11f, 0xFFB9C2CE);
        logView.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
        ScrollView sv = new ScrollView(act);
        sv.addView(logView);
        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(mode == 1 ? "只装 Java 运行时（≈60MB）"
                        : mode == 2 ? "安装全部工具链" : "补齐基础工具")
                .setView(sv)
                .setNegativeButton("收起到后台", null)
                .create();
        dlg.show();
        Toolchain.step = "准备中…";
        Toolchain.lastLine = "";
        startTick();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 380));
        }

        final ApkWorkbench.Progress prog = new ApkWorkbench.Progress() {
            @Override
            public void onStep(final String step) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appendLog(logView, "→ " + step);
                    }
                });
            }

            @Override
            public void onLog(final String line) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appendLog(logView, line);
                    }
                });
            }
        };

        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = mode == 1
                        ? Toolchain.installJre(act, prog)
                        : Toolchain.install(act, mode == 2, prog);
                final String msg = err == null
                        ? "✅ 安装完成" + (mode == 1 ? "，jadx/apktool 可以用了" : "，可以解包 APK 了")
                        : "❌ " + err;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appendLog(logView, msg);
                        Toast.makeText(act, err == null ? "工具链已就绪" : "安装失败，详情见日志",
                                Toast.LENGTH_LONG).show();
                        LinuxEnv.get(act).invalidateSize();
                        probeCache = null;
                        refreshProbe();
                    }
                });
            }
        }).start();
    }

    /** 只保留最后 200 行，避免长任务内存膨胀 */
    private void appendLog(TextView v, String line) {
        CharSequence cur = v.getText();
        String[] old = cur.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, old.length - 200);
        for (int i = from; i < old.length; i++) sb.append(old[i]).append('\n');
        sb.append(line).append('\n');
        v.setText(sb.toString());
    }

    /** 每秒刷新页面横幅，展示安装耗时、当前步骤、最后一行输出 */
    private void startTick() {
        if (tickActive) return;
        tickActive = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (tcBanner == null) {
                    tickActive = false;
                    return;
                }
                if (Toolchain.running) {
                    long sec = (System.currentTimeMillis() - Toolchain.startedAt) / 1000;
                    tcBanner.setVisibility(View.VISIBLE);
                    tcBanner.setText("⏳ 安装中 " + fmtSec(sec) + "　" + Toolchain.step
                            + (Toolchain.lastLine.isEmpty() ? "" : "\n" + clip(Toolchain.lastLine, 90))
                            + "\n（可退回后台上装，完成后这里会变✅）");
                    ui.postDelayed(this, 900);
                    return;
                }
                tickActive = false;
                if (!Toolchain.step.isEmpty()) {
                    tcBanner.setVisibility(View.VISIBLE);
                    tcBanner.setText("已完成 · 耗时 " + Toolchain.doneMsg
                            + (Toolchain.lastLine.isEmpty() ? "" : "　" + clip(Toolchain.lastLine, 80)));
                    Toolchain.step = "";
                }
            }
        });
    }

    /** 顶部「Alpine 版本 · 已安装 · 体积」；体积改到后台算，算好回填 */
    private String linuxTitle(final LinuxEnv linux, boolean ready) {
        if (!ready) return "未安装（proot + Alpine rootfs）";
        final String head = linux.versionText() + " · 已安装";
        long sz = linux.cachedSize();
        if (sz >= 0) return head + " · " + LinuxEnv.humanSize(sz);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long s = linux.installedSize();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (sizeTv != null) sizeTv.setText(head + " · " + LinuxEnv.humanSize(s));
                    }
                });
            }
        }).start();
        return head + " · 计算中…";
    }

    private static String fmtSec(long s) {
        if (s < 60) return s + "s";
        return (s / 60) + "分" + (s % 60) + "秒";
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** 在系统 PATH 中查找可执行文件 */
    public static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) path = "/system/bin:/system/xbin:/vendor/bin";
        for (String dir : path.split(":")) {
            if (dir.isEmpty()) continue;
            File f = new File(dir, name);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }
        for (String dir : new String[]{"/system/bin", "/system/xbin", "/vendor/bin", "/product/bin"}) {
            File f = new File(dir, name);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    public static void showInfo(Activity act, String title, String content) {
        ScrollView sv = new ScrollView(act);
        TextView tv = Ui.text(act, content, 12f, 0xFFC8D3E5);
        tv.setTextIsSelectable(true);
        int p = Ui.dp(act, 14);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);
        new AlertDialog.Builder(act).setTitle(title).setView(sv).setPositiveButton("关闭", null).show();
    }
}
