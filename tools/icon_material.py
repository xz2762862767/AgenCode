"""
AgenCode 图标 · 材质路线

放弃"深色底 + 发光线条符号"，改做实体材质：
  · 真实三维光照：球面/环面法线 + Lambert 漫反射 + Blinn-Phong 高光 + 菲涅尔边缘光
  · 背景：iOS 式超椭圆（squircle）+ 极细颗粒噪点 + 内侧 1px 亮边
  · 主体：接触阴影落地，有体积、有光泽，不发光

三个方向：
  1 曜石 —— 炭黑底 + 青蓝到靛紫的实心球，冷冽科技
  2 鎏金 —— 纯黑底 + 金属金环，镂空中心
  3 云白 —— 暖白瓷底 + 深墨蓝球，克制干净
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFilter


def _norm(v):
    v = np.asarray(v, dtype=np.float32)
    return v / np.linalg.norm(v)


def squircle_mask(S, n=5.0, circle=False):
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    u = (xx - S / 2.0) / (S / 2.0)
    v = (yy - S / 2.0) / (S / 2.0)
    if circle:
        return (u * u + v * v) <= 1.0
    return (np.abs(u) ** n + np.abs(v) ** n) <= 1.0


def bg_gradient(S, c_in, c_out, focus=(0.32, 0.24)):
    """从左上受光点到右下沉底的柔和径向渐变 + 细颗粒"""
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    d = np.hypot(xx - S * focus[0], yy - S * focus[1]) / (S * 1.05)
    t = np.clip(d, 0, 1) ** 0.9
    c_in, c_out = np.array(c_in, np.float32), np.array(c_out, np.float32)
    arr = c_in[None, None, :] + (c_out - c_in)[None, None, :] * t[:, :, None]
    rng = np.random.default_rng(7)
    arr = arr + rng.normal(0, 2.2, arr.shape).astype(np.float32)
    return np.clip(arr, 0, 255)


def render_sphere(S, R_frac, c_top, c_bot, edge_col, spec_pow=42.0,
                  spec_amt=0.92, rim_amt=0.55, cy_frac=0.47):
    """球体光照：返回 (rgb, inside_mask)"""
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    cx, cy = S / 2.0, S * cy_frac
    R = S * R_frac
    nx, ny = (xx - cx) / R, (yy - cy) / R
    rr = nx * nx + ny * ny
    inside = rr <= 1.0
    nz = np.sqrt(np.clip(1.0 - rr, 0, 1))

    L = _norm([-0.42, -0.62, 0.66])           # 主光：左上偏前
    diff = np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)

    Hv = _norm(L + np.array([0, 0, 1.0], np.float32))
    spec = np.clip(nx * Hv[0] + ny * Hv[1] + nz * Hv[2], 0, 1) ** spec_pow

    rim = (1.0 - nz) ** 3.2                    # 菲涅尔：边缘泛光
    fill = np.clip(-nx * 0.25 - ny * 0.15 + 0.18, 0, 1)  # 底部反光

    t = np.clip((ny + 1.0) / 2.0, 0, 1)
    c_top, c_bot = np.array(c_top, np.float32), np.array(c_bot, np.float32)
    edge_col = np.array(edge_col, np.float32)
    base = c_top[None, None, :] * (1 - t[:, :, None]) + c_bot[None, None, :] * t[:, :, None]

    col = base * (0.30 + 0.85 * diff)[:, :, None]
    col = col + fill[:, :, None] * base * 0.42
    col = col + (spec * spec_amt * 255.0)[:, :, None]
    col = col + (rim * rim_amt)[:, :, None] * edge_col
    return np.clip(col, 0, 255), inside


def render_ring(S, R_frac, r_frac, c_light, c_dark, c_spec, cy_frac=0.47, ky=0.93):
    """金属环（torus）。

    金属的可信度来自"反射了什么"，而不是漫反射有多亮：
    这里用反射向量采样一套虚拟环境（上亮下暗 + 一条环境灯带），
    环的上半映到亮环境、下半映到暗环境，才会出现金属特有的明暗流动。
    """
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    cx, cy = S / 2.0, S * cy_frac
    R0, r = S * R_frac, S * r_frac
    dx, dy = xx - cx, yy - cy
    # 轻微俯视：垂直方向压一点，环成为椭圆，立刻有视角和体积感
    dye = dy / ky
    rho = np.hypot(dx, dye)
    d = rho - R0
    inside = np.abs(d) <= r
    z = np.sqrt(np.clip(r * r - d * d, 0.0, None)) / r
    dd = d / r
    ux = np.divide(dx, rho, out=np.zeros_like(dx), where=rho > 1e-6)
    uy = np.divide(dye, rho, out=np.zeros_like(dye), where=rho > 1e-6)
    nx, ny, nz = ux * dd, uy * dd / ky, z
    nl = np.sqrt(nx * nx + ny * ny + nz * nz) + 1e-6
    nx, ny, nz = nx / nl, ny / nl, nz / nl

    # 反射向量 R = 2(N·V)N - V，V = (0,0,1)
    ry = 2.0 * nz * ny
    rx = 2.0 * nz * nx

    rz = 2.0 * nz * nz - 1.0
    t = np.clip((ry + 0.42) / 0.84, 0, 1)
    env = 0.14 + 0.80 * t                                  # 环境：上亮下暗
    band = np.exp(-((ry - 0.40) ** 2) / (2 * 0.115 ** 2))  # 头顶一条环境灯带
    env = env + band * 0.85
    # 方向性：反射方向朝着左上方主光的部位明显更亮，金属才有明暗流动
    Ldir = _norm([-0.58, -0.74, 0.34])
    dirv = np.clip(rx * Ldir[0] + ry * Ldir[1] + rz * Ldir[2], 0, 1) ** 1.4
    env = env * (0.50 + 0.95 * dirv)
    side = 0.40 + 0.60 * np.clip(1.0 - np.abs(rx), 0, 1)
    env = env * side

    L = _norm([-0.40, -0.66, 0.64])
    Hv = _norm(L + np.array([0, 0, 1.0], np.float32))
    spec = np.clip(nx * Hv[0] + ny * Hv[1] + nz * Hv[2], 0, 1) ** 26

    c_light, c_dark, c_spec = (np.array(x, np.float32) for x in (c_light, c_dark, c_spec))
    col = c_dark[None, None, :] + (c_light - c_dark)[None, None, :] * np.clip(env, 0, 1.6)[:, :, None]
    col = col + (spec * 0.85 * 255.0)[:, :, None] * (c_spec / 255.0)[None, None, :]
    return np.clip(col, 0, 255), inside


def contact_shadow(S, R_frac, cy_frac, strength=0.55):
    """主体下方的接触阴影，让物件"坐"在底上"""
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    cx, cy = S / 2.0, S * (cy_frac + R_frac * 0.92)
    w, h = S * R_frac * 1.55, S * R_frac * 0.30
    d.ellipse([cx - w, cy - h, cx + w, cy + h], fill=int(255 * strength))
    return np.asarray(m.filter(ImageFilter.GaussianBlur(S * 0.035)), np.float32) / 255.0


def build(plan, S=1024, circle=False):
    if plan["id"] == "obsidian":
        bg = bg_gradient(S, (18, 23, 34), (7, 8, 12))
    elif plan["id"] == "gold":
        bg = bg_gradient(S, (16, 15, 16), (6, 6, 7))
    else:
        bg = bg_gradient(S, (250, 247, 242), (228, 223, 214))

    mask = squircle_mask(S, circle=circle)
    canvas = bg

    sh = contact_shadow(S, plan["R"], 0.47, plan.get("shadow", 0.55))
    canvas = canvas * (1.0 - sh[:, :, None] * 0.85)

    if plan["kind"] == "sphere":
        col, inside = render_sphere(S, plan["R"], plan["c_top"], plan["c_bot"],
                                    plan["edge"], plan["spec_pow"],
                                    plan["spec_amt"], plan["rim"])
    else:
        col, inside = render_ring(S, plan["R"], plan["r"], plan["c_top"],
                                  plan["c_bot"], plan["c_spec"])

    a = inside.astype(np.float32)[:, :, None]
    out = canvas * (1 - a) + col * a

    # 内侧 1px 亮边（玻璃 / 瓷器的边沿）
    if plan["id"] != "cloud":
        m8 = mask.astype(np.uint8) * 255
        img = Image.fromarray(m8).filter(ImageFilter.GaussianBlur(S * 0.004))
        inner = np.asarray(img, np.float32) / 255.0
        ring = np.clip(mask.astype(np.float32) - inner, 0, 1)
        el = np.array(plan.get("edge_light", (150, 170, 200)), np.float32)
        out = out + ring[:, :, None] * el * 0.30

    out[~mask] = 0
    rgba = np.dstack([np.clip(out, 0, 255), mask.astype(np.float32) * 255]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


PLANS = [
    dict(id="obsidian", name="1 曜石 · 冷冽科技", kind="sphere", R=0.295,
         c_top=(96, 200, 255), c_bot=(74, 62, 208), edge=(140, 225, 255),
         spec_pow=64.0, spec_amt=0.52, rim=0.60, shadow=0.60,
         edge_light=(150, 175, 215)),
    dict(id="gold", name="2 鎏金 · 金属奢华", kind="ring", R=0.300, r=0.062,
         c_top=(246, 214, 138), c_bot=(96, 66, 22), c_spec=(255, 248, 214),
         shadow=0.50, edge_light=(255, 226, 160)),
    dict(id="cloud", name="3 云白 · 克制现代", kind="sphere", R=0.285,
         c_top=(52, 74, 118), c_bot=(20, 29, 50), edge=(120, 170, 230),
         spec_pow=58.0, spec_amt=0.40, rim=0.42, shadow=0.45),
]


def compare(path, size=360):
    pad, top = 34, 62
    W = pad + (size + pad) * 3
    H = top + (size + pad) * 2 + pad
    cv = Image.new("RGBA", (W, H), (14, 16, 21, 255))
    d = ImageDraw.Draw(cv)
    from PIL import ImageFont
    font = ImageFont.truetype("/sdcard/codeforge/fonts/DroidSans-Bold.ttf", 30)
    for i, p in enumerate(PLANS):
        x = pad + i * (size + pad)
        d.text((x, 16), p["name"], font=font, fill=(214, 224, 238, 255))
        for row, circ in ((0, False), (1, True)):
            im = build(p, 1024, circle=circ).resize((size, size), Image.LANCZOS)
            cv.paste(im, (x, top + row * (size + pad)), im)
    cv.convert("RGB").save(path, quality=95)
    print("->", path)


if __name__ == "__main__":
    compare("/sdcard/Download/AgenCode-icon-v3.png")
