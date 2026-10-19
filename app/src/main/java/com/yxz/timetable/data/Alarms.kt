package com.yxz.timetable.data

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 闹钟从哪来：
 *
 * - [MANUAL]  手建的，用户直接给「几点 + 周几」
 * - [COURSE]  绑在一门课上（课前 N 分钟）
 * - [MOMENT]  绑在时间轴上的**某一格**（今天页点格子设的那种）
 */
enum class AlarmSource { MANUAL, COURSE, MOMENT }

/**
 * 一个闹钟。
 *
 * ## 三种闹钟共用一个模型
 *
 * - [AlarmSource.MANUAL]：用户指定「几点 + 周几」，比如 06:30 的工作日起床闹钟。
 * - [AlarmSource.COURSE]：绑一门课，响铃时刻 = **那节课的开始时刻 − [leadMinutes]**，
 *   而且**只在这门课真的上的那一周才响**（周次不在课表里就彻底不排闹钟）。
 * - [AlarmSource.MOMENT]：绑时间轴上的**一格**（「晚自习」「午餐」「睡觉」…），
 *   见下面「按 tag 响的闹钟」。
 *
 * 用同一个模型而不是三个类，是因为它们在闹钟页里排在同一张列表上、
 * 共用开关 / 铃声 / 节假日跳过这些设置 —— 拆成三个类就得写三遍。
 *
 * ## 按 tag 响的闹钟（[AlarmSource.MOMENT]）
 *
 * 今天页每一个格子背后都是同一个东西：一条 [Moment]（标题 + 性质 + 起点）。
 * 点格子设闹钟时，存的不是「07:35」这个数字，而是**这一格的标签**
 * （[momentTitle] + [momentKind]）。响铃时现算当天的时间轴，找得到这一格
 * 就按它**当天的真实起点**减提前量响 ——
 *
 *   · 模板把「早读」从 07:35 挪到 07:40，闹钟自动跟着挪，不用重新设一遍
 *   · 那天是休息日 / 假期，时间轴里根本没有「早读」这一格 → 不响
 *   · A/B 型日同一格的时刻不同时，各按各的那天响
 *
 * 两个可选的收窄条件，都写在这条闹钟上：
 *
 *   · [days] 非空 → 只在**这几个星期几**响（「每周三有这个 tag 就响」）
 *   · [momentDayType] 非空 → 只在**这种日型**响（勾了「按日型」就是它）。
 *     这时 [days] 不再参与判断 —— 日型已经说明了是哪几天，
 *     再叠一层周几，用户想要「每个 A 型日都响」就表达不出来了。
 *     [days] 仍然留着，作为「取消按日型」之后回退到的那几天。
 *
 * @param days         周几响（1 = 周一 … 7 = 周日）。「工作日」= 周一到周五全选
 * @param soundUri     这个闹钟的铃声；空串表示「跟随设置页里的默认铃声」
 * @param skipHolidays 智能识别节假日：法定放假日不响、补班日（周末上班）按工作日算
 * @param snoozeMinutes 贪睡时长。系统对穿透 Doze 的闹钟大约每 9 分钟才放行一次，
 *                      所以下限取 10 分钟 —— 设成 5 分钟会被系统推到 10 分钟后才响，
 *                      表现就是「按了贪睡但没动静」
 * @param momentTitle  [AlarmSource.MOMENT] 绑的那一格的标题（就是用户看到的字）
 * @param momentKind   同一格的「性质」。标题和性质一起才算认出一格 ——
 *                     不同的格偶尔会重名（「空档 · 机动」每天都在），
 *                     只比标题会把「训练」和「休息」当成同一件事
 * @param momentDayType 只在这种日型响；null = 不限制
 */
