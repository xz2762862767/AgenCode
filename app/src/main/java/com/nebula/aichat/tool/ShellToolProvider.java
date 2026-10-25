package com.nebula.aichat.tool;

import android.content.Context;

import android.util.Log;

import com.nebula.aichat.apk.SandboxBridge;
import com.nebula.aichat.data.Settings;
import com.nebula.aichat.linux.LinuxEnv;
import com.nebula.aichat.shell.ShellRunner;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 让模型能真的在终端里跑命令：AI 边回答边执行，输出同步回显到终端页。
 * 执行通道与终端页一致（Linux / 本地 toybox / 远程执行服务）。
 */
public class ShellToolProvider {

    /** 单次命令回传给模型的最大字符数，超长只保留尾部 */
    private static final int MAX_RESULT_CHARS = 12000;
    private static final int DEFAULT_TIMEOUT = 120;
    private static final int MAX_TIMEOUT = 600;
    /** 终端页还没创建时，先把回显存下来，等它建好后回填 */
    private static final int PENDING_MAX = 400;
    private static final ArrayDeque<String> pending = new ArrayDeque<String>();

    /** 终端镜像：AI 执行的命令与输出同步显示在终端页 */
    public interface Mirror {
        void begin(String cmd);

        void line(String line);

        void end(int code);
    }

    private static volatile Mirror mirror;

    public static void setMirror(Mirror m) {
        mirror = m;
    }

    private static void pending(String line) {
        synchronized (pending) {
            pending.addLast(line);
            while (pending.size() > PENDING_MAX) pending.removeFirst();
        }
    }

    /** 终端页创建时取走缓存的回显行 */
    public static List<String> drainPending() {
        synchronized (pending) {
            List<String> out = new ArrayList<String>(pending);
            pending.clear();
            return out;
        }
    }

    public static void register(ToolRegistry reg, final Context ctx) {
        reg.add(new ToolDef("run_shell",
                "在手机内置终端里真实执行一条 shell 命令并拿到输出。"
                        + "适合：跑用户要求的命令、用脚本验证结果、查看/改动文件、装依赖、编译运行代码。"
                        + "命令默认在沙盒工作目录执行，可用 && 串多步。"
                        + "不要执行破坏性命令（rm -rf /、格式化、清空用户数据等）。",
                ToolDef.schema(ToolDef.props(
                                "command", ToolDef.prop("string", "要执行的完整命令，如 python3 hello.py"),
                                "timeout", ToolDef.prop("integer", "超时秒数，默认 120，最大 600")),
                        "command"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        String cmd = a.optString("command", "").trim();
                        if (cmd.isEmpty()) return "错误：command 不能为空。";
                        int timeout = a.optInt("timeout", DEFAULT_TIMEOUT);
                        if (timeout <= 0) timeout = DEFAULT_TIMEOUT;
                        if (timeout > MAX_TIMEOUT) timeout = MAX_TIMEOUT;
                        return exec(ctx, cmd, timeout);
                    }
                }));
    }

    private static String exec(Context ctx, final String cmd, int timeoutSec) {
        Settings s = Settings.load(ctx);
        File home = new File(ctx.getFilesDir(), "sandbox");
        //noinspection ResultOfMethodCallIgnored
        if (!home.exists()) home.mkdirs();

        final StringBuilder buf = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        final int[] code = new int[]{-1};
        final String[] err = new String[] { null };
        final long startedAt = System.currentTimeMillis();
        final ShellRunner runner = new ShellRunner();

        final Mirror m = mirror;
        if (m != null) m.begin(cmd);
        else pending("⌘ " + cmd);

        ShellRunner.Callback cb = new ShellRunner.Callback() {
            @Override
            public void onOutput(String line) {
                synchronized (buf) {
                    if (buf.length() < MAX_RESULT_CHARS * 2) buf.append(line).append('\n');
                }
                Mirror mm = mirror;
                if (mm != null) mm.line(line);
                else pending(line);
            }

            @Override
            public void onFinish(int c, String e) {
                code[0] = c;
                err[0] = e;
                done.countDown();
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        String mode = s.execMode == null ? "local" : s.execMode;
        if ("alpine".equals(mode)) {
            runner.runAlpine(cmd, cb);
        } else if ("remote".equals(mode) && !s.remoteExecUrl.trim().isEmpty()) {
            runner.runRemote(s.remoteExecUrl, s.remoteExecToken, cmd, home.getAbsolutePath(), cb);
        } else {
            runner.runLocal(cmd, home.getAbsolutePath(), home.getAbsolutePath(), cb);
        }

        boolean finished;
        try {
            finished = done.await(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            finished = false;
        }
        if (!finished) runner.cancel();

        String out;
        synchronized (buf) {
            out = buf.toString();
        }
        int exit = finished ? code[0] : -1;
        Mirror mm = mirror;
        if (mm != null) {
            mm.end(exit);
        } else if (!finished) {
            pending("[超时中断]");
        }

        StringBuilder res = new StringBuilder();
        if (!finished) {
            res.append("命令超时（").append(timeoutSec).append(" 秒），已中断。\n");
        } else if (err[0] != null) {
            res.append("启动失败：").append(err[0]).append('\n');
        } else {
            res.append("退出码 ").append(exit).append('\n');
        }
        if (out.trim().isEmpty()) {
            res.append("（无输出）");
        } else {
            if (out.length() > MAX_RESULT_CHARS) {
                int cut = out.length() - MAX_RESULT_CHARS;
                res.append("…（前面省略 ").append(cut).append(" 字符）\n");
                out = out.substring(cut);
            }
            res.append(out.trim());
        }

        // 产物落盘：把这次命令新生成的东西复制进「星云产物」。
        // 用户在文件管理器里点不到 /data 深处的文件，APK 也就装不了 —— 统一搬到产物目录就解决了。
        try {
            long since = startedAt - 1000L;
            List<String> synced = new ArrayList<String>();
            if ("alpine".equals(mode)) {
                synced.addAll(SandboxBridge.syncArtifacts(ctx,
                        LinuxEnv.get(ctx).exchangeDir(), since));
            }
            synced.addAll(SandboxBridge.syncArtifacts(ctx, home, since));
            if (!synced.isEmpty()) {
                res.append("\n\n📦 产物已同步到「星云产物」：");
                for (String x : synced) res.append("\n  · ").append(x);
                res.append("\n（APK 可在「APK 工具 → 安装产物」里直接安装）");
                Mirror m2 = mirror;
                if (m2 != null) {
                    m2.line("📦 产物已同步到「星云产物」：");
                    for (String x : synced) m2.line("  · " + x);
                }
            }
        } catch (Throwable t) {
            Log.w("ShellTool", "artifact sync: " + t);
        }
        return res.toString();
    }
}
