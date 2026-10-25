package com.nebula.aichat.shell;

import android.annotation.SuppressLint;
import com.nebula.aichat.linux.LinuxEnv;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 命令执行器：手机本地 shell（toybox）或远程 HTTP 执行服务 */
public class ShellRunner {

    public interface Callback {
        /** 一行输出（stdout/stderr 已合并） */
        void onOutput(String line);

        /** 执行结束，code 为退出码，-1 表示启动失败 */
        void onFinish(int code, String error);

        /** 是否已被取消 */
        boolean isCancelled();
    }

    private volatile Process proc;
    private volatile boolean cancelled = false;

    @SuppressLint("StaticFieldLeak")   // 存的是 application context，不是 Activity
    private static volatile android.content.Context nebulaCtx;

    /** 必须在应用启动时注入一次（Application/Activity 均可） */
    public static void init(android.content.Context ctx) {
        if (ctx != null) nebulaCtx = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return proc != null && proc.isAlive();
    }

    public void cancel() {
        cancelled = true;
        Process p = proc;
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    public static String shellPath() {
        for (String p : new String[]{"/system/bin/sh", "/system/xbin/sh", "/bin/sh"}) {
            if (new File(p).canExecute()) return p;
        }
        return "/system/bin/sh";
    }

    public static String defaultPath() {
        return "/system/bin:/system/xbin:/vendor/bin:/product/bin";
    }

    /** 内置 Alpine Linux 执行（proot）；回调发生在工作线程 */
    public void runAlpine(final String cmd, final Callback cb) {
        cancelled = false;
        LinuxEnv env = LinuxEnv.get(nebulaCtx);
        if (env == null || !env.isInstalled()) {
            cb.onFinish(-1, "内置 Alpine 环境尚未安装");
            return;
        }
        env.runAlpine(cmd, new LinuxEnv.Callback() {
            @Override
            public void onOutput(String line) {
                cb.onOutput(line);
            }

            @Override
            public void onFinish(int code, String error) {
                cb.onFinish(code, error);
            }

            @Override
            public boolean isCancelled() {
                return cb.isCancelled();
            }
        });
    }

    /** 本地执行；回调发生在工作线程 */
    public void runLocal(final String cmd, final String cwd, final String home, final Callback cb) {
        cancelled = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                String err = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder(shellPath(), "-c", cmd);
                    File dir = new File(cwd);
                    if (dir.isDirectory()) pb.directory(dir);
                    pb.redirectErrorStream(true);
                    pb.environment().put("HOME", home);
                    pb.environment().put("TMPDIR", home + "/tmp");
                    pb.environment().put("PATH", defaultPath());
                    pb.environment().put("LANG", "en_US.UTF-8");
                    pb.environment().put("PWD", dir.getAbsolutePath());
                    new File(home + "/tmp").mkdirs();

                    proc = pb.start();
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (cb.isCancelled()) break;
                        cb.onOutput(line);
                    }
                    code = proc.waitFor();
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                } catch (Throwable t) {
                    // Error（如 StackOverflow/OOM）也兜住：工作线程没有上层，
                    // 漏出去就是整个 App 闪退，比命令失败严重得多
                    err = t.getClass().getSimpleName();
                } finally {
                    proc = null;
                    if (cancelled) code = 130;
                    // 回调自身的异常必须兜住：这个线程没有上层捕获，
                    // 漏一次就整个 App 闪退（历史事故：err 数组越界就是这么炸的）
                    try {
                        cb.onFinish(code, err);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "shell-exec");
        t.start();
    }

    /**
     * 远程执行：POST {url}/exec  {"cmd":..,"cwd":..}
     * 期望响应 {"stdout":"..","stderr":"..","code":0}；纯文本响应也可接受。
     */
    public void runRemote(final String url, final String token, final String cmd,
                          final String cwd, final Callback cb) {
        cancelled = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                String err = null;
                HttpURLConnection conn = null;
                try {
                    String base = url.trim();
                    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    if (!base.endsWith("/exec")) base = base + "/exec";

                    JSONObject body = new JSONObject();
                    body.put("cmd", cmd);
                    body.put("cwd", cwd == null ? "" : cwd);

                    conn = (HttpURLConnection) new URL(base).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(180000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Accept", "application/json");
                    if (token != null && !token.trim().isEmpty()) {
                        conn.setRequestProperty("X-Auth-Token", token.trim());
                        conn.setRequestProperty("Authorization", "Bearer " + token.trim());
                    }

                    OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();

                    int http = conn.getResponseCode();
                    InputStream is = http >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String text = readAll(is);
                    if (cb.isCancelled()) {
                        cb.onFinish(130, null);
                        return;
                    }
                    String out = text;
                    if (text.trim().startsWith("{")) {
                        try {
                            JSONObject o = new JSONObject(text);
                            String so = o.optString("stdout", "");
                            String se = o.optString("stderr", "");
                            out = so;
                            if (se != null && !se.trim().isEmpty()) {
                                out = (so == null || so.isEmpty() ? "" : so + "\n") + se;
                            }
                            code = o.optInt("code", o.optInt("exitCode", 0));
                        } catch (Exception parse) {
                            code = http >= 400 ? http : 0;
                        }
                    } else {
                        code = http >= 400 ? http : 0;
                        if (http >= 400) {
                            // 可能是纯文本后端；标记为失败
                            out = text;
                        }
                    }
                    if (out != null && !out.isEmpty()) {
                        for (String line : out.split("\n", -1)) cb.onOutput(line);
                    }
                    if (http >= 400 && (out == null || out.isEmpty())) {
                        err = "HTTP " + http;
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                    cb.onFinish(cancelled ? 130 : code, err);
                }
            }
        }, "shell-remote");
        t.start();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
