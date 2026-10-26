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
 * <p>一整块墨绿玻璃底 + 微绿白文字。原先那三样描边装饰——顶部通栏粗线、
 * 线下发光、左侧竖条——全部去掉了：它们本质都在「给一块区域画边框」，
 * 而结论是正文的收束，不该看起来像个被框起来的附件。</p>
 *
 * <p>底色刻意压暗：整片高饱和的亮绿在深色主题里就是一块发光牌，
 * 每轮回答末尾闪一次，眼睛受不了。现在底色是近乎背景的深墨绿，
 * 绿色只留在左上角一道很淡的斜向光晕上。
 * 卡片靠色相区分而不是靠亮度，安静地把正文收住。</p>
 *
 * <p>文字仍用子 View（可换行、可长按选中），所以不用 Canvas 画文本。</p>
 */
public class ConclusionCard extends LinearLayout {

    /** 左上：墨绿，比正文背景只绿一点点 */
    private static final int GREEN_LIGHT = 0xFF17392C;
    /** 中段：过渡 */
    private static final int GREEN_MID = 0xFF102C22;
    /** 右下：几乎融进背景，卡片自带下沉感 */
    private static final int GREEN_DARK = 0xFF0B2019;

    /** 左上角斜向光晕：整张卡唯一的「亮」，只占一角 */
    private static final int GLOW_HEAD = 0x2444E08C;
    private static final int GLOW_TAIL = 0x00000000;

    /** 文字色：近白微绿，比纯白柔和，和绿底同源 */
    private static final int TEXT_TITLE = 0xFFF1FDF7;
    private static final int TEXT_DETAIL = 0xFFCEE8DC;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint pGlint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF rf = new RectF();

    private final float radius;

    /** 银光扫掠进度：0 = 从左侧进场，1 = 已扫出右侧 */
    private float sweep = 1f;
    /** 只有正在扫的时候才画高光，静态卡（历史消息）永远不画 */
    private boolean sweeping;
    private ValueAnimator sweepAnim;

    // 扫光用的颜色/位置提到常量，避免 onDraw 每帧重新分配数组
    private static final int[] GLINT_COLORS = {0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF};
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

        // 斜向渐变：左上稍亮、右下更深
        pBg.setShader(new LinearGradient(0, 0, w * 0.78f, h,
                new int[]{GREEN_LIGHT, GREEN_MID, GREEN_DARK},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        // 左上角光晕：从角上铺开又很快消失，让卡片有个「受光面」
        pGlow.setShader(new LinearGradient(0, 0, w * 0.62f, h * 0.85f,
                new int[]{GLOW_HEAD, GLOW_TAIL},
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

        // ① 墨绿底 + 左上受光
        rf.set(0, 0, w, h);
        cv.drawRoundRect(rf, radius, radius, pBg);
        cv.drawRoundRect(rf, radius, radius, pGlow);

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
}
