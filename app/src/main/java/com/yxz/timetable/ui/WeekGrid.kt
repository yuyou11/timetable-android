package com.yxz.timetable.ui

import com.yxz.timetable.data.Course
import com.yxz.timetable.data.Periods

/**
 * 「一格课怎么落在网格上」的纯逻辑 —— 课表页两种外观共用一份。
 *
 * ## 为什么单独抽出来
 *
 * 课表页现在有两套画法：
 *
 *   · 默认外观（[WeekFragment]）：一行行 LinearLayout 拼出来的网格
 *   · P3 外观（[com.yxz.timetable.ui.persona.PersonaWeekView]）：整页自绘
 *
 * 两种画法差别很大，但**哪些格子合、哪些格子空**必须完全一致 ——
 * 同一份课表在一套外观下显示在第 3-4 节、在另一套下显示成第 3 节，
 * 那就不是「换个皮」，而是两个不同的课表。
 * 所以判定和合并这段逻辑只留一份，两边都调它。
 */
object WeekGrid {

    /**
     * 网格里的一块：从 [fromNode] 到 [toNode] 占同一个位置。
     *
     * [course] 为 null 表示这是一块**空格子**（只有一行高）——
     * 空格子也要出现在结果里，因为两套外观都要让它可点
     * （「这一格别的周有没有课」是真问题）。
     */
    data class Span(
        val day: Int,
        val fromNode: Int,
        val toNode: Int,
        /** 显示用的那条课程记录；同一门课拆成多条时是第一条 */
        val course: Course?,
        /** 合并进来的原始记录条数（一门课被拆成「1-2」「3-4」两条时是 2） */
        val merged: Int = 1
    )

    /**
     * 一天的课列里有哪些块。
     *
     * ## 命中判据是「区间重叠」，不是「起点相等」
     *
     * 行是按学校声明的节数结构切的（见 [Periods]），别的学校单节的课、
     * 跨在两节之间的课，一行都对不上「起点」，要求相等的话整门课会在网格里消失。
     *
     * ## 相邻同课自动合并
     *
     * 有的学校课不成对，一门课在数据里是按小节拆开的两条记录 ——
     * 显示时拼回一整块，才和课表上看到的一格对得上。
     *
     * @param nodes 网格的行（节次号，升序）。默认 1..10，随 periods 变
     */
    fun spans(day: Int, nodes: List<Int>, week: Int, courses: List<Course>): List<Span> {
        val hit = nodes.map { node ->
            courses.firstOrNull { c ->
                c.enabled && c.dayOfWeek == day && week in c.weeks &&
                        c.startNode <= node && c.endNode >= node
            }
        }

        val out = ArrayList<Span>(nodes.size)
        var i = 0
        while (i < nodes.size) {
            val first = hit[i]
            var span = 1
            if (first != null) {
                while (i + span < nodes.size) {
                    val next = hit[i + span]
                    if (next == null || !sameVisual(next, first)) break
                    span++
                }
            }
            out += Span(
                day = day,
                fromNode = nodes[i],
                toNode = nodes[i + span - 1],
                course = first,
                merged = span
            )
            i += span
        }
        return out
    }

    /**
     * 显示上算「同一门课」：同一条记录，或名称 / 教室 / 周次完全一致的相邻记录。
     *
     * 不用 courseId 判断：一条课被拆成多条记录时，它们在数据里本来就是**不同的 id**。
     */
    fun sameVisual(a: Course, b: Course): Boolean =
        a === b || (a.name == b.name && a.place == b.place && a.weeks == b.weeks)
}

/**
 * 近五周点阵的数据（课表页默认外观的那张点阵卡片、P3 外观底部的密度条都用它）。
 *
 * 抽出来的理由和 [WeekGrid] 一样：一周「有没有课」是**事实**，
 * 两种外观画成什么样子可以不同，判断不能不同 —— 否则点阵亮着、
 * 下面的格子却是空的。
 */
object WeekDensity {

    /**
     * 第 [week] 周的占用表：`[星期一..星期日 0..6][第 1..rowCount 节 0..rowCount-1]`。
     */
    fun occupancy(week: Int, courses: List<Course>, rowCount: Int): Array<BooleanArray> {
        val grid = Array(7) { BooleanArray(rowCount) }
        for (c in courses) {
            if (!c.enabled || week !in c.weeks) continue
            for (node in c.startNode..c.endNode) {
                // 学校声明的节数比这门课少时（导入的旧数据），多出来的节次没有行可落
                val col = node - 1
                if (col in 0 until rowCount) grid[c.dayOfWeek - 1][col] = true
            }
        }
        return grid
    }

    /**
     * 近五周（当前浏览周 ±2）的点阵。
     *
     * 跟着**浏览的周**走（而不是钉死在真实本周）：翻周时预览也往前挪，
     * 「下周会怎样」才是这排点阵真正回答的问题。
     * 学期头尾自动收窄（第 1 周只有 1-3 周），标签就是周次号。
     */
    fun weeks(
        week: Int, totalWeeks: Int, courses: List<Course>, rowCount: Int, paired: Boolean
    ): List<WeekDotsView.Week> =
        ((week - 2)..(week + 2))
            .filter { it in 1..totalWeeks }
            .map { w ->
                var grid = occupancy(w, courses, rowCount)
                if (paired) grid = Periods.mergePairs(grid)
                WeekDotsView.Week(w, grid, w == week)
            }
}
