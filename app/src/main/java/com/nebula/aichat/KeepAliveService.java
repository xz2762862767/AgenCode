package com.nebula.aichat;

import com.nebula.aichat.ui.Palette;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 保活服务（前台服务 + 常驻通知）。
 *
 * 两种用法：
 * 1. 空闲常驻（{@link #startIdle}）：设置里"后台常驻通知"开启时，进入应用就拉起，
 *    通知写"后台运行中"。系统看到前台服务会少回收进程，用户也能在状态栏确认它还活着。
 * 2. 长任务保活（{@link #startBusy}）：一轮回答/命令超过几秒时才拉，通知写"正在处理"，
 *    避免短问答闪一条通知。
 *
 * 通知权限被关掉时（Android 13+ 未授予 POST_NOTIFICATIONS）系统会静默丢掉这条通知，
 * 服务本身照跑 —— 那时状态栏看不到任何东西，属于权限问题，由 MainActivity 负责引导。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "nebula_alive";
    /** 旧渠道（IMPORTANCE_LOW）：国产 ROM 上低重要度通知常被折叠、不给状态栏图标，已弃用 */
    private static final String CHANNEL_OLD = "nebula_task";
    private static final int NOTIF_ID = 1001;

    private static final String EXTRA_TITLE = "notif_title";
    private static final String EXTRA_TEXT = "notif_text";

    private static final String TITLE_BUSY = "AgenCode 正在处理";
    private static final String TEXT_BUSY = "长任务进行中，切到后台也不会被打断";
    private static final String TITLE_IDLE = "AgenCode 后台运行中";
    private static final String TEXT_IDLE = "常驻通知可减少被系统回收，点按回到对话";

    /** 兼容旧调用点：等价于"任务进行中" */
    public static void start(Context ctx) {
        startBusy(ctx);
    }

    public static void startBusy(Context ctx) {
        start(ctx, TITLE_BUSY, TEXT_BUSY);
    }

    public static void startIdle(Context ctx) {
        start(ctx, TITLE_IDLE, TEXT_IDLE);
    }

    private static void start(Context ctx, String title, String text) {
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            // 服务已在跑时再发一次 start 会把文案刷成新的，达到"任务态/空闲态"切换效果
            i.putExtra(EXTRA_TITLE, title);
            i.putExtra(EXTRA_TEXT, text);
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
    public void onCreate() {
        super.onCreate();
        // 兜底：个别机型 startForegroundService 后若没及时 startForeground 会崩（超时 ANR）。
        // Service 没有 getIntent()，这里先用默认文案起来，紧接着 onStartCommand 会刷成真实状态。
        try {
            foreground(TITLE_BUSY, TEXT_BUSY);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            show(intent);
        } catch (Throwable ignored) {
        }
        return START_NOT_STICKY;
    }

    private void show(Intent intent) {
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        // intent 为空只可能是系统重建服务：这时没任务在跑，按空闲态显示更符合实际
        if (title == null) title = TITLE_IDLE;
        if (text == null) text = TEXT_IDLE;
        foreground(title, text);
    }

    private void foreground(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            // 用 DEFAULT 而不是 LOW：ColorOS/OriginOS 会把 LOW 通知折叠进"静默通知"，
            // 状态栏干脆不给图标 —— 用户看到的就是"后台在跑但状态栏啥也没有"。
            // 声音/振动都在渠道上关掉，所以不会打扰。
            NotificationChannel ch = new NotificationChannel(CHANNEL, "后台运行与任务",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("回答或执行命令期间保持运行，常驻通知可用于确认进程仍存活");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
            try {
                nm.deleteNotificationChannel(CHANNEL_OLD);
            } catch (Throwable ignored) {
            }
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        // 状态栏用自家六边形图标：系统会整体染成单色，所以图标只能是纯剪影
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_nebula)
                .setColor(Palette.c(0xFF5B93FF))
                .setContentIntent(contentIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        Notification n = b.build();

        if (Build.VERSION.SDK_INT >= 29) {
            // Android 14+ 必须与 manifest 里声明的 foregroundServiceType 一致
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /** 点通知直接回到正在处理的对话，而不是重新冷启动一次 */
    private PendingIntent contentIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 0, i, flag);
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
