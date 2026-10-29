package com.yxz.timetable.ui

import com.yxz.timetable.data.Course
import com.yxz.timetable.data.Periods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表网格的「哪几格合成一块」—— 纯逻辑，两套外观共用一份（见 [WeekGrid]）。
 *
 * ============================================================
 *  为什么这段值得单独测
 * ============================================================
 *
 * 课表页的两种画法（默认的 XML 网格、P3 的整页自绘）差别很大，
 * 但**同一份课表在两套外观下必须长得一样**：哪一格是空的、哪几行合成一块、
 * 点开的是哪几节 —— 任何一处不一致，用户就会以为其中一边在骗他。
 *
 * 所以判定和合并全部收在 [WeekGrid] 里，这里把它钉死。
 */
class WeekGridTest {

    private fun c(
        id: String, name: String, day: Int, a: Int, b: Int, weeks: Set<Int>,
        place: String = "", enabled: Boolean = true
    ) = Course(
        id = id, name = name, dayOfWeek = day, startNode = a, endNode = b,
        weeks = weeks, place = place, enabled = enabled
    )

    private val nodes = (1..10).toList()

    // ---------- 一、命中判据：区间重叠 ----------

    @Test
    fun `连堂课在它跨过的每一行都命中`() {
        val list = listOf(c("1", "高数", 1, 3, 4, setOf(1)))
        val spans = WeekGrid.spans(1, nodes, 1, list)
        val hit = spans.filter { it.course != null }
        assertEquals("第 3、4 两行合成一块", 1, hit.size)
        assertEquals(3, hit[0].fromNode)
        assertEquals(4, hit[0].toNode)
    }

    @Test
    fun `单节课只占一行`() {
        val list = listOf(c("1", "班会", 2, 5, 5, setOf(1)))
        val hit = WeekGrid.spans(2, nodes, 1, list).filter { it.course != null }
        assertEquals(1, hit.size)
        assertEquals(5, hit[0].fromNode)
        assertEquals(5, hit[0].toNode)
        assertEquals(1, hit[0].merged)
    }

    @Test
    fun `起点不在行首的课不会消失`() {
        // 有的学校课不成对：一行是按小节切的，课程区间只和某一行重叠
        val list = listOf(c("1", "怪课", 3, 2, 5, setOf(1)))
        val hit = WeekGrid.spans(3, nodes, 1, list).filter { it.course != null }
        assertEquals(1, hit.size)
        assertEquals(2, hit[0].fromNode)
        assertEquals(5, hit[0].toNode)
    }

    // ---------- 二、相邻同课合并 ----------

    @Test
    fun `名称教室周次相同的两条记录合并成一整块`() {
        // 一门课在数据里被拆成「1-2」「3-4」两条记录（不同 id）
        val list = listOf(
            c("1", "大学物理", 4, 1, 2, setOf(3), place = "教二-108"),
            c("2", "大学物理", 4, 3, 4, setOf(3), place = "教二-108")
        )
        val hit = WeekGrid.spans(4, nodes, 3, list).filter { it.course != null }
        assertEquals(1, hit.size)
        assertEquals(1, hit[0].fromNode)
        assertEquals(4, hit[0].toNode)
        assertEquals(4, hit[0].merged)
    }

    @Test
    fun `名称相同但教室或周次不同的相邻记录不合并`() {
        val list = listOf(
            c("1", "大学物理", 4, 1, 2, setOf(3), place = "教二-108"),
            c("2", "大学物理", 4, 3, 4, setOf(3), place = "教三-201")
        )
        val hit = WeekGrid.spans(4, nodes, 3, list).filter { it.course != null }
        assertEquals("换了教室就是两门不同的课", 2, hit.size)
    }

    @Test
    fun `停课和别的周上的课都不出现`() {
        val list = listOf(
            c("1", "停掉的课", 1, 1, 2, setOf(1), enabled = false),
            c("2", "只在第 5 周", 1, 3, 4, setOf(5))
        )
        val hit = WeekGrid.spans(1, nodes, 1, list).filter { it.course != null }
        assertTrue("第 1 周这一列应该全空", hit.isEmpty())
    }

