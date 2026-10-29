# -*- coding: utf-8 -*-
"""
按 PersonaClockView.kt 的同一套几何渲染一张静态图，用来先看效果。

白底、无辉光、带秒针，与 Kotlin 实现一比一。

用法：python mock_clock.py [输出.png]
"""
from __future__ import annotations

import datetime as dt
import math
import sys

from PIL import Image, ImageDraw, ImageFont

SIZE = 900
R_RATIO = 0.86

NEON = (47, 98, 255)
SECOND = (122, 150, 255)
PAGE = (255, 255, 255)

ROMAN = ["XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI"]

FONT_PATH = r"C:\Windows\Fonts\timesbd.ttf"


def main(out: str = "mock-clock.png"):
    img = Image.new("RGB", (SIZE, SIZE), PAGE)
    d = ImageDraw.Draw(img)
    cx = cy = SIZE / 2
    R = min(SIZE, SIZE) / 2 * R_RATIO

    # ---- 双圈 ----
    for radius, width in ((R * 0.955, R * 0.012), (R * 0.80, R * 0.010)):
        d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                  outline=NEON, width=max(1, int(width)))

    # ---- 罗马数字（随角度旋转）----
    size = int(R * 0.135)
    f = ImageFont.truetype(FONT_PATH, size)
    for i in range(12):
        a = math.radians(i * 30 - 90)
        rn = R * 0.878
        x = cx + rn * math.cos(a)
        y = cy + rn * math.sin(a)

        tile = Image.new("RGBA", (size * 5, size * 4), (0, 0, 0, 0))
        td = ImageDraw.Draw(tile)
        td.text((tile.width / 2, tile.height / 2), ROMAN[i], font=f, fill=NEON, anchor="mm")
        tile = tile.rotate(-(i * 30), resample=Image.BICUBIC, expand=False)

        base = img.convert("RGBA")
        base.alpha_composite(tile, (int(x - tile.width / 2), int(y - tile.height / 2)))
        img = base.convert("RGB")
    d = ImageDraw.Draw(img)

    # ---- 圈外尖刺 ----
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
        ], fill=NEON)

    # ---- 指针 ----
    now = dt.datetime.now()
    minute_angle = (now.minute + now.second / 60) * 6 - 90
    hour_angle = (now.hour % 12 + now.minute / 60) * 30 - 90
    second_angle = now.second * 6 - 90

    def spindle(angle_deg, length, half_w):
        a = math.radians(angle_deg)
        ux, uy = math.cos(a), math.sin(a)
        px, py = -uy, ux
        tail = length * 0.11
        shoulder = length * 0.14
        d.polygon([
            (cx + ux * length, cy + uy * length),
            (cx + ux * shoulder + px * half_w, cy + uy * shoulder + py * half_w),
            (cx - ux * tail, cy - uy * tail),
            (cx + ux * shoulder - px * half_w, cy + uy * shoulder - py * half_w),
        ], fill=NEON)

    spindle(hour_angle, R * 0.50, R * 0.034)
    spindle(minute_angle, R * 0.76, R * 0.024)

    # 秒针
    a = math.radians(second_angle)
    ux, uy = math.cos(a), math.sin(a)
    d.line([(cx - ux * R * 0.14, cy - uy * R * 0.14),
            (cx + ux * R * 0.84, cy + uy * R * 0.84)],
           fill=SECOND, width=max(1, int(R * 0.008)))

    # 轴心
    d.ellipse([cx - R * 0.030, cy - R * 0.030, cx + R * 0.030, cy + R * 0.030], fill=NEON)
    d.ellipse([cx - R * 0.013, cy - R * 0.013, cx + R * 0.013, cy + R * 0.013], fill=PAGE)

    img.save(out)
    print("saved", out, img.size)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "mock-clock.png")
