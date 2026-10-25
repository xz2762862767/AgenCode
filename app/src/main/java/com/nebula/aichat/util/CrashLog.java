package com.nebula.aichat.util;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 崩溃 + 过程诊断落盘。
 *
 * 目的：闪退后不用连电脑、不用 root 也能拿到现场。
 * 日志直接写到「下载/NebulaAI/」：
 *   diag.txt          每次对话关键节点追加一行（进程被系统杀掉也能看到最后一步）
 *   crash-时间.txt     未捕获异常的完整堆栈（Java 异常）
 *   anr-时间.txt       主线程卡死 / 疑似被杀前的现场（无 Java 异常时唯一线索）
 *
 * 三条设计约束（都是历史事故换来的）：
 *  1. 写盘全部在后台线程完成，主线程只进队列 —— 诊断代码本身绝不能拖慢主线程，
 *     否则从"帮你定位闪退"变成"制造卡顿/ANR"。
 *  2. 崩溃堆栈必须同步写：进程下一秒就没了，排队等于丢日志。
 *  3. 心跳每 20s 落一次盘。下次启动时若发现"上次心跳还很新却没有正常收尾"，
 *     就说明进程是被系统杀掉的（无 Java 堆栈的闪退），这是唯一能留下的痕迹。
 */
public final class CrashLog {

    private static final String DIR = "NebulaAI";
    private static final String DIAG_NAME = "diag.txt";
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final String SP = "crashlog";
    private static final String K_URI = "diag_uri";
    private static final String K_BEAT = "last_beat";
    private static final String K_ACTION = "last_action";
    private static final String K_START = "last_start";

    /** 心跳周期 */
    private static final long BEAT_MS = 20_000L;
    /**
     * 上次心跳到本次启动的间隔小于这个值 => 上次是"活跃期内突然消失"，
     * 典型原因：系统回收进程（LMK）、ANR 被终结、native 崩溃、被用户强杀。
     */
    private static final long ALIVE_GAP_MS = 180_000L;

    @SuppressLint("StaticFieldLeak")   // 存的是 application context，不是 Activity
    private static Context app;
    private static Uri diagUri;

    private static volatile String lastAction = "";
    private static volatile boolean installed;

    /** 日志写盘队列：主线程只 add，绝不碰磁盘 */
    private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<String>(4000);
    private static ExecutorService writer;

    private CrashLog() {
    }

    /** 在 Application.onCreate 里调用一次 */
    public static void install(Context ctx) {
        if (installed) return;
        installed = true;
        app = ctx.getApplicationContext();

        startWriter();
        // 先判定上一轮进程是怎么没的，再记本次启动，顺序不能反
        checkLastRun();
        d("app", "启动 pid=" + android.os.Process.myPid()
                + " sdk=" + Build.VERSION.SDK_INT
                + " 机型=" + Build.MANUFACTURER + " " + Build.MODEL
                + " 内存=" + usedMemMb() + "/" + maxMemMb() + "MB"
                + " oom_adj=" + oomAdj());
        startBeat();
        installHandler();
    }

    // ------------------------------------------------------------ 生命周期痕迹

