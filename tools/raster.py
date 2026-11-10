#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把松鼠几何栅格化：出对比图 + 字符画自检。

字符画是给"看不到图的 AI"用的：宽高比 2:1 的等宽字符，正好对上 24x16 的 viewBox，
一眼能看出轮廓是不是糊成一团、尾巴有没有镂空、四帧是不是真在迈腿。
"""
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = "/sdcard/codeforge/fonts/DroidSans-Bold.ttf"
LABELS = {"old": "A  old", "slim": "B  slim", "cute": "C  cute"}

sys.path.insert(0, "/root/projects/nebula-ai/tools")
import gen_squirrel as G  # noqa: E402

SS = 8          # 超采样倍数，先画大再缩，边缘才不会一齿一齿的
CELL = 120      # 每帧的显示宽度（像素）


def bezier(p0, p1, p2, p3, n=24):
    t = np.linspace(0, 1, n)[:, None]
    return ((1 - t) ** 3) * np.array(p0) + 3 * ((1 - t) ** 2) * t * np.array(p1) \
        + 3 * (1 - t) * t * t * np.array(p2) + (t ** 3) * np.array(p3)


def scale(pts, k, pad):
    return [((x) * k + pad, (y) * k + pad) for x, y in pts]


def draw_frame(dr, g, k, pad, color):
    art = []
    for kind, v in g["shapes"]:
        if kind == "ellipse":
            cx, cy, rx, ry = v
            cx, cy, rx, ry = cx * k + pad, cy * k + pad, rx * k, ry * k
            dr.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=color)
        elif kind == "poly":
            dr.polygon(scale(v, k, pad), fill=color)
        elif kind == "tail":
            # 沿贝塞尔采样成多边形再填，和 pathData 走的是同一批控制点
            first = v[0]
            pts = [first]
            for seg in v[1:]:
                c1, c2, p = seg
                pts_b = bezier(pts[-1], c1, c2, p, 26)
                pts.extend([tuple(q) for q in pts_b[1:]])
            dr.polygon(scale(pts, k, pad), fill=color)
    # 腿：先远后近，远的压暗
    for far in (True, False):
        for (x1, y1, x2, y2, wd, is_far) in g["legs"]:
            if is_far != far:
                continue
            c = (color[0], color[1], color[2], int(255 * 0.55)) if far else color
            dr.line([x1 * k + pad, y1 * k + pad, x2 * k + pad, y2 * k + pad],
                    fill=c, width=max(1, int(wd * k)))
            r = wd * k / 2.0
            for (px, py) in ((x1, y1), (x2, y2)):
                dr.ellipse([px * k + pad - r, py * k + pad - r,
                            px * k + pad + r, py * k + pad + r], fill=c)


def render(profile, name, bg, fg, label_dy=0):
    k = CELL / G.W * SS
    pad = 6 * SS
    fw = int(G.W * k + pad * 2)
    fh = int(G.H * k + pad * 2)
    frames = len(profile["poses"])
    img = Image.new("RGB", (fw * frames, fh), bg)
    for i in range(frames):
        layer = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
        dr = ImageDraw.Draw(layer)
        draw_frame(dr, G.geom(profile, i), k, pad, fg)
        img.paste(Image.alpha_composite(
            Image.new("RGBA", (fw, fh), bg + (255,)), layer).convert("RGB"), (i * fw, 0))
    return img


def ascii_frame(profile, i):
    """48x16 字符点阵：横向 0.5 单位一格，纵向 1 单位一行"""
    cols, rows = 48, 16
    grid = [["." for _ in range(cols)] for _ in range(rows)]
    g = G.geom(profile, i)
    sub = 3  # 每个字符格内部再细分采样，避免细腿漏掉
    for kind, v in g["shapes"]:
        if kind == "ellipse":
            cx, cy, rx, ry = v
            for r in range(rows):
                for c in range(cols):
                    for sy in range(sub):
                        for sx in range(sub):
                            x = (c + (sx + 0.5) / sub) * 0.5
                            y = r + (sy + 0.5) / sub
                            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1:
                                grid[r][c] = "#"
        elif kind == "poly":
            ax, ay = v[0]
            bx, by = v[1]
            cx_, cy_ = v[2]
            for r in range(rows):
                for c in range(cols):
                    x = (c + 0.5) * 0.5
                    y = r + 0.5
                    d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
                    d2 = (x - cx_) * (by - cy_) - (bx - cx_) * (y - cy_)
                    d3 = (x - ax) * (cy_ - ay) - (cx_ - ax) * (y - ay)
                    neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
                    pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
                    if not (neg and pos):
                        grid[r][c] = "#"
        elif kind == "tail":
            pts = [v[0]]
            for seg in v[1:]:
                c1, c2, p = seg
                pts.extend([tuple(q) for q in bezier(pts[-1], c1, c2, p, 40)[1:]])
            poly = np.array(pts)
            for r in range(rows):
                for c in range(cols):
                    for sy in range(sub):
                        for sx in range(sub):
                            x = (c + (sx + 0.5) / sub) * 0.5
                            y = r + (sy + 0.5) / sub
                            if _inside(poly, x, y):
                                grid[r][c] = "#"
    for (x1, y1, x2, y2, wd, far) in g["legs"]:
        ch = "+" if far else "*"
        steps = 40
        r = wd / 2.0
        for s in range(steps + 1):
            t = s / steps
            px, py = x1 + (x2 - x1) * t, y1 + (y2 - y1) * t
            for r2 in range(rows):
                for c in range(cols):
                    cx = (c + 0.5) * 0.5
                    if abs(cx - px) <= r and abs(r2 + 0.5 - py) <= r:
                        if grid[r2][c] in ".+":
                            grid[r2][c] = ch
    return "\n".join("".join(row) for row in grid)


def _inside(poly, x, y):
    n = len(poly)
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi):
            inside = not inside
        j = i
    return inside


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/sdcard/Download/squirrel_compare.png"
    dark = (26, 28, 32)
    light = (238, 240, 243)
    fg_d = (224, 232, 238, 255)
    fg_l = (32, 36, 42, 255)

    rows = []
    try:
        font = ImageFont.truetype(FONT, 54)
    except Exception:
        font = ImageFont.load_default()
    for name in ("old", "slim", "cute"):
        prof = G.PROFILES[name]
        for bg, fg in ((dark, fg_d), (light, fg_l)):
            img = render(prof, name, bg, fg)
            dr = ImageDraw.Draw(img)
            # 标签反色写在左上角，方便按行认方案
            lab_fg = (150, 200, 240) if bg == dark else (30, 90, 150)
            dr.text((18, 10), LABELS[name], font=font, fill=lab_fg)
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

    for name in ("slim", "cute"):
        print("\n===== %s =====" % name)
        for i in range(4):
            print("--- frame %d ---" % i)
            print(ascii_frame(G.PROFILES[name], i))
