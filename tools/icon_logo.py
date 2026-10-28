# -*- coding: utf-8 -*-
"""图标重设计：形状优先。

上一版的问题是「只有光效、没有符号」——桌面小尺寸下就是一团青雾。
这一版让每个方案都有明确几何主体，全部控制在自适应安全区（半径 0.29）内，
配色统一青绿，只对比形状。绘制用 2x 超采样保证边缘干净。
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

S = 1024
SS = 2                      # 超采样倍数
SAFE = 0.290                # 自适应安全区半径（相对画布）

# 统一配色：顶部亮青 -> 底部深青蓝
C_TOP = (0.42, 0.99, 0.85)      # #6BFFD9
C_BOT = (0.06, 0.63, 0.95)      # #0FA1F2
C_DEEP = (0.03, 0.35, 0.55)
BG1 = (0.045, 0.082, 0.090)     # 左上
BG2 = (0.016, 0.030, 0.034)     # 右下


def _grad(size, c1, c2, vertical=True):
    n = size
    t = (np.linspace(0, 1, n, dtype=np.float32) ** 1.0)
    if not vertical:
        t = t[None, :].repeat(n, 0)
    else:
        t = t[:, None].repeat(n, 1)
    return np.dstack([c1[i] * (1 - t) + c2[i] * t for i in range(3)]).astype(np.float32)


def base_canvas(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / (size - 1)
    # 左上略亮：混合垂直 + 水平权重
    t = np.clip(yy * 0.72 + (1 - xx) * 0.28, 0, 1)[:, :, None]
    img = np.array(BG1, np.float32) * (1 - t) + np.array(BG2, np.float32) * t
    r = np.hypot(xx - 0.5, yy - 0.5) / 0.7071
    v = np.clip((r - 0.45) / 0.55, 0, 1)
    v = v * v * (3 - 2 * v)
    img = img * (1 - 0.45 * v[:, :, None])
    # 顶部一层极轻玻璃反光
    gl = np.clip(1 - yy / 0.42, 0, 1) ** 2 * 0.030
    img = img + gl[:, :, None]
    return img


def _mask(fn, size, ss=SS):
    m = Image.new("L", (size * ss, size * ss), 0)
    fn(ImageDraw.Draw(m), size * ss)
    return np.asarray(m.resize((size, size), Image.LANCZOS), np.float32) / 255.0


def _round_cap(d, pts, w):
    for (x, y) in (pts[0], pts[-1]):
        d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)


# ---------------- 四个方案 ----------------

def s_term(d, N):
    """终端提示符 >_  —— 代码 / 命令行，辨识度最高"""
    w = N * 0.064
    pts = [(0.295 * N, 0.360 * N), (0.472 * N, 0.500 * N), (0.295 * N, 0.640 * N)]
    d.line(pts, fill=255, width=int(w), joint="curve")
    _round_cap(d, pts, w)
    y = 0.640 * N
    d.rounded_rectangle((0.518 * N, y - w / 2, 0.700 * N, y + w / 2),
                        radius=w / 2, fill=255)


def s_hex(d, N):
    """六角棱镜 + 核心 —— 硬朗科技，形状在圆遮罩下极稳"""
    R = 0.276 * N
    c = N * 0.5
    pts = []
    for k in range(6):
        a = np.deg2rad(-90 + 60 * k)
        pts.append((c + R * np.cos(a), c + R * np.sin(a)))
    d.line(pts + [pts[0]], fill=255, width=int(N * 0.043), joint="curve")
    # 内环
    r2 = 0.190 * N
    d.ellipse((c - r2, c - r2, c + r2, c + r2), outline=255, width=int(N * 0.016))
    # 核心
    rc = 0.062 * N
    d.ellipse((c - rc, c - rc, c + rc, c + rc), fill=255)


def s_letter_a(d, N):
    """窄体几何 A —— 只用折线构成，收在安全区内不被削角"""
    w = N * 0.062
    pts = [(0.320 * N, 0.660 * N), (0.500 * N, 0.340 * N), (0.680 * N, 0.660 * N)]
    d.line(pts, fill=255, width=int(w), joint="curve")
    _round_cap(d, pts, w)
    y = 0.575 * N
    a, b = 0.398 * N, 0.602 * N
    d.rounded_rectangle((a, y - w / 2, b, y + w / 2), radius=w / 2, fill=255)


def s_orbit(d, N):
    """轨道 + 星核 —— AI 核心感，双环张力"""
    c = N * 0.5
    for r, w in ((0.257 * N, 0.037 * N), (0.163 * N, 0.014 * N)):
        d.ellipse((c - r, c - r, c + r, c + r), outline=255, width=int(w))
    rc = 0.056 * N
    d.ellipse((c - rc, c - rc, c + rc, c + rc), fill=255)
    # 轨道上的绕行点
    ang = np.deg2rad(-52)
    px, py = c + 0.257 * N * np.cos(ang), c + 0.257 * N * np.sin(ang)
    rp = 0.030 * N
    d.ellipse((px - rp, py - rp, px + rp, py + rp), fill=255)


PLANS = [
    dict(key="term", name="终端符 >_", draw=s_term),
    dict(key="hex", name="六角棱镜", draw=s_hex),
    dict(key="letter_a", name="几何 A", draw=s_letter_a),
    dict(key="orbit", name="轨道星核", draw=s_orbit),
]


def _edge_top(a, k):
    return np.clip(a - np.roll(a, k, axis=0), 0, 1)


def build(plan, size=S):
    m = _mask(plan["draw"], size)
    img = base_canvas(size)

    # 主体：垂直渐变填充
    body = _grad(size, C_TOP, C_BOT)
    # 下缘再压一点深色，做出体积
    bottom = np.clip((np.linspace(0, 1, size, dtype=np.float32)[:, None] - 0.55) / 0.45, 0, 1)
    body = body * (1 - bottom[:, :, None] * 0.35) + np.array(C_DEEP, np.float32) * (bottom[:, :, None] * 0.35)

    mm = m[:, :, None]
    img = img * (1 - mm) + body * mm

    # 外发光（宽 + 紧两层）
    pil = Image.fromarray((m * 255).astype(np.uint8), "L")
    wide = np.asarray(pil.filter(ImageFilter.GaussianBlur(size * 0.055)), np.float32) / 255.0
    tight = np.asarray(pil.filter(ImageFilter.GaussianBlur(size * 0.012)), np.float32) / 255.0
    glow_c = np.array((0.20, 0.95, 0.85), np.float32)
    img = img + wide[:, :, None] * glow_c * 0.52
    img = img + tight[:, :, None] * glow_c * 0.34

    # 上缘内高光 / 下缘暗边，做出玻璃倒角
    k = max(2, int(size * 0.005))
    top = _edge_top(m, k) * (1 - m * 0.35)
    img = img + top[:, :, None] * np.array((0.75, 1.0, 0.98), np.float32) * 0.85
    bot = np.clip(m - np.roll(m, -k, axis=0), 0, 1)
    img = img * (1 - bot[:, :, None] * 0.30)

    # 轻微颗粒，避免大面积纯色生硬
    rng = np.random.default_rng(7)
    img = img + (rng.standard_normal((size, size, 1)).astype(np.float32) * 0.007)

    np.clip(img, 0, 1, out=img)
    return Image.fromarray((img * 255).astype(np.uint8), "RGB")


if __name__ == "__main__":
    outs = []
    for p in PLANS:
        im = build(p, S)
        im.save("/sdcard/Download/logo-%s.png" % p["key"])
        outs.append((p, im))
        print("-> /sdcard/Download/logo-%s.png" % p["key"])