    // ---------- 三、不变量 ----------

    @Test
    fun `一天的块首尾相接铺满整列`() {
        val list = listOf(
            c("1", "早课", 1, 1, 2, setOf(1)),
            c("2", "午课", 1, 5, 6, setOf(1)),
            c("3", "晚课", 1, 9, 10, setOf(1))
        )
        // 3 门连堂各占 2 行、合并后各算 1 块：10 行 → 7 块
        val spans = WeekGrid.spans(1, nodes, 1, list)
        assertEquals(7, spans.size)
        assertEquals("第一块从第 1 节开始", 1, spans.first().fromNode)
        assertEquals("最后一块到第 10 节结束", 10, spans.last().toNode)
        for (i in 1 until spans.size) {
            assertEquals(
                "第 $i 块必须紧接上一块，中间不能断层",
                spans[i - 1].toNode + 1, spans[i].fromNode
            )
        }
    }

    @Test
    fun `没有课的那天是十个单行空格子`() {
        val list = listOf(c("1", "早课", 1, 1, 2, setOf(1)))
        for (day in 2..7) {
            val spans = WeekGrid.spans(day, nodes, 1, list)
            assertEquals(10, spans.size)
            assertTrue(spans.all { it.course == null && it.merged == 1 })
        }
    }

    @Test
    fun `节数结构变了，块跟着行走`() {
        // 13 节的学校（"445"）：一门 11-13 的晚课
        val many = (1..13).toList()
        val list = listOf(c("1", "晚课", 1, 11, 13, setOf(1)))
        val hit = WeekGrid.spans(1, many, 1, list).filter { it.course != null }
        assertEquals(1, hit.size)
        assertEquals(11, hit[0].fromNode)
        assertEquals(13, hit[0].toNode)
    }

    // ---------- 四、近五周点阵 ----------

    @Test
    fun `占用表忽略停课、别的周和超出节数的节次`() {
        val list = listOf(
            c("1", "本周课", 1, 1, 2, setOf(3)),
            c("2", "别的周", 1, 1, 2, setOf(4)),
            c("3", "停课", 2, 1, 2, setOf(3), enabled = false),
            // 导入的旧数据里节次可能超出学校声明的行数，落地时不能越界
            c("4", "越界的课", 3, 9, 12, setOf(3))
        )
        val grid = WeekDensity.occupancy(3, list, 10)
        assertTrue("周一是课", grid[0][0])
        assertTrue("周二是空的（停课）", !grid[1][0])
        assertTrue("第 3 天第 9-10 节有课", grid[2][8] && grid[2][9])
        assertEquals("越界的那两节不该写出第 11、12 格", 10, grid[2].size)
    }

    @Test
    fun `并两课-连堂课只亮一个点`() {
        val list = listOf(c("1", "连堂", 1, 3, 4, setOf(2)))
        assertTrue(Periods.isPairedSchedule(list))
        val merged = Periods.mergePairs(WeekDensity.occupancy(2, list, 10))
        assertTrue("并成一行后第 2 格（原 3-4 节）亮", merged[0][1])
        assertEquals("10 节并成 5 行", 5, merged[0].size)
    }

    @Test
    fun `近五周只在学期范围内取，当前周高亮`() {
        val list = listOf(c("1", "课", 1, 1, 2, setOf(1)))
        val first = WeekDensity.weeks(1, 20, list, 10, paired = false)
        assertEquals("第 1 周只有 1-3 周", listOf(1, 2, 3), first.map { it.label })
        assertEquals(listOf(true, false, false), first.map { it.highlight })

        val last = WeekDensity.weeks(20, 20, list, 10, paired = false)
        assertEquals(listOf(18, 19, 20), last.map { it.label })
        assertEquals(listOf(false, false, true), last.map { it.highlight })
    }

    @Test
    fun `空格子也留在结果里`() {
        val spans = WeekGrid.spans(7, nodes, 1, emptyList())
        assertEquals(10, spans.size)
        assertNull(spans[0].course)
    }
}
