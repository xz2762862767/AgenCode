package com.nebula.aichat.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
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
 * 结论区：回答最结尾那块「结论 + 详述」。
 *
 * <p>观感目标：<b>洁亮</b>——干净、通透、提亮，但绝不能变成一块发光牌。
 * 做法是「背景色 + 主题强调色」按比例混出底色，再整体提一档亮度：
 * 卡片因此永远和当前主题（深色 / 纯黑 OLED / 五档强调色）自动同源，
 * 用户换强调色时结论卡跟着换，不用在这里再维护一套写死的色值。</p>
 *
 * <p>明亮感来自三样叠加的层次，而不是靠纯白垫底：
 * ① 左上到右下的三段渐隐（上亮下沉，玻璃厚度感）；
 * ② 左上角一道强调色的斜向受光；
 * ③ 覆盖上半部分的一层极淡白高光（模拟玻璃反光，这一层最出「洁」的感觉）。
 * 纯白卡在深色流里滚动会刺眼，所以亮度上限压在「比气泡亮一档、比白低很多」的位置。</p>
 *
 * <p>文字仍用子 View（可换行、可长按选中），所以不用 Canvas 画文本。</p>
 */
public class ConclusionCard extends LinearLayout {

    /** 文字色：近白偏冷，压在彩色底上依然干净 */
    private static final int TEXT_TITLE = 0xFFF8FBFF;
    private static final int TEXT_DETAIL = 0xFFD7E3F4;

    /** 三段底色 + 受光色 + 顶部高光，构造时按当前主题算出来 */
    private final int cTop;
    private final int cMid;
    private final int cBottom;
    private final int cGlow;
    private final int cSheen;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint pSheen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint pGlint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF rf = new RectF();

    private final float radius;

    /** 银光扫掠进度：0 = 从左侧进场，1 = 已扫出右侧 */
    private float sweep = 1f;
    /** 只有正在扫的时候才画高光，静态卡（历史消息）永远不画 */
    private boolean sweeping;
    private ValueAnimator sweepAnim;

    // 扫光用的颜色/位置提到常量，避免 onDraw 每帧重新分配数组
    private static final int[] GLINT_COLORS = {0x00FFFFFF, 0x3AFFFFFF, 0x00FFFFFF};
    private static final float[] GLINT_POS = {0f, 0.5f, 1f};
    private LinearGradient glintShader;
    private final Matrix glintM = new Matrix();
    private float glintBand;

    public ConclusionCard(Context c, String title, String detail) {
        super(c);
        setOrientation(VERTICAL);
        setWillNotDraw(false);   // ViewGroup 默认不调 onDraw，必须打开

        radius = Ui.dp(c, 14);
        setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 15));

        // 底色全部由当前主题推导：背景混强调色（上多下少）+ 提亮，得到通透的玻璃面
        int bgc = Palette.bg(c);
        int accent = Palette.accent(c);
        cTop = lighten(mix(bgc, accent, 0.24f), 0.07f);
        cMid = lighten(mix(bgc, accent, 0.12f), 0.03f);
        cBottom = mix(bgc, accent, 0.05f);
        cGlow = (accent & 0x00FFFFFF) | 0x30000000;   // 左上受光：强调色压到很淡
        cSheen = 0x18FFFFFF;                          // 顶部白高光：洁净感的来源

        // 一句话结论直接当卡片第一行：原来上面还有一行「结 论」眉标，
        // 证过一轮发现它只是把已经写着结论的东西再说一遍，白占高度，去掉。

        TextView tvTitle = new TextView(c);
        tvTitle.setText(title == null ? "" : title.trim());
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(TEXT_TITLE);
        tvTitle.setLineSpacing(Ui.dp(c, 4), 1.32f);
        tvTitle.setIncludeFontPadding(false);
        tvTitle.setTextIsSelectable(true);
        addView(tvTitle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ③ 详细说明：白但略收，靠字号与行距和标题分层
        if (detail != null && !detail.trim().isEmpty()) {
            TextView tvDetail = new TextView(c);
            tvDetail.setText(detail.trim());
            tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            tvDetail.setTextColor(TEXT_DETAIL);
            tvDetail.setLineSpacing(Ui.dp(c, 4), 1.58f);
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
     * 收尾动画：一道白光从左扫到右，扫完就停。
     * 只给「刚生成完」的那条调用——历史里几十条同时扫纯属浪费。
     */
    public void playSweep() {
        if (sweepAnim != null) sweepAnim.cancel();
        sweeping = true;
        sweep = 0f;
        invalidate();
        sweepAnim = ValueAnimator.ofFloat(0f, 1f);
        sweepAnim.setDuration(520);
        sweepAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        sweepAnim.addUpdateListener(a -> {
            Object v = a.getAnimatedValue();
            sweep = v instanceof Float ? (Float) v : 1f;
            invalidate();
        });
        sweepAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                sweeping = false;
                invalidate();
            }
        });
        sweepAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;

        // 斜向渐变：左上最亮、右下收到接近背景，做出玻璃的厚度
        pBg.setShader(new LinearGradient(0, 0, w * 0.78f, h,
                new int[]{cTop, cMid, cBottom},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        // 左上角受光：强调色的淡光从角上铺开，卡片才有「被照亮」的方向感
        pGlow.setShader(new LinearGradient(0, 0, w * 0.62f, h * 0.85f,
                new int[]{cGlow, 0x00000000},
                null, Shader.TileMode.CLAMP));

        // 顶部白高光：垂直方向铺到一半就没了，模拟玻璃反光（「洁」就靠它）
        pSheen.setShader(new LinearGradient(0, 0, 0, h * 0.62f,
                new int[]{cSheen, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));

        // 扫光条预先建好，onDraw 里只平移矩阵不重建
        glintBand = w * 0.55f;
        glintShader = new LinearGradient(-glintBand, 0, glintBand, h,
                GLINT_COLORS, GLINT_POS, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // ① 底色（上亮下沉）+ 左上受光 + 顶部白高光，三层叠出洁亮的玻璃面
        rf.set(0, 0, w, h);
        cv.drawRoundRect(rf, radius, radius, pBg);
        cv.drawRoundRect(rf, radius, radius, pGlow);
        cv.drawRoundRect(rf, radius, radius, pSheen);

        // ② 扫掠高光：一条斜着的白光带，跟着 sweep 走。
        // Shader 在 onSizeChanged 里建好，这里只平移矩阵，不再每帧 new 对象（避免 GC 抖动）
        if (sweeping && glintShader != null) {
            float band = glintBand;
            float cx = -band + (w + band * 2f) * clamp01(sweep);
            glintM.setTranslate(cx, 0);
            glintShader.setLocalMatrix(glintM);
            pGlint.setShader(glintShader);
            cv.drawRoundRect(rf, radius, radius, pGlint);
        }

        super.onDraw(cv);
    }

    @Override
    protected void onDetachedFromWindow() {
        // 列表回收/清空时把动画停掉，别让它在没有窗口的地方继续跑
        if (sweepAnim != null) {
            sweepAnim.cancel();
            sweepAnim = null;
        }
        sweeping = false;
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
