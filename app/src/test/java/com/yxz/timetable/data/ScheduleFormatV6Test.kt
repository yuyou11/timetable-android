package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * v6 的两段「私人物品」：闹钟和日程。
 *
 * 这两段最容易出的错不是崩，而是**静默丢/静默重**：字段漏写一个，
 * 导入后闹钟就少了「提前 10 分钟」；去重键选错，合并一次就多出一倍。
 * 所以这里既测往返（每个字段都要回来），也测合并（按自然键而不是 id）。
 */
class ScheduleFormatV6Test {

    private fun ok(json: String) = when (val r = ScheduleFormat.parse(json)) {
        is ScheduleFormat.Result.Ok -> r.parsed
        is ScheduleFormat.Result.Failed -> throw AssertionError("本该解析成功：${r.message}")
    }

    private fun failed(json: String) = when (val r = ScheduleFormat.parse(json)) {
        is ScheduleFormat.Result.Failed -> r.message
        is ScheduleFormat.Result.Ok -> throw AssertionError("本该解析失败，却成功了")
    }

    private val baseTerm = """
        "term": {"name": "测试学期", "startDate": "2026-09-07", "totalWeeks": 19}
    """.trimIndent()

    private fun alarm(
        id: String = "a001",
        hour: Int = 7,
        minute: Int = 5,
        days: Set<Int> = setOf(1, 3, 5),
        label: String = "起床",
        source: AlarmSource = AlarmSource.MANUAL,
        lead: Int = 10,
        snooze: Int = 10,
        once: LocalDate? = null,
        courseId: String = "",
        momentTitle: String = ""
    ) = Alarm(
        id = id, hour = hour, minute = minute, days = days,
        label = label, source = source, leadMinutes = lead, snoozeMinutes = snooze,
        onceDate = once, courseId = courseId, momentTitle = momentTitle
    )

    private fun event(
        id: String = "e001",
        date: String = "2026-10-07",
        start: Int = 1200,
        end: Int = 1260,
        title: String = "例会",
        weekly: Boolean = false,
        until: String? = null
    ) = CalEvent(
        id = id, date = LocalDate.parse(date), start = start, end = end,
        title = title, weekly = weekly, until = until?.let { LocalDate.parse(it) }
    )

    // ---------- 一、往返 ----------

    @Test
    fun `v6 导出带闹钟和日程，解析回来每个字段都在`() {
        val alarms = listOf(
            alarm(id = "a001", once = LocalDate.of(2026, 10, 8), lead = 15),
            alarm(
                id = "a002", hour = 8, minute = 30, days = setOf(2, 4), label = "早八",
                source = AlarmSource.COURSE, courseId = "c001", lead = 0, snooze = 15
            )
        )
        val events = listOf(
            event(id = "e001", weekly = true, until = "2026-12-30"),
            event(id = "e002", date = "2026-10-09", start = 600, end = 660, title = "体检")
        )

        val json = ScheduleFormat.serialize(
            "测试学期", LocalDate.of(2026, 9, 7), 19, emptyList(),
            alarms = alarms, events = events
        )
        assertTrue("默认导出应该是 v6", json.contains("\"version\": 6"))

        val p = ok(json)
        assertEquals(alarms, p.alarms)
        assertEquals(events, p.events)
    }

    @Test
    fun `没有闹钟和日程时整段不写，文件保持干净`() {
        val json = ScheduleFormat.serialize("学期", LocalDate.of(2026, 9, 7), 19, emptyList())
        assertTrue(!json.contains("\"alarms\""))
        assertTrue(!json.contains("\"events\""))
    }

    @Test
    fun `默认值不写进文件：贪睡还是 10 分钟就不出现 snooze 键`() {
        val json = ScheduleFormat.serialize(
            "学期", LocalDate.of(2026, 9, 7), 19, emptyList(),
            alarms = listOf(alarm(snooze = 10, lead = 0))
        )
        assertTrue(!json.contains("\"snooze\""))
        assertTrue(!json.contains("\"lead\""))
    }

    // ---------- 二、缺失与清空 ----------

    @Test
    fun `v5 文件没有这两段，解析结果是 null（不动现有数据）`() {
        val p = ok("""{"format":"timetable","version":5,$baseTerm}""")
        assertNull(p.alarms)
        assertNull(p.events)
    }

    @Test
    fun `写了空数组才算清空`() {
        val p = ok("""{"format":"timetable","version":6,$baseTerm,"alarms":[],"events":[]}""")
        assertTrue(p.alarms != null && p.alarms!!.isEmpty())
        assertTrue(p.events != null && p.events!!.isEmpty())
    }

    @Test
    fun `只有闹钟和日程的文件也算有内容`() {
        val json = """
            {"format":"timetable","version":6,
             "alarms":[{"id":"a001","hour":7,"minute":0,"days":[1,2,3,4,5],"source":"MANUAL"}]}
        """.trimIndent()
        val p = ok(json)
        assertEquals(1, p.alarms!!.size)
        assertNull(p.courses)
    }

    // ---------- 三、报错 ----------

    @Test
    fun `闹钟小时越界报错，并指出是第几条`() {
        val msg = failed(
            """{"format":"timetable","version":6,"term":{"name":"x","startDate":"2026-09-07","totalWeeks":19},
                "alarms":[{"hour":24,"minute":0,"days":[1]}]}""".trimIndent()
        )
        assertTrue(msg, msg.contains("alarms 第 1 条") && msg.contains("hour"))
    }

    @Test
    fun `日程结束不晚于开始时报错`() {
        val msg = failed(
            """{"format":"timetable","version":6,"term":{"name":"x","startDate":"2026-09-07","totalWeeks":19},
                "events":[{"date":"2026-10-07","start":600,"end":600,"title":"例会"}]}""".trimIndent()
        )
        assertTrue(msg, msg.contains("events 第 1 条"))
    }

    // ---------- 四、合并（导入「合并」模式） ----------

    @Test
    fun `闹钟合并按自然键去重，新增的重新编号`() {
        val existing = listOf(alarm(id = "a001", hour = 7, minute = 5, label = "起床"))
        val incoming = listOf(
            alarm(id = "a001", hour = 7, minute = 5, label = "起床"),        // 同一条（id 撞车也不算新的）
            alarm(id = "a009", hour = 8, minute = 0, days = setOf(2), label = "上课")
        )
        val r = Alarms.merge(existing, incoming)
        assertEquals(1, r.added)
        assertEquals(2, r.alarms.size)
        assertEquals("a002", r.alarms.last().id)
        assertEquals("上课", r.alarms.last().label)
    }

    @Test
    fun `日程合并按自然键去重`() {
        val existing = listOf(event(id = "e001"))
        val incoming = listOf(
            event(id = "e001"),
            event(id = "e005", date = "2026-10-08", title = "另一条")
        )
        val r = Events.merge(existing, incoming)
        assertEquals(1, r.added)
        assertEquals("e002", r.events.last().id)
    }
}
