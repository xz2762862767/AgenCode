package com.nebula.aichat.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 一轮回答收尾时的「结论卡」。
 *
 * <p>排版按「白纸便签」的思路重做，不再是深色渐变玻璃：
 * 底色是近乎纯白的净面（只掺 3% 强调色保住色系关联），文字用深墨色，
 * 左侧一条 3dp 强调色竖条负责身份识别，最上面一行小字「对话已结束」标明本轮到此为止。</p>
 *
 * <p>为什么反过来用浅底深字：深色卡片在深色对话流里只能靠提亮区分层级，
 * 一提亮就发灰、发闷，怎么调都像脏；换成浅底后对比度天然拉满，
 * 「洁亮」是靠对比度得到的，不靠堆高光，纯黑 OLED 主题下也不会糊成一块。</p>
 *
 * <p>层次只靠字号与颜色三档拉开：结束标记 11sp 灰 → 结论 16.5sp 粗墨 → 详述 13.5sp 中灰。
 * 没有任何描边、发光、渐变高光，卡片因此不会在滚动时抢注意力。</p>
 */
public class ConclusionCard extends LinearLayout {

    /** 标题：近黑墨蓝，压在浅底上干净利落 */
    private static final int TEXT_TITLE = 0xFF101725;
    /** 详述：比标题浅两档，读起来不抢主线 */
    private static final int TEXT_DETAIL = 0xFF3E4B62;
    /** 结束标记：最淡，只是提示「这轮完了」，不该被读到 */
    private static final int TEXT_END = 0xFF8592A6;

    private final int cTop;
    private final int cBottom;
    private final int cBar;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF rf = new RectF();
    private final RectF rfBar = new RectF();

    private final float radius;
    private final float barW;

    private ValueAnimator appearAnim;

    public ConclusionCard(Context c, String title, String detail) {
        super(c);
        setOrientation(VERTICAL);
        setWillNotDraw(false);   // ViewGroup 默认不调 onDraw，必须打开

        radius = Ui.dp(c, 14);
        barW = Ui.dp(c, 3);

        int accent = Palette.accent(c);
        // 净面：白里只掺一丝强调色，换强调色时整卡跟着微微变色，但永远不脏
        cTop = lighten(mix(0xFFFFFFFF, accent, 0.03f), 0.55f);
        cBottom = mix(0xFFFFFFFF, accent, 0.09f);
        cBar = accent;
        pBar.setColor(cBar);

        // 左内边距 = 常规 16 + 竖条 3 + 呼吸 11，正文不会压在竖条上
        int padLeft = Ui.dp(c, 16) + (int) barW + Ui.dp(c, 11);
        setPadding(padLeft, Ui.dp(c, 13), Ui.dp(c, 16), Ui.dp(c, 15));

        // ① 结束标记：写在卡片最上面，一眼知道这轮对话到此结束
        TextView tvEnd = new TextView(c);
        tvEnd.setText("对话已结束");
        tvEnd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvEnd.setTextColor(TEXT_END);
        tvEnd.setIncludeFontPadding(false);
        tvEnd.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = Ui.dp(c, 7);
        addView(tvEnd, elp);

        // ② 一句话结论：整卡唯一的粗体，字号拉到 16.5，和详述拉开明确层级
        TextView tvTitle = new TextView(c);
        tvTitle.setText(title == null ? "" : title.trim());
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(TEXT_TITLE);
        tvTitle.setLineSpacing(Ui.dp(c, 3), 1.28f);
        tvTitle.setIncludeFontPadding(false);
        tvTitle.setTextIsSelectable(true);
        addView(tvTitle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ③ 详细说明：中灰常规体，行距放宽到 1.62，长段落也不挤
        if (detail != null && !detail.trim().isEmpty()) {
            TextView tvDetail = new TextView(c);
            tvDetail.setText(detail.trim());
            tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tvDetail.setTextColor(TEXT_DETAIL);
            tvDetail.setLineSpacing(Ui.dp(c, 4), 1.62f);
            tvDetail.setIncludeFontPadding(false);
            tvDetail.setTextIsSelectable(true);
            tvDetail.setGravity(Gravity.START);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Ui.dp(c, 9);
            addView(tvDetail, dlp);
        }
    }

    /**
     * 收尾入场：整卡淡入并轻轻托起 6dp。
     * 只给「刚生成完」的那一条调用——历史里几十条同时动纯属浪费。
     * 方法名沿用 playSweep（调用方不用改），但已经不再画扫光了：
     * 净面卡片上打一道白光会显脏，动效收在透明度上更安静。
     */
    public void playSweep() {
        if (appearAnim != null) appearAnim.cancel();
        final int rise = Ui.dp(getContext(), 6);
        setAlpha(0f);
        setTranslationY(rise);
        appearAnim = ValueAnimator.ofFloat(0f, 1f);
        appearAnim.setDuration(240);
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
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        // 上下几乎同色的极淡垂直渐变：只为了不是一块死平色，肉眼几乎看不出过渡
        pBg.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{cTop, cBottom},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // ① 净面底
        rf.set(0, 0, w, h);
        cv.drawRoundRect(rf, radius, radius, pBg);

        // ② 左侧强调竖条：y 从圆角之后起、到圆角之前止，正好贴在卡片轮廓内侧，
        //    不会在四角探出去（这也是不画整圈描边的原因——只留一条，信息量足够且不吵）
        if (h > radius * 2f) {
            rfBar.set(0, radius, barW, h - radius);
            cv.drawRoundRect(rfBar, barW / 2f, barW / 2f, pBar);
        }

        super.onDraw(cv);
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

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** base 里掺 f 比例的 over（f=0 取 base，f=1 取 over） */
    private static int mix(int base, int over, float f) {
        float k = clamp01(f);
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or = (over >> 16) & 0xFF, og = (over >> 8) & 0xFF, ob = over & 0xFF;
        int r = (int) (br + (or - br) * k);
        int g = (int) (bg + (og - bg) * k);
        int b = (int) (bb + (ob - bb) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 朝白色提亮 f，亮了但不会糊成一片 */
    private static int lighten(int color, float f) {
        float k = clamp01(f);
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r = Math.min(255, (int) (r + (255 - r) * k));
        g = Math.min(255, (int) (g + (255 - g) * k));
        b = Math.min(255, (int) (b + (255 - b) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
