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
     * **原始日历规则** —— 不算策略，只看日历。判断依据按顺序两条：
     *
     * 1. **工作日全天没课 → [DayType.REST]**（无课休息日）。程序不认识国庆中秋，
     *    但「课表里这天一门课都没有」是数据里写得明明白白的客观事实 ——
     *    假期、停课、课表没排到的周次都会落到这里，按休息日过（无课、无晚自习）。
     * 2. 有课的工作日，再看**今天第 1-2 节有没有课**区分 A / B 型。
     *
     * 这是整个设计里我最想让你注意的一处：
     * 文档里写死了「周一、周三 = A 型」，但如果照抄成 `if (dow == 1 || dow == 3)`，
     * 那么第 8 周停课、国庆调课、中秋放假时，App 还会按 A 型让你 06:55 起床 —— 错的。
     *
     * 改成「现算」之后，这几种情况**自动就对**了，一行特判都不用写。
     * 判断规则和文档里那句「真正需要你记住的只有一件事：今天有没有早八」完全一致。
     *
     * ============================================================
     *  它和 [dayType] 的分工
     * ============================================================
     *
     * 这个函数回答「**日历上是哪种日**」，[dayType] 回答「**这次实际要用哪套模板**」。
     * 两者的差别来自 [DayTypePolicy]：这份配置可能根本没启用训练日，
     * 那么算出来的 `B_TRAIN_A` 就要被映射成别的。
     *
     * 拆成两层的好处是策略可配置 —— 如果这里直接把训练日那两行删掉，
     * 就再也没有「想让训练日生效」的余地了。
     *
     * **要测「日历规则」本身（比如「周二算不算训练日」）就调这个。**
     */
    fun naturalDayType(date: LocalDate, week: Int, courses: List<Course>): DayType {
        val dow = date.dayOfWeek.value           // 1=周一 … 7=周日
        if (dow == 6) return DayType.SATURDAY
        if (dow == 7) return DayType.SUNDAY

        val today = courses.filter {
            it.enabled && it.dayOfWeek == dow && week in it.weeks
        }
        if (today.isEmpty()) return DayType.REST // 全天没课 → 按休息日过

        if (today.any { it.startNode <= 2 }) return DayType.A

        return when (dow) {
            2 -> DayType.B_TRAIN_A       // 周二 · 力量 A
            4 -> DayType.B_TRAIN_B       // 周四 · 力量 B
            else -> DayType.B_NORMAL     // 周五
        }
    }

    /**
     * **实际要用的日型** —— 原始规则再经过 [DayTypePolicy] 映射。
     *
     * ## REST 不参与映射
     *
     * 无课休息日在进入策略**之前**就拦下来了：「那天没课」是课表决定的
     * 客观事实，不是用户可以配置的偏好。而且它不在任何 [DayTypePolicy.enabled] 里
     * （它不是可选日型）—— 不拦的话，[DayTypePolicy.resolve] 会把它映射成
     * fallback，假期里又变回上学日，晚自习就回来了。
     *
     * ⚠️ **[policy] 这个参数故意不给默认值。**
     *
     * 如果给它一个默认值，那么调用方「忘了传策略」时不会有任何提示，
     * 程序会静悄悄地按默认策略跑 —— 而界面按 A 型显示、通知却按 B 型排，
     * 这类不一致查起来非常费劲。
     *
     * 不给默认值的话，编译器会把**每一个**调用点都列出来，一个都跑不掉。
     * **让编译器替你找调用点，比靠人记住可靠得多。**
     */
    fun dayType(
        date: LocalDate,
        week: Int,
        courses: List<Course>,
        policy: DayTypePolicy
    ): DayType {
        val natural = naturalDayType(date, week, courses)
        if (natural == DayType.REST) return DayType.REST
        return policy.resolve(natural)
    }

    /**
     * 界面上显示的日型描述 —— 会区分「用哪套模板」和「今天有没有早八」。
     *
     * ## 为什么需要单独一个函数
     *
     * [DayType.label] 只说明「这套模板叫什么」。但用户看到今天标着「A 型日」时，
     * 自然会想知道今天要不要早起 —— 而这**不能从 label 推出来**：
     *
     *   默认配置下   A 型日 ⟺ 当天第 1-2 节有课（有早八）
     *   自定义配置下 用户可以让工作日全部回落到 A 型模板，
     *               于是「A 型日」不再意味着「有早八」
     *
     * 实测过一次：把工作日全落到 A 之后，周二（当天没课）的界面
     * 依然显示「A 型日 · 有早八」—— **那是一句假话**。
     *
     * 所以「有没有早八」去问**日历**（[naturalDayType]），
     * 「用哪套模板」去问**策略**（[dayType]），两者拼起来才是完整的描述。
     */
    fun dayTypeDisplay(
        date: LocalDate,
        week: Int,
        courses: List<Course>,
        policy: DayTypePolicy
    ): String {
        val natural = naturalDayType(date, week, courses)
        // REST 的短路和 dayType() 里的是**同一条规则**，写两遍是因为这个函数
        // 需要 natural（判有早八）和 used（出标签）两个答案，凑不到一次调用里。
        // 改策略映射规则时，两处要一起动。
        val used = if (natural == DayType.REST) natural else policy.resolve(natural)
        return if (natural == DayType.A) "${used.label} · 有早八" else used.label
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
        // 策略跟着模板走 —— 两者都是「用户的作息配置」的一部分，
        // 放在一起就不会出现「用了 A 的策略、却取了 B 的模板」这种错配
        val base = template(dayType(date, week, courses, templates.policy), templates)
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
                isCourse = true
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
                    out += blk.copy(title = "第 ${nodes.first}-${nodes.last} 节 · 无课")
                }
            } else {
                // 固定日程：被课「压」到多少就保留多少
                var segments = listOf(blk)
                for (c in courseBlocks) {
                    segments = segments.flatMap { clip(it, c) }
                }
                out += segments
            }
        }

        // 没有任何占位格认领的课（周末加课、临时调课等），直接插进来并裁掉冲突
        courseBlocks.forEachIndexed { i, c ->
            if (i !in claimed) {
                val kept = out.filter { it.isCourse } +
                        out.filter { !it.isCourse }.flatMap { clip(it, c) }
                out.clear()
                out += kept
                out += c
            }
        }

        return assemble(out)
    }

    /** 把一块 [b] 挖掉与 [c] 重叠的部分，返回剩下的碎片（可能是 0、1 或 2 块） */
    private fun clip(b: Block, c: Block): List<Block> {
        if (b.end <= c.start || b.start >= c.end) return listOf(b)   // 不重叠，原样返回
        val parts = mutableListOf<Block>()
        if (b.start < c.start) parts += b.copy(end = c.start)        // 前一段
        if (b.end > c.end) parts += b.copy(start = c.end)            // 后一段
        return parts
    }

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
            raw += Moment(start, b.end, b.title, b.note, b.place, b.kind, b.isCourse)
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
                last.place == m.place && last.kind == m.kind
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
}
