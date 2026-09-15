package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日型策略的测试 —— 「这份配置实际用哪几种日型」。
 *
 * ## 背景
 *
 * 引擎原来固定算六种日型，其中 `B_TRAIN_A / B_TRAIN_B`（周二力量A、周四力量B）
 * 来自某一份具体规划表里的训练安排，不是通用规律。
 * 现在改成可配置：JSON 里写 `dayTypes.enabled` 决定用哪几种，
 * 不在其中的一律落到 `dayTypes.fallback`。
 *
 * 这个文件守两件事：
 *
 *   ① **默认策略不能把「有早八 / 没早八」这档区分弄丢** ——
 *      那是这个 App 存在的理由（它回答的就是「今天要不要早起」）
 *   ② 策略要真的穿透到 [TimelineEngine.moments]，而不只是改个标签
 */
class DayTypePolicyTest {

    private val courses = BuiltinCourses.LIST
    private val termStart = LocalDate.of(2026, 9, 7)   // 教学第 1 周的周一

    private fun d(day: Int) = LocalDate.of(2026, 9, day)
    private fun week3(date: LocalDate) = TimelineEngine.weekOf(date, termStart)

    // 第 3 周：周一 9/21、周二 9/22、周四 9/24、周五 9/25、周六 9/26、周日 9/27
    private val mon = d(21)
    private val tue = d(22)
    private val thu = d(24)
    private val fri = d(25)
    private val sat = d(26)
    private val sun = d(27)

    private fun typeOf(date: LocalDate, policy: DayTypePolicy) =
        TimelineEngine.dayType(date, week3(date), courses, policy)

    private fun wakeOf(date: LocalDate, policy: DayTypePolicy): Int? {
        val set = TemplateSet(policy = policy)
        return TimelineEngine.wakeMinute(
            TimelineEngine.template(typeOf(date, policy), set)
        )
    }

    // ============================================================
    //  一、原始规则本身没有被改坏
    // ============================================================

    @Test
    fun `原始规则仍然算得出全部六种日型`() {
        // 政策层的改动**不能**动到日历规则本身。
        // 这条保证了「想让训练日生效」时，引擎还拿得出那个答案。
        assertEquals(DayType.A, TimelineEngine.naturalDayType(mon, 3, courses))
        assertEquals(DayType.B_TRAIN_A, TimelineEngine.naturalDayType(tue, 3, courses))
        assertEquals(DayType.B_TRAIN_B, TimelineEngine.naturalDayType(thu, 3, courses))
        assertEquals(DayType.B_NORMAL, TimelineEngine.naturalDayType(fri, 3, courses))
        assertEquals(DayType.SATURDAY, TimelineEngine.naturalDayType(sat, 3, courses))
        assertEquals(DayType.SUNDAY, TimelineEngine.naturalDayType(sun, 3, courses))
    }

    @Test
    fun `ALL 策略是恒等映射`() {
        for (date in listOf(mon, tue, thu, fri, sat, sun)) {
            assertEquals(
                "ALL 策略不该改变任何日型",
                TimelineEngine.naturalDayType(date, week3(date), courses),
                typeOf(date, DayTypePolicy.ALL)
            )
        }
    }

    // ============================================================
    //  二、默认策略：保住核心区分，去掉训练日细分
    // ============================================================

    @Test
    fun `默认策略下周二不再算训练日`() {
        assertEquals(DayType.B_NORMAL, typeOf(tue, DayTypePolicy.DEFAULT))
        assertEquals(DayType.B_NORMAL, typeOf(thu, DayTypePolicy.DEFAULT))
    }

    @Test
    fun `默认策略保住了「有早八就是 A 型」`() {
        assertEquals(DayType.A, typeOf(mon, DayTypePolicy.DEFAULT))
    }

    @Test
    fun `默认策略保住了周末`() {
        assertEquals(DayType.SATURDAY, typeOf(sat, DayTypePolicy.DEFAULT))
        assertEquals(DayType.SUNDAY, typeOf(sun, DayTypePolicy.DEFAULT))
    }

    @Test
    fun `默认策略下没早八的日子仍然是 07 点 25 起床`() {
        // ★ 这条是默认值取舍的核心。
        //
        // 「有早八 06:55 起 / 没早八 07:25 起」是这个 App 存在的理由 ——
        // 它回答的就是「今天要不要早起」。默认策略削掉了训练日的细分，
        // 但**绝不能**顺手把这一档也弄丢：那样周二会被按 A 型排，
        // 没早八的日子也会 06:55 把人叫起来。
        assertEquals(7 * 60 + 25, wakeOf(tue, DayTypePolicy.DEFAULT))
        assertEquals(7 * 60 + 25, wakeOf(thu, DayTypePolicy.DEFAULT))
        assertEquals(7 * 60 + 25, wakeOf(fri, DayTypePolicy.DEFAULT))
        // 有早八的日子照旧 06:55
        assertEquals(6 * 60 + 55, wakeOf(mon, DayTypePolicy.DEFAULT))
    }

