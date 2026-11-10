#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""兔子跑动剪影生成器。

为什么换掉松鼠：松鼠的辨识度几乎全压在"一条比身体还大的蓬松尾巴"上，一旦尾巴
画细了、贴背竖直了，剪影立刻退化成"尖耳朵的狗"。兔子不一样 —— 两只长耳朵是
纯几何特征，身体怎么简化都不会认错，这对 14dp 的横幅内嵌动画来说是最优解。

坐标沿用松鼠那套：viewBox 24 x 16，朝右跑，地面 y=15。
水平方向有一个 stretch 参数：兔子是跳跃式奔跑（gallop），蹬地帧身体拉长、
腾空帧收缩，全靠这个参数实现，不是简单上下平移。

  body   = 椭圆链（后臀 / 躯干 / 肩胸），后臀最大才像兔子
  head   = 小圆头，位置比松鼠低、靠前，脖子短
  ears   = 两片细长四边形，各自后倾角不同 —— 分开成 V 才认得出是兔子，
           两片叠在一起就糊成一个尖角了
  nose   = 吻部小球，用来定朝向
  tail   = 短圆球，屁股上一点，不抢戏
  legs   = (髋点, 长度, 粗细)，顺序 前近/前远/后近/后远；后腿明显比前腿粗
  poses  = 4 帧手写关键帧 (四腿角度, 竖直起伏, 水平拉伸)