data class Alarm(
    val id: String,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<Int> = Alarms.WORKDAYS,
    val enabled: Boolean = true,
    val label: String = "",
    val soundUri: String = "",
    val skipHolidays: Boolean = true,
    val source: AlarmSource = AlarmSource.MANUAL,
    val courseId: String = "",
    val leadMinutes: Int = 0,
    val snoozeMinutes: Int = 10,
    /**
     * 「只响一次」的那一天（只在 [days] 为空、且是手建闹钟时有效）。
     *
     * 把七个星期**全部取消勾选**，意思就是「不重复，只响一次」—— 这是闹钟的
     * 常规语义（`daySummary` 里也一直写着「不重复」）。存下来的那一刻就把
     * 「下一次那个时刻」算成具体日期固定住；日期一过自然就不响了，
     * 真响过之后接收器还会把它关掉，免得第二天同一时刻又响。
     */
    val onceDate: LocalDate? = null,
    /**
     * 「跳过下一次」：只让这一天不响，过后自动失效。
     *
     * 和直接关掉开关的区别：关掉要记得再打开，而这是一次性的 ——
     * 临时放假、调休、这周不上课都用得上。
     */
    val skipDate: LocalDate? = null,
    val momentTitle: String = "",
    val momentKind: Kind? = null,
    val momentDayType: DayType? = null
) {
    val timeText: String get() = "%02d:%02d".format(hour, minute)

    /**
     * 提前量说成人话。0 是「到点提醒」而不是「提前 0 分钟」——
     * 后者读起来像少打了个数字。
     */
    val leadText: String
        get() = if (leadMinutes == 0) "到点提醒" else "提前 $leadMinutes 分钟"
}

/** 一次即将发生的响铃 */
data class AlarmFire(val alarm: Alarm, val at: LocalDateTime, val minuteOfDay: Int) {
    val date: LocalDate get() = at.toLocalDate()
}

/**
 * 闹钟的纯逻辑：**什么时候该响、什么时候不该响**。
 *
 * 这一层不碰 Android（没有 Context、没有 AlarmManager），所以能直接在电脑上
 * 跑单测 —— 而闹钟最要命的 bug 恰恰都在这里：「补班日到底响不响」
 * 「这门课第 5 周没课，闹钟还会不会响」。这些判断错一次，用户那天就迟到了。
 *
 * ## 「只有有课的时候才响」是怎么做到的
 *
 * 不是响的时候再问一遍「今天有课吗」——那样每次都要唤醒进程来判断，白费电。
 * 而是在**排程的时候**就把周次算清楚：第 5 周这门课不在 [Course.weeks] 里，
 * [fireMinuteOfDay] 直接返回 null，那个时刻**根本不会排进系统闹钟**，
 * 一次唤醒都不会发生。
 */
object Alarms {

    /** 「工作日」预设：周一到周五 */
    val WORKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

    /** 周末 */
    val WEEKEND: Set<Int> = setOf(6, 7)

    /** 贪睡下限。见 [Alarm.snoozeMinutes] 的说明（Doze 的 9 分钟限速） */
    const val MIN_SNOOZE_MINUTES = 10

    /** 往后找多少天。两周足够跨过任何一个「这周没课、下周才上」的间隔 */
    const val LOOKAHEAD_DAYS = 14

    /** 「工作日闹钟」：周一到周五全选、且不含周末。补班日只有这种闹钟会响 */
    fun isWorkdayAlarm(a: Alarm): Boolean =
        a.days.containsAll(WORKDAYS) && a.days.none { it in WEEKEND }

    /**
     * 周几集合 → 人话。周一到周五全选时说「工作日」而不是「一 二 三 四 五」——
     * 后者不但长，还会让人以为这是五个独立的选择。
     */
    fun daySummary(days: Set<Int>): String = when {
        days.isEmpty() -> "不重复"
        days == WORKDAYS -> "工作日"
        days == WEEKEND -> "周末"
        days.size == 7 -> "每天"
        else -> (1..7).filter { it in days }
            .joinToString(" ") { ScheduleFormat.weekdayCn(it).removePrefix("周") }
    }

