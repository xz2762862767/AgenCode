package com.nebula.aichat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 翻译行前面的图标：地球（外圆 + 经线椭圆 + 一条赤道）。
 *
 * 尺寸对齐调用行的星轨节点：那边 r = 5.5dp（直径 11dp），这里 R = 5.2dp
 * （直径 10.4dp），减去描边占的宽度后视觉上正好一般大。之前是 6.5dp，
 * 比节点大一圈，一列看下来像两个不同量级的东西。
 *
 * 缩到这个尺寸后两条纬线会挤成一坨，所以只留赤道：三个笔画是 10dp 内
 * 还能分辨的上限。
 *
 * 画布 12dp，调用方给 leftMargin 3dp → 中心落在 9dp，跟节点同一列。
 */
public class ExplainIcon extends Drawable {

    private static final float R = 5.2f;      // 外圆半径，直径 10.4dp
    private static final float RX = 2.24f;    // 经线椭圆横向半径，约 0.43R
    private static final float SW = 1.15f;    // 线宽：小图上 1.3 会显粗发黑

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float density;
    private int color = 0xFF6E7B8B;

    public ExplainIcon(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(SW * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setColor(int c) {
        if (color != c) {
            color = c;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        final float d = density;
        final float cx = b.left + b.width() / 2f;
        final float cy = b.top + b.height() / 2f;
        final float r = R * d;

        paint.setColor(color);

        canvas.drawCircle(cx, cy, r, paint);

        oval.set(cx - RX * d, cy - r, cx + RX * d, cy + r);
        canvas.drawOval(oval, paint);

        // 赤道。端点在圆上，半宽按该高度处的圆截面算，写死会让线头戳出圆外
        float half = (float) Math.sqrt(Math.max(0, r * r - 0));
        canvas.drawLine(cx - half, cy, cx + half, cy, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(12 * density);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(12 * density);
    }
}
