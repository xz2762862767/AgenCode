# -*- coding: utf-8 -*-
"""AgenCode 图标 · 柔和光晕路线（Apple Intelligence 气质）

设计要点
--------
1. 纯黑底，不画任何几何形状或线条 —— 图标本身就是一团光。
2. 多个不同色相的光团用指数饱和 (1 - e^-acc) 叠加：
   核心自然趋近白色，外围保留色彩，边缘无限柔和，永远不会出现硬边。
3. 光团位置非对称、半径互不相同，交叠处产生第三种颜色 -> "有生命力"。
4. 轻微暗角 + 极细颗粒，避免大面积渐变出现色带与塑料感。
"""
import numpy as np
from PIL import Image, ImageFilter

S_BASE = 1024

# 可调增益：核心光核 / 彩色晕 / 模糊后核心回补
CORE_GAIN = 1.8
BLOB_GAIN = 0.45
CORE_BACK = 0.0
DUST_GAIN = 0.66

# (cx, cy, r, (r,g,b), weight)  坐标均为相对 0..1，半径相对画布宽
PLANS = [
    {
        "name": "晨雾 · 蓝紫霞光",
        "bg": (7, 8, 12),
        # core: 白炽光核（小、极亮） / blobs: 环绕彩色弥散晕（大、柔和）
        "core": (0.500, 0.470, 0.105, (196, 214, 255), 0.90),
        "blobs": [
            (0.435, 0.415, 0.275, (96, 116, 255), 0.95),   # 电子蓝
            (0.590, 0.545, 0.240, (58, 206, 232), 0.80),   # 青
            (0.565, 0.330, 0.215, (150, 108, 255), 0.82),  # 紫
            (0.385, 0.560, 0.195, (255, 126, 168), 0.52),  # 桃粉
            (0.500, 0.480, 0.470, (26, 38, 104), 0.42),    # 大范围底光
        ],
        "dust": [(0.700, 0.335, 0.014, (214, 230, 255), 0.60),
                 (0.335, 0.665, 0.012, (255, 196, 220), 0.46),
                 (0.645, 0.695, 0.010, (176, 238, 255), 0.42),
                 (0.300, 0.395, 0.009, (206, 200, 255), 0.36),
                 (0.735, 0.545, 0.008, (255, 224, 236), 0.32)],
    },
    {
        "name": "极光 · 青绿流光",
        "bg": (6, 9, 10),
        "core": (0.500, 0.470, 0.100, (200, 255, 242), 0.88),
        "blobs": [
            (0.450, 0.455, 0.270, (34, 208, 162), 0.92),   # 极光绿
            (0.605, 0.385, 0.230, (52, 128, 255), 0.82),   # 蓝
            (0.375, 0.575, 0.205, (132, 100, 255), 0.68),  # 紫
            (0.560, 0.610, 0.170, (188, 255, 150), 0.40),  # 黄绿
            (0.500, 0.480, 0.470, (10, 56, 66), 0.44),     # 底光
        ],
        "dust": [(0.690, 0.345, 0.013, (206, 255, 246), 0.52),
                 (0.340, 0.640, 0.010, (176, 210, 255), 0.40),
                 (0.660, 0.680, 0.009, (226, 255, 190), 0.36)],
    },
    {
        "name": "暮色 · 暖金玫光",
        "bg": (11, 8, 9),
        "core": (0.500, 0.468, 0.102, (255, 234, 206), 0.89),
        "blobs": [
            (0.465, 0.430, 0.265, (255, 140, 62), 0.92),   # 暖金
            (0.600, 0.555, 0.225, (255, 82, 132), 0.78),   # 玫红
            (0.390, 0.560, 0.200, (146, 92, 255), 0.62),   # 紫
            (0.555, 0.335, 0.180, (255, 202, 122), 0.55),  # 顶部暖光
            (0.500, 0.480, 0.470, (74, 30, 36), 0.42),     # 底光
        ],
        "dust": [(0.700, 0.330, 0.013, (255, 232, 196), 0.55),
                 (0.345, 0.655, 0.010, (255, 178, 200), 0.40),
                 (0.655, 0.675, 0.009, (255, 208, 168), 0.36)],
    },
]


def squircle_mask(S, n=5.0, inset=1.0):
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    h = S / 2.0
    u = (xx - h + 0.5) / (h * inset)
    v = (yy - h + 0.5) / (h * inset)
    a = np.abs(u) ** n + np.abs(v) ** n
    m = np.clip((1.0 - a) * (h * inset) / max(1.0, S * 0.006), 0, 1)
    return m


