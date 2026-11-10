#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""兔子几何预览：出对比图（给用户看）+ 字符画（给看不到图的 AI 看）。

复用 raster.py 的渲染管线，只把 geom 换成兔子的。字符画重写了一份，因为
raster 那份只会画三角形 ear，兔子耳朵是四边形。

用法：/usr/bin/python3.9 tools/preview_rabbit.py [输出png]
"""
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

TOOLS = "/root/projects/nebula-ai/tools"
sys.path.insert(0, TOOLS)

import gen_squirrel as G          # noqa: E402
import gen_rabbit as R            # noqa: E402

G.geom = R.geom                   # raster 内部一律通过 G.geom 取几何
import raster                      # noqa: E402

FONT = raster.FONT
DARK = (26, 28, 32)
LIGHT = (238, 240, 243)
FG_D = (224, 232, 238, 255)
FG_L = (32, 36, 42, 255)


def ascii_frame(profile, i, cols=48, rows=16):
    """48x16 字符点阵：横向半格、纵向一格，正好对上 24x16 的 viewBox。
    支持任意顶点数的多边形（耳朵是四边形）。"""
    grid = [["." for _ in range(cols)] for _ in range(rows)]
    g = R.geom(profile, i)
    cw, chh = R.W / cols, R.H / rows
    sub = 3

    def mark(px, py):
        c = int(px / cw)
        r = int(py / chh)
        if 0 <= c < cols and 0 <= r < rows:
            return (c, r)
        return None

    for kind, v in g["shapes"]:
        if kind == "ellipse":
            cx, cy, rx, ry = v
            for r in range(rows):
                for c in range(cols):
                    hit = False
                    for sy in range(sub):
                        for sx in range(sub):
                            x = (c + (sx + 0.5) / sub) * cw
                            y = (r + (sy + 0.5) / sub) * chh
                            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1:
                                hit = True
                                break
                        if hit:
                            break
                    if hit:
                        grid[r][c] = "#"
        elif kind == "poly":
            poly = np.array(v, dtype=float)
            for r in range(rows):
                for c in range(cols):
                    hit = False
                    for sy in range(sub):
                        for sx in range(sub):
                            x = (c + (sx + 0.5) / sub) * cw
                            y = (r + (sy + 0.5) / sub) * chh
                            if raster._inside(poly, x, y):
                                hit = True
                                break
                        if hit:
                            break
                    if hit and grid[r][c] == ".":
                        grid[r][c] = "E"      # 耳朵单独用 E 标出来，方便看倾角

    # 腿最后画，盖住身体；远侧腿用 +，近侧腿用 *
    for (x1, y1, x2, y2, wd, far) in g["legs"]:
        ch = "+" if far else "*"
        rad = wd / 2.0
        for s in range(41):
            t = s / 40.0
            px, py = x1 + (x2 - x1) * t, y1 + (y2 - y1) * t
            for r in range(rows):
                for c in range(cols):
                    cx = (c + 0.5) * cw
                    if abs(cx - px) <= rad and abs((r + 0.5) * chh - py) <= rad:
                        if grid[r][c] in ".+":
                            grid[r][c] = ch
    return "\n".join("".join(row) for row in grid)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/sdcard/Download/rabbit_compare.png"
    try:
        font = ImageFont.truetype(FONT, 54)
    except Exception:
        font = ImageFont.load_default()

    rows = []
    for name in ("lean", "stout", "dash"):
        prof = R.PROFILES[name]
        for bg, fg in ((DARK, FG_D), (LIGHT, FG_L)):
            img = raster.render(prof, name, bg, fg)
            dr = ImageDraw.Draw(img)
            lab_fg = (150, 200, 240) if bg == DARK else (30, 90, 150)
            dr.text((18, 10), R.LABELS[name], font=font, fill=lab_fg)
            rows.append(img)

    w = max(r.width for r in rows)
    h = sum(r.height for r in rows) + 8 * (len(rows) - 1)
    canvas = Image.new("RGB", (w, h), (18, 20, 24))
    y = 0
    for r in rows:
        canvas.paste(r, (0, y))
        y += r.height + 8
    canvas.save(out)
    print("saved", out, canvas.size)

    for name in ("lean", "stout", "dash"):
        print("\n===== %s (%s) =====" % (name, R.LABELS[name]))
        for i in range(len(R.PROFILES[name]["poses"])):
            print("--- frame %d ---" % i)
            print(ascii_frame(R.PROFILES[name], i))


if __name__ == "__main__":
    main()