    @Test
    fun `默认策略真的穿透到了时间轴而不只是改了标签`() {
        // 日型不一致最典型的坏法：界面显示 B 型、实际按 A 型排。
        // 这条不看日型名字，**直接看排出来的时间轴** ——
        //
        // 每套模板的第一格都是「00:00 到起床」，
        // 所以第一格的结束时刻就是那天的起床时间，一目了然。
        val tueDefault = TimelineEngine.moments(
            tue, 3, courses, TemplateSet(policy = DayTypePolicy.DEFAULT)
        )
        assertEquals("周二应当按 B 型日排（07:25 起）", 7 * 60 + 25, tueDefault.first().end)

        val tueAll = TimelineEngine.moments(
            tue, 3, courses, TemplateSet(policy = DayTypePolicy.ALL)
        )
        assertEquals(
            "ALL 策略下周二走训练日模板，同样是 07:25 起 —— 但内容不同",
            7 * 60 + 25, tueAll.first().end
        )

        // 真正证明「策略生效了」的是：两种策略排出来的东西**不一样**
        assertTrue(
            "默认策略和 ALL 策略在周二应当排出不同的时间轴",
            tueDefault != tueAll
        )

        // 而周一（有早八）两种策略都该是 06:55 起，不受影响
        val monDefault = TimelineEngine.moments(
            mon, 3, courses, TemplateSet(policy = DayTypePolicy.DEFAULT)
        )
        assertEquals("周一有早八，仍是 06:55 起", 6 * 60 + 55, monDefault.first().end)
    }

    // ============================================================
    //  三、自定义策略
    // ============================================================

