# -*- coding: utf-8 -*-
"""
Persona 3 风格日历页 —— 静态效果图生成器。

作用：在写 Kotlin 之前，先把版式和配色定下来。
坐标全部用 dp 写，和以后 Kotlin 里自绘 Canvas 的常量一一对应
（density 2.75，即 1080 / 393），避免「画出来和实现不一样」。

用法：
    python mock_calendar.py [输出.png]
"""
from __future__ import annotations

import calendar
import datetime as dt
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---------------------------------------------------------------- 画布
SCALE = 2.75                      # dp -> px，和 Pixel 7 / 1080p 一档一致
W_DP, H_DP = 393, 800             # 手机可用区域（不含状态栏 / 底部导航）
W, H = int(W_DP * SCALE), int(H_DP * SCALE)

# ---------------------------------------------------------------- 配色
# 全部取样自 docs/persona3/ref/日历.png
BG_TOP = (30, 167, 254)      # #1EA7FE 上缘的亮蓝
BG_MID = (14, 73, 190)       # #0E49BE
BG_DEEP = (5, 36, 177)       # #0524B1 底部深蓝
INK = (1, 13, 100)           # #010D64 巨号数字 / 今天数字的深墨蓝
RED = (240, 16, 48)          # #F01030 周日
MAGENTA = (255, 63, 216)     # #FF3FD8 今天那块三角纸片
CYAN = (24, 216, 248)        # #18D8F8 周六
CYAN_BAR = (72, 248, 248)    # #48F8F8 日期条
GOLD = (144, 136, 56)        # #908838 选中日金环
WHITE = (255, 255, 255)
PAPER = (234, 246, 255)      # 普通日期的白（略偏冷）
GRAY = (150, 156, 162)

# ---------------------------------------------------------------- 版式
PAD = 14
WEEKDAY_Y = 96
GRID_TOP = 122
ROW_H = 56
TITLE_SIZE = 40
WATERMARK_SIZE = 200

# 白纸的上下关系：整条弧线都压在网格下面，右端比左端高 ——
# 先定白纸再塞网格会让弧线切掉最后一排日期（真机上出过这个 bug）
GRID_BOTTOM = GRID_TOP + 6 * ROW_H
PAPER_LEFT = GRID_BOTTOM + 78
PAPER_RIGHT = GRID_BOTTOM + 16

FONT_DIR = r"C:\Windows\Fonts"
F_IMPACT = FONT_DIR + r"\impact.ttf"
F_BLACK = FONT_DIR + r"\ariblk.ttf"
F_YAHEI_B = FONT_DIR + r"\msyhbd.ttc"
F_YAHEI = FONT_DIR + r"\msyh.ttc"


def font(path: str, size_dp: float) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, int(size_dp * SCALE))


def dp(v: float) -> int:
    return int(round(v * SCALE))


# ---------------------------------------------------------------- 背景
def vertical_gradient(size, stops) -> Image.Image:
    """stops: [(0.0, rgb), (0.5, rgb), (1.0, rgb)]"""
    w, h = size
    grad = Image.new("RGB", (1, h))
    px = grad.load()
    for y in range(h):
        t = y / max(1, h - 1)
        # 找到所在区间
        for i in range(len(stops) - 1):
            t0, c0 = stops[i]
            t1, c1 = stops[i + 1]
            if t0 <= t <= t1:
                k = (t - t0) / max(1e-6, t1 - t0)
                px[0, y] = tuple(int(c0[j] + (c1[j] - c0[j]) * k) for j in range(3))
                break
        else:
            px[0, y] = stops[-1][1]
    return grad.resize((w, h), Image.BILINEAR)


