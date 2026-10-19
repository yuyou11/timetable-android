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

    // ---------- 六、只响一次（七天的勾全取消） ----------

    @Test
    fun `七天全不选就是只响一次`() {
        val now = LocalDateTime.of(2026, 11, 18, 20, 0)
        val a = Alarm(
            id = "a1", hour = 7, minute = 0, days = emptySet(),
            onceDate = Alarms.oneShotDate(7, 0, now)
        )
        assertTrue(Alarms.isOneShot(a))
        assertEquals("晚上 8 点设的，明天早上 7 点响", LocalDate.of(2026, 11, 19), a.onceDate)
        assertEquals(7 * 60, fire(a, LocalDate.of(2026, 11, 19)))
        assertNull("过了那一天就不再响", fire(a, LocalDate.of(2026, 11, 20)))
        assertNull("前一天也不该响", fire(a, LocalDate.of(2026, 11, 18)))
    }

    @Test
    fun `只响一次的日期 今天这个点还没过就是今天`() {
        val now = LocalDateTime.of(2026, 11, 18, 6, 0)
        assertEquals(LocalDate.of(2026, 11, 18), Alarms.oneShotDate(7, 0, now))
        assertEquals(LocalDate.of(2026, 11, 19), Alarms.oneShotDate(5, 0, now))
        assertEquals("正好等于此刻算明天，免得刚存下就立刻响", LocalDate.of(2026, 11, 19), Alarms.oneShotDate(6, 0, now))
    }

    @Test
    fun `只响一次不受节假日跳过影响`() {
        // 用户明确指定了「就这一次、就这个点」——静默跳过等于让他误事
        val now = LocalDateTime.of(2026, 9, 30, 22, 0)
        val a = Alarm(
            id = "a1", hour = 7, minute = 0, days = emptySet(),
            onceDate = Alarms.oneShotDate(7, 0, now)     // 落在 10/1 国庆
        )
        assertEquals(LocalDate.of(2026, 10, 1), a.onceDate)
        assertEquals(7 * 60, fire(a, LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `只响一次的闹钟只被 nextFire 找到一次`() {
        val a = Alarm(
            id = "a1", hour = 7, minute = 0, days = emptySet(),
            onceDate = LocalDate.of(2026, 11, 19)
        )
        val first = Alarms.nextFire(
            listOf(a), LocalDateTime.of(2026, 11, 18, 20, 0),
            courses, templates, holidays, ::weekOf
        )!!
        assertEquals(LocalDate.of(2026, 11, 19), first.date)
        // 响过之后接收器会把它关掉；关掉之后 nextFire 就找不到它了
        val off = a.copy(enabled = false)
        assertNull(
            Alarms.nextFire(
                listOf(off), LocalDateTime.of(2026, 11, 19, 8, 0),
                courses, templates, holidays, ::weekOf
            )
        )
    }

    // ---------- 六·五、明天卡片的起床闹钟 ----------

    @Test
    fun `起床闹钟只响明天那一天`() {
        val date = LocalDate.of(2026, 11, 19)
        val a = Alarms.forWakeOnce("a1", date, 6 * 60 + 55)
        assertTrue(Alarms.isOneShot(a))
        assertEquals("06:55", a.timeText)
        assertEquals(6 * 60 + 55, fire(a, date))
        assertNull("前一天不响", fire(a, date.minusDays(1)))
        assertNull("过完这一天就再也不响", fire(a, date.plusDays(1)))
    }

    @Test
    fun `起床闹钟减掉提前量`() {
        val a = Alarms.forWakeOnce("a1", LocalDate.of(2026, 11, 19), 6 * 60 + 55, 5)
        assertEquals("提前 5 分钟就是 06:50", "06:50", a.timeText)
    }

    @Test
    fun `提前量比起床时间还大时按 0 点算`() {
        // 00:10 起、提前 20 分钟。算成「昨天 23:50」的话它永远不响，还查不出来
        val a = Alarms.forWakeOnce("a1", LocalDate.of(2026, 11, 19), 10, 20)
        assertEquals("00:00", a.timeText)
        assertEquals(0, fire(a, LocalDate.of(2026, 11, 19)))
    }

    @Test
    fun `同一天再设一次是改时刻 不是多出一条`() {
        val date = LocalDate.of(2026, 11, 19)
        val first = Alarms.withWakeOnce(emptyList(), date, 6 * 60 + 55, 0) { "a1" }
        assertEquals(1, first.size)

        val again = Alarms.withWakeOnce(first, date, 6 * 60 + 55, 10) { "a2" }
        assertEquals("再点一次不该多出一条", 1, again.size)
        assertEquals("id 还是原来那条", "a1", again[0].id)
        assertEquals("改成提前 10 分钟", "06:45", again[0].timeText)
    }

    @Test
    fun `重设起床闹钟会重新打开 并清掉跳过的这一天`() {
        val date = LocalDate.of(2026, 11, 19)
        val off = Alarms.forWakeOnce("a1", date, 6 * 60 + 55)
            .copy(enabled = false, skipDate = date, soundUri = "content://bell")
        val list = Alarms.withWakeOnce(listOf(off), date, 6 * 60 + 55, 5) { "a2" }

        assertEquals(1, list.size)
        assertTrue("重设就是要它响", list[0].enabled)
        assertNull("长按跳过的正是明天，得清掉", list[0].skipDate)
        assertEquals("用户换过的铃声要留着", "content://bell", list[0].soundUri)
    }

    @Test
    fun `隔一天的起床闹钟各是各的`() {
        val d1 = LocalDate.of(2026, 11, 19)
        val d2 = d1.plusDays(1)
        val one = Alarms.withWakeOnce(emptyList(), d1, 6 * 60 + 55, 0) { "a1" }
        val two = Alarms.withWakeOnce(one, d2, 7 * 60 + 25, 0) { "a2" }

        assertEquals(2, two.size)
        assertNotNull("明天那条还在", Alarms.findWakeOnce(two, d1))
        assertNotNull("后天那条也在", Alarms.findWakeOnce(two, d2))
    }

    @Test
    fun `用户自己建的只响一次 不会被起床闹钟顶掉`() {
        // 身份靠 label 认：同样是「只响一次、就在明天」，但那是「赶飞机」
        val date = LocalDate.of(2026, 11, 19)
        val mine = Alarm(
            id = "a1", hour = 9, minute = 0, days = emptySet(),
            label = "赶飞机", onceDate = date
        )
        val after = Alarms.withWakeOnce(listOf(mine), date, 6 * 60 + 55, 0) { "a2" }

        assertEquals("两条都要在", 2, after.size)
        assertEquals(9 * 60, fire(after[0], date))
        assertEquals(6 * 60 + 55, fire(after[1], date))
    }

    @Test
    fun `起床闹钟会被 nextFire 排上`() {
        val date = LocalDate.of(2026, 11, 19)
        val a = Alarms.forWakeOnce("a1", date, 6 * 60 + 55)
        val next = Alarms.nextFire(
            listOf(a), LocalDateTime.of(2026, 11, 18, 22, 0),
            courses, templates, holidays, ::weekOf
        )!!

        assertEquals(date, next.date)
        assertEquals(6 * 60 + 55, next.minuteOfDay)
    }

    // ---------- 四·五、跳过下一次 / 过期 ----------

    @Test
    fun `跳过下一次只让那一天不响`() {
        val a = alarmAt(6, 30, Alarms.WORKDAYS)
        val wed = LocalDate.of(2026, 11, 18)
        val skipped = a.copy(skipDate = wed)
        assertNull("跳过的那天不响", fire(skipped, wed))
        assertEquals("前一天照常", 6 * 60 + 30, fire(skipped, wed.minusDays(1))!!)
        assertEquals("后一天照常", 6 * 60 + 30, fire(skipped, wed.plusDays(1))!!)
    }

    @Test
    fun `课程闹钟也能跳过下一次`() {
        val a = courseAlarm("c14")                       // 周四的课
        val thu = LocalDate.of(2026, 9, 24)
        assertNotNull("原本该响", fire(a, thu))
        assertNull("跳过之后这一天不响", fire(a.copy(skipDate = thu), thu))
    }

    @Test
    fun `跳过的那天不会被 nextFire 排进去`() {
        val a = alarmAt(6, 30, Alarms.WORKDAYS)
            .copy(skipDate = LocalDate.of(2026, 11, 18))   // 周三
        val next = Alarms.nextFire(
            listOf(a), LocalDateTime.of(2026, 11, 17, 20, 0),
            courses, templates, holidays, ::weekOf
        )!!
        assertEquals("跳过周三之后，下一次是周四", LocalDate.of(2026, 11, 19), next.date)
    }

    @Test
    fun `只响一次过期之后会被标出来 也不再被排程`() {
        val a = Alarm(
            id = "a1", hour = 7, minute = 0, days = emptySet(),
            onceDate = LocalDate.of(2026, 11, 19)
        )
        assertFalse("当天还没过期", Alarms.isExpiredOneShot(a, LocalDate.of(2026, 11, 19)))
        assertTrue("过了那天就是过期", Alarms.isExpiredOneShot(a, LocalDate.of(2026, 11, 20)))
        assertFalse(
            "重复闹钟没有过期这回事",
            Alarms.isExpiredOneShot(alarmAt(7, 0, Alarms.WORKDAYS), LocalDate.of(2026, 11, 20))
        )
        assertNull(
            Alarms.nextFire(
                listOf(a), LocalDateTime.of(2026, 11, 20, 0, 1),
                courses, templates, holidays, ::weekOf
            )
        )
    }

    @Test
    fun `替换课表后 课程闹钟按自然键接回新 id`() {
        val old = listOf(
            Course("c001", "高数", 1, 1, 2, (1..4).toSet()),
            Course("c002", "英语", 2, 3, 4, (1..4).toSet())
        )
        // 新表顺序颠倒 —— id 全变了，但两门课还是同样两门
        val new = listOf(
            Course("c001", "英语", 2, 3, 4, (1..4).toSet()),
            Course("c002", "高数", 1, 1, 2, (1..4).toSet())
        )
        val alarms = listOf(
            Alarm(id = "a1", days = emptySet(), source = AlarmSource.COURSE, courseId = "c001", leadMinutes = 10)
        )
        val out = Alarms.remapCourseAlarms(alarms, old, new)
        assertEquals("高数在新表里是 c002", "c002", out.first().courseId)
        assertTrue("接上了就不该被停用", out.first().enabled)
    }

    @Test
    fun `替换后找不到的课程闹钟会被停用`() {
        val old = listOf(Course("c001", "高数", 1, 1, 2, (1..4).toSet()))
        val new = listOf(Course("c001", "英语", 2, 3, 4, (1..4).toSet()))
        val alarms = listOf(
            Alarm(id = "a1", days = emptySet(), source = AlarmSource.COURSE, courseId = "c001", leadMinutes = 10)
        )
        val out = Alarms.remapCourseAlarms(alarms, old, new)
        assertFalse("课没了就该停用，不能留一条永远不响的闹钟", out.first().enabled)
    }

    @Test
    fun `手建闹钟不受课表替换影响`() {
        val manual = alarmAt(6, 30, Alarms.WORKDAYS)
        val out = Alarms.remapCourseAlarms(
            listOf(manual),
            listOf(Course("c001", "高数", 1, 1, 2, (1..4).toSet())),
            emptyList()
        )
        assertEquals(manual, out.first())
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

    // ---------- 六、按格子响的闹钟（今日页点一格设的那种） ----------

    /**
     * 七种日型都换成同一套简单模板，只为了让断言不依赖任何一天的课表。
     *
     * 用 `DayType.entries` 全铺满而不是只写 A：哪天落到哪套模板是引擎算出来的，
     * 测试里再猜一遍没意义，也不该因为默认映射改了就让这些断言失败。
     */
    private fun flatTemplates(includeLunch: Boolean = true): TemplateSet {
        val blocks = buildList {
            add(Block(0, 7 * 60, "睡觉", "", Kind.SLEEP))
            add(Block(7 * 60, 8 * 60, "早读", "", Kind.STUDY))
            add(Block(8 * 60, 12 * 60, "自习", "", Kind.STUDY))
            if (includeLunch) add(Block(12 * 60, 13 * 60, "午餐", "", Kind.MEAL))
            add(Block(13 * 60, 24 * 60, "自由", "", Kind.FREE))
        }
        return TemplateSet(custom = DayType.entries.associateWith { blocks })
    }

    private fun fireMoment(a: Alarm, date: LocalDate, set: TemplateSet = flatTemplates()) =
        Alarms.fireMinuteOfDay(a, date, weekOf(date), emptyList(), set, holidays)

    /** 普通周三，不是节假日 */
    private val wed = LocalDate.of(2026, 11, 18)

    @Test
    fun `按格子响的闹钟 0 分钟档就是到点响`() {
        val a = Alarms.forMoment("a100", "午餐", Kind.MEAL, setOf(3), leadMinutes = 0)
        assertEquals("到点 = 这一格的开始时刻", 12 * 60, fireMoment(a, wed)!!)
    }

    @Test
    fun `按格子响的闹钟 提前量从这一格的开始时刻往前推`() {
        val a = Alarms.forMoment("a101", "午餐", Kind.MEAL, setOf(3), leadMinutes = 5)
        assertEquals(11 * 60 + 55, fireMoment(a, wed)!!)
    }

    @Test
    fun `按格子响的闹钟 时间轴上没有这一格就不响`() {
        // 把午餐从模板里拿掉 —— 用户改了作息之后，这一格不存在了，闹钟自然不该响
        val a = Alarms.forMoment("a102", "午餐", Kind.MEAL, setOf(3))
        assertNull(fireMoment(a, wed, flatTemplates(includeLunch = false)))
    }

    @Test
    fun `按格子响的闹钟 只在这一天响`() {
        val a = Alarms.forMoment("a103", "早读", Kind.STUDY, setOf(3))
        assertNotNull("周三该响", fireMoment(a, wed))
        assertNull("周四是另一天", fireMoment(a, LocalDate.of(2026, 11, 19)))
    }

    @Test
    fun `标题一样但性质不同 不算同一格`() {
        // 「自由」和「自习」标题不同；反过来同名不同性质（比如都是「放松」
        // 但一格是 TRAIN 一格是 FREE）也不该被认成一格
        val a = Alarms.forMoment("a104", "早读", Kind.TRAIN, setOf(3))
        assertNull("早读是 STUDY，不是 TRAIN", fireMoment(a, wed))
    }

    @Test
    fun `按日型响的闹钟 日型不符就不响`() {
        // 没课的工作日 → REST；排上一节课就不再是 REST 了（日型变了）
        val a = Alarms.forMoment("a105", "午餐", Kind.MEAL, setOf(3), DayType.REST)
        assertNotNull("休息日该响", fireMoment(a, wed))

        val withCourse = listOf(Course("c1", "高等数学", 3, 1, 2, setOf(weekOf(wed))))
        assertNull(
            "那天有早八，日型就不是 REST 了",
            Alarms.fireMinuteOfDay(a, wed, weekOf(wed), withCourse, flatTemplates(), holidays)
        )
    }

    @Test
    fun `提前量比这一天还早 就不响而不是算出负数`() {
        // 睡觉这一格从 00:00 开始，提前 5 分钟 → -5 分钟，那天没有这一刻
        val a = Alarms.forMoment("a106", "睡觉", Kind.SLEEP, setOf(3), leadMinutes = 5)
        assertNull(fireMoment(a, wed))
    }

    @Test
    fun `按格子响的闹钟的文案`() {
        val a = Alarms.forMoment("a107", "午餐", Kind.MEAL, setOf(3), leadMinutes = 0)
        assertEquals("午餐 · 到点提醒", Alarms.describe(a, emptyList()))
        assertEquals("每周三", Alarms.momentScope(a))
        assertEquals("提前 5 分钟", a.copy(leadMinutes = 5).leadText)

        val byType = a.copy(momentDayType = DayType.A)
        assertEquals("每个「A 型日」", Alarms.momentScope(byType))
    }

    @Test
    fun `时间轴上已经没有这一格时 闹钟要能被标出来`() {
        val a = Alarms.forMoment("a108", "午餐", Kind.MEAL, setOf(3))
        assertFalse(
            "午餐还在时间轴上",
            Alarms.isLostMoment(a, emptyList(), flatTemplates(), this::weekOf, wed)
        )
        assertTrue(
            "模板里没午餐了，两周内都不会再出现",
            Alarms.isLostMoment(a, emptyList(), flatTemplates(includeLunch = false), this::weekOf, wed)
        )
        assertFalse(
            "课程闹钟不走这套判断",
            Alarms.isLostMoment(courseAlarm("c14"), courses, templates, this::weekOf, wed)
        )
    }
}
