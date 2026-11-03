package com.nebula.aichat.util;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行中任务现场守护。
 *
 * 解决的问题：AI 任务执行到一半，整个进程被系统 LMK 直接 SIGKILL。
 * SIGKILL 无法捕获 —— finally 不执行、日志来不及写、用户只看到界面消失，
 * 重新打开 App 之后连"刚才跑到哪了"都无从得知。
 *
 * 做法：任务开始时把现场（任务摘要、开始时间、轮次、内存水位）原子写盘，
 * 正常结束就删掉。于是"文件还在"这件事本身就是证据：
 *   - 正常完成 / 用户取消 → 已删除，下次启动什么都没看到
 *   - 被系统杀掉 → 残留，下次启动就能明确告诉用户"上次任务没跑完、
 *     是在内存紧张的状态下没的"，而不是让它静默丢失
 *
 * 同时把被杀前最后的内存水位一起落盘，让归因有依据：
 * 残留记录里 avail 很低 / lastTrim 是 CRITICAL，基本就是内存回收。
 */
public final class RunGuard {

    private static final String FILE_NAME = "run_guard.json";
    private static final int MAX_TITLE = 80;
    /** beat 限流：任务轮次推进很频繁，别把磁盘写成瓶颈 */
    private static final long BEAT_MIN_GAP_MS = 3000L;

    /** 上次未正常结束的任务留下的现场 */
    public static final class Residue {
        public long startedAt;
        public long lastBeat;
        public int rounds;
        public int lastTrim;
        public long availMb;
        public long usedMb;
        public long maxMb;
        public String title = "";

        /** 归因文案：能说清就说清，说不清就只说事实 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("上次任务没有跑完：执行到第 ").append(Math.max(rounds, 1)).append(" 轮时")
                    .append("进程被系统直接终止（不是点停止，而是整个应用被回收）。\n");
            String t = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(startedAt));
            sb.append("任务：").append(title.isEmpty() ? "（无摘要）" : title).append('\n');
            sb.append("开始时间：").append(t);
            if (lastBeat > startedAt) {
                long mins = Math.max(1, (lastBeat - startedAt) / 60000L);
                sb.append("，坚持了约 ").append(mins).append(" 分钟");
            }
            sb.append('\n');
            if (availMb > 0) {
                sb.append("当时内存：应用 ").append(usedMb).append("MB / 上限 ").append(maxMb)
                        .append("MB，系统可用 ").append(availMb).append("MB\n");
            }
            sb.append("原因：").append(verdict());
            return sb.toString();
        }

        private String verdict() {
            // 被杀前系统已经反复要求回收内存 → 基本可以确定是内存不够被 LMK 挑中
            if (lastTrim == 15 || lastTrim == 80 || availMb > 0 && availMb < 300) {
                return "内存不足触发系统回收（LMK）。执行重活（构建、大文件处理）时最容易发生。";
            }
            if (lastTrim >= 10) {
                return "内存偏紧，进程被系统回收的概率很高；也可能是从最近任务里被手动清理。";
            }
            return "未能确定具体原因：可能是内存回收，也可能是从最近任务里手动清理、或系统省电策略终止。";
        }
    }

    private static Context app;
    private static volatile boolean active = false;
    private static volatile long startedAt = 0L;
    private static volatile long lastBeat = 0L;
    private static volatile long lastWrite = 0L;
    private static volatile int rounds = 0;
    private static volatile String title = "";
    /** 最近一次 onTrimMemory 的等级与当时的内存水位（由 NebulaApp 喂进来） */
    private static volatile int lastTrim = 0;
    private static volatile long availMb = 0L;
    private static volatile long usedMb = 0L;
    private static volatile long maxMb = 0L;

    private RunGuard() {
    }

    public static void attach(Context c) {
        if (c != null) app = c.getApplicationContext();
    }

    public static boolean isActive() {
        return active;
    }

