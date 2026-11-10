package com.nebula.aichat.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/**
 * 工具调用的「星轨」：一条竖线把所有步骤串成一条轨迹，每次调用是线上一个节点。
 *
 * 为什么用 Drawable 而不是塞几个 View：
 * 竖线要跨行连续、节点要压在线上、光晕要跟着状态呼吸——这些用 View 拼（线一个 View、
 * 节点一个 View、光晕再来一个）在每行都要建三个视图，连续十几次调用就是四五十个 View，
 * 而且行高变化时光晕和节点的对齐很容易错位。画出来只有一个 Drawable，bounds 一变自动重排。
 *
 * 节点四态：
 *   PENDING  空心灰环，等轮到自己
 *   RUN      空心绿环 + 外圈呼吸光晕（唯一会动的状态）
 *   OK       实心绿点 + 白勾
 *   FAIL     实心红点 + 白叉
 */
public class TrackBg extends Drawable {

    public static final int PENDING = 0;
    public static final int RUN = 1;
    public static final int OK = 2;
    public static final int FAIL = 3;

    private final float d;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF arcRect = new android.graphics.RectF();
    private final Path markPath = new Path();

    private int state = PENDING;
    private boolean hasPrev = false;
    private boolean tailFade = true;
    private float phase = 0f;

    private ValueAnimator glow;

    private final int cIdle;      // 线 / 空心环
    private final int cRun;
    private final int cOk;
    private final int cFail;

    public TrackBg(Context c) {
        d = c.getResources().getDisplayMetrics().density;
        cIdle = Palette.c(c, 0xFF2A3240);
        cRun = Palette.c(c, 0xFF37E8A6);
        cOk = Palette.c(c, 0xFF3DDC97);
        cFail = Palette.c(c, 0xFFFF6B6B);

        linePaint.setStrokeWidth(dp(1.2f));
        nodeStroke.setStyle(Paint.Style.STROKE);
        nodeStroke.setStrokeWidth(dp(1.6f));
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeWidth(dp(1.5f));
        markPaint.setStrokeCap(Paint.Cap.ROUND);
        markPaint.setStrokeJoin(Paint.Join.ROUND);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(2.2f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private float dp(float v) {
        return v * d;
    }

    public void setState(int st) {
        if (state != st) {
            state = st;
            if (st == RUN) startGlow();
            else stopGlow();
            invalidateSelf();
        }
    }

    public void setHasPrev(boolean b) {
        if (hasPrev != b) {
            hasPrev = b;
            invalidateSelf();
        }
    }

    /** 最后一条不再需要向下延伸，尾巴渐隐 */
    public void setTailFade(boolean b) {
        if (tailFade != b) {
            tailFade = b;
            invalidateSelf();
        }
    }

    private void startGlow() {
        if (glow != null) return;
        glow = ValueAnimator.ofFloat(0f, 1f);
        glow.setDuration(1200);
        glow.setRepeatCount(ValueAnimator.INFINITE);
        glow.setInterpolator(new LinearInterpolator());
        glow.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        glow.start();
    }

    private void stopGlow() {
        if (glow != null) {
            glow.cancel();
            glow = null;
        }
        phase = 0f;
    }

    /** 行被移除时必须调用，否则动画会一直空转 */
    public void release() {
        stopGlow();
    }

    @Override
    public void draw(Canvas cv) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        float cx = dp(9f);
        // 17dp = 头行最小高度 34dp 的一半。节点必须咬住调用行的中线，
        // 否则整条时间轴会显得比卡片高半格
        float cy = dp(17f);
        float r = dp(5.5f);

        // ── 竖线 ──
        // 第一行不从顶上画：轨迹有起点，不是凭空垂下来
        float top = hasPrev ? b.top : cy;
        float bot = b.bottom;
        if (tailFade && state != RUN) bot = Math.min(bot, cy + dp(16f));
        if (bot > top) {
            if (tailFade && state != RUN) {
                linePaint.setShader(new LinearGradient(0, top, 0, bot,
                        cIdle, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            } else {
                linePaint.setShader(null);
                linePaint.setColor(cIdle);
            }
            cv.drawLine(cx, top, cx, bot, linePaint);
            linePaint.setShader(null);
        }

        // ── 节点 ──
        switch (state) {
            case RUN:
                // 外圈光晕：扩散 + 淡出，循环
                int ga = (int) (96 * (1f - phase));
                glowPaint.setStyle(Paint.Style.FILL);
                glowPaint.setColor((ga << 24) | (cRun & 0x00FFFFFF));
                cv.drawCircle(cx, cy, r + dp(3f) + dp(4.5f) * phase, glowPaint);
                // 底环压暗：留一圈"坑"给转动的弧，两者叠起来才看得出在绕圈
                nodeStroke.setColor((0x50 << 24) | (cRun & 0x00FFFFFF));
                cv.drawCircle(cx, cy, r, nodeStroke);
                // 缺口弧绕圈：1200ms 一圈、缺口 100°。
                // 之前只有一圈呼吸光晕，半径才 5dp，静着看几秒才分得出，
                // 等于告诉用户"没在动"——这是被指出的核心问题
                arcRect.set(cx - r, cy - r, cx + r, cy + r);
                arcPaint.setColor(cRun);
                cv.drawArc(arcRect, phase * 360f, 100f, false, arcPaint);
                // 中心实心核，跑着的时候是"燃着"的
                nodeFill.setColor((0xB0 << 24) | (cRun & 0x00FFFFFF));
                cv.drawCircle(cx, cy, r * 0.42f, nodeFill);
                break;
            case OK:
                nodeFill.setColor(cOk);
                cv.drawCircle(cx, cy, r, nodeFill);
                drawCheck(cv, cx, cy, r);
                break;
            case FAIL:
                nodeFill.setColor(cFail);
                cv.drawCircle(cx, cy, r, nodeFill);
                drawCross(cv, cx, cy, r);
                break;
            default:
                nodeStroke.setColor(cIdle);
                cv.drawCircle(cx, cy, r, nodeStroke);
                break;
        }
    }

    private void drawCheck(Canvas cv, float cx, float cy, float r) {
        markPaint.setColor(0xFF0F1218);
        markPath.reset();
        markPath.moveTo(cx - r * 0.44f, cy + r * 0.04f);
        markPath.lineTo(cx - r * 0.10f, cy + r * 0.38f);
        markPath.lineTo(cx + r * 0.46f, cy - r * 0.36f);
        cv.drawPath(markPath, markPaint);
    }

    private void drawCross(Canvas cv, float cx, float cy, float r) {
        markPaint.setColor(0xFF0F1218);
        float k = r * 0.40f;
        cv.drawLine(cx - k, cy - k, cx + k, cy + k, markPaint);
        cv.drawLine(cx + k, cy - k, cx - k, cy + k, markPaint);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
