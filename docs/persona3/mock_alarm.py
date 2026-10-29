# -*- coding: utf-8 -*-
"""
P3 闹钟页效果图：上半深蓝 + 白色表盘，下半对话框样式卡片。

和 PersonaAlarmStyle.kt / PersonaClockView.kt 用同一套数值。

用法：python mock_alarm.py [输出.png]
"""
from __future__ import annotations

import datetime as dt
import math
import sys

from PIL import Image, ImageDraw, ImageFont

W, H = 900, 1600
SPLIT = 0.46

BLUE_TOP = (30, 167, 254)
BLUE_MID = (14, 73, 190)
BLUE_DEEP = (5, 36, 177)
CARD_BG = (250, 250, 252)
DISC_FILL = (27, 34, 112)
BLUE_LAYER = (72, 132, 255)
TITLE = (16, 30, 150)
DISC_STROKE = (111, 168, 255)
WHITE = (255, 255, 255)
DIM = (169, 190, 232)

ROMAN = ["XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI"]
SERIF = r"C:\Windows\Fonts\timesbd.ttf"
SANS = r"C:\Windows\Fonts\msyhbd.ttc"


def gradient(size, stops):
    w, h = size
    g = Image.new("RGB", (1, h))
    px = g.load()
    for y in range(h):
        t = y / max(1, h - 1)
        for i in range(len(stops) - 1):
            t0, c0 = stops[i]
            t1, c1 = stops[i + 1]
            if t0 <= t <= t1:
                k = (t - t0) / max(1e-6, t1 - t0)
                px[0, y] = tuple(int(c0[j] + (c1[j] - c0[j]) * k) for j in range(3))
                break
        else:
            px[0, y] = stops[-1][1]
    return g.resize((w, h), Image.BILINEAR)


def dialog_card(d, box):
    """两层：亮蓝斜底 + 深色不规则多边形（和 PersonaDialogBackground 同一套比例）"""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0

    def P(nx, ny):
        return (x0 + nx * w, y0 + ny * h)

    d.polygon([P(0.00, 0.10), P(0.99, 0.00), P(1.00, 0.93), P(0.02, 1.00)], fill=BLUE_LAYER)
    # 面板贴住右、下边：蓝底只在左边和上边露出来
    d.polygon([P(0.100, 0.17), P(0.955, 0.20), P(0.918, 0.34), P(0.912, 0.72),
               P(0.878, 1.00), P(0.560, 0.95), P(0.145, 0.93), P(0.072, 0.83)],
              fill=DISC_FILL, outline=DISC_STROKE, width=3)


def main(out="mock-alarm.png"):
    img = gradient((W, H), [(0.0, BLUE_TOP), (0.55, BLUE_MID), (1.0, BLUE_DEEP)])
    d = ImageDraw.Draw(img)
    split_y = int(H * SPLIT)
    d.polygon([(0, split_y), (W, split_y + int(H * 0.035)), (W, H), (0, H)], fill=CARD_BG)

    # ---------- 表盘（白色）----------
    cx, cy = W / 2, 360
    R = 300
    for radius, w in ((R * 0.955, R * 0.012), (R * 0.80, R * 0.010)):
        d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                  outline=WHITE, width=max(1, int(w)))

    fsize = int(R * 0.135)
    f = ImageFont.truetype(SERIF, fsize)
    for i in range(12):
        a = math.radians(i * 30 - 90)
        rn = R * 0.878
        x, y = cx + rn * math.cos(a), cy + rn * math.sin(a)
        tile = Image.new("RGBA", (fsize * 5, fsize * 4), (0, 0, 0, 0))
        td = ImageDraw.Draw(tile)
        td.text((tile.width / 2, tile.height / 2), ROMAN[i], font=f, fill=WHITE, anchor="mm")
        tile = tile.rotate(-(i * 30), resample=Image.BICUBIC, expand=False)
        base = img.convert("RGBA")
        base.alpha_composite(tile, (int(x - tile.width / 2), int(y - tile.height / 2)))
        img = base.convert("RGB")
    d = ImageDraw.Draw(img)

    for i in range(12):
        a = math.radians(i * 30 - 90)
        ux, uy = math.cos(a), math.sin(a)
        px, py = -uy, ux
        inner = R * 0.955
        ln = R * 0.16 if i == 0 else R * 0.10
        hw = R * 0.016
        d.polygon([
            (cx + ux * (inner + ln), cy + uy * (inner + ln)),
            (cx + ux * inner + px * hw, cy + uy * inner + py * hw),
            (cx + ux * inner - px * hw, cy + uy * inner - py * hw),
        ], fill=WHITE)

    now = dt.datetime.now()
    minute_angle = (now.minute + now.second / 60) * 6 - 90
    hour_angle = (now.hour % 12 + now.minute / 60) * 30 - 90
    second_angle = now.second * 6 - 90

    def spindle(angle_deg, length, half_w):
        a = math.radians(angle_deg)
        ux, uy = math.cos(a), math.sin(a)
        px, py = -uy, ux
        tail, shoulder = length * 0.11, length * 0.14
        d.polygon([
            (cx + ux * length, cy + uy * length),
            (cx + ux * shoulder + px * half_w, cy + uy * shoulder + py * half_w),
            (cx - ux * tail, cy - uy * tail),
            (cx + ux * shoulder - px * half_w, cy + uy * shoulder - py * half_w),
        ], fill=WHITE)

    spindle(hour_angle, R * 0.50, R * 0.034)
    spindle(minute_angle, R * 0.76, R * 0.024)

    a = math.radians(second_angle)
    ux, uy = math.cos(a), math.sin(a)
    d.line([(cx - ux * R * 0.14, cy - uy * R * 0.14),
            (cx + ux * R * 0.84, cy + uy * R * 0.84)],
           fill=(255, 255, 255, 180), width=max(1, int(R * 0.008)))

    d.ellipse([cx - R * 0.030, cy - R * 0.030, cx + R * 0.030, cy + R * 0.030], fill=WHITE)
    d.ellipse([cx - R * 0.013, cy - R * 0.013, cx + R * 0.013, cy + R * 0.013], fill=BLUE_MID)

    # ---------- 下一次响铃 ----------
    f_next = ImageFont.truetype(SANS, 26)
    d.text((W / 2, 700), "下次响铃：明天 06:40", font=f_next, fill=WHITE, anchor="mm")

    # ---------- 卡片 ----------
    f_head = ImageFont.truetype(SANS, 46)
    f_sub = ImageFont.truetype(SANS, 24)
    cards = [
        ("06:40", "周一 周二 周三 周四 周五", "下次：明天 06:40"),
        ("07:10", "只响一次 · 10 月 24 日", "下次：10 月 24 日 07:10"),
        ("课前 10 分钟", "高等数学 · 周一第 1-2 节", "下次：周一 08:00"),
    ]
    y = split_y + 40
    for head, sub, nxt in cards:
        box = (60, y, W - 60, y + 200)
        dialog_card(d, box)
        # 主信息装在白色圆角条里
        tw = d.textlength(head, font=f_head)
        bar = (box[0] + 52, box[1] + 40, box[0] + 52 + tw + 44, box[1] + 40 + 60)
        d.rounded_rectangle(bar, radius=10, fill=WHITE)
        d.text((bar[0] + 22, bar[1] + 31), head, font=f_head, fill=TITLE, anchor="lm")
        d.text((box[0] + 46, box[1] + 132), sub, font=f_sub, fill=DIM)
        d.text((box[0] + 46, box[1] + 166), nxt, font=f_sub, fill=DIM)
        y += 224

    img.save(out)
    print("saved", out, img.size)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "mock-alarm.png")
