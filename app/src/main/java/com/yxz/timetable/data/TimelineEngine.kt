package com.yxz.timetable.data

import java.time.LocalDate

/**
 * 时间轴引擎 —— 整个 App 的心脏。
 *
 * 输入：一个日期 + 当前第几周 + 全部课程
 * 输出：从 00:00 到 24:00 一条**首尾相接、互不重叠**的日程表
 *
 * 做法是「两层叠加」：
 *
 *   第一层  作息模板（A 型 / B 型 / 周六 / 周日）—— 铺满一整天
 *   第二层  当天的课            —— 盖在第一层上面
 *
 * 为什么是这个顺序？因为模板回答的是「正常情况下这个点该干嘛」，
 * 而课表回答的是「学校规定这个点必须在哪」。后者优先级更高，
 * 而且课表是稀疏的（一天最多 5 段），拿它去覆盖密集的模板最省事。
 *
 * 反过来先铺课表、再填模板，就会遇到「两个空闲段之间怎么填」的问题，
 * 边界情况反而更多。
 */
object TimelineEngine {

    /**
     * 教学周次计算。
     *
     * 特意做成**纯函数**（不碰 SharedPreferences、不碰 Context）：
     * 这样它能被单元测试直接调用。Store.weekOf() 只是加了「手动指定周次」的
     * 覆盖逻辑，算数部分委托给这里。
     *
     * 为什么较真这个？因为周次算错一天，整个课表就全错，而且是那种
     * 「看起来正常、实际错位」的错 —— 只有测试能抓住它。
     */
    fun weekOf(date: LocalDate, termStart: LocalDate): Int =
        Math.floorDiv(date.toEpochDay() - termStart.toEpochDay(), 7L).toInt() + 1

    /**
     * 把任意日期吸附到它所在那一周的周一。
     *
     * 为什么需要它：整个时间轴是按「第 N 周 = 起始日 + (N-1)×7 天」推出来的，
     * 起始日只要不是周一，每一周的边界就全错位。
     *
     * 两个地方会用到，但处理方式不同 —— 这个对比很值得记：
     *
     *   JSON 导入   → 报错拒绝。用户不在场，没法确认，猜错了就是整张表错位。
     *   界面选日期   → 自动吸附。用户就在屏幕前，顺手改对比甩个错误更好。
     *
     * **同一个问题在批处理场景和交互场景下，应该给不同的答案。**
     */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    // ============================================================
    //  第一步：今天属于哪种日型
    // ============================================================

    /**
     * 「有早八」的日历定义：今天第 1-2 节有课。
     *
     * 这是**日历事实**，不受任何配置影响 —— 界面上的「· 有早八」
     * 后缀永远按它说话（见 [dayTypeDisplay]）。
     */
    fun hasEarlyClass(date: LocalDate, week: Int, courses: List<Course>): Boolean =
        coursesOn(date, week, courses).any { it.startNode <= 2 }

