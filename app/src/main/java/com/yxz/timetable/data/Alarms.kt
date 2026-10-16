package com.yxz.timetable.data

import java.time.LocalDate
import java.time.LocalDateTime

/** 闹钟从哪来：手建的，还是绑在某门课上（课前 N 分钟） */
enum class AlarmSource { MANUAL, COURSE }

/**
 * 一个闹钟。
 *
 * ## 两种闹钟共用一个模型
 *
 * - [AlarmSource.MANUAL]：用户指定「几点 + 周几」，比如 06:30 的工作日起床闹钟。
 * - [AlarmSource.COURSE]：绑一门课，响铃时刻 = **那节课的开始时刻 − [leadMinutes]**，
 *   而且**只在这门课真的上的那一周才响**（周次不在课表里就彻底不排闹钟）。
 *
 * 用同一个模型而不是两个类，是因为它们在闹钟页里排在同一张列表上、
 * 共用开关 / 铃声 / 节假日跳过这些设置 —— 拆成两个类就得写两遍。
 *
 * @param days         周几响（1 = 周一 … 7 = 周日）。「工作日」= 周一到周五全选
 * @param soundUri     这个闹钟的铃声；空串表示「跟随设置页里的默认铃声」
 * @param skipHolidays 智能识别节假日：法定放假日不响、补班日（周末上班）按工作日算
 * @param snoozeMinutes 贪睡时长。系统对穿透 Doze 的闹钟大约每 9 分钟才放行一次，
 *                      所以下限取 10 分钟 —— 设成 5 分钟会被系统推到 10 分钟后才响，
 *                      表现就是「按了贪睡但没动静」
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
    val snoozeMinutes: Int = 10
) {
    val timeText: String get() = "%02d:%02d".format(hour, minute)
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
        return when (alarm.source) {
            AlarmSource.MANUAL -> {
                if (!manualDateMatches(alarm, date, holidays)) return null
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

    /** 列表里显示的名字：有备注用备注，课程闹钟显示「课程名 · 课前 N 分钟」 */
    fun describe(alarm: Alarm, courses: List<Course>): String {
        if (alarm.label.isNotBlank()) return alarm.label
        return when (alarm.source) {
            AlarmSource.MANUAL -> "闹钟"
            AlarmSource.COURSE -> {
                val name = courses.firstOrNull { it.id == alarm.courseId }?.name ?: "已删除的课"
                "$name · 课前 ${alarm.leadMinutes} 分钟"
            }
        }
    }

    /** 绑的课已经不在课表里了（用户删了课 / 换了一份课表）—— 界面上要标出来 */
    fun isOrphan(alarm: Alarm, courses: List<Course>): Boolean =
        alarm.source == AlarmSource.COURSE && courses.none { it.id == alarm.courseId }

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
}
