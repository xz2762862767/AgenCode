#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""兔子高保真几何：距离场平滑并集 -> 等值线 -> 多色分层。

和 gen_rabbit.py 的根本区别：
  旧版把椭圆/胶囊一个个丢进 PIL 的 fill() 里取并集，轮廓上保留了每段圆弧的接缝，
  所以放大看就是"几个圆球拼出来的"。这一版先把所有部件写成有符号距离场（SDF），
  再用 smooth-min 融合成一块，最后从 0 等值线提轮廓 —— 部件之间是平滑过渡，
  像一整块面团捏出来的，而不是零件组装。

另外补上剪影也能看出的层次（这是"真实感"的另一半）：
  - 耳内侧浅色（真实兔子耳内是浅粉/白，不是同色）
  - 眼睛（深色小点）
  - 尾巴近白（兔子屁股上那块白是极强的识别特征）
  - 远侧腿压暗（分出前后）
  - 脚掌（兔子后脚掌很长，是跳跃的主力，纯胶囊画不出来）

输出：
  1. app/src/main/java/com/nebula/aichat/ui/RabbitGeometry.java
  2. /sdcard/Download/rabbit_hd.png（大图看细节 + 真实 14dp 尺寸看观感）

用法：/usr/bin/python3.9 tools/gen_rabbit_hi.py [输出png]
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

TOOLS = "/root/projects/nebula-ai/tools"
sys.path.insert(0, TOOLS)
import gen_rabbit as GR          # noqa: E402

W, H = 24.0, 16.0
GROUND_Y = 15.0
RES = 0.05                       # 采样步长（单位/像素）-> 480 x 320 的场
NW, NH = int(W / RES), int(H / RES)
SMOOTH_K = 0.62                  # smooth-min 的融合半径（单位）。太小等于没融合，
                                 # 太大头和身子会糊成一坨，0.6 大约是头半径的 1/4

# 角色：绘制顺序就是从下到上
ROLE_FAR_LEG, ROLE_BODY, ROLE_EAR_INNER, ROLE_TAIL, ROLE_EYE, ROLE_NOSE = range(6)
ROLE_NAMES = ["FAR_LEG", "BODY", "EAR_INNER", "TAIL", "EYE", "NOSE"]

# ---------------------------------------------------------------- SDF 基元


def sd_ellipse(X, Y, cx, cy, rx, ry):
    dx, dy = (X - cx) / rx, (Y - cy) / ry
    k = np.sqrt(dx * dx + dy * dy)
    return (k - 1.0) * min(rx, ry)


def sd_capsule(X, Y, ax, ay, bx, by, w):
    pax, pay = X - ax, Y - ay
    bax, bay = bx - ax, by - ay
    l2 = bax * bax + bay * bay + 1e-12
    t = np.clip((pax * bax + pay * bay) / l2, 0.0, 1.0)
    dx, dy = pax - bax * t, pay - bay * t
    return np.sqrt(dx * dx + dy * dy) - w * 0.5


def sd_convex_poly(X, Y, pts):
    """凸多边形的有符号距离：内部为负、外部为正。"""
    p = np.asarray(pts, float)
    area2 = np.sum(p[:, 0] * np.roll(p[:, 1], -1) - np.roll(p[:, 0], -1) * p[:, 1])
    if area2 < 0:
        p = p[::-1]                      # 统一成逆时针，内法向才稳定
    best = None
    n = len(p)
    for i in range(n):
        ax, ay = p[i]
        bx, by = p[(i + 1) % n]
        ex, ey = bx - ax, by - ay
        ln = math.hypot(ex, ey) + 1e-12
        nx, ny = -ey / ln, ex / ln       # 逆时针 -> 内部在边的左侧
        d = (X - ax) * nx + (Y - ay) * ny
        best = d if best is None else np.minimum(best, d)
    return -best


def smin(a, b, k):
    """多项式 smooth-min：两个场靠得近时平滑融合，离得远时仍是普通 min。"""
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0.0, 1.0)
    return b * (1.0 - h) + a * h - k * h * (1.0 - h)


# ---------------------------------------------------------------- 等值线

