# -*- coding: utf-8 -*-
"""落地「六角棱镜 + >_」霓虹材质到 Android 各密度资源。

自适应前景的 alpha 设计（关键）
------------------------------
* Android 实际裁切边界是 72dp（半径 0.333），安全区 66dp（0.305）只是保守区。
* 主体最大半径 0.298，完整落在安全区内。
* 外发光在 0.300→0.333 之间用 fade 归零，这样 72dp 裁切处 alpha≈0，
  不会出现被切出硬边的光圈。
"""
import sys
import numpy as np
from PIL import Image

sys.path.insert(0, "tools")
import icon_fusion as inf

RES = "app/src/main/res"
MAT = "metal"
S = 1024
DENSITIES = [("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
             ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)]

CUT_START, CUT_END = 0.300, 0.333      # 光晕 fade 区间（相对半径）


def radial(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / (size - 1)
    return np.hypot(xx - 0.5, yy - 0.5) / 0.7071 * 0.7071  # 归一化到 0..~0.707


def make_foreground(src):
    ring = inf._mask(inf.d_hex_ring, S)
    sym = inf._mask(inf.d_term_fit, S)
    fill = inf._mask(inf.d_hex_fill, S)
    core = np.maximum(ring, sym)
    glow = inf._blur(core, S, 0.050)

    arr = np.asarray(src, np.float32)
    rgb = arr[:, :, :3]

    a = np.maximum(np.maximum(fill * 0.94, core), glow * 0.66)
    r = radial(S)
    t = np.clip((CUT_END - r) / (CUT_END - CUT_START), 0, 1)
    t = t * t * (3 - 2 * t)                 # smoothstep
    a = np.clip(a * t, 0, 1)

    return Image.fromarray(np.dstack([rgb, a * 255.0]).astype(np.uint8), "RGBA")


def main():
    src = inf.build_fusion(MAT, S)
    fg = make_foreground(src)
    src.save("/sdcard/Download/final-full-1024.png")
    fg.save("/sdcard/Download/final-foreground-1024.png")

    # 背景层采样图标最外圈颜色
    arr = np.asarray(src, np.float32)[:, :, :3]
    corner = arr[20:80, 20:80].reshape(-1, 3).mean(axis=0)
    print("背景角采样 RGB", corner.round().astype(int).tolist())

    for d, icon_px, fg_px in DENSITIES:
        src.resize((icon_px, icon_px), Image.LANCZOS).save(
            "%s/mipmap-%s/ic_launcher.png" % (RES, d))
        fg.resize((fg_px, fg_px), Image.LANCZOS).save(
            "%s/mipmap-%s/ic_launcher_foreground.png" % (RES, d))
        print("mipmap-%-8s icon=%3d fg=%3d" % (d, icon_px, fg_px))

    # 自检：前景在 72dp 裁切边界内侧的 alpha 应足够低
    a = np.asarray(fg, np.float32)[:, :, 3] / 255.0
    r = radial(S)
    for lo, hi in ((0.30, 0.31), (0.31, 0.32), (0.32, 0.333)):
        band = a[(r >= lo) & (r < hi)]
        print("alpha 半径 %.2f-%.3f 区间 均值 %.3f 最大 %.3f"
              % (lo, hi, band.mean(), band.max()))
    print("主体(<=0.298) alpha 均值 %.2f" % a[r <= 0.298].mean())


if __name__ == "__main__":
    main()
