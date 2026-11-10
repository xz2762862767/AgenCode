package com.nebula.aichat.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.util.Locale;

/**
 * 产物图标：不再用 emoji（真机上 emoji 字体缺失会退化成系统机器人，而且所有产物长一个样）。
 *
 * <p>两层策略：一是 APK 先读它自己声明的 launcher 图标，那是作者自己的「脸」，能用就用；
 * 二是读不到、或读出来就是系统默认小机器人（模型生成的工程十有八九懒得换图标），
 * 就按文件类型 + 文件名现画一个：不同色系的渐变圆角底 + 一个白色符号，
 * 同类型内部再按文件名哈希微调色相，让每个产物的图标都不一样。
 *
 * <p>全程 Canvas 自绘，不引第三方库、不占资源文件。
 */
public final class ArtiIcon {

    private ArtiIcon() {
    }

    // ---- 类型：决定画什么符号、用什么色系 ----
    private static final int K_APK = 0;
    private static final int K_IMG = 1;
    private static final int K_VID = 2;
    private static final int K_AUD = 3;
    private static final int K_ZIP = 4;
    private static final int K_DOC = 5;
    private static final int K_CODE = 6;
    private static final int K_WEB = 7;
    private static final int K_DATA = 8;
    private static final int K_SHELL = 9;
    private static final int K_ANY = 10;

    /** 每类一套渐变色（起始色 → 结束色），明度压过，落在深色气泡上不刺眼 */
    private static final int[][] HUE = {
            {0xFF3DDC97, 0xFF1B9C72},   // APK 青绿
            {0xFFFF8FB1, 0xFFD94C7C},   // 图片 粉
            {0xFF9B7BFF, 0xFF6440D0},   // 视频 紫
            {0xFFFFB25C, 0xFFDE7414},   // 音频 橙
            {0xFF7AB8FF, 0xFF3568CC},   // 压缩 蓝
            {0xFF93A9C6, 0xFF5A7089},   // 文档 灰蓝
            {0xFF5AD2F4, 0xFF2489B3},   // 代码 青
            {0xFF68E1C6, 0xFF1F9E82},   // 网页 松石
            {0xFFFFD166, 0xFFD99A12},   // 数据 金
            {0xFFA8E063, 0xFF5C9E24},   // 脚本 草绿
            {0xFF9AA8BC, 0xFF66748A},   // 其它 中性
    };

    /** 从扩展名判类型 */
    private static int kindOf(String name) {
        int d = name.lastIndexOf('.');
        String ext = d > 0 ? name.substring(d + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "apk": return K_APK;
            case "png": case "jpg": case "jpeg": case "webp": case "gif": case "bmp":
                return K_IMG;
            case "mp4": case "webm": case "mov": case "mkv": case "avi": return K_VID;
            case "mp3": case "wav": case "m4a": case "aac": case "flac": case "ogg": return K_AUD;
            case "zip": case "tar": case "gz": case "tgz": case "7z": case "xz": return K_ZIP;
            case "md": case "txt": case "log": case "pdf": case "doc": case "docx": return K_DOC;
            case "java": case "kt": case "kts": case "py": case "js": case "ts":
            case "c": case "cpp": case "h": case "go": case "rs": case "smali":
                return K_CODE;
            case "html": case "htm": case "css": return K_WEB;
            case "json": case "xml": case "csv": case "yml": case "yaml": case "tsv":
                return K_DATA;
            case "sh": case "bat": case "ps1": return K_SHELL;
            default: return K_ANY;
        }
    }

    /** 任何产物文件：按名字现画 */
    public static Drawable forFile(String pathOrName) {
        if (pathOrName == null) pathOrName = "file";
        String name = pathOrName.substring(pathOrName.lastIndexOf('/') + 1);
        return new Glyph(kindOf(name), name);
    }

