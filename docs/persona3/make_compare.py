# -*- coding: utf-8 -*-
"""
把「游戏截图」和「效果图」并排拼一张，方便一眼比对。

用法：
    python make_compare.py
输出：
    对比图.png
"""
from PIL import Image, ImageDraw, ImageFont

REF = r"ref\日历.png"
MOCK = r"mock-calendar.png"
OUT = r"对比图.png"

TARGET_H = 1400          # 两张图统一缩放到这个高度
GAP = 40
LABEL_H = 64

FONT = r"C:\Windows\Fonts\msyhbd.ttc"
LABEL_FONT = ImageFont.truetype(FONT, 34)


def load(path, box=None):
    im = Image.open(path).convert("RGB")
    if box:
        im = im.crop(box)
    ratio = TARGET_H / im.height
    return im.resize((int(im.width * ratio), TARGET_H), Image.LANCZOS)


# 游戏截图上下有窗口黑边，裁掉
ref = load(REF, box=(0, 95, 1922, 1200))
mock = load(MOCK)

pad = 30
W = pad + ref.width + GAP + mock.width + pad
H = LABEL_H + TARGET_H + pad
canvas = Image.new("RGB", (W, H), (18, 18, 22))
d = ImageDraw.Draw(canvas)

d.text((pad, 18), "游戏原图", font=LABEL_FONT, fill=(235, 235, 240))
d.text((pad + ref.width + GAP, 18), "手机竖屏效果图", font=LABEL_FONT, fill=(235, 235, 240))

canvas.paste(ref, (pad, LABEL_H))
canvas.paste(mock, (pad + ref.width + GAP, LABEL_H))
canvas.save(OUT)
print("saved", OUT, canvas.size)
