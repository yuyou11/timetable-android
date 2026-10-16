package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「点课表一格 → 这一格全学期都有什么课」的测试。
 *
 * ============================================================
 *  为什么这个查询值得单独测
 * ============================================================
 *
 * 它要回答的问题和课表网格**是同一件事的两个方向**：
 * 网格问「第 9 周这一格是什么课」，它问「这一格有哪些周、分别是什么课」。
 * 两边的命中判据一旦不一致，就会出现「网格里画着课、点开却是空的」——
 * 用户没有任何办法自己判断哪边是对的，只能不再相信这个软件。
 *
 * 所以下面第一组测试就是钉住那条判据：**区间有重叠**，不是起点相等。
 */
class SlotCoursesTest {

    private fun c(
        id: String, name: String, day: Int, a: Int, b: Int, weeks: Set<Int>,
        place: String = "", teacher: String = ""
    ) = Course(id, name, day, a, b, weeks, place, teacher)

    // ---------- 一、命中判据：区间重叠 ----------

    @Test
    fun `连堂课能被它中间任一节查到`() {
        val list = listOf(c("1", "高数", 1, 3, 4, setOf(1)))
        assertEquals(1, SlotCourses.at(1, 3..3, list).size)
        assertEquals(1, SlotCourses.at(1, 4..4, list).size)
    }

    @Test
    fun `被点的范围横跨两门课时两门都查得到`() {
        val list = listOf(
            c("1", "单节课", 1, 3, 3, setOf(1)),
            c("2", "四节连堂", 1, 3, 6, setOf(1))
        )
        val hit = SlotCourses.at(1, 3..4, list)
        assertEquals(2, hit.size)
    }

    @Test
    fun `别的星期和别的时段不会混进来`() {
        val list = listOf(
            c("1", "周一课", 1, 3, 4, setOf(1)),
            c("2", "周二同一格", 2, 3, 4, setOf(1)),
            c("3", "周一别的时段", 1, 5, 6, setOf(1))
        )
        val hit = SlotCourses.at(1, 3..4, list)
        assertEquals(listOf("周一课"), hit.map { it.course.name })
    }

    // ---------- 二、跨周次：同一格的不同课 ----------

    @Test
    fun `同一格不同周次的课都列出来且按首周排序`() {
        // 内置课表里真实存在的写法：周四 7-8 节，第 2 周归一门、第 3 周起归另一门
        val list = listOf(
            c("1", "数据结构", 4, 7, 8, setOf(3, 5, 7), "实验楼-201"),
            c("2", "程序设计基础", 4, 7, 8, setOf(2), "实验楼-201")
        )
        val hit = SlotCourses.at(4, 7..8, list)
        assertEquals(listOf("程序设计基础", "数据结构"), hit.map { it.course.name })
    }

    @Test
    fun `正在浏览的那一周会上的一门排最前`() {
        val list = listOf(
            c("1", "早上周的课", 4, 7, 8, setOf(2)),
            c("2", "晚些周的课", 4, 7, 8, setOf(3, 5, 7))
        )
        assertEquals("晚些周的课", SlotCourses.at(4, 7..8, list, viewedWeek = 5).first().course.name)
        assertEquals("早上周的课", SlotCourses.at(4, 7..8, list, viewedWeek = 2).first().course.name)
    }

    @Test
    fun `浏览的那一周不在任何一门课的周次里时徽标全部为空`() {
        val list = listOf(c("1", "只在第 2 周", 4, 7, 8, setOf(2)))
        assertNull(SlotCourses.at(4, 7..8, list, viewedWeek = 9).first().activeInWeek)
    }

    @Test
    fun `不传周次时不会替谁盖上本周的章`() {
        val list = listOf(c("1", "课", 1, 1, 2, setOf(1)))
        assertNull(SlotCourses.at(1, 1..2, list).first().activeInWeek)
    }

    // ---------- 三、合并：一门课的多条记录 ----------

    @Test
    fun `名称教室老师周次都相同的两条记录合并成一张卡`() {
        // 有的学校课不成对，一门课在数据里按小节拆成两条；网格里拼成一整块，
        // 点开也必须只有一张卡 —— 否则用户会以为有两门一样的课
        val list = listOf(
            c("1", "军事理论", 1, 1, 2, setOf(2), "教二-311", "赵国强"),
            c("2", "军事理论", 1, 3, 4, setOf(2), "教二-311", "赵国强")
        )
        val hit = SlotCourses.at(1, 1..4, list)
        assertEquals(1, hit.size)
        assertEquals(1, hit[0].fromNode)
        assertEquals(4, hit[0].toNode)
    }

    @Test
    fun `周次不同的同名课不会被合并`() {
        val list = listOf(
            c("1", "高数", 1, 1, 2, setOf(2), "教一-203"),
            c("2", "高数", 1, 1, 2, setOf(5), "教一-203")
        )
        assertEquals(2, SlotCourses.at(1, 1..2, list).size)
    }

    @Test
    fun `教室不同的同名课不会被合并`() {
        val list = listOf(
            c("1", "高数", 1, 1, 2, setOf(2), "教一-203"),
            c("2", "高数", 1, 1, 2, setOf(2), "教一-205")
        )
        assertEquals(2, SlotCourses.at(1, 1..2, list).size)
    }

    @Test
    fun `老师不同的同名课不会被合并`() {
        val list = listOf(
            c("1", "高数", 1, 1, 2, setOf(2), "教一-203", "李振华"),
            c("2", "高数", 1, 1, 2, setOf(2), "教一-203", "何静")
        )
        assertEquals(2, SlotCourses.at(1, 1..2, list).size)
    }

    // ---------- 四、停课与边界 ----------

    @Test
    fun `停课的课仍然列出来`() {
        // 「这一格本来还有一门课、被你停掉了」是有用信息；静默吞掉反而像数据丢了
        val list = listOf(c("1", "军事理论", 1, 1, 2, setOf(2)).copy(enabled = false))
        val hit = SlotCourses.at(1, 1..2, list)
        assertEquals(1, hit.size)
        assertTrue(!hit[0].course.enabled)
    }

    @Test
    fun `空格子返回空列表而不是异常`() {
        val list = listOf(c("1", "课", 1, 1, 2, setOf(1)))
        assertEquals(0, SlotCourses.at(3, 5..6, list).size)
        assertEquals(0, SlotCourses.at(1, 5..6, emptyList()).size)
    }

    @Test
    fun `同样的输入给同样的顺序`() {
        val list = listOf(
            c("1", "B课", 2, 3, 4, setOf(4)),
            c("2", "A课", 2, 3, 4, setOf(4)),
            c("3", "C课", 2, 3, 4, setOf(3))
        )
        assertEquals(
            SlotCourses.at(2, 3..4, list, 4).map { it.course.name },
            SlotCourses.at(2, 3..4, list, 4).map { it.course.name }
        )
    }

    @Test
    fun `内置课表的周四第七八节能查到两门课`() {
        // 拿真实内置数据再验一遍：这一格是「同一时段、不同周次」的示范写法
        val hit = SlotCourses.at(4, 7..8, BuiltinCourses.LIST, viewedWeek = 9)
        assertEquals(listOf("数据结构", "程序设计基础"), hit.map { it.course.name }.sorted())
        assertEquals(9, hit.first { it.course.name == "数据结构" }.activeInWeek)
    }
}
