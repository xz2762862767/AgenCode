package com.nebula.aichat.tool;

import android.content.Context;

import android.util.Log;

import com.nebula.aichat.apk.SandboxBridge;
import com.nebula.aichat.data.Settings;
import com.nebula.aichat.linux.LinuxEnv;
import com.nebula.aichat.shell.ShellRunner;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 让模型能真的在终端里跑命令：AI 边回答边执行，输出同步回显到终端页。
 * 执行通道与终端页一致（Linux / 本地 toybox / 远程执行服务）。
 */
public class ShellToolProvider {

    /** 单次命令回传给模型的最大字符数，超长只保留尾部 */
    // 单条命令进上下文的上限。原来 12000 字符，但超长输出的尾部往往才是关键
    // （报错一般在最后），截到 6000 后仍保留头尾两端，省下来的都是每轮重发的钱。
    private static final int MAX_RESULT_CHARS = 6000;
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
        reg.add(new ToolDef("bash_exec",
                "在手机内置终端里真实执行一条 shell 命令并拿到输出。"
                        + "适合：跑用户要求的命令、用脚本验证结果、查看/改动文件、装依赖、编译运行代码。"
                        + "命令默认在沙盒工作目录执行，可用 && 串多步。"
                        + "不要执行破坏性命令（rm -rf /、格式化、清空用户数据等）。"
                        + "内置可用命令：jadx、apktool、baksmali、smali、apkinfo、unzip。"
                        + "注意：签名和安装 APK 没有 shell 命令（手机里没有 apksigner/adb），"
                        + "要用 apk_sign、apk_install 这两个内置工具。",
                ToolDef.schema(ToolDef.props(
                                "command", ToolDef.prop("string", "要执行的完整命令，如 python3 hello.py"),
                                "timeout", ToolDef.prop("integer", "超时秒数，默认 120，最大 600"),
                                "cwd", ToolDef.prop("string", "可选：在哪个目录执行。不填就接着上一条命令结束时的目录")),
                        "command"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        String cmd = a.optString("command", "").trim();
                        if (cmd.isEmpty()) return "错误：command 不能为空。";
                        int timeout = a.optInt("timeout", DEFAULT_TIMEOUT);
                        if (timeout <= 0) timeout = DEFAULT_TIMEOUT;
                        if (timeout > MAX_TIMEOUT) timeout = MAX_TIMEOUT;
                        return exec(ctx, cmd, timeout, a.optString("cwd", null), true);
                    }
                }));
        reg.add(bgTool(ctx));
    }

    /** shell 单引号转义：命令里带 ' 也不能把整条命令拼坏 */
    private static String shq(String x) {
        if (x == null) return "''";
        return "'" + x.replace("'", "'\\''") + "'";
    }

    /** 上一条命令结束时所处的目录；目录被删了/不可用就退回默认目录 */
    private static String readCwd(File state, String fallback, boolean alpine) {
        try {
            if (!state.isFile()) return fallback;
            String d = readText(state).trim();
            if (d.isEmpty()) return fallback;
            // alpine 里的路径在手机侧看不到，没法校验，直接交给 shell 自己 cd
            if (alpine) return d;
            return new File(d).isDirectory() ? d : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 把命令包成「先 cd 到上次的目录，跑完把最后所在的目录记下来」。
     *
     * <p>不包这一层，cd 只影响它自己那一条命令，AI 每次都得重新写绝对路径；
     * 长路径在上下文里反复出现，既费钱又容易抄错。
     */
    private static String wrapCmd(String cmd, String startDir, String fallback, String statePath) {
        return "cd " + shq(startDir) + " 2>/dev/null || cd " + shq(fallback) + "\n"
                + cmd + "\n"
                + "__nebula_rc=$?\n"
                + "pwd > " + shq(statePath) + " 2>/dev/null\n"
                + "exit $__nebula_rc\n";
    }

    private static String readText(File f) {
        try {
            if (!f.isFile() || f.length() > 64 * 1024) return "";
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r <= 0) break;
                    n += r;
                }
            } finally {
                in.close();
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String t) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try {
                out.write(t.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String shorten(String t, int max) {
        if (t == null) return "";
        String x = t.replace('\n', ' ').trim();
        return x.length() <= max ? x : x.substring(0, max) + "…";
    }

    private static String exec(Context ctx, final String cmd, int timeoutSec,
                               String cwdArg, boolean mirrorUi) {
        Settings s = Settings.load(ctx);
        File home = new File(ctx.getFilesDir(), "sandbox");
        //noinspection ResultOfMethodCallIgnored
        if (!home.exists()) home.mkdirs();

        String mode0 = s.execMode == null ? "local" : s.execMode;
        boolean inAlpine = "alpine".equals(mode0);
        boolean inRemote = "remote".equals(mode0) && !s.remoteExecUrl.trim().isEmpty();
        // cd 之后下一条命令还留在那个目录：把结束时的 pwd 记下来，下次从这儿开始。
        // 放在 exchange 目录里是为了 alpine 模式也能读写同一份状态。
        File stateFile = inAlpine
                ? new File(LinuxEnv.get(ctx).exchangeDir(), ".nebula_cwd")
                : new File(home, ".nebula_cwd");
        String statePath = inAlpine ? "/exchange/.nebula_cwd" : stateFile.getAbsolutePath();
        String fallback = inAlpine ? "/root" : home.getAbsolutePath();
        String startDir = (cwdArg != null && !cwdArg.trim().isEmpty())
                ? cwdArg.trim() : readCwd(stateFile, fallback, inAlpine);
        // 远程执行服务看不到手机上的路径，往它那儿包 cd 只会报一句错，所以原样发过去
        final String runCmd = inRemote ? cmd : wrapCmd(cmd, startDir, fallback, statePath);

        final StringBuilder buf = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        final int[] code = new int[]{-1};
        final String[] err = new String[] { null };
        final long startedAt = System.currentTimeMillis();
        final ShellRunner runner = new ShellRunner();

        final Mirror m = mirrorUi ? mirror : null;
        if (m != null) m.begin(cmd);
        else if (mirrorUi) pending("⌘ " + cmd);

        ShellRunner.Callback cb = new ShellRunner.Callback() {
            @Override
            public void onOutput(String line) {
                synchronized (buf) {
                    if (buf.length() < MAX_RESULT_CHARS * 2) buf.append(line).append('\n');
                }
                Mirror mm = mirrorUi ? mirror : null;
                if (mm != null) mm.line(line);
                else if (mirrorUi) pending(line);
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

        if (inAlpine) {
            runner.runAlpine(runCmd, cb);
        } else if (inRemote) {
            runner.runRemote(s.remoteExecUrl, s.remoteExecToken, runCmd, home.getAbsolutePath(), cb);
        } else {
            runner.runLocal(runCmd, home.getAbsolutePath(), home.getAbsolutePath(), cb);
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
        } else if (!finished && mirrorUi) {
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

        // 产物落盘：把这次命令新生成的东西复制进「AgenCode」。
        // 用户在文件管理器里点不到 /data 深处的文件，APK 也就装不了 —— 统一搬到产物目录就解决了。
        try {
            long since = startedAt - 1000L;
            List<String> synced = new ArrayList<String>();
            if (inAlpine) {
                synced.addAll(SandboxBridge.syncArtifacts(ctx,
                        LinuxEnv.get(ctx).exchangeDir(), since));
            }
            synced.addAll(SandboxBridge.syncArtifacts(ctx, home, since));
            if (!synced.isEmpty()) {
                res.append("\n\n📦 产物已同步到「AgenCode」：");
                for (String x : synced) res.append("\n  · ").append(x);
                res.append("\n（APK 可在「APK 工具 → 安装产物」里直接安装）");
                Mirror m2 = mirror;
                if (m2 != null) {
                    m2.line("📦 产物已同步到「AgenCode」：");
                    for (String x : synced) m2.line("  · " + x);
                }
            }
        } catch (Throwable t) {
            Log.w("ShellTool", "artifact sync: " + t);
        }
        return res.toString();
    }

    // —————————————————————— 后台任务 ——————————————————————

    /**
     * 后台执行：起一条命令就立刻返回，不等它跑完。
     *
     * <p>为什么非要有这个：起个本地服务、跑个长时间压测、开个 tcpdump，
     * 用 bash_exec 会一直占着那一次调用直到超时，超时还会把进程杀掉 ——
     * 模型只能干等着，什么都做不了。后台起、日志落盘，才能边跑边看边干别的。
     */
    private static ToolDef bgTool(final Context ctx) {
        return new ToolDef("bash_bg",
                "在后台起一条命令，立刻返回、不等它结束。适合：本地服务、长时间任务、抓日志、压测。\n"
                        + "action=start 启动（要给 command）；action=log 看输出（给 id）；"
                        + "action=list 列出全部任务；action=stop 停掉（给 id）。\n"
                        + "启动后会返回 id、进程号和日志路径，日志也能用 bash_exec 的 tail/cat 直接看。\n"
                        + "注意：App 被系统回收时后台任务可能一起没掉，重要产物要落到文件里。",
                ToolDef.schema(ToolDef.props(
                                "action", ToolDef.prop("string", "start / log / list / stop，默认 start"),
                                "command", ToolDef.prop("string", "要后台执行的命令，action=start 时必填"),
                                "id", ToolDef.prop("string", "任务 id，action=log/stop 时给"),
                                "lines", ToolDef.prop("integer", "action=log 时看尾部多少行，默认 80，最大 500"),
                                "cwd", ToolDef.prop("string", "可选：在哪个目录启动")),
                        "action"),
                new ToolDef.Executor() {
                    @Override
                    public String run(JSONObject a) throws Exception {
                        String act = a.optString("action", "start").trim().toLowerCase(Locale.ROOT);
                        if (act.isEmpty()) act = "start";
                        if ("list".equals(act)) return bgList(ctx);
                        if ("log".equals(act)) {
                            return bgLog(ctx, a.optString("id", "").trim(), a.optInt("lines", 80));
                        }
                        if ("stop".equals(act)) return bgStop(ctx, a.optString("id", "").trim());
                        return bgStart(ctx, a.optString("command", "").trim(),
                                a.optString("cwd", null));
                    }
                });
    }

    private static File bgDir(Context ctx) {
        File d = new File(new File(ctx.getFilesDir(), "sandbox"), "bg");
        //noinspection ResultOfMethodCallIgnored
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String bgStart(Context ctx, String cmd, String cwd) {
        if (cmd.isEmpty()) return "错误：action=start 需要 command。";
        File dir = bgDir(ctx);
        String id = "bg" + System.currentTimeMillis();
        File log = new File(dir, id + ".log");
        // nohup + 全量重定向 + 关掉 stdin：父 shell 退出之后它照样接着跑
        String launch = "nohup sh -c " + shq(cmd) + " > " + shq(log.getAbsolutePath())
                + " 2>&1 < /dev/null &\necho $!";
        String r = exec(ctx, launch, 20, cwd, false);
        String pid = lastNumLine(r);
        if (pid == null) return "启动失败：\n" + r;
        writeText(new File(dir, id + ".pid"), pid);
        writeText(new File(dir, id + ".cmd"), cmd);
        return "已在后台启动\n  id: " + id + "\n  pid: " + pid
                + "\n  日志: " + log.getAbsolutePath()
                + "\n\n看输出：bash_bg(action=log, id=" + id + ")，停止：action=stop。";
    }

    private static String bgList(Context ctx) {
        File dir = bgDir(ctx);
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) return "当前没有后台任务。";
        StringBuilder sb = new StringBuilder("后台任务：\n");
        int n = 0;
        for (File f : fs) {
            String name = f.getName();
            if (!name.endsWith(".pid")) continue;
            String id = name.substring(0, name.length() - 4);
            String pid = readText(f).trim();
            String cmd = readText(new File(dir, id + ".cmd"));
            long size = new File(dir, id + ".log").length();
            sb.append("\n· ").append(id)
                    .append("   pid ").append(pid.isEmpty() ? "?" : pid)
                    .append(isAlive(pid) ? "   运行中" : "   已结束")
                    .append("\n    ").append(shorten(cmd, 100))
                    .append("\n    日志 ").append(size).append(" 字节")
                    .append(isAlive(pid) ? "" : "（已结束的任务可删 id.log 清磁盘）").append("\n");
            n++;
            if (n >= 30) {
                sb.append("\n（还有更多，只列前 30 个）");
                break;
            }
        }
        return n == 0 ? "当前没有后台任务。" : sb.toString();
    }

    private static String bgLog(Context ctx, String id, int lines) {
        if (id.isEmpty()) return "错误：action=log 需要 id。";
        File log = new File(bgDir(ctx), id + ".log");
        if (!log.isFile()) return "找不到任务 " + id + " 的日志。";
        int want = lines <= 0 ? 80 : Math.min(lines, 500);
        List<String> ls = tailLines(log, want);
        String pid = readText(new File(bgDir(ctx), id + ".pid")).trim();
        StringBuilder sb = new StringBuilder();
        sb.append("任务 ").append(id).append(isAlive(pid) ? "（运行中）" : "（已结束）")
                .append("\n日志 ").append(log.length()).append(" 字节，尾部 ")
                .append(ls.size()).append(" 行：\n\n");
        for (String x : ls) sb.append(x).append('\n');
        return sb.toString().trim();
    }

    private static String bgStop(Context ctx, String id) {
        if (id.isEmpty()) return "错误：action=stop 需要 id。";
        String pid = readText(new File(bgDir(ctx), id + ".pid")).trim();
        if (pid.isEmpty()) return "找不到任务 " + id + "。";
        if (!isAlive(pid)) return "任务 " + id + "（pid " + pid + "）已经结束了。";
        // 先收子进程再收自己：sh -c 被杀时它的孩子会变成孤儿继续跑
        exec(ctx, "pkill -9 -P " + pid + " 2>/dev/null; kill -TERM " + pid
                        + " 2>/dev/null; sleep 0.3; kill -9 " + pid + " 2>/dev/null; echo done",
                20, null, false);
        return isAlive(pid)
                ? "没能停掉 pid " + pid + "（可能权限受限），可以用 bash_exec 手动 kill -9 " + pid + "。"
                : "已停止任务 " + id + "（pid " + pid + "）。";
    }

    /** 探活直接看 /proc，比每次起个 shell 跑 kill -0 便宜得多 */
    private static boolean isAlive(String pid) {
        if (pid == null || pid.trim().isEmpty()) return false;
        try {
            File st = new File("/proc/" + pid.trim() + "/stat");
            if (!st.isFile()) return false;
            // 第三段是状态：Z 表示僵尸（已退出只是还没被回收），不算活着
            String t = readText(st);
            int rp = t.lastIndexOf(')');
            if (rp > 0 && rp + 2 < t.length()) {
                char state = t.charAt(rp + 2);
                return state != 'Z';
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从执行结果里挑出最后一行纯数字（就是 echo $! 打出来的 pid） */
    private static String lastNumLine(String r) {
        if (r == null) return null;
        String[] ls = r.split("\n");
        for (int i = ls.length - 1; i >= 0; i--) {
            String x = ls[i].trim();
            if (x.isEmpty()) continue;
            boolean num = x.length() <= 9;
            for (int k = 0; num && k < x.length(); k++) num = Character.isDigit(x.charAt(k));
            if (num) return x;
        }
        return null;
    }

    /** 只读文件尾部，避免几 MB 的日志整个读进内存 */
    private static List<String> tailLines(File f, int maxLines) {
        List<String> out = new ArrayList<String>();
        RandomAccessFile raf = null;
        try {
            long len = f.length();
            int window = (int) Math.min(len, 128 * 1024L);
            raf = new RandomAccessFile(f, "r");
            raf.seek(len - window);
            byte[] b = new byte[window];
            raf.readFully(b);
            String t = new String(b, StandardCharsets.UTF_8);
            // 从中间切开时首行多半是残的，丢掉
            if (window < len) {
                int nl = t.indexOf('\n');
                if (nl >= 0) t = t.substring(nl + 1);
            }
            String[] ls = t.split("\n");
            int start = Math.max(0, ls.length - maxLines);
            for (int i = start; i < ls.length; i++) out.add(ls[i]);
            // 行数不够可能是最后一行没换行，已包含
        } catch (Throwable t) {
            out.add("（读取日志失败：" + t.getClass().getSimpleName() + "）");
        } finally {
            if (raf != null) try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