def build(plan, S=S_BASE):
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    xn, yn = xx / S, yy / S

    bg = np.array(plan["bg"], np.float32) / 255.0
    acc = np.zeros((S, S, 3), np.float32)

    for cx, cy, r, col, w in [plan["core"]] + list(plan["blobs"]):
        c = np.array(col, np.float32) / 255.0
        # 轻微椭圆，避免完美圆形的机械感
        dx = (xn - cx) / (r * 1.06)
        dy = (yn - cy) / (r * 0.94)
        d2 = dx * dx + dy * dy
        f = np.exp(-d2 * 1.52).astype(np.float32)
        acc += f[:, :, None] * c[None, None, :] * (w * (CORE_GAIN if r < 0.16 else BLOB_GAIN))

    # 指数饱和：核心趋白、外围留色、边缘无硬边
    glow = 1.0 - np.exp(-acc * 1.45)

    # 暗角：让光更聚、四周更沉
    r = np.hypot(xn - 0.5, yn - 0.5) / 0.7071
    vig = np.clip(1.0 - 0.42 * r ** 2.0, 0, 1)[:, :, None]

    img = bg[None, None, :] * (1.0 - glow) + glow

    # 朦胧化：整体轻模糊让光"化开"，再回补一点核心亮度避免中心发灰
    blur_r = S * 0.0115
    tmp = Image.fromarray(np.clip(img * 255.0, 0, 255).astype(np.uint8), "RGB")
    soft = np.asarray(tmp.filter(ImageFilter.GaussianBlur(blur_r)), np.float32) / 255.0
    cx0, cy0, r0 = plan["core"][0], plan["core"][1], plan["core"][2]
    cd2 = ((xn - cx0) / (r0 * 0.84)) ** 2 + ((yn - cy0) / (r0 * 0.84)) ** 2
    img = np.clip(soft + np.exp(-cd2 * 1.55)[:, :, None] * CORE_BACK, 0, 1.15)

    # ---------- 质感层 ----------
    dxn, dyn = xn - plan["core"][0], yn - plan["core"][1]
    rr = np.hypot(dxn, dyn)
    th = np.arctan2(dyn, dxn)

    # 星芒：4 向主芒 + 45° 弱芒，用高斯角向分布，径向从光核外侧起亮
    ang = np.zeros_like(th)
    for k in range(4):
        a0 = k * np.pi / 2.0
        dth = np.arctan2(np.sin(th - a0), np.cos(th - a0))
        ang += np.exp(-(dth ** 2) / (2 * 0.038 ** 2))
    for k in range(4):
        a0 = k * np.pi / 2.0 + np.pi / 4.0
        dth = np.arctan2(np.sin(th - a0), np.cos(th - a0))
        ang += 0.34 * np.exp(-(dth ** 2) / (2 * 0.036 ** 2))
    rad = np.exp(-rr / 0.420) * (1.0 - np.exp(-(rr / 0.058) ** 2))
    img = img + (ang * rad)[:, :, None] * (np.array([1.00, 0.98, 0.94], np.float32) * 0.470)
    # 星芒的柔和包裹：让芒不是一根硬线，而是有光晕的束
    halo = np.exp(-rr / 0.34) * (ang ** 0.45) * (1.0 - np.exp(-(rr / 0.075) ** 2))
    img = img + (halo * 0.10)[:, :, None]

    # 玻璃镜面高光弧：左上一条窄弧，模拟玻璃罩的反光
    ring = np.exp(-((rr - 0.218) ** 2) / (2 * 0.017 ** 2))
    up = np.exp(-((th + 2.05) ** 2) / (2 * 0.60 ** 2))
    img = img + (ring * up * 0.220)[:, :, None]

    # 玻璃内缘折射光：靠近边缘处一圈极淡的亮环，上方更强，像玻璃罩的边缘
    edge = np.exp(-((rr - 0.355) ** 2) / (2 * 0.045 ** 2))
    edge = edge * (0.55 + 0.45 * np.clip(-np.sin(th), 0, 1))
    img = img + (edge * 0.105)[:, :, None]

    # 星尘：几粒极小的光点，制造纵深与"活物"感
    for sx, sy, sr, scol, sw in plan.get("dust", []):
        dd2 = ((xn - sx) ** 2 + (yn - sy) ** 2) / (sr * sr)
        cdust = np.array(scol, np.float32) / 255.0 * sw * DUST_GAIN
        img = img + np.exp(-dd2 * 2.1)[:, :, None] * cdust
        # 星尘的十字小芒：微小但锐利，才有"闪"的感觉
        ex, ey = xn - sx, yn - sy
        wbar = sr * 0.30
        spk = (np.exp(-(ey ** 2) / (2 * wbar ** 2)) * np.exp(-np.abs(ex) / (sr * 2.2))
               + np.exp(-(ex ** 2) / (2 * wbar ** 2)) * np.exp(-np.abs(ey) / (sr * 2.2)))
        img = img + (spk * 0.55)[:, :, None] * cdust

    # 玻璃反光：极淡的上亮下暗，制造一层"罩子"的厚度
    img = img + ((0.5 - yn) * 0.042)[:, :, None]
    img = np.clip(img, 0, 1.15)

    img = img * vig

    # 细颗粒，消除色带
    rng = np.random.default_rng(20251027)
    img = img + rng.normal(0, 0.0052, (S, S, 3)).astype(np.float32)
    # 磨砂玻璃的细密纹理：低幅高频，压制大面积渐变的塑料感
    grit = rng.normal(0, 1.0, (S // 3, S // 3, 3)).astype(np.float32)
    grit = np.asarray(Image.fromarray(((grit * 40) + 128).clip(0, 255).astype(np.uint8)).resize((S, S), Image.BILINEAR), np.float32)
    img = img + ((grit - 128.0) / 255.0) * 0.016

    rgb8 = np.clip(img * 255.0, 0, 255).astype(np.uint8)
    a = (squircle_mask(S) * 255).astype(np.uint8)
    return Image.fromarray(np.dstack([rgb8, a]), "RGBA")


def contact_sheet(out_path, cell=512, pad=24):
    tiles = []
    for p in PLANS:
        im = build(p, cell)
        bg = Image.new("RGBA", (cell, cell), (13, 14, 17, 255))
        bg.alpha_composite(im)
        tiles.append(bg.convert("RGB"))
    W = len(tiles) * cell + (len(tiles) + 1) * pad
    H = cell + 2 * pad
    sheet = Image.new("RGB", (W, H), (13, 14, 17))
    for i, t in enumerate(tiles):
        sheet.paste(t, (pad + i * (cell + pad), pad))
    sheet.save(out_path)
    return out_path


if __name__ == "__main__":
    import sys
    for p in PLANS:
        f = "/sdcard/Download/AgenCode-glow-%s.png" % p["name"].split(" ")[0]
        build(p).save(f)
        print("->", f)
    print("->", contact_sheet("/sdcard/Download/AgenCode-icon-glow.png"))
