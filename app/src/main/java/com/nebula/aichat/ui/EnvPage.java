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
            "jq", "rsync", "gdb", "tree",
            "java", "javac", "python3", "pip3", "node", "git", "make", "gcc", "ffmpeg", "aapt2", "apktool"
    };

    private final Activity act;
    private final Callback cb;
    private final Settings settings;
    private LinearLayout rootView;
    private LinearLayout probeBox;
    private TextView probeSum;
    private TextView tcBanner;
    /** 工具档位清单的每一行，探测完成后把按钮切成「下载 / 已安装（灰）」 */
    private final java.util.List<Tier> tiers = new java.util.ArrayList<Tier>();
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 需要重建整页（设置变更 / 安装完成）；平时切回本页只做轻量刷新 */
    private boolean dirty;
    /** 命令探测结果缓存：避免每次切到本页都拉起 proot 进程重跑一遍 */
    private static Set<String> probeCache;
    /** 安装进度轮询是否已在跑（防止多次 build 叠加出多个循环） */
    private boolean tickActive;
    private boolean busyShown;   // 上一轮是否显示着"安装中"，用来只播一次"已完成"
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
                alpineReady ? Palette.c(0xFF7FE3A1) : Palette.c(0xFFFFB74D));
        TextView lTitle = sizeTv;
        linuxCard.addView(lTitle);
        TextView lDesc = Ui.text(act, alpineReady
                ? "终端可执行 apk add 安装真实软件包：python3 / nodejs / gcc / git / vim"
                : "在 App 内运行真正的 Alpine Linux，支持 apk add 安装软件，无需 Root。内置资产约 4 MB。",
                11.5f, Palette.c(0xFF8B97A9));
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
                : (remote ? "远程执行" : "本地执行（手机 shell）"), 13.5f, Palette.c(0xFFE8EDF6)));
        String url = settings.remoteExecUrl == null ? "" : settings.remoteExecUrl.trim();
        String envLine = alpineMode
                ? (linux.isInstalled()
                        ? "proot + " + linux.versionText() + " · /root"
                        : (linux.needsUpgrade()
                                ? "⚠ 环境需要升级到新版 Alpine，你的项目会保留"
                                : "⚠ 环境未安装，请先在上方安装"))
                : (remote
                        ? (url.isEmpty() ? "⚠ 未配置地址，将回退本地" : url)
                        : "shell: " + com.nebula.aichat.shell.ShellRunner.shellPath());
        boolean envWarn = (remote && !alpineMode && url.isEmpty()) || (alpineMode && !linux.isInstalled());
        modeCard.addView(Ui.text(act, envLine, 11.5f,
                envWarn ? Palette.c(0xFFFFB74D) : Palette.c(0xFF8B97A9)));
        box.addView(modeCard);

        // ---- 工具档位：一档一行，缺哪个点哪行右边的「下载」，装好后按钮自动变灰
        box.addView(Ui.sectionTitle(act, "工具档位 · 缺哪个点下载"));
        LinearLayout tierCard = Ui.card(act);
        addTier(tierCard, 0, "基础工具", "zip / curl / git / sqlite3 / jq / rsync / gdb / tree / xz …", "29 MB · 随 APK 内置", true,
                new String[]{"zip", "unzip", "xz", "curl", "wget", "git", "jq", "rsync", "tree", "xxd", "sqlite3", "gdb", "sha256sum"});
        tierCard.addView(Ui.divider(act));
        addTier(tierCard, 1, "Java 运行时", "跑 jadx / apktool / baksmali / smali 反编译工具", "61 MB · 随 APK 内置", true,
                new String[]{"java"});
        tierCard.addView(Ui.divider(act));
        addTier(tierCard, 5, "JDK 编译器", "javac / javadoc / jar / javap / jshell", "5.6 MB · 随 APK 内置", true,
                new String[]{"javac"});
        tierCard.addView(Ui.divider(act));
        addTier(tierCard, 3, "Python 数据档", "pip + numpy 2.2，算数据不用写循环", "29 MB · 随 APK 内置", true,
                new String[]{"pip3"});
        tierCard.addView(Ui.divider(act));
        addTier(tierCard, 4, "FFmpeg 转码", "x264 / x265 / vp9 / av1，转码、抽帧、转 GIF", "45 MB · 随 APK 内置", true,
                new String[]{"ffmpeg"});
        tierCard.addView(Ui.divider(act));
        addTier(tierCard, 2, "开发工具", "python3 / node / gcc / make", "在线下载 · 需联网", false,
                new String[]{"python3", "node", "gcc", "make"});
        box.addView(tierCard);

        // ---- 工具链探测：47 项逐个标状态（常驻显示，不折叠）
        box.addView(Ui.sectionTitle(act, "工具链探测"));
        LinearLayout toolCard = Ui.card(act);
        probeSum = Ui.text(act, "正在探测内置 Linux 环境…", 12f, Palette.c(0xFF5B93FF));
        toolCard.addView(probeSum);

        tcBanner = Ui.text(act, "", 11.5f, Palette.c(0xFFFFD180));
        tcBanner.setBackground(Ui.roundRect(act, Palette.c(0xFF2A2218), 0, 8f));
        tcBanner.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(act, 8);
        tcBanner.setVisibility(View.GONE);
        toolCard.addView(tcBanner, blp);

        probeBox = Ui.col(act);
        probeBox.setPadding(0, Ui.dp(act, 8), 0, 0);
        toolCard.addView(probeBox);

        TextView tcHint = Ui.text(act,
                "内置系统已升到 Alpine 3.22：gcc 14.2 / python 3.12.16 / git 2.49 / node 22 都可直接装。"
                        + "上面五档（基础工具 29MB、Java 运行时 61MB、JDK 编译器 5.6MB、Python 数据档 29MB、FFmpeg 45MB）"
                        + "都随 APK 内置，共 169MB，装好环境后自动解包，平时不用点、也不耗流量；"
                        + "只有「开发工具」那档是联网下载。解包后约占 460MB，各档共用同一个根文件系统，不会重复占空间。"
                        + "首次安装要解 169MB，请保持前台、别锁屏。",
                11f, Palette.c(0xFF7A8494));
        tcHint.setPadding(0, Ui.dp(act, 6), 0, 0);
        toolCard.addView(tcHint);
        box.addView(toolCard);
        refreshProbe();
        startTick();

        // ---- 工具能干什么：光列包名用户不知道能拿来做什么，给几条可以直接说的话
        LinearLayout tipsBox = Ui.col(act);
        tipsBox.addView(tipRow("jq", "把这段 JSON 按字段整理成表格，或只挑出我要的那几项"));
        tipsBox.addView(Ui.divider(act));
        tipsBox.addView(tipRow("rsync", "把 A 目录增量同步到 B，只传变过的文件，断了能续传"));
        tipsBox.addView(Ui.divider(act));
        tipsBox.addView(tipRow("gdb", "这段 C 代码崩了，帮我看看崩在哪一行、变量是什么值"));
        tipsBox.addView(Ui.divider(act));
        tipsBox.addView(tipRow("tree", "看下这个目录的整体结构，别用 ls 一层层翻"));
        tipsBox.addView(Ui.divider(act));
        tipsBox.addView(tipRow("ffmpeg", "把这个视频压到 720p、转成 GIF，或者每秒抽一帧"));
        tipsBox.addView(Ui.divider(act));
        tipsBox.addView(tipRow("numpy", "用 numpy 算这组数据的均值方差，别写循环"));
        TextView tipsHint = Ui.text(act, "这六样都已随环境自动装好，直接这么跟 AI 说就行，它会在内置环境里执行，不用你敲命令。",
                11f, Palette.c(0xFF7A8494));
        tipsHint.setPadding(0, Ui.dp(act, 10), 0, 0);
        tipsBox.addView(tipsHint);
        box.addView(Ui.sectionTitle(act, "这些工具能干什么"));
        LinearLayout tips = Ui.card(act);
        tips.addView(tipsBox);
        box.addView(tips);

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
        LinearLayout devBox = Ui.col(act);
        devBox.addView(kv("型号", Build.MANUFACTURER + " " + Build.MODEL));
        devBox.addView(kv("系统", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"));
        devBox.addView(kv("架构", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
        devBox.addView(kv("沙箱目录", new File(act.getFilesDir(), "sandbox").getAbsolutePath()));
        Runtime rt = Runtime.getRuntime();
        devBox.addView(kv("堆内存", Ui.humanSize(rt.maxMemory())));
        try {
            StatFs sf = new StatFs(act.getFilesDir().getAbsolutePath());
            devBox.addView(kv("可用空间", Ui.humanSize(sf.getAvailableBytes())));
        } catch (Exception ignored) {
        }
        box.addView(Ui.sectionTitle(act, "设备"));
        LinearLayout dev = Ui.card(act);
        dev.addView(devBox);
        box.addView(dev);

        // ---- 环境变量
        box.addView(Ui.sectionTitle(act, "环境变量"));
        LinearLayout envCard = Ui.card(act);
        Map<String, String> env = new TreeMap<>(System.getenv());
        if (env.isEmpty()) {
            envCard.addView(Ui.text(act, "（无）", 12f, Palette.c(0xFF8B97A9)));
        } else {
            for (Map.Entry<String, String> e : env.entrySet()) {
                envCard.addView(kv(e.getKey(), e.getValue()));
            }
        }
        box.addView(envCard);

        rootView.addView(sv);
    }

    /** 工具示例行：左边是命令名（等宽字体 + 淡蓝标签），右边是「能拿它干什么」 */

    // ---------------------------------------------------------------- 工具链档位

    /** 一个工具档位：一行「名称 + 说明 + 大小」，右边一个下载按钮 */
    private static class Tier {
        final int mode;
        final TextView title;
        final TextView btn;
        /** 这一档应该装上的命令；用它算缺几项，而不是只看一两个关键命令 */
        final String[] need;

        Tier(int mode, TextView title, TextView btn, String[] need) {
            this.mode = mode;
            this.title = title;
            this.btn = btn;
            this.need = need;
        }
    }

    /** 加一行档位：左边名称/说明/大小，右边「下载」按钮；探测完由 paintTier 切成已装灰态 */
    private void addTier(LinearLayout parent, final int mode, String name, String detail,
                         String size, boolean bundled, String[] need) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));

        LinearLayout left = Ui.col(act);
        TextView title = Ui.text(act, name, 12.5f, Palette.c(0xFFE8EDF6));
        left.addView(title);
        TextView sub = Ui.text(act, detail, 11f, Palette.c(0xFF7A8494));
        sub.setPadding(0, Ui.dp(act, 3), 0, 0);
        left.addView(sub);
        TextView sv = Ui.text(act, size, 10.5f, bundled ? Palette.c(0xFF5A6577) : Palette.c(0xFF5B93FF));
        sv.setPadding(0, Ui.dp(act, 2), 0, 0);
        left.addView(sv);
        row.addView(left, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView btn = new TextView(act);
        btn.setTextSize(11.5f);
        btn.setSingleLine(true);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setMinWidth(Ui.dp(act, 64));
        btn.setPadding(Ui.dp(act, 12), Ui.dp(act, 7), Ui.dp(act, 12), Ui.dp(act, 7));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installToolchain(mode);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.dp(act, 10);
        row.addView(btn, blp);

        Tier t = new Tier(mode, title, btn, need);
        paintTier(t, new HashSet<String>());
        tiers.add(t);
        parent.addView(row);
    }

    /**
     * 按实际缺项数刷新档位状态，三种态互斥：
     * 一项没装 → 蓝底「下载」；缺一部分 → 橙底「补齐（缺 N 项）」仍可点；全齐 → 灰底「已安装」点不动。
     * 这样明细里有 ✗ 时不会出现「显示已安装却修不了」的死角。
     */
    private void paintTier(Tier t, Set<String> has) {
        int missing = 0;
        for (int i = 0; i < t.need.length; i++) {
            if (!has.contains(t.need[i])) missing++;
        }
        int total = t.need.length;
        if (missing == 0) {
            t.btn.setEnabled(false);
            t.btn.setText("已安装");
            t.btn.setTextColor(Palette.c(0xFF5A6577));
            t.btn.setBackground(Ui.roundRect(act, Palette.c(0xFF1E2228), 0, 10f));
            t.title.setTextColor(Palette.c(0xFF7CD98F));
        } else if (missing == total) {
            t.btn.setEnabled(true);
            t.btn.setText("下载");
            t.btn.setTextColor(Palette.c(0xFFFFFFFF));
            t.btn.setBackground(Ui.roundRect(act, Palette.c(0xFF3D6FE0), 0, 10f));
            t.title.setTextColor(Palette.c(0xFFE8EDF6));
        } else {
            t.btn.setEnabled(true);
            t.btn.setText("补齐（缺 " + missing + " 项）");
            t.btn.setTextColor(Palette.c(0xFFFFB74D));
            t.btn.setBackground(Ui.roundRect(act, Palette.c(0xFF32260F), 0, 10f));
            t.title.setTextColor(Palette.c(0xFFFFD180));
        }
    }

    private LinearLayout tipRow(String cmd, String desc) {
        LinearLayout r = Ui.row(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));
        TextView name = Ui.text(act, cmd, 11.5f, Palette.accent(act));
        name.setTypeface(android.graphics.Typeface.MONOSPACE);
        name.setGravity(Gravity.CENTER);
        name.setMinWidth(Ui.dp(act, 58));
        name.setPadding(Ui.dp(act, 8), Ui.dp(act, 3), Ui.dp(act, 8), Ui.dp(act, 3));
        name.setBackground(Ui.roundRect(act, Palette.c(0x1A5B93FF), Palette.c(0x4D5B93FF), 8f));
        r.addView(name);
        TextView d = Ui.text(act, desc, 12.5f, Palette.c(0xFF868F9E));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = Ui.dp(act, 10);
        r.addView(d, dlp);
        return r;
    }

    private LinearLayout entryRow(String icon, String title, String desc, View.OnClickListener l) {
        LinearLayout row = Ui.row(act);
        row.setBackground(Ui.roundRect(act, Palette.c(0xFF161B24), 0, 12f));
        int p = Ui.dp(act, 12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 7);
        row.setLayoutParams(lp);

        row.addView(Ui.text(act, icon, 17f, Palette.c(0xFFFFFFFF)));
        LinearLayout mid = Ui.col(act);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = Ui.dp(act, 10);
        TextView t = Ui.text(act, title, 13.5f, Palette.c(0xFFE8EDF6));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mid.addView(t);
        mid.addView(Ui.text(act, desc, 11f, Palette.c(0xFF8B97A9)));
        row.addView(mid, mp);
        row.addView(Ui.text(act, "›", 18f, Palette.c(0xFF5A6577)));
        row.setOnClickListener(l);
        return row;
    }

    private LinearLayout kv(String k, String v) {
        LinearLayout row = Ui.row(act);
        row.setPadding(0, Ui.dp(act, 3), 0, Ui.dp(act, 3));
        TextView key = Ui.text(act, k, 11.5f, Palette.c(0xFF8B97A9));
        key.setSingleLine(true);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f);
        TextView val = Ui.text(act, v == null ? "" : v, 11.5f, Palette.c(0xFFC8D3E5));
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
                    yes ? Palette.c(0xFF7CD98F) : Palette.c(0xFF5A6577));
            chip.setBackground(Ui.roundRect(act, yes ? Palette.c(0xFF16281C) : Palette.c(0xFF1A1D22), 0, 8f));
            chip.setPadding(Ui.dp(act, 8), Ui.dp(act, 5), Ui.dp(act, 8), Ui.dp(act, 5));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(act, 5);
            clp.bottomMargin = Ui.dp(act, 5);
            line.addView(chip, clp);
        }
        if (!LinuxEnv.get(act).isInstalled()) {
            probeSum.setText("内置 Linux 未安装　（先点上方「立即安装」）");
            return;
        }
        boolean onlyDevMissing = true;
        for (int i = 0; i < PROBE.length; i++) {
            String c = PROBE[i];
            if (has.contains(c)) continue;
            if (!("python3".equals(c) || "node".equals(c) || "gcc".equals(c) || "make".equals(c))) {
                onlyDevMissing = false;
            }
        }
        // 每档一个按钮：齐全变灰不可点，缺一项以上变橙色「补齐（缺 N 项）」仍可点
        for (int i = 0; i < tiers.size(); i++) {
            paintTier(tiers.get(i), has);
        }

        String sum;
        if (ok == PROBE.length) {
            sum = "可用 " + ok + " / " + PROBE.length + "　全部就绪 ✓";
        } else if (onlyDevMissing) {
            sum = "可用 " + ok + " / " + PROBE.length + "　内置工具已就绪，仅缺 python3 / node / make / gcc";
        } else {
            sum = "可用 " + ok + " / " + PROBE.length + "　（看上面各档，缺哪档点哪档的下载）";
        }
        probeSum.setText(sum);
    }

    /**
     * @param mode 0=装内置基础工具（本地）  1=装内置 Java 运行时（本地）  2=装开发工具（本地，缺包时才联网）
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
        final TextView logView = Ui.text(act, "开始…\n", 11f, Palette.c(0xFFB9C2CE));
        logView.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
        ScrollView sv = new ScrollView(act);
        sv.addView(logView);
        final AlertDialog dlg = new NebAlert.Builder(act)
                .setTitle(mode == 5 ? "安装内置 JDK 17 编译器（零流量）"
                        : mode == 1 ? "安装内置 Java 运行时（零流量）"
                        : mode == 2 ? "下载开发工具 python3/node/gcc"
                        : mode == 3 ? "安装 Python 数据档 pip/numpy（零流量）"
                        : mode == 4 ? "安装 FFmpeg 转码（零流量）"
                        : "安装内置基础工具（零流量）")
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
                String err = mode == 5 ? Toolchain.installJdk(act, prog)
                        : mode == 1 ? Toolchain.installJre(act, prog)
                        : mode == 3 ? Toolchain.installPython(act, prog)
                        : mode == 4 ? Toolchain.installMedia(act, prog)
                        : Toolchain.install(act, mode == 2, prog);
                final String okTail = mode == 5 ? "，javac/jar/javadoc 可以用了"
                        : mode == 1 ? "，jadx/apktool 可以用了"
                        : mode == 3 ? "，pip 与 numpy 可以用了"
                        : mode == 4 ? "，ffmpeg 可以转码了"
                        : "，可以解包 APK 了";
                final String msg = err == null
                        ? "✅ 安装完成" + okTail
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
                if (tcBanner == null || act.isFinishing() || act.isDestroyed()) {
                    tickActive = false;
                    return;
                }
                // 四档串行装包时档与档之间 running 会短暂为 false，只看 running 会误判收工、
                // 把 tick 停掉，后面几档就再没有进度了 —— 所以 provisioning 也算忙
                if (Toolchain.running || Toolchain.provisioning) {
                    busyShown = true;
                    long sec = (System.currentTimeMillis() - Toolchain.startedAt) / 1000;
                    tcBanner.setVisibility(View.VISIBLE);
                    String head = Toolchain.provisioning
                            ? "⏳ 装第 " + Math.min(Toolchain.planStepTotal, Toolchain.planStep + 1)
                              + "/" + Toolchain.planStepTotal + " 档 · " + Toolchain.planPct + "%"
                              + (Toolchain.planLabel.isEmpty() ? "" : "　" + Toolchain.planLabel)
                            : "⏳ 安装中";
                    tcBanner.setText(head + "　" + fmtSec(sec) + "　" + Toolchain.step
                            + (Toolchain.lastLine.isEmpty() ? "" : "\n" + clip(Toolchain.lastLine, 90))
                            + "\n（可退回后台上装，完成后这里会变✅）");
                } else if (busyShown) {
                    busyShown = false;
                    if (!Toolchain.step.isEmpty()) {
                        tcBanner.setVisibility(View.VISIBLE);
                        tcBanner.setText("已完成 · 耗时 " + Toolchain.doneMsg
                                + (Toolchain.lastLine.isEmpty() ? "" : "　" + clip(Toolchain.lastLine, 80)));
                        Toolchain.step = "";
                    }
                }
                ui.postDelayed(this, 900);
            }
        });
    }

    /** 顶部「Alpine 版本 · 已安装 · 体积」；体积改到后台算，算好回填 */
    private String linuxTitle(final LinuxEnv linux, boolean ready) {
        if (!ready) {
            // 环境还在、只是 rootfs 换代了：说"要升级"，别说"未安装"，免得用户以为炸了
            if (linux.needsUpgrade()) {
                return "旧版 Alpine（v" + linux.installedAssetVersion() + "）· 需升级，项目会保留";
            }
            return "未安装（proot + Alpine rootfs）";
        }
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
        TextView tv = Ui.text(act, content, 12f, Palette.c(0xFFC8D3E5));
        tv.setTextIsSelectable(true);
        int p = Ui.dp(act, 14);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);
        new NebAlert.Builder(act).setTitle(title).setView(sv).setPositiveButton("关闭", null).show();
    }
}
