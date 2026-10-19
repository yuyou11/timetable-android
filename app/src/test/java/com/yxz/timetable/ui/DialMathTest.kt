package com.yxz.timetable.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 表盘几何的测试。
 *
 * 这些是全屏手势里最容易错、又最难在真机上看出来的东西：
 * 指针指着的方向和选中的数字差一格，用户只会在早上迷迷糊糊时觉得「不对劲」。
 *
 * 约定：0° 在 12 点方向，顺时针为正。
 */
class DialMathTest {

    @Test
    fun `值换算成角度 0 在 12 点 顺时针`() {
        assertEquals(0f, DialMath.angleOf(0, 24), 0.001f)
        assertEquals("6 点整 = 正右", 90f, DialMath.angleOf(6, 24), 0.001f)
        assertEquals(180f, DialMath.angleOf(12, 24), 0.001f)
        assertEquals(270f, DialMath.angleOf(18, 24), 0.001f)
        assertEquals("24 格每格 15°", 105f, DialMath.angleOf(7, 24), 0.001f)
    }

    @Test
    fun `触摸点换算成角度`() {
        assertEquals("正上方 = 0°", 0f, DialMath.angleOfTouch(50f, 0f, 50f, 50f), 0.001f)
        assertEquals("正右方 = 90°", 90f, DialMath.angleOfTouch(100f, 50f, 50f, 50f), 0.001f)
        assertEquals("正下方 = 180°", 180f, DialMath.angleOfTouch(50f, 100f, 50f, 50f), 0.001f)
        assertEquals("正左方 = 270°", 270f, DialMath.angleOfTouch(0f, 50f, 50f, 50f), 0.001f)
    }

    @Test
    fun `角度落在最近的一格`() {
        assertEquals(0, DialMath.cellAt(0f, 24))
        assertEquals(0, DialMath.cellAt(7f, 24))
        assertEquals("过了半格就进位", 1, DialMath.cellAt(8f, 24))
        assertEquals(7, DialMath.cellAt(105f, 24))
        assertEquals(7, DialMath.cellAt(112f, 24))
        assertEquals(8, DialMath.cellAt(113f, 24))
    }

    @Test
    fun `12 点两侧的接缝都落在 0 附近`() {
        // 第 0 格覆盖 352.5°–367.5°，所以 12 点左边一点点也算第 0 格
        assertEquals(23, DialMath.cellAt(352f, 24))
        assertEquals(0, DialMath.cellAt(353f, 24))
        assertEquals(0, DialMath.cellAt(359.9f, 24))
        assertEquals(0, DialMath.cellAt(0.1f, 24))
        assertEquals("负角度也要归一化", 23, DialMath.cellAt(-10f, 24))
        assertEquals(0, DialMath.cellAt(360f, 24))
    }

    @Test
    fun `分钟盘 60 格`() {
        assertEquals(0, DialMath.cellAt(0f, 60))
        assertEquals("35 分 = 210°", 35, DialMath.cellAt(210f, 60))
        assertEquals(59, DialMath.cellAt(356f, 60))
        assertEquals(6f, DialMath.angleOf(1, 60), 0.001f)
    }

    @Test
    fun `每一格都能原样换算回去`() {
        for (cells in intArrayOf(24, 60)) {
            for (v in 0 until cells) {
                assertEquals(
                    "$cells 格的 $v",
                    v, DialMath.cellAt(DialMath.angleOf(v, cells), cells)
                )
            }
        }
    }

    @Test
    fun `吸附走最短路 不会倒着扫大半圈`() {
        assertEquals("350° → 10° 只该走 +20°", 20f, DialMath.shortestDelta(350f, 10f), 0.001f)
        assertEquals("10° → 350° 只该走 -20°", -20f, DialMath.shortestDelta(10f, 350f), 0.001f)
        assertEquals(0f, DialMath.shortestDelta(100f, 100f), 0.001f)
        assertEquals(180f, Math.abs(DialMath.shortestDelta(0f, 180f)), 0.001f)
    }
}
