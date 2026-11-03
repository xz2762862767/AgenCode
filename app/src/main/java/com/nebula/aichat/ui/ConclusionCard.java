package com.nebula.aichat.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 一轮回答收尾时的「结论」段。
 *
 * <p>这里刻意不做卡片：没有底色、没有圆角、没有描边、没有强调色竖条。
 * 它和上面的正文共用同一片背景、同一条左基线，视觉上就是正文的最后一段——
 * 只是排版上多了一层结构：最上面一行「对话已结束」标明本轮到此为止，
 * 中间是加粗的一句话结论，下面是可以略过的详述。</p>
 *
 * <p>文字色全部取自当前主题（text / dim），所以深色、纯黑 OLED、浅色主题下都自动跟正文一致，
 * 不会出现「一段文字压在另一块颜色上」的割裂感。</p>
 */
public class ConclusionCard extends LinearLayout {

    private ValueAnimator appearAnim;

    public ConclusionCard(Context c, String title, String detail) {
        super(c);
        setOrientation(VERTICAL);
        // 左右不留内边距：结论段的左边缘要和正文严格对齐，多 1dp 都看得出来
        setPadding(0, Ui.dp(c, 2), 0, 0);

        int textColor = Palette.text(c);
        int dimColor = Palette.dim(c);

        // ① 结束标记：最淡的一档，只是告诉读者「这轮说完了」，不该被读到
        TextView tvEnd = new TextView(c);
        tvEnd.setText("对话已结束");
        tvEnd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvEnd.setTextColor(dimColor);
        tvEnd.setIncludeFontPadding(false);
        tvEnd.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = Ui.dp(c, 7);
        addView(tvEnd, elp);

        // ② 一句话结论：全段唯一的加粗，靠字重和字号与正文分层
        TextView tvTitle = new TextView(c);
        tvTitle.setText(title == null ? "" : title.trim());
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(textColor);
        tvTitle.setLineSpacing(Ui.dp(c, 3), 1.30f);
        tvTitle.setIncludeFontPadding(false);
        tvTitle.setTextIsSelectable(true);
        addView(tvTitle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ③ 详述：常规字重、稍微收淡，行距放宽，长段落也不挤
        if (detail != null && !detail.trim().isEmpty()) {
            TextView tvDetail = new TextView(c);
            tvDetail.setText(detail.trim());
            tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            tvDetail.setTextColor(dimColor);
            tvDetail.setLineSpacing(Ui.dp(c, 4), 1.55f);
            tvDetail.setIncludeFontPadding(false);
            tvDetail.setTextIsSelectable(true);
            tvDetail.setGravity(Gravity.START);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Ui.dp(c, 8);
            addView(tvDetail, dlp);
        }
    }

    /**
     * 收尾入场：整段淡入并轻轻托起 4dp。
     * 方法名沿用 playSweep（调用方不用改）；没有底色之后任何扫光/高光都没有落点，
     * 动效只保留透明度，尽量安静。
     */
    public void playSweep() {
        if (appearAnim != null) appearAnim.cancel();
        final int rise = Ui.dp(getContext(), 4);
        setAlpha(0f);
        setTranslationY(rise);
        appearAnim = ValueAnimator.ofFloat(0f, 1f);
        appearAnim.setDuration(200);
        appearAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        appearAnim.addUpdateListener(a -> {
            Object v = a.getAnimatedValue();
            float f = v instanceof Float ? (Float) v : 1f;
            setAlpha(f);
            setTranslationY(rise * (1f - f));
        });
        appearAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        // 列表回收/清空时把动画停掉，别让它在没有窗口的地方继续跑
        if (appearAnim != null) {
            appearAnim.cancel();
            appearAnim = null;
        }
        super.onDetachedFromWindow();
    }
}
