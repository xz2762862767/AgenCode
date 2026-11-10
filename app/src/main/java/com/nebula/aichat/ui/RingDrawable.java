package com.nebula.aichat.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 工具行行首的「轨道环」。
 *
 * <p>为什么是自绘 Drawable 而不是给 ImageView 挂 rotation：环要转、环里的核心图标不能转。
 * 一旦用 View 的 rotation，图标跟着转，用户就看不出这是「终端 / 文件 / 搜索」哪一类动作，
 * 语义图标旋转 360° 之后只剩一团线。所以把环放在 background 层自己跑动画，
 * 核心图标留在 foreground 层做呼吸脉冲，两层互不干扰。
 *
 * <p>动感由两段叠出来的，只转圈太"机械"：
 * <ol>
 *   <li>旋转：900ms 匀速整圈，这是底座节奏；</li>
 *   <li>弧长呼吸：270° ⇄ 200° 往返，1150ms 一轮。弧在自己伸缩的同时还在转，
 *       看起来像一段光在轨道上追自己的尾巴 —— 这是"正在干活"最直观的表达。</li>
 * </ol>
 * 两个周期不整除（900 / 1150），相位一直在错开，长时间盯着也不会看出重复感。
 *
 * <p>结束态把弧收掉只留淡色圆盘：转动停下＝进程结束，
 * 而圆盘和外框轮廓与运行态完全重合，状态切换时不会有「形状跳一下」的感觉。
 */
public class RingDrawable extends Drawable {

    /** 弧的基础扫过角度：留 90° 缺口，看得出是在「转」而不是一个整圆慢慢挪 */
    private static final float SWEEP_FULL = 270f;
    /** 呼吸到最短的弧长：太短会像断掉的括号，太长就看不出伸缩 */
    private static final float SWEEP_MIN = 200f;
    private static final long SPIN_MS = 900L;
    private static final long BREATH_MS = 1150L;

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float startAngle = -90f;
    private float sweep = SWEEP_FULL;
    private ValueAnimator spin;
    private ValueAnimator breath;
    private boolean showArc = true;

    public RingDrawable(int color) {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        discPaint.setStyle(Paint.Style.FILL);
        setTint(color);
    }

    /** 换色时不重建对象，避免每次状态刷新都 new 一次 */
    public void setTint(int color) {
        arcPaint.setColor(color);
        // 底色只给同色系一丝透明度，把图标和周围文字分出层次，又不至于变成一块实心色块
        discPaint.setColor((color & 0x00FFFFFF) | 0x2E000000);
    }

    public void setShowArc(boolean show) {
        if (showArc != show) {
            showArc = show;
            invalidateSelf();
        }
    }

    /** 开跑：旋转 + 弧长呼吸同时上。重复调用是安全的，已经在跑就直接返回 */
    public void startSpin() {
        if (spin != null) return;
        showArc = true;

        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(SPIN_MS);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> {
            startAngle = (float) a.getAnimatedValue() - 90f;
            invalidateSelf();
        });
        spin.start();

        // 弧长往返：和旋转用不同周期，两者相位错开，视觉上不会每 900ms 重复一次
        breath = ValueAnimator.ofFloat(SWEEP_FULL, SWEEP_MIN);
        breath.setDuration(BREATH_MS);
        breath.setRepeatCount(ValueAnimator.INFINITE);
        breath.setRepeatMode(ValueAnimator.REVERSE);
        breath.setInterpolator(new AccelerateDecelerateInterpolator());
        breath.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        breath.start();
    }

    /** 停转并归位：留着取消时的随机角度会让人以为是卡住了 */
    public void stopSpin() {
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
        if (breath != null) {
            breath.cancel();
            breath = null;
        }
        startAngle = -90f;
        sweep = SWEEP_FULL;
        showArc = false;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        // 线宽跟着实际像素尺寸走：图标会有 20/22dp 两种，写死 dp 会在小尺寸上糊成一圈
        arcPaint.setStrokeWidth(Math.max(2f, bounds.width() * 0.082f));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) / 2f - arcPaint.getStrokeWidth() / 2f;
        if (r <= 0f) return;
        canvas.drawCircle(cx, cy, r, discPaint);
        if (showArc) {
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, startAngle, sweep, false, arcPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        arcPaint.setAlpha(alpha);
        discPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        arcPaint.setColorFilter(cf);
        discPaint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
