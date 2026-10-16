package com.yxz.timetable.data

import java.time.LocalDate

/**
 * 一天的性质：法定放假，或者「周末但上班」的补班日。
 *
 * @param name   节日名（元旦 / 春节 / 国庆节…），补班日也用节日的名字
 * @param date   哪一天
 * @param offDay true = 放假；false = 补班（那天是周末但要上班）
 */
data class HolidayDay(val name: String, val date: LocalDate, val offDay: Boolean)

/**
 * 内置的中国法定节假日表。
 *
 * ============================================================
 *  为什么是「烘进代码的一张表」，而不是联网查
 * ============================================================
 *
 * 这个 App **没有联网权限**（见 README 第八节），而且它的省电设计建立在
 * 「空闲期进程根本不存在」之上。为了知道「十月一号放不放假」去开网络、
 * 去轮询接口，和这套设计是直接冲突的。
 *
 * 放假安排一年才动一次（国务院办公厅每年 11 月前后发通知），把它变成
 * 一张静态表，运行时只剩一次哈希查表 —— **零网络、零轮询、零唤醒**。
 * 代价是每年要更新一次 App（或有新通知时把新的 JSON 并进来）。
 *
 * ## 数据来源与核对
 *
 * 2026 年这份逐条抄自国务院办公厅《关于 2026 年部分节假日安排的通知》
 * （国办发明电〔2025〕7 号，2025-11-04 发布），并与社区维护的机器可读版本
 * （NateScarlet/holiday-cn 的 2026.json）**逐条比对过**，两者完全一致。
 *
 * ⚠️ 这张表**只覆盖已经发布通知的年份**。没覆盖到的年份由
 * [HolidayTable.covers] 如实报告，界面会提示用户「这一年的安排还没有内置」，
 * 而不是悄悄把工作日当成周末。
 */
object Holidays {

    private const val OFF = true
    private const val WORK = false

    /**
     * 2026 年：放假 33 天、补班 6 天。
     *
     * 补班日是这套表里最容易被忽略、但很关键的一半：
     * 「周末上班」意味着那天的起床闹钟**应该响**，
     * 而「周末闹钟」那天**不该响** —— 只记放假不记补班，两头都会错。
     */
    private val Y2026: List<HolidayDay> = buildList {
        fun off(name: String, from: String, to: String = from) {
            var d = LocalDate.parse(from)
            val end = LocalDate.parse(to)
            while (!d.isAfter(end)) {
                add(HolidayDay(name, d, OFF))
                d = d.plusDays(1)
            }
        }

        fun work(name: String, date: String) = add(HolidayDay(name, LocalDate.parse(date), WORK))

        // 元旦：1/1（周四）–1/3（周六）放假，1/4（周日）上班
        off("元旦", "2026-01-01", "2026-01-03")
        work("元旦", "2026-01-04")

        // 春节：2/15（周日）–2/23（周一）放假共 9 天，2/14、2/28 两个周六上班
        off("春节", "2026-02-15", "2026-02-23")
        work("春节", "2026-02-14")
        work("春节", "2026-02-28")

        // 清明：4/4（周六）–4/6（周一）放假，不调休
        off("清明节", "2026-04-04", "2026-04-06")

        // 劳动节：5/1（周五）–5/5（周二）放假，5/9（周六）上班
        off("劳动节", "2026-05-01", "2026-05-05")
        work("劳动节", "2026-05-09")

        // 端午：6/19（周五）–6/21（周日）放假，不调休
        off("端午节", "2026-06-19", "2026-06-21")

        // 中秋：9/25（周五）–9/27（周日）放假，不调休
        off("中秋节", "2026-09-25", "2026-09-27")

        // 国庆：10/1（周四）–10/7（周三）放假，9/20（周日）、10/10（周六）上班
        off("国庆节", "2026-10-01", "2026-10-07")
        work("国庆节", "2026-09-20")
        work("国庆节", "2026-10-10")
    }

    val BUILTIN: List<HolidayDay> = Y2026

    /** 内置表覆盖了哪些年份。没覆盖的年份 [HolidayTable.covers] 会返回 false */
    val COVERED_YEARS: Set<Int> = Y2026.map { it.date.year }.toSet()
}

/**
 * 生效中的节假日表：**内置那份 + 用户手动加的那份**。
 *
 * 用户手动加的优先级更高 —— 临时调休、学校自己的放假安排、以及每年 11 月
 * 新通知出来前的空档，都靠它兜。
 *
 * 全部查表都是 O(1)，构造一次就能反复用；它**不进任何后台流程**，
 * 只在「算下一次闹钟该不该响」时被查一下。
 */
class HolidayTable(private val custom: List<HolidayDay> = emptyList()) {

    private val offDays = HashMap<LocalDate, String>()
    private val workDays = HashMap<LocalDate, String>()

    init {
        for (d in Holidays.BUILTIN) put(d)
        for (d in custom) put(d)          // 手动那份后放，可以覆盖内置
    }

    /** 同一天只能是一种性质：重新登记时把另一种抹掉，免得自相矛盾 */
    private fun put(d: HolidayDay) {
        offDays.remove(d.date)
        workDays.remove(d.date)
        if (d.offDay) offDays[d.date] = d.name else workDays[d.date] = d.name
    }

    /** 法定放假日（不管星期几） */
    fun isOffDay(date: LocalDate): Boolean = offDays.containsKey(date)

    /** 补班日：周末但要上班 */
    fun isMakeupWorkday(date: LocalDate): Boolean = workDays.containsKey(date)

    /** 这一天的节日名，普通日子是 null */
    fun label(date: LocalDate): String? = offDays[date] ?: workDays[date]

    /**
     * 这一年的放假安排有没有被覆盖到（内置或用户手动都算）。
     *
     * 界面拿它来决定要不要提示「2027 年的安排还没内置」——
     * 静默按周末处理会让人以为 App 算错了，说出来才诚实。
     */
    fun covers(year: Int): Boolean =
        Holidays.COVERED_YEARS.contains(year) || custom.any { it.date.year == year }

    companion object {
        /** 空表：只有内置那份 */
        val BUILTIN_ONLY = HolidayTable()
    }
}
