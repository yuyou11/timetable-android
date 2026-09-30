package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节数信息（periods）展开成网格行的测试。
 *
 * 对齐电脑版 tests/test_week_grid.py 与 test_core.py 的对应用例 ——
 * 两个平台必须按同一套规则把节数信息切成行：文件互通的前提是
 * 同一份 `periods` 在两边切出来的行一模一样。
 *
 * 模型：**一天的小节数 = 各位之和**，一行一小节，学校的第 N 节就是
 * 第 N 小节；第 11-14 节的时间由 [Slots] 按晚课节奏外推。
 */
class PeriodsTest {

    private fun nodes(periods: String) = Periods.rows(periods).map { it.node }
    private fun labels(periods: String) = Periods.rows(periods).map { it.label }

    @Test
    fun `默认 442 展开成十行逐节分行`() {
        assertEquals((1..10).toList(), nodes(ScheduleFormat.DEFAULT_PERIODS))
        assertEquals((1..10).map { "第 $it 节" }, labels(ScheduleFormat.DEFAULT_PERIODS))
    }

    @Test
    fun `一天的小节数等于各位之和`() {
        assertEquals(10, ScheduleFormat.nodeCount("442"))
        assertEquals(13, ScheduleFormat.nodeCount("445"))
        assertEquals(9, ScheduleFormat.nodeCount("441"))
    }

    @Test
    fun `445 展开成十三行`() {
        assertEquals((1..13).toList(), nodes("445"))
        assertEquals((1..13).map { "第 $it 节" }, labels("445"))
    }

    @Test
    fun `441 一天九节 没有第 10 节`() {
        assertEquals((1..9).toList(), nodes("441"))
    }

    @Test
    fun `外推节的时刻来自 Slots 晚课节奏`() {
        // 第 11 节 20:40-21:25，第 14 节 23:10-23:55
        assertEquals("20:40", Slots.fmt(Slots.start(11)))
        assertEquals("21:25", Slots.fmt(Slots.end(11)))
        assertEquals("23:10", Slots.fmt(Slots.start(14)))
        assertEquals("23:55", Slots.fmt(Slots.end(14)))
    }

    @Test
    fun `每行的起止时刻都落在一天之内`() {
        // 任何合法结构切出来的行，时刻都必须能从 Slots 取到且有序 ——
        // 越界的节次在解析层就该被拒绝，这里验证兜底路径也不崩
        for (periods in listOf("441", "442", "445", "22222", "55")) {
            for (r in Periods.rows(periods)) {
                val start = Slots.start(r.node)
                val end = Slots.end(r.node)
                assertTrue(
                    "periods=$periods 第 ${r.node} 节时刻异常",
                    start in 0 until end
                )
            }
        }
    }

    @Test
    fun `非法节数信息按默认 442 兜底`() {
        // Store 已经在入口挡了一道，这里验证的是：就算坏值漏进来，
        // 网格也不能崩或者缺行
        assertEquals(nodes(ScheduleFormat.DEFAULT_PERIODS), nodes("456"))
    }
}
