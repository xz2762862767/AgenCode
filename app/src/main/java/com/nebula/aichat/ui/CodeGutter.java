package com.nebula.aichat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

/**
 * 代码块左侧行号列。
 *
 * 不另建文本（那样字号/行距一变就会错位），而是直接读取代码 TextView 的 Layout，
 * 按 layout.getLineBaseline(i) 逐行绘制——所以换行、折叠、字号变化都自动对齐。
 * 它放在 HorizontalScrollView 外面，横向滚动时代码滑动、行号固定。
 */
public class CodeGutter extends View {

    private final TextView code;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int padLeft;
    private final int padRight;
    private int totalLines = 1;
    private int gutterWidth;

    public CodeGutter(Context ctx, TextView codeView) {
        super(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        padLeft = (int) (10 * density);
        padRight = (int) (8 * density);
        this.code = codeView;
        paint.setTextSize(codeView.getTextSize());
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(Palette.c(0xFF4C566E));
        paint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setTotalLines(int n) {
        totalLines = Math.max(1, n);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        String widest = String.valueOf(totalLines);
        gutterWidth = padLeft + padRight + (int) Math.ceil(paint.measureText(widest));
        super.onMeasure(MeasureSpec.makeMeasureSpec(gutterWidth, MeasureSpec.EXACTLY), heightSpec);
        // 父容器高度未定（wrap_content）时，跟着代码视图的高度走
        if (getMeasuredHeight() == 0 && code.getMeasuredHeight() > 0) {
            setMeasuredDimension(gutterWidth, code.getMeasuredHeight());
        } else {
            setMeasuredDimension(gutterWidth, getMeasuredHeight());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Layout layout = code.getLayout();
        if (layout == null) return;

        int visible = layout.getLineCount();
        int max = code.getMaxLines();
        if (max > 0 && max < visible) visible = max;

        int top = code.getPaddingTop();
        int right = getWidth() - padRight;
        for (int i = 0; i < visible && i < totalLines; i++) {
            float baseline = top + layout.getLineBaseline(i);
            if (baseline > getHeight()) break;
            canvas.drawText(String.valueOf(i + 1), right, baseline, paint);
        }
    }
}
