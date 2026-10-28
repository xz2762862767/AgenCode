# -*- coding: utf-8 -*-
"""融合方案：六角棱镜外壳 + 终端符 >_ 内核。

设计逻辑
--------
* 外壳六边形给「形」——圆形遮罩下仍能看出六条边，小尺寸不糊成团。
* 内核 >_ 给「义」——一眼读懂代码/执行。
* 三种材质只改光的分配方式，不改形状，方便横向对比。

所有内容实测最大半径 0.298，落在自适应安全区 (0.305) 内。
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

S = 1024
SS = 2
HEX_R = 0.272       # 六边形外接圆半径
HEX_W = 0.050       # 六边形描边宽度
SYM_S = 0.740       # >_ 相对原坐标的缩放
SYM_W = 0.058       # >_ 笔宽

BG1 = (0.040, 0.078, 0.086)
BG2 = (0.013, 0.026, 0.030)

MATS = {
    "glass": dict(
        name="玻璃", ring_top=(0.70, 1.00, 0.96), ring_bot=(0.04, 0.46, 0.56),
        sym_top=(0.58, 1.00, 0.90), sym_bot=(0.04, 0.58, 0.92),
        glow_ring=0.40, glow_sym=0.38, panel=0.032, bevel=1.00, grain=0.007),
    "neon": dict(
        name="霓虹", ring_top=(0.45, 1.00, 0.96), ring_bot=(0.08, 0.82, 0.90),
        sym_top=(0.60, 1.00, 0.96), sym_bot=(0.14, 0.78, 1.00),
        glow_ring=0.95, glow_sym=0.88, panel=0.010, bevel=0.40, grain=0.006),
    "metal": dict(
        name="金属", ring_top=(0.88, 0.96, 0.97), ring_bot=(0.19, 0.35, 0.40),
        sym_top=(0.72, 0.98, 0.95), sym_bot=(0.09, 0.42, 0.52),
        glow_ring=0.13, glow_sym=0.15, panel=0.024, bevel=1.35, grain=0.005),
}


# ---------------- 形状 ----------------

def _hexa_pts(N, R=HEX_R):
    c = N * 0.5
    return [(c + R * N * np.cos(np.deg2rad(-90 + 60 * k)),
             c + R * N * np.sin(np.deg2rad(-90 + 60 * k))) for k in range(6)]


def d_hex_ring(d, N):
    pts = _hexa_pts(N)
    w = HEX_W * N
    d.line(pts + [pts[0]], fill=255, width=int(w), joint="curve")
    for (x, y) in pts:                      # 顶点补圆，避免尖角被抗锯齿吃掉
        d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)


def d_hex_fill(d, N):
    d.polygon(_hexa_pts(N), fill=255)


def d_term_fit(d, N):
    c = N * 0.5

    def T(x, y):
        return (c + (x - 0.5) * SYM_S * N, c + (y - 0.5) * SYM_S * N)

    w = SYM_W * N
    pts = [T(0.295, 0.360), T(0.472, 0.500), T(0.295, 0.640)]
    d.line(pts, fill=255, width=int(w), joint="curve")
    for (x, y) in (pts[0], pts[-1]):
        d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)
    a, b = T(0.518, 0.640), T(0.700, 0.640)
    d.rounded_rectangle((a[0], a[1] - w / 2, b[0], b[1] + w / 2), radius=w / 2, fill=255)


def _mask(fn, size, ss=SS):
    m = Image.new("L", (size * ss, size * ss), 0)
    fn(ImageDraw.Draw(m), size * ss)
    return np.asarray(m.resize((size, size), Image.LANCZOS), np.float32) / 255.0


# ---------------- 材质 ----------------

def _vgrad(size, top, bot, power=1.0):
    t = (np.linspace(0, 1, size, dtype=np.float32) ** power)[:, None]
    return np.dstack([top[i] * (1 - t) + bot[i] * t for i in range(3)]).astype(np.float32)


def _blur(a, size, frac):
    return np.asarray(Image.fromarray((a * 255).astype(np.uint8), "L")
                      .filter(ImageFilter.GaussianBlur(size * frac)), np.float32) / 255.0


def _screen(img, add, k=1.0):
    """按剩余余量提亮，永不超过 1.0 —— 避免高光/发光叠出死白。"""
    return 1.0 - (1.0 - img) * (1.0 - np.clip(add * k, 0, 1))


def _base(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / (size - 1)
    t = np.clip(yy * 0.72 + (1 - xx) * 0.28, 0, 1)[:, :, None]
    img = np.array(BG1, np.float32) * (1 - t) + np.array(BG2, np.float32) * t
    r = np.hypot(xx - 0.5, yy - 0.5) / 0.7071
    v = np.clip((r - 0.45) / 0.55, 0, 1)
    img *= (1 - 0.45 * (v * v * (3 - 2 * v)))[:, :, None]
    gl = np.clip(1 - yy / 0.42, 0, 1) ** 2 * 0.028
    return img + gl[:, :, None]


def build_fusion(mat_key, size=S):
    m = MATS[mat_key]
    ring = _mask(d_hex_ring, size)
    fill = _mask(d_hex_fill, size)
    sym = _mask(d_term_fit, size)

    img = _base(size)

    # 玻璃面板：内部比外部略亮，边缘一圈内阴影
    img = img + fill[:, :, None] * m["panel"]
    inner_edge = np.clip(fill - _blur(fill, size, 0.030), 0, 1)
    img = img * (1 - inner_edge[:, :, None] * 0.55)

    # ---- 外壳 ----
    body = _vgrad(size, m["ring_top"], m["ring_bot"], 0.85)
    img = img * (1 - ring[:, :, None]) + body * ring[:, :, None]
    k = max(2, int(size * 0.005))
    top = np.clip(ring - np.roll(ring, k, axis=0), 0, 1)
    bot = np.clip(ring - np.roll(ring, -k, axis=0), 0, 1)
    img = _screen(img, top[:, :, None] * np.array((0.80, 1.0, 0.98), np.float32), 0.72 * m["bevel"])
    img = img * (1 - bot[:, :, None] * 0.35 * m["bevel"])
    glow_ring = _blur(ring, size, 0.050) * 0.7 + _blur(ring, size, 0.011) * 0.3
    img = _screen(img, glow_ring[:, :, None] * np.array((0.18, 0.92, 0.86), np.float32), 0.72 * m["glow_ring"])

    # ---- 内核 >_ ----
    body2 = _vgrad(size, m["sym_top"], m["sym_bot"], 0.9)
    img = img * (1 - sym[:, :, None]) + body2 * sym[:, :, None]
    stop = np.clip(sym - np.roll(sym, k, axis=0), 0, 1)
    img = _screen(img, stop[:, :, None] * np.array((0.85, 1.0, 0.98), np.float32), 0.62 * m["bevel"])
    glow_sym = _blur(sym, size, 0.055) * 0.65 + _blur(sym, size, 0.013) * 0.35
    img = _screen(img, glow_sym[:, :, None] * np.array((0.20, 0.95, 0.90), np.float32), 0.70 * m["glow_sym"])

    rng = np.random.default_rng(11)
    img = img + rng.standard_normal((size, size, 1)).astype(np.float32) * m["grain"]
    np.clip(img, 0, 1, out=img)
    return Image.fromarray((img * 255).astype(np.uint8), "RGB")


if __name__ == "__main__":
    for k in MATS:
        build_fusion(k, S).save("/sdcard/Download/fusion-%s.png" % k)
        print("-> /sdcard/Download/fusion-%s.png" % k)
