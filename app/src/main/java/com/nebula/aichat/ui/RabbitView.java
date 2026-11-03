package com.nebula.aichat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * 跑动的小兔子：四帧几何 + 横向循环位移，全在一个 View 里画完。
 *
 * <p>和上一版的区别（上一版是单色 VectorDrawable + tint）：
 *
 * <p><b>轮廓</b>——上一版的几何是把椭圆和胶囊一个个丢进 PIL 的 fill() 里取并集，
 * 轮廓上保留了每段圆弧的接缝，放大看就是"几个圆球拼出来的"。现在几何由
 * {@code tools/gen_rabbit_hi.py} 用有符号距离场（SDF）加 smooth-min 融合成一块，
 * 再从 0 等值线提轮廓，部件之间是平滑过渡，像一整块面团捏出来的。
 *
 * <p><b>层次</b>——单色剪影少了"真实感"的另一半。现在分六层画：远侧腿（压暗，
 * 分出前后）、主体（渐变，背部受光腹部背光，出体积）、耳内侧（浅色，真实兔子耳内
 * 是浅粉/白而不是同色）、白尾巴、眼睛、鼻尖。兔子屁股上那块白是最强的识别特征之一，
 * 之前整只一个颜色把它抹掉了。
 *
 * <p>为什么自绘而不是 AnimationDrawable 叠位移动画：位移每帧都要变，帧切换和位移
 * 本来就得在同一个绘制周期里算，拆成两层只是多一次重绘。
 */
public final class RabbitView extends View {

    /**
     * 每帧停留时长（毫秒）。四帧不均分：腾空收拢那帧（索引 1）停最久，蹬地和触地最短
     * —— 兔子是蹦跳着跑的，滞空长、着地快，均分帧时长出来会像"匀速滑行"。
     * 腾空帧从 120 提到 150：滞空感主要靠这一段撑，其余三帧各压 2~4ms 把整循环收短。
     */
    private static final int[] FRAME_MS = {78, 150, 74, 108};

    /** 从左外侧跑到右外侧一轮的时长。2400 偏温吞，改成 2000 才有扑出去的速度感。 */
    private static final int TRACK_MS = 2000;

    /** 图形高度占控件高度的比例。控件竖直空间是从横幅内容里让出来的，留白要少。 */
    private static final float HEIGHT_RATIO = 0.95f;

    /** 与几何的视口 24:16 保持一致，否则会被拉扁 */
    private static final float ASPECT = 24f / 16f;

    private static final float VP_H = 16f;

    /** 几何是常量，Path 建一次就够；静态懒加载，不拖慢 View 构造。 */
    private static Path[][] paths;

