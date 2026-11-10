"""
AgenCode 图标候选方案（三个方向并排出图）。

共同语言：冷调深空底、单色系渐变、外发光 + 边缘高光，克制不花哨。
  A「尖括号 · 核心」—— 代码尖括号夹一颗发光内核，AgenCode = Code 里跑的 Agent
  B「棱镜 · 六边」  —— 圆角六边形描边，内部一道斜向光带，抽象、偏硬件
  C「提示符 · 光标」—— 终端 > 加一条光标横线，最贴近开发者日常
"""
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter


def _arr(im):
    return np.asarray(im, dtype=np.int16)


def _sub(a, b):
    """a 减去 b，用于取描边/边缘"""
    return Image.fromarray(np.clip(_arr(a) - _arr(b), 0, 255).astype(np.uint8))


def _max(a, b):
    return Image.fromarray(np.maximum(_arr(a), _arr(b)).astype(np.uint8))

CYAN = (125, 235, 255)
BLUE = (79, 156, 255)
VIOLET = (169, 123, 255)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def thick_line(d, p0, p1, w, fill=255):
    """粗线段 + 两端圆头（PIL 的 line 是平头，圆头看着更精致）"""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    L = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / L * w / 2.0, dx / L * w / 2.0
    d.polygon([
        (p0[0] - nx, p0[1] - ny), (p1[0] - nx, p1[1] - ny),
        (p1[0] + nx, p1[1] + ny), (p0[0] + nx, p0[1] + ny),
    ], fill=fill)
    r = w / 2.0
    d.ellipse([p0[0] - r, p0[1] - r, p0[0] + r, p0[1] + r], fill=fill)
    d.ellipse([p1[0] - r, p1[1] - r, p1[0] + r, p1[1] + r], fill=fill)


def hgrad(size, c0, c1, c2):
    """横向三段渐变图"""
    img = Image.new("RGBA", (size, size))
    d = ImageDraw.Draw(img)
    for x in range(size):
        t = x / (size - 1.0)
        c = lerp(c0, c1, t / 0.5) if t < 0.5 else lerp(c1, c2, (t - 0.5) / 0.5)
        d.line([(x, 0), (x, size)], fill=c + (255,))
    return img