    /**
     * 今天实际用哪套模板 —— 引擎判定的唯一入口。按顺序三条：
     *
     *   ① **工作日全天无课 → [DayType.REST]**（休息日）。程序不认识国庆中秋，
     *      但「课表里这天一门课都没有」是数据里写得明明白白的客观事实 ——
     *      假期、停课、课表没排到的周次都落到这里。
     *      它**不受星期映射影响**：休息日不是一种「偏好」，
     *      再怎么映射也不能把没课的日子变成上学日。
     *
     *   ② **工作日有早八且配置指定了早八日型 → [DayTypeConfig.earlyType]**。
     *      「今天要不要早起」是这个 App 存在的理由，这一层优先于星期映射。
     *
     *   ③ 其余 → [DayTypeConfig.weekdayMap] 按星期几取。
     *
     * ## ⚠️ [config] 故意不给默认值
     *
     * 忘了传配置的话，程序会静悄悄按默认映射跑 —— 界面按 A 型显示、
     * 通知却按 B 型排，这类不一致查起来非常费劲。不给默认值，
     * 编译器会把**每一个**漏改的调用点都列出来，一个都跑不掉。
     * **让编译器替你找调用点，比靠人记住可靠得多。**
     *
     * ## 周末为什么不做「无课 → 休息日」
     *
     * 周末不管有没有课都按星期映射的模板过 —— 周末的作息本来就不由
     * 课表驱动。只有**工作日**的无课才判休息日（假期、停课、调课都发生在工作日）。
     */
    fun resolveDayType(
        date: LocalDate,
        week: Int,
        courses: List<Course>,
        config: DayTypeConfig
    ): DayType {
        val dow = date.dayOfWeek.value           // 1=周一 … 7=周日
        if (dow >= 6) return config.weekdayMap[dow - 1]
        if (coursesOn(date, week, courses).isEmpty()) return DayType.REST
        if (config.earlyType != null && hasEarlyClass(date, week, courses)) {
            return config.earlyType
        }
        return config.weekdayMap[dow - 1]
    }

    /**
     * **实际要用的日型** —— [resolveDayType] 的对外名字。
     *
     * [config] 不给默认值的理由见 [resolveDayType]：漏传配置必须
     * 当场被编译器揪出来，不能静默按默认跑。
     */
    fun dayType(
        date: LocalDate,
        week: Int,
        courses: List<Course>,
        config: DayTypeConfig
    ): DayType = resolveDayType(date, week, courses, config)

    /**
     * 界面上显示的日型描述 —— 会区分「用哪套模板」和「今天有没有早八」。
     *
     * [names] 是 store 里的自定义日型名（v4，稀疏表）；不传或传空就用默认名。
     * 引擎本身不碰 store，所以名字只能由调用方带进来。
     *
     * ## 为什么需要单独一个函数
     *
     * 日型名只说明「这套模板叫什么」。但用户看到今天标着「A 型日」时，
     * 自然会想知道今天要不要早起 —— 而这**不能从标签推出来**：
     *
     *   默认配置下   A 型日 ⟺ 当天第 1-2 节有课（有早八）
     *   自定义配置下 用户可以把工作日全部映射到 A 型模板，
     *               于是「A 型日」不再意味着「有早八」
     *
     * 实测过一次：把工作日全落到 A 之后，周二（当天没课）的界面
     * 依然显示「A 型日 · 有早八」—— **那是一句假话**。
     *
     * 所以「有没有早八」去问**日历**（[hasEarlyClass]），
     * 「用哪套模板」去问**配置**（[dayType]），两者拼起来才是完整的描述。
     */
    fun dayTypeDisplay(
        date: LocalDate,
        week: Int,
        courses: List<Course>,
        config: DayTypeConfig,
        names: Map<DayType, String> = emptyMap()
    ): String {
        val used = dayType(date, week, courses, config)
        val label = dayTypeName(used, names)
        val early = date.dayOfWeek.value <= 5 && hasEarlyClass(date, week, courses)
        return if (early) "$label · 有早八" else label
    }

    /**
     * 取某一天的模板。
     *
     * [templates] 默认是内置模板，所以老的调用点（比如单元测试）不用改 ——
     * 这是给参数设默认值的好处：新增能力时，已有代码保持原样就能继续工作。
     */
    fun template(type: DayType, templates: TemplateSet = TemplateSet.BUILTIN): List<Block> =
        templates.of(type)

    /**
     * 从模板推出「今天几点起床」。
     *
     * 做法是找**午前最后一个「睡觉」块的结束时刻** —— 那一格结束就意味着醒了。
     *
     * 为什么不直接写死 06:55 / 07:25？因为模板现在可以被用户导入替换了，
     * 起床时间不再是个常量。而且这样一来，模板一改，通知栏、次日预告、
     * 一键闹钟三个地方会**同时**跟着变，不需要挨个改。
     *
     * 返回 null 表示这套模板里没有上午的睡眠段（比如整夜工作的排法），
     * 调用方要能接受这种情况 —— 界面上那一行直接不显示，而不是显示一个错的时间。
     */
    fun wakeMinute(template: List<Block>): Int? =
        template.filter { it.kind == Kind.SLEEP && it.start < 12 * 60 }
            .maxByOrNull { it.end }
            ?.end

