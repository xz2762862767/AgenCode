#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""松鼠跑动剪影生成器。

一套几何两种输出：SVG（预览）和 Android VectorDrawable 的 pathData（正式用），
保证预览看到什么、装到手机上就是什么。

坐标：viewBox 24 x 16，松鼠朝右跑，地面在 y=15。

造型全部参数化在 PROFILES 里，换 preset 就是换风格：
  body  = 若干椭圆（身体 / 臀部 / 肩胸），叠起来才不是一根香肠
  head  = (cx, cy, r)，抬高到身体上方，两圆相交自然出现颈部凹角
  ear   = 三点三角形，尖要露出头顶
  nose  = 吻部小球，贴在头的前下方
  tail  = 闭合贝塞尔环，从臀部往后、往下、往上卷，尖端不许越过背线
  legs  = (髋点, 长度, 粗细)，顺序 前近/前远/后近/后远
  poses = 4 帧手写关键帧
"""
import math
import os

W, H = 24.0, 16.0
GROUND_Y = 15.0

# ---------------------------------------------------------------- 造型预设

# 写实向：弓背 + 翘臀 + 后腿粗；尾巴根部收细、向后倾斜上扬、末端内勾
SLIM = dict(
    body=[(8.9, 9.4, 2.9, 2.9), (12.0, 9.0, 4.2, 2.9), (14.8, 8.6, 2.6, 2.6)],
    head=(17.5, 4.8, 2.6),
    ear=[(16.3, 1.4), (15.8, 3.8), (18.1, 3.4)],
    nose=(20.5, 6.2, 1.0),
    tail=[(8.0, 11.4),
          ((6.2, 13.0), (4.4, 12.4), (3.4, 10.6)),
          ((1.6, 8.2), (0.4, 5.4), (0.7, 2.8)),
          ((0.9, 1.2), (2.4, 0.5), (4.0, 0.8)),
          ((5.2, 1.0), (5.9, 2.0), (5.5, 3.4)),
          ((4.6, 5.4), (3.9, 7.4), (4.1, 9.4)),
          ((4.3, 10.8), (6.2, 11.8), (8.0, 11.4))],
    legs=[((15.3, 10.6), 4.8, 1.7), ((14.5, 10.9), 4.6, 1.6),
          ((9.7, 11.0), 5.0, 2.3), ((8.7, 11.2), 4.8, 2.1)],
    poses=[((65, 82, 125, 108), 0.15), ((98, 112, 88, 74), -0.45),
           ((108, 96, 72, 86), 0.10), ((118, 126, 64, 78), -0.20)],
)

# 卡通向：头更大、身子更短、尾巴夸张到顶满画面，走"可爱"而不是"写实"
CUTE = dict(
    body=[(9.4, 9.6, 3.0, 2.9), (12.4, 9.2, 3.8, 3.0), (15.2, 8.8, 2.6, 2.7)],
    head=(18.0, 4.6, 3.2),
    ear=[(17.0, 0.4), (16.3, 3.4), (19.0, 2.8)],
    nose=(21.2, 6.2, 1.2),
    tail=[(8.6, 11.0),
          ((6.6, 12.8), (4.6, 12.2), (3.4, 10.0)),
          ((1.4, 7.2), (0.2, 4.0), (0.6, 1.6)),
          ((0.9, 1.4), (2.6, 0.6), (4.4, 1.0)),
          ((5.8, 1.4), (6.6, 2.4), (6.1, 3.8)),
          ((5.0, 5.8), (4.2, 7.4), (4.4, 9.2)),
          ((4.6, 10.6), (6.8, 11.6), (8.6, 11.0))],
    legs=[((15.6, 10.8), 4.4, 2.0), ((14.8, 11.0), 4.2, 2.0),
          ((10.2, 11.2), 4.4, 2.4), ((9.2, 11.4), 4.2, 2.4)],
    poses=[((65, 82, 125, 108), 0.15), ((98, 112, 88, 74), -0.45),
           ((108, 96, 72, 86), 0.10), ((118, 126, 64, 78), -0.20)],
)

# 旧版（对照用）：圆柱身子 + 大圆头，尾巴尖越过背线
OLD = dict(
    body=[(11.2, 9.4, 4.8, 3.4)],
    head=(17.4, 5.6, 2.9),
    ear=[(16.3, 1.5), (15.6, 4.3), (17.9, 3.9)],
    nose=(20.3, 6.9, 1.0),
    tail=[(7.0, 11.9),
          ((3.7, 12.4), (0.9, 10.2), (0.7, 7.0)),
          ((0.5, 3.8), (2.3, 1.3), (4.7, 1.1)),
          ((5.9, 1.0), (6.7, 1.5), (7.0, 2.2)),
          ((5.5, 2.5), (4.1, 3.9), (3.8, 6.3)),
          ((3.5, 8.7), (4.5, 10.4), (7.0, 11.9))],
    legs=[((15.4, 11.3), 3.9, 1.6), ((14.6, 11.5), 3.7, 1.6),
          ((9.4, 11.3), 4.0, 1.6), ((8.6, 11.5), 3.8, 1.6)],
    poses=[((65, 82, 125, 108), 0.15), ((98, 112, 88, 74), -0.45),
           ((108, 96, 72, 86), 0.10), ((118, 126, 64, 78), -0.20)],
)

PROFILES = {"old": OLD, "slim": SLIM, "cute": CUTE}
# 生成 vector 时用哪个风格
ACTIVE = "slim"


# ---------------------------------------------------------------- 几何

def f(v):
    """数字格式化，去掉多余的 .0，让 pathData 短一些"""
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def geom(profile, i):
    """第 i 帧的原始几何。pathData 和栅格预览都从这里取，不会两边走样。

    返回 {"dy", "shapes":[(kind, data)], "legs":[(x1,y1,x2,y2,width,far)]}
    """
    ang, dy = profile["poses"][i % len(profile["poses"])]

    shapes = []
    for (cx, cy, rx, ry) in profile["body"]:
        shapes.append(("ellipse", (cx, cy + dy, rx, ry)))
    hx, hy, hr = profile["head"]
    shapes.append(("ellipse", (hx, hy + dy, hr, hr)))
    shapes.append(("poly", [(x, y + dy) for x, y in profile["ear"]]))
    nx, ny, nr = profile["nose"]
    shapes.append(("ellipse", (nx, ny + dy, nr, nr)))
    # 尾巴只跟着浮一半幅度，跑起来才有"甩"的滞后感
    shapes.append(("tail", _shift_tail(profile["tail"], dy * 0.5)))

    legs = []
    for k, (hip, length, wdt) in enumerate(profile["legs"]):
        a = math.radians(ang[k])
        x2 = hip[0] + length * math.cos(a)
        y2 = min(GROUND_Y + 0.35, hip[1] + dy + length * math.sin(a))
        legs.append((hip[0], hip[1] + dy, x2, y2, wdt, k in (1, 3)))
    return {"dy": dy, "shapes": shapes, "legs": legs}


def _shift_tail(pts, dy):
    out = []
    for item in pts:
        if isinstance(item[0], tuple):
            out.append(tuple((c[0], c[1] + dy) for c in item))
        else:
            out.append((item[0], item[1] + dy))
    return out


def ellipse_path(cx, cy, rx, ry):
    return ("M%s,%s A%s,%s 0 1 0 %s,%s A%s,%s 0 1 0 %s,%s Z" % (
        f(cx - rx), f(cy), f(rx), f(ry), f(cx + rx), f(cy),
        f(rx), f(ry), f(cx - rx), f(cy)))


def poly_path(pts):
    d = ["M%s,%s" % (f(pts[0][0]), f(pts[0][1]))]
    for p in pts[1:]:
        d.append("L%s,%s" % (f(p[0]), f(p[1])))
    d.append("Z")
    return " ".join(d)


def tail_path(pts):
    d = ["M%s,%s" % (f(pts[0][0]), f(pts[0][1]))]
    for seg in pts[1:]:
        c1, c2, p = seg
        d.append("C%s,%s %s,%s %s,%s" % (
            f(c1[0]), f(c1[1]), f(c2[0]), f(c2[1]), f(p[0]), f(p[1])))
    d.append("Z")
    return " ".join(d)


def fill_path(shapes):
    out = []
    for kind, v in shapes:
        if kind == "ellipse":
            out.append(ellipse_path(*v))
        elif kind == "poly":
            out.append(poly_path(v))
        elif kind == "tail":
            out.append(tail_path(v))
    return " ".join(out)


def leg_path(legs, far):
    return " ".join(
        "M%s,%s L%s,%s" % (f(l[0]), f(l[1]), f(l[2]), f(l[3]))
        for l in legs if l[5] == far)


# ---------------------------------------------------------------- 输出

def build_vector(profile, i):
    """单帧 VectorDrawable：颜色一律写白色，运行时用 tint 换主题色。
    远侧腿压 0.55 透明度，剪影里也能分出前后腿。"""
    g = geom(profile, i)
    near = [l for l in g["legs"] if not l[5]]
    far = [l for l in g["legs"] if l[5]]
    parts = ["""<?xml version="1.0" encoding="utf-8"?>