def contour_rings(f, lvl=0.0):
    """marching squares：把 f=level 的等值线提成闭合环（网格坐标）。"""
    s = f > lvl
    a, b = s[:-1, :-1], s[:-1, 1:]
    c, d = s[1:, 1:], s[1:, :-1]
    act = (a != b) | (b != c) | (c != d) | (d != a)
    ii, jj = np.nonzero(act)

    segs = []
    for i, j in zip(ii.tolist(), jj.tolist()):
        corners = ((j, i), (j + 1, i), (j + 1, i + 1), (j, i + 1))
        vals = (f[i, j], f[i, j + 1], f[i + 1, j + 1], f[i + 1, j])
        pts = []
        for k in range(4):
            k2 = (k + 1) % 4
            v1, v2 = vals[k], vals[k2]
            if (v1 > lvl) != (v2 > lvl):
                t = (lvl - v1) / (v2 - v1)
                x1, y1 = corners[k]
                x2, y2 = corners[k2]
                pts.append((x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
        if len(pts) == 2:
            segs.append((pts[0], pts[1]))
        elif len(pts) == 4:
            # 鞍点：用中心值判断该怎么连，不然会连出交叉的线
            cen = sum(vals) / 4.0
            if (cen > lvl) == (vals[0] > lvl):
                segs.append((pts[0], pts[3]))
                segs.append((pts[1], pts[2]))
            else:
                segs.append((pts[0], pts[1]))
                segs.append((pts[2], pts[3]))

    Q = 10000.0
    key = lambda p: (int(round(p[0] * Q)), int(round(p[1] * Q)))
    edges = [(key(p0), key(p1), p0, p1) for p0, p1 in segs]
    adj = {}
    for idx, (k0, k1, _p0, _p1) in enumerate(edges):
        adj.setdefault(k0, []).append((k1, idx))
        adj.setdefault(k1, []).append((k0, idx))

    # 按"边"遍历而不是按"点"：点的 visited 集合会在环之间互相污染，
    # 第一个环走完把公共点标记掉，后面的环立刻断在半路 —— 耳朵就是这么丢的。
    used = [False] * len(edges)
    rings = []
    for start in range(len(edges)):
        if used[start]:
            continue
        _k0, _k1, p0, p1 = edges[start]
        used[start] = True
        ring = [p0, p1]
        cur, prev_pt = edges[start][1], p1
        while True:
            nxt = None
            for (k, idx) in adj.get(cur, ()):
                if not used[idx]:
                    nxt = (k, idx)
                    break
            if nxt is None:
                break
            _k, idx = nxt
            a_pt, b_pt = edges[idx][2], edges[idx][3]
            used[idx] = True
            same = (abs(a_pt[0] - prev_pt[0]) < 1e-9 and abs(a_pt[1] - prev_pt[1]) < 1e-9)
            nxt_pt = b_pt if same else a_pt
            ring.append(nxt_pt)
            prev_pt = nxt_pt
            cur = key(nxt_pt)
        if len(ring) > 3:
            rings.append(ring)
    return rings


def poly_area(p):
    x = [q[0] for q in p]
    y = [q[1] for q in p]
    return abs(sum(x[i] * y[(i + 1) % len(p)] - x[(i + 1) % len(p)] * y[i]
                   for i in range(len(p)))) * 0.5


def dp_simplify(pts, eps):
    """Douglas-Peucker：等值线动辄几千点，直接喂给 pathData 是浪费。"""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        ax, ay = pts[i]
        bx, by = pts[j]
        dx, dy = bx - ax, by - ay
        ln = math.hypot(dx, dy) + 1e-12
        best, bi = -1.0, -1
        for k in range(i + 1, j):
            px, py = pts[k]
            dist = abs((px - ax) * dy - (py - ay) * dx) / ln
            if dist > best:
                best, bi = dist, k
        if best > eps:
            keep[bi] = True
            stack.append((i, bi))
            stack.append((bi, j))
    return [p for p, k in zip(pts, keep) if k]


def dp_closed(pts, eps):
    """闭合环的 DP 简化。

    坑：marching squares 出来的环首尾是同一个点，直接丢给 dp_simplify 时基线长度为 0，
    所有中间点到基线的距离恒为 0，整条轮廓会被简化成两个重合点（整只兔子消失）。
    所以先砍掉重复尾点，再从中间挑第二个锚点，拆成两条开放折线各自简化。
    """
    while len(pts) > 3:
        if math.hypot(pts[-1][0] - pts[0][0], pts[-1][1] - pts[0][1]) < 1e-9:
            pts = pts[:-1]
        else:
            break
    n = len(pts)
    if n < 6:
        return pts
    mid = n // 2
    a = dp_simplify(pts[:mid + 1], eps)
    b = dp_simplify(pts[mid:] + [pts[0]], eps)
    return a[:-1] + b[:-1]


def chaikin(pts, it=1):
    """闭合曲线细分：把 DP 留下的折角磨圆。点到点之间足够密，Java 里 lineTo 就够。"""
    for _ in range(it):
        out = []
        n = len(pts)
        for i in range(n):
            x0, y0 = pts[i]
            x1, y1 = pts[(i + 1) % n]
            out.append((0.75 * x0 + 0.25 * x1, 0.75 * y0 + 0.25 * y1))
            out.append((0.25 * x0 + 0.75 * x1, 0.25 * y0 + 0.75 * y1))
        pts = out
    return pts


def ellipse_poly(cx, cy, rx, ry, n=14):
    return [(cx + rx * math.cos(2 * math.pi * i / n),
             cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]


def capsule_poly(ax, ay, bx, by, w, n=7):
    """胶囊轮廓：两条平行边 + 两个半圆端帽。"""
    dx, dy = bx - ax, by - ay
    ln = math.hypot(dx, dy) + 1e-12
    ux, uy = dx / ln, dy / ln
    nx, ny = -uy, ux
    r = w * 0.5
    pts = []
    for i in range(n + 1):
        t = math.pi * i / n
        pts.append((bx + (ux * math.cos(t) + nx * math.sin(t)) * r,
                    by + (uy * math.cos(t) + ny * math.sin(t)) * r))
    for i in range(n + 1):
        t = math.pi * i / n
        pts.append((ax - (ux * math.cos(t) - nx * math.sin(t)) * r,
                    ay - (uy * math.cos(t) - ny * math.sin(t)) * r))
    return pts


# ---------------------------------------------------------------- 造型

# 取自 gen_rabbit.py 的 lean 预设（已验证的那套），在这里加了三样东西：
# 颊部椭圆（脸侧面要饱满）、耳内侧、脚掌。
LEAN = dict(GR.PROFILES["lean"])
# poses 是 list 对象，浅拷贝会改坏 gen_rabbit.py，必须自己复制一份。
# 另外把远近腿的角度差从 8~10 度拉到 22~27 度：差太小两条腿在剪影里完全重叠，
# 远腿被主体盖住，看起来只有两条腿。
LEAN["poses"] = [
    ((55, 82, 125, 98), -0.10, 1.10),
    ((100, 122, 68, 90), -0.80, 0.88),
    ((78, 100, 90, 112), 0.12, 1.04),
    ((66, 88, 52, 74), -0.45, 0.94),
]

# 颊部：从头中心往下方偏前一点，让脸不是个正圆
CHEEK = (17.9, 7.9, 1.35, 1.15)
# 下颌：脸颊下方补一点，脖子那一侧才有下巴而不是直接切进去
JAW = (16.6, 8.6, 1.5, 0.95)
# 肩：前腿根部和胸之间补一块，跑起来肩胛是凸的
SHOULDER = (14.9, 8.9, 1.7, 1.4)

# 耳朵参数（取自 lean 预设，原样）。单独拎出来是为了能按帧改倾角。
EARS = [dict(root_front=(16.5, 5.8), root_back=(16.0, 5.2),
             theta=42.0, length=4.0, hw=0.55),
        dict(root_front=(18.1, 5.3), root_back=(17.5, 4.8),
             theta=14.0, length=4.2, hw=0.50)]
# 每帧耳倾角附加量（度）。纯平移的耳朵看起来像塑料，腾空帧被风压向后甩、
# 触地帧回弹前倾，整只兔子才"活"。变化控制在 20 度内，四帧才不会跳。
EAR_TILT = [6.0, 15.0, 3.0, -4.0]

# 脚掌（单位：长度, 宽度），兔子后脚掌特别长
FOOT = {0: (0.55, 0.55), 1: (0.50, 0.50), 2: (1.25, 0.85), 3: (1.15, 0.80)}


def ear_inner(ear_quad):
    """从耳朵四边形里取一块内耳：沿耳轴缩到 22%~86%，宽度收 52%。"""
    root_f, tip_a, tip_b, root_b = ear_quad
    rc = ((root_f[0] + root_b[0]) * 0.5, (root_f[1] + root_b[1]) * 0.5)
    tc = ((tip_a[0] + tip_b[0]) * 0.5, (tip_a[1] + tip_b[1]) * 0.5)

    def at(t, hw_scale):
        x = rc[0] + (tc[0] - rc[0]) * t
        y = rc[1] + (tc[1] - rc[1]) * t
        # 沿耳轴的法向
        ex, ey = tc[0] - rc[0], tc[1] - rc[1]
        ln = math.hypot(ex, ey) + 1e-12
        nx, ny = -ey / ln, ex / ln
        half = _ear_half_width(ear_quad) * hw_scale
        return (x + nx * half, y + ny * half), (x - nx * half, y - ny * half)

    a0, b0 = at(0.22, 0.52)
    a1, b1 = at(0.86, 0.26)
    return [a0, a1, b1, b0]


def _ear_half_width(q):
    root_f, tip_a, tip_b, root_b = q
    return (math.hypot(tip_a[0] - tip_b[0], tip_a[1] - tip_b[1]) * 0.5
            + math.hypot(root_f[0] - root_b[0], root_f[1] - root_b[1]) * 0.5) * 0.5


def frame_layers(i):
    """第 i 帧的所有部件，分两类返回：
       solid  —— 参与 SDF 融合、最终合成一条主体轮廓
       detail —— 单独上色的层
    """
    prof = LEAN
    ang, dy, st = prof["poses"][i % len(prof["poses"])]

    def sx(x):
        return GR.BODY_CX + (x - GR.BODY_CX) * st

    solid = []
    for (cx, cy, rx, ry) in prof["body"]:
        solid.append(("ellipse", (sx(cx), cy + dy, rx * st, ry)))
    hx, hy, hr = prof["head"]
    solid.append(("ellipse", (sx(hx), hy + dy, hr, hr)))
    for spec in (CHEEK, JAW, SHOULDER):
        cx, cy, rx, ry = spec
        solid.append(("ellipse", (sx(cx), cy + dy, rx * st, ry)))
    tilt = EAR_TILT[i % len(EAR_TILT)]
    ears = []
    for e in EARS:
        q = GR.ear(e["root_front"], e["root_back"], e["theta"] + tilt,
                   e["length"], e["hw"])
        ears.append([(sx(x), y + dy) for x, y in q])
    for q in ears:
        solid.append(("poly", q))
    nx, ny, nr = prof["nose"]
    solid.append(("ellipse", (sx(nx), ny + dy, nr, nr)))
    tx, ty, tr = prof["tail"]
    solid.append(("ellipse", (sx(tx), ty + dy * 0.5, tr, tr * 0.95)))

    legs, feet, far_legs = [], [], []
    for k, (hip, length, wdt) in enumerate(prof["legs"]):
        a = math.radians(ang[k])
        x1, y1 = sx(hip[0]), hip[1] + dy
        x2 = x1 + length * math.cos(a)
        y2 = min(GROUND_Y, y1 + length * math.sin(a))
        flen, fw = FOOT[k]
        # 脚掌：从腿末端往 +x 伸（兔子脚是朝前的），末端贴地
        fx = x2 + flen
        fy = min(GROUND_Y, y2)
        is_far = k in (1, 3)
        item = (x1, y1, x2, y2, wdt, fx, fy, fw)
        if is_far:
            far_legs.append(item)
        else:
            legs.append(item)
            feet.append(item)

    # 近侧腿和脚掌参与融合
    for (x1, y1, x2, y2, wdt, fx, fy, fw) in legs:
        solid.append(("capsule", (x1, y1, x2, y2, wdt)))
        solid.append(("capsule", (x2, y2, fx, fy, fw)))

    detail = {
        ROLE_FAR_LEG: [],
        ROLE_EAR_INNER: [ear_inner(q) for q in ears],
        ROLE_TAIL: [ellipse_poly(sx(tx), ty + dy * 0.5, tr * 0.56, tr * 0.53, 12)],
        ROLE_EYE: [ellipse_poly(sx(hx + 0.10 * hr), hy + dy - 0.30 * hr,
                                0.40, 0.36, 12)],
        ROLE_NOSE: [ellipse_poly(sx(nx), ny + dy, nr * 0.46, nr * 0.42, 10)],
    }
    for (x1, y1, x2, y2, wdt, fx, fy, fw) in far_legs:
        detail[ROLE_FAR_LEG].append(capsule_poly(x1, y1, x2, y2, wdt))
        detail[ROLE_FAR_LEG].append(capsule_poly(x2, y2, fx, fy, fw))
    return solid, detail


def _segs_cross(p1, p2, p3, p4):
    def cr(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    return (cr(p3, p4, p1) * cr(p3, p4, p2) < 0 and
            cr(p1, p2, p3) * cr(p1, p2, p4) < 0)


def check_quads(i):
    """自检：耳朵四边形必须不自交。

    坑：sd_convex_poly 按半平面交集算凸多边形距离。四边形一旦自交（蝴蝶结），
    各边内法向互相打架，交集为空 —— SDF 处处为正，那一片耳朵会被静默丢掉，
    整帧只剩一只耳。这种 bug 生成器不会报错，只能靠这里拦。
    """
    solid, _ = frame_layers(i)
    for kind, v in solid:
        if kind != "poly":
            continue
        a, b, c, d = v
        if _segs_cross(a, b, c, d) or _segs_cross(b, c, d, a):
            raise RuntimeError("frame %d: 耳朵四边形自交 -> %r" % (i, v))


def build_sdf(solid):
    X, Y = np.meshgrid((np.arange(NW) + 0.5) * RES,
                       (np.arange(NH) + 0.5) * RES)
    field = None
    for kind, v in solid:
        if kind == "ellipse":
            d = sd_ellipse(X, Y, *v)
        elif kind == "capsule":
            d = sd_capsule(X, Y, *v)
        else:
            d = sd_convex_poly(X, Y, v)
        field = d if field is None else smin(field, d, SMOOTH_K)
    return field


def body_outline(i):
    solid, detail = frame_layers(i)
    field = build_sdf(solid)
    rings = contour_rings(field, 0.0)
    if not rings:
        raise SystemExit("等值线提取失败：SDF 里没有闭合环")
    # 网格坐标 -> 视口单位
    rings = [[(p[0] * RES, p[1] * RES) for p in r] for r in rings]
    main = max(rings, key=poly_area)
    main = dp_closed(main, 0.05)         # 0.05 单位 ≈ 14dp 下的 0.03dp，肉眼看不出
    main = chaikin(main, 1)
    return main, detail


def all_frames():
    out = []
    for i in range(len(LEAN["poses"])):
        body, detail = body_outline(i)
        layers = [[] for _ in range(6)]
        layers[ROLE_BODY] = [body]
        for role, polys in detail.items():
            layers[role] = polys
        out.append(layers)
    return out


# ---------------------------------------------------------------- 输出

def dump_java(frames, path):
    lines = ["""package com.nebula.aichat.ui;

/**
 * 兔子四帧几何，由 tools/gen_rabbit_hi.py 生成，别手改。
 *
 * <p>坐标是 24x16 视口单位，每个多边形是扁平点列 x,y,x,y...（闭合，最后一点不重复首点）。
 * 层按数组下标从下往上画：远侧腿 -> 主体 -> 耳内侧 -> 尾巴 -> 眼睛 -> 鼻子。
 */
final class RabbitGeometry {

    private RabbitGeometry() {
    }

    static final int FAR_LEG = 0;
    static final int BODY = 1;
    static final int EAR_INNER = 2;
    static final int TAIL = 3;
    static final int EYE = 4;
    static final int NOSE = 5;
    static final int LAYERS = 6;

    /** [帧][层] -> 该层的一个或多个闭合多边形，每项是 {x0,y0,x1,y1,...} */
    static final float[][][][] POLY = {"""]
    for fi, layers in enumerate(frames):
        lines.append("        {   // frame %d" % fi)
        for li in range(6):
            polys = layers[li]
            lines.append("            {   // %s" % ROLE_NAMES[li])
            for poly in polys:
                flat = []
                for (x, y) in poly:
                    # 必须带 f 后缀：写成 double 字面量在 Java 里是 lossy 编译错误
                    flat.append("%.2ff" % x)
                    flat.append("%.2ff" % y)
                # 每行 10 个数，避免单行过长
                chunks = [", ".join(flat[k:k + 10]) for k in range(0, len(flat), 10)]
                lines.append("                {%s}," % (",\n                 ".join(chunks)))
            lines.append("            },")
        lines.append("        },")
    lines.append("""    };
}
""")
    with open(path, "w") as f:
        f.write("\n".join(lines))
    total = sum(len(p) for fr in frames for ls in fr for p in ls)
    print("wrote", path, "polygons:", total)


# ---------------------------------------------------------------- 预览

BASE = (147, 242, 196)      # 横幅"运行中"的青绿
WHITE = (255, 255, 255)
BLACK = (10, 14, 16)


def blend(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def colors(base=BASE):
    return {
        ROLE_FAR_LEG: blend(base, (26, 28, 32), 0.45),
        ROLE_BODY: base,
        ROLE_EAR_INNER: blend(base, WHITE, 0.60),
        ROLE_TAIL: blend(base, WHITE, 0.82),
        ROLE_EYE: blend(base, BLACK, 0.88),
        ROLE_NOSE: blend(base, BLACK, 0.80),
    }


BG = (26, 28, 32)


def draw_frame(img, layers, k, ox, oy, base=BASE):
    """按 RabbitView.onDraw 的层序渲染：远腿压暗 -> 主体渐变 -> 耳内/尾/眼/鼻。

    预览必须和 App 里 Canvas 的行为一致，否则是在评价一张手机上不存在的图。
    主体是竖向渐变（y=2.2 最亮 -> y=15.6 最暗），再加一圈内侧压暗描边。
    """
    d = ImageDraw.Draw(img)
    w, h = img.size

    def pts(poly):
        return [(x * k + ox, y * k + oy) for (x, y) in poly]

    # 远侧腿：压暗到接近背景，分出前后腿层次（对应 Java 里的 alpha 150）
    for poly in layers[ROLE_FAR_LEG]:
        d.polygon(pts(poly), fill=blend(base, BG, 0.45))

    # 主体：先铺竖向渐变，再拿主体多边形当遮罩贴上去
    top, bot = blend(base, WHITE, 0.30), blend(base, BLACK, 0.26)
    y0, y1 = 2.2 * k + oy, 15.6 * k + oy
    grad = Image.new("RGB", (w, h))
    gd = ImageDraw.Draw(grad)
    for yy in range(h):
        t = (yy - y0) / max(1e-6, (y1 - y0))
        t = 0.0 if t < 0 else (1.0 if t > 1 else t)
        gd.line([(0, yy), (w, yy)], fill=blend(top, bot, t))

    mask = Image.new("L", (w, h), 0)
    md = ImageDraw.Draw(mask)
    for poly in layers[ROLE_BODY]:
        md.polygon(pts(poly), fill=255)
    img.paste(grad, (0, 0), mask)

    # 内侧压暗描边：mask 减一圈腐蚀，只留轮廓内侧
    ew = int(round(0.34 * k))
    if ew >= 1:
        inner = mask.filter(ImageFilter.MinFilter(ew * 2 + 1))
        edge = ImageChops.subtract(mask, inner).point(lambda v: int(v * 0.5))
        img.paste(blend(base, BLACK, 0.30), (0, 0), edge)

    for role, col in ((ROLE_EAR_INNER, blend(base, WHITE, 0.62)),
                      (ROLE_TAIL, blend(base, WHITE, 0.86)),
                      (ROLE_NOSE, blend(base, BLACK, 0.72)),
                      (ROLE_EYE, blend(base, BLACK, 0.88))):
        for poly in layers[role]:
            d.polygon(pts(poly), fill=col)


def render_sheet(frames, out, bg=BG):
    """两段：上面大图看细节，下面按 14dp@3x 的真实像素渲染再放大 8 倍看观感。"""
    k = 26.0                                   # 大图：每单位 26px
    fw, fh = int(W * k) + 16, int(H * k) + 16
    n = len(frames)
    big = Image.new("RGB", (fw * n, fh), bg)
    for i, layers in enumerate(frames):
        img = Image.new("RGB", (fw, fh), bg)
        draw_frame(img, layers, k, 8, 8)
        big.paste(img, (i * fw, 0))

    # 真实尺寸：14dp 高 @3x = 42px，宽度按 24:16 得 63px
    real_h = 42
    kk = real_h / H
    rw, rh = int(W * kk) + 6, real_h + 6
    Z = 8
    small = Image.new("RGB", (rw * n, rh), bg)
    for i, layers in enumerate(frames):
        img = Image.new("RGB", (rw, rh), bg)
        draw_frame(img, layers, kk, 3, 3)
        small.paste(img, (i * rw, 0))
    small = small.resize((small.width * Z, small.height * Z), Image.LANCZOS)

    gap = 12
    canvas = Image.new("RGB", (max(big.width, small.width), big.height + gap + small.height), bg)
    canvas.paste(big, (0, 0))
    canvas.paste(small, (0, big.height + gap))
    canvas.save(out)
    print("saved", out, canvas.size, "| 下半部分是 14dp@3x 实际像素放大 %dx" % Z)


def ascii_frame(layers, cols=100, rows=25):
    """字符画：空格=空，'#'=主体，'o'=耳内侧/尾，'.'=暗(远腿)，'@'=眼/鼻"""
    grid = [[" " for _ in range(cols)] for _ in range(rows)]
    order = [ROLE_FAR_LEG, ROLE_BODY, ROLE_TAIL, ROLE_EAR_INNER, ROLE_NOSE, ROLE_EYE]
    ch = {ROLE_FAR_LEG: ".", ROLE_BODY: "#", ROLE_TAIL: "o",
          ROLE_EAR_INNER: "o", ROLE_NOSE: "@", ROLE_EYE: "@"}
    for role in order:
        for poly in layers[role]:
            px = [(p[0] / W * cols, p[1] / H * rows) for p in poly]
            xs = [p[0] for p in px]
            ys = [p[1] for p in px]
            x0, x1 = max(0, int(min(xs))), min(cols - 1, int(max(xs)) + 1)
            y0, y1 = max(0, int(min(ys))), min(rows - 1, int(max(ys)) + 1)
            for r in range(y0, y1 + 1):
                for c in range(x0, x1 + 1):
                    if _inside(px, c + 0.5, r + 0.5):
                        grid[r][c] = ch[role]
    return "\n".join("".join(row) for row in grid)


def _inside(poly, x, y):
    n = len(poly)
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi):
            inside = not inside
        j = i
    return inside


if __name__ == "__main__":
    out_png = sys.argv[1] if len(sys.argv) > 1 else "/sdcard/Download/rabbit_hd.png"
    frames = all_frames()
    for _i in range(len(frames)):
        check_quads(_i)
    java = "/root/projects/nebula-ai/app/src/main/java/com/nebula/aichat/ui/RabbitGeometry.java"
    dump_java(frames, java)
    render_sheet(frames, out_png)
    for i, layers in enumerate(frames):
        print("\n===== frame %d =====  (空格空 / # 身体 / o 耳内&尾 / . 远腿 / @ 眼鼻)" % i)
        print(ascii_frame(layers))