"""
import math
import os

import gen_squirrel as S

f = S.f
ellipse_path = S.ellipse_path
poly_path = S.poly_path
fill_path = S.fill_path
GROUND_Y = S.GROUND_Y
W, H = S.W, S.H

# 水平拉伸的基准线：以躯干中心为原点做 stretch，头往前伸、屁股往后拖
BODY_CX = 11.0


def ear(root_front, root_back, theta_deg, length, half_width):
    """按"竖直向上、向后倒 θ 度"造一片耳朵，返回四点四边形。

    坑：方向向量必须是 (-sinθ, -cosθ)。写成 (-cosθ, -sinθ) 就变成横着向后甩的
    滑翔翼 —— 第一版就是这么错的，剪影完全不像兔子。θ 越大耳朵越贴背。
    """
    th = math.radians(theta_deg)
    dx, dy = -math.sin(th), -math.cos(th)          # 向上偏后的单位方向
    px, py = math.cos(th), -math.sin(th)           # 该方向的法向
    rc = ((root_front[0] + root_back[0]) / 2.0, (root_front[1] + root_back[1]) / 2.0)
    tip = (rc[0] + dx * length, rc[1] + dy * length)
    tw = half_width * 0.7                          # 尖端收窄，耳朵才不是一根等宽的棍
    # 坑：root_front 相对耳根中心落在法向 p 的哪一侧，会随 θ 变号。若固定成
    # [root_front, tip+p, tip-p, root_back]，θ 一大就变成自交的蝴蝶结四边形，
    # 凸多边形 SDF 会退化成"处处为正"的空集 —— 整只后耳直接消失。
    # 所以先判符号，再让尖端顶点按同一侧配对。
    side = (root_front[0] - rc[0]) * px + (root_front[1] - rc[1]) * py
    s = 1.0 if side >= 0.0 else -1.0
    return [root_front,
            (tip[0] + px * tw * s, tip[1] + py * tw * s),
            (tip[0] - px * tw * s, tip[1] - py * tw * s),
            root_back]


# ---------------------------------------------------------------- 造型预设

# 猎兔向：后臀更饱满、躯干更长更扁、头更小更前探，耳朵长且大幅后掠，四帧是真 gallop
LEAN = dict(
    body=[(7.5, 8.4, 3.25, 3.05), (11.5, 9.15, 4.05, 2.50), (14.9, 9.50, 2.15, 2.15)],
    head=(17.6, 6.30, 2.15),
    # 耳朵从 42°/14° 加到 54°/26°，长度 4.0/4.2 加到 4.6/4.5：更贴背 = 破风感，
    # 但也别再加了 —— 前耳尖 y 只剩 1.0，视口顶是 0，再长就出画被裁掉。
    # 两耳 x 区间只在根部（15.8~16.5）交叠，中段分离，14dp 下才留得住镂空。
    ears=[ear((16.5, 5.8), (16.0, 5.2), 54, 4.60, 0.58),    # 后耳：后掠 54°，几乎贴背
          ear((18.1, 5.3), (17.5, 4.8), 26, 4.50, 0.54)],   # 前耳：后掠 26°，比后耳立
    nose=(19.45, 7.20, 0.80),
    tail=(4.30, 7.80, 1.25),
    legs=[((15.4, 10.2), 4.8, 1.05), ((14.7, 10.5), 4.6, 1.00),
          ((8.2, 10.4), 5.2, 1.85), ((7.4, 10.7), 5.0, 1.75)],
    poses=[((58, 66, 124, 114), -0.16, 1.14),    # 蹬地：前腿前够、后腿狠蹬，身体拉到最长
           ((108, 118, 72, 82), -1.05, 0.86),    # 腾空：四腿往身体中线聚拢，跃起最高
           ((80, 88, 98, 106), 0.12, 1.06),      # 前腿触地
           ((68, 76, 54, 64), -0.52, 0.92)],     # 后腿前收，蓄下一次蹬地
)

# 短粗向：身子更圆、耳朵更短更宽，像个"兔子团"
STOUT = dict(
    body=[(7.8, 9.0, 3.2, 3.2), (11.4, 9.5, 3.6, 2.8), (14.6, 9.9, 2.1, 2.3)],
    head=(17.3, 6.8, 2.4),
    ears=[ear((17.6, 5.6), (16.9, 5.0), 22, 3.7, 0.48),
          ear((17.9, 5.3), (17.2, 4.7), 8, 4.0, 0.44)],
    nose=(19.2, 7.7, 0.9),
    tail=(4.6, 8.3, 1.5),
    legs=[((15.5, 10.4), 4.4, 1.15), ((14.8, 10.7), 4.2, 1.1),
          ((8.2, 10.6), 4.8, 2.0), ((7.4, 10.9), 4.6, 1.9)],
    poses=[((62, 70, 118, 108), -0.10, 1.08),
           ((108, 116, 74, 84), -0.85, 0.90),
           ((84, 92, 96, 104), 0.12, 1.02),
           ((72, 80, 58, 68), -0.45, 0.95)],
)

# 飞奔向：耳朵更平（几乎贴着背），身体拉得更长，速度感最强但辨识度稍弱
DASH = dict(
    body=[(7.6, 8.6, 3.2, 3.0), (11.4, 9.2, 4.0, 2.5), (14.8, 9.6, 2.0, 2.1)],
    head=(17.6, 6.6, 2.1),
    ears=[ear((17.7, 5.5), (17.1, 4.9), 50, 4.2, 0.40),
          ear((17.9, 5.4), (17.3, 4.8), 36, 4.4, 0.38)],
    nose=(19.4, 7.5, 0.8),
    tail=(4.4, 7.9, 1.2),
    legs=[((15.6, 10.2), 4.9, 1.0), ((14.9, 10.5), 4.7, 0.95),
          ((8.2, 10.3), 5.3, 1.8), ((7.4, 10.6), 5.1, 1.7)],
    poses=[((60, 68, 122, 112), -0.10, 1.14),
           ((110, 118, 70, 80), -1.20, 0.86),
           ((82, 90, 98, 106), 0.12, 1.06),
           ((70, 78, 54, 64), -0.50, 0.92)],
)

PROFILES = {"lean": LEAN, "stout": STOUT, "dash": DASH}
LABELS = {"lean": "A  lean", "stout": "B  stout", "dash": "C  dash"}
# 生成 vector 时用哪个方案
ACTIVE = "lean"


# ---------------------------------------------------------------- 几何

def geom(profile, i):
    """第 i 帧的原始几何。pathData 和栅格预览都从这里取，不会两边走样。

    返回 {"dy", "shapes":[(kind, data)], "legs":[(x1,y1,x2,y2,width,far)]}
    """
    ang, dy, st = profile["poses"][i % len(profile["poses"])]

    def sx(x):
        """水平拉伸：以躯干中心为原点，蹬地帧头往前冲、屁股往后拖"""
        return BODY_CX + (x - BODY_CX) * st

    shapes = []
    for (cx, cy, rx, ry) in profile["body"]:
        shapes.append(("ellipse", (sx(cx), cy + dy, rx * st, ry)))
    hx, hy, hr = profile["head"]
    shapes.append(("ellipse", (sx(hx), hy + dy, hr, hr)))
    for ear in profile["ears"]:
        shapes.append(("poly", [(sx(x), y + dy) for x, y in ear]))
    nx, ny, nr = profile["nose"]
    shapes.append(("ellipse", (sx(nx), ny + dy, nr, nr)))
    # 尾巴只跟一半起伏：球本身不动，是屁股带着它晃
    tx, ty, tr = profile["tail"]
    shapes.append(("ellipse", (sx(tx), ty + dy * 0.5, tr, tr * 0.95)))

    legs = []
    for k, (hip, length, wdt) in enumerate(profile["legs"]):
        a = math.radians(ang[k])
        x1 = sx(hip[0])
        y1 = hip[1] + dy
        x2 = x1 + length * math.cos(a)
        y2 = min(GROUND_Y, y1 + length * math.sin(a))
        legs.append((x1, y1, x2, y2, wdt, k in (1, 3)))
    return {"dy": dy, "shapes": shapes, "legs": legs}


# ---------------------------------------------------------------- 输出

def build_vector(profile, i):
    """单帧 VectorDrawable：颜色一律写白色，运行时用 tint 换主题色。
    远侧腿压 0.55 透明度，剪影里也能分出前后腿。"""
    g = geom(profile, i)
    near = [l for l in g["legs"] if not l[5]]
    far = [l for l in g["legs"] if l[5]]
    parts = ["""<?xml version="1.0" encoding="utf-8"?>
<!-- 兔子跑动第 %d 帧，由 tools/gen_rabbit.py（preset=%s）生成，别手改 -->
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


def write_vectors(profile, res_dir, name="rabbit_run"):
    os.makedirs(res_dir, exist_ok=True)
    for i in range(len(profile["poses"])):
        fp = os.path.join(res_dir, "%s_%d.xml" % (name, i))
        with open(fp, "w") as v:
            v.write(build_vector(profile, i))
        print("wrote", fp)


if __name__ == "__main__":
    res = "/root/projects/nebula-ai/app/src/main/res/drawable"
    write_vectors(PROFILES[ACTIVE], res)