def draw_background() -> Image.Image:
    img = vertical_gradient((W, H), [(0.0, BG_TOP), (0.42, BG_MID), (1.0, BG_DEEP)]).convert("RGBA")

    # 水彩感：中间偏下压一团深墨蓝的柔光，四周提亮，避免纯渐变的塑料味
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([dp(-90), dp(120), dp(300), dp(520)], fill=INK + (120,))
    gd.ellipse([dp(150), dp(-120), dp(560), dp(300)], fill=(90, 200, 255, 46))
    glow = glow.filter(ImageFilter.GaussianBlur(dp(46)))
    img = Image.alpha_composite(img, glow)

    # 白色大弧：左低右高，整条都落在网格下面，游戏里那块白的分界线
    paper = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    pd = ImageDraw.Draw(paper)
    p0 = (0.0, dp(PAPER_LEFT))
    c1 = (W * 0.34, dp(PAPER_LEFT - 8))
    c2 = (W * 0.66, dp(PAPER_RIGHT + 14))
    p3 = (float(W), dp(PAPER_RIGHT))
    pts = []
    for i in range(61):
        t = i / 60
        u = 1 - t
        x = u ** 3 * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * p3[0]
        y = u ** 3 * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * p3[1]
        pts.append((x, y))
    pd.polygon(pts + [(W, H), (0, H)], fill=WHITE)
    return Image.alpha_composite(img, paper)


# ---------------------------------------------------------------- 文字工具
def text_size(draw, text, fnt):
    box = draw.textbbox((0, 0), text, font=fnt)
    return box[2] - box[0], box[3] - box[1], box


