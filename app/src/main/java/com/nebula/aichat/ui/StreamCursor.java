package com.nebula.aichat.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * 正文末尾的呼吸光标：一条竖线，随文字一起往后走。
 *
 * <p>为什么需要它：光扫和漂移只挂在工具行上，模型在生成正文（尤其是写代码）时
 * 一个工具都没调用，气泡里完全是静止的——静止既可以是"正在写"，也可以是"写完了"，
 * 用户没法区分。光标是唯一能表达"还在写"的东西，而且它长在文字后面，
 * 天然属于"正在写"这个语义，不需要额外解释。</p>
 *
 * <p>为什么所有宿主共享一个呼吸值：一屏里可能同时挂好几个（多轮回复），
 * 各自跑各自的动画会错拍，看起来像闪烁故障。共享一个相位，所有光标同频呼吸。</p>
 */
public class StreamCursor extends ReplacementSpan {

    private static final ArrayList<WeakReference<TextView>> HOSTS =
            new ArrayList<WeakReference<TextView>>();
    private static ValueAnimator breath;
    /** 共享呼吸相位：0.28 暗 → 0.95 亮 */
    private static float glow = 0.95f;

    private static final float MIN_GLOW = 0.28f;
    private static final float MAX_GLOW = 0.95f;
    private static final long BREATH_MS = 760L;

    private final int color;
    private final float width;
    private final float gap;
    private final RectF rect = new RectF();

    public StreamCursor(int color, float widthPx) {
        this.color = color;
        this.width = widthPx;
        this.gap = widthPx * 0.9f;
    }

    /** 登记宿主：同一时刻只有这些 TextView 会被动画驱动重绘 */
    public static void host(TextView tv) {
        if (tv == null) return;
        purge();
        for (int i = 0; i < HOSTS.size(); i++) {
            if (HOSTS.get(i).get() == tv) {
                ensureBreath();
                return;
            }
        }
        HOSTS.add(new WeakReference<TextView>(tv));
        ensureBreath();
    }

    /** 内容重渲染后旧宿主作废，必须显式释放，否则会一直空转重绘 */
    public static void release(TextView tv) {
        purge();
        for (int i = HOSTS.size() - 1; i >= 0; i--) {
            TextView h = HOSTS.get(i).get();
            if (h == null || h == tv) HOSTS.remove(i);
        }
        if (HOSTS.isEmpty()) stopBreath();
    }

    public static void releaseAll() {
        HOSTS.clear();
        stopBreath();
    }

    private static void purge() {
        for (int i = HOSTS.size() - 1; i >= 0; i--) {
            if (HOSTS.get(i).get() == null) HOSTS.remove(i);
        }
    }

    private static void ensureBreath() {
        if (breath != null) return;
        breath = ValueAnimator.ofFloat(MIN_GLOW, MAX_GLOW);
        breath.setDuration(BREATH_MS);
        breath.setRepeatCount(ValueAnimator.INFINITE);
        breath.setRepeatMode(ValueAnimator.REVERSE);
        breath.setInterpolator(new AccelerateDecelerateInterpolator());
        breath.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                Object v = a.getAnimatedValue();
                glow = v instanceof Float ? (Float) v : MAX_GLOW;
                for (int i = HOSTS.size() - 1; i >= 0; i--) {
                    TextView tv = HOSTS.get(i).get();
                    if (tv == null) HOSTS.remove(i);
                    else tv.invalidate();
                }
            }
        });
        breath.start();
    }

    private static void stopBreath() {
        if (breath != null) {
            breath.cancel();
            breath = null;
        }
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fm) {
        if (fm != null) {
            // 不填好这些值，光标这一行会比别的行矮一截，段落末尾看起来会塌
            Paint.FontMetricsInt m = paint.getFontMetricsInt();
            fm.ascent = m.ascent;
            fm.descent = m.descent;
            fm.top = m.top;
            fm.bottom = m.bottom;
        }
        return (int) Math.ceil(width + gap);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                     int top, int y, int bottom, Paint paint) {
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        if (fm == null || fm.descent <= fm.ascent) return;
        float h = (fm.descent - fm.ascent) * 0.94f;
        // 基线往上挪半个行高：光标视觉重心对齐字形中部，不是行框中部
        float cy = y - (fm.ascent + fm.descent) / 2f;
        float l = x + gap * 0.5f;
        rect.set(l, cy - h / 2f, l + width, cy + h / 2f);

        int oldColor = paint.getColor();
        Paint.Style oldStyle = paint.getStyle();
        int a = (int) (Math.max(0f, Math.min(1f, glow)) * 255);
        // 只替换 alpha，色相仍由强调色决定，不会在正文里多出一种颜色
        paint.setColor((color & 0x00FFFFFF) | (a << 24));
        paint.setStyle(Paint.Style.FILL);
        float r = width / 2f;
        canvas.drawRoundRect(rect, r, r, paint);
        paint.setStyle(oldStyle);
        paint.setColor(oldColor);
    }
}
