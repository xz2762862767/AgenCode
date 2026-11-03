package com.nebula.aichat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * 极简线性图标：全部用 Canvas 手绘，不依赖图片资源也不依赖 emoji。
 *
 * <p>为什么自己画：菜单、标签、历史这三处以前是用「↩ ⟳ ✕ ●」这类字符拼的，
 * 不同机型字体缺字会变成方框，而且粗细、视觉重量完全不统一，一排下来像补丁。
 * 手绘路径只有一套 stroke 宽度和圆角端点，深浅主题下换个颜色就成立。
 *
 * <p>坐标统一按 24×24 网格设计（跟常见图标库一致），绘制时整体缩放到 View 尺寸，
 * 所以同一个图标放到 16dp 还是 28dp 都不会变形。
 */
public class Icons extends View {

    public static final int BACK = 1;
    public static final int FORWARD = 2;
    public static final int RELOAD = 3;
    public static final int HOME = 4;
    public static final int EXTERNAL = 5;
    public static final int HISTORY = 6;
    public static final int TAB = 7;
    public static final int PLUS = 8;
    public static final int CLOSE = 9;
    public static final int TRASH = 10;
    public static final int CHECK = 11;
    /** 单个汉字/字母，用于「译」这种线性画不出意思的项 */
    public static final int GLYPH = 90;

    private int type = BACK;
    private int color = 0xFFFFFFFF;
    private String glyph = "";

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arc = new RectF();
    /** 下面三个只在 onDraw 里用，提成字段复用：列表滚动时每帧 new 会让 GC 抖 */
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillTmp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    public Icons(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        applyColor();
    }

    public static Icons of(Context c, int type, int color) {
        Icons v = new Icons(c);
        v.type = type;
        v.setColor(color);
        return v;
    }

    /** 用文字代替图形的图标（例如「译」） */
    public static Icons glyph(Context c, String g, int color) {
        Icons v = new Icons(c);
        v.type = GLYPH;
        v.glyph = g;
        v.setColor(color);
        return v;
    }

    public void setType(int t) {
        this.type = t;
        invalidate();
    }

    public void setColor(int argb) {
        this.color = argb;
        applyColor();
        invalidate();
    }

    private void applyColor() {
        stroke.setColor(color);
        fill.setColor(color);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h) / 24f;      // 24 网格 → 实际像素

        if (type == GLYPH) {
            Paint t = textPaint;
            t.setColor(color);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(Math.min(w, h) * 0.72f);
            t.setFakeBoldText(true);
            Paint.FontMetrics fm = t.getFontMetrics();
            float cy = h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(glyph, w / 2f, cy, t);
            return;
        }

        stroke.setStrokeWidth(1.9f * s);
        canvas.save();
        canvas.translate((w - 24 * s) / 2f, (h - 24 * s) / 2f);
        canvas.scale(s, s);

