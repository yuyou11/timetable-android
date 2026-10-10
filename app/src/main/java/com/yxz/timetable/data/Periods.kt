package com.yxz.timetable.data

/**
 * 节数信息（periods）—— 课表网格的行结构。
 *
 * 描述**学校的节次结构**：一串数字（1–14 位，每位 1–9），从左到右是当天
 * 从早到晚各时段的节数（通常是上午、下午、晚上）。**一天的小节数 =
 * 各位之和**，不再写死 10：
 *
 *   "442"（默认）  一天共 10 节，与内置小节一一对应
 *   "441"          一天共 9 节，没有第 10 节
 *   "445"          一天共 13 节，第 11-13 节按晚课节奏外推时间（见 [Slots]）
 *   "22222"        五段、每段 2 节 —— 一天共 10 节
 *
 * ## 行就是节，节就是行
 *
 * 每一行 = 一个 45 分钟小节，学校的第 N 节就是第 N 小节，编号一一对应 ——
 * 「441 下晚上那节是第 9 节还是第 9-10 节」这类两套编号的换算已经不需要了。
 * 一门课横跨多行时，相邻的同课格子由课表页合并显示。
 *
 * ## 不管结构怎么写，1 到 node_count 每个小节都得有行
 *
 * 漏掉任何一个小节，那类课在网格里就**整门消失**
 * （电脑版 test_week_grid 盯的就是这条）。
 * 各位之和的上限是 14 —— 第 11 节起按晚课节奏外推到 23:55 为止，
 * 一天排不下更多的节（见 [Slots]）。
 */
object Periods {

    /** 网格里的一行 = 一个 45 分钟小节。学校的第 N 节就是第 N 小节。 */
    data class Row(
        /** 这一行对应的节次（= 小节号 = 行标签里的数字） */
        val node: Int
    ) {
        /** 行标签，形如「第 9 节」 */
        val label: String get() = "第 $node 节"
    }

    /**
     * 把节数信息展开成网格的行：从小节 1 排到小节 node_count，每节一行。
     *
     * 非法输入一律按默认 "442" 处理 —— 调用方（Store）虽然已经保证过合法性，
     * 这里再兜一道底：它只是显示结构，坏值不能让课表页崩掉。
     */
    fun rows(periods: String): List<Row> {
        val digits = ScheduleFormat.normalizePeriods(periods) ?: ScheduleFormat.DEFAULT_PERIODS
        return (1..ScheduleFormat.nodeCount(digits)).map { Row(it) }
    }

    /**
     * 是否是「两课并一课」的标准课表：**每门在用的课都恰好是两节连堂、
     * 且起点是奇数**（1-2、3-4、5-6…）—— 大学最常见的排法，两小节并成一大节。
     *
     * 任何一门课破坏这个形状（单节、三节连排、2-3 这种偶数起点的连堂），
     * 就不算「标准」—— 显示上退回「一节一行」，宁可密一点也不并错。
     * 停掉的课（enabled = false）不参与判定：它不显示，不该改变显示形状。
     *
     * 空课表不算：没有课就谈不上「并一课」，按一节一行显示。
     */
    fun isPairedSchedule(courses: List<Course>): Boolean {
        val active = courses.filter { it.enabled }
        return active.isNotEmpty() && active.all {
            it.startNode % 2 == 1 && it.endNode == it.startNode + 1
        }
    }

    /**
     * 把占用表每两小节并成一行（1-2、3-4、5-6…）——
     * 「两课并一课」点阵的行结构。两格里任一有课，合并格就算有课。
     *
     * 尾巴上凑不成对的那一小节（节数为奇数时的最后一节）直接丢掉：
     * 能通过 [isPairedSchedule] 的课表永远不会用到它 ——
     * 用到它的课要么是单节、要么是偶数起点，都会被判定拦下。
     */
    fun mergePairs(occupancy: Array<BooleanArray>): Array<BooleanArray> =
        Array(occupancy.size) { day ->
            val periods = occupancy[day]
            BooleanArray(periods.size / 2) { pair ->
                periods[pair * 2] || periods[pair * 2 + 1]
            }
        }
}