    /**
     * 这个闹钟在 [date] 这一天响铃的时刻（距当天 00:00 的分钟数）；不该响就是 null。
     *
     * @param week  [date] 属于第几教学周（由 [Store.weekOf] 算出来传进来，
     *              这一层不认识学期从哪天开始）
     */
    fun fireMinuteOfDay(
        alarm: Alarm,
        date: LocalDate,
        week: Int,
        courses: List<Course>,
        templates: TemplateSet,
        holidays: HolidayTable = HolidayTable.BUILTIN_ONLY
    ): Int? {
        if (!alarm.enabled) return null
        // 用户手动跳过的那一天：两种闹钟都不响，过后自动失效
        if (alarm.skipDate == date) return null
        return when (alarm.source) {
            AlarmSource.MANUAL -> {
                if (alarm.days.isEmpty()) {
                    // 只响一次：日期是固定的那一个，过了就不再响。
                    // **不套节假日判断** —— 用户明确指定了「就这一次、就这个点」，
                    // 静默跳过等于让他误了飞机；要跳过节假日就别用只响一次。
                    if (alarm.onceDate != date) return null
                } else if (!manualDateMatches(alarm, date, holidays)) {
                    return null
                }
                alarm.hour * 60 + alarm.minute
            }

            // 课程闹钟：时刻由课表推，而且**这一周没课就返回 null**（不排、不醒、不响）。
            // ⚠️ 它不看 alarm.days —— 课程闹钟的「周几」由课程自己决定（days 是空集）。
            // 早期版本把「周几匹配」无差别套在两种闹钟上，结果课程闹钟一次都不会响。
            AlarmSource.COURSE -> {
                if (alarm.skipHolidays && holidays.isOffDay(date)) return null
                val c = courses.firstOrNull { it.id == alarm.courseId } ?: return null
                if (!c.enabled) return null
                if (c.dayOfWeek != date.dayOfWeek.value) return null
                if (week !in c.weeks) return null
                templates.slotStart(c.startNode) - alarm.leadMinutes
            }

            // 按 tag 响的闹钟：现算这一天的时间轴，**找得到这一格**才响，
            // 时刻用它当天的真实起点减提前量（见 [Alarm] 顶部「按 tag 响的闹钟」）。
            // 这里现算而不是存死时刻，是这类闹钟唯一能跟着模板变化走的写法。
            AlarmSource.MOMENT -> {
                if (alarm.skipHolidays && holidays.isOffDay(date)) return null
                if (alarm.momentDayType != null) {
                    // 勾了「按日型」：日型已经说明了是哪几天，不再看周几
                    val type = TimelineEngine.dayType(date, week, courses, templates.config)
                    if (type != alarm.momentDayType) return null
                } else if (alarm.days.isNotEmpty() && date.dayOfWeek.value !in alarm.days) {
                    return null
                }
                if (alarm.momentTitle.isBlank()) return null
                val m = TimelineEngine.moments(date, week, courses, templates)
                    .firstOrNull { it.title == alarm.momentTitle && it.kind == alarm.momentKind }
                    ?: return null
                // 提前量比整点还大（00:10 的一格要提前 20 分钟）—— 那天没法提前，不响。
                // 返回负数的话排程会算出一个昨天的时刻，那比不响更难查。
                (m.start - alarm.leadMinutes).takeIf { it >= 0 }
            }
        }
    }

    /**
     * 手建闹钟的日期判断：该不该响、以及节假日怎么算。
     *
     * 规则刻意写成两种模式，好向用户解释：
     *
     * - **[Alarm.skipHolidays] 打开（默认）**：法定放假日一律不响；
     *   **补班日**（周末上班）按工作日算 —— 「工作日闹钟」那天会响，
     *   而「周末闹钟」那天不响。这正是「智能识别节假日」的全部含义。
     * - **关掉**：只看星期几，完全不看日历。放假照响、补班日照旧。
     */
    private fun manualDateMatches(alarm: Alarm, date: LocalDate, holidays: HolidayTable): Boolean {
        val dow = date.dayOfWeek.value
        if (!alarm.skipHolidays) return dow in alarm.days

        if (holidays.isOffDay(date)) return false

        val makeup = holidays.isMakeupWorkday(date)
        if (dow in alarm.days) {
            // 补班日：这天是周末但要上班，所以「周末型」闹钟不该响
            return !makeup
        }
        // 没直接命中：补班日让「工作日闹钟」响起来
        return makeup && isWorkdayAlarm(alarm)
    }

    /**
     * 找出 [from] 之后最早的一次响铃；一个都没有就返回 null。
     *
     * 逐天往后找（最多 [LOOKAHEAD_DAYS] 天），把所有闹钟的候选时刻取最小值 ——
     * 和 [AlarmScheduler] 那边「一个闹钟 + 取最小」的思路一致：
     * 多个理由撞在同一分钟时只醒一次。
     */
    fun nextFire(
        alarms: List<Alarm>,
        from: LocalDateTime,
        courses: List<Course>,
        templates: TemplateSet,
        holidays: HolidayTable = HolidayTable.BUILTIN_ONLY,
        weekOf: (LocalDate) -> Int,
        lookaheadDays: Int = LOOKAHEAD_DAYS
    ): AlarmFire? {
        var best: AlarmFire? = null
        for (offset in 0..lookaheadDays) {
            val date = from.toLocalDate().plusDays(offset.toLong())
            val week = weekOf(date)
            for (a in alarms) {
                val m = fireMinuteOfDay(a, date, week, courses, templates, holidays) ?: continue
                val at = date.atStartOfDay().plusMinutes(m.toLong())
                if (!at.isAfter(from)) continue
                if (best == null || at.isBefore(best!!.at)) best = AlarmFire(a, at, m)
            }
        }
        return best
    }