def finish(mask, size, glow_color, glow_amt=0.34, edge=0.75, core=None, core_amt=0.0):
    """把 mask 上色 + 外发光 + 顶部边缘高光，返回 RGBA"""
    body = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    body.paste(hgrad(size, CYAN, BLUE, VIOLET), (0, 0), mask)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    g = mask.filter(ImageFilter.GaussianBlur(max(2, size // 26)))
    tint = Image.new("RGBA", (size, size), glow_color + (255,))
    gl = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gl.paste(tint, (0, 0), g.point(lambda v: int(v * glow_amt)))
    out = Image.alpha_composite(out, gl)
    if core is not None and core_amt > 0:
        # 核心单独再亮一圈：整体光弱、核心光强，视觉焦点才落在中间
        cg = core.filter(ImageFilter.GaussianBlur(max(2, size // 20)))
        cgl = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        cgl.paste(Image.new("RGBA", (size, size), (170, 240, 255, 255)), (0, 0),
                  cg.point(lambda v: int(v * core_amt)))
        out = Image.alpha_composite(out, cgl)
    out = Image.alpha_composite(out, body)

    sh = max(1, size // 340)
    shifted = Image.new("L", (size, size), 0)
    shifted.paste(mask, (0, sh))
    e = _sub(mask, shifted)
    hi = Image.new("RGBA", (size, size), (228, 248, 255, 255))
    hi.putalpha(e.point(lambda v: int(v * edge)))
    return Image.alpha_composite(out, hi)


def draw_A(size, scale=0.52):
    """方案 A：尖括号 + 菱形核心。三者必须留出空隙，否则会糊成一条横杠。"""
    S = size
    m = Image.new("L", (S, S), 0)
    core = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    dc = ImageDraw.Draw(core)
    cx = cy = S / 2.0

    half = S * 0.275       # 括号外沿到中心的距离（总宽 ≈ 0.625，留在安全区内）
    H = S * 0.47           # 括号高（加高到接近方正，桌面图标才不显扁）
    bw = S * 0.10          # 括号横向进深
    T = S * 0.075          # 笔宽
    r = S * 0.075          # 核心菱形半径

    lx, rx = cx - half, cx + half
    top, bot = cy - H / 2.0, cy + H / 2.0
    thick_line(d, (lx, top), (lx + bw, cy), T)
    thick_line(d, (lx + bw, cy), (lx, bot), T)
    thick_line(d, (rx, top), (rx - bw, cy), T)
    thick_line(d, (rx - bw, cy), (rx, bot), T)

    dc.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], fill=255)
    m = _max(m, core)
    return finish(m, S, BLUE, glow_amt=0.20, core=core, core_amt=0.55)


def draw_B(size, scale=0.55):
    """方案 B：圆角六边形环 + 中心核（斜带会和环打架，去掉更干净）"""
    S = size
    cx = cy = S / 2.0
    R = S * scale / 2.0
    T = S * scale * 0.115

    def hexp(rad):
        return [(cx + rad * math.cos(math.pi / 6 + math.pi / 3 * i),
                 cy + rad * math.sin(math.pi / 6 + math.pi / 3 * i)) for i in range(6)]

    ring = Image.new("L", (S, S), 0)
    ImageDraw.Draw(ring).polygon(hexp(R), fill=255)
    hole = Image.new("L", (S, S), 0)
    ImageDraw.Draw(hole).polygon(hexp(R - T), fill=255)
    m = _sub(ring, hole)

    core = Image.new("L", (S, S), 0)
    cr = S * scale * 0.135
    ImageDraw.Draw(core).ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=255)
    m = _max(m, core)
    return finish(m, S, BLUE, glow_amt=0.22, core=core, core_amt=0.45)


def draw_C(size, scale=0.54):
    """方案 C：终端提示符 > 与光标"""
    S = size
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    cx = cy = S / 2.0
    W = S * scale
    H = W * 0.62
    T = W * 0.155

    # >
    x0 = cx - W / 2.0
    y0 = cy - H / 2.0 - W * 0.02
    thick_line(d, (x0, y0), (x0 + W * 0.30, y0 + H / 2.0), T)
    thick_line(d, (x0 + W * 0.30, y0 + H / 2.0), (x0, y0 + H), T)
    # 光标横线
    by = y0 + H / 2.0
    thick_line(d, (x0 + W * 0.40, by), (x0 + W * 0.78, by), T * 0.92)
    return finish(m, S, BLUE)


def tile(icon_fn, size=384, radius=0.225, circle=False):
    """把前景贴到深色底上（方形圆角 或 圆形）"""
    S = size * 4
    yy, xx = np.mgrid[0:S, 0:S]
    cx0, cy0 = S * 0.30, S * 0.22
    maxd = math.hypot(S, S) * 0.72
    dd = np.clip(np.hypot(xx - cx0, yy - cy0) / maxd, 0, 1) ** 0.85
    c0, c1 = np.array([30, 42, 62], float), np.array([7, 9, 14], float)
    arr = (c0 + (c1 - c0) * dd[:, :, None]).astype(np.uint8)
    bg = Image.fromarray(arr, "RGB").convert("RGBA")

    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    if circle:
        md.ellipse([0, 0, S - 1, S - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * radius), fill=255)

    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    out = Image.alpha_composite(out, icon_fn(S))
    out.putalpha(mask)
    return out.resize((size, size), Image.LANCZOS)


def compare(path):
    size = 384
    pad = 40
    top = 64
    W = pad + (size + pad) * 3
    H = top + (size + pad) * 2 + pad
    cv = Image.new("RGBA", (W, H), (16, 18, 24, 255))
    d = ImageDraw.Draw(cv)
    try:
        from PIL import ImageFont
        font = ImageFont.truetype("/sdcard/codeforge/fonts/DroidSans-Bold.ttf", 34)
    except Exception:
        font = None
    names = ["A · 尖括号+核心", "B · 六边棱镜", "C · 提示符光标"]
    fns = [draw_A, draw_B, draw_C]
    for i, (nm, fn) in enumerate(zip(names, fns)):
        x = pad + i * (size + pad)
        if font:
            d.text((x, 18), nm, font=font, fill=(210, 224, 240, 255))
        sq = tile(fn, size)
        cv.paste(sq, (x, top), sq)
        rd = tile(fn, size, circle=True)
        cv.paste(rd, (x, top + size + pad), rd)
    cv.save(path)
    print("->", path)


if __name__ == "__main__":
    compare("/sdcard/Download/AgenCode-icon-3options.png")
