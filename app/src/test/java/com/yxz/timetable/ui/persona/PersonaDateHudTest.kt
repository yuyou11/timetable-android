package com.yxz.timetable.ui.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今天」页头部 HUD 的两段纯数学：时段文案的边界、月相受照比例。
 *
 * 画得像不像要在真机上看，但这两段算错了是文字性错误 ——
 * 半夜显示「上午」、十五画成新月，用户一眼就能指出来。
 */
class PersonaDateHudTest {

    @Test
    fun `时段按小时划分`() {
        assertEquals("深夜", PersonaDateHud.phaseOf(0))
        assertEquals("深夜", PersonaDateHud.phaseOf(4))
        assertEquals("清晨", PersonaDateHud.phaseOf(5))
        assertEquals("清晨", PersonaDateHud.phaseOf(7))
        assertEquals("上午", PersonaDateHud.phaseOf(8))
        assertEquals("上午", PersonaDateHud.phaseOf(10))
        assertEquals("中午", PersonaDateHud.phaseOf(11))
        assertEquals("中午", PersonaDateHud.phaseOf(12))
        assertEquals("下午", PersonaDateHud.phaseOf(13))
        assertEquals("下午", PersonaDateHud.phaseOf(16))
        assertEquals("黄昏", PersonaDateHud.phaseOf(17))
        assertEquals("黄昏", PersonaDateHud.phaseOf(18))
        assertEquals("夜晚", PersonaDateHud.phaseOf(19))
        assertEquals("夜晚", PersonaDateHud.phaseOf(22))
        assertEquals("深夜", PersonaDateHud.phaseOf(23))
    }

    @Test
    fun `月相跟着农历日走`() {
        assertEquals("初一是朔", 0f, PersonaDateHud.moonLitFraction(1), 0.01f)
        assertEquals("十五前后是望", 1f, PersonaDateHud.moonLitFraction(15), 0.03f)
        assertEquals("三十又是朔", 0f, PersonaDateHud.moonLitFraction(30), 0.01f)
        assertEquals("初八上弦，亮一半", 0.5f, PersonaDateHud.moonLitFraction(8), 0.05f)
        assertEquals("廿三下弦，亮一半", 0.5f, PersonaDateHud.moonLitFraction(23), 0.05f)
        assertTrue(
            "上弦（初八）比初五亮",
            PersonaDateHud.moonLitFraction(8) > PersonaDateHud.moonLitFraction(5)
        )
    }
}
