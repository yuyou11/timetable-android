package com.yxz.timetable.data

import java.time.LocalDate

/**
 * 「这一格（星期 X 第 m–n 节）在全学期都上过哪些课」里的一条结果。
 *
 * @param course       对应的课程记录
 * @param fromNode     这门课**自己**在这一天占的起始节次
 * @param toNode       结束节次
 * @param activeInWeek 正在浏览的那一周里有没有这门课：有就是那个周次号，没有是 null
 *
 * ⚠️ fromNode / toNode 取的是**课程自己的**范围，不是被点的那一格。
 * 同一格在不同周次上不但可能是不同的课，节次范围也可能不同：
 * 这一周这格是「第 3-4 节」，别的周可能只占「第 3 节」，或者一口气占到「第 3-6 节」。
 * 卡片上要写「这门课那天到底上到第几节」，所以不能拿被点格子的范围糊弄过去。
 */
data class SlotCourse(
    val course: Course,
    val fromNode: Int,
    val toNode: Int,
    val activeInWeek: Int? = null
)

/**
 * 「点课表一格 → 这一格全学期都有什么课」的查询。
 *
 * ## 命中判据：节点区间有重叠
 *
 * 和课表网格、周次点阵用的是**同一条规则**（`startNode <= 末节 && endNode >= 首节`），
 * 不是「起始节次相等」。三处口径必须一致，否则会出现「网格里明明画着课、
 * 点开却是空的」或者反过来 —— 用户没有任何办法自己判断哪边是对的，
 * 只会觉得这个软件不可信。
 *
 * ## 一门课可能是多条记录
 *
 * 有的学校课不成对，一门课在数据里按小节拆成了两条记录（见 [BuiltinCourses] 的
 * 「军事理论」那几条）。网格显示时会拼回一整块，这里必须做同一件事：
 * **名称、教室、老师、周次四项完全一致**才并成一条，节次范围取并集。
 * （网格那边的判据是 WeekFragment.sameCourseVisual —— 两处要一起改。）
 *
 * ## 停课的课也在结果里
 *
 * `enabled = false` 的课照样返回，由界面打上「已停课」标记。
 * 「这一格本来还有一门课、被你停掉了」是有用信息；静默吞掉反而像数据丢了。
 *
 * ## 排序
 *
 * 1. 正在浏览的那一周会上的一门课排最前（一打开就能对上眼前这一格）
 * 2. 其余按「第一次出现的周次」从早到晚
 * 3. 再按起始节次、课程名 —— 纯粹为了让同样的数据每次点开顺序都一样
 */
object SlotCourses {

    /** 合并判据用的键：这四项一致，才算「同一门课的多条记录」 */
    private data class Key(
        val name: String, val place: String, val teacher: String, val weeks: Set<Int>
    ) {
        constructor(c: Course) : this(c.name, c.place, c.teacher, c.weeks)
    }

    fun at(
        dayOfWeek: Int,
        nodes: IntRange,
        courses: List<Course>,
        viewedWeek: Int? = null
    ): List<SlotCourse> {
        val lo = nodes.first
        val hi = nodes.last
        return courses
            .filter { it.dayOfWeek == dayOfWeek && it.startNode <= hi && it.endNode >= lo }
            .groupBy { Key(it) }
            .map { (_, group) ->
                val c = group.first()
                SlotCourse(
                    course = c,
                    fromNode = group.minOf { it.startNode },
                    toNode = group.maxOf { it.endNode },
                    activeInWeek = viewedWeek?.takeIf { it in c.weeks }
                )
            }
            .sortedWith(
                compareByDescending<SlotCourse> { it.activeInWeek != null }
                    .thenBy { it.course.weeks.minOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.fromNode }
                    .thenBy { it.course.name }
            )
    }
}

/**
 * 这一格「整学期都没课」时，应该显示什么。
 *
 * 取**当天实际生效的日型**模板里覆盖这一节的那个占位格：
 * 日期 + 周次 + 课表一起交给 [TimelineEngine.dayType] 判日型，
 * 拿到日型再取它的占位格 —— 和今日页、通知栏同一条链路，
 * 所以课表页弹出来的「自习 / 轻松跑」和通知栏里那行字必然一致。
 *
 * 返回 null 表示连占位格都没有（周六、周日、无课休息日的模板里本来就没有
 * 课表格子），界面会退回「自习」+ 内置作息时刻（[TemplateSet.slotStart] /
 * [TemplateSet.slotEnd] 自带这个兜底）。
 */
fun selfStudySlot(
    date: LocalDate,
    week: Int,
    node: Int,
    courses: List<Course>,
    set: TemplateSet
): Block? = set.blockAt(TimelineEngine.dayType(date, week, courses, set.config), node)
