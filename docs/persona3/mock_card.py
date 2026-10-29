# -*- coding: utf-8 -*-
"""
P3 对话框卡片的形状样张（第二版）。

相对第一版的变化：
  · 蓝底变浅；
  · 面板右边收窄（右侧留出蓝底）；
  · 右上角、左下角各切一刀；
  · 上边往右多伸一点；
  · 字体从窄体换成宽体重体。

用法：python mock_card.py [输出.png]
"""
from __future__ import annotations

import sys

from PIL import Image, ImageDraw, ImageFont

W, H = 760, 240
BLUE = (72, 132, 255)          # 浅一档的蓝底
NAVY = (27, 34, 112)
STROKE = (70, 96, 220)
WHITE = (255, 255, 255)
TITLE = (16, 30, 150)

SANS = r"C:\Windows\Fonts\msyhbd.ttc"


def pts(box, norm):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    return [(x0 + nx * w, y0 + ny * h) for nx, ny in norm]


def main(out="mock-card.png"):
    img = Image.new("RGB", (W, H), (235, 236, 240))
    d = ImageDraw.Draw(img)
    box = (10, 6, W - 10, H - 6)

    # 1) 浅蓝斜底
    d.polygon(pts(box, [(0.00, 0.10), (0.97, 0.00), (0.99, 0.93), (0.02, 1.00)]), fill=BLUE)

    # 2) 深色面板：左上斜切 + 右上切角 + 左下切角 + 右下尖尾
    panel = pts(box, [
        (0.100, 0.17),   # 左上（斜切后）
        (0.955, 0.20),   # 上边右端（往右伸）
        (0.918, 0.34),   # 右上切角的下端（小切角）
        (0.912, 0.72),   # 右侧（收窄）
        (0.878, 1.00),   # 尖尾
        (0.560, 0.95),   # 底边右段
        (0.145, 0.93),   # 底边左端
        (0.072, 0.83),   # 左下切角的上端
    ])
    d.polygon(panel, fill=NAVY, outline=STROKE, width=2)

    # 3) 白色圆角文字条
    x0, y0, x1, y1 = box
    bw, bh = x1 - x0, y1 - y0
    bar = (x0 + bw * 0.19, y0 + bh * 0.22, x0 + bw * 0.83, y0 + bh * 0.50)
    d.rounded_rectangle(bar, radius=8, fill=WHITE)

    f = ImageFont.truetype(SANS, 27)
    d.text(((bar[0] + bar[2]) / 2, (bar[1] + bar[3]) / 2), "只有我刚来的时候",
           font=f, fill=TITLE, anchor="mm")
    f2 = ImageFont.truetype(SANS, 22)
    d.text((x0 + bw * 0.5, y0 + bh * 0.70), "如你所见", font=f2, fill=WHITE, anchor="mm")

    img.save(out)
    print("saved", out, img.size)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "mock-card.png")
