package com.nebula.aichat.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nebula.aichat.data.Settings;
import com.nebula.aichat.linux.InstallDialog;
import com.nebula.aichat.linux.LinuxEnv;
import com.nebula.aichat.shell.ShellRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 终端页：本地 shell / 内置 Alpine / 远程执行器。
 * <p>
 * 视觉：IDE 面板式布局 —— 顶部状态栏（呼吸状态灯 + 计时）、圆角输出画布、
 * 运行扫描线、快捷命令胶囊、独立输入卡片（聚焦点亮描边）。
 * <p>
 * 输出用 Spannable 按类型着色（命令 / 正常 / 成功 / 错误 / 提示），
 * 但仍只累积到内存、每 {@link #FLUSH_MS} 合并刷一次，避免大输出卡死主线程。
 */
public class TerminalPage {

    private static final int MAX_CHARS = 60000;
    /** 输出合并刷新的间隔：跑编译/装包时一秒几十行，逐行 setText 会把主线程锁死 */
    private static final long FLUSH_MS = 200L;

    // ---- 语义色 ----
    private static final int C_OK = 0xFF6FCF97;
    private static final int C_ERR = 0xFFFF8A80;
    private static final int C_WARN = 0xFFFFB74D;
    private static final int C_AI = 0xFF7DD3A0;

    private final Activity act;
    private final Settings settings;
    private final ShellRunner runner = new ShellRunner();
    private final LinuxEnv linux;

    /** 一行输出：文本 + 文字色 + 可选的整行底色（0 表示无） */
    private static final class Ln {
        final String t;
        final int c;
        final int bg;

        Ln(String t, int c, int bg) {
            this.t = t;
            this.c = c;
            this.bg = bg;
        }
    }

    private final List<Ln> lines = new ArrayList<>();
    private int totalChars;
    private boolean dirty;
    private boolean flushPending;

    private final List<String> history = new ArrayList<>();
    private int histIndex = -1;

    private File home;
    private String cwd;

    // ---- 主题色缓存 ----
    private final int cText;
    private final int cDim;
    private final int cAccent;
    private final int cCmdBg;

    private LinearLayout root;
    private ScrollView scroll;
    private TextView out;
    private TextView tvMode;
    private TextView tvCwd;
    private TextView tvTimer;
    private TextView btnRun;
    private EditText input;
    private View dot;
    private View scanBar;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ValueAnimator scanAnim;
    private Runnable tick;
    private long startAt;
    private boolean running;
    private int pulse;

    public TerminalPage(Activity act) {
        this.linux = LinuxEnv.get(act);
        this.act = act;
        this.settings = Settings.load(act);
        this.cText = Palette.text(act);
        this.cDim = Palette.dim(act);
        this.cAccent = Palette.accent(act);
        this.cCmdBg = Palette.accentSoft(act, 0x14);

        this.home = new File(act.getFilesDir(), "sandbox");
        //noinspection ResultOfMethodCallIgnored
        this.home.mkdirs();
        this.cwd = home.getAbsolutePath();
        build();
        printWelcome();
        List<String> pend = com.nebula.aichat.tool.ShellToolProvider.drainPending();
        if (!pend.isEmpty()) {
            addCmdNote("—— AI 后台执行记录 ——");
            for (String l : pend) addLine(l, cDim);
        }
        com.nebula.aichat.util.MemTrim.register(memListener);
    }

    // 内存告急时先砍输出缓冲：它是纯展示用的、可再生的一块
    private final com.nebula.aichat.util.MemTrim.Listener memListener =
            new com.nebula.aichat.util.MemTrim.Listener() {
                @Override
                public void onTrim(int level, boolean critical) {
                    if (critical) shrinkForMemory();
                }
            };

    /**
     * 把已积累的输出砍到很小（保留最近约 1/5）。
     *
     * 终端输出是"可再生"的：砍掉不影响命令本身，只是看不到更早的日志；
     * 但它往往占着几百 KB 到数 MB 的 Spannable，内存告急时最值得先丢。
     * 只保留最近一段，避免用户刚跑完命令就面对一块空白。
     */
    public void shrinkForMemory() {
        synchronized (lines) {
            int keep = Math.max(20, lines.size() / 5);
            while (lines.size() > keep) {
                Ln first = lines.remove(0);
                totalChars -= first.t.length() + 1;
            }
            if (totalChars < 0) totalChars = 0;
            dirty = true;
        }
        scheduleFlush();
    }

    public View getView() {
        return root;
    }

    public void onShown() {
        refreshHeader();
        // 页面不可见期间累积的输出不渲染，切回来这一次补齐
        flushNow();
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        root = Ui.col(act);
        root.setBackgroundColor(Palette.bg(act));

        // ============================ 顶部状态栏 ============================
        LinearLayout head = Ui.col(act);
        head.setPadding(Ui.dp(act, 14), Ui.dp(act, 11), Ui.dp(act, 14), Ui.dp(act, 8));

        LinearLayout titleRow = Ui.row(act);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        // 呼吸状态灯：就绪常亮，运行中缓慢明暗
        dot = new View(act);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Palette.c(C_OK));
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(act, 8), Ui.dp(act, 8));
        dlp.rightMargin = Ui.dp(act, 8);
        titleRow.addView(dot, dlp);

        TextView title = Ui.text(act, "终端", 14.5f, cText);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.06f);
        titleRow.addView(title);

        View gap = new View(act);
        titleRow.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));

        // 运行计时贴在标题右侧，始终可见、不随输出滚走
        tvTimer = Ui.text(act, "", 11f, cDim);
        tvTimer.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = Ui.dp(act, 8);
        titleRow.addView(tvTimer, tlp);

        titleRow.addView(iconChip("⌫", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearScreen();
            }
        }));

        TextView btnSwitch = iconChip("⇄", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModeDialog();
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = Ui.dp(act, 6);
        titleRow.addView(btnSwitch, mlp);

        head.addView(titleRow);

        // 第二行：执行方式胶囊 + 当前目录（点一下复制）
        LinearLayout metaRow = Ui.row(act);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mrlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mrlp.topMargin = Ui.dp(act, 8);

        tvMode = Ui.text(act, "", 10.5f, cAccent);
        tvMode.setTypeface(Typeface.MONOSPACE);
        tvMode.setPadding(Ui.dp(act, 8), Ui.dp(act, 3), Ui.dp(act, 8), Ui.dp(act, 3));
        GradientDrawable modeBg = new GradientDrawable();
        modeBg.setColor(Palette.accentSoft(act, 0x1A));
        modeBg.setCornerRadius(Ui.dp(act, 7));
        modeBg.setStroke(Math.max(1, Ui.dp(act, 0.8f)), Palette.accentSoft(act, 0x44));
        tvMode.setBackground(modeBg);
        tvMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModeDialog();
            }
        });
        metaRow.addView(tvMode);

        tvCwd = Ui.text(act, cwd, 11f, cDim);
        tvCwd.setTypeface(Typeface.MONOSPACE);
        tvCwd.setSingleLine(true);
        tvCwd.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvCwd.setPadding(Ui.dp(act, 2), 0, 0, 0);
        tvCwd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(cwd);
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(act, 8);
        metaRow.addView(tvCwd, clp);

        head.addView(metaRow, mrlp);

        root.addView(head);

        // ============================ 运行扫描线 ============================
        // 用 FrameLayout 包一层：扫描线只在原地左右滑动，出现/消失不顶动下面的画布
        FrameLayout scanHost = new FrameLayout(act);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 3));
        swlp.leftMargin = Ui.dp(act, 14);
        swlp.rightMargin = Ui.dp(act, 14);

        scanBar = new View(act);
        GradientDrawable sBg = new GradientDrawable();
        sBg.setColor(cAccent);
        sBg.setCornerRadius(Ui.dp(act, 2));
        scanBar.setBackground(sBg);
        scanBar.setVisibility(View.INVISIBLE);
        scanBar.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(act, 110), Ui.dp(act, 3)));
        scanHost.addView(scanBar);
        root.addView(scanHost, swlp);

        // ============================ 输出画布 ============================
        LinearLayout canvas = Ui.col(act);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(Palette.surface(act));
        cb.setCornerRadius(Ui.dp(act, 14));
        cb.setStroke(Math.max(1, Ui.dp(act, 0.8f)), Palette.stroke(act));
        canvas.setBackground(cb);
        canvas.setClipToOutline(true);

        scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setFillViewport(true);
        out = Ui.text(act, "", 12f, cText);
        out.setTypeface(Typeface.MONOSPACE);
        // 长按走自定义菜单（复制全部 / 清屏）；否则文本选择会吞掉长按事件
        out.setTextIsSelectable(false);
        out.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showOutMenu();
                return true;
            }
        });
        out.setLineSpacing(Ui.dp(act, 2.5f), 1f);
        int p = Ui.dp(act, 13);
        out.setPadding(p, p, p, p);
        scroll.addView(out);
        // 输出很短时下方是一大片空白，空白处长按也要能出菜单
        scroll.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showOutMenu();
                return true;
            }
        });
        canvas.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout.LayoutParams canLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        canLp.leftMargin = Ui.dp(act, 12);
        canLp.rightMargin = Ui.dp(act, 12);
        canLp.topMargin = Ui.dp(act, 4);
        root.addView(canvas, canLp);

        // ============================ 快捷命令 ============================
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout chips = Ui.row(act);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 2));
        String[] quick = {"ls -la", "pwd", "df -h .", "uname -a", "du -sh *", "ps -A | head", "help"};
        for (String q : quick) {
            final String cmd = q;
            TextView c = quickChip(q, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    input.setText(cmd);
                    execute(cmd, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 6);
            chips.addView(c, lp);
        }
        TextView cHist = quickChip("历史 ↑", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recallHistory();
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = Ui.dp(act, 4);
        chips.addView(cHist, hlp);
        hs.addView(chips);
        root.addView(hs);

        // ============================ 输入卡片 ============================
        final LinearLayout inCard = Ui.row(act);
        inCard.setGravity(Gravity.CENTER_VERTICAL);
        applyInputBg(inCard, false);
        inCard.setPadding(Ui.dp(act, 12), Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4));

        TextView prompt = Ui.text(act, "❯", 13f, cAccent);
        prompt.setTypeface(Typeface.DEFAULT_BOLD);
        inCard.addView(prompt);

        input = new EditText(act);
        input.setHint("输入命令，回车执行");
        input.setHintTextColor(blend(cDim, Palette.bg(act), 0.30f));
        input.setTextColor(cText);
        input.setTextSize(12.5f);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyInputBg(inCard, hasFocus);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    execute(v.getText().toString(), true);
                    return true;
                }
                return false;
            }
        });
        inCard.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnRun = runButton();
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                Ui.dp(act, 38), Ui.dp(act, 38));
        rlp.leftMargin = Ui.dp(act, 6);
        inCard.addView(btnRun, rlp);

        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.leftMargin = Ui.dp(act, 12);
        inLp.rightMargin = Ui.dp(act, 12);
        inLp.topMargin = Ui.dp(act, 6);
        inLp.bottomMargin = Ui.dp(act, 10);
        root.addView(inCard, inLp);

        refreshHeader();
    }

    /** 输入卡片底：平时细描边，聚焦时描边点亮成强调色并微微提亮底色 */
    private void applyInputBg(View v, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? Palette.surface2(act) : Palette.surface(act));
        bg.setCornerRadius(Ui.dp(act, 13));
        bg.setStroke(Math.max(1, Ui.dp(act, focused ? 1.2f : 0.8f)),
                focused ? Palette.accentSoft(act, 0x99) : Palette.stroke(act));
        v.setBackground(bg);
    }

    /** 标题栏的方形图标按钮 */
    private TextView iconChip(String s, View.OnClickListener l) {
        TextView tv = Ui.text(act, s, 13f, cDim);
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth(Ui.dp(act, 32));
        tv.setMinHeight(Ui.dp(act, 30));
        tv.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.surface2(act));
        bg.setCornerRadius(Ui.dp(act, 9));
        bg.setStroke(Math.max(1, Ui.dp(act, 0.8f)), Palette.stroke(act));
        tv.setBackground(Ui.pressable(act, bg));
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    /** 快捷命令小胶囊：等宽字，只有细描边，不抢视觉 */
    private TextView quickChip(String s, View.OnClickListener l) {
        TextView tv = Ui.text(act, s, 11f, cDim);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(act, 9), Ui.dp(act, 5), Ui.dp(act, 9), Ui.dp(act, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.surface(act));
        bg.setCornerRadius(Ui.dp(act, 8));
        bg.setStroke(Math.max(1, Ui.dp(act, 0.8f)), Palette.stroke(act));
        tv.setBackground(Ui.pressable(act, bg));
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    /** 圆形运行按钮：空闲 = 强调色实心播放符，运行中 = 红色停止块 */
    private TextView runButton() {
        final TextView tv = Ui.text(act, "▶", 13f, Palette.c(0xFFFFFFFF));
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(cAccent);
        tv.setBackground(Ui.pressable(act, bg));
        tv.setClickable(true);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (runner.isRunning()) {
                    runner.cancel();
                    addLine("^C 已中断", Palette.c(C_ERR));
                    decorateRunButton(false);
                } else {
                    execute(input.getText().toString(), true);
                }
            }
        });
        return tv;
    }

    private void decorateRunButton(boolean busy) {
        if (btnRun == null) return;
        btnRun.setText(busy ? "■" : "▶");
        btnRun.setTextSize(busy ? 12f : 13f);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(busy ? Palette.c(0xFFE05A4F) : cAccent);
        btnRun.setBackground(Ui.pressable(act, bg));
    }

    /** 主/次色混合，用于 hint 这类需要"比暗更暗一点"的文字 */
    private static int blend(int fg, int bg, float bgRatio) {
        float f = Math.max(0f, Math.min(1f, bgRatio));
        int r = (int) (android.graphics.Color.red(fg) * (1 - f) + android.graphics.Color.red(bg) * f);
        int g = (int) (android.graphics.Color.green(fg) * (1 - f) + android.graphics.Color.green(bg) * f);
        int b = (int) (android.graphics.Color.blue(fg) * (1 - f) + android.graphics.Color.blue(bg) * f);
        return android.graphics.Color.rgb(r, g, b);
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("path", s));
                Toast.makeText(act, "路径已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshHeader() {
        if (tvMode == null) return;
        boolean remote = "remote".equals(settings.execMode);
        if ("alpine".equals(settings.execMode)) {
            tvMode.setText(linux.isInstalled() ? "Alpine " + linux.versionText() : "Alpine · 未安装");
        } else {
            tvMode.setText(remote
                    ? (settings.remoteExecUrl.isEmpty() ? "远程 · 未配置" : "远程执行")
                    : "本地 · " + ShellRunner.shellPath());
        }
        tvCwd.setText(pretty(cwd));
    }

    /** 工作区内显示成 ~/xxx，外面显示全路径 */
    private String pretty(String path) {
        String base = home.getAbsolutePath();
        if (path == null) return "~";
        if (path.equals(base)) return "~";
        if (path.startsWith(base)) return "~" + path.substring(base.length());
        return path;
    }

    private void showModeDialog() {
        String alpineLabel = "内置 Alpine Linux" + (linux.isInstalled() ? "" : "（未安装，点选后安装）");
        final String[] items = {"本地（手机 toybox shell）", alpineLabel, "远程（HTTP 执行服务）"};
        int checked = "alpine".equals(settings.execMode) ? 1 : ("remote".equals(settings.execMode) ? 2 : 0);
        AlertDialog.Builder b = new NebAlert.Builder(act);
        b.setTitle("执行方式");
        b.setSingleChoiceItems(items, checked, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                if (which == 1) {
                    settings.execMode = "alpine";
                    settings.save(act);
                    d.dismiss();
                    refreshHeader();
                    if (!linux.isInstalled()) {
                        InstallDialog.show(act, new Runnable() {
                            @Override
                            public void run() {
                                refreshHeader();
                            }
                        });
                    }
                    return;
                }
                settings.execMode = which == 0 ? "local" : "remote";
                settings.save(act);
                d.dismiss();
                refreshHeader();
                if (which == 2 && settings.remoteExecUrl.trim().isEmpty()) askRemoteUrl();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    public void askRemoteUrl() {
        final EditText et = new EditText(act);
        et.setHint("http://192.168.1.10:8080");
        et.setText(settings.remoteExecUrl);
        et.setTextColor(cText);
        et.setHintTextColor(cDim);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        final EditText token = new EditText(act);
        token.setHint("访问密钥（可留空）");
        token.setText(settings.remoteExecToken);
        token.setTextColor(cText);
        token.setHintTextColor(cDim);

        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 16);
        box.setPadding(p, Ui.dp(act, 8), p, 0);
        box.addView(Ui.text(act, "远程执行服务地址（需实现 POST /exec）", 12f, cDim));
        box.addView(et);
        box.addView(token);

        new NebAlert.Builder(act)
                .setTitle("远程执行配置")
                .setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        settings.remoteExecUrl = et.getText().toString().trim();
                        settings.remoteExecToken = token.getText().toString().trim();
                        settings.execMode = "remote";
                        settings.save(act);
                        refreshHeader();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // -------------------------------------------------------------- 执行逻辑

    private void printWelcome() {
        addLine("AgenCode Terminal · " + ShellRunner.shellPath(), cAccent);
        addLine("工作目录 " + pretty(cwd), cDim);
        addLine("输入 help 查看内置命令，cd 切换目录", cDim);
        addLine("", 0);
    }

    private void execute(String raw, boolean echo) {
        final String cmd = raw == null ? "" : raw.trim();
        if (cmd.isEmpty()) return;

        input.setText("");
        history.add(cmd);
        histIndex = history.size();

        if (echo) addCmdLine(pretty(cwd) + " ❯ " + cmd);

        // 内置命令
        if (handleBuiltin(cmd)) return;

        if (runner.isRunning()) {
            addLine("上一条命令仍在执行，先中断它", Palette.c(C_WARN));
            return;
        }

        boolean remote = "remote".equals(settings.execMode);
        if (remote && settings.remoteExecUrl.trim().isEmpty()) {
            addLine("远程地址未配置，已回退本地执行", Palette.c(C_WARN));
            remote = false;
        }

        startLive("执行中");
        decorateRunButton(true);

        ShellRunner.Callback cb = new ShellRunner.Callback() {
            @Override
            public void onOutput(final String line) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        addLine(line, cText);
                    }
                });
            }

            @Override
            public void onFinish(final int code, final String error) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            addLine("[执行失败] " + error, Palette.c(C_ERR));
                            endLive("✕ 失败", Palette.c(C_ERR));
                            setDot(Palette.c(C_ERR));
                        } else if (code != 0) {
                            addLine("[退出码 " + code + "]", Palette.c(C_ERR));
                            endLive("✕ 退出码 " + code, Palette.c(C_ERR));
                            setDot(Palette.c(C_ERR));
                        } else {
                            addLine("✓ 命令结束", Palette.c(C_OK));
                            endLive("✓ 完成", Palette.c(C_OK));
                            setDot(Palette.c(C_OK));
                        }
                        decorateRunButton(false);
                    }
                });
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        if ("alpine".equals(settings.execMode)) {
            runner.runAlpine(cmd, cb);
        } else if (remote) {
            runner.runRemote(settings.remoteExecUrl, settings.remoteExecToken, cmd, cwd, cb);
        } else {
            runner.runLocal(cmd, cwd, home.getAbsolutePath(), cb);
        }
    }

    /** 返回 true 表示已处理（不再交给 shell） */
    private boolean handleBuiltin(String cmd) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (lower.equals("clear") || lower.equals("cls")) {
            clearScreen();
            return true;
        }
        if (lower.equals("help")) {
            addLine("内置命令：", cAccent);
            addLine("  clear        清屏", cText);
            addLine("  cd <目录>    切换工作目录（支持 .. 和相对路径）", cText);
            addLine("  pwd          显示当前目录", cText);
            addLine("  mode         切换执行方式（本地 / 内置 Alpine / 远程）", cText);
            addLine("  help         本帮助", cText);
            addLine("其余命令直接交给系统 shell 执行。", cDim);
            return true;
        }
        if (lower.startsWith("cd ") || lower.equals("cd")) {
            String arg = cmd.length() > 2 ? cmd.substring(3).trim() : "";
            if (arg.isEmpty() || arg.equals("~")) arg = home.getAbsolutePath();
            changeDir(arg);
            return true;
        }
        if (lower.equals("pwd")) {
            addLine(cwd, cText);
            return true;
        }
        if (lower.equals("mode")) {
            showModeDialog();
            return true;
        }
        return false;
    }

    private void changeDir(String arg) {
        File target = new File(arg);
        if (!target.isAbsolute()) target = new File(cwd, arg);
        try {
            target = target.getCanonicalFile();
        } catch (Exception ignored) {
        }
        if (!target.isDirectory()) {
            addLine("cd: 目录不存在 " + target.getAbsolutePath(), Palette.c(C_ERR));
            return;
        }
        String path = target.getAbsolutePath();
        if (!path.startsWith(home.getAbsolutePath())) {
            addLine("提示：已离开工作区 " + pretty(home.getAbsolutePath()) + "，可能受系统权限限制", Palette.c(C_WARN));
        }
        cwd = path;
        refreshHeader();
        addLine("→ " + pretty(cwd), cAccent);
    }

    private void recallHistory() {
        if (history.isEmpty()) return;
        if (histIndex <= 0) histIndex = history.size() - 1;
        histIndex = Math.max(0, Math.min(histIndex, history.size() - 1));
        input.setText(history.get(histIndex));
        input.setSelection(input.getText().length());
        histIndex--;
        if (histIndex < 0) histIndex = 0;
    }

    // ------------------------------------------------------------ 运行态指示

    /** 状态灯：运行时呼吸（琥珀），结束后回到绿/红 */
    private void setDot(int color) {
        if (dot == null) return;
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(color);
        dot.setBackground(dg);
        dot.setAlpha(1f);
    }

    /** 开始执行：扫描线循环滑动 + 计时跳动，状态灯呼吸 */
    private void startLive(String what) {
        running = true;
        startAt = SystemClock.uptimeMillis();
        setDot(Palette.c(C_WARN));
        startScan();
        if (tvTimer != null) tvTimer.setTextColor(Palette.c(C_WARN));
        if (tick != null) ui.removeCallbacks(tick);
        tick = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                long ms = SystemClock.uptimeMillis() - startAt;
                if (tvTimer != null) tvTimer.setText("● " + fmtDur(ms));
                // 呼吸：0.45 ~ 1.0 明暗来回
                pulse = (pulse + 1) % 12;
                float a = 0.45f + 0.55f * Math.abs(6 - pulse) / 6f;
                if (dot != null) dot.setAlpha(a);
                ui.postDelayed(this, 250);
            }
        };
        ui.postDelayed(tick, 250);
    }

    /** 执行结束：停止扫描，计时定格成结论，2.6 秒后淡出 */
    private void endLive(String result, int color) {
        running = false;
        stopScan();
        if (tick != null) {
            ui.removeCallbacks(tick);
            tick = null;
        }
        if (dot != null) dot.setAlpha(1f);
        long ms = startAt > 0 ? SystemClock.uptimeMillis() - startAt : 0;
        startAt = 0;
        if (tvTimer == null) return;
        tvTimer.setTextColor(color);
        tvTimer.setText(result + (ms > 0 ? " · " + fmtDur(ms) : ""));
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running && tvTimer != null) tvTimer.setText("");
            }
        }, 2600);
    }

    /** 扫描线：一条短亮条从左滑到右，循环不停 */
    private void startScan() {
        if (scanBar == null) return;
        stopScan();
        scanBar.setVisibility(View.VISIBLE);
        final int track = Math.max(Ui.dp(act, 60), root.getWidth() - Ui.dp(act, 28));
        final int barW = Ui.dp(act, 110);
        scanAnim = ValueAnimator.ofFloat(0f, 1f);
        scanAnim.setDuration(1000);
        scanAnim.setRepeatCount(ValueAnimator.INFINITE);
        scanAnim.setInterpolator(new LinearInterpolator());
        scanAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (float) a.getAnimatedValue();
                scanBar.setTranslationX(-barW + f * (track + barW));
            }
        });
        scanAnim.start();
    }

    private void stopScan() {
        if (scanAnim != null) {
            scanAnim.cancel();
            scanAnim = null;
        }
        if (scanBar != null) scanBar.setVisibility(View.INVISIBLE);
    }

    /** 耗时：1 秒内毫秒，10 秒内一位小数，再长取整秒 */
    private static String fmtDur(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 10000) return String.format(Locale.ROOT, "%.1fs", ms / 1000f);
        return (ms / 1000) + "s";
    }

    // ------------------------------------------------------------ 输出缓冲

    private void addLine(String s, int color) {
        addLine(s, color, 0);
    }

    private void addCmdLine(String s) {
        addLine(s, cAccent, cCmdBg);
    }

    private void addCmdNote(String s) {
        addLine("", 0);
        addLine(s, cAccent);
    }

    /**
     * 追加一行输出。
     * 关键：只累积到内存，由 {@link #scheduleFlush} 每 200ms 合并成一次 setText。
     * 之前是每行都 out.setText(整段日志)，一条命令输出上千行时等于上千次
     * 全量文本重排，主线程被占满，表现就是「跑着跑着所有按钮都没反应」。
     */
    private void addLine(String s, int color, int bg) {
        String text = s == null ? "" : s;
        synchronized (lines) {
            lines.add(new Ln(text, color, bg));
            totalChars += text.length() + 1;
            // 超长时从头部整行丢弃，保证不会出现半行乱码
            while (totalChars > MAX_CHARS && lines.size() > 1) {
                Ln first = lines.remove(0);
                totalChars -= first.t.length() + 1;
            }
            dirty = true;
        }
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushPending) return;
        flushPending = true;
        final TextView tv = out;
        if (tv == null) {
            flushPending = false;
            return;
        }
        tv.postDelayed(new Runnable() {
            @Override
            public void run() {
                flushPending = false;
                flushNow();
            }
        }, FLUSH_MS);
    }

    /** 把累积的输出一次性刷到界面；终端页当前不可见就跳过，等切回来再补 */
    private void flushNow() {
        if (!dirty) return;
        TextView tv = out;
        if (tv == null) return;
        if (tv.getWindowToken() == null || !tv.isShown()) {
            // 用户正在聊天页看 AI 干活，终端这一屏根本没人看，
            // 这时候渲染纯属浪费主线程，直接留着 dirty 等 onShown()
            return;
        }
        List<Ln> snap;
        synchronized (lines) {
            if (!dirty) return;
            dirty = false;
            snap = new ArrayList<>(lines);
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < snap.size(); i++) {
            Ln l = snap.get(i);
            int st = sb.length();
            sb.append(l.t);
            int en = sb.length();
            if (en > st) {
                if (l.c != 0) {
                    sb.setSpan(new ForegroundColorSpan(l.c), st, en,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (l.bg != 0) {
                    sb.setSpan(new BackgroundColorSpan(l.bg), st, en,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            sb.append('\n');
        }
        tv.setText(sb);

        final ScrollView sc = scroll;
        if (sc != null) {
            sc.post(new Runnable() {
                @Override
                public void run() {
                    sc.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void clearScreen() {
        synchronized (lines) {
            lines.clear();
            totalChars = 0;
            dirty = false;
        }
        if (out != null) out.setText("");
    }

    /** 长按输出区：复制全部 / 清屏 */
    private void showOutMenu() {
        try {
            new NebAlert.Builder(act)
                    .setTitle("终端输出")
                    .setItems(new String[]{"复制全部输出", "清屏"},
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int which) {
                                    if (which == 0) copyAllOutput();
                                    else if (which == 1) clearScreen();
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** 把缓冲区里的全部输出送到剪贴板 */
    private void copyAllOutput() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        synchronized (lines) {
            for (int i = 0; i < lines.size(); i++) {
                sb.append(lines.get(i).t).append('\n');
                n++;
            }
        }
        if (sb.length() == 0) {
            Toast.makeText(act, "暂无输出", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("terminal", sb.toString().trim()));
                Toast.makeText(act, "已复制 " + n + " 行", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------- AI 自动执行时的终端镜像

    /** AI 开始执行一条命令 */
    public void aiBegin(final String cmd) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                addCmdNote("⌘ AI 执行");
                addCmdLine("❯ " + cmd);
                startLive("AI 执行中");
                decorateRunButton(true);
                setDot(Palette.c(C_AI));
            }
        });
    }

    /** AI 命令的一行输出（任意线程可直接调用） */
    public void aiLine(String line) {
        addLine(line, cText);
    }

    /** AI 命令的一行输出，指定颜色（流式回调线程安全） */
    public void aiLineAsync(String line) {
        addLine(line, cText);
    }

    /** AI 命令执行结束 */
    public void aiEnd(final int code) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (code == 0) {
                    addLine("✓ 执行完成", Palette.c(C_OK));
                    endLive("✓ 完成", Palette.c(C_OK));
                    setDot(Palette.c(C_OK));
                } else {
                    addLine("[退出码 " + code + "]", Palette.c(C_ERR));
                    endLive("✕ 退出码 " + code, Palette.c(C_ERR));
                    setDot(Palette.c(C_ERR));
                }
                decorateRunButton(false);
            }
        });
    }

    public void setCwd(String path) {
        cwd = path;
        refreshHeader();
        addLine("→ " + pretty(cwd), cAccent);
    }
}
