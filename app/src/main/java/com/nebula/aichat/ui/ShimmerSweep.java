package com.nebula.aichat.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 一道从左往右扫过的光，压在整行上。
 *
 * <p>用途很单纯：说「这一行还在动」。旋转的小图标只有一个点，扫光占满整行，
 * 一眼就知道现在正在思考，而不是卡住了。</p>
 *
 * <p>实现上就是个会动的渐变矩形：一条比行宽窄一些的斜光带，从行外左侧
 * 匀速滑到行外右侧，循环。不用 shader 做文字流光，是因为那要把 TextView
 * 的 Paint 掏出来改 shader 矩阵，字一换行就错位；这里盖一层，跟文字
 * 长什么样无关，稳。</p>
 */
public class ShimmerSweep extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 光带上沿／中段／下沿的透明色，缓存下来，避免每帧新建对象 */
    private int[] colors = new int[]{0x00000000, 0x66FFFFFF, 0x00000000};
    private LinearGradient grad;
    private int gradBand = -1;

    private float progress;          // 0 → 1 表示从最左走到最右
    private int bandRatio = 42;      // 光带宽度占整行的百分比
    private long duration = 1500L;
    private ValueAnimator anim;
    private int waitTries;

    public ShimmerSweep(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        // 自己不再叠一层不透明度，颜色里已经带了 alpha
    }

    /** 换颜色：传进来的色值只取 RGB，alpha 由这里按「淡—亮—淡」配好 */
    public void setSweepColor(int rgb) {
        int base = rgb & 0x00FFFFFF;
        colors = new int[]{base, base | 0x5C000000, base};
        grad = null;
        gradBand = -1;
        invalidate();
    }

    public void setDuration(long ms) {
        duration = Math.max(200L, ms);
    }

    public boolean isRunning() {
        return anim != null && anim.isRunning();
    }

    /** 开始扫。重复调用不会叠加动画 */
    public void start() {
        if (anim != null && anim.isRunning()) return;
        if (getWidth() <= 0) {
            // 还没量到尺寸就先等一帧，别在 0 宽度上跑动画；
            // 但只等有限几次——万一这行始终没布局（被 GONE 掉了），不能无限重投
            if (waitTries++ < 20) postDelayed(this::start, 50);
            return;
        }
        waitTries = 0;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(duration);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            Object v = a.getAnimatedValue();
            progress = v instanceof Float ? (Float) v : 0f;
            invalidate();
        });
        anim.start();
    }

    /** 停下并让光带退回起点，避免停在中途留一条亮边 */
    public void stop() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        progress = 0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int band = Math.max(1, w * bandRatio / 100);
        if (grad == null || gradBand != band) {
            grad = new LinearGradient(0, 0, band, 0, colors,
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            gradBand = band;
        }
        // 起点在行外，终点也完全滑出行外，看起来才是「一遍扫过」而不是「一趟来回」
        float x = -band + progress * (w + band);
        int save = canvas.save();
        canvas.translate(x, 0);
        paint.setShader(grad);
        canvas.drawRect(0, 0, band, h, paint);
        canvas.restoreToCount(save);
    }
}
