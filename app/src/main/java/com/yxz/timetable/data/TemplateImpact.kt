package com.yxz.timetable.data

import java.time.LocalDate

/**
 * 「刚导入的作息模板，什么时候能看到变化」。
 *
 * ============================================================
 *  为什么需要这个
 * ============================================================
 *
 * 用户反馈：「导入作息模板后显示成功，但主页没有任何反应。」
 *
 * 查下来的结论是：**导入完全正确，App 也没撒谎**，但它只说了「成功」，
 * 没说「这份文件改的是 A 型日，而今天是周二、用的是 B 型日」。
 *
 * 具体到那份文件：它只定义了 `A` 一种日型，而用户导入那天是 9/15 周二，
 * 走的是 `B_TRAIN_A` —— 按「局部覆盖 + 缺省回落」的设计回落到内置模板，
 * 逐格比较下来**主页 21 格一模一样**。变化确实发生了，只是要等到周三才看得见。
 *
 * 所以问题不在功能，在**反馈**：
 *
 * > 「成功」这个反馈回答了「有没有做成功」，
 * > 却没有回答用户真正关心的「我为什么看不出来」。
 *
 * ============================================================
 *  为什么日型要「算」而不是「查表」
 * ============================================================
 *
 * 最容易写错的一步：以为 A 型日 = 周一和周三。
 * 不对 —— `dayType()` 是按「今天第 1-2 节有没有课」**现算**的，
 * 所以第 8 周停课时周三会变成 B 型，国庆调课时又可能反过来。
 *
 * 因此这里不查表，而是**真的把未来两周逐天算一遍**，
 * 找出第一个用到了「被改过的日型」的日期。
 * 这样停课、调课、假期全都自动正确，和引擎本身用的是同一套规则。
 *
 * 这是「不要在第二个地方重新实现同一套规则」的一个具体例子：
 * 只要有第二份实现，它迟早会和第一份对不上。
 */
data class TemplateImpact(
    /** 这次改动涉及哪几种日型 */
    val changed: Set<DayType>,
    /** 今天用的是哪种日型 */
    val todayType: DayType,
    /** 今天是否真的会变（= 今天的日型在被改的集合里） */
    val todayChanged: Boolean,
    /** 下一次会用到「被改过的日型」的日期；两周内都没有则为 null */
    val nextDate: LocalDate?
) {

    /**
     * 一句能直接给用户看的话。
     *
     * 分两种情况，因为用户需要知道的东西完全不同：
     *   今天变了   → 告诉他「已经生效了」，他就不会再去找
     *   今天没变   → **必须解释为什么**，否则他会以为导入失败
     */
    fun summary(today: LocalDate): String = buildString {
        append("已修改：")
        append(changed.joinToString("、") { it.label })
        append("\n\n")

        if (todayChanged) {
            append("已生效 —— 今天是").append(todayType.label)
            append("，主页和通知栏已经按新作息显示。")
            return@buildString
        }

        append("⚠️ 今天看不到变化\n")
        append("今天是").append(ScheduleFormat.weekdayCn(today.dayOfWeek.value))
        append("（").append(todayType.label).append("），")
        append("这份文件没有改这种日型，所以主页和通知栏都不会变。\n\n")

        if (nextDate != null) {
            append("下次生效：")
            append(nextDate.monthValue).append(" 月 ").append(nextDate.dayOfMonth).append(" 日 ")
            append(ScheduleFormat.weekdayCn(nextDate.dayOfWeek.value))
        } else {
            append("未来两周内都不会用到上面这些日型 —— ")
            append("如果你改的是周末，而这两周都没有周末课，就是这个情况。")
        }
    }

    companion object {

        /** 往后找多少天。两周足够覆盖「工作日改的、周末才生效」这类情况 */
        const val HORIZON_DAYS = 14

        fun compute(
            changed: Set<DayType>,
            today: LocalDate,
            termStart: LocalDate,
            courses: List<Course>,
            horizonDays: Int = HORIZON_DAYS
        ): TemplateImpact {
            // 和引擎用同一个函数算日型，不另起炉灶
            fun typeOn(date: LocalDate): DayType =
                TimelineEngine.dayType(date, TimelineEngine.weekOf(date, termStart), courses)

            val todayType = typeOn(today)

            var next: LocalDate? = null
            for (offset in 1..horizonDays) {
                val date = today.plusDays(offset.toLong())
                if (typeOn(date) in changed) {
                    next = date
                    break
                }
            }

            return TemplateImpact(
                changed = changed,
                todayType = todayType,
                todayChanged = todayType in changed,
                nextDate = next
            )
        }
    }
}
