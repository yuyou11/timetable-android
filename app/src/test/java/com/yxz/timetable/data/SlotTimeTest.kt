package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 守住「节次时间」只有一个来源。
 *
 * ============================================================
 *  这个 bug 是怎么被报上来的
 * ============================================================
 *
 * 用户说：「时间线改了，但是课表页面的上课时间没改。」
 *
 * 查下来：节次时间**写死在 [Slots] 里**（那是原作者的学校作息）。
 * 用户在模板里改了上课时间之后：
 *
 *   今日页  —— 课程按 `Slots` 摆（08:30-10:05），但周围的固定日程
 *              按模板摆（早读到 09:00）→ 两边错位，冒出一堆「空档 · 机动」
 *   课表页  —— 节次列读 `Slots`，纹丝不动
 *
 * 也就是说：**模板里写的上课时间被彻底忽略了**，而用户以为自己改成功了。
 *
 * ============================================================
 *  修法
 * ============================================================
 *
 * 反过来：**占位格里写的时刻就是准的**，`Slots` 降级为兜底
 * （某种日型没有那一段占位格时，比如 B 型日的第 1-2 节）。
 *
 * 这样三个地方由同一个来源推导，不可能再各说各话：
 *   · 课表页的节次列       —— `TemplateSet.slotStart/slotEnd`
 *   · 今日页里课的位置     —— `TimelineEngine.moments` 里同一对函数
 *   · 占位格的匹配         —— 同上
 */
class SlotTimeTest {

    private val courses = BuiltinCourses.LIST
    private val mon = LocalDate.of(2026, 9, 21)      // 第 3 周周一，有大学英语
    private val termStart = LocalDate.of(2026, 9, 7)

    /** 把第 1-2 节从 08:30 挪到 09:00、第 3-4 节从 10:25 挪到 10:55 的 A 型模板 */
    private fun shiftedA(): List<Block> = listOf(
        Block(0, 6 * 60 + 50, "睡觉", kind = Kind.SLEEP),
        Block(6 * 60 + 50, 7 * 60 + 10, "起床、洗漱"),
        Block(7 * 60 + 10, 7 * 60 + 40, "早餐", kind = Kind.MEAL),
        Block(7 * 60 + 40, 9 * 60, "早读/课前准备", kind = Kind.STUDY),
        Block(9 * 60, 10 * 60 + 35, "第 1-2 节", kind = Kind.CLASS, nodes = 1..2),
        Block(10 * 60 + 35, 10 * 60 + 55, "大课间"),
        Block(10 * 60 + 55, 12 * 60 + 30, "第 3-4 节", kind = Kind.CLASS, nodes = 3..4),
        Block(12 * 60 + 30, 1440, "下午其余", kind = Kind.FREE)
    )

    private fun set(vararg custom: Pair<DayType, List<Block>>) =
        TemplateSet(custom.toMap(), DayTypePolicy.ALL)

    // ============================================================
    //  一、默认行为不能变（内置模板和 Slots 是一致的）
    // ============================================================

    @Test
    fun `用内置模板时节次时间和 Slots 完全一致`() {
        val s = TemplateSet.BUILTIN
        for (n in listOf(1, 3, 5, 7, 9)) {
            assertEquals("第 $n 节开始", Slots.start(n), s.slotStart(n))
        }
        for (n in listOf(2, 4, 6, 8, 10)) {
            assertEquals("第 $n 节结束", Slots.end(n), s.slotEnd(n))
        }
    }

    @Test
    fun `用内置模板时端到端结果和以前一样`() {
        // 最要紧的回归保险：改了取值来源之后，内置模板下的时间轴必须一字不差
        val ms = TimelineEngine.moments(mon, 3, courses, TemplateSet.BUILTIN)
        val english = ms.first { it.title == "大学英语" }
        assertEquals(Slots.start(1), english.start)
        assertEquals(Slots.end(2), english.end)
        assertEquals(0, ms.count { it.title.contains("空档") })
    }

    // ============================================================
    //  二、改了模板里的节次时间，两处都要跟着变
    // ============================================================

    @Test
    fun `模板改了上课时间 课表页的节次列跟着变`() {
        val s = set(DayType.A to shiftedA())
        assertEquals("第 1-2 节应当用模板里的 09:00", 9 * 60, s.slotStart(1))
        assertEquals("第 1-2 节应当用模板里的 10:35", 10 * 60 + 35, s.slotEnd(2))
    }

    @Test
    fun `模板改了上课时间 今日页里课的位置也跟着变`() {
        // ★ 这条是用户报障的核心：改模板必须真的影响课摆在哪里
        val s = set(DayType.A to shiftedA())
        val ms = TimelineEngine.moments(mon, 3, courses, s)
        val english = ms.first { it.title == "大学英语" }
        assertEquals("大学英语应当按模板排到 09:00", 9 * 60, english.start)
        assertEquals(10 * 60 + 35, english.end)
    }

