package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 闹钟逻辑的测试。
 *
 * 这些断言守的是**用户会迟到的那种 bug**：
 *
 *  - 补班日（周末上班）到底响不响？
 *  - 这门课第 5 周没课，闹钟还会不会响？
 *  - 国庆节早上会不会被 06:30 的起床闹钟叫醒？
 *
 * 全是纯逻辑，在电脑上几秒钟跑完，不需要手机。
 */
class AlarmsTest {

    private val termStart = LocalDate.of(2026, 9, 7)      // 第 1 周周一
    private val courses = BuiltinCourses.LIST
    private val templates = TemplateSet.BUILTIN
    private val holidays = HolidayTable.BUILTIN_ONLY

    /** 和 Store.weekOf 同一条规则：入学前算第 1 周，之后每 7 天一周 */
    private fun weekOf(d: LocalDate): Int =
        ((ChronoUnit.DAYS.between(termStart, d) / 7) + 1).toInt().coerceAtLeast(1)

    private fun alarmAt(hour: Int, minute: Int, days: Set<Int>, skipHolidays: Boolean = true) =
        Alarm(id = "a001", hour = hour, minute = minute, days = days, skipHolidays = skipHolidays)

    private fun fire(a: Alarm, date: LocalDate) =
        Alarms.fireMinuteOfDay(a, date, weekOf(date), courses, templates, holidays)

    // ---------- 一、普通闹钟：周几 ----------

    @Test
    fun `工作日闹钟在周三响 在周六不响`() {
        val a = alarmAt(6, 30, Alarms.WORKDAYS)
        assertEquals(6 * 60 + 30, fire(a, LocalDate.of(2026, 9, 23))!!)   // 周三
        assertNull(fire(a, LocalDate.of(2026, 9, 26)))                    // 周六
    }

    @Test
    fun `每天闹钟哪一天都响`() {
        // 挑 11 月中旬这一周：没有节假日、也没有补班，七个星期几都干净
        val a = alarmAt(7, 0, setOf(1, 2, 3, 4, 5, 6, 7))
        for (d in 16..22) assertNotNull("11/$d 应当响", fire(a, LocalDate.of(2026, 11, d)))
        // 反例：中秋是法定假日，每天型闹钟那天也不该响
        assertNull("中秋 9/25 不该响", fire(a, LocalDate.of(2026, 9, 25)))
    }

    @Test
    fun `关掉的闹钟不响`() {
        assertNull(fire(alarmAt(6, 30, Alarms.WORKDAYS).copy(enabled = false), LocalDate.of(2026, 9, 23)))
    }

    // ---------- 二、智能识别节假日 ----------

    @Test
    fun `法定放假日工作日闹钟不响`() {
        val a = alarmAt(6, 30, Alarms.WORKDAYS)
        assertNull("10/1 是周四但放假，不该响", fire(a, LocalDate.of(2026, 10, 1)))
        assertNull("10/7 是周三但放假，不该响", fire(a, LocalDate.of(2026, 10, 7)))
    }

    @Test
    fun `关掉智能识别之后 放假照响`() {
        val a = alarmAt(6, 30, Alarms.WORKDAYS, skipHolidays = false)
        assertEquals(6 * 60 + 30, fire(a, LocalDate.of(2026, 10, 1))!!)
    }

