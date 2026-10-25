package com.nebula.aichat.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 聊天背景图：导入、压暗、铺设、清除 */
public final class ChatBackground {

    private static final int MAX_EDGE = 1600;

    private ChatBackground() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "chat_bg");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 从相册导入：等比缩到最长边 1600 并存成 jpg，返回文件名 */
    public static String importFrom(Context c, Uri uri) throws Exception {
        Bitmap src = decode(c, uri);
        if (src == null) throw new Exception("图片读取失败");
        String name = "bg_" + System.currentTimeMillis() + ".jpg";
        OutputStream os = new FileOutputStream(new File(dir(c), name));
        src.compress(Bitmap.CompressFormat.JPEG, 90, os);
        os.flush();
        os.close();
        src.recycle();
        return name;
    }

    private static Bitmap decode(Context c, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = c.getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(in, null, bounds);
        if (in != null) in.close();

        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

        int sample = 1;
        while (Math.max(w, h) / sample > MAX_EDGE) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        in = c.getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
        if (in != null) in.close();
        return bmp;
    }

    public static boolean exists(Context c, String name) {
        return name != null && !name.trim().isEmpty() && new File(dir(c), name.trim()).isFile();
    }

    /** 生成可直接当背景用的 Drawable（含压暗与不透明度），无图返回 null */
    public static Drawable build(Context c, String name, int alphaPct, int dimPct) {
        if (!exists(c, name)) return null;
        File file = new File(dir(c), name.trim());
        Bitmap bmp;
        try {
            // 先量尺寸再按需采样，避免大图直接解码 OOM
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int maxEdge = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxEdge / sample > 2000) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
        if (bmp == null) return null;

        int dim = Math.max(0, Math.min(90, dimPct));
        if (dim > 0) {
            Bitmap dimmed = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(dimmed);
            cv.drawBitmap(bmp, 0, 0, null);
            Paint p = new Paint();
            p.setColor(Color.BLACK);
            p.setAlpha(dim * 255 / 100);
            cv.drawRect(0, 0, bmp.getWidth(), bmp.getHeight(), p);
            bmp.recycle();
            bmp = dimmed;
        }

        BitmapDrawable d = new BitmapDrawable(c.getResources(), bmp);
        d.setGravity(Gravity.FILL);
        d.setAlpha(Math.max(5, Math.min(100, alphaPct)) * 255 / 100);
        return d;
    }

    public static void clear(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) f.delete();
    }
}