    /**
     * 此刻这一分钟里**所有**该响的闹钟。
     *
     * 为什么要一次拿一批：两个闹钟设在同一分钟是很常见的（起床 + 吃药）。
     * 排程时只排了最早的哪一个，后一个到期时也已经在窗口里了 ——
     * 不一起响的话，第二个要么漏掉、要么被系统推迟到 9 分钟以后。
     */
    fun dueAt(
        alarms: List<Alarm>,
        at: LocalDateTime,
        courses: List<Course>,
        templates: TemplateSet,
        holidays: HolidayTable = HolidayTable.BUILTIN_ONLY,
        weekOf: (LocalDate) -> Int,
        windowMinutes: Int = 1
    ): List<Alarm> {
        val minute = at.hour * 60 + at.minute
        return alarms.filter { a ->
            val m = fireMinuteOfDay(
                a, at.toLocalDate(), weekOf(at.toLocalDate()), courses, templates, holidays
            )
            m != null && minute >= m && minute < m + windowMinutes
        }
    }

    /** 只响一次的闹钟：手建 + 七天的勾全取消 */
    fun isOneShot(a: Alarm): Boolean =
        a.source == AlarmSource.MANUAL && a.days.isEmpty()

    /**
     * 「只响一次」且那一天已经过去了 —— 它永远不会再响，界面上要说清楚，
     * 而不是让用户看着一个「启用中」的闹钟、以为明天早上会被叫醒。
     */
    fun isExpiredOneShot(a: Alarm, today: LocalDate): Boolean =
        isOneShot(a) && a.onceDate?.isBefore(today) == true

    /**
     * 「只响一次」定在哪一天：今天这个点还没过就是今天，已经过了就是明天。
     *
     * 存的时候算一次、固定下来（写进 [Alarm.onceDate]），而不是每次排程时现算 ——
     * 现算的话「每次排程都把明天挪成新的明天」，那就成了每天响一次。
     */
    fun oneShotDate(hour: Int, minute: Int, now: LocalDateTime): LocalDate {
        val today = now.toLocalDate()
        return if (today.atTime(hour, minute).isAfter(now)) today else today.plusDays(1)
    }

    /** 列表里显示的名字：有备注用备注，课程闹钟显示「课程名 · 课前 N 分钟」 */
    fun describe(alarm: Alarm, courses: List<Course>): String {
        if (alarm.label.isNotBlank()) return alarm.label
        return when (alarm.source) {
            AlarmSource.MANUAL -> "闹钟"
            AlarmSource.COURSE -> {
                val name = courses.firstOrNull { it.id == alarm.courseId }?.name ?: "已删除的课"
                "$name · 课前 ${alarm.leadMinutes} 分钟"
            }
            AlarmSource.MOMENT -> "${alarm.momentTitle} · ${alarm.leadText}"
        }
    }

    /**
     * 「按 tag 响」的闹钟在哪几天响，写成一行字。
     *
     * 这类闹钟没有固定时刻（时刻来自当天的时间轴），列表上能说的只有两件事：
     * 哪几天、提前多久。提前量由 [Alarm.leadText] 说，这里只说哪几天。
     *
     * 「每周一三五」而不是「周一 周三 周五」：闹钟页一行只放得下这么几个字，
     * 而星期几本来就是一个字符的事。
     */
    fun momentScope(alarm: Alarm, names: Map<DayType, String> = emptyMap()): String {
        alarm.momentDayType?.let { return "每个「${dayTypeName(it, names)}」" }
        val days = alarm.days
        return when {
            days.isEmpty() || days.size == 7 -> "每天"
            days == WORKDAYS -> "每个工作日"
            else -> "每周" + (1..7).filter { it in days }
                .joinToString("") { ScheduleFormat.weekdayCn(it).removePrefix("周") }
        }
    }

    /** 绑的课已经不在课表里了（用户删了课 / 换了一份课表）—— 界面上要标出来 */
    fun isOrphan(alarm: Alarm, courses: List<Course>): Boolean =
        alarm.source == AlarmSource.COURSE && courses.none { it.id == alarm.courseId }