    @Test
    fun `补班日 工作日闹钟要响 周末闹钟不响`() {
        // 9/20 是周日、10/10 是周六，但都是补班日（国庆调休）
        val workday = alarmAt(6, 30, Alarms.WORKDAYS)
        val weekend = alarmAt(9, 0, Alarms.WEEKEND)
        for (d in listOf(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 10))) {
            assertNotNull("$d 补班，工作日闹钟应当响", fire(workday, d))
            assertNull("$d 补班，周末闹钟不该响", fire(weekend, d))
        }
    }

    @Test
    fun `周末闹钟在普通周末照常响`() {
        // 11/21 是普通周六；10/10 是周六但补班（国庆调休）→ 不该响。
        // 注意别拿 9/26 当「普通周六」——那是中秋假期，不响才是对的
        val weekend = alarmAt(9, 0, Alarms.WEEKEND)
        assertNotNull(fire(weekend, LocalDate.of(2026, 11, 21)))
        assertNull(fire(weekend, LocalDate.of(2026, 10, 10)))
    }

    // ---------- 三、课程闹钟：只有有课才响 ----------

    private fun courseAlarm(courseId: String, lead: Int = 10) =
        Alarms.forCourse("a010", courseId, lead)

    @Test
    fun `课程闹钟只在这门课真的上的那一周才响`() {
        // 内置课表 c14「数据结构」周四 7-8 节，周次是 3,5,7…17（单周）
        val a = courseAlarm("c14")
        val thuWeek3 = LocalDate.of(2026, 9, 24)     // 第 3 周周四
        val thuWeek2 = LocalDate.of(2026, 9, 17)     // 第 2 周周四（单周不上）
        assertEquals(weekOf(thuWeek3), 3)
        assertEquals(weekOf(thuWeek2), 2)
        assertNotNull("第 3 周有课，应当响", fire(a, thuWeek3))
        assertNull("第 2 周没课，不该响", fire(a, thuWeek2))
    }

    @Test
    fun `课程闹钟的时刻等于上课时间减去提前量`() {
        val thuWeek3 = LocalDate.of(2026, 9, 24)
        val start = templates.slotStart(7)
        assertEquals("提前 10 分钟", start - 10, fire(courseAlarm("c14", 10), thuWeek3)!!)
        assertEquals("提前 1 分钟", start - 1, fire(courseAlarm("c14", 1), thuWeek3)!!)
        assertEquals("不提前", start, fire(courseAlarm("c14", 0), thuWeek3)!!)
    }

    @Test
    fun `同一时段不同周次的两门课各响各的`() {
        // 周四 7-8 节：第 2 周是「程序设计基础」c15，第 3 周起是「数据结构」c14
        val thuWeek2 = LocalDate.of(2026, 9, 17)
        assertNotNull(fire(courseAlarm("c15"), thuWeek2))
        assertNull("c14 第 2 周还没开课", fire(courseAlarm("c14"), thuWeek2))
    }

    @Test
    fun `停课或删掉的课不会响`() {
        val thuWeek3 = LocalDate.of(2026, 9, 24)
        val paused = courses.map { if (it.id == "c14") it.copy(enabled = false) else it }
        assertNull(
            "停课的课不该响",
            Alarms.fireMinuteOfDay(
                courseAlarm("c14"), thuWeek3, weekOf(thuWeek3), paused, templates, holidays
            )
        )
        assertNull(
            "课被删了（换了一份课表）也不该响",
            Alarms.fireMinuteOfDay(
                courseAlarm("c14"), thuWeek3, weekOf(thuWeek3), emptyList(), templates, holidays
            )
        )
    }

    @Test
    fun `节假日那天课程闹钟也不响`() {
        // 10/1 是周四，但放假 —— 课表里就算排了课也不该叫人起床
        val withCourse = listOf(
            Course("x01", "国庆加课", 4, 7, 8, setOf(weekOf(LocalDate.of(2026, 10, 1))), "教一-101")
        )
        val a = courseAlarm("x01")
        val d = LocalDate.of(2026, 10, 1)
        assertNull(fire(a, d))
        val noSkip = a.copy(skipHolidays = false)
        assertNotNull(
            "关掉智能识别后照响",
            Alarms.fireMinuteOfDay(noSkip, d, weekOf(d), withCourse, templates, holidays)
        )
    }

    // ---------- 四、找下一次 / 找此刻 ----------

    @Test
    fun `nextFire 取最近的一次`() {
        val list = listOf(
            alarmAt(23, 0, setOf(1, 2, 3, 4, 5, 6, 7)),      // 每晚 23:00
            alarmAt(6, 30, Alarms.WORKDAYS)                    // 早上 06:30
        )
        val from = LocalDateTime.of(2026, 9, 23, 20, 0)        // 周三晚上 8 点
        val next = Alarms.nextFire(list, from, courses, templates, holidays, ::weekOf)!!
        assertEquals("周三晚上之后，最近的是 23:00", LocalDateTime.of(2026, 9, 23, 23, 0), next.at)
    }

    @Test
    fun `nextFire 会跨过没课的那一周`() {
        // 只放一个「数据结构（单周）」的课程闹钟，从第 2 周周四之后开始找，
        // 下一次应当是第 3 周周四 —— 第 2 周那格是另一门课，这一条不该醒
        val only = listOf(courseAlarm("c14", 10))
        val from = LocalDateTime.of(2026, 9, 17, 20, 0)        // 第 2 周周四晚
        val next = Alarms.nextFire(only, from, courses, templates, holidays, ::weekOf)!!
        assertEquals(LocalDate.of(2026, 9, 24), next.date)
        assertEquals(templates.slotStart(7) - 10, next.minuteOfDay)
    }

    @Test
    fun `同一个时刻的多个闹钟会被一起找出来`() {
        // 两个 06:30 的闹钟：排程只排最早那一个，到点时两个都要响
        val a1 = alarmAt(6, 30, Alarms.WORKDAYS).copy(id = "a001")
        val a2 = alarmAt(6, 30, Alarms.WORKDAYS).copy(id = "a002")
        val at = LocalDateTime.of(2026, 9, 23, 6, 30)
        val due = Alarms.dueAt(listOf(a1, a2), at, courses, templates, holidays, ::weekOf)
        assertEquals(listOf("a001", "a002"), due.map { it.id })
    }

    @Test
    fun `dueAt 不会把别的时刻的闹钟算进来`() {
        val a1 = alarmAt(6, 30, Alarms.WORKDAYS)
        val a2 = alarmAt(22, 0, Alarms.WORKDAYS)
        val due = Alarms.dueAt(
            listOf(a1, a2), LocalDateTime.of(2026, 9, 23, 6, 30),
            courses, templates, holidays, ::weekOf
        )
        assertEquals(listOf(6 * 60 + 30), due.map { it.hour * 60 + it.minute })
    }

    @Test
    fun `没有任何闹钟时 nextFire 是 null`() {
        assertNull(
            Alarms.nextFire(
                emptyList(), LocalDateTime.of(2026, 9, 23, 8, 0),
                courses, templates, holidays, ::weekOf
            )
        )
    }

    // ---------- 五、文案与辅助 ----------

    @Test
    fun `周几的摘要文案`() {
        assertEquals("工作日", Alarms.daySummary(Alarms.WORKDAYS))
        assertEquals("周末", Alarms.daySummary(Alarms.WEEKEND))
        assertEquals("每天", Alarms.daySummary(setOf(1, 2, 3, 4, 5, 6, 7)))
        assertEquals("一 三 五", Alarms.daySummary(setOf(1, 3, 5)))
        assertEquals("不重复", Alarms.daySummary(emptySet()))
    }

    @Test
    fun `工作日闹钟的判定`() {
        assertTrue(Alarms.isWorkdayAlarm(alarmAt(6, 0, Alarms.WORKDAYS)))
        assertFalse(Alarms.isWorkdayAlarm(alarmAt(6, 0, Alarms.WORKDAYS + 6)))
        assertFalse(Alarms.isWorkdayAlarm(alarmAt(6, 0, setOf(1, 3, 5))))
    }

    @Test
    fun `课程闹钟的显示名字带上课程名和提前量`() {
        val a = courseAlarm("c14", 20)
        assertEquals("数据结构 · 课前 20 分钟", Alarms.describe(a, courses))
        val manual = alarmAt(6, 30, Alarms.WORKDAYS)
        assertEquals("闹钟", Alarms.describe(manual, courses))
        assertEquals("起床", Alarms.describe(manual.copy(label = "起床"), courses))
    }

    @Test
    fun `课被删掉的课程闹钟会被标出来`() {
        val a = courseAlarm("c14")
        assertFalse(Alarms.isOrphan(a, courses))
        assertTrue(Alarms.isOrphan(a, emptyList()))
        assertFalse("手建闹钟永远不算孤儿", Alarms.isOrphan(alarmAt(6, 0, Alarms.WORKDAYS), emptyList()))
    }

    @Test
    fun `贪睡下限不低于十分钟`() {
        // Doze 下穿透闹钟大约每 9 分钟才放行一次，5 分钟会被系统推到 10 分钟后
        assertEquals(10, Alarms.MIN_SNOOZE_MINUTES)
        assertTrue(Alarm(id = "a").snoozeMinutes >= Alarms.MIN_SNOOZE_MINUTES)
    }
}
