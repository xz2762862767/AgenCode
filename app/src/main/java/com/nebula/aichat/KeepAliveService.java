package com.nebula.aichat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 长任务保活。
 *
 * 解决的是"切到微信回一条消息，回来发现星云 AI 已经没了"这类"自动退出"：
 * 回答/跑命令期间进程一旦退到后台，没有前台服务的应用会被系统随时回收
 * （尤其 ColorOS 这类调度很激进的系统），而且不会有任何 Java 堆栈。
 *
 * 只在一轮任务超过几秒时才真正拉起通知，短问答不会闪一个常驻通知骚扰用户。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "nebula_task";
    private static final int NOTIF_ID = 1001;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 兜底：个别机型 startForegroundService 后若没及时 startForeground 会崩（超时 ANR）
        try {
            foreground();
        } catch (Throwable ignored) {
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            foreground();
        } catch (Throwable ignored) {
        }
    }

    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "任务进行中",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("回答或执行命令期间保持运行");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("星云 AI 正在处理")
                .setContentText("长任务进行中，切到后台也不会被打断")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true);
        Notification n = b.build();

        if (Build.VERSION.SDK_INT >= 29) {
            // Android 14+ 必须与 manifest 里声明的 foregroundServiceType 一致
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
