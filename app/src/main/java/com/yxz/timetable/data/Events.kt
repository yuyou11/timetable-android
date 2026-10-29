package com.yxz.timetable.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/**
 * 一条日历日程。
 *
 * 时刻用「距当天 00:00 的分钟数」，和 [Block] / [Moment] 用的是同一种表示 ——
 * 比大小、算时长、判断「在不在这一格」都是一行整数运算。
 *
 * @param date 第一次发生的那天。开 [weekly] 之后，它也负责**定星期几**。
 * @param weekly 每周重复：从 [date] 起，每个同星期的日子都发生；
 *               [until] 非空时到那天为止（含），为空就一直重复，删掉才停。
 *
 * 为什么「重复」不做成 RRULE 那种通用规则：这个 App 的日程就是课表的补充
 * （例会、值班、约人），一周一次覆盖了绝大多数场景；通用规则会让编辑框、导出、
 * 提醒全都复杂一大截，而我们连提醒都还没接。
 */
data class CalEvent(
    val id: String,
    val date: LocalDate,
    val start: Int,
    val end: Int,
    val title: String,
    val place: String = "",
    val note: String = "",
    val weekly: Boolean = false,
    val until: LocalDate? = null
) {
    val duration: Int get() = end - start

    /**
     * 这一天有没有这条日程。
     *
     * 一次性只看日期相等；每周重复看三件事：不早于起始日、星期几相同、没超过截止日。
     * 起始日本身也算（`!day.isBefore(date)` 而不是 `isAfter`）。
     */
    fun occursOn(day: LocalDate): Boolean = when {
        weekly -> !day.isBefore(date) &&
                day.dayOfWeek == date.dayOfWeek &&
                (until == null || !day.isAfter(until))

        else -> day == date
    }
}

/** 一天的日程列表 + 解析时跳过的坏条目数 */
data class EventParseResult(val events: List<CalEvent>, val bad: Int)

/**
 * 日程的纯逻辑：筛选、排序、校验、JSON。
 *
 * 和界面无关，全部能跑在电脑 JVM 上（有单测）—— 这一类「错了不崩」的算术
 * （星期几算错、重复边界差一天、排序不稳定）只有单测能守住。
 */
object Events {

    /** 某一天发生的日程，按开始时刻排；同一时刻按标题，保证顺序稳定 */
    fun on(list: List<CalEvent>, day: LocalDate): List<CalEvent> =
        list.filter { it.occursOn(day) }.sortedWith(compareBy({ it.start }, { it.title }))

    /** 这一天有没有日程（格子上要不要画标记） */
    fun hasAny(list: List<CalEvent>, day: LocalDate): Boolean = list.any { it.occursOn(day) }

    /**
     * 这个月里哪些日子有日程 —— 给月视图的「有日程」小标记用。
     *
     * 只算本月，不含网格里露出来的上下月那几格：那些格子本来就压暗了，
     * 再点一个标记会让人以为点得进去。
     */
    fun daysWithEvents(list: List<CalEvent>, month: YearMonth): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        for (d in 1..month.lengthOfMonth()) {
            val day = month.atDay(d)
            if (hasAny(list, day)) out += day
        }
        return out
    }

    /**
     * 校验并规整一条日程。返回 null 表示这条不该存：
     *
     * - 标题去掉首尾空白后是空的；
     * - 结束不晚于开始（零时长、倒挂都没有意义）；
     * - 每周重复的截止日早于起始日（那样一次都不会发生）。
     *
     * 结尾时刻夹到 24:00（1440）：零点结束就是当天最后一分钟，
     * 而不是「第二天 0 点」——跨天日程这一版不支持。
     */
    fun normalize(e: CalEvent): CalEvent? {
        val title = e.title.trim()
        if (title.isEmpty()) return null
        val start = e.start.coerceIn(0, 1439)
        val end = e.end.coerceIn(0, 1440)
        if (end <= start) return null
        val until = e.until?.takeIf { it >= e.date }
        return e.copy(title = title, start = start, end = end, until = until)
    }

    // ---------------------------------------------------------------- JSON

    fun toJson(list: List<CalEvent>): String {
        val arr = JSONArray()
        for (e in list) arr.put(toJson(e))
        return arr.toString()
    }

    fun toJson(e: CalEvent): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("date", e.date.toString())
        put("start", e.start)
        put("end", e.end)
        put("title", e.title)
        put("place", e.place)
        put("note", e.note)
        put("weekly", e.weekly)
        if (e.until != null) put("until", e.until.toString())
    }

    /**
     * 解析存储里的整串 JSON。坏条目**跳过并计数**，不整串丢弃 ——
     * 一条日程写坏了不该让其余几十条一起消失（和 [Store.courses] 同一套策略）。
     */
    fun parse(raw: String): EventParseResult {
        if (raw.isBlank()) return EventParseResult(emptyList(), 0)
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<CalEvent>(arr.length())
            var bad = 0
            for (i in 0 until arr.length()) {
                val e = try {
                    fromJson(arr.getJSONObject(i))
                } catch (_: Exception) {
                    null
                }
                if (e == null) bad++ else out += e
            }
            EventParseResult(out, bad)
        } catch (_: Exception) {
            EventParseResult(emptyList(), 1)
        }
    }

    private fun fromJson(o: JSONObject): CalEvent? {
        val id = o.optString("id", "")
        val date = LocalDate.parse(o.getString("date"))
        val start = o.getInt("start")
        val end = o.getInt("end")
        val title = o.optString("title", "")
        val place = o.optString("place", "")
        val note = o.optString("note", "")
        val weekly = o.optBoolean("weekly", false)
        val until = o.optString("until", "").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        return normalize(CalEvent(id, date, start, end, title, place, note, weekly, until))
    }
}