def draw_skewed_text(base: Image.Image, xy, text, fnt, fill, *, outline=None,
                     outline_w=0, skew=-0.20, anchor_left=True):
    """
    把文字画到独立图层再横向切变，模拟游戏里那种倾斜的重体字。
    返回占用的宽度，方便排下一行。
    """
    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    tw, th, box = text_size(probe, text, fnt)
    pad = int(th * 0.35)
    layer = Image.new("RGBA", (tw + pad * 2 + max(1, outline_w) * 2,
                               th + pad * 2 + max(1, outline_w) * 2), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ox = pad - box[0]
    oy = pad - box[1]
    if outline and outline_w:
        for dx in range(-outline_w, outline_w + 1):
            for dy in range(-outline_w, outline_w + 1):
                if dx * dx + dy * dy <= outline_w * outline_w:
                    ld.text((ox + dx, oy + dy), text, font=fnt, fill=outline)
    ld.text((ox, oy), text, font=fnt, fill=fill)

    # 横向切变：x' = x + skew * (y - 底边)
    k = skew
    layer = layer.transform(
        layer.size, Image.AFFINE,
        (1, -k, k * layer.size[1], 0, 1, 0),
        resample=Image.BICUBIC,
    )
    x = dp(xy[0]) if anchor_left else dp(xy[0]) - layer.size[0]
    y = dp(xy[1])
    base.alpha_composite(layer, (int(x), int(y)))
    return layer.size[0] / SCALE


# ---------------------------------------------------------------- 今天 / 选中标记
def torn_shape(draw: ImageDraw.ImageDraw, cx, cy, w, h, fill, seed=0, slant=0.22):
    """带毛边的撕纸块（今天的粉色斜块）。用确定性的锯齿，不随机。"""
    jag = [0, 1.6, -0.9, 2.2, 0.4, -1.5, 1.1, 0]
    left = cx - w / 2
    right = cx + w / 2
    top = cy - h / 2
    bot = cy + h / 2

    def shear(y):                       # 平行四边形：越靠下越向左偏
        return -(y - cy) * slant

    pts = []
    n = 6
    for i in range(n):                  # 上边，左→右
        t = i / (n - 1)
        y = top + jag[(i + seed) % len(jag)]
        pts.append((left + t * w + shear(y), y))
    for i in range(4):                  # 右边，上→下
        t = i / 3
        pts.append((right + shear(top + t * h) + jag[(i + seed + 3) % len(jag)], top + t * h))
    for i in range(n):                  # 下边，右→左
        t = i / (n - 1)
        y = bot + jag[(i + seed + 5) % len(jag)]
        pts.append((right - t * w + shear(y), y))
    for i in range(4):                  # 左边，下→上
        t = i / 3
        pts.append((left + shear(bot - t * h) - jag[(i + seed + 2) % len(jag)], bot - t * h))

    draw.polygon(pts, fill=fill)


# ---------------------------------------------------------------- 主绘制
def build(year=2026, month=4, today=22, selected=22, jitter=(0.0,) * 6):
    """画一帧，返回 RGBA 图。[jitter] 是今天纸片三个顶点的偏移（dp）。"""
    img = draw_background()
    d = ImageDraw.Draw(img)

    # ---- 顶部标题 ----
    title_font = font(F_IMPACT, TITLE_SIZE)
    draw_skewed_text(img, (PAD, 14), "CALENDAR", title_font, WHITE,
                     outline=INK, outline_w=dp(1.6), skew=-0.22)

    year_font = font(F_IMPACT, 12)
    draw_skewed_text(img, (PAD + 150, 58), f"{year}", year_font, WHITE,
                     outline=INK, outline_w=dp(0.8), skew=-0.22)

    # ---- 右上角翻月按钮（游戏里那对 Q / E 方块的玩法）----
    btn_y = dp(18)
    for i, ch in enumerate(("‹", "›")):
        bx = W - dp(PAD) - dp(64) + i * dp(34)
        d.rounded_rectangle([bx, btn_y, bx + dp(28), btn_y + dp(28)],
                            radius=dp(3), outline=WHITE, width=dp(1.3))
        d.text((bx + dp(14), btn_y + dp(13)), ch,
               font=font(F_YAHEI_B, 17), fill=WHITE, anchor="mm")

    # ---- 巨号月份水印（压在网格后面，只留一个，别抢日期）----
    # ---- 巨号数字不放在网格背后：竖屏七列占满宽度，没有游戏里那块空地，
    #      压上去只会糊住日期。改放到白纸上（原图的「5」本来就骑在白纸上）----

    # ---- 星期表头（游戏里那条白色撕纸条）----
    strip = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(strip)
    sy = dp(WEEKDAY_Y - 6)
    sh = dp(24)
    jag_top = [0, -1.6, 1.2, 0.4, -0.8, 1.6, 0, -1.2, 0.8, 0]
    jag_bot = [0, 1.2, -0.8, 1.6, 0, -1.4, 0.6, 0, 1.4, -0.6]
    n = 14
    top = [(dp(PAD - 5) + i * dp((W_DP - PAD * 2 + 10) / (n - 1)), sy + dp(jag_top[i % 10]))
           for i in range(n)]
    bot = [(dp(PAD - 5) + i * dp((W_DP - PAD * 2 + 10) / (n - 1)),
            sy + sh + dp(jag_bot[i % 10])) for i in range(n - 1, -1, -1)]
    sd.polygon(top + bot, fill=WHITE + (132,))
    img.alpha_composite(strip)

    wk_font = font(F_YAHEI_B, 12.5)
    names = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"]
    col_w = (W_DP - PAD * 2) / 7
    today_col = (dt.date(year, month, today).weekday() + 1) % 7
    for i, name in enumerate(names):
        lx = dp(PAD + i * col_w + 2)
        if i == today_col:
            # 今天那一列的星期名压在白纸标签上、字换成深墨蓝（原图里 THU 的样子）
            jags = [0, 1.0, -0.6, 1.4, 0, -1.0, 0.6]
            tag = [(lx - dp(3) + dp((col_w - 6) * k / 5), dp(90) + dp(jags[k % len(jags)]))
                   for k in range(6)]
            tag += [(lx - dp(3) + dp(col_w - 6), dp(114)), (lx - dp(3), dp(114))]
            d.polygon(tag, fill=WHITE + (240,))
            color = INK
        else:
            color = RED if i == 0 else (CYAN if i == 6 else WHITE)
        d.text((lx, dp(WEEKDAY_Y - 4)), name, font=wk_font, fill=color)

    # ---- 日期网格 ----
    first = dt.date(year, month, 1)
    start = first - dt.timedelta(days=(first.weekday() + 1) % 7)   # 周日为准
    date_font = font(F_BLACK, 23)
    today_font = font(F_BLACK, 25)
    tag_font = font(F_IMPACT, 7.5)

    for r in range(6):
        for c in range(7):
            cur = start + dt.timedelta(days=r * 7 + c)
            cx = dp(PAD + c * col_w + col_w / 2)
            cy = dp(GRID_TOP + r * ROW_H + ROW_H / 2)
            in_month = (cur.year == year and cur.month == month)
            is_today = (cur.year == year and cur.month == month and cur.day == today)

            if is_today:
                # P3 的 TODAY 是一块三角形纸片（从原图量出来的三个顶点），
                # 不是「撕纸矩形」—— 三条直边、右下是尖角、没有毛刺。
                # 三个顶点各自带一点抖动偏移（真机上是一直在慢慢飘的）
                sh = dp(ROW_H * 1.06)        # 纸片高度跟行高走（原图 ≈ 行距的 1.06 倍）
                sw = sh * 1.32               # 宽高比 1.32
                num_cy = cy - dp(2)          # 和其他日期同一个中心
                left = cx - sw / 2
                top = num_cy - 0.42 * sh     # 数字中心落在纸片高度 37% 处（真机偏低，上移到 42%）
                jt = jitter
                d.polygon([(left + 0.54 * sw + dp(jt[0]), top + dp(jt[1])),
                           (left + sw + dp(jt[2]), top + 0.67 * sh + dp(jt[3])),
                           (left + 0.13 * sw + dp(jt[4]), top + 0.95 * sh + dp(jt[5]))],
                          fill=MAGENTA)

                # TODAY 压在纸片身上（原图：T/O 在蓝底，D/A/Y 压在粉色上，顶点从字母中间探出来）
                cap = 23 * 0.72
                baseline = num_cy - dp(cap / 2) - dp(1.5)
                d.text((cx, baseline), "TODAY", font=tag_font, fill=WHITE, anchor="ms")
                d.text((cx, num_cy), str(cur.day), font=date_font,
                       fill=(0, 0, 0), anchor="mm")
                continue

            color = RED if c == 0 else (CYAN if c == 6 else PAPER)
            alpha = 255 if in_month else 64
            d.text((cx, cy - dp(2)), str(cur.day), font=date_font, fill=color + (alpha,),
                   anchor="mm")

            if in_month and cur.day in (19, 29):     # 有日程的小圆点
                d.ellipse([cx - dp(1.6), cy + dp(13), cx + dp(1.6), cy + dp(16.2)],
                          fill=WHITE + (235,))

    # ---- 选中日：金环 ----
    if selected != today:
        sr = (selected - 1 + (dt.date(year, month, 1).weekday() + 1) % 7)
        rr, cc = divmod(sr, 7)
        if rr < 6:
            cx = dp(PAD + cc * col_w + col_w / 2)
            cy = dp(GRID_TOP + rr * ROW_H + ROW_H / 2)
            rad = dp(19)
            d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad],
                      outline=GOLD + (255,), width=dp(1.4))

    # ---- 白色面板上的信息 ----
    bar_y = dp(PAPER_LEFT + 18)
    bar_h = dp(26)
    bar_w = dp(158)
    d.rectangle([dp(20), bar_y, dp(20) + bar_w, bar_y + bar_h], fill=CYAN_BAR)

    bar_font = font(F_YAHEI_B, 14.5)
    d.text((dp(20) + dp(10), bar_y + bar_h / 2), f"{month}月{selected}日（三）",
           font=bar_font, fill=INK, anchor="lm")

    bullet_font = font(F_YAHEI, 12)
    d.text((dp(28), bar_y + dp(40)), "· 没什么特别的事", font=bullet_font, fill=GRAY)
    d.text((dp(28), bar_y + dp(62)), "· 农历三月初六", font=bullet_font, fill=GRAY)

    # 白纸上那个巨号日期（原图「5」的位置），右对齐。
    # 字号按「日期条下沿到 Guide 上沿」的可用高度收缩，矮屏不至于压到 Guide
    guide_y = H - dp(46)
    num_top = bar_y + dp(50)
    room = guide_y - num_top - dp(14)
    num_size = max(56, min(150, room / 1.16))
    big_font = font(F_BLACK, num_size)
    tw, th, box = text_size(d, str(selected), big_font)
    d.text((W - dp(16), num_top + th), str(selected), font=big_font,
           fill=INK + (238,), anchor="rs")

    guide_font = font(F_YAHEI, 12)
    guide = "要查看什么时候的日程安排？"
    gw, gh, _ = text_size(d, guide, guide_font)
    gx = W - dp(22) - gw
    ty = (guide_y - gh) / SCALE
    d.text((gx, dp(ty)), guide, font=guide_font, fill=(90, 110, 130))
    d.line([(gx, dp(ty) + gh + dp(1.5)), (gx + gw, dp(ty) + gh + dp(1.5))],
           fill=(90, 110, 130), width=max(1, dp(0.5)))
    d.text((gx, dp(ty) + gh + dp(3)), "Guide", font=font(F_IMPACT, 7), fill=(120, 140, 160))

    return img


def render(path: str, **kw):
    img = build(**kw)
    img.convert("RGB").save(path, quality=95)
    print("saved", path, img.size)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "persona3-calendar-mock.png"
    render(out)