<!-- 松鼠跑动第 %d 帧，由 tools/gen_squirrel.py（preset=%s）生成，别手改 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="16dp"
    android:viewportWidth="24"
    android:viewportHeight="16">""" % (i, ACTIVE)]
    for l in far:
        parts.append("""    <path
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="%s"
        android:strokeLineCap="round"
        android:strokeAlpha="0.55"
        android:pathData="M%s,%s L%s,%s" />""" % (f(l[4]), f(l[0]), f(l[1]), f(l[2]), f(l[3])))
    parts.append("""    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="%s" />""" % fill_path(g["shapes"]))
    for l in near:
        parts.append("""    <path
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="%s"
        android:strokeLineCap="round"
        android:pathData="M%s,%s L%s,%s" />""" % (f(l[4]), f(l[0]), f(l[1]), f(l[2]), f(l[3])))
    parts.append("</vector>")
    return "\n".join(parts) + "\n"


def write_vectors(profile, res_dir):
    os.makedirs(res_dir, exist_ok=True)
    for i in range(len(profile["poses"])):
        fp = os.path.join(res_dir, "squirrel_run_%d.xml" % i)
        with open(fp, "w") as v:
            v.write(build_vector(profile, i))
        print("wrote", fp)


if __name__ == "__main__":
    res = "/root/projects/nebula-ai/app/src/main/res/drawable"
    write_vectors(PROFILES[ACTIVE], res)