        switch (type) {
            case BACK:
                line(canvas, 19.5f, 12f, 4.5f, 12f);
                line(canvas, 4.5f, 12f, 11f, 5.5f);
                line(canvas, 4.5f, 12f, 11f, 18.5f);
                break;
            case FORWARD:
                line(canvas, 4.5f, 12f, 19.5f, 12f);
                line(canvas, 19.5f, 12f, 13f, 5.5f);
                line(canvas, 19.5f, 12f, 13f, 18.5f);
                break;
            case RELOAD:
                // 缺口放右上，箭头沿切线指向顺时针方向，看着才「会转」
                arc.set(4f, 4f, 20f, 20f);
                canvas.drawArc(arc, 45f, 270f, false, stroke);
                triangle(canvas, 19.9f, 8.6f, 15.8f, 8.2f, 19.5f, 4.5f);
                break;
            case HOME:
                line(canvas, 3f, 11f, 12f, 4f);
                line(canvas, 12f, 4f, 21f, 11f);
                line(canvas, 6f, 11f, 6f, 20f);
                line(canvas, 18f, 11f, 18f, 20f);
                line(canvas, 6f, 20f, 18f, 20f);
                line(canvas, 10.5f, 20f, 10.5f, 14.5f);
                line(canvas, 10.5f, 14.5f, 13.5f, 14.5f);
                line(canvas, 13.5f, 14.5f, 13.5f, 20f);
                break;
            case EXTERNAL:
                path.reset();
                path.moveTo(13f, 5f);
                path.lineTo(5f, 5f);
                path.lineTo(5f, 19f);
                path.lineTo(19f, 19f);
                path.lineTo(19f, 11f);
                canvas.drawPath(path, stroke);
                line(canvas, 11.5f, 12.5f, 20f, 4f);
                line(canvas, 14f, 4f, 20f, 4f);
                line(canvas, 20f, 4f, 20f, 10f);
                break;
            case HISTORY:
                path.reset();
                canvas.drawCircle(12f, 12f, 8.2f, stroke);
                line(canvas, 12f, 7.5f, 12f, 12.4f);
                line(canvas, 12f, 12.4f, 15.6f, 14.4f);
                break;
            case TAB:
                // 前面一张卡片 + 后面一张错开的，两层就够表达「多标签」
                path.reset();
                tmpRect.set(9f, 3.5f, 20.5f, 15.5f);
                path.addRoundRect(tmpRect, 2.6f, 2.6f, Path.Direction.CW);
                canvas.drawPath(path, stroke);
                path.reset();
                tmpRect.set(3.5f, 8.5f, 15.5f, 20.5f);
                path.addRoundRect(tmpRect, 2.6f, 2.6f, Path.Direction.CW);
                fill(canvas, path, Palette.isLight(getContext()) ? 0xFFF2F5FA : 0xFF141A23);
                canvas.drawPath(path, stroke);
                break;
            case PLUS:
                line(canvas, 12f, 5f, 12f, 19f);
                line(canvas, 5f, 12f, 19f, 12f);
                break;
            case CLOSE:
                line(canvas, 6.5f, 6.5f, 17.5f, 17.5f);
                line(canvas, 17.5f, 6.5f, 6.5f, 17.5f);
                break;
            case TRASH:
                line(canvas, 4f, 7f, 20f, 7f);
                line(canvas, 9.5f, 7f, 9.5f, 4.2f);
                line(canvas, 9.5f, 4.2f, 14.5f, 4.2f);
                line(canvas, 14.5f, 4.2f, 14.5f, 7f);
                path.reset();
                path.moveTo(6.4f, 7f);
                path.lineTo(7.5f, 20f);
                path.lineTo(16.5f, 20f);
                path.lineTo(17.6f, 7f);
                canvas.drawPath(path, stroke);
                line(canvas, 10.5f, 10.5f, 10.9f, 16.5f);
                line(canvas, 13.5f, 10.5f, 13.1f, 16.5f);
                break;
            case CHECK:
                line(canvas, 4.5f, 12.5f, 9.5f, 17.5f);
                line(canvas, 9.5f, 17.5f, 19.5f, 7f);
                break;
            default:
                break;
        }
        canvas.restore();
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, stroke);
    }

    private void triangle(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        c.drawPath(path, fill);
    }

    /** 用底色填充路径（标签图标的「前一张卡片」要遮住后面那张的线） */
    private void fill(Canvas c, Path p, int color) {
        fillTmp.setStyle(Paint.Style.FILL);
        fillTmp.setColor(color);
        c.drawPath(p, fillTmp);
    }

    /** 把一个图标包进 34dp 的圆角小方块里，做成菜单行左侧的样子 */
    public static android.widget.FrameLayout inTile(Context c, View icon, int tint, int bgColor) {
        android.widget.FrameLayout tile = new android.widget.FrameLayout(c);
        int size = Ui.dp(c, 34);
        tile.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        tile.setBackground(Ui.roundRect(c, bgColor, 0, 11f));
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 19), Ui.dp(c, 19));
        lp.gravity = android.view.Gravity.CENTER;
        icon.setLayoutParams(lp);
        tile.addView(icon);
        return tile;
    }
}
