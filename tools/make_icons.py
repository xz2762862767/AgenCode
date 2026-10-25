import math
from PIL import Image, ImageDraw, ImageFilter

S = 1024
BLUE = (79, 140, 255)
BLUE_L = (150, 190, 255)

def star_points(cx, cy, R, r, n=4, rot=0.0):
    pts = []
    for i in range(n * 2):
        ang = rot + math.pi * i / n
        rad = R if i % 2 == 0 else r
        pts.append((cx + rad * math.cos(ang), cy + rad * math.sin(ang)))
    return pts

def make_foreground(size, inner_scale=0.62):
    """透明背景的星芒，居中，占总尺寸 inner_scale"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = size / 2
    R = size * inner_scale / 2
    r = R * 0.30
    # 主星（垂直四角星）
    d.polygon(star_points(cx, cy, R, r, 4, -math.pi / 2), fill=BLUE_L + (255,))
    # 副星（45° 旋转的较小星）
    d.polygon(star_points(cx, cy, R * 0.62, r * 0.55, 4, -math.pi / 4), fill=BLUE + (235,))
    # 右上小亮点
    sx, sy = cx + R * 0.72, cy - R * 0.62
    d.polygon(star_points(sx, sy, R * 0.20, R * 0.06, 4, -math.pi / 2), fill=(255, 255, 255, 235))
    # 柔光
    glow = img.filter(ImageFilter.GaussianBlur(max(2, size // 90)))
    out = Image.alpha_composite(Image.new("RGBA", (size, size), (0, 0, 0, 0)), glow)
    out = Image.alpha_composite(out, img)
    return out

def make_legacy(size):
    """带圆角的完整图标：深色渐变底 + 星芒"""
    big = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # 渐变底（对角线 径向感）
    grad = Image.new("RGBA", (size, size))
    gp = grad.load()
    for y in range(size):
        for x in range(size):
            t = (x / size * 0.45 + y / size * 0.55)
            c0 = (26, 42, 74)
            c1 = (12, 15, 22)
            gp[x, y] = (
                int(c0[0] + (c1[0] - c0[0]) * t),
                int(c0[1] + (c1[1] - c0[1]) * t),
                int(c0[2] + (c1[2] - c0[2]) * t),
                255,
            )
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.225), fill=255)
    big.paste(grad, (0, 0), mask)
    fg = make_foreground(size, 0.60)
    big = Image.alpha_composite(big, fg)
    return big

if __name__ == "__main__":
    base = "/root/projects/nebula-ai/app/src/main/res"
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, sz in dens.items():
        d = f"{base}/mipmap-{name}"
        make_legacy(sz).save(f"{d}/ic_launcher.png")
        make_foreground(sz * 108 // 48, 0.62).save(f"{d}/ic_launcher_foreground.png")
    print("icons done")
