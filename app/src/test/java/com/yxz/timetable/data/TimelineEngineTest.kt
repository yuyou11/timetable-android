package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 时间轴引擎的单元测试。
 *
 * 这些测试跑在**你电脑的 JVM 上**，不需要手机、不需要模拟器，
 * 一条命令几秒钟跑完。这就是「把纯逻辑和 Android 框架解耦」的回报：
 * TimelineEngine / Templates / Slots / BuiltinCourses 全都不碰 Context，
 * 所以它们可以被任意测试。
 *
 * 反过来说：如果把这些逻辑写进 Activity 里，就只能装到手机上手动点着试，
 * 而且每改一行都要重新试一遍。**这不是洁癖，是能不能持续改动的问题。**
 */
class TimelineEngineTest {

    private val courses = BuiltinCourses.LIST
    private val termStart = LocalDate.of(2026, 9, 7)   // 教学第 1 周的周一

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun weekOf(date: LocalDate) = TimelineEngine.weekOf(date, termStart)

    /**
     * 这个测试类测的是**引擎和内置模板本身**，所以要关掉日型策略的裁剪
     * —— 用 [DayTypePolicy.ALL] 让映射变成恒等，算出什么就用什么。
     *
     * 不这么做的话，「周二晚间是力量 A 训练」这类断言会因为默认策略
     * 把 B_TRAIN_A 映射成 B_NORMAL 而失败 —— 那是**策略层**的行为，
     * 不该由这个类来管。策略本身在 DayTypePolicyTest 里单独测。
     */
    private val templatesAll = TemplateSet(policy = DayTypePolicy.ALL)

    private fun moments(date: LocalDate, week: Int) =
        TimelineEngine.moments(date, week, courses, templatesAll)

    /** 取某天某个时刻所处的那一格 */
    private fun at(date: LocalDate, week: Int, hhmm: String): Moment {
        val parts = hhmm.split(":")
        val minute = parts[0].toInt() * 60 + parts[1].toInt()
        return TimelineEngine.currentAt(moments(date, week), minute)!!
    }

    // ============================================================
    //  一、周次计算
    // ============================================================

    @Test
    fun `第 1 周从 9 月 7 日周一开始`() {
        assertEquals(1, weekOf(d(2026, 9, 7)))    // 周一
        assertEquals(1, weekOf(d(2026, 9, 13)))   // 周日，仍在第 1 周
    }

    @Test
    fun `第 2 周从 9 月 14 日开始`() {
        assertEquals(2, weekOf(d(2026, 9, 14)))
        assertEquals(2, weekOf(d(2026, 9, 20)))
    }

    @Test
    fun `第 17 周从 12 月 28 日开始`() {
        assertEquals(17, weekOf(d(2026, 12, 28)))
    }

    @Test
    fun `任意日期都能吸附到所在那一周的周一`() {
        // 2026-09-07 是周一
        assertEquals(d(2026, 9, 7), TimelineEngine.mondayOf(d(2026, 9, 7)))   // 周一 → 自己
        assertEquals(d(2026, 9, 7), TimelineEngine.mondayOf(d(2026, 9, 8)))   // 周二 → 前一天
        assertEquals(d(2026, 9, 7), TimelineEngine.mondayOf(d(2026, 9, 12)))  // 周六 → 往回 5 天
        assertEquals(d(2026, 9, 7), TimelineEngine.mondayOf(d(2026, 9, 13)))  // 周日 → 往回 6 天
        assertEquals(d(2026, 9, 14), TimelineEngine.mondayOf(d(2026, 9, 14))) // 下周一 → 自己
    }

    @Test
    fun `吸附到周一之后再算周次结果稳定`() {
        // 这条是真正要防的东西：吸附之后的日期算出来的周次，
        // 必须和用户预期的一致。偏移量写错一天，整个学期都会错位。
        for (offset in 0..20) {
            val anyDay = d(2026, 9, 7).plusDays(offset.toLong())
            val monday = TimelineEngine.mondayOf(anyDay)
            assertEquals("$anyDay 吸附后应落在周一", DayOfWeek.MONDAY, monday.dayOfWeek)
            assertTrue("$anyDay 吸附后不该跑到未来", !monday.isAfter(anyDay))

            // 同一周内的任何一天，吸附后算出的周次必须相同
            assertEquals(
                "同一周内周次不一致",
                TimelineEngine.weekOf(monday, termStart),
                TimelineEngine.weekOf(anyDay, termStart)
            )
        }
    }

    // ============================================================
    //  二、日型自动推导 —— 这是全 App 最核心的一条规则
    // ============================================================

