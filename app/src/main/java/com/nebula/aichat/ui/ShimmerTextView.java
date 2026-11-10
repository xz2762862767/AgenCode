package com.nebula.aichat.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * 文字上扫过一道柔光的 TextView，并可附带极小的立体漂移。
 *
 * <p>用法：{@link #setColors(int, int)} 定基色和高光，{@link #startShimmer(long)} 开始扫，
 * {@link #startDrift()} 开始漂移；收尾调 {@link #stopShimmer()} / {@link #stopDrift()}。</p>
 *
 * <p>为什么不是底部跑马灯：跑马灯占一条横向空间，而且天然在表达"进度"——
 * 一条匀速跑满全宽的亮带，跑完又从头开始，等价于告诉用户"快好了"，跑完却什么也没发生。
 * 工具调用的耗时本来就不可预测，不该由动画去猜。光扫在文字上只表达"这东西还活着"，
 * 顺带把视线引到正在做什么，不承诺任何百分比。</p>
 */
public class ShimmerTextView extends TextView {

    private int baseColor = 0xFF6FCF97;
    private int highlightColor = 0xFFEAFFF4;
    /** -1 表示不扫（左侧场外）；动画把它推到 1（右侧场外） */
    private float phase = -1f;
    private ValueAnimator anim;

    /**
     * 波纹显现：一段「暗—亮—暗」的色带按自己的长度平铺，整体往右推。
     * 波峰流过文字时字符亮起，波谷走过时字符隐去——看着就是一阵风从字上吹过去。
     */
    private ValueAnimator wave;
    private float waveShift = 0f;
    /** 一个完整波峰的长度：太长就看不出在流，太短会变成满屏闪 */
    private static final float WAVE_LEN_DP = 48f;
    /** 波峰走完自己长度的时间：1s 一个波峰，风是有速度的，不能拖 */
    private static final long WAVE_MS = 1000L;

    /** 运行态的立体漂移：极小的 3D 倾斜 + 位移，双频正弦合成 */
    private ValueAnimator drift;
    private static final float DRIFT_ROT_Y = 3.5f;
    private static final float DRIFT_ROT_X = 1.6f;
    private static final float DRIFT_SHIFT_DP = 1.3f;

    public ShimmerTextView(Context c) {
        super(c);
    }

    public void setColors(int base, int highlight) {
        this.baseColor = base;
        this.highlightColor = highlight;
        invalidate();
    }

    /**
     * 运行态的立体漂移。
     *
     * <p>为什么是倾斜而不是平移：整块左右移动是"晃"，像喝多了；
     * 绕 Y 轴极小角度倾斜配合相机距离，字形会有一点侧面透视，
     * 看着像一层薄片悬在纸面上方漂——立体感来自透视，不来自位移。</p>
     *
     * <p>X 与 Y 用 1:2 的不同频率，合成轨迹是李萨如曲线而非直线往返，
     * 所以永远回不到完全一样的姿态，看不出是循环动画。</p>
     *
     * <p>幅度刻意压到 3.5°/1.6°/1.3dp：再大就会看到字形锯齿变糊，
     * 这种效果只能"察觉"，一旦"看清"就变成廉价特效了。</p>
     */
    public void startDrift() {
        if (drift != null) return;
        final float d = getResources().getDisplayMetrics().density;
        // 相机距离必须跟着屏幕密度走，否则同样的角度在高分屏上透视会夸张到变形
        setCameraDistance(7000f * d);
        drift = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        drift.setDuration(5200L);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
        drift.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                Object v = a.getAnimatedValue();
                float t = v instanceof Float ? (Float) v : 0f;
                setRotationY((float) Math.sin(t) * DRIFT_ROT_Y);
                setRotationX((float) Math.sin(t * 2f) * DRIFT_ROT_X);
                setTranslationY((float) Math.sin(t + 1.1f) * DRIFT_SHIFT_DP * d);
                setTranslationX((float) Math.sin(t * 0.5f) * DRIFT_SHIFT_DP * 0.5f * d);
            }
        });
        // 一层极淡的投影：有了它，倾斜才能被读成"悬着"，
        // 否则就是一行字自己在扭。半透明黑、偏下 1px，像纸面上的真实投影
        setShadowLayer(5f, 0f, 1.2f, 0x66000000);
        drift.start();
    }

    /** 收尾：姿态必须归零，否则下一次亮起来会顶着一个歪掉的角度出现 */
    public void stopDrift() {
        if (drift != null) {
            drift.cancel();
            drift = null;
        }
        setRotationX(0f);
        setRotationY(0f);
        setTranslationX(0f);
        setTranslationY(0f);
        setShadowLayer(0f, 0f, 0f, 0);
    }

    public void startShimmer(long periodMs) {
        stopShimmer();
        anim = ValueAnimator.ofFloat(-1f, 1f);
        anim.setDuration(periodMs <= 0 ? 2200L : periodMs);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                Object v = a.getAnimatedValue();
                phase = v instanceof Float ? (Float) v : -1f;
                invalidate();
            }
        });
        anim.start();
    }

    public void stopShimmer() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        phase = -1f;
        invalidate();
    }

    public boolean isShimmering() {
        return anim != null;
    }

    /** 开跑：风起来，波纹开始流 */
    public void startWave() {
        if (wave != null) return;
        final float d = getResources().getDisplayMetrics().density;
        final float len = WAVE_LEN_DP * d;
        wave = ValueAnimator.ofFloat(0f, len);
        wave.setDuration(WAVE_MS);
        wave.setRepeatCount(ValueAnimator.INFINITE);
        wave.setInterpolator(new LinearInterpolator());
        wave.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                Object v = a.getAnimatedValue();
                waveShift = v instanceof Float ? (Float) v : 0f;
                invalidate();
            }
        });
        wave.start();
        invalidate();
    }

    /** 跑完：风停，波纹不再流，字回到常亮——「不流了」本身就是完成的信号 */
    public void stopWave() {
        if (wave != null) {
            wave.cancel();
            wave = null;
        }
        waveShift = 0f;
        invalidate();
    }

    public boolean isWaving() {
        return wave != null;
    }

    /** 按比例混合两色，只管 RGB——alpha 由调用方决定 */
    private static int mixRgb(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        TextPaint p = getPaint();
        int w = getWidth();
        if (wave != null && w > 0) {
            // 波纹：逐帧按「距波峰的距离」算出一条亮暗带，余弦调幅。
            // 不用 REPEAT + setLocalMatrix 平铺——那条路在部分 ROM 的硬件加速下
            // 会整体失效（shader 设了，字一点没变），排查起来像"代码根本没跑"。
            // 每帧自己算 9 个色标，代价可以忽略，但一定看得见
            float d = getResources().getDisplayMetrics().density;
            float len = WAVE_LEN_DP * d;
            final int n = 9;
            int[] colors = new int[n];
            float[] stops = new float[n];
            for (int i = 0; i < n; i++) {
                stops[i] = i / (float) (n - 1);
                double ph = ((stops[i] * w - waveShift) / len) * Math.PI * 2.0;
                float a = (float) (0.5 + 0.5 * Math.cos(ph));    // 1 = 波峰，0 = 波谷
                a = a * a * (3f - 2f * a);                       // smoothstep，去掉硬棱
                int alpha = (int) (0x26 + a * (0xFF - 0x26));    // 波谷只剩 15%：字是真的"隐去"
                colors[i] = (alpha << 24) | (mixRgb(baseColor, highlightColor, a) & 0x00FFFFFF);
            }
            p.setShader(new LinearGradient(0f, 0f, w, 0f, colors, stops, Shader.TileMode.CLAMP));
        } else if (phase > -1f && w > 0) {
            float band = w * 0.46f;
            float x = w * phase * 1.35f - band;
            p.setShader(new LinearGradient(x, 0, x + band, 0,
                    new int[]{baseColor, highlightColor, baseColor},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        } else {
            p.setShader(null);
            p.setColor(baseColor);
        }
        super.onDraw(canvas);
        // 必须清掉：TextView 的 paint 是共用的，留着 shader 会渗到下一次绘制（比如背景或重排）
        p.setShader(null);
    }
}
