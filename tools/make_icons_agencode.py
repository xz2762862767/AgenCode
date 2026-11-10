"""
AgenCode 图标生成器（高端线）。

设计语言：
  · 底：近黑石墨径向渐变，左上偏冷、右下沉底，无任何杂元素
  · 主体：一对代码尖括号 < > 夹住一颗菱形内核（AgenCode = 代码里的 Agent）
  · 渐变：左侧亮青 -> 中段电蓝 -> 右侧紫，模拟"从信号到算力"的过渡
  · 质感：整字外发光 + 顶部边缘一条更亮的高光边（玻璃感）
"""
import math
from PIL import Image, ImageDraw, ImageFilter

SS = 4  # 超采样倍数，先大后缩，保证边缘干净

# 渐变色标（沿字母竖直方向）
STOPS = [
    (0.00, (125, 235, 255)),
    (0.30, (79, 168, 255)),
    (0.68, (107, 123, 255)),
    (1.00, (169, 123, 255)),
]


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def grad_color(t):
    t = max(0.0, min(1.0, t))
    for i in range(len(STOPS) - 1):
        t0, c0 = STOPS[i]
        t1, c1 = STOPS[i + 1]
        if t <= t1:
            k = 0.0 if t1 <= t0 else (t - t0) / (t1 - t0)
            return lerp(c0, c1, k)
    return STOPS[-1][1]


def thick_line(d, p0, p1, w, fill=255):
    """粗线段 + 两端圆头。PIL 的 line 是平头，圆头转折才显得精致"""
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


def mark_mask(size):
    """返回 (主体 mask, 核心 mask)。

    造型：一对代码尖括号 < > 夹住一颗菱形内核。
    语义：AgenCode = 运行在代码环境里的 Agent 内核。
    尺寸按 0.63(w) x 0.55(h) 收，自适应图标在圆形/圆角方形遮罩下都安全。
    """
    m = Image.new("L", (size, size), 0)
    core = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    dc = ImageDraw.Draw(core)
    cx = cy = size / 2.0

    half = size * 0.275     # 括号外沿到中心
    H = size * 0.47         # 括号高
    bw = size * 0.10        # 括号横向进深
    T = size * 0.075        # 笔宽
    r = size * 0.080        # 核心菱形半径

    lx, rx = cx - half, cx + half
    top, bot = cy - H / 2.0, cy + H / 2.0
    for x, sgn in ((lx, 1), (rx, -1)):
        thick_line(d, (x, top), (x + sgn * bw, cy), T)
        thick_line(d, (x + sgn * bw, cy), (x, bot), T)

    dc.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], fill=255)
    return m, core


def colorize_h(mask, size):
    """横向渐变：左侧亮青 -> 中段电蓝 -> 右侧紫"""
    px = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(px)
    for x in range(size):
        draw.line([(x, 0), (x, size)], fill=grad_color(x / max(1.0, size - 1.0)) + (255,))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(px, (0, 0), mask)
    return out


def build_a(size, h_frac=0.52, glow=True, ss=4):
    """保留原调用签名（h_frac 已不参与造型，内部比例固定）"""
    S = max(size * ss, 256)
    big = _build_a(S, glow)
    return big.resize((size, size), Image.LANCZOS)


def _build_a(size, glow=True):
    m, core = mark_mask(size)
    body_mask = m
    m = Image.new("L", (size, size), 0)
    m.paste(body_mask, (0, 0))
    m.paste(core, (0, 0), core)

    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    if glow:
        # 整体弱光
        g = m.filter(ImageFilter.GaussianBlur(max(2, size // 26)))
        glow_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        glow_img.paste(Image.new("RGBA", (size, size), (79, 140, 255, 255)), (0, 0),
                       g.point(lambda v: int(v * 0.22)))
        layer = Image.alpha_composite(layer, glow_img)
        # 核心强光：视觉焦点落在中间那颗菱形上
        cg = core.filter(ImageFilter.GaussianBlur(max(2, size // 20)))
        core_glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        core_glow.paste(Image.new("RGBA", (size, size), (170, 240, 255, 255)), (0, 0),
                        cg.point(lambda v: int(v * 0.55)))
        layer = Image.alpha_composite(layer, core_glow)

    layer = Image.alpha_composite(layer, colorize_h(m, size))

    # 顶部边缘高光（玻璃感）
    sh = max(1, size // 340)
    shifted = Image.new("L", (size, size), 0)
    shifted.paste(m, (0, sh))
    edge = Image.frombytes("L", m.size, bytes(
        max(0, a - b) for a, b in zip(m.tobytes(), shifted.tobytes())))
    hi = Image.new("RGBA", (size, size), (228, 248, 255, 255))
    hi.putalpha(edge.point(lambda v: int(v * 0.75)))
    return Image.alpha_composite(layer, hi)


def legacy_icon(size, radius_frac=0.225):
    """完整方形图标：石墨径向底 + A。按 4 倍画再缩小，边缘更锐利。"""
    S = max(size * 4, 512)
    out = _legacy_icon(S, radius_frac)
    return out.resize((size, size), Image.LANCZOS)


def _legacy_icon(size, radius_frac=0.225):
    big = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    grad = Image.new("RGBA", (size, size))
    gp = grad.load()
    cx0, cy0 = size * 0.30, size * 0.22
    maxd = math.hypot(size, size) * 0.72
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - cx0, y - cy0) / maxd
            d = min(1.0, d)
            # 中心冷灰蓝 -> 边缘近黑
            c = lerp((30, 42, 62), (7, 9, 14), d ** 0.85)
            gp[x, y] = c + (255,)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_frac), fill=255)
    big.paste(grad, (0, 0), mask)

    big = Image.alpha_composite(big, build_a(size, 0.52, ss=1))

    # 外圈一道极淡的内描边，贴合深色桌面
    ring = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(ring).rounded_rectangle(
        [1, 1, size - 2, size - 2], radius=int(size * radius_frac),
        outline=(255, 255, 255, 26), width=max(1, size // 220))
    big = Image.alpha_composite(big, ring)
    return big


def preview(path, size=1024):
    """预览：左 legacy 图，右桌面圆形裁切效果"""
    W = size * 2 + size // 6
    canvas = Image.new("RGBA", (W, size), (18, 20, 26, 255))
    icon = legacy_icon(size)
    canvas.paste(icon, (0, 0), icon)

    # 圆形（启动器常见裁切）
    circ = Image.new("L", (size, size), 0)
    ImageDraw.Draw(circ).ellipse([0, 0, size - 1, size - 1], fill=255)
    rd = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bgc = Image.new("RGBA", (size, size), (10, 13, 19, 255))
    rd.paste(bgc, (0, 0), circ)
    rd = Image.alpha_composite(rd, build_a(int(size * 1.0), 0.52))
    rd.paste(rd, (0, 0), circ)
    canvas.paste(rd, (size + size // 6, 0), rd)
    canvas.save(path)
    print("preview ->", path)


def write_android_res(base="/root/projects/nebula-ai/app/src/main/res"):
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, sz in dens.items():
        d = f"{base}/mipmap-{name}"
        legacy_icon(sz).save(f"{d}/ic_launcher.png")
        # adaptive 前景画布是 108dp
        fg = sz * 108 // 48
        # 0.52 而不是 0.60：A 底部张得开，铺满会让圆形遮罩削掉两条腿的底角
        build_a(fg, 0.52).save(f"{d}/ic_launcher_foreground.png")
    print("android res done")


if __name__ == "__main__":
    import sys
    if "--write" in sys.argv:
        write_android_res()
    else:
        preview("/sdcard/codeforge/tmp/agencode_preview.png")