    @Test
    fun `有早八的周一按 A 型日`() {
        // 第 3 周周一第 1-2 节有大学英语
        assertEquals(DayType.A, TimelineEngine.naturalDayType(d(2026, 9, 21), 3, courses))
    }

    @Test
    fun `还没开课的周一不该按 A 型日`() {
        // 第 1 周大学英语还没开（它是 2-4、6-17 周）→ 没有早八 → B 型
        // 如果这里写死成「周一 = A 型」，开学前一周就会误报 06:55 起床
        assertEquals(DayType.B_NORMAL, TimelineEngine.naturalDayType(d(2026, 9, 7), 1, courses))
    }

    @Test
    fun `第 8 周周三停课所以退回 B 型`() {
        // 高数周三 1-2 节是「2-7、9-17 周」，第 8 周正好不上
        assertEquals(DayType.B_NORMAL, TimelineEngine.naturalDayType(d(2026, 10, 28), 8, courses))
    }

    @Test
    fun `周二周四是训练日`() {
        assertEquals(DayType.B_TRAIN_A, TimelineEngine.naturalDayType(d(2026, 9, 22), 3, courses))
        assertEquals(DayType.B_TRAIN_B, TimelineEngine.naturalDayType(d(2026, 9, 24), 3, courses))
    }

    @Test
    fun `周末用周六周日的模板`() {
        assertEquals(DayType.SATURDAY, TimelineEngine.naturalDayType(d(2026, 9, 26), 3, courses))
        assertEquals(DayType.SUNDAY, TimelineEngine.naturalDayType(d(2026, 9, 27), 3, courses))
    }

    // ============================================================
    //  三、课表覆盖模板
    // ============================================================

    @Test
    fun `周一早八显示成课程而不是「第 1-2 节」`() {
        val m = at(d(2026, 9, 21), 3, "09:00")
        assertEquals("大学英语", m.title)
        assertEquals("教一-101", m.place)
        assertEquals(Kind.CLASS, m.kind)
        assertTrue(m.isCourse)
    }

    @Test
    fun `连堂合并成一整块而不是两节`() {
        val m = at(d(2026, 9, 21), 3, "09:00")
        assertEquals(8 * 60 + 30, m.start)   // 08:30
        assertEquals(10 * 60 + 5, m.end)     // 10:05，中间那 5 分钟算在里面
    }

    @Test
    fun `没有课的格子显示「无课」而不是留空`() {
        // 第 1 周周一还没开课，第 7-8 节是空的课表格子
        val m = at(d(2026, 9, 7), 1, "16:00")
        assertTrue("实际得到: ${m.title}", m.title.contains("无课"))
    }

    @Test
    fun `大学物理实验周二 5-6 节只在第 4 周出现`() {
        assertEquals("大学物理实验", at(d(2026, 9, 29), 4, "15:00").title)
        // 第 5 周周二同一时段没有这门课
        assertFalse(at(d(2026, 10, 6), 5, "15:00").title.startsWith("大学物理实验"))
    }

    @Test
    fun `周四 7-8 节的单双周切换`() {
        // 第 2 周这里是程序设计基础
        assertEquals("程序设计基础", at(d(2026, 9, 17), 2, "16:00").title)
        // 第 3 周（单周）起是数据结构
        assertEquals("数据结构", at(d(2026, 9, 24), 3, "16:00").title)
        // 第 4 周是双周，数据结构不上 → 回到「无课」
        assertTrue(at(d(2026, 10, 1), 4, "16:00").title.contains("无课"))
    }

    @Test
    fun `周三晚课人工智能导论在第 10 周正常出现`() {
        assertEquals("人工智能导论", at(d(2026, 11, 11), 10, "19:30").title)
    }

    @Test
    fun `周二晚 19 点是训练而不是晚自习`() {
        val m = at(d(2026, 9, 22), 3, "19:30")
        assertTrue("实际得到: ${m.title}", m.title.contains("训练"))
        assertEquals(Kind.TRAIN, m.kind)
    }

    @Test
    fun `周四晚的训练是力量 B`() {
        assertTrue(at(d(2026, 9, 24), 3, "19:30").note.contains("力量 B"))
    }

    // ============================================================
    //  四、结构性不变量
    //
    //  这两条比上面所有单点断言都值钱：
    //  单点断言只能证明「我想到的那几种情况是对的」，
    //  而遍历整学期能证明「不存在我没想到的坏情况」。
    // ============================================================

    @Test
    fun `整个学期每天的时间轴都首尾相接无空洞`() {
        for (week in 1..19) {
            for (dow in 0..6) {
                val date = termStart.plusDays((week - 1) * 7L + dow)
                val ms = moments(date, week)

                assertTrue("$date 时间轴为空", ms.isNotEmpty())
                assertEquals("$date 没有从 00:00 开始", 0, ms.first().start)
                assertEquals("$date 没有到 24:00 结束", 1440, ms.last().end)

                for (i in 1 until ms.size) {
                    assertEquals(
                        "$date 第 $i 格与上一格之间有空洞或重叠",
                        ms[i - 1].end, ms[i].start
                    )
                }
            }
        }
    }