    private static void installHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    // 崩溃现场必须同步写，不能排队
                    writeCrash(t, e);
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** 上次进程的结局：正常结束，还是活跃期内突然消失 */
    private static void checkLastRun() {
        try {
            SharedPreferences sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE);
            long last = sp.getLong(K_BEAT, 0L);
            if (last <= 0L) return;
            long gap = System.currentTimeMillis() - last;
            String act = sp.getString(K_ACTION, "");
            if (gap >= 0 && gap <= ALIVE_GAP_MS) {
                d("prev", "上次进程在活跃期消失：距今 " + (gap / 1000) + "s"
                        + "，最后动作=" + act
                        + "。若下载目录没有对应的 crash-*.txt，说明不是 Java 异常，"
                        + "而是系统回收进程 / ANR 被终结 / native(proot) 崩溃；"
                        + "请对照同批次的 anr-*.txt 与本次启动前的操作步骤。");
                d("prev", "机器内存：avail=" + availMemMb() + "MB total=" + totalMemMb() + "MB");
            } else {
                d("prev", "上次正常结束（距今 " + (gap / 1000) + "s）最后动作=" + act);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void startBeat() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        beat();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(BEAT_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "crash-beat");
        t.setDaemon(true);
        t.start();
    }

    private static void beat() {
        Context c = app;
        if (c == null) return;
        String snap = lastAction + " | mem=" + usedMemMb() + "/" + maxMemMb()
                + "MB oom_adj=" + oomAdj();
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putLong(K_BEAT, System.currentTimeMillis())
                .putLong(K_START, startedAt)
                .putString(K_ACTION, snap)
                .apply();
    }

    private static final long startedAt = System.currentTimeMillis();

    // ------------------------------------------------------------ 对外记录接口

    /** 关键节点记录一行（异步落盘，主线程安全） */
    public static void d(String tag, String msg) {
        String line = TS.format(new Date()) + " [" + tag + "] " + msg;
        Log.d("NebulaAI", line);
        lastAction = "[" + tag + "] " + msg;
        try {
            // 队列满就丢最旧的，宁可少几行日志也不能卡住调用方
            if (!QUEUE.offer(line)) {
                QUEUE.poll();
                QUEUE.offer(line);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 内存压力：系统回收前一定会先 trim，这是"被杀"的预警信号 */
    public static void noteTrim(int level) {
        d("mem", "onTrimMemory level=" + level
                + " used=" + usedMemMb() + "/" + maxMemMb() + "MB"
                + " avail=" + availMemMb() + "MB");
    }

    /**
     * 主线程卡死现场（watchdog 调用）。
     * 这是"没有 Java 堆栈却自动退出"最关键的证据：ANR 被终结前主线程卡在哪。
     */
    public static void anr(long stuckMs, String stack) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 星云 AI 主线程卡死现场 =====\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("卡住时长: ").append(stuckMs).append(" ms\n");
        sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("内存: 已用 ").append(usedMemMb()).append(" MB / 上限 ").append(maxMemMb()).append(" MB")
                .append(" / 可用 ").append(availMemMb()).append(" MB\n");
        sb.append("oom_adj: ").append(oomAdj()).append('\n');
        sb.append("最后动作: ").append(lastAction).append('\n');
        sb.append("\n----- 主线程堆栈 -----\n").append(stack == null ? "(无)" : stack).append('\n');
        String name = "anr-" + new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            create(app, name, sb.toString());
        } catch (Throwable ignored) {
            try {
                File dir = app.getExternalFilesDir("diag");
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    FileOutputStream fo = new FileOutputStream(new File(dir, name));
                    fo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    fo.close();
                }
            } catch (Throwable ignored2) {
            }
        }
        Log.e("NebulaAI", "主线程卡死 " + stuckMs + "ms");
    }

    // ------------------------------------------------------------ 崩溃堆栈

    private static void writeCrash(Thread t, Throwable e) {
        Context c = app;
        if (c == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("===== 星云 AI 崩溃报告 =====\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("线程: ").append(t == null ? "?" : t.getName()).append('\n');
        sb.append("内存: 已用 ").append(usedMemMb()).append(" MB / 上限 ")
                .append(maxMemMb()).append(" MB / 可用 ").append(availMemMb()).append(" MB\n");
        sb.append("最后动作: ").append(lastAction).append('\n');
        sb.append("存活: ").append((System.currentTimeMillis() - startedAt) / 1000).append(" s\n\n");
        sb.append("----- 堆栈 -----\n");
        sb.append(Log.getStackTraceString(e)).append('\n');

        Throwable cause = e == null ? null : e.getCause();
        if (cause != null) {
            sb.append("\n----- cause -----\n").append(Log.getStackTraceString(cause)).append('\n');
        }

        String name = "crash-" + new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            create(c, name, sb.toString());
        } catch (Throwable ignored) {
            try {
                File dir = c.getExternalFilesDir("diag");
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    FileOutputStream fo = new FileOutputStream(new File(dir, name));
                    fo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    fo.close();
                }
            } catch (Throwable ignored2) {
            }
        }
    }

    // ------------------------------------------------------------ 写盘线程

    private static void startWriter() {
        if (writer != null) return;
        writer = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "crash-log-writer");
                t.setDaemon(true);
                return t;
            }
        });
        writer.execute(new Runnable() {
            @Override
            public void run() {
                List<String> batch = new ArrayList<String>(64);
                while (true) {
                    try {
                        String first = QUEUE.poll(1, TimeUnit.SECONDS);
                        if (first == null) continue;
                        batch.clear();
                        batch.add(first);
                        QUEUE.drainTo(batch, 63);
                        StringBuilder sb = new StringBuilder(2048);
                        for (int i = 0; i < batch.size(); i++) sb.append(batch.get(i)).append('\n');
                        Context c = app;
                        if (c == null) continue;
                        try {
                            append(c, DIAG_NAME, sb.toString());
                        } catch (Throwable ignored) {
                        }
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------ 内存信息

    private static long usedMemMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / 1024 / 1024;
    }

    private static long maxMemMb() {
        return Runtime.getRuntime().maxMemory() / 1024 / 1024;
    }

    /** 系统剩余内存：判断是否被 LMK 盯上的关键 */
    private static long availMemMb() {
        try {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            android.app.ActivityManager am = (android.app.ActivityManager)
                    app.getSystemService(Context.ACTIVITY_SERVICE);
            am.getMemoryInfo(mi);
            return mi.availMem / 1024 / 1024;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static long totalMemMb() {
        try {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            android.app.ActivityManager am = (android.app.ActivityManager)
                    app.getSystemService(Context.ACTIVITY_SERVICE);
            am.getMemoryInfo(mi);
            return mi.totalMem / 1024 / 1024;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 进程优先级：越小越先被杀（负数是前台） */
    private static int oomAdj() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/oom_score_adj"));
            String s = r.readLine();
            r.close();
            return s == null ? -999 : Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return -999;
        }
    }

    // ------------------------------------------------------------ 写文件

    private static void create(Context c, String name, String text) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR);
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                OutputStream os = c.getContentResolver().openOutputStream(uri);
                if (os != null) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    return;
                }
            }
        }
        throw new Exception("MediaStore 不可用");
    }

    private static void append(Context c, String name, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                // MediaStore 遇到同名文件会自动改名成 diag (1).txt，
                // 于是按名字精确查找永远找不到 -> 每次启动都新建一个日志文件。
                // 这里把拿到的 uri 记下来复用，保证同一个安装内一直追加同一个文件。
                Uri uri = diagUri;
                if (uri == null) uri = savedUri(c);
                if (uri == null) uri = find(c, name);
                if (uri == null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR);
                    uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                }
                if (uri == null) return;
                diagUri = uri;
                saveUri(c, uri);
                OutputStream os = c.getContentResolver().openOutputStream(uri, "wa");
                if (os != null) {
                    os.write(bytes);
                    os.flush();
                    os.close();
                }
                return;
            } catch (Throwable ignored) {
                // 追加失败（文件被用户删了/uri 失效）：丢掉旧 uri，下次重建
                diagUri = null;
                clearSavedUri(c);
                try {
                    fallbackAppend(c, name, bytes);
                } catch (Throwable ignored2) {
                }
            }
        }
        try {
            File dir = c.getExternalFilesDir("diag");
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                FileOutputStream fo = new FileOutputStream(new File(dir, name), true);
                fo.write(bytes);
                fo.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static Uri savedUri(Context c) {
        try {
            String v = c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_URI, null);
            if (v == null || v.isEmpty()) return null;
            return Uri.parse(v);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void saveUri(Context c, Uri uri) {
        try {
            c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                    .putString(K_URI, uri.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void clearSavedUri(Context c) {
        try {
            c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().remove(K_URI).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void fallbackAppend(Context c, String name, byte[] bytes) throws Exception {
        File dir = c.getExternalFilesDir("diag");
        if (dir == null) return;
        if (!dir.exists()) dir.mkdirs();
        FileOutputStream fo = new FileOutputStream(new File(dir, name), true);
        fo.write(bytes);
        fo.close();
    }

    @SuppressLint("NewApi")   // 只在 SDK_INT >= 29 的写入路径里用到
    private static Uri find(Context c, String name) {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] proj = {MediaStore.MediaColumns._ID};
        String sel = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String base = name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
        String[] args = {base + "%", Environment.DIRECTORY_DOWNLOADS + "/" + DIR + "/"};
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(collection, proj, sel, args, null);
            if (cur != null && cur.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cur.getLong(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) try {
                cur.close();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