    @Test
    fun `只启用 A 和周末时 周二回落到 A 型`() {
        // 用户明确选择「我只要 A 型和周末」——这时周二就该按 A 型走，
        // 哪怕那意味着没早八的日子也 06:55 起床。
        // 这是**用户的选择**，不是 bug。
        val p = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY),
            fallback = DayType.A
        )
        assertEquals(DayType.A, typeOf(tue, p))
        assertEquals(DayType.A, typeOf(thu, p))
        assertEquals(DayType.A, typeOf(fri, p))
        assertEquals(DayType.SATURDAY, typeOf(sat, p))
        assertEquals(6 * 60 + 55, wakeOf(tue, p))
    }

    @Test
    fun `fallback 允许不在 enabled 里`() {
        // 「只启用 A 和周末，但周中没早八时要回落成 B 型」是完全正当的用法。
        // fallback 表达的是「拿哪套模板兜底」，和「启用了哪些日型」不是一回事。
        val p = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.SATURDAY),
            fallback = DayType.B_NORMAL
        )
        assertFalse(DayType.B_NORMAL in p.enabled)
        assertEquals(DayType.B_NORMAL, typeOf(fri, p))
        assertEquals(7 * 60 + 25, wakeOf(fri, p))
    }

    @Test
    fun `只启用训练日时其他日子全落到训练日模板`() {
        val p = DayTypePolicy(
            enabled = setOf(DayType.B_TRAIN_A),
            fallback = DayType.B_TRAIN_A
        )
        assertEquals(DayType.B_TRAIN_A, typeOf(mon, p))
        assertEquals(DayType.B_TRAIN_A, typeOf(tue, p))
        assertEquals(DayType.B_TRAIN_A, typeOf(sat, p))
    }

    // ============================================================
    //  四、JSON 解析
    // ============================================================

    private fun doc(dayTypes: String) =
        """{"format":"timetable","version":3,"dayTypes":$dayTypes,"courses":[]}"""

    private fun parsed(json: String): ScheduleFormat.Parsed {
        val r = ScheduleFormat.parse(json, 19)
        assertTrue("期望解析成功，实际失败", r is ScheduleFormat.Result.Ok)
        return (r as ScheduleFormat.Result.Ok).parsed
    }

    private fun failed(json: String): String {
        val r = ScheduleFormat.parse(json, 19)
        assertTrue("期望解析失败，实际成功", r is ScheduleFormat.Result.Failed)
        return (r as ScheduleFormat.Result.Failed).message
    }

    @Test
    fun `能解析一份完整的 dayTypes 段`() {
        val p = parsed(
            doc("""{"enabled":["A","SATURDAY","SUNDAY"],"fallback":"A"}""")
        ).dayTypes!!

        assertEquals(setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY), p.enabled)
        assertEquals(DayType.A, p.fallback)
    }

    @Test
    fun `没写 dayTypes 段时是 null 表示不动`() {
        // 和 courses 一样的「null = 不要动」语义。
        // 如果当成「用默认值」，用户每导入一份只改课表的文件，
        // 辛苦配好的日型策略都会被打回默认。
        val json = """{"format":"timetable","version":3,""" +
                """"courses":[{"name":"高数","dayOfWeek":1,"nodes":[1,2],"weeks":"1-5"}]}"""
        assertNull(parsed(json).dayTypes)
    }

    @Test
    fun `enabled 为空数组要报错`() {
        val msg = failed(doc("""{"enabled":[],"fallback":"A"}"""))
        assertTrue("要说明至少启用一种，实际：$msg", msg.contains("至少要启用一种"))
    }

    @Test
    fun `缺少 enabled 字段要报错`() {
        val msg = failed(doc("""{"fallback":"A"}"""))
        assertTrue("要提示缺 enabled，实际：$msg", msg.contains("enabled"))
    }

    @Test
    fun `日型名字大小写不敏感`() {
        val p = parsed(
            doc("""{"enabled":["a","b_normal","saturday"],"fallback":"b_normal"}""")
        ).dayTypes!!
        assertEquals(
            setOf(DayType.A, DayType.B_NORMAL, DayType.SATURDAY),
            p.enabled
        )
        assertEquals(DayType.B_NORMAL, p.fallback)
    }

    @Test
    fun `不认识的名字要报错并列出自定义项`() {
        val msg = failed(doc("""{"enabled":["A","FOO"],"fallback":"A"}"""))
        assertTrue("要指出第几项，实际：$msg", msg.contains("第 2 项"))
        assertTrue("要列出可用值，实际：$msg", msg.contains("B_TRAIN_A"))
    }

    @Test
    fun `省略 fallback 时按标准顺序取第一个已启用项`() {
        // 用固定顺序而不是集合遍历顺序 —— 后者每次运行可能不同，
        // 会让同一份文件解析出不同结果
        val p = parsed(
            doc("""{"enabled":["SUNDAY","B_TRAIN_B"]}""")
        ).dayTypes!!
        // 标准顺序是 A, B_TRAIN_A, B_TRAIN_B, B_NORMAL, SATURDAY, SUNDAY
        // 其中被启用的有 B_TRAIN_B 和 SUNDAY → 取更靠前的 B_TRAIN_B
        assertEquals(DayType.B_TRAIN_B, p.fallback)
    }

    @Test
    fun `四段全空才报错 dayTypes 单独存在也算有效内容`() {
        // 一份只写 dayTypes 的文件是正当的（只想改日型，课表模板都别动）
        val p = parsed(doc("""{"enabled":["A"],"fallback":"A"}"""))
        assertNull(p.courses)
        assertNull(p.templates)
        assertTrue(p.dayTypes != null)
    }

    // ============================================================
    //  五、往返
    // ============================================================

    @Test
    fun `导出再导入 日型策略不变`() {
        val policy = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY),
            fallback = DayType.B_NORMAL
        )
        val json = ScheduleFormat.serialize(
            termName = "测试学期",
            startDate = termStart,
            totalWeeks = 19,
            courses = emptyList(),
            templates = TemplateSet(policy = policy).expanded(),
            dayTypes = policy
        )
        val back = parsed(json).dayTypes!!
        assertEquals(policy.enabled, back.enabled)
        assertEquals(policy.fallback, back.fallback)
    }

    @Test
    fun `enabled 的输出顺序是固定的`() {
        // 输入顺序不同、内容相同 → 输出必须一致。
        // 否则同一份数据每次导出都产生 diff，版本对比就没法看了。
        val a = DayTypePolicy(
            enabled = setOf(DayType.SUNDAY, DayType.A, DayType.SATURDAY),
            fallback = DayType.A
        )
        val b = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY),
            fallback = DayType.A
        )
        assertEquals(
            ScheduleFormat.dayTypesToJson(a),
            ScheduleFormat.dayTypesToJson(b)
        )
        assertTrue(
            ScheduleFormat.dayTypesToJson(a).contains("""["A","SATURDAY","SUNDAY"]""")
        )
    }

    // ============================================================
    //  六、本地存储往返
    // ============================================================

    @Test
    fun `存进本地再读回来 策略不变`() {
        // 这个坑 templates 那边踩过一次：写出去的形状和读回来的对不上，
        // 结果用户改的设置**静默消失**。所以每个存储格式都要有一条往返测试。
        val policy = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.B_TRAIN_A, DayType.SATURDAY),
            fallback = DayType.B_TRAIN_A
        )
        val json = ScheduleFormat.dayTypesToJson(policy)
        val back = ScheduleFormat.dayTypesFromJson(json)

        assertEquals(policy.enabled, back.enabled)
        assertEquals(policy.fallback, back.fallback)
    }

    @Test
    fun `存储损坏时回落到默认策略而不是崩溃`() {
        // 存储损坏不能变成「程序打不开」—— 大不了重新配一遍
        assertEquals(DayTypePolicy.DEFAULT, ScheduleFormat.dayTypesFromJson(""))
        assertEquals(DayTypePolicy.DEFAULT, ScheduleFormat.dayTypesFromJson("{坏掉的"))
        assertEquals(DayTypePolicy.DEFAULT, ScheduleFormat.dayTypesFromJson("""{"enabled":[]}"""))
    }

    @Test
    fun `默认策略自己也经得起一次往返`() {
        val back = ScheduleFormat.dayTypesFromJson(
            ScheduleFormat.dayTypesToJson(DayTypePolicy.DEFAULT)
        )
        assertEquals(DayTypePolicy.DEFAULT, back)
    }
}
