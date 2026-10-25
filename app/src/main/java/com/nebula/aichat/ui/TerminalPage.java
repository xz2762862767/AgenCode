package com.nebula.aichat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
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

/** 终端页：本地 shell / 远程执行器 */
public class TerminalPage {

    private static final int MAX_CHARS = 60000;
    /** 输出合并刷新的间隔：跑编译/装包时一秒几十行，逐行 setText 会把主线程锁死 */
    private static final long FLUSH_MS = 200L;

    private final Activity act;
    private final Settings settings;
    private final ShellRunner runner = new ShellRunner();
    private final LinuxEnv linux;
    private final StringBuilder log = new StringBuilder();
    /** 有未刷到界面的新输出 */
    private boolean dirty;
    private boolean flushPending;
    private final List<String> history = new ArrayList<>();

    private final LineLayoutHolder hold = new LineLayoutHolder();
    private int histIndex = -1;

    private File home;
    private String cwd;

    private ScrollView scroll;
    private TextView out;
    private TextView tvCwd;
    private TextView tvMode;
    private EditText input;
    private TextView btnRun;

    private LinearLayout root;

    // ---- 执行状态条：命令跑起来后这里会转圈+计时，不随输出滚走 ----
    private LinearLayout liveBar;
    private TextView liveDot;
    private TextView liveText;
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private Runnable liveTick;
    private long liveStartAt;
    private int liveSpin;
    private static final String[] LIVE_SPIN = {"\u25d0", "\u25d3", "\u25d1", "\u25d2"};

    private static class LineLayoutHolder {
    }

