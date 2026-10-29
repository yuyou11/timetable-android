package com.yxz.timetable.ui.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 课表页的纵向算术：一行多高、能滚多远。
 *
 * 这几条全是「错了不崩」的：算小了字挤成一团、算大了最后一行滚不到底。
 * 装个 APK 用眼睛很难发现，所以钉在 JVM 上跑。
 */
class PersonaWeekLayoutTest {

    /** 一个典型的网格区高度：360×800dp 的机器，扣掉表头和密度条之后剩这么多 */
    private val avail = 1290f

    // ---------- 一、装得下时刚好铺满 ----------

    @Test
    fun `十节：一行 = 可用高度除以十，整表正好铺满`() {
        val rowH = PersonaWeekLayout.rowHeight(avail, 10)
        assertEquals(avail / 10f, rowH, 0.01f)
        assertEquals("铺满就不该有滚动余量", 0f, PersonaWeekLayout.scrollRange(avail, 10, rowH), 0.01f)
    }

    @Test
    fun `十三节：照样铺满，不滚动`() {
        val rowH = PersonaWeekLayout.rowHeight(avail, 13)
        assertEquals(avail / 13f, rowH, 0.01f)
        assertEquals(0f, PersonaWeekLayout.scrollRange(avail, 13, rowH), 0.01f)
    }

    @Test
    fun `节数越少行越高：五节的一行比十节高一倍`() {
        val five = PersonaWeekLayout.rowHeight(avail, 5)
        val ten = PersonaWeekLayout.rowHeight(avail, 10)
        assertEquals(five, ten * 2f, 0.01f)
    }

    // ---------- 二、超过十七节：锁行高、改为滚动 ----------

    @Test
    fun `十七节仍然铺满，是能压的最后一行`() {
        val rowH = PersonaWeekLayout.rowHeight(avail, PersonaWeekLayout.MAX_FIT_ROWS)
        assertEquals(avail / 17f, rowH, 0.01f)
        assertEquals(0f, PersonaWeekLayout.scrollRange(avail, 17, rowH), 0.01f)
    }

    @Test
    fun `十八节：行高按十七行算，多出来的一行就是可滚距离`() {
        val rowH = PersonaWeekLayout.rowHeight(avail, 18)
        assertEquals("超过 17 就不再压行高", avail / 17f, rowH, 0.01f)
        assertEquals(avail / 17f, PersonaWeekLayout.scrollRange(avail, 18, rowH), 0.01f)
    }

    @Test
    fun `二十节：行高和十七节一样，滚三行`() {
        val row17 = PersonaWeekLayout.rowHeight(avail, 17)
        val row20 = PersonaWeekLayout.rowHeight(avail, 20)
        assertEquals("行高锁死，不随节数继续缩", row17, row20, 0.01f)
        assertEquals(row17 * 3f, PersonaWeekLayout.scrollRange(avail, 20, row20), 0.01f)
        assertTrue(PersonaWeekLayout.scrollRange(avail, 20, row20) > 0f)
    }

    // ---------- 三、边角 ----------

    @Test
    fun `零节也当一节算，不会除零`() {
        val rowH = PersonaWeekLayout.rowHeight(avail, 0)
        assertEquals(avail, rowH, 0.01f)
        assertEquals(0f, PersonaWeekLayout.scrollRange(avail, 0, rowH), 0.01f)
    }

    @Test
    fun `可用高度为零时不产生负数或 NaN`() {
        val rowH = PersonaWeekLayout.rowHeight(0f, 10)
        assertEquals(0f, rowH, 0.001f)
        assertEquals(0f, PersonaWeekLayout.scrollRange(0f, 10, rowH), 0.001f)
        assertTrue(!rowH.isNaN())
    }
}
