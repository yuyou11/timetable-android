package com.yxz.timetable.data

import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import java.time.LocalDate
import java.time.ZoneId

private val MONTH_NAMES = arrayOf(
    "正月", "二月", "三月", "四月", "五月", "六月",
    "七月", "八月", "九月", "十月", "冬月", "腊月"
)

private val DAY_NAMES = arrayOf(
    "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
    "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
    "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
)

/**
 * 农历日期（只保留日历页要用的三项：月、日、是否闰月）。
 *
 * @param month 1..12，1 = 正月，12 = 腊月
 * @param day   1..30
 * @param isLeap 是不是闰月里的日子
 */
data class LunarDate(val month: Int, val day: Int, val isLeap: Boolean) {

    /** 「正月」「闰六月」「腊月」 */
    val monthName: String
        get() = (if (isLeap) "闰" else "") + MONTH_NAMES[month - 1]

    /** 「初一」「十五」「廿三」「三十」 */
    val dayName: String get() = DAY_NAMES[day - 1]

    /** 格子里的第二行：初一只写月份，其余写日子 */
    val shortName: String get() = if (day == 1) monthName else dayName
}

/**
 * 公历 ↔ 农历。
 *
 * ## 为什么用 android.icu 而不是自己抄一张万年历表
 *
 * 自己实现要么背一张 1900–2100 的压缩表（200 个魔数，抄错一位就是某年某月整体错位），
 * 要么写一遍定朔定气的天文算法。Android 从 API 24 起自带 ICU，
 * 里面就有 [ChineseCalendar] —— 同一套规则、经过 ICU 的测试集，
 * 而且不用联网、不用打包数据。minSdk 是 26，够用。
 *
 * ## 为什么把时区钉死在 Asia/Shanghai
 *
 * 农历的「初一」是按东八区的朔日算的。手机如果设成别的时区，
 * ICU 会按当地时区取日期，边界那几天就会整体偏一天。
 * 这个 App 是给国内课表用的，钉死东八区才是对的。
 */
object Lunar {

    private const val TZ_ID = "Asia/Shanghai"
    private val zone: ZoneId = ZoneId.of(TZ_ID)

    fun of(date: LocalDate): LunarDate {
        val cal = ChineseCalendar()
        cal.timeZone = TimeZone.getTimeZone(TZ_ID)
        // 取当天中午，避开「零点整」在闰秒 / 时区边界上的抖动
        cal.timeInMillis = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return LunarDate(
            month = cal.get(ChineseCalendar.MONTH) + 1,
            day = cal.get(ChineseCalendar.DAY_OF_MONTH),
            isLeap = cal.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        )
    }

    /**
     * 这一天是不是除夕（腊月最后一天）。
     *
     * 农历没有「第 31 天」这种说法，腊月是大月 30 天还是小月 29 天得算，
     * 所以只能看「明天是不是正月初一」—— 这是唯一不需要查表的判据。
     */
    fun isLunarNewYearEve(date: LocalDate): Boolean {
        val today = of(date)
        if (today.month != 12 || today.isLeap) return false
        val tomorrow = of(date.plusDays(1))
        return tomorrow.month == 1 && tomorrow.day == 1 && !tomorrow.isLeap
    }
}
