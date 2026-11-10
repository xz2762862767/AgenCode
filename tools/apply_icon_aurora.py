# -*- coding: utf-8 -*-
"""落地「极光 · 青绿流光」图标到 Android 各密度资源。

自适应图标注意点
----------------
* 前景画布 108dp，安全区仅中央 66dp，所以内容必须缩到约 0.66 再居中，
  否则圆形/水滴遮罩会削到光晕边缘。
* 前景不能带深色底（会是硬边方块），这里用**亮度转 alpha**：
  光越亮越不透明，深空底全透明，叠在背景渐变上自然融合。
"""
import sys
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, "tools")
import icon_glow as ig

RES = "app/src/main/res"
PLAN = [p for p in ig.PLANS if p["name"].startswith("极光")][0]

DENSITIES = [("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
             ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)]

S = 1024
FG_SCALE = 0.66          # 前景内容占画布比例，落在 66dp 安全区内
ALPHA_LO, ALPHA_HI = 0.020, 0.34   # 亮度 -> alpha 的过渡区间


def make_foreground(src):
    """把完整图标转成透明底前景层（亮度作 alpha）。"""
    arr = np.asarray(src, np.float32)
    rgb, a0 = arr[:, :, :3], arr[:, :, 3] / 255.0
    lum = rgb.sum(axis=2) / 3.0 / 255.0
    t = np.clip((lum - ALPHA_LO) / (ALPHA_HI - ALPHA_LO), 0, 1)
    a = t * t * (3 - 2 * t)                      # smoothstep，过渡无硬边
    a = np.maximum(a, np.clip((lum - 0.55) / 0.35, 0, 1))   # 亮部（星芒/星尘）全不透明
    a = a * a0
    fg = Image.fromarray(np.dstack([rgb, a * 255.0]).astype(np.uint8), "RGBA")
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    n = int(S * FG_SCALE)
    canvas.alpha_composite(fg.resize((n, n), Image.LANCZOS), ((S - n) // 2, (S - n) // 2))
    return canvas


def main():
    src = ig.build(PLAN, S)
    fg = make_foreground(src)
    fg.save("/sdcard/Download/check-foreground-1024.png")
    src.save("/sdcard/Download/check-full-1024.png")

    # 背景：取图标边角的深空色，保证前景叠上去无色差
    arr = np.asarray(src, np.float32)[:, :, :3]
    corner = arr[60:140, 60:140].reshape(-1, 3).mean(axis=0).round().astype(int)
    print("背景角采样 RGB", corner.tolist())

    for d, icon_px, fg_px in DENSITIES:
        src.resize((icon_px, icon_px), Image.LANCZOS).save(
            "%s/mipmap-%s/ic_launcher.png" % (RES, d))
        fg.resize((fg_px, fg_px), Image.LANCZOS).save(
            "%s/mipmap-%s/ic_launcher_foreground.png" % (RES, d))
        print("mipmap-%-8s icon=%d fg=%d" % (d, icon_px, fg_px))

    # 圆形遮罩下检查前景是否越界（模拟启动器裁切）
    for sz in (192, 96, 48):
        c = fg.resize((sz, sz), Image.LANCZOS)
        m = Image.new("L", (sz, sz), 0)
        ImageDraw.Draw(m).ellipse((0, 0, sz - 1, sz - 1), fill=255)
        bgd = Image.new("RGBA", (sz, sz), (6, 12, 13, 255))
        bgd.paste(c, (0, 0), m)
        bgd.convert("RGB").save("/sdcard/Download/check-circle-%d.png" % sz)
    print("前景/圆形检查图已存 /sdcard/Download/check-*.png")
    return corner


if __name__ == "__main__":
    main()
