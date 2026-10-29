package com.yxz.timetable.ui.persona

import com.yxz.timetable.data.Kind
import com.yxz.timetable.data.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 今天页的三段纯逻辑：时间线怎么滚、格子状态怎么判、24 小时色带怎么切。
 *
 * 这些在真机上「错了也不崩」—— 滚过头只是露出空白，状态判错只是高亮错了格。
 * 正因为不崩，才更要单测：装 APK 用眼睛看是发现不了「滚到最后一格还能再拖出半屏」的。
 */
class PersonaTodayModelTest {

    private fun m(start: Int, end: Int, title: String, kind: Kind = Kind.CHORE) =
        Moment(start, end, title, note = "", place = "", kind = kind)

    /** 一天二十来格，和真实时间轴同构：首尾相接、从 0 到 1440 */
    private fun day(): List<Moment> {
        val raw = listOf(
            0 to 420, 420 to 445, 445 to 480, 480 to 605, 605 to 625,
            625 to 720, 720 to 760, 760 to 810, 810 to 840, 840 to 935,
            935 to 955, 955 to 1050, 1050 to 1110, 1110 to 1140, 1140 to 1235,
            1235 to 1300, 1300 to 1360, 1360 to 1390, 1390 to 1440
        )
        return raw.mapIndexed { i, (a, b) -> m(a, b, "第 $i 格") }
    }

    // ---------- 一、格子的状态 ----------

    @Test
    fun `左闭右开：整点属于新的一格，格尾属于下一格`() {
        val mm = m(420, 445, "起床")
        assertEquals(
            "420 这一刻刚进入这一格",
            PersonaTodayModel.RowState.NOW, PersonaTodayModel.stateOf(mm, 420)
        )
        assertEquals(PersonaTodayModel.RowState.NOW, PersonaTodayModel.stateOf(mm, 444))
        assertEquals(
            "445 已经是下一格了",
            PersonaTodayModel.RowState.PAST, PersonaTodayModel.stateOf(mm, 445)
        )
        assertEquals(PersonaTodayModel.RowState.NEXT, PersonaTodayModel.stateOf(mm, 419))
    }

    // ---------- 二、当前格下标 ----------

    @Test
    fun `当前格下标取所在的那一格`() {
        val moments = day()
        // 第 13 格 1110-1140
        assertEquals(13, PersonaTodayModel.indexAt(moments, 1130))
        assertEquals(0, PersonaTodayModel.indexAt(moments, 10))
    }

    @Test
    fun `都不在区间里时退回最后一格，空表退回 0`() {
        val moments = listOf(m(600, 660, "只到 11 点"))
        assertEquals("12:00 之后所有格子都结束了", 0, PersonaTodayModel.indexAt(moments, 1200))
        assertEquals(0, PersonaTodayModel.indexAt(emptyList(), 1200))
    }

    // ---------- 三、滚动范围与初始位置 ----------

    @Test
    fun `内容装得下时不许滚`() {
        assertEquals(0f, PersonaTodayModel.maxScroll(300f, 400f), 0.01f)
        assertEquals(100f, PersonaTodayModel.maxScroll(500f, 400f), 0.01f)
    }

    @Test
    fun `初始位置把当前格摆在正中`() {
        // 19 格 × 40 = 760 内容高，视口 320，当前格第 9 格
        val scroll = PersonaTodayModel.initialScroll(9, 40f, 320f, 760f)
        // 9*40 - (320-40)/2 = 360 - 140 = 220
        assertEquals(220f, scroll, 0.01f)
        // 当前行中心正好落在视口中心
        val rowCenter = scroll + 320f / 2f
        assertEquals(9 * 40f + 20f, rowCenter, 0.01f)
    }

    @Test
    fun `深夜的最后一格不会被居中到越界`() {
        val scroll = PersonaTodayModel.initialScroll(18, 40f, 320f, 760f)
        val max = PersonaTodayModel.maxScroll(760f, 320f)
        assertEquals("夹到滚动上限", max, scroll, 0.01f)
    }

    @Test
    fun `凌晨的第一格不会算出负的滚动位置`() {
        assertEquals(0f, PersonaTodayModel.initialScroll(0, 40f, 320f, 760f), 0.01f)
        assertEquals(0f, PersonaTodayModel.initialScroll(3, 0f, 320f, 760f), 0.01f)
        assertEquals(0f, PersonaTodayModel.initialScroll(3, 40f, 0f, 760f), 0.01f)
    }

    @Test
    fun `滚到头和尾都不会越界`() {
        val max = PersonaTodayModel.maxScroll(760f, 320f)
        // 最后一次甩动可能把 scroller 交回一个超出的值，View 里就是靠这个夹的
        assertEquals(0f, (-50f).coerceIn(0f, max), 0.01f)
        assertEquals(max, (max + 80f).coerceIn(0f, max), 0.01f)
    }

    // ---------- 四、24 小时色带 ----------

    @Test
    fun `色带铺满一天，总宽度就是 1440 分钟`() {
        val segs = PersonaTodayModel.segments(day())
        assertEquals(1440, segs.sumOf { it.minutes })
        assertEquals(0, segs.first().start)
        assertEquals(1440, segs.last().end)
        for (i in 1 until segs.size) {
            assertEquals("相邻两段必须首尾相接", segs[i - 1].end, segs[i].start)
        }
    }

    @Test
    fun `超出一天的时段会被裁掉，不会画到条外`() {
        val segs = PersonaTodayModel.segments(
            listOf(m(1380, 1500, "跨天的觉", Kind.SLEEP))
        )
        assertEquals(1, segs.size)
        assertEquals(1440, segs[0].end)
    }

    @Test
    fun `零长度和倒挂的时段直接丢掉`() {
        val segs = PersonaTodayModel.segments(
            listOf(m(600, 600, "空"), m(700, 650, "倒挂"), m(800, 900, "正常"))
        )
        assertEquals(1, segs.size)
        assertEquals(800, segs[0].start)
    }

    @Test
    fun `八种时段性质的颜色两两不同`() {
        val colors = Kind.values().map { PersonaTodayModel.kindColor(it) }
        assertEquals("色带只有 10dp 高，撞色就分不出来了", colors.size, colors.toSet().size)
        assertEquals(0xFFFF3FD8.toInt(), PersonaTodayModel.kindColor(Kind.CLASS))
        assertEquals(0xFF18D8F8.toInt(), PersonaTodayModel.kindColor(Kind.STUDY))
        assertNotEquals(
            "训练日和吃饭不能同色",
            PersonaTodayModel.kindColor(Kind.TRAIN),
            PersonaTodayModel.kindColor(Kind.MEAL)
        )
    }

    @Test
    fun `一天的每一格都有确定的滚动位置`() {
        val moments = day()
        val rowH = 48f
        val contentH = rowH * moments.size
        val viewport = 400f
        val max = PersonaTodayModel.maxScroll(contentH, viewport)
        assertTrue("19 格 × 48dp 一定超过一屏，才需要滚", max > 0f)
        for (i in moments.indices) {
            val s = PersonaTodayModel.initialScroll(i, rowH, viewport, contentH)
            assertTrue("第 $i 格的初始位置必须落在 [0, max] 里", s in 0f..max)
        }
    }
}