    /** 任务开始：立刻落盘（顺序很重要：先有记录，才有"被杀"的证据） */
    public static synchronized void begin(String taskTitle) {
        active = true;
        startedAt = System.currentTimeMillis();
        lastBeat = startedAt;
        rounds = 0;
        title = clip(taskTitle);
        sampleMemory();
        write();
        CrashLog.d("run", "任务开始：" + title);
    }

    /** 轮次推进 / 有进展时刷新现场 */
    public static synchronized void beat(int round) {
        if (!active) return;
        rounds = Math.max(rounds, round);
        long now = System.currentTimeMillis();
        if (now - lastWrite < BEAT_MIN_GAP_MS) return;
        lastBeat = now;
        sampleMemory();
        write();
    }

    /** 任务正常结束（完成、失败、用户取消都算正常结束） */
    public static synchronized void end() {
        if (!active) return;
        active = false;
        long cost = System.currentTimeMillis() - startedAt;
        CrashLog.d("run", "任务结束：rounds=" + rounds + " cost=" + cost + "ms");
        File f = file();
        if (f != null) {
            try {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** onTrimMemory 时喂入：被杀瞬间没法写盘，只能靠最后一次 beat 带出去 */
    public static void noteTrim(int level) {
        lastTrim = level;
        sampleMemory();
        if (active) {
            // 内存告急是最高优先级信号，绕过限流立刻刷一次
            lastWrite = 0L;
            beat(rounds);
        }
    }

    /** 读取上次残留现场并清除；没有残留返回 null */
    public static synchronized Residue consume(Context c) {
        Context ctx = c != null ? c.getApplicationContext() : app;
        File f = ctx == null ? null : new File(ctx.getFilesDir(), FILE_NAME);
        if (f == null || !f.exists()) return null;
        String json = read(f);
        try {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
        }
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            Residue r = new Residue();
            r.startedAt = o.optLong("startedAt");
            r.lastBeat = o.optLong("lastBeat");
            r.rounds = o.optInt("rounds");
            r.lastTrim = o.optInt("lastTrim");
            r.availMb = o.optLong("availMb");
            r.usedMb = o.optLong("usedMb");
            r.maxMb = o.optLong("maxMb");
            r.title = o.optString("title", "");
            if (r.startedAt <= 0) return null;
            CrashLog.d("run", "发现上次任务残留现场：rounds=" + r.rounds
                    + " avail=" + r.availMb + "MB lastTrim=" + r.lastTrim);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sampleMemory() {
        try {
            usedMb = CrashLog.usedMemMb();
            maxMb = CrashLog.maxMemMb();
            availMb = CrashLog.availMemMb();
        } catch (Throwable ignored) {
        }
    }

    /** 原子写：先写 .tmp 再改名，避免 SIGKILL 恰好落在写一半的时刻把 JSON 写坏 */
    private static void write() {
        File f = file();
        if (f == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("startedAt", startedAt);
            o.put("lastBeat", lastBeat);
            o.put("rounds", rounds);
            o.put("lastTrim", lastTrim);
            o.put("availMb", availMb);
            o.put("usedMb", usedMb);
            o.put("maxMb", maxMb);
            o.put("title", title);
            o.put("sdk", Build.VERSION.SDK_INT);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp, false);
            try {
                fo.write(o.toString().getBytes("UTF-8"));
                fo.flush();
                fo.getFD().sync();
            } finally {
                try {
                    fo.close();
                } catch (Throwable ignored) {
                }
            }
            if (!tmp.renameTo(f)) {
                // 改名失败（极少见）时退回直接写，宁可非原子也别丢现场
                FileOutputStream fo2 = new FileOutputStream(f, false);
                try {
                    fo2.write(o.toString().getBytes("UTF-8"));
                } finally {
                    fo2.close();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            lastWrite = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    private static File file() {
        Context c = app;
        return c == null ? null : new File(c.getFilesDir(), FILE_NAME);
    }

    private static String read(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(512);
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0) {
                bo.write(b, 0, n);
                if (bo.size() > 8192) break;
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > MAX_TITLE ? s.substring(0, MAX_TITLE) + "…" : s;
    }
}