    private final Paint bodyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flat = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] layerColor = new int[RabbitGeometry.LAYERS];

    private final int cycleMs;

    private int tint = 0xFF93F2C4;
    private boolean colorDirty = true;
    private boolean running;
    private long startAt;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postOnAnimation(this);   // 跟着 vsync 走，比固定 delay 稳
        }
    };

    public RabbitView(Context c) {
        super(c);
        int sum = 0;
        for (int ms : FRAME_MS) sum += ms;
        cycleMs = sum;
        bodyEdge.setStyle(Paint.Style.STROKE);
        bodyEdge.setStrokeWidth(0.34f);   // 视口单位：14dp 高时约 1px@3x
        setAlpha(0.95f);
    }

    /** 换主题时改颜色。几何是纯白坐标，颜色全在这里派生。 */
    public void setTintColor(int color) {
        if (tint == color) return;
        tint = color;
        colorDirty = true;
        invalidate();
    }

    public void start() {
        startAt = SystemClock.uptimeMillis();
        running = true;
        removeCallbacks(ticker);
        postOnAnimation(ticker);
    }

    public void stop() {
        running = false;
        removeCallbacks(ticker);
    }

    public boolean isRunning() {
        return running;
    }

    /** 按"帧时长不相等"的时间轴找当前该画哪一帧 */
    private int frameAt(long el) {
        int t = (int) (el % cycleMs);
        for (int i = 0; i < FRAME_MS.length; i++) {
            if (t < FRAME_MS[i]) return i;
            t -= FRAME_MS[i];
        }
        return FRAME_MS.length - 1;
    }

    /**
     * 颜色全部从主色派生，所以横幅换主题色时兔子自动跟着变，不用出多套资源。
     * 主体用竖向渐变（背亮腹暗）—— 单一平涂在 14dp 上也只是一块色斑，加了明暗才有体积。
     */
    private void applyColors() {
        colorDirty = false;
        bodyFill.setShader(new LinearGradient(0f, 2.2f, 0f, 15.6f,
                mix(tint, 0xFFFFFFFF, 0.30f),
                mix(tint, 0xFF000000, 0.26f),
                Shader.TileMode.CLAMP));
        bodyEdge.setColor(mix(tint, 0xFF000000, 0.30f));
        bodyEdge.setAlpha(130);
        layerColor[RabbitGeometry.BODY] = tint;
        layerColor[RabbitGeometry.FAR_LEG] = withAlpha(mix(tint, 0xFF000000, 0.40f), 150);
        layerColor[RabbitGeometry.EAR_INNER] = withAlpha(mix(tint, 0xFFFFFFFF, 0.62f), 235);
        layerColor[RabbitGeometry.TAIL] = mix(tint, 0xFFFFFFFF, 0.86f);
        layerColor[RabbitGeometry.EYE] = mix(tint, 0xFF000000, 0.88f);
        layerColor[RabbitGeometry.NOSE] = mix(tint, 0xFF000000, 0.72f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (colorDirty) applyColors();

        Path[][] frames = paths();
        if (frames == null || frames.length == 0) return;

        long el = SystemClock.uptimeMillis() - startAt;
        Path[] frame = frames[frameAt(el) % frames.length];

        float ih = h * HEIGHT_RATIO;
        float iw = ih * ASPECT;
        float k = ih / VP_H;
        // 一个周期从左边外侧走到右边外侧，走完瞬间回到起点重来，中间没有"倒着走"
        float p = (el % TRACK_MS) / (float) TRACK_MS;
        float left = -iw + p * (w + iw);
        float top = (h - ih) * 0.5f;

        canvas.save();
        canvas.translate(left, top);
        canvas.scale(k, k);

        for (int layer = 0; layer < frame.length; layer++) {
            Path path = frame[layer];
            if (path == null) continue;
            if (layer == RabbitGeometry.BODY) {
                canvas.drawPath(path, bodyFill);
                canvas.drawPath(path, bodyEdge);
            } else {
                flat.setColor(layerColor[layer]);
                canvas.drawPath(path, flat);
            }
        }
        canvas.restore();
    }

    /**
     * 把每层的一个或多个多边形合并成一条 Path（同层同色，一次 drawPath 就够）。
     * 点数在生成阶段已经用 Douglas-Peucker 减过，这里直接 lineTo。
     */
    private static Path[][] paths() {
        if (paths != null) return paths;
        float[][][][] poly = RabbitGeometry.POLY;
        Path[][] out = new Path[poly.length][RabbitGeometry.LAYERS];
        for (int f = 0; f < poly.length; f++) {
            for (int l = 0; l < poly[f].length; l++) {
                if (poly[f][l].length == 0) continue;
                Path path = new Path();
                for (float[] pts : poly[f][l]) {
                    if (pts.length < 6) continue;
                    path.moveTo(pts[0], pts[1]);
                    for (int i = 2; i < pts.length - 1; i += 2) {
                        path.lineTo(pts[i], pts[i + 1]);
                    }
                    path.close();
                }
                out[f][l] = path;
            }
        }
        paths = out;
        return out;
    }

    private static int mix(int a, int b, float t) {
        float u = 1f - t;
        int r = (int) (((a >> 16) & 0xFF) * u + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * u + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * u + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int h = resolveSize(Math.round(dp(15)), hSpec);
        int w = resolveSize(Math.round(dp(20)), wSpec);
        setMeasuredDimension(w, h);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        // 被隐藏就别再空转，但 running 状态留着，重新可见时接着跑
        if (visibility == VISIBLE) {
            if (running) {
                removeCallbacks(ticker);
                postOnAnimation(ticker);
            }
        } else {
            removeCallbacks(ticker);
        }
    }
}