    /**
     * APK：先试它自己的图标，是默认机器人就换成现画的。
     * 解析失败（空壳包、截断包）也不抛，直接走现画。
     */
    public static Drawable forApk(Context c, File f) {
        String name = f == null ? "app.apk" : f.getName();
        Drawable own = ownIcon(c, f);
        return own != null ? new BitmapIcon(own) : new Glyph(K_APK, name);
    }

    /** 取 APK 里声明的 launcher 图标；是系统默认机器人就返回 null */
    private static Drawable ownIcon(Context c, File f) {
        if (c == null || f == null || !f.isFile()) return null;
        try {
            PackageManager pm = c.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(
                    f.getAbsolutePath(), PackageManager.GET_ACTIVITIES);
            if (pi == null || pi.applicationInfo == null) return null;
            // 从 APK 文件里读资源必须先把 sourceDir 指过去，否则拿到的是本机已装应用的
            pi.applicationInfo.sourceDir = f.getAbsolutePath();
            pi.applicationInfo.publicSourceDir = f.getAbsolutePath();
            if (pi.applicationInfo.icon == 0) return null;   // 压根没声明图标
            Drawable d = pi.applicationInfo.loadIcon(pm);
            if (d == null) return null;
            if (looksDefault(c, d)) return null;             // 声明了，但就是那个小机器人
            return d;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把图标和系统默认图标各画成 48px 位图比像素差：几乎一致就认定是默认机器人 */
    private static boolean looksDefault(Context c, Drawable d) {
        try {
            Bitmap a = flatten(d, 48);
            Bitmap b = flatten(c.getPackageManager().getDefaultActivityIcon(), 48);
            if (a == null || b == null) return false;
            long diff = 0;
            int n = 0;
            for (int y = 0; y < 48; y += 2) {
                for (int x = 0; x < 48; x += 2) {
                    int pa = a.getPixel(x, y);
                    int pb = b.getPixel(x, y);
                    diff += Math.abs(Color.red(pa) - Color.red(pb))
                            + Math.abs(Color.green(pa) - Color.green(pb))
                            + Math.abs(Color.blue(pa) - Color.blue(pb));
                    n++;
                }
            }
            return n > 0 && diff / (n * 3) < 14;   // 平均每通道差 < 14 就算同一张脸
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Bitmap flatten(Drawable d, int size) {
        if (d == null) return null;
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        d.setBounds(0, 0, size, size);
        d.draw(cv);
        return bm;
    }

    // =====================================================================
    // 现画图标：48×48 坐标系里画圆角渐变方块 + 白色符号
    // =====================================================================
    static final class Glyph extends Drawable {
        private final int kind;
        private final String seed;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        Glyph(int kind, String seed) {
            this.kind = kind;
            this.seed = seed == null ? "" : seed;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3.1f);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 48f;
            if (s <= 0f) return;
            cv.save();
            cv.translate(b.left + (b.width() - 48 * s) / 2f, b.top + (b.height() - 48 * s) / 2f);
            cv.scale(s, s);

            int[] pair = HUE[kind % HUE.length];
            // 同类型内部再区分：按文件名哈希把色相左右挪 ±11°，色差看得出又不换色系
            int c0 = shiftHue(pair[0], seed);
            int c1 = shiftHue(pair[1], seed);

            r.set(1.5f, 1.5f, 46.5f, 46.5f);
            p.setShader(new LinearGradient(1.5f, 1.5f, 46.5f, 46.5f, c0, c1, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(r, 12.5f, 12.5f, p);
            p.setShader(null);

            // 左上角一层柔光，纯平涂会像色块而不是图标
            p.setColor(0x26FFFFFF);
            cv.drawCircle(15f, 13f, 15f, p);

            drawMark(cv);
            cv.restore();
        }

        /** 哈希取模映射到 [-11, +11]，只挪色相不碰明度饱和度 */
        private static int shiftHue(int color, String seed) {
            int h = 0;
            for (int i = 0; i < seed.length(); i++) h = h * 31 + seed.charAt(i);
            float delta = ((Math.abs(h) % 23) - 11);
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            hsv[0] = (hsv[0] + delta + 360f) % 360f;
            return Color.HSVToColor(Color.alpha(color), hsv);
        }

        private void drawMark(Canvas cv) {
            switch (kind) {
                case K_APK: {   // 2×2 应用格子，右下那格换成箭头，暗示「装上去」
                    float u = 8.4f, g = 3.4f, x0 = 13.4f, y0 = 13.4f;
                    p.setColor(0xF2FFFFFF);
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            if (i == 1 && j == 1) continue;
                            r.set(x0 + i * (u + g), y0 + j * (u + g),
                                    x0 + i * (u + g) + u, y0 + j * (u + g) + u);
                            cv.drawRoundRect(r, 2.4f, 2.4f, p);
                        }
                    }
                    path.reset();
                    path.moveTo(31.4f, 30.4f);
                    path.lineTo(36.4f, 33.9f);
                    path.lineTo(31.4f, 37.4f);
                    path.close();
                    p.setColor(0xE6FFFFFF);
                    cv.drawPath(path, p);
                    break;
                }
                case K_IMG: {   // 太阳 + 山
                    p.setColor(0xF2FFFFFF);
                    cv.drawCircle(17.5f, 17.5f, 3.4f, p);
                    path.reset();
                    path.moveTo(12f, 35f);
                    path.lineTo(21f, 24.5f);
                    path.lineTo(27.5f, 31.5f);
                    path.lineTo(31.5f, 27.5f);
                    path.lineTo(36f, 35f);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case K_VID: {   // 播放三角
                    p.setColor(0xF2FFFFFF);
                    path.reset();
                    path.moveTo(18.5f, 14.5f);
                    path.lineTo(35f, 24f);
                    path.lineTo(18.5f, 33.5f);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case K_AUD: {   // 音符：两个头 + 两条竖杆 + 顶横
                    p.setColor(0xF2FFFFFF);
                    cv.drawCircle(17.5f, 32.5f, 4.2f, p);
                    cv.drawCircle(29.5f, 30f, 4.2f, p);
                    r.set(19.6f, 14f, 22.4f, 33f);
                    cv.drawRoundRect(r, 1.4f, 1.4f, p);
                    r.set(31.6f, 11.5f, 34.4f, 30.5f);
                    cv.drawRoundRect(r, 1.4f, 1.4f, p);
                    r.set(19.6f, 11.5f, 34.4f, 16.5f);
                    cv.drawRoundRect(r, 1.6f, 1.6f, p);
                    break;
                }
                case K_ZIP: {   // 中间一条拉链，左右各三齿
                    p.setColor(0xE0FFFFFF);
                    r.set(22.6f, 11f, 25.4f, 37f);
                    cv.drawRoundRect(r, 1.2f, 1.2f, p);
                    p.setColor(0xF5FFFFFF);
                    for (int i = 0; i < 3; i++) {
                        r.set(17.2f, 15f + i * 7.4f, 22.2f, 19.4f + i * 7.4f);
                        cv.drawRoundRect(r, 1.2f, 1.2f, p);
                        r.set(25.8f, 15f + i * 7.4f, 30.8f, 19.4f + i * 7.4f);
                        cv.drawRoundRect(r, 1.2f, 1.2f, p);
                    }
                    break;
                }
                case K_DOC: {   // 折角纸 + 三条文字线
                    p.setColor(0xF2FFFFFF);
                    path.reset();
                    path.moveTo(14f, 11.5f);
                    path.lineTo(27.5f, 11.5f);
                    path.lineTo(34f, 18f);
                    path.lineTo(34f, 36.5f);
                    path.lineTo(14f, 36.5f);
                    path.close();
                    cv.drawPath(path, p);
                    p.setColor(0x55203040);
                    path.reset();
                    path.moveTo(27.5f, 11.5f);
                    path.lineTo(27.5f, 18f);
                    path.lineTo(34f, 18f);
                    path.close();
                    cv.drawPath(path, p);
                    p.setColor(0x99203040);
                    for (int i = 0; i < 3; i++) {
                        r.set(18f, 22.5f + i * 4.4f, i == 2 ? 26f : 30f, 24.2f + i * 4.4f);
                        cv.drawRoundRect(r, 0.9f, 0.9f, p);
                    }
                    break;
                }
                case K_CODE: {   // 尖括号 + 斜杠
                    cv.drawLine(19.5f, 17f, 13.5f, 24f, stroke);
                    cv.drawLine(13.5f, 24f, 19.5f, 31f, stroke);
                    cv.drawLine(28.5f, 17f, 34.5f, 24f, stroke);
                    cv.drawLine(34.5f, 24f, 28.5f, 31f, stroke);
                    cv.drawLine(26f, 14.5f, 22f, 33.5f, stroke);
                    break;
                }
                case K_WEB: {   // 地球：圆 + 赤道 + 经线
                    r.set(12.5f, 12.5f, 35.5f, 35.5f);
                    cv.drawOval(r, stroke);
                    cv.drawLine(13f, 24f, 35f, 24f, stroke);
                    r.set(19f, 12.5f, 29f, 35.5f);
                    cv.drawOval(r, stroke);
                    break;
                }
                case K_DATA: {   // 表格网格
                    stroke.setStrokeWidth(2.8f);
                    cv.drawRoundRect(12.5f, 14f, 35.5f, 34f, 3.2f, 3.2f, stroke);
                    cv.drawLine(12.5f, 21f, 35.5f, 21f, stroke);
                    cv.drawLine(12.5f, 27.5f, 35.5f, 27.5f, stroke);
                    cv.drawLine(20f, 14f, 20f, 34f, stroke);
                    break;
                }
                case K_SHELL: {  // >_ 终端提示符
                    stroke.setStrokeWidth(3.7f);   // 纯线条符号本来就偏轻，加粗一档才压得住底色
                    cv.drawLine(14.5f, 16.5f, 22f, 24f, stroke);
                    cv.drawLine(22f, 24f, 14.5f, 31.5f, stroke);
                    cv.drawLine(24.5f, 31.8f, 34.5f, 31.8f, stroke);
                    break;
                }
                default: {       // 空白纸条
                    p.setColor(0xF0FFFFFF);
                    path.reset();
                    path.moveTo(14.5f, 11.5f);
                    path.lineTo(27.5f, 11.5f);
                    path.lineTo(34f, 18f);
                    path.lineTo(34f, 36.5f);
                    path.lineTo(14.5f, 36.5f);
                    path.close();
                    cv.drawPath(path, p);
                    p.setColor(0x55203040);
                    path.reset();
                    path.moveTo(27.5f, 11.5f);
                    path.lineTo(27.5f, 18f);
                    path.lineTo(34f, 18f);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
            stroke.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
            stroke.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // =====================================================================
    // APK 自带图标：包一层圆角，跟自绘图标视觉一致
    // =====================================================================
    static final class BitmapIcon extends Drawable {
        private final Bitmap bm;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Rect src = new Rect();

        BitmapIcon(Drawable d) {
            bm = flatten(d, 96);
            p.setFilterBitmap(true);
            bg.setColor(0x00000000);
        }

        @Override
        public void draw(Canvas cv) {
            if (bm == null) return;
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float l = b.left + (b.width() - s) / 2f;
            float t = b.top + (b.height() - s) / 2f;
            r.set(l, t, l + s, t + s);
            // 圆角裁剪：先铺一块全透明再贴着圆角路径画位图
            cv.save();
            Path clip = new Path();
            clip.addRoundRect(r, s * 0.25f, s * 0.25f, Path.Direction.CW);
            cv.clipPath(clip);
            src.set(0, 0, bm.getWidth(), bm.getHeight());
            cv.drawBitmap(bm, src, r, p);
            cv.restore();
            // 极淡的内描边，浅色图标压在浅色气泡上不会糊边
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1f);
            edge.setColor(0x22FFFFFF);
            cv.drawRoundRect(r, s * 0.25f, s * 0.25f, edge);
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
