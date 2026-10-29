package com.yxz.timetable.data

import java.time.LocalDate

/**
 * 节日表：公历固定的 + 农历的。
 *
 * 只放「名字」，不管放不放假 —— 放假安排是另一张表（[Holidays]），
 * 两者的年份覆盖范围也不一样：节日每年都有，放假安排一年发一次通知。
 * 混在一起的话，没内置放假安排的年份就会连「春节是哪天」都算不出来。
 */
object Festivals {

    /** 公历固定日期：month to day to 名字 */
    private val SOLAR = mapOf(
        (1 to 1) to "元旦",
        (2 to 14) to "情人节",
        (3 to 8) to "妇女节",
        (3 to 12) to "植树节",
        (4 to 1) to "愚人节",
        (5 to 1) to "劳动节",
        (5 to 4) to "青年节",
        (6 to 1) to "儿童节",
        (7 to 1) to "建党节",
        (8 to 1) to "建军节",
        (9 to 10) to "教师节",
        (10 to 1) to "国庆节",
        (11 to 11) to "双十一",
        (12 to 24) to "平安夜",
        (12 to 25) to "圣诞节"
    )

    /** 农历日期：month to day to 名字（闰月不算） */
    private val LUNAR = mapOf(
        (1 to 1) to "春节",
        (1 to 15) to "元宵节",
        (2 to 2) to "龙抬头",
        (5 to 5) to "端午节",
        (7 to 7) to "七夕",
        (7 to 15) to "中元节",
        (8 to 15) to "中秋节",
        (9 to 9) to "重阳节",
        (12 to 8) to "腊八节",
        (12 to 23) to "小年"
    )

    /**
     * 这一天是什么节日，没有就返回 null。
     *
     * 农历优先：同一天撞车时（比如某年 8 月 15 日正好是中秋），
     * 农历那个更值得显示。
     */
    fun of(date: LocalDate, lunar: LunarDate): String? {
        if (!lunar.isLeap) {
            LUNAR[lunar.month to lunar.day]?.let { return it }
        }
        if (Lunar.isLunarNewYearEve(date)) return "除夕"
        SOLAR[date.monthValue to date.dayOfMonth]?.let { return it }
        if (date.monthValue == 4) qingmingDay(date.year)?.let { d ->
            if (d == date.dayOfMonth) return "清明节"
        }
        return null
    }

    /**
     * 清明是节气不是固定日期，每年 4 月 4 或 5 日。
     *
     * 用的是通行的近似式（2000–2099 有效）：4.81 + 0.2422 × (年 - 2000)，
     * 再减去闰年修正。查过 2023–2026 四年的实际日期，都对得上。
     * 超出这个世纪就退回 4 月 4 日 —— 那时候这个 App 早该换数据了。
     */
    private fun qingmingDay(year: Int): Int? {
        if (year !in 2000..2099) return 4
        val y = year - 2000
        return (y * 0.2422 + 4.81).toInt() - y / 4
    }
}
