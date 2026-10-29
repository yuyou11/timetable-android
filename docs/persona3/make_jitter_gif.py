# -*- coding: utf-8 -*-
"""
把「今天纸片」的抖动做成动图，用来核对效果 —— 静态截图看不出这个。

抖动模型和 PersonaCalendarView.jitterTick 完全一致：
每 72ms 一拍，每个顶点有 30% 概率换一个 ±1.5dp 的新目标，
再以 0.18 的系数朝目标插值。所以慢、且不会跳。

用法：python make_jitter_gif.py
输出：today-jitter.gif
"""
import random

from PIL import Image

import mock_calendar as mc

FRAMES = 32
TICK_MS = 72
AMP = 3.0
P_NEW_TARGET = 0.32
LERP = 0.22

# 今天那块纸片在整图里的位置（April 2026, 22 号：第 4 行第 4 列）
CENTER_X = mc.dp(mc.PAD + 3 * (mc.W_DP - mc.PAD * 2) / 7 + (mc.W_DP - mc.PAD * 2) / 14)
CENTER_Y = mc.dp(mc.GRID_TOP + 3 * mc.ROW_H + mc.ROW_H / 2)
HALF_W, HALF_H = 210, 170


def main():
    rnd = random.Random(20090422)   # 固定种子，方便复现同一段抖动
    jitter = [0.0] * 6
    target = [0.0] * 6
    frames = []

    for _ in range(FRAMES):
        for i in range(6):
            if rnd.random() < P_NEW_TARGET:
                target[i] = (rnd.random() * 2 - 1) * AMP
            jitter[i] += (target[i] - jitter[i]) * LERP

        img = mc.build(jitter=tuple(jitter)).convert("RGB")
        box = (CENTER_X - HALF_W, CENTER_Y - HALF_H,
               CENTER_X + HALF_W, CENTER_Y + HALF_H)
        frames.append(img.crop(box).resize((HALF_W * 2, HALF_H * 2), Image.LANCZOS))

    frames[0].save(
        "today-jitter.gif",
        save_all=True,
        append_images=frames[1:],
        duration=TICK_MS,
        loop=0,
        optimize=True,
    )
    print("saved today-jitter.gif", len(frames), "frames")


if __name__ == "__main__":
    main()
