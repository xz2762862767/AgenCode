package com.nebula.aichat.util;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Map;

/**
 * 主线程卡死看门狗。
 *
 * 为什么需要它：Java 未捕获异常有堆栈可写，但"主线程卡住 5 秒以上被系统判 ANR 直接终结"
 * 不会走 UncaughtExceptionHandler —— 用户看到的就是"莫名其妙自动退出"，日志里什么都没有。
 * 这里用最土但最有效的办法：主线程每 250ms 给计数 +1，后台线程发现计数不动就说明卡住了，
 * 立刻把主线程堆栈落盘（anr-*.txt），于是下次闪退就有据可查。
 */
public final class AnrWatchdog {

    private static final long TICK_MS = 250L;
    /** 超过这个时长没动 -> 认为是卡死（系统 ANR 阈值 5s，这里同档抓现场） */
    private static final long STUCK_MS = 5_000L;
    /** 两次上报之间的最小间隔，避免卡死期间刷出一堆文件 */
    private static final long REPORT_GAP_MS = 30_000L;

    private static volatile long tick;
    private static volatile boolean started;
    private static volatile long lastReport;

    private AnrWatchdog() {
    }

    public static void start() {
        if (started) return;
        started = true;
        try {
            // 主线程心跳
            final Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                @Override
                public void run() {
                    tick++;
                    h.postDelayed(this, TICK_MS);
                }
            });

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    long seen = tick;
                    while (true) {
                        try {
                            Thread.sleep(TICK_MS);
                        } catch (InterruptedException e) {
                            return;
                        } catch (Throwable e) {
                            return;
                        }
                        long now = tick;
                        if (now != seen) {
                            seen = now;
                            continue;
                        }
                        // 计数没动：开始计时，确认是真的卡住而不是恰好错开一个 tick
                        long begin = SystemClock.elapsedRealtime();
                        try {
                            Thread.sleep(STUCK_MS);
                        } catch (InterruptedException e) {
                            return;
                        } catch (Throwable e) {
                            return;
                        }
                        if (tick != seen) {
                            // 只是短暂停顿，恢复后刷新基线
                            seen = tick;
                            continue;
                        }
                        long stuck = SystemClock.elapsedRealtime() - begin + TICK_MS;
                        long t = System.currentTimeMillis();
                        if (t - lastReport >= REPORT_GAP_MS) {
                            lastReport = t;
                            try {
                                CrashLog.anr(stuck, mainStack());
                            } catch (Throwable ignored) {
                            }
                        }
                        // 卡死仍未恢复时不要原地空转刷屏，等它恢复
                        long waitStart = SystemClock.elapsedRealtime();
                        while (tick == seen && SystemClock.elapsedRealtime() - waitStart < 60_000L) {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                return;
                            } catch (Throwable e) {
                                return;
                            }
                        }
                        seen = tick;
                    }
                }
            }, "anr-watchdog");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /** 取主线程当前堆栈：ANR 现场的核心信息 */
    private static String mainStack() {
        try {
            Thread main = Looper.getMainLooper().getThread();
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            StackTraceElement[] st = all.get(main);
            if (st == null || st.length == 0) return "(拿不到主线程堆栈)";
            StringBuilder sb = new StringBuilder();
            sb.append(main.getName()).append(" tid=").append(main.getId()).append('\n');
            for (StackTraceElement e : st) {
                sb.append("\tat ").append(e.toString()).append('\n');
            }
            return sb.toString();
        } catch (Throwable e) {
            return "(取堆栈失败: " + e + ")";
        }
    }
}
