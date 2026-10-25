from PIL import Image, ImageDraw

BG = (15, 18, 24)
FG = (232, 237, 246)
S = 3          # 放大倍数
G = 72         # 单格
PAD = 14


def canvas(w, h):
    im = Image.new("RGB", (w, h), BG)
    return im, ImageDraw.Draw(im)


def draw_a(d, ox, oy, k):
    """A: 外框+8引脚+中心圆+内部连线（当前 xml 方案）"""
    w = max(1, int(round(1.7 * k)))
    d.rounded_rectangle([ox + 4.5 * k, oy + 4.5 * k, ox + 19.5 * k, oy + 19.5 * k],
                        radius=3 * k, outline=FG, width=w)
    for x in (9.2, 14.8):
        d.line([ox + x * k, oy + 1.8 * k, ox + x * k, oy + 4.5 * k], fill=FG, width=w)
        d.line([ox + x * k, oy + 19.5 * k, ox + x * k, oy + 22.2 * k], fill=FG, width=w)
    for y in (9.2, 14.8):
        d.line([ox + 1.8 * k, oy + y * k, ox + 4.5 * k, oy + y * k], fill=FG, width=w)
        d.line([ox + 19.5 * k, oy + y * k, ox + 22.2 * k, oy + y * k], fill=FG, width=w)
    r = 2.4 * k
    d.ellipse([ox + 12 * k - r, oy + 12 * k - r, ox + 12 * k + r, oy + 12 * k + r], fill=FG)
    w2 = max(1, int(round(1.5 * k)))
    d.line([ox + 12 * k, oy + 4.5 * k, ox + 12 * k, oy + 9.6 * k], fill=FG, width=w2)
    d.line([ox + 12 * k, oy + 14.4 * k, ox + 12 * k, oy + 19.5 * k], fill=FG, width=w2)
    d.line([ox + 4.5 * k, oy + 12 * k, ox + 9.6 * k, oy + 12 * k], fill=FG, width=w2)
    d.line([ox + 14.4 * k, oy + 12 * k, ox + 19.5 * k, oy + 12 * k], fill=FG, width=w2)


def draw_b(d, ox, oy, k):
    """B: 外框+8引脚+大中心圆（去掉内部连线，更干净）"""
    w = max(1, int(round(1.8 * k)))
    d.rounded_rectangle([ox + 4.5 * k, oy + 4.5 * k, ox + 19.5 * k, oy + 19.5 * k],
                        radius=3.2 * k, outline=FG, width=w)
    for x in (9.2, 14.8):
        d.line([ox + x * k, oy + 1.8 * k, ox + x * k, oy + 4.5 * k], fill=FG, width=w)
        d.line([ox + x * k, oy + 19.5 * k, ox + x * k, oy + 22.2 * k], fill=FG, width=w)
    for y in (9.2, 14.8):
        d.line([ox + 1.8 * k, oy + y * k, ox + 4.5 * k, oy + y * k], fill=FG, width=w)
        d.line([ox + 19.5 * k, oy + y * k, ox + 22.2 * k, oy + y * k], fill=FG, width=w)
    r = 2.9 * k
    d.ellipse([ox + 12 * k - r, oy + 12 * k - r, ox + 12 * k + r, oy + 12 * k + r], fill=FG)


def draw_c(d, ox, oy, k):
    """C: 六边形+中心圆（更像模型/网络节点）"""
    w = max(1, int(round(1.8 * k)))
    pts = [(12, 2.6), (20.1, 7.3), (20.1, 16.7), (12, 21.4), (3.9, 16.7), (3.9, 7.3)]
    d.line([(ox + x * k, oy + y * k) for x, y in pts + [pts[0]]], fill=FG, width=w, joint="curve")
    r = 2.9 * k
    d.ellipse([ox + 12 * k - r, oy + 12 * k - r, ox + 12 * k + r, oy + 12 * k + r], fill=FG)


def draw_d(d, ox, oy, k):
    """D: 神经节点（三圆+连线）"""
    w = max(1, int(round(1.6 * k)))
    r1 = 2.5 * k
    for cx, cy in ((6.0, 7.0), (18.0, 7.0), (12.0, 17.5)):
        d.ellipse([ox + cx * k - r1, oy + cy * k - r1, ox + cx * k + r1, oy + cy * k + r1], fill=FG)
    d.line([ox + 6 * k, oy + 7 * k, ox + 18 * k, oy + 7 * k], fill=FG, width=w)
    d.line([ox + 6 * k, oy + 7 * k, ox + 12 * k, oy + 17.5 * k], fill=FG, width=w)
    d.line([ox + 18 * k, oy + 7 * k, ox + 12 * k, oy + 17.5 * k], fill=FG, width=w)


items = [("A 现方案", draw_a), ("B 精简", draw_b), ("C 六边形", draw_c), ("D 神经节点", draw_d)]

W = len(items) * G + (len(items) + 1) * PAD
H = G * 2 + PAD * 3 + 20
im, d = canvas(W, H)

for i, (name, fn) in enumerate(items):
    ox = PAD + i * (G + PAD)
    fn(d, ox, PAD, S)          # 放大 3x 看细节
    # 24dp 真实尺寸再放大显示（NEAREST），模拟小尺寸观感
    tmp = Image.new("RGB", (24, 24), BG)
    td = ImageDraw.Draw(tmp)
    fn(td, 0, 0, 1)
    real = tmp.resize((G, G), Image.NEAREST)
    im.paste(real, (ox, PAD * 2 + G))

im.save("/sdcard/icon_cand.png")
print("saved", im.size)
