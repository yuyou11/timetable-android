# -*- coding: utf-8 -*-
"""把复刻结果和游戏原图拼成一张对比图。

用法：
    python make_compare.py <复刻图.png> <输出.png> [放大倍数]

原图 ref/今天.png 是 586×231 的视频帧，上面 48px 是黑边；
复刻的是去掉黑边之后的 586×183，所以先把原图裁掉黑边再拼。

复刻图如果已经是 2 倍（1172×366），就原样贴上去 —— 不要再放大一次，
否则硬边会被重采样糊掉，看着像加了模糊。
"""
import sys
from PIL import Image, ImageDraw, ImageFont

HERE = r"E:\SharkData\projects\06-android-timetable-persona3\p3web"
REF = r"E:\SharkData\projects\06-android-timetable-persona3\docs\persona3\ref\今天.png"
LABEL_FONT = r"C:\Windows\Fonts\msyhbd.ttc"


def main():
    shot = sys.argv[1]
    out = sys.argv[2]
    k = int(sys.argv[3]) if len(sys.argv) > 3 else 1

    ref = Image.open(REF).convert("RGB").crop((0, 48, 586, 231))
    mine = Image.open(shot).convert("RGB")

    if k != 1:
        ref = ref.resize((ref.width * k, ref.height * k), Image.LANCZOS)
        # 只有尺寸不够时才放大，已经够大就用原像素
        if mine.width < ref.width:
            mine = mine.resize((mine.width * k, mine.height * k), Image.LANCZOS)

    pad, bar = 18 * k, 34 * k
    W = max(ref.width, mine.width) + pad * 2
    H = bar + ref.height + pad + bar + mine.height + pad
    canvas = Image.new("RGB", (W, H), (16, 18, 26))
    d = ImageDraw.Draw(canvas)
    font = ImageFont.truetype(LABEL_FONT, 15 * k)

    y = pad // 2
    d.text((pad, y), "原图  ref/今天.png（已裁掉黑边）", font=font, fill=(150, 160, 190))
    y += bar
    canvas.paste(ref, (pad, y))
    d.rectangle([pad - 1, y - 1, pad + ref.width, y + ref.height], outline=(60, 70, 100))

    y += ref.height + pad
    d.text((pad, y), "复刻  p3web/today-hud.html（Edge 无头截图，原生像素）", font=font,
           fill=(150, 160, 190))
    y += bar
    canvas.paste(mine, (pad, y))
    d.rectangle([pad - 1, y - 1, pad + mine.width, y + mine.height], outline=(60, 70, 100))

    canvas.save(out)
    print("saved", out, canvas.size)


if __name__ == "__main__":
    main()