    @Test
    fun `两个页面报出来的时间必须一致`() {
        // ★★ 这条才是「bug 修好了」的判据。
        // 单独看「课表页变了」或「今日页变了」都不够 ——
        // 必须**两边说的是同一个时间**，否则只是换了一种不一致。
        val s = set(DayType.A to shiftedA())
        val ms = TimelineEngine.moments(mon, 3, courses, s)

        for ((startNode, endNode) in listOf(1 to 2, 3 to 4)) {
            val course = ms.firstOrNull {
                it.isCourse && it.start == s.slotStart(startNode)
            }
            assertTrue(
                "第 $startNode-$endNode 节：课表页写的是 " +
                        "${Slots.fmt(s.slotStart(startNode))}-${Slots.fmt(s.slotEnd(endNode))}，" +
                        "但在今日页里找不到任何一门课排在这个时刻 —— 两处对不上",
                course != null
            )
            assertEquals(
                "第 $startNode-$endNode 节两处的结束时间不一致",
                s.slotEnd(endNode), course!!.end
            )
        }
    }

    @Test
    fun `改模板之后不会再冒出空档碎片`() {
        // 修复前：课程按 Slots 摆、固定日程按模板摆，两者错位 →
        // 早读被砍短 + 冒出一块「空档 · 机动」。
        // 现在两边同源，不该再有碎片。
        val s = set(DayType.A to shiftedA())
        val ms = TimelineEngine.moments(mon, 3, courses, s)

        assertEquals(
            "不该出现「空档 · 机动」，实际：" +
                    ms.filter { it.title.contains("空档") }
                        .joinToString { "${Slots.fmt(it.start)}-${Slots.fmt(it.end)}" },
            0, ms.count { it.title.contains("空档") }
        )
        // 早读应当完整保留到 09:00，而不是被砍到 08:30
        val reading = ms.first { it.title.contains("早读") }
        assertEquals(9 * 60, reading.end)
    }

    // ============================================================
    //  三、没有占位格时回落到内置作息表
    // ============================================================

    @Test
    fun `某种日型缺占位格时回落 不崩`() {
        // B 型日本来就没有 1-2 节占位格。这时候 slotStart(1) 该回落到 Slots，
        // 而不是抛异常或者返回 0。
        val s = TemplateSet(mapOf(DayType.B_NORMAL to listOf(
            Block(0, 7 * 60 + 25, "睡觉", kind = Kind.SLEEP),
            Block(7 * 60 + 25, 1440, "白天", kind = Kind.FREE)
        )), DayTypePolicy.ALL)

        assertEquals(Slots.start(1), s.slotStart(1))
        assertEquals(Slots.end(2), s.slotEnd(2))
    }

    @Test
    fun `空模板也不会崩`() {
        val s = TemplateSet(emptyMap(), DayTypePolicy.ALL)
        assertEquals(Slots.start(3), s.slotStart(3))
        assertEquals(Slots.end(4), s.slotEnd(4))
    }

    // ============================================================
    //  四、各日型不一致时要能查出来
    // ============================================================

    @Test
    fun `内置模板没有节次时间冲突`() {
        assertEquals(emptyList<String>(), TemplateSet.BUILTIN.slotConflicts())
    }

    @Test
    fun `各日型对同一节次写了不同时间要报告出来`() {
        // 课表页只有一列节次时间，两种日型写不同的话它没法同时说对 ——
        // 与其让用户看到一处对一处错，不如导入时就告诉他
        val s = TemplateSet(
            mapOf(
                DayType.A to shiftedA(),
                DayType.B_NORMAL to listOf(
                    Block(0, 8 * 60, "睡觉", kind = Kind.SLEEP),
                    // 同一个节次段，写了个不一样的时间
                    Block(8 * 60, 10 * 60, "第 1-2 节", kind = Kind.CLASS, nodes = 1..2),
                    Block(10 * 60, 1440, "其余", kind = Kind.FREE)
                )
            ),
            DayTypePolicy.ALL
        )
        val conflicts = s.slotConflicts()
        assertTrue("应当报出第 1-2 节的时间不一致，实际：$conflicts", conflicts.isNotEmpty())
        assertTrue(conflicts.any { it.contains("第 1-2 节") })
        assertTrue("要说清两种日型各写的是什么", conflicts.any { it.contains("A") })
    }

    @Test
    fun `只有一种日型设了模板时不会误报冲突`() {
        // 用户只改了 A 型日，其余回落到内置 —— 内置和 A 一致，不该报
        val s = set(DayType.A to shiftedA())
        // A 写 09:00、内置写 08:30，这里**确实**不一致，应当报
        assertTrue(s.slotConflicts().isNotEmpty())

        // 反过来，一份和内置一致的 A 型模板不该报
        val same = TemplateSet(mapOf(DayType.A to Templates.A), DayTypePolicy.ALL)
        assertEquals(emptyList<String>(), same.slotConflicts())
    }
}