    @Test
    fun `有课的时段不会同时留下一个「第 N 节」占位格`() {
        // 周一第 3 周：1-2 英语、3-4 高数、5-6 无课、7-8 军事理论、9-10 无课
        val ms = moments(d(2026, 9, 21), 3)
        val classBlocks = ms.filter { it.kind == Kind.CLASS }
        assertEquals("课表格子的数量应恒等于模板里的 5 个节次段", 5, classBlocks.size)

        val titles = classBlocks.map { it.title }
        assertTrue(titles.any { it == "大学英语" })
        assertTrue(titles.any { it.contains("无课") })
    }

    @Test
    fun `即使课程跨越了午餐和午睡，时间轴也不会错乱`() {
        // 人为造一门 3-8 节连上的怪课，专门压测 assemble() 的收口能力。
        // 现实里不会这么排，但边界情况正是 bug 的藏身处。
        val weird = listOf(Course("x1", "占位测试课", 2, 3, 8, (1..19).toSet(), "X-101"))
        val ms = TimelineEngine.moments(d(2026, 9, 22), 3, weird)

        assertEquals(0, ms.first().start)
        assertEquals(1440, ms.last().end)
        for (i in 1 until ms.size) assertEquals(ms[i - 1].end, ms[i].start)
        assertTrue(ms.any { it.title == "占位测试课" })
        // 被这门课盖住的「午餐」应该已经消失，而不是和被覆盖的课并存
        assertFalse(ms.any { it.title == "午餐" })
    }

    @Test
    fun `停掉早八课之后整个上午会自动退化成 B 型`() {
        // 把周一早八的大学英语停掉 → 程序判定「今天没有早八」→ 日型自动变 B 型，
        // 08:00-10:05 那一格从「第 1-2 节」变成黄金自习块。
        //
        // 这条最能说明「规则要推导、不要写死」：没有任何一行代码在描述
        // 「停课之后要切模板」，它是 dayType() 那条判断自然产生的结果。
        val disabled = courses.map { if (it.name == "大学英语") it.copy(enabled = false) else it }
        val ms = TimelineEngine.moments(d(2026, 9, 21), 3, disabled)

        assertEquals(DayType.B_NORMAL, TimelineEngine.naturalDayType(d(2026, 9, 21), 3, disabled))
        val m = TimelineEngine.currentAt(ms, 9 * 60)!!
        assertTrue("实际得到: ${m.title}", m.title.contains("黄金自习块"))
    }

    // ============================================================
    //  五、起床时间推导
    // ============================================================

    @Test
    fun `A 型日的起床时间是 06 时 55 分`() {
        assertEquals(6 * 60 + 55, TimelineEngine.wakeMinute(Templates.A))
    }

    @Test
    fun `B 型日的起床时间是 07 时 25 分`() {
        assertEquals(7 * 60 + 25, TimelineEngine.wakeMinute(Templates.B_NORMAL))
        assertEquals(7 * 60 + 25, TimelineEngine.wakeMinute(Templates.B_TRAIN_A))
    }

    @Test
    fun `周六是可以睡到自然醒的 09 时`() {
        assertEquals(9 * 60, TimelineEngine.wakeMinute(Templates.SATURDAY))
    }

    @Test
    fun `周日是 08 时 30 分`() {
        assertEquals(8 * 60 + 30, TimelineEngine.wakeMinute(Templates.SUNDAY))
    }

    @Test
    fun `六种内置模板都能推出起床时间`() {
        // 这条是防回归的：将来有人改了模板里某个 kind，导致找不到上午的睡眠段，
        // 次日预告上那一行会凭空消失。这个测试会立刻发现。
        for (type in Templates.ALL_TYPES) {
            val wake = TimelineEngine.wakeMinute(Templates.builtin(type))
            assertTrue("$type 推不出起床时间", wake != null)
            assertTrue("$type 的起床时间 $wake 不像早晨", wake!! in 5 * 60..11 * 60)
        }
    }

    @Test
    fun `没有上午睡眠段时返回 null 而不是瞎猜`() {
        val noSleep = listOf(
            Block(0, 1440, "连续工作", "", Kind.STUDY)
        )
        assertNull(TimelineEngine.wakeMinute(noSleep))
    }

    // ============================================================
    //  六、自定义模板
    // ============================================================

