package com.nebula.aichat.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.nebula.aichat.R;

/**
 * 任务完成提醒。
 *
 * <p>AI 回复结束、任务出错时，如果用户已经把应用切走，就发一条通知把他叫回来；
 * 前台可见时由调用方直接跳过，不做打扰。</p>
 *
 * <p>「有没有声音 / 有没有振动」在 Android 8+ 是挂在<b>通知渠道</b>上的，
 * 渠道一旦建好就改不了声音和振动。为了让设置页那两个开关立刻生效，
 * 这里按「声音 × 振动」的四种组合建四条渠道，发送时挑一条用。</p>
 */
public final class DoneNotify {

    private static final String CH_SOUND_VIB = "nebula_done_sv";
    private static final String CH_SOUND = "nebula_done_s";
    private static final String CH_VIB = "nebula_done_v";
    private static final String CH_SILENT = "nebula_done_q";

    private static final int ID_DONE = 0x0D01;
    private static final int ID_FAIL = 0x0D02;
    /** 蓝底图标色，与前台常驻通知同一套观感 */
    private static final int ACCENT = 0xFF5B93FF;

    private DoneNotify() {
    }

    private static String channelFor(boolean sound, boolean vibrate) {
        if (sound && vibrate) return CH_SOUND_VIB;
        if (sound) return CH_SOUND;
        if (vibrate) return CH_VIB;
        return CH_SILENT;
    }

    /** 按渠道 id 反推它该不该响 / 该不该振 */
    private static void ensureChannel(Context ctx, String id) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(id) != null) return;
        boolean sound = !CH_SILENT.equals(id) && !CH_VIB.equals(id);
        boolean vibrate = !CH_SILENT.equals(id) && !CH_SOUND.equals(id);

        NotificationChannel ch = new NotificationChannel(id, "任务完成提醒",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("AI 回复完成、任务出错时提醒");
        ch.setShowBadge(true);
        if (!sound) ch.setSound(null, null);
        ch.enableVibration(vibrate);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ch);
    }

    /**
     * 发一条完成提醒。
     *
     * @param fail    true=出错，false=正常完成
     * @param text    回复正文或错误信息，取开头一小段做摘要
     * @param sound   是否播放提示音
     * @param vibrate 是否振动
     */
    public static void send(Context ctx, boolean fail, String text,
                            boolean sound, boolean vibrate) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            // 用户把 App 的通知整个关掉时，渠道建了也发不出来，直接返回
            if (nm == null || !nm.areNotificationsEnabled()) return;

            String chId = channelFor(sound, vibrate);
            ensureChannel(ctx, chId);

            Intent it = new Intent(ctx, com.nebula.aichat.MainActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, fail ? 2 : 1, it, piFlags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, chId)
                    : new Notification.Builder(ctx);
            b.setContentTitle(fail ? "任务出错" : "回复完成")
                    .setContentText(brief(text, fail ? "点开查看错误详情" : "点开查看结果"))
                    .setSmallIcon(R.drawable.ic_stat_nebula)
                    .setColor(ACCENT)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(fail ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
                if (sound && vibrate) b.setDefaults(Notification.DEFAULT_ALL);
                else if (sound) b.setDefaults(Notification.DEFAULT_SOUND);
                else if (vibrate) b.setDefaults(Notification.DEFAULT_VIBRATE);
            }
            nm.notify(fail ? ID_FAIL : ID_DONE, b.build());
        } catch (Throwable e) {
            // 通知是锦上添花，任何异常都不该影响主流程
            CrashLog.d("notify", "发送失败: " + e);
        }
    }

    /**
     * 把正文压成一行短摘要：Markdown 符号、多余空白、换行都抹掉，
     * 通知栏里只留"人话"。
     */
    private static String brief(String text, String fallback) {
        if (text == null) return fallback;
        String s = text.replaceAll("[#*`>\\-\\[\\]()]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.isEmpty()) return fallback;
        if (s.length() > 60) s = s.substring(0, 60) + "…";
        return s;
    }
}
