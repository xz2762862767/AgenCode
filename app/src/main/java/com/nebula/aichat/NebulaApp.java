package com.nebula.aichat;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.nebula.aichat.skill.SkillStore;
import com.nebula.aichat.util.AnrWatchdog;
import com.nebula.aichat.util.CrashLog;
import com.nebula.aichat.util.MemTrim;
import com.nebula.aichat.util.RunGuard;

/**
 * 应用入口。
 *
 * 除了装崩溃日志，这里承担"现场取证"的职责：闪退如果发生在 Java 异常之外
 * （系统回收进程 / ANR 被终结 / native 崩溃），唯一能留下的痕迹就是生命周期、
 * 内存压力和主线程卡死记录。日志量很小，但出事时能省掉一遍瞎猜。
 */
public class NebulaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        RunGuard.attach(this);
        AnrWatchdog.start();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle savedInstanceState) {
                CrashLog.d("ui", name(a) + " onCreate");
            }

            @Override
            public void onActivityStarted(Activity a) {
            }

            @Override
            public void onActivityResumed(Activity a) {
                CrashLog.d("ui", name(a) + " onResume");
            }

            @Override
            public void onActivityPaused(Activity a) {
                CrashLog.d("ui", name(a) + " onPause");
            }

            @Override
            public void onActivityStopped(Activity a) {
                CrashLog.d("ui", name(a) + " onStop");
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
                // finishing=true 是用户/代码主动关；false 多为系统回收或配置重建
                boolean finishing = a.isFinishing();
                CrashLog.d("ui", name(a) + " onDestroy finishing=" + finishing
                        + (finishing ? "" : "（系统回收或重建，非用户主动退出）"));
            }
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 被系统回收前一定会先 trim，这是"后台被杀"最靠前的预警
        CrashLog.noteTrim(level);
        // 顺手写进任务现场：万一下一步就是 SIGKILL，残留下来的记录里
        // 会带着"被杀前系统已经在催内存"这个证据，归因才不会靠猜
        RunGuard.noteTrim(level);

        // 让各处持有者先把可再生的缓存丢掉（技能正文、离屏富文本视图等）
        MemTrim.dispatch(level);

        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try {
                SkillStore.clearCache();
            } catch (Throwable ignored) {
            }
        }
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            // 走到这里说明系统已经在挑人杀了。ART 平时容忍堆停在高位（largeHeap 更宽松），
            // 但 LMK 看的是 RSS，所以这时主动收一次，把水位压下去换活命机会。
            try {
                System.gc();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        CrashLog.d("mem", "onLowMemory");
        MemTrim.dispatch(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
        try {
            SkillStore.clearCache();
            System.gc();
        } catch (Throwable ignored) {
        }
    }

    private static String name(Activity a) {
        return a == null ? "?" : a.getClass().getSimpleName();
    }
}
