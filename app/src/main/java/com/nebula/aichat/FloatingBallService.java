package com.nebula.aichat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 悬浮球：退出应用后屏幕上留一个圆形小入口，点一下回到对话。
 *
 * 为什么用前台服务：悬浮球必须在进程活着时才存在，而后台进程随时会被系统回收。
 * 前台服务带一条常驻通知是代价，但这是 Android 保证 overlay 不被杀的官方路径。
 *
 * 位置记忆存在 nebula_settings 里，和其余设置共用一份 SharedPreferences。
 * 纵向位置完全自由，横向松手后吸附到左/右边缘——手指不挡内容，也不会被拖出屏幕。
 */
public class FloatingBallService extends Service {

    private static final String CHANNEL = "nebula_float";
    private static final int NOTIF_ID = 1002;
    private static final String PREF = "nebula_settings";
    private static final String KEY_X = "float_ball_x";
    private static final String KEY_Y = "float_ball_y";

    /** 悬浮球直径（dp）。48 是 Android 最小可点区域，52 更好按 */
    private static final int SIZE_DP = 52;
    /** 判断"这是拖动还是点击"的位移阈值（dp） */
    private static final int DRAG_SLOP_DP = 5;
    /** 吸附到边缘时留的空隙（dp） */
    private static final int EDGE_GAP_DP = 6;

    private WindowManager wm;
    private View ball;
    private WindowManager.LayoutParams lp;
    private boolean added;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, FloatingBallService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, FloatingBallService.class));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 有没有"在其他应用上层显示"的权限。
     * 用全名 android.provider.Settings：本项目的 Settings 类与之同名，简写会撞车。
     */
    public static boolean canDraw(Context ctx) {
        try {
            return android.provider.Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForegroundCompat(buildNotification());
        } catch (Throwable t) {
            // 同 KeepAliveService：进不了前台就必须马上退，不能空转等系统杀进程
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!canDraw(this)) {
            // 没授权就不要留一个后台服务空转，直接退出等用户授权后再拉起
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!added) addBall();
        return START_STICKY;
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            // targetSdk 34 起必须显式给出服务类型，否则抛 MissingForegroundServiceTypeException
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "悬浮球",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("退出应用后保留的悬浮入口");
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("悬浮球已开启")
                .setContentText("点按屏幕上的圆球回到 AgenCode")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    private void addBall() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            stopSelf();
            return;
        }

        final int size = dp(SIZE_DP);
        final int slop = dp(DRAG_SLOP_DP);

        TextView v = new TextView(this);
        v.setText("AI");
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF2B6CF6);
        bg.setStroke(dp(2), 0x66FFFFFF);
        v.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) v.setElevation(dp(6));

        lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        final SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        lp.x = sp.getInt(KEY_X, dp(EDGE_GAP_DP));
        lp.y = sp.getInt(KEY_Y, dp(220));

        v.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true;
                        if (moved) {
                            lp.x = startX + dx;
                            lp.y = startY + dy;
                            try {
                                wm.updateViewLayout(view, lp);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            openApp();
                        } else {
                            snapToEdge(sp, size);
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(v, lp);
            ball = v;
            added = true;
        } catch (Throwable t) {
            // 权限被撤回、或系统拒绝 overlay 时不要崩，安静退出
            stopSelf();
        }
    }

    /** 松手后横向吸附到最近的一侧，纵向位置原样保留 */
    private void snapToEdge(SharedPreferences sp, int size) {
        try {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int gap = dp(EDGE_GAP_DP);
            lp.x = (lp.x + size / 2 < screenW / 2) ? gap : screenW - size - gap;
            wm.updateViewLayout(ball, lp);
            sp.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
        } catch (Throwable ignored) {
        }
    }

    private void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (wm != null && ball != null && added) wm.removeView(ball);
        } catch (Throwable ignored) {
        }
        added = false;
        ball = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
