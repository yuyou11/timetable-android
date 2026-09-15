package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「导入作息模板后，什么时候能看到变化」的测试。
 *
 * ## 背景
 *
 * 用户导入一份**只定义了 A 型日**的模板，App 提示成功，但主页毫无反应 ——
 * 因为他导入那天是周二（B 型日），而这份文件根本没改 B 型。
 *
 * 功能是对的，缺的是反馈。这个文件守的就是那条反馈：
 * **必须能算出「今天会不会变」「哪天才会变」。**
 */
class TemplateImpactTest {

    // 2026-09-07 是教学第 1 周的周一，和别处的测试保持一致
    private val termStart = LocalDate.of(2026, 9, 7)
    private val courses = BuiltinCourses.LIST

    private fun d(day: Int) = LocalDate.of(2026, 9, day)

    private fun impact(changed: Set<DayType>, today: LocalDate) =
        TemplateImpact.compute(changed, today, termStart, courses)

    // ============================================================
    //  一、用户报障的那一次，必须说清楚「今天不会变」
    // ============================================================

    @Test
    fun `只改 A 型日时 周二会被告知今天看不到变化`() {
        // 复现用户报障的原场景：文件只定义了 A，导入那天是 2026-09-15 周二
        val r = impact(setOf(DayType.A), d(15))

        assertEquals("9/15 是周二", java.time.DayOfWeek.TUESDAY, d(15).dayOfWeek)
        assertFalse("今天不该被判定为「已生效」", r.todayChanged)
        assertEquals(DayType.B_TRAIN_A, r.todayType)

        val msg = r.summary(d(15))
        assertTrue("必须说明今天没有变化，实际：$msg", msg.contains("今天看不到变化"))
        assertTrue("要说清今天是周几，实际：$msg", msg.contains("周二"))
        assertTrue("要给出下次生效的日期，实际：$msg", msg.contains("下次生效"))
    }

    @Test
    fun `下次生效要落在真正会用 A 型日的那一天`() {
        // 内置课表下，周一和周三第 1-2 节有课 → A 型日
        val r = impact(setOf(DayType.A), d(15))
        assertEquals("从周二往后，第一个 A 型日是周三 9/16", d(16), r.nextDate)
        assertEquals(java.time.DayOfWeek.WEDNESDAY, r.nextDate!!.dayOfWeek)
    }

    @Test
    fun `改的就是今天的日型时要明确说已生效`() {
        // 周三 9/16 是 A 型日
        val r = impact(setOf(DayType.A), d(16))
        assertTrue(r.todayChanged)
        val msg = r.summary(d(16))
        assertTrue("要告诉用户已经生效，实际：$msg", msg.contains("已生效"))
        assertFalse("已生效就不该再提「今天看不到变化」", msg.contains("今天看不到变化"))
    }

    // ============================================================
    //  二、日型必须「现算」，不能按星期几查表
    // ============================================================

    @Test
    fun `第 8 周停课时 周三不再是 A 型日`() {
        // 这是本文件最重要的一条。
        //
        // 高数周三 1-2 节是「2-7、9-17 周」，第 8 周正好不上 →
        // dayType() 会算出 B_NORMAL。如果这里写成「周三 = A 型」的查表，
        // 就会告诉用户「周三生效」，而那天其实什么都不会变。
        val week8Wed = LocalDate.of(2026, 10, 28)

        val r = impact(setOf(DayType.A), week8Wed)
        assertFalse(
            "第 8 周周三停课，今天不该算作 A 型日",
            r.todayType == DayType.A
        )
        // 而且给出的「下次生效」必须真的是 A 型日 ——
        // 不能因为「它是周三」就认定它会生效
        assertTrue(
            "给出的生效日期必须真的是 A 型日",
            r.nextDate == null ||
                    TimelineEngine.dayType(
                        r.nextDate!!,
                        TimelineEngine.weekOf(r.nextDate!!, termStart),
                        courses
                    ) == DayType.A
        )
    }

    @Test
    fun `给出的生效日期一定是真的会用被改日型的那天`() {
        // 对每一种日型、每一个起始日都验一遍：
        // 只要报告了 nextDate，那天的日型就一定在 changed 里。
        // 这条是防止「算出来的日期和引擎对不上」的通用保险。
        for (start in 1..28) {
            val today = LocalDate.of(2026, 9, 1).plusDays(start.toLong())
            for (type in Templates.ALL_TYPES) {
                val r = impact(setOf(type), today)
                val next = r.nextDate ?: continue
                val actual = TimelineEngine.dayType(
                    next, TimelineEngine.weekOf(next, termStart), courses
                )
                assertEquals(
                    "起始日 $today 找 $type 时，给出的 $next 实际是 $actual",
                    type, actual
                )
            }
        }
    }

    // ============================================================
    //  三、周末
    // ============================================================

    @Test
    fun `工作日改的周末日型会指向最近的周末`() {
        // 周一 9/14 导入一份只改 SATURDAY 的模板 → 下次生效应该是 9/19 周六
        val r = impact(setOf(DayType.SATURDAY), d(14))
        assertFalse(r.todayChanged)
        assertEquals(d(19), r.nextDate)
    }

    @Test
    fun `改周六而今天就是周六时立刻生效`() {
        val r = impact(setOf(DayType.SATURDAY), d(19))
        assertTrue(r.todayChanged)
        assertEquals(DayType.SATURDAY, r.todayType)
    }

    // ============================================================
    //  四、多种日型一起改
    // ============================================================

    @Test
    fun `改了多种日型时列出全部`() {
        val r = impact(setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY), d(15))
        val msg = r.summary(d(15))
        assertTrue("要列出 A 型", msg.contains("A 型日"))
        assertTrue("要列出周六", msg.contains("周六"))
        assertTrue("要列出周日", msg.contains("周日"))
    }

    @Test
    fun `改了工作日常用日型时今天通常就会生效`() {
        // 用户报障那天（周二）如果改的是 B_TRAIN_A，就该立刻看到变化
        val r = impact(setOf(DayType.B_TRAIN_A), d(15))
        assertTrue("周二用的就是 B_TRAIN_A，应当立即生效", r.todayChanged)
    }

    // ============================================================
    //  五、边界
    // ============================================================

    @Test
    fun `两周内都用不到的日型要给出解释而不是空白`() {
        // 构造一个极端场景：把搜索窗口压到 1 天，改的还是明天用不到的日型
        val r = TemplateImpact.compute(
            changed = setOf(DayType.SUNDAY),
            today = d(14),          // 周一
            termStart = termStart,
            courses = courses,
            horizonDays = 1         // 只看明天（周二，B_TRAIN_A）
        )
        assertNull("窗口内找不到，nextDate 应为 null", r.nextDate)

        val msg = r.summary(d(14))
        assertTrue("不能留空，要给一句解释，实际：$msg", msg.contains("未来两周内都不会用到"))
    }

    @Test
    fun `changed 为空时不会崩`() {
        val r = impact(emptySet(), d(15))
        assertFalse(r.todayChanged)
        assertNull(r.nextDate)
        // 摘要里「已修改：」后面是空的，但不该抛异常
        assertTrue(r.summary(d(15)).isNotEmpty())
    }
}
