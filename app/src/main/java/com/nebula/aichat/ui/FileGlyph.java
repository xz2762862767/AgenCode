package com.nebula.aichat.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/**
 * 文件类型图标：24×24 网格上手绘的描边矢量。
 *
 * 为什么不用 emoji：深色底上系统 emoji 是彩色的，一列排下来花花绿绿，
 * 各 ROM 字形差异也大，20dp 内经常糊成一团；
 * 这里统一描边风格 + 类型色，看起来像 IDE 的文件树。
 */
public class FileGlyph extends Drawable {

    /* 类型常量 */
    public static final int DIR = 0;      // 文件夹
    public static final int CODE = 1;     // 源码 / 配置
    public static final int TEXT = 2;     // 文本 / 日志 / Markdown
    public static final int IMAGE = 3;    // 图片
    public static final int MEDIA = 4;    // 音视频
    public static final int ARCHIVE = 5;  // 压缩包
    public static final int APK = 6;      // 安装包
    public static final int DOC = 7;      // 文档表格
    public static final int BIN = 8;      // 其它二进制

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public FileGlyph(int kind, int color) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        if (size <= 0) return;
        float s = size / 24f;
        int save = c.save();
        c.translate(b.exactCenterX() - size / 2f, b.exactCenterY() - size / 2f);
        c.scale(s, s);
        if (kind == DIR) {
            drawFolder(c);
        } else {
            drawSheet(c);
            drawInner(c);
        }
        c.restoreToCount(save);
    }

    /** 文件夹：带盖沿的轮廓，转角走 quadTo 保持圆润 */
    private void drawFolder(Canvas c) {
        path.reset();
        path.moveTo(3.0f, 9.4f);
        path.lineTo(3.0f, 18.2f);
        path.quadTo(3.0f, 20.0f, 4.8f, 20.0f);
        path.lineTo(19.2f, 20.0f);
        path.quadTo(21.0f, 20.0f, 21.0f, 18.2f);
        path.lineTo(21.0f, 11.4f);
        path.quadTo(21.0f, 9.6f, 19.2f, 9.6f);
        path.lineTo(11.4f, 9.6f);
        path.lineTo(9.2f, 7.0f);
        path.lineTo(4.8f, 7.0f);
        path.quadTo(3.0f, 7.0f, 3.0f, 8.8f);
        path.close();
        c.drawPath(path, paint);
    }

    /** 纸张：右上折角 */
    private void drawSheet(Canvas c) {
        path.reset();
        path.moveTo(6.0f, 4.4f);
        path.lineTo(13.2f, 4.4f);
        path.lineTo(18.2f, 9.4f);
        path.lineTo(18.2f, 19.2f);
        path.quadTo(18.2f, 20.8f, 16.6f, 20.8f);
        path.lineTo(7.6f, 20.8f);
        path.quadTo(6.0f, 20.8f, 6.0f, 19.2f);
        path.close();
        c.drawPath(path, paint);

        path.reset();
        path.moveTo(13.2f, 4.4f);
        path.lineTo(13.2f, 9.4f);
        path.lineTo(18.2f, 9.4f);
        c.drawPath(path, paint);
    }

    /** 纸张内部的类型符号：尺寸小，只留最必要的笔画 */
    private void drawInner(Canvas c) {
        switch (kind) {
            case CODE:
                path.reset();
                path.moveTo(11.4f, 12.6f);
                path.lineTo(9.2f, 15.2f);
                path.lineTo(11.4f, 17.8f);
                c.drawPath(path, paint);
                path.reset();
                path.moveTo(13.4f, 12.6f);
                path.lineTo(15.6f, 15.2f);
                path.lineTo(13.4f, 17.8f);
                c.drawPath(path, paint);
                break;
            case TEXT:
                line(c, 8.4f, 12.6f, 15.8f, 12.6f);
                line(c, 8.4f, 15.2f, 15.8f, 15.2f);
                line(c, 8.4f, 17.8f, 12.6f, 17.8f);
                break;
            case DOC:
                line(c, 8.4f, 12.8f, 15.8f, 12.8f);
                line(c, 8.4f, 15.4f, 15.8f, 15.4f);
                line(c, 8.4f, 18.0f, 11.8f, 18.0f);
                break;
            case IMAGE:
                path.reset();
                path.moveTo(8.0f, 18.0f);
                path.lineTo(11.0f, 13.6f);
                path.lineTo(13.0f, 16.4f);
                path.lineTo(14.4f, 14.8f);
                path.lineTo(16.2f, 18.0f);
                c.drawPath(path, paint);
                c.drawCircle(10.6f, 11.8f, 1.2f, fill);
                break;
            case MEDIA:
                path.reset();
                path.moveTo(10.4f, 12.2f);
                path.lineTo(15.4f, 15.2f);
                path.lineTo(10.4f, 18.2f);
                path.close();
                c.drawPath(path, fill);
                break;
            case ARCHIVE:
                line(c, 12.0f, 10.8f, 12.0f, 18.4f);
                line(c, 10.2f, 12.6f, 13.8f, 12.6f);
                line(c, 10.2f, 15.0f, 13.8f, 15.0f);
                line(c, 10.2f, 17.4f, 13.8f, 17.4f);
                break;
            case APK:
                // 小机器人：半圆头 + 触角 + 眼睛
                path.reset();
                path.moveTo(9.4f, 17.6f);
                path.quadTo(9.4f, 12.6f, 12.0f, 12.6f);
                path.quadTo(14.6f, 12.6f, 14.6f, 17.6f);
                path.close();
                c.drawPath(path, paint);
                line(c, 9.4f, 12.9f, 8.6f, 11.6f);
                line(c, 14.6f, 12.9f, 15.4f, 11.6f);
                c.drawCircle(10.9f, 15.0f, 0.65f, fill);
                c.drawCircle(13.1f, 15.0f, 0.65f, fill);
                break;
            default:
                // 二进制 / 未知：不画符号，留白更干净
                break;
        }
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        c.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // ------------------------------------------------------------------ 类型判定

    private static final String[] CODE_EXT = {
            "java", "kt", "kts", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs", "rb", "php", "swift",
            "dart", "lua", "pl", "sh", "bash", "zsh", "fish",
            "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg",
            "properties", "gradle", "mk", "css", "scss", "less", "html", "htm",
            "vue", "sql", "smali", "dex"
    };
    private static final String[] TEXT_EXT = {
            "md", "markdown", "txt", "log", "text", "rst", "csv"
    };
    private static final String[] IMAGE_EXT = {
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico",
            "heic", "heif", "tif", "tiff"
    };
    private static final String[] MEDIA_EXT = {
            "mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "amr",
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "flv", "m4v"
    };
    private static final String[] ARCHIVE_EXT = {
            "zip", "jar", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz",
            "zst", "deb", "rpm"
    };
    private static final String[] APK_EXT = {"apk", "aab", "apks", "xapk", "apkm"};
    private static final String[] DOC_EXT = {
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "rtf"
    };

    /** 按扩展名判类型；目录优先，认不出的算 BIN */
    public static int kindOf(String name, boolean isDir) {
        if (isDir) return DIR;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) return BIN;
        String ext = n.substring(dot + 1);
        if (has(CODE_EXT, ext)) return CODE;
        if (has(TEXT_EXT, ext)) return TEXT;
        if (has(IMAGE_EXT, ext)) return IMAGE;
        if (has(MEDIA_EXT, ext)) return MEDIA;
        if (has(ARCHIVE_EXT, ext)) return ARCHIVE;
        if (has(APK_EXT, ext)) return APK;
        if (has(DOC_EXT, ext)) return DOC;
        return BIN;
    }

    private static boolean has(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return true;
        }
        return false;
    }
}
