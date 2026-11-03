package com.nebula.aichat.util;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存回收广播。
 *
 * 系统在回收进程之前一定会先发 onTrimMemory —— 这是唯一一次"提前打招呼"。
 * 收到之后如果不做任何事，RSS 就会一直挂在高位，下一次内存紧张时被挑中的概率更高。
 * 这里把信号广播给各处持有者，让它们主动把可再生的缓存丢掉。
 *
 * 约定：
 *   - 回调必须轻量、在主线程执行、允许被多次调用（幂等）
 *   - 只存弱引用：持有者通常是页面/Adapter，强引用会反向坑住 Activity 造成泄漏
 */
public final class MemTrim {

    public interface Listener {
        /**
         * @param level    原始 onTrimMemory 等级
         * @param critical 是否已经进入"随时可能被杀"的等级
         */
        void onTrim(int level, boolean critical);
    }

    private static final CopyOnWriteArrayList<WeakReference<Listener>> LISTENERS =
            new CopyOnWriteArrayList<>();

    private MemTrim() {
    }

    public static void register(Listener l) {
        if (l == null) return;
        for (WeakReference<Listener> ref : LISTENERS) {
            if (ref.get() == l) return;
        }
        LISTENERS.add(new WeakReference<>(l));
    }

    public static void unregister(Listener l) {
        if (l == null) return;
        for (WeakReference<Listener> ref : LISTENERS) {
            Listener x = ref.get();
            if (x == null || x == l) LISTENERS.remove(ref);
        }
    }

    /** 由 Application 的 onTrimMemory 统一转发 */
    public static void dispatch(int level) {
        boolean critical = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
        for (WeakReference<Listener> ref : LISTENERS) {
            Listener l = ref.get();
            if (l == null) {
                // 持有者已经被回收了，顺手清掉这条登记
                LISTENERS.remove(ref);
                continue;
            }
            try {
                l.onTrim(level, critical);
            } catch (Throwable ignored) {
                // 一个持有者清理失败不能影响其它清理
            }
        }
    }
}