    /**
     * 「按格子响」的闹钟绑的那一格，在接下来的时间轴里还找不找得到。
     *
     * 和 [isOrphan] 是同一类问题：导入了一份新模板 / 把「午餐」改成了别的名字之后，
     * 那条闹钟会变成一条**看着开着、永远不响**的闹钟。用户以为有保险，其实没有。
     *
     * 为什么是「翻一遍接下来这些天的时间轴」而不是「翻一遍模板」：
     * 今日页上真正的格子有一半是引擎算出来的 ——「空档 · 机动」「第 1-2 节 · 无课」
     * 在模板里根本没有同名的一格。按模板比对的话，这两类格子一设完就会
     * 立刻被标成「已不在模板里」，比不标更糟。
     *
     * 两周足够跨过任何一个「这周没课、下周才上」的间隔（和 [LOOKAHEAD_DAYS] 一致）。
     */
    fun isLostMoment(
        alarm: Alarm,
        courses: List<Course>,
        templates: TemplateSet,
        weekOf: (LocalDate) -> Int,
        from: LocalDate = LocalDate.now()
    ): Boolean {
        if (alarm.source != AlarmSource.MOMENT) return false
        for (offset in 0..LOOKAHEAD_DAYS) {
            val date = from.plusDays(offset.toLong())
            val hit = TimelineEngine.moments(date, weekOf(date), courses, templates)
                .any { it.title == alarm.momentTitle && it.kind == alarm.momentKind }
            if (hit) return false
        }
        return true
    }

    /** 课程的自然键：课名 + 星期 + 起始节。课程 id 会随导入重编号，这个不会 */
    internal fun courseKey(c: Course): String = "${c.name}|${c.dayOfWeek}|${c.startNode}"

    /**
     * 课表被替换后，把课程闹钟按自然键接到新课程上。
     *
     * 为什么不能只靠 courseId：导入时整张表会重新编号（c001、c002…），
     * 同一门课可能换了 id；直接留着旧 id，轻则闹钟变孤儿永远不响，
     * 重则**指向另一门课、在错误的时间响**。
     *
     * 新表里找不到对应课程的，停用 —— 不要留一条「看着开着、永远不响」的闹钟。
     */
    fun remapCourseAlarms(
        alarms: List<Alarm>,
        oldCourses: List<Course>,
        newCourses: List<Course>
    ): List<Alarm> {
        val oldById = oldCourses.associateBy { it.id }
        // 同一门课一周上两次时会有多个同键课程，按顺序一个配一个
        val newIdsByKey = newCourses.groupBy { courseKey(it) }
            .mapValues { (_, list) -> list.map { it.id }.toMutableList() }

        return alarms.map { a ->
            if (a.source != AlarmSource.COURSE) return@map a
            val old = oldById[a.courseId] ?: return@map a
            val mapped = newIdsByKey[courseKey(old)]?.removeFirstOrNull()
            if (mapped != null) a.copy(courseId = mapped) else a.copy(enabled = false)
        }
    }

    /** 新建一个手建闹钟（id 由调用方给，和其它地方一样是自增编号） */
    fun manual(id: String, hour: Int, minute: Int, days: Set<Int> = WORKDAYS): Alarm =
        Alarm(id = id, hour = hour, minute = minute, days = days)

    /** 从课程卡片上的「设置闹钟」按钮建一个课程闹钟 */
    fun forCourse(id: String, courseId: String, leadMinutes: Int): Alarm =
        Alarm(
            id = id,
            hour = 0,
            minute = 0,
            days = emptySet(),
            source = AlarmSource.COURSE,
            courseId = courseId,
            leadMinutes = leadMinutes
        )

    /**
     * 从今日页的格子卡片上建一个「按 tag 响」的闹钟。
     *
     * @param days     基准是「这一格出现的那几天」—— 点今天页就传今天星期几
     * @param dayType  非 null 表示「按日型响」。这时判断只看日型，
     *                 但 [days] 照样存着：用户改主意取消按日型时，
     *                 要退回「每周三」而不是变成「每天」
     */
    fun forMoment(
        id: String,
        title: String,
        kind: Kind,
        days: Set<Int>,
        dayType: DayType? = null,
        leadMinutes: Int = 0
    ): Alarm =
        Alarm(
            id = id,
            hour = 0,
            minute = 0,
            days = days,
            source = AlarmSource.MOMENT,
            leadMinutes = leadMinutes,
            momentTitle = title,
            momentKind = kind,
            momentDayType = dayType
        )
}
