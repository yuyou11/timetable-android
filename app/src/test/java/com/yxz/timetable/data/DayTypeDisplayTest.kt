package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 守住界面上的日型标签**不说假话**。
 *
 * ============================================================
 *  这个 bug 是怎么来的
 * ============================================================
 *
 * `DayType.A` 的 label 原本是 `"A 型日 · 有早八"`。在**没有策略**的时候
 * 这没问题 —— A 型日的定义就是「当天第 1-2 节有课」，
 * 「用 A 模板」和「有早八」是同一件事的两种说法。
 *
 * 加了 [DayTypePolicy] 之后，这两件事可以分开了。用户想把工作日
 * 统一成一份作息，写成：
 *
 * ```json
 * "dayTypes": { "enabled": ["A", "SATURDAY", "SUNDAY"], "fallback": "A" }
 * ```
 *
 * 于是周二（当天没课）也用 A 模板 —— 实跑出来界面标着
 * **「A 型日 · 有早八」**，而那天根本没有早课。
 *
 * 修法是把两件事拆开：
 *   · 用哪套模板 → 问策略（[DayTypePolicy.resolve]）
 *   · 有没有早八 → 问日历（[TimelineEngine.naturalDayType]）
 * 拼起来由 [TimelineEngine.dayTypeDisplay] 负责。
 *
 * ============================================================
 *  教训
 * ============================================================
 *
 * **一个字段同时表达两件事，等其中一件事能独立变化时就会出错。**
 * 而且出错的方式很安静 —— 没有异常、没有日志，只是屏幕上一句假话。
 */
class DayTypeDisplayTest {

    private val courses = BuiltinCourses.LIST
    private val termStart = LocalDate.of(2026, 9, 7)

    // 第 3 周：周一有早八、周二/周四/周五没有
    private val mon = LocalDate.of(2026, 9, 21)
    private val tue = LocalDate.of(2026, 9, 22)
    private val wed = LocalDate.of(2026, 9, 23)
    private val fri = LocalDate.of(2026, 9, 25)
    private val sat = LocalDate.of(2026, 9, 26)

    private fun show(date: LocalDate, policy: DayTypePolicy): String =
        TimelineEngine.dayTypeDisplay(
            date, TimelineEngine.weekOf(date, termStart), courses, policy
        )

    // ============================================================
    //  一、默认配置：行为和以前完全一样（不能修出回归）
    // ============================================================

    @Test
    fun `默认配置下有早八的日子照旧显示有早八`() {
        assertEquals("A 型日 · 有早八", show(mon, DayTypePolicy.DEFAULT))
        assertEquals("A 型日 · 有早八", show(wed, DayTypePolicy.DEFAULT))
    }

    @Test
    fun `默认配置下没早八的日子不显示有早八`() {
        // 默认策略里 B_NORMAL 是启用的，周二走 B 型模板
        // （周五全天无课，是休息日，单独测，见下面那条）
        val s = show(tue, DayTypePolicy.DEFAULT)
        assertFalse("显示成「$s」，不该声称有早八", s.contains("有早八"))
        assertTrue(show(sat, DayTypePolicy.DEFAULT).startsWith("周六"))
    }

    @Test
    fun `无课的工作日显示休息日`() {
        // 第 3 周周五全天没课 → 无课 · 休息日。
        // 就算策略的 fallback 是 A，也不能把假期说成「A 型日」——
        // 那天确实按休息模板过，不走任何回落。
        assertEquals("无课 · 休息日", show(fri, DayTypePolicy.DEFAULT))
        assertEquals("无课 · 休息日", show(fri, weekdaysAsA))
    }

    // ============================================================
    //  二、用户那份配置：工作日全落到 A，但只有真有的才说「有早八」
    // ============================================================

    /** 用户实际用的配置：工作日统一用 A 型模板，周末走内置 */
    private val weekdaysAsA = DayTypePolicy(
        enabled = setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY),
        fallback = DayType.A
    )

    @Test
    fun `回落到 A 型模板的日子 不能声称有早八`() {
        // ★ 这条就是这个文件存在的理由。
        // 修复之前，下面这些全都会显示「A 型日 · 有早八」—— 那是假话。
        val s = show(tue, weekdaysAsA)
        assertFalse(
            "$tue 当天没有早课，却显示成「$s」",
            s.contains("有早八")
        )
        assertTrue("还是要说清用的是哪套模板", s.contains("A 型日"))
    }

    @Test
    fun `真的有早八的日子 仍然要说有早八`() {
        // 反向：不能为了不撒谎，把该说的也一起省掉
        assertEquals("A 型日 · 有早八", show(mon, weekdaysAsA))
    }

    @Test
    fun `周末不受影响`() {
        assertTrue(show(sat, weekdaysAsA).contains("周六"))
        assertFalse(show(sat, weekdaysAsA).contains("有早八"))
    }

    // ============================================================
    //  三、别的策略组合也不能撒谎
    // ============================================================

    @Test
    fun `无论什么策略 只要日历没算出 A 就不出现有早八`() {
        // 穷举：六种日型各当一次 fallback，配上各种 enabled
        val policies = buildList {
            add(DayTypePolicy.ALL)
            add(DayTypePolicy.DEFAULT)
            add(weekdaysAsA)
            for (fb in Templates.ALL_TYPES) {
                add(DayTypePolicy(setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY), fb))
                add(DayTypePolicy(setOf(fb, DayType.SATURDAY), fb))
            }
        }

        for (p in policies) {
            for (d in listOf(mon, tue, wed, fri, sat)) {
                val natural = TimelineEngine.naturalDayType(
                    d, TimelineEngine.weekOf(d, termStart), courses
                )
                val s = show(d, p)
                if (natural != DayType.A) {
                    assertFalse(
                        "策略(${p.enabled.map { it.name }}, fb=${p.fallback.name}) 下 " +
                                "$d 日历算出的是 $natural（没有早八），却显示「$s」",
                        s.contains("有早八")
                    )
                } else {
                    assertTrue(
                        "$d 确实有早八，显示「$s」时应当说明",
                        s.contains("有早八")
                    )
                }
            }
        }
    }

    // ============================================================
    //  四、label 本身不再夹带「有早八」
    // ============================================================

    @Test
    fun `DayType 的 label 只描述模板 不描述当天情况`() {
        // 结构性约束：label 一旦又写上「有早八」，
        // 上面那些断言就会被绕过（因为拼接时会重复）
        // 遍历 DayType.entries：REST 不在 ALL_TYPES 里，但它也有 label
        for (t in DayType.entries) {
            assertFalse(
                "DayType.${t.name} 的 label「${t.label}」夹带了当天信息 —— " +
                        "label 只该说明这套模板叫什么",
                t.label.contains("有早八")
            )
        }
    }
}
