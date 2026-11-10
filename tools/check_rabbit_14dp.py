#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""14dp 真实尺寸下的可辨识度自检。

为什么需要它：14dp @3x 只有 42px 高，任何"看着还行"的细节在这个尺度上可能直接
消失成一个色块。眼睛、耳朵分开、尾巴对比度都必须在这里量化确认，而不是凭大图
印象拍板。

检查项：
  ears     上部区域的连通块数 —— 2 才说明两只耳朵在真实像素下是分开的
  eye      眼睛像素数 + 与主体的色差（色差小于 40 基本看不出）
  tail     尾巴像素数 + 与主体的色差
  far_leg  远侧腿像素数（有没有把前后腿分开）
  cover    主体占画面比例（太小说明 14dp 里兔子是"虚"的）
"""
import sys

sys.path.insert(0, "/root/projects/nebula-ai/tools")

from PIL import Image, ImageDraw

import gen_rabbit_hi as G

REAL_H = 42          # 14dp @ 3x
k = REAL_H / float(G.H)
IMG_W = int(round(G.W * k)) + 8
IMG_H = REAL_H + 8
OX = OY = 4


def mask_of(layers, role):
    m = Image.new("L", (IMG_W, IMG_H), 0)
    d = ImageDraw.Draw(m)
    for poly in layers[role]:
        d.polygon([(x * k + OX, y * k + OY) for (x, y) in poly], fill=255)
    return m


def pixels(mask, thresh=128):
    px = mask.load()
    out = []
    for y in range(IMG_H):
        for x in range(IMG_W):
            if px[x, y] >= thresh:
                out.append((x, y))
    return out


def components(mask, thresh=128):
    """8 连通标号，42x63 的规模直接暴力扫就行。

    用 8 邻接而不是 4 邻接：14dp 下耳朵根部与头常常只差一个对角像素，人眼看是连着的，
    4 邻接却判成两块，属于检查器过严。
    """
    px = mask.load()
    seen = [[False] * IMG_W for _ in range(IMG_H)]
    comps = []
    for y in range(IMG_H):
        for x in range(IMG_W):
            if px[x, y] < thresh or seen[y][x]:
                continue
            stack = [(x, y)]
            seen[y][x] = True
            comp = []
            while stack:
                cx, cy = stack.pop()
                comp.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1),
                               (1, 1), (1, -1), (-1, 1), (-1, -1)):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < IMG_W and 0 <= ny < IMG_H and px[nx, ny] >= thresh \
                            and not seen[ny][nx]:
                        seen[ny][nx] = True
                        stack.append((nx, ny))
            comps.append(comp)
    comps.sort(key=len, reverse=True)
    return comps


def avg_color(img, pts):
    if not pts:
        return None
    r = g = b = 0
    px = img.load()
    for (x, y) in pts:
        c = px[x, y]
        r += c[0]
        g += c[1]
        b += c[2]
    n = len(pts)
    return (r / n, g / n, b / n)


def dist(c1, c2):
    return sum((c1[i] - c2[i]) ** 2 for i in range(3)) ** 0.5


print("14dp@3x = %dpx 高，画面 %dx%d" % (REAL_H, IMG_W, IMG_H))
ok_all = True
for i, layers in enumerate(G.all_frames()):
    img = Image.new("RGB", (IMG_W, IMG_H), G.BG)
    G.draw_frame(img, layers, k, OX, OY)
    body = mask_of(layers, G.ROLE_BODY)
    body_pts = pixels(body)

    # 耳朵是否分开的正确判据是"同一行上主体被背景断成两段"（真有镂空），
    # 而不是上部连通块数 —— 后者在头抬得高的帧里会把头顶算进来，两耳假性连通。
    bp = body.load()
    gap_rows = 0
    max_gap = 0
    # 只扫耳区（视口 y < 4.6）：再往下就进入头/躯干交界，那里左右两块是
    # "后臀顶部 + 头"之间的正常空气，混进来会把耳朵镂空值算成 7 个单位。
    for y in range(OY, int(OY + 4.6 * k)):
        runs = []          # 该行主体形成的连续段 [(起, 止)]
        start = None
        for x in range(IMG_W):
            on = bp[x, y] >= 128
            if on and start is None:
                start = x
            elif not on and start is not None:
                runs.append((start, x - 1))
                start = None
        if start is not None:
            runs.append((start, IMG_W - 1))
        if len(runs) >= 2:
            gap_rows += 1
            for a, b in zip(runs, runs[1:]):
                max_gap = max(max_gap, b[0] - a[1] - 1)
    ear_comps = [1, 2] if (gap_rows >= 3 and max_gap >= 1) else [1]

    # 主体必须整块连通：SDF 融合不够时会裂成"头 + 身子"两段，14dp 上就是头掉下来
    body_comps = [c for c in components(body) if len(c) >= 8]

    body_rgb = avg_color(img, body_pts)
    eye_pts = pixels(mask_of(layers, G.ROLE_EYE))
    tail_pts = pixels(mask_of(layers, G.ROLE_TAIL))
    far_pts = pixels(mask_of(layers, G.ROLE_FAR_LEG))
    ear_in_pts = pixels(mask_of(layers, G.ROLE_EAR_INNER))

    d_eye = dist(avg_color(img, eye_pts), body_rgb) if eye_pts else 0
    d_tail = dist(avg_color(img, tail_pts), body_rgb) if tail_pts else 0
    d_earin = dist(avg_color(img, ear_in_pts), body_rgb) if ear_in_pts else 0
    cover = len(body_pts) / float(IMG_W * IMG_H)

    flags = []
    if len(body_comps) != 1:
        flags.append("主体裂成 %d 块(SDF 融合不足)" % len(body_comps))
    if len(ear_comps) != 2:
        flags.append("耳朵镂空行=%d 空隙=%dpx(两耳糊在一起)" % (gap_rows, max_gap))
    if len(eye_pts) < 2:
        flags.append("眼睛像素=%d(糊了)" % len(eye_pts))
    elif d_eye < 40:
        flags.append("眼-体色差=%.0f(偏弱)" % d_eye)
    if len(tail_pts) < 4 or d_tail < 40:
        flags.append("尾巴像素=%d 色差=%.0f" % (len(tail_pts), d_tail))
    if len(far_pts) < 8:
        flags.append("远腿像素=%d(前后腿没分开)" % len(far_pts))
    if cover < 0.16:
        flags.append("主体占比=%.0f%%(偏空)" % (cover * 100))
    if flags:
        ok_all = False

    print("frame %d: 块=%d 耳镂空=%d行/%dpx 眼=%dpx(Δ%.0f) 耳内=%dpx(Δ%.0f) 尾=%dpx(Δ%.0f) "
          "远腿=%dpx 主体=%.0f%%  %s"
          % (i, len(body_comps), gap_rows, max_gap, len(eye_pts), d_eye, len(ear_in_pts), d_earin,
             len(tail_pts), d_tail, len(far_pts), cover * 100,
             "OK" if not flags else "!! " + "; ".join(flags)))

print("\n结论：" + ("四帧在 14dp 下均可辨识" if ok_all else "存在需要修的点（见上）"))