    /** 今天实际要上的课（已经按周次过滤） */
    fun coursesOn(date: LocalDate, week: Int, courses: List<Course>): List<Course> {
        val dow = date.dayOfWeek.value
        return courses.filter { it.enabled && it.dayOfWeek == dow && week in it.weeks }
    }

    // ============================================================
    //  第二步：合成时间轴
    // ============================================================

    fun moments(
        date: LocalDate,
        week: Int,
        courses: List<Course>,
        templates: TemplateSet = TemplateSet.BUILTIN
    ): List<Moment> {
        // 配置跟着模板走 —— 两者都是「用户的作息配置」的一部分，
        // 放在一起就不会出现「按 A 型的配置、却取了 B 型的模板」这种错配
        val base = template(dayType(date, week, courses, templates.config), templates)
        val todayCourses = coursesOn(date, week, courses)

        // 课程占哪一段，**以模板的占位格为准**（templates.slotStart/End）。
        //
        // 这里原来写的是 `Slots.start(c.startNode)` —— 节次时间写死在代码里，
        // 用户改模板里的上课时间对课程完全不生效。详见 TemplateSet 里那段注释。
        val courseBlocks = todayCourses.map { c ->
            Block(
                start = templates.slotStart(c.startNode),
                end = templates.slotEnd(c.endNode),   // 连堂自动合并成一整块
                title = c.name,
                note = "",
                kind = Kind.CLASS,
                nodes = c.startNode..c.endNode,
                place = c.place,
                isCourse = true,
                courseId = c.id
            )
        }

        val out = mutableListOf<Block>()
        val claimed = mutableSetOf<Int>()

        for (blk in base) {
            val nodes = blk.nodes
            if (nodes != null) {
                // 占位格：有课就整格替换，没课就标注「无课」。
                // 用同一个来源（templates.slotStart）算，所以两边必然对得上。
                val idx = courseBlocks.indexOfFirst { it.start == templates.slotStart(nodes.first) }
                if (idx >= 0) {
                    out += courseBlocks[idx]
                    claimed += idx
                } else {
                    // 单节的占位格（nodes = [2, 2]）显示成「第 2 节」，
                    // 别写成「第 2-2 节」—— 那是没人写得出的课表写法
                    out += blk.copy(
                        title = if (nodes.first == nodes.last) "第 ${nodes.first} 节 · 无课"
                        else "第 ${nodes.first}-${nodes.last} 节 · 无课"
                    )
                }
            } else {
                // 固定日程：和课撞上就**整块让位** —— 阈值是原块的 100%。
                //
                // 这里曾经是「被课压到多少就保留多少」，结果出现过
                // 「训练 100 分钟」被两节课夹成 15:55-16:00 一条 5 分钟缝
                // 还照显的自相矛盾条目（标题说 100 分钟、时长写着 5 分）。
                // 残块再短也留不得：标题、备注都是按完整时长写的，
                // 砍剩一截之后它们全变成假话。空档交给 assemble 的
                // 「空档 · 机动」兜底，那才是对这一段的真实描述。
                if (courseBlocks.none { overlap(it, blk) }) out += blk
            }
        }

        // 没有任何占位格认领的课（周末加课、临时调课等）直接插进来；
        // 和它撞上的非课程块按同一条 100% 规则整块让位（无课占位格同理）
        courseBlocks.forEachIndexed { i, c ->
            if (i !in claimed) {
                out.retainAll { it.isCourse || !overlap(it, c) }
                out += c
            }
        }

        return assemble(out)
    }