    public TerminalPage(Activity act) {
        this.linux = LinuxEnv.get(act);
        this.act = act;
        this.settings = Settings.load(act);
        this.home = new File(act.getFilesDir(), "sandbox");
        //noinspection ResultOfMethodCallIgnored
        this.home.mkdirs();
        this.cwd = home.getAbsolutePath();
        build();
        printWelcome();
        java.util.List<String> pend = com.nebula.aichat.tool.ShellToolProvider.drainPending();
        if (!pend.isEmpty()) {
            appendLine("—— AI 后台执行记录 ——", 0xFF5B93FF);
            for (String l : pend) appendLine(l, 0xFFB9C7DA);
        }
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
        root.setBackgroundColor(0xFF0F1218);

        // 顶部信息条
        LinearLayout head = Ui.row(act);
        head.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 6));

        LinearLayout paths = Ui.col(act);
        tvMode = Ui.text(act, "", 11f, 0xFF5B93FF);
        tvCwd = Ui.text(act, cwd, 11f, 0xFF8B97A9);
        tvCwd.setSingleLine(true);
        tvCwd.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        paths.addView(tvMode);
        paths.addView(tvCwd);
        head.addView(paths, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnClear = Ui.ghostChip(act, "清屏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                log.setLength(0);
                out.setText("");
            }
        });
        head.addView(btnClear);

        TextView btnMode = Ui.ghostChip(act, "切换", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModeDialog();
            }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = Ui.dp(act, 6);
        head.addView(btnMode, mp);

        root.addView(head);
        root.addView(Ui.divider(act));

        // 输出区
        scroll = new ScrollView(act);
        scroll.setBackgroundColor(0xFF0B0E14);
        scroll.setFillViewport(true);
        out = Ui.text(act, "", 11.5f, 0xFFC8D3E5);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        int p = Ui.dp(act, 10);
        out.setPadding(p, p, p, p);
        out.setLineSpacing(Ui.dp(act, 1f), 1f);
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 执行状态条：默认隐藏，命令跑起来才出现
        liveBar = Ui.row(act);
        liveBar.setGravity(Gravity.CENTER_VERTICAL);
        liveBar.setBackgroundColor(0xFF12171F);
        liveBar.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
        liveBar.setVisibility(View.GONE);
        liveDot = Ui.text(act, "", 12f, 0xFFFFC65C);
        liveText = Ui.text(act, "", 12f, 0xFFFFC65C);
        LinearLayout.LayoutParams ldp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ldp.rightMargin = Ui.dp(act, 6);
        liveBar.addView(liveDot, ldp);
        liveBar.addView(liveText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(liveBar);

        // 快捷命令
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(act);
        chips.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 2));
        String[] quick = {"ls -la", "pwd", "df -h .", "uname -a", "getprop ro.product.model",
                "ps -A | head -15", "du -sh *", "help"};
        for (String q : quick) {
            final String cmd = q;
            TextView c = Ui.chip(act, q, 0xFF1A212C, 0xFF9AA7BA, new View.OnClickListener() {
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
        hs.addView(chips);
        root.addView(hs);

        // 输入行
        root.addView(Ui.divider(act));
        LinearLayout bar = Ui.row(act);
        bar.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8));

        TextView prompt = Ui.text(act, "❯", 15f, 0xFF5B93FF);
        prompt.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(prompt);

        input = new EditText(act);
        input.setHint("输入命令，回车执行");
        input.setHintTextColor(0xFF5A6577);
        input.setTextColor(0xFFE8EDF6);
        input.setTextSize(13f);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
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
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ip.leftMargin = Ui.dp(act, 8);
        bar.addView(input, ip);

        TextView btnHist = Ui.ghostChip(act, "↑", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recallHistory();
            }
        });
        bar.addView(btnHist);

        btnRun = Ui.chip(act, "运行", 0xFF1E3A6B, 0xFF9DC0FF, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (runner.isRunning()) {
                    runner.cancel();
                    appendLine("^C 已中断", 0xFFFF8A80);
                } else {
                    execute(input.getText().toString(), true);
                }
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = Ui.dp(act, 6);
        bar.addView(btnRun, rp);

        root.addView(bar);
        refreshHeader();
    }

    private void refreshHeader() {
        boolean remote = "remote".equals(settings.execMode);
        if ("alpine".equals(settings.execMode)) {
            tvMode.setText(linux.isInstalled() ? "Alpine Linux · " + linux.versionText() : "Alpine（未安装）");
        } else {
            tvMode.setText(remote ? "远程执行" + (settings.remoteExecUrl.isEmpty() ? "（未配置地址）" : "")
                    : "本地执行 · " + ShellRunner.shellPath());
        }
        tvCwd.setText(cwd);
    }

    private void showModeDialog() {
        String alpineLabel = "内置 Alpine Linux" + (linux.isInstalled() ? "" : "（未安装，点选后安装）");
        final String[] items = {"本地（手机 toybox shell）", alpineLabel, "远程（HTTP 执行服务）"};
        int checked = "alpine".equals(settings.execMode) ? 1 : ("remote".equals(settings.execMode) ? 2 : 0);
        AlertDialog.Builder b = new AlertDialog.Builder(act);
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
        et.setTextColor(0xFFE8EDF6);
        et.setHintTextColor(0xFF5A6577);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        final EditText token = new EditText(act);
        token.setHint("访问密钥（可留空）");
        token.setText(settings.remoteExecToken);
        token.setTextColor(0xFFE8EDF6);
        token.setHintTextColor(0xFF5A6577);

        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 16);
        box.setPadding(p, Ui.dp(act, 8), p, 0);
        box.addView(Ui.text(act, "远程执行服务地址（需实现 POST /exec）", 12f, 0xFF8B97A9));
        box.addView(et);
        box.addView(token);

        new AlertDialog.Builder(act)
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
        appendLine("星云终端 · 本地 toybox shell", 0xFF5B93FF);
        appendLine("工作目录 " + cwd, 0xFF8B97A9);
        appendLine("输入 help 查看内置命令；cd 切换目录；支持 ls/cat/grep/find/ps/df 等系统命令", 0xFF8B97A9);
        appendLine("", 0);
    }

    private void execute(String raw, boolean echo) {
        final String cmd = raw == null ? "" : raw.trim();
        if (cmd.isEmpty()) return;

        input.setText("");
        history.add(cmd);
        histIndex = history.size();

        if (echo) appendLine(cwd.substring(Math.max(0, cwd.lastIndexOf('/'))) + " ❯ " + cmd, 0xFF6FA8FF);

        // 内置命令
        if (handleBuiltin(cmd)) return;

        if (runner.isRunning()) {
            appendLine("上一条命令仍在执行，先中断它", 0xFFFFB74D);
            return;
        }

        boolean remote = "remote".equals(settings.execMode);
        if (remote && settings.remoteExecUrl.trim().isEmpty()) {
            appendLine("远程地址未配置，已回退本地执行", 0xFFFFB74D);
            remote = false;
        }

        btnRun.setText("中断");
        btnRun.setTextColor(0xFFFF8A80);
        btnRun.setBackground(Ui.roundRect(act, 0xFF3A1E1E, 0, 16f));

        ShellRunner.Callback cb = new ShellRunner.Callback() {
            @Override
            public void onOutput(final String line) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appendLine(line, 0xFFC8D3E5);
                    }
                });
            }

            @Override
            public void onFinish(final int code, final String error) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            appendLine("[执行失败] " + error, 0xFFFF8A80);
                            endLive("\u2715 执行失败", 0xFFFF8A80);
                        } else if (code != 0) {
                            appendLine("[退出码 " + code + "]", 0xFFFF8A80);
                            endLive("\u2715 退出码 " + code, 0xFFFF8A80);
                        } else {
                            appendLine("\u2713 命令结束", 0xFF6FCF97);
                            endLive("\u2713 执行完成", 0xFF6FCF97);
                        }
                        btnRun.setText("运行");
                        btnRun.setTextColor(0xFF9DC0FF);
                        btnRun.setBackground(Ui.roundRect(act, 0xFF1E3A6B, 0, 16f));
                    }
                });
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        if ("alpine".equals(settings.execMode)) {
            showLive("Alpine 执行中");
            runner.runAlpine(cmd, cb);
        } else if (remote) {
            showLive("远程执行中");
            runner.runRemote(settings.remoteExecUrl, settings.remoteExecToken, cmd, cwd, cb);
        } else {
            showLive("执行中");
            runner.runLocal(cmd, cwd, home.getAbsolutePath(), cb);
        }
    }

    /** 返回 true 表示已处理（不再交给 shell） */
    private boolean handleBuiltin(String cmd) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (lower.equals("clear") || lower.equals("cls")) {
            log.setLength(0);
            out.setText("");
            return true;
        }
        if (lower.equals("help")) {
            appendLine("内置命令：", 0xFF5B93FF);
            appendLine("  clear        清屏", 0xFFC8D3E5);
            appendLine("  cd <目录>    切换工作目录（支持 .. 和相对路径）", 0xFFC8D3E5);
            appendLine("  pwd          显示当前目录", 0xFFC8D3E5);
            appendLine("  mode         切换执行方式（本地 / 内置 Alpine / 远程）", 0xFFC8D3E5);
            appendLine("  open <路径>  用系统应用打开文件", 0xFFC8D3E5);
            appendLine("  help         本帮助", 0xFFC8D3E5);
            appendLine("其余命令直接交给系统 shell 执行。", 0xFF8B97A9);
            return true;
        }
        if (lower.startsWith("cd ") || lower.equals("cd")) {
            String arg = cmd.length() > 2 ? cmd.substring(3).trim() : "";
            if (arg.isEmpty() || arg.equals("~")) arg = home.getAbsolutePath();
            changeDir(arg);
            return true;
        }
        if (lower.equals("pwd")) {
            appendLine(cwd, 0xFFC8D3E5);
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
            appendLine("cd: 目录不存在 " + target.getAbsolutePath(), 0xFFFF8A80);
            return;
        }
        String path = target.getAbsolutePath();
        if (!path.startsWith(home.getAbsolutePath())) {
            // 沙箱外只读访问提示
            appendLine("提示：已离开工作区 " + home.getAbsolutePath() + "，可能受系统权限限制", 0xFFFFB74D);
        }
        cwd = path;
        refreshHeader();
        appendLine("→ " + cwd, 0xFF6FA8FF);
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

    /** 开始执行：状态条出现，每 0.4 秒刷新一次旋转符和耗时 */
    private void showLive(final String what) {
        if (liveBar == null) return;
        liveStartAt = SystemClock.uptimeMillis();
        liveBar.setVisibility(View.VISIBLE);
        liveText.setTextColor(0xFFFFC65C);
        liveText.setText(what + " …");
        if (liveTick != null) liveHandler.removeCallbacks(liveTick);
        liveTick = new Runnable() {
            private int ticks = 0;

            @Override
            public void run() {
                if (liveBar == null || liveStartAt == 0) return;
                liveSpin++;
                long ms = SystemClock.uptimeMillis() - liveStartAt;
                liveDot.setText(LIVE_SPIN[liveSpin % LIVE_SPIN.length]);
                liveText.setText(what + " · 已用 " + fmtDur(ms) + "（右上角可中断）");
                // 兜底：万一回调没来，转够 40 分钟自己停，不让它一直跑
                if (++ticks < 6000) liveHandler.postDelayed(this, 400);
            }
        };
        liveDot.setText(LIVE_SPIN[0]);
        liveHandler.postDelayed(liveTick, 400);
    }

    /** 执行结束：状态条停在明确结论上，几秒后自动收起 */
    private void endLive(final String result, final int color) {
        if (liveBar == null) return;
        if (liveTick != null) {
            liveHandler.removeCallbacks(liveTick);
            liveTick = null;
        }
        long ms = liveStartAt > 0 ? SystemClock.uptimeMillis() - liveStartAt : 0;
        liveStartAt = 0;
        liveDot.setText("");
        liveText.setTextColor(color);
        liveText.setText(result + (ms > 0 ? " · 用时 " + fmtDur(ms) : ""));
        liveHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (liveStartAt == 0 && liveBar != null) liveBar.setVisibility(View.GONE);
            }
        }, 6000);
    }

    /** 耗时：1 秒内毫秒，10 秒内一位小数，再长取整秒 */
    private static String fmtDur(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 10000) return String.format(Locale.ROOT, "%.1fs", ms / 1000f);
        return (ms / 1000) + "s";
    }

    /**
     * 追加一行输出。
     * 关键：这里只累积到内存，由 [scheduleFlush] 每 150ms 合并成一次 setText。
     * 之前是每行都 out.setText(整段日志)，一条命令输出上千行时等于上千次
     * 全量文本重排，主线程被占满，表现就是「跑着跑着所有按钮都没反应」。
     */
    private void appendLine(String s, int color) {
        synchronized (log) {
            log.append(s).append('\n');
            if (log.length() > MAX_CHARS) {
                log.delete(0, log.length() - MAX_CHARS / 2);
                log.insert(0, "…（输出过长，已截断）\n");
            }
            dirty = true;
        }
        scheduleFlush();
    }

    /**
     * AI 命令的一行输出（任意线程可直接调用）。
     * 只进缓冲区 + 触发合并刷新，不投递 UI 任务 ——
     * 一条命令吐上万行时，逐行 runOnUiThread 会往主线程队列塞上万个任务。
     */
    public void aiLineAsync(String line) {
        appendLine(line, 0xFFB9C7DA);
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
        String text;
        synchronized (log) {
            if (!dirty) return;
            dirty = false;
            text = log.toString();
        }
        tv.setText(text);
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

    // ---------------------------------------------- AI 自动执行时的终端镜像

    /** AI 开始执行一条命令 */
    public void aiBegin(final String cmd) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendLine("⌘ " + cmd, 0xFF7DD3A0);
                showLive("AI 执行中");
            }
        });
    }

    /** AI 命令的一行输出 */
    public void aiLine(final String line) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendLine(line, 0xFFB9C7DA);
            }
        });
    }

    /** AI 命令执行结束 */
    public void aiEnd(final int code) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (code == 0) {
                    appendLine("✓ 执行完成", 0xFF6FCF97);
                    endLive("\u2713 执行完成", 0xFF6FCF97);
                } else {
                    appendLine("[退出码 " + code + "]", 0xFFFF8A80);
                    endLive("\u2715 退出码 " + code, 0xFFFF8A80);
                }
            }
        });
    }

    public void setCwd(String path) {
        cwd = path;
        refreshHeader();
        appendLine("→ " + cwd, 0xFF6FA8FF);
    }
}
