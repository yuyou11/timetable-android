package com.yxz.timetable.data

/**
 * 时段性质。目前只用来决定界面配色（上课=蓝、睡觉=灰、训练=橙…）。
 * 用 enum 而不是字符串，好处是写错名字编译器当场报错，而不是等到运行时才发现。
 */
enum class Kind { SLEEP, MEAL, CLASS, STUDY, TRAIN, FREE, CHORE, TRANSIT }

/**
 * 作息模板里的一格。
 *
 * start / end 都不是「时间对象」，而是**距当天 00:00 的分钟数**：
 * 08:30 -> 8*60+30 = 510，23:10 -> 1390。
 *
 * 为什么不用 LocalTime？因为后面要做三件事：比大小、算时长、跨格裁剪。
 * 这三件事在「整数分钟」上都是一行代码，用时间对象反而要来回转换。
 * 这是很常见的一个取舍：**能用整数表达的量，就别用复杂对象**。
 *
 * @param nodes 不为 null 时，说明这一格是课表格子（值是节次范围，如 1..2），
 *              可以被当天的课程覆盖掉；为 null 则是固定日程，任何课都盖不住它。
 */
data class Block(
    val start: Int,
    val end: Int,
    val title: String,
    val note: String = "",
    val kind: Kind = Kind.CHORE,
    val nodes: IntRange? = null,
    val place: String = "",
    val isCourse: Boolean = false
) {
    val duration: Int get() = end - start
}

/** 一门课。weeks 是「这门课在第几教学周会上」，用它来过滤单双周、起止周。 */
data class Course(
    val id: String,
    val name: String,
    val dayOfWeek: Int,          // 1 = 周一 … 7 = 周日
    val startNode: Int,          // 起始节次
    val endNode: Int,            // 结束节次
    val weeks: Set<Int>,         // 上课的教学周次集合
    val place: String = "",
    val enabled: Boolean = true  // 放假 / 停课时可以单独关掉某一门
)

/** 时间轴上的最终结果，UI 和通知栏都只认这个 */
data class Moment(
    val start: Int,
    val end: Int,
    val title: String,
    val note: String,
    val place: String,
    val kind: Kind,
    val isCourse: Boolean = false
) {
    val duration: Int get() = end - start
    fun contains(minute: Int): Boolean = minute >= start && minute < end
}