    /** 两块时间是否有重叠。首尾相接不算 —— 10:05 下课、10:05 开始的块互不侵犯 */
    private fun overlap(a: Block, b: Block): Boolean = a.start < b.end && b.start < a.end

    /**
     * 把一堆可能重叠、可能有空洞的块，整理成一条连续的线。
     *
     * 这个函数是「结果一定合法」的保证：不管上面叠加逻辑多乱，
     * 经过它之后出来的东西一定满足三条 —— 有序、不重叠、首尾相接。
     * 这种「最后一道收口」的写法很值得学：把正确性压力集中到一处，
     * 上游就可以写得随意一些。
     */
    private fun assemble(blocks: List<Block>): List<Moment> {
        val sorted = blocks.filter { it.end > it.start }.sortedBy { it.start }

        val raw = mutableListOf<Moment>()
        var cursor = 0

        for (b in sorted) {
            val start = maxOf(b.start, cursor)
            if (start >= b.end) continue                 // 完全被前面的块吃掉了
            if (start > cursor) raw += gap(cursor, start)
            raw += Moment(start, b.end, b.title, b.note, b.place, b.kind, b.isCourse, b.courseId)
            cursor = b.end
        }
        if (cursor < 1440) raw += gap(cursor, 1440)

        return mergeAdjacent(raw)
    }

    private fun gap(from: Int, to: Int) =
        Moment(from, to, "空档 · 机动", "临时会议、社团活动、突发任务", "", Kind.FREE)

    /** 相邻两格如果标题、备注、性质完全一样，就合成一格，避免出现两条「晚自习」 */
    private fun mergeAdjacent(list: List<Moment>): List<Moment> {
        if (list.isEmpty()) return list
        val merged = mutableListOf<Moment>()
        for (m in list) {
            val last = merged.lastOrNull()
            if (last != null && last.end == m.start &&
                last.title == m.title && last.note == m.note &&
                last.place == m.place && last.kind == m.kind &&
                last.courseId == m.courseId
            ) {
                merged[merged.size - 1] = last.copy(end = m.end)
            } else {
                merged += m
            }
        }
        return merged
    }

    // ============================================================
    //  第三步：查询
    // ============================================================

    /** 此刻在哪一格 */
    fun currentAt(moments: List<Moment>, minute: Int): Moment? =
        moments.lastOrNull { it.start <= minute } ?: moments.firstOrNull()

    /** 下一格是什么 */
    fun nextAfter(moments: List<Moment>, minute: Int): Moment? =
        moments.firstOrNull { it.start > minute }

    /**
     * 课间：夹在两节课中间的短空档。
     *
     * 是课间就返回**下一节课**（今天页和通知栏都靠它显示教室），
     * 不是课间返回 null。
     *
     * 判据三条，缺一不可：
     *  1. 此刻这一格是空档（[Kind.FREE]）—— 课间在时间线上就是一段 gap；
     *  2. 前后相邻的都是课 —— 只有「上完一节、还要上下一节」那段才叫课间，
     *     早八前那段空档、午休、放学后都不算；
     *  3. 空档不超过 [maxGapMinutes] 分钟 —— 中午两小时的空档也是「前后都是课」，
     *     但它不该被当成课间。
     *
     * 注意这里用的是**相邻**关系而不是「下一节课」，所以课间长度怎么变、
     * 中间有没有插进来一个固定日程，判断都不会错。
     */
    fun breakNextCourse(
        moments: List<Moment>,
        minute: Int,
        maxGapMinutes: Int = 40
    ): Moment? {
        val idx = moments.indexOfFirst { minute >= it.start && minute < it.end }
        if (idx < 0) return null
        val cur = moments[idx]
        if (cur.kind != Kind.FREE || cur.duration > maxGapMinutes) return null
        if (moments.getOrNull(idx - 1)?.isCourse != true) return null
        return moments.getOrNull(idx + 1)?.takeIf { it.isCourse }
    }
}
