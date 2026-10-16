package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 内置节假日表的测试。
 *
 * 这张表是**抄来的**，抄错了没人会发现 —— 直到某个国庆节的早上闹钟把人叫醒。
 * 所以下面这些断言只干一件事：把官方通知里的每一条都钉一遍。
 *
 * 来源：国务院办公厅《关于 2026 年部分节假日安排的通知》
 * （国办发明电〔2025〕7 号，2025-11-04），并与 NateScarlet/holiday-cn 的
 * 2026.json 逐条比对过。
 */
class HolidaysTest {

    private val t = HolidayTable.BUILTIN_ONLY

    @Test
    fun `总数是 33 天放假加 6 天补班`() {
        val off = Holidays.BUILTIN.count { it.offDay }
        val work = Holidays.BUILTIN.count { !it.offDay }
        assertEquals("2026 年放假天数", 33, off)
        assertEquals("2026 年补班天数（周末上班）", 6, work)
    }

    @Test
    fun `元旦 1月1到3日放假 1月4日周日补班`() {
        assertTrue(t.isOffDay(LocalDate.of(2026, 1, 1)))
        assertTrue(t.isOffDay(LocalDate.of(2026, 1, 2)))
        assertTrue(t.isOffDay(LocalDate.of(2026, 1, 3)))
        assertEquals("元旦", t.label(LocalDate.of(2026, 1, 1)))
        assertTrue("1/4 是周日但要上班", t.isMakeupWorkday(LocalDate.of(2026, 1, 4)))
    }

    @Test
    fun `春节 2月15到23日放假 前后两个周六补班`() {
        for (d in 15..23) assertTrue("2/$d 应当放假", t.isOffDay(LocalDate.of(2026, 2, d)))
        assertTrue(t.isMakeupWorkday(LocalDate.of(2026, 2, 14)))
        assertTrue(t.isMakeupWorkday(LocalDate.of(2026, 2, 28)))
        assertFalse("2/14 不是放假日", t.isOffDay(LocalDate.of(2026, 2, 14)))
        assertFalse("2/24 已收假", t.isOffDay(LocalDate.of(2026, 2, 24)))
    }

    @Test
    fun `清明 劳动 端午 中秋 各三天且不调休`() {
        assertEquals("清明节", t.label(LocalDate.of(2026, 4, 4)))
        assertEquals("清明节", t.label(LocalDate.of(2026, 4, 6)))       // 周一
        for (d in 1..5) assertEquals("劳动节", t.label(LocalDate.of(2026, 5, d)))
        assertEquals("劳动节", t.label(LocalDate.of(2026, 5, 9)))       // 补班日也用节日名
        assertTrue(t.isMakeupWorkday(LocalDate.of(2026, 5, 9)))
        for (d in 19..21) assertEquals("端午节", t.label(LocalDate.of(2026, 6, d)))
        for (d in 25..27) assertEquals("中秋节", t.label(LocalDate.of(2026, 9, d)))
    }

    @Test
    fun `国庆 10月1到7日放假 9月20日和10月10日补班`() {
        for (d in 1..7) assertEquals("国庆节", t.label(LocalDate.of(2026, 10, d)))
        assertFalse(t.isOffDay(LocalDate.of(2026, 10, 8)))
        assertTrue("9/20 是周日但要上班", t.isMakeupWorkday(LocalDate.of(2026, 9, 20)))
        assertTrue("10/10 是周六但要上班", t.isMakeupWorkday(LocalDate.of(2026, 10, 10)))
    }

    @Test
    fun `中秋和国庆都落在本学期内`() {
        // 学期从 2026-09-07 起 19 周，到 2027-01-17。这两段假期正好在学期里，
        // 也就是「最需要别在假期叫人起床」的两个时间点
        val termStart = LocalDate.of(2026, 9, 7)
        val termEnd = termStart.plusWeeks(19).minusDays(1)
        for (d in listOf(
            LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)
        )) {
            assertTrue("$d 应当落在学期内", d in termStart..termEnd)
            assertTrue("$d 应当是法定放假日", t.isOffDay(d))
        }
    }

    @Test
    fun `普通日子既不是放假也不是补班`() {
        val d = LocalDate.of(2026, 9, 23)          // 周三，普通上课日
        assertFalse(t.isOffDay(d))
        assertFalse(t.isMakeupWorkday(d))
        assertNull(t.label(d))
    }

    @Test
    fun `手动加的那份能覆盖内置`() {
        val d = LocalDate.of(2026, 10, 8)          // 内置里是普通工作日
        val custom = HolidayTable(listOf(HolidayDay("校庆", d, true)))
        assertTrue("手动标成放假后应当算放假", custom.isOffDay(d))
        assertFalse("内置那份不受影响", HolidayTable.BUILTIN_ONLY.isOffDay(d))
    }

    @Test
    fun `同一天先标放假再标补班 以最后一次为准`() {
        // 同一天只能是一种性质。重复登记时要把另一种抹掉，
        // 否则会出现「既是放假日又是补班日」这种自相矛盾的状态
        val d = LocalDate.of(2026, 10, 8)
        val table = HolidayTable(
            listOf(HolidayDay("先标放假", d, true), HolidayDay("又说是补班", d, false))
        )
        assertFalse(table.isOffDay(d))
        assertTrue(table.isMakeupWorkday(d))
    }

    @Test
    fun `覆盖年份如实报告`() {
        assertTrue("2026 有内置", HolidayTable.BUILTIN_ONLY.covers(2026))
        assertFalse("2027 还没有内置（通知要 11 月才发）", HolidayTable.BUILTIN_ONLY.covers(2027))
        assertTrue(
            "手动加过 2027 的一天就算覆盖了",
            HolidayTable(listOf(HolidayDay("元旦", LocalDate.of(2027, 1, 1), true))).covers(2027)
        )
    }
}