    @Test
    fun `自定义模板会覆盖内置模板`() {
        // 把 A 型日的起床时间从 06:55 改成 08:00
        val custom = listOf(
            Block(0, 8 * 60, "睡觉", "", Kind.SLEEP),
            Block(8 * 60, 1440, "全天自习", "", Kind.STUDY)
        )
        val set = TemplateSet(mapOf(DayType.A to custom))

        assertEquals(8 * 60, TimelineEngine.wakeMinute(set.of(DayType.A)))
        // 没被覆盖的日型继续用内置
        assertEquals(7 * 60 + 25, TimelineEngine.wakeMinute(set.of(DayType.B_NORMAL)))
    }

    @Test
    fun `改了模板之后时间轴上的内容跟着变`() {
        // 把 A 型日改成「07:30 起床 + 自定义早晨」
        val custom = listOf(
            Block(0, 7 * 60 + 30, "睡觉", "", Kind.SLEEP),
            Block(7 * 60 + 30, 8 * 60 + 30, "自定义的早晨", "改过的", Kind.STUDY),
            Block(8 * 60 + 30, 1440, "余下的时间", "", Kind.FREE)
        )
        val set = TemplateSet(mapOf(DayType.A to custom))
        val ms = TimelineEngine.moments(d(2026, 9, 21), 3, courses, set)

        // 起床时间跟着模板走了
        assertEquals(7 * 60 + 30, TimelineEngine.wakeMinute(set.of(DayType.A)))

        // 08:00 这一段没人跟它抢，显示自定义内容
        assertEquals("自定义的早晨", TimelineEngine.currentAt(ms, 8 * 60)!!.title)

        // 时间轴依然首尾相接
        assertEquals(0, ms.first().start)
        assertEquals(1440, ms.last().end)
        for (i in 1 until ms.size) assertEquals(ms[i - 1].end, ms[i].start)
    }

    @Test
    fun `课程永远优先于模板——即使自定义模板里没留课表格子`() {
        // 这条测试纠正了我自己一开始的错误预期。
        //
        // 我以为「模板里不写带 nodes 的占位格，就不会有课了」。
        // 实际上不是：课程会作为独立日程**插进时间轴并裁掉冲突的模板格子**。
        //
        // 为什么这个行为是对的？因为模板回答的是「正常情况下这个点干嘛」，
        // 课表回答的是「学校规定这个点必须在哪」—— 后者是硬约束。
        // 如果用户改模板就能让课凭空消失，那是灾难。
        val custom = listOf(
            Block(0, 8 * 60 + 30, "睡觉", "", Kind.SLEEP),
            Block(8 * 60 + 30, 1440, "我安排的一整天", "", Kind.STUDY)
        )
        val set = TemplateSet(mapOf(DayType.A to custom))
        val ms = TimelineEngine.moments(d(2026, 9, 21), 3, courses, set)   // 周一第 3 周

        // 08:30-10:05 本该是「我安排的一整天」，被大学英语顶掉了
        assertEquals("大学英语", TimelineEngine.currentAt(ms, 9 * 60)!!.title)
        // 而空着的时段仍然是自定义内容
        assertEquals("我安排的一整天", TimelineEngine.currentAt(ms, 12 * 60 + 30)!!.title)
        // 时间轴依旧连续
        for (i in 1 until ms.size) assertEquals(ms[i - 1].end, ms[i].start)
    }

    @Test
    fun `自定义模板下课程依然能覆盖占位格`() {
        val custom = listOf(
            Block(0, 8 * 60 + 30, "睡觉", "", Kind.SLEEP),
            // 自己排的课表格子：只有第 1-2 节
            Block(8 * 60 + 30, 10 * 60 + 5, "第 1-2 节", "", Kind.CLASS, 1..2),
            Block(10 * 60 + 5, 1440, "自由", "", Kind.FREE)
        )
        val set = TemplateSet(mapOf(DayType.A to custom))
        val ms = TimelineEngine.moments(d(2026, 9, 21), 3, courses, set)

        // 周一 1-2 节有大学英语，应该覆盖掉自定义的占位格
        assertEquals("大学英语", TimelineEngine.currentAt(ms, 9 * 60)!!.title)
    }

    @Test
    fun `停掉一门非早八课只影响那一格`() {
        val disabled = courses.map { if (it.name == "军事理论") it.copy(enabled = false) else it }
        val ms = TimelineEngine.moments(d(2026, 9, 21), 3, disabled)

        // 上午的英语照常 —— 因为早八还在，日型仍是 A 型
        assertEquals("大学英语", TimelineEngine.currentAt(ms, 9 * 60)!!.title)
        // 只有第 7-8 节退回「无课」
        assertTrue(TimelineEngine.currentAt(ms, 16 * 60)!!.title.contains("无课"))
    }
}
