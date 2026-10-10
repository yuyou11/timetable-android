package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ============================================================
    //  「两课并一课」的识别与并行（点阵预览用）
    // ============================================================

    @Test
    fun `两节连堂且奇数起点的标准课表判为两课并一课`() {
        val paired = listOf(
            Course("a", "高数", 1, 1, 2, setOf(1)),
            Course("b", "英语", 2, 3, 4, setOf(1)),
            Course("c", "体育", 5, 5, 6, setOf(1)),
            Course("d", "实验", 7, 9, 10, setOf(1))
        )
        assertTrue(Periods.isPairedSchedule(paired))
    }

    @Test
    fun `单节三连排偶数起点都不是标准两课并一课`() {
        assertFalse(
            Periods.isPairedSchedule(listOf(Course("a", "实验", 1, 3, 3, setOf(1))))
        )
        assertFalse(
            Periods.isPairedSchedule(listOf(Course("a", "实训", 1, 3, 5, setOf(1))))
        )
        assertFalse(
            Periods.isPairedSchedule(listOf(Course("a", "调课", 1, 2, 3, setOf(1))))
        )
    }

    @Test
    fun `停掉的单节课不参与判定 空课表不算`() {
        val mixed = listOf(
            Course("a", "高数", 1, 1, 2, setOf(1)),
            Course("b", "已停的单节", 1, 3, 3, setOf(1), enabled = false)
        )
        assertTrue(Periods.isPairedSchedule(mixed))
        assertFalse(Periods.isPairedSchedule(emptyList()))
    }

    @Test
    fun `点阵并行是两格取或 行数减半`() {
        // 周一：1-2 有课、第 5 节有课；周二：只有第 4 节有课
        val grid = arrayOf(
            booleanArrayOf(true, true, false, false, true, false),
            booleanArrayOf(false, false, false, true, false, false)
        )
        val merged = Periods.mergePairs(grid)

        assertEquals(2, merged.size)          // 七天的行数不变，只是每天的行减半
        assertEquals(3, merged[0].size)       // 6 小节 → 3 大节
        assertTrue(merged[0][0])              // 1-2
        assertFalse(merged[0][1])             // 3-4 空
        assertTrue(merged[0][2])              // 5-6：只占第 5 节也算亮
        assertTrue(merged[1][1])              // 3-4：只占第 4 节也算亮
        assertFalse(merged[1][0])
    }

    @Test
    fun `奇数节数时尾巴上凑不成对的一节直接丢掉`() {
        val grid = arrayOf(booleanArrayOf(true, true, false, false, false, false, false))
        val merged = Periods.mergePairs(grid)
        assertEquals(3, merged[0].size)       // 7 小节 → 3 对，第 7 节不留残行
    }
}
