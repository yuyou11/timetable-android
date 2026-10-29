package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历日程的纯逻辑：哪天发生、怎么排、怎么写进存储。
 *
 * 这些错误全都「不崩」：星期几算错就是某条例会提前一天出现，排序不稳就是
 * 同一屏幕上两条日程换位置 —— 装个 APK 用眼睛是看不出来的。
 */
class EventsTest {

    private fun e(
        id: String = "e001",
        date: String = "2026-10-07",
        start: Int = 20 * 60,
        end: Int = 21 * 60,
        title: String = "例会",
        place: String = "",
        note: String = "",
        weekly: Boolean = false,
        until: String? = null
    ) = CalEvent(
        id = id,
        date = LocalDate.parse(date),
        start = start,
        end = end,
        title = title,
        place = place,
        note = note,
        weekly = weekly,
        until = until?.let { LocalDate.parse(it) }
    )

    // ---------- 一、哪天发生 ----------

    @Test
    fun `一次性的日程只在那一天`() {
        val ev = e(date = "2026-10-07")
        assertTrue(ev.occursOn(LocalDate.parse("2026-10-07")))
        assertFalse(ev.occursOn(LocalDate.parse("2026-10-08")))
        assertFalse("前一周也不算", ev.occursOn(LocalDate.parse("2026-09-30")))
    }

    @Test
    fun `每周重复从起始那天开始，之后每个同星期几都算`() {
        // 2026-10-07 是周三
        val ev = e(date = "2026-10-07", weekly = true)
        assertTrue("起始日自己", ev.occursOn(LocalDate.parse("2026-10-07")))
        assertTrue(ev.occursOn(LocalDate.parse("2026-10-14")))
        assertTrue(ev.occursOn(LocalDate.parse("2026-12-30")))
        assertFalse("起始日之前", ev.occursOn(LocalDate.parse("2026-09-30")))
        assertFalse("同周但不是周三", ev.occursOn(LocalDate.parse("2026-10-08")))
    }

    @Test
    fun `每周重复的截止日含当天，之后不再发生`() {
        val ev = e(date = "2026-10-07", weekly = true, until = "2026-10-21")
        assertTrue(ev.occursOn(LocalDate.parse("2026-10-21")))
        assertFalse(ev.occursOn(LocalDate.parse("2026-10-28")))
    }

    // ---------- 二、某一天 / 某个月 ----------

    @Test
    fun `某一天的日程按开始时刻排序，同一时刻按标题`() {
        val list = listOf(
            e(id = "e1", date = "2026-10-07", start = 20 * 60, title = "例会"),
            e(id = "e2", date = "2026-10-07", start = 8 * 60, title = "早课答疑"),
            e(id = "e3", date = "2026-10-07", start = 20 * 60, title = "团建"),
            e(id = "e4", date = "2026-10-08", start = 9 * 60, title = "别的日子")
        )
        val got = Events.on(list, LocalDate.parse("2026-10-07")).map { it.title }
        // 同一时刻的两条按标题的码位排（"例" U+4F8B 在 "团" U+56E2 前面）——
        // 这里只要求**顺序稳定**，不要求按拼音
        assertEquals(listOf("早课答疑", "例会", "团建"), got)
    }

    @Test
    fun `月视图的有日程日期把每周重复的日子也算进去`() {
        val list = listOf(e(date = "2026-09-30", weekly = true))   // 周三
        val days = Events.daysWithEvents(list, YearMonth.of(2026, 10))
        assertEquals(
            setOf(
                LocalDate.parse("2026-10-07"),
                LocalDate.parse("2026-10-14"),
                LocalDate.parse("2026-10-21"),
                LocalDate.parse("2026-10-28")
            ),
            days
        )
    }

    @Test
    fun `月视图不会把上个月露出来的格子算进来`() {
        val list = listOf(e(date = "2026-09-28"))   // 9 月 28 日，周一
        val days = Events.daysWithEvents(list, YearMonth.of(2026, 10))
        assertTrue(days.isEmpty())
    }

    // ---------- 三、校验 ----------

    @Test
    fun `标题空白、时长为零或倒挂的都存不进去`() {
        assertNull(Events.normalize(e(title = "   ")))
        assertNull(Events.normalize(e(start = 600, end = 600)))
        assertNull(Events.normalize(e(start = 700, end = 600)))
    }

    @Test
    fun `标题去掉首尾空白，截止日记早于起始日时丢掉`() {
        val ok = Events.normalize(e(title = "  例会  ", weekly = true, until = "2026-10-01"))
        assertEquals("例会", ok?.title)
        assertNull("截止日比起始日还早，一次都不会发生，不如当没写", ok?.until)
    }

    @Test
    fun `结尾超过二十四点会夹到当天最后一刻`() {
        val ok = Events.normalize(e(start = 1380, end = 1500))
        assertEquals(1440, ok?.end)
    }

    // ---------- 四、存储 ----------

    @Test
    fun `JSON 往返之后每个字段都在`() {
        val src = listOf(
            e(id = "e001", date = "2026-10-07", start = 1200, end = 1260, title = "例会",
                place = "教三-201", note = "带材料", weekly = true, until = "2026-12-30"),
            e(id = "e002", date = "2026-10-09", start = 600, end = 660, title = "体检")
        )
        val back = Events.parse(Events.toJson(src))
        assertEquals(0, back.bad)
        assertEquals(src, back.events)
    }

    @Test
    fun `坏条目跳过并计数，其余照常读出来`() {
        val raw = """
            [
              {"id":"e001","date":"2026-10-07","start":1200,"end":1260,"title":"例会"},
              {"id":"e002","date":"坏日期","start":600,"end":660,"title":"读不出来"},
              {"id":"e003","date":"2026-10-09","start":600,"end":600,"title":"零时长"}
            ]
        """.trimIndent()
        val r = Events.parse(raw)
        assertEquals(1, r.events.size)
        assertEquals(2, r.bad)
        assertEquals("例会", r.events.first().title)
    }

    @Test
    fun `空串解析成空列表，不算坏条目`() {
        val r = Events.parse("")
        assertTrue(r.events.isEmpty())
        assertEquals(0, r.bad)
    }
}
