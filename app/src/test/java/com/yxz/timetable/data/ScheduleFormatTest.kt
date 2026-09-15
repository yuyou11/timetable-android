package com.yxz.timetable.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 数据格式标准 v1 的测试。
 *
 * 这个文件比 TimelineEngineTest 更值得认真看，原因是：
 * **解析器的输入来自外部，而外部输入永远会以你没想到的方式出错。**
 *
 * 引擎的输入是我自己构造的，我能保证它合法；
 * 但导入的 JSON 是用户手写的、别的程序生成的、从网页上复制粘贴来的。
 * 所以「错误路径」的测试和「正确路径」一样重要 ——
 * 下面几乎一半的用例都在验证「坏输入会不会给出说得清楚的错误」。
 */
class ScheduleFormatTest {

    // ============================================================
    //  一、周次写法
    // ============================================================

    private fun weeks(s: String, total: Int = 19) = ScheduleFormat.Weeks.parse(s, total)

    @Test
    fun `单个周次`() {
        assertEquals(setOf(3), weeks("3"))
    }

    @Test
    fun `连续区间`() {
        assertEquals(setOf(2, 3, 4), weeks("2-4"))
    }

    @Test
    fun `步长写法表示单周`() {
        assertEquals(setOf(3, 5, 7, 9, 11, 13, 15, 17), weeks("3-17/2"))
    }

    @Test
    fun `步长写法表示双周`() {
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), weeks("2-16/2"))
    }

    @Test
    fun `分段可以混用`() {
        assertEquals(setOf(2, 3, 4) + setOf(6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17), weeks("2-4,6-17"))
    }

    @Test
    fun `星号表示全学期`() {
        assertEquals((1..19).toSet(), weeks("*"))
        assertEquals((1..16).toSet(), weeks("*", 16))
    }

    @Test
    fun `中文标点会被自动识别`() {
        // 用户手写时几乎一定会打出中文逗号和波浪号，这不该成为导入失败的理由
        val expected = setOf(2, 3, 4) + setOf(6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17)
        assertEquals(expected, weeks("2～4，6－17"))
        assertEquals(expected, weeks("2 - 4 , 6 - 17"))
    }

    @Test
    fun `周次超出总周数要报错并说明范围`() {
        val e = assertThrows(ScheduleFormat.FormatException::class.java) { weeks("1-20") }
        assertTrue(e.message!!, e.message!!.contains("1–19"))
    }

    @Test
    fun `区间起点大于终点要报错`() {
        val e = assertThrows(ScheduleFormat.FormatException::class.java) { weeks("5-2") }
        assertTrue(e.message!!, e.message!!.contains("起点比终点大"))
    }

    @Test
    fun `多余逗号要报错`() {
        assertThrows(ScheduleFormat.FormatException::class.java) { weeks("2,,4") }
        assertThrows(ScheduleFormat.FormatException::class.java) { weeks("2-4,") }
    }

    @Test
    fun `步长不是数字要报错`() {
        val e = assertThrows(ScheduleFormat.FormatException::class.java) { weeks("1-17/x") }
        assertTrue(e.message!!, e.message!!.contains("步长"))
    }

    @Test
    fun `空字符串要报错`() {
        assertThrows(ScheduleFormat.FormatException::class.java) { weeks("") }
        assertThrows(ScheduleFormat.FormatException::class.java) { weeks("   ") }
    }

    // ---------- 反向：集合 -> 字符串 ----------

    @Test
    fun `导出时优先用步长写法`() {
        // 「单周」比一长串数字好核对，所以能压就压
        assertEquals("3-17/2", ScheduleFormat.Weeks.format(setOf(3, 5, 7, 9, 11, 13, 15, 17)))
        assertEquals("5", ScheduleFormat.Weeks.format(setOf(5)))
        assertEquals("5-6", ScheduleFormat.Weeks.format(setOf(5, 6)))
        assertEquals("2-4,6-17", ScheduleFormat.Weeks.format(setOf(2, 3, 4) + (6..17).toSet()))
        assertEquals("2,5-12", ScheduleFormat.Weeks.format(setOf(2) + (5..12).toSet()))
    }

    @Test
    fun `周次格式化再解析应该回到原样`() {
        val samples = listOf(
            setOf(3),
            setOf(2, 3, 4),
            setOf(3, 5, 7, 9, 11, 13, 15, 17),
            setOf(2) + (5..12).toSet(),
            (1..19).toSet()
        )
        for (s in samples) {
            assertEquals("周次集合 $s 往返失败", s, weeks(ScheduleFormat.Weeks.format(s)))
        }
    }

    // ============================================================
    //  二、整份文档的解析
    // ============================================================

    private fun doc(courses: String, term: String? = null, version: Int = 1, format: String = "timetable") =
        """{"format":"$format","version":$version,""" +
                (if (term != null) """"term":$term,""" else "") +
                """"courses":[$courses]}"""

    private val oneCourse =
        """{"name":"大学英语(Ⅰ)","dayOfWeek":1,"nodes":[1,2],"weeks":"2-4","place":"EI-309"}"""

    private fun ok(json: String, total: Int = 19): ScheduleFormat.Parsed =
        when (val r = ScheduleFormat.parse(json, total)) {
            is ScheduleFormat.Result.Ok -> r.parsed
            is ScheduleFormat.Result.Failed -> throw AssertionError("期望解析成功，却失败了：${r.message}")
        }

    private fun fail(json: String, total: Int = 19): String =
        when (val r = ScheduleFormat.parse(json, total)) {
            is ScheduleFormat.Result.Ok -> throw AssertionError("期望解析失败，却成功了")
            is ScheduleFormat.Result.Failed -> r.message
        }

    /**
     * 取课程列表，并断言它确实存在。
     *
     * `Parsed.courses` 是可空的：null 表示「这份文件没打算动课程」。
     * 大多数测试关心的是「文件里带了课程」的那种情况，所以用这个辅助函数
     * 把「必须有课程」这个前提写在名字里，比到处撒 `!!` 清楚。
     */
    private fun coursesOf(json: String, total: Int = 19): List<Course> =
        ok(json, total).courses
            ?: throw AssertionError("期望文件里带课程，实际 courses 为 null")

    @Test
    fun `最简文档可以解析`() {
        val p = ok(doc(oneCourse))
        assertEquals(1, p.courses!!.size)
        val c = p.courses!![0]
        assertEquals("大学英语(Ⅰ)", c.name)
        assertEquals(1, c.dayOfWeek)
        assertEquals(1, c.startNode)
        assertEquals(2, c.endNode)
        assertEquals(setOf(2, 3, 4), c.weeks)
        assertEquals("EI-309", c.place)
        assertTrue(c.enabled)
        assertNull(p.term)
    }

    @Test
    fun `term 段是可选的`() {
        val p = ok(doc(oneCourse))
        assertNull(p.term)
    }

    @Test
    fun `term 段可以带上学期信息`() {
        val p = ok(
            doc(
                oneCourse,
                """{"name":"大一上","startDate":"2026-09-07","totalWeeks":19}"""
            )
        )
        assertEquals("大一上", p.term!!.name)
        assertEquals(LocalDate.of(2026, 9, 7), p.term!!.startDate)
        assertEquals(19, p.term!!.totalWeeks)
    }

    @Test
    fun `起始日不是周一要报错并且直接给出正确日期`() {
        // 报错的价值在于「告诉用户改成什么」，所以这里连正确的日期都算好了
        val msg = fail(
            doc(oneCourse, """{"startDate":"2026-09-09","totalWeeks":19}""")
        )
        assertTrue(msg, msg.contains("必须是周一"))
        assertTrue(msg, msg.contains("2026-09-07"))
    }

    @Test
    fun `日期格式不对要有明确提示`() {
        val msg = fail(doc(oneCourse, """{"startDate":"2026/09/07"}"""))
        assertTrue(msg, msg.contains("2026-09-07"))
    }

    @Test
    fun `dayOfWeek 可以写中文`() {
        val p = ok(doc("""{"name":"高数","dayOfWeek":"周三","nodes":[3,4],"weeks":"1-5"}"""))
        assertEquals(3, p.courses!![0].dayOfWeek)
    }

    @Test
    fun `周末也能排课`() {
        // 课表页画的是周一到周日七列，前提是数据层收得下周末的课。
        // 把 6 / 7 这个边界钉在这里，是为了防止将来有人「顺手」把校验收回 1–5 ——
        // 那样课表页会照常画出周六周日两列，但两列永远空着，还很不好查。
        val p = ok(
            doc(
                """{"name":"周六实验","dayOfWeek":6,"nodes":[3,4],"weeks":"1-5"},""" +
                        """{"name":"周日重修","dayOfWeek":7,"nodes":[7,8],"weeks":"1-5"}"""
            )
        )
        assertEquals(listOf(6, 7), p.courses!!.map { it.dayOfWeek })
    }

    @Test
    fun `周末也可以写中文`() {
        val sat = ok(doc("""{"name":"高数","dayOfWeek":"周六","nodes":[3,4],"weeks":"1-5"}"""))
        assertEquals(6, sat.courses!![0].dayOfWeek)

        val sun = ok(doc("""{"name":"高数","dayOfWeek":"周日","nodes":[3,4],"weeks":"1-5"}"""))
        assertEquals(7, sun.courses!![0].dayOfWeek)
    }

    @Test
    fun `weeks 也可以写成数组`() {
        val p = ok(doc("""{"name":"高数","dayOfWeek":3,"nodes":[3,4],"weeks":[2,3,4,6,7]}"""))
        assertEquals(setOf(2, 3, 4, 6, 7), p.courses!![0].weeks)
    }

    @Test
    fun `enabled 为 false 表示停课`() {
        val p = ok(doc("""{"name":"高数","dayOfWeek":3,"nodes":[3,4],"weeks":"1-5","enabled":false}"""))
        assertFalse(p.courses!![0].enabled)
    }

    // ---------- 坏输入 ----------

    @Test
    fun `不是本 App 的文件要拒绝`() {
        assertTrue(fail(doc(oneCourse, format = "something-else")).contains("timetable"))
    }

    @Test
    fun `版本号比 App 新要拒绝而不是硬解析`() {
        val msg = fail(doc(oneCourse, version = 99))
        assertTrue(msg, msg.contains("v99"))
        assertTrue(msg, msg.contains("更新 App"))
    }

    @Test
    fun `不是合法 JSON 要有友好提示`() {
        assertTrue(fail("{这不是 json").contains("不是合法的 JSON"))
    }

    @Test
    fun `三段全空的文件要拒绝`() {
        assertTrue(fail("""{"format":"timetable","version":1}""").contains("courses"))
    }

    @Test
    fun `报错要指明是第几门课`() {
        // 19 门课的文件里报一句「weeks 格式有误」，用户根本不知道去看哪一条，
        // 所以错误信息必须带上课程序号和课程名
        val broken = """{"name":"高数","dayOfWeek":3,"nodes":[3,4],"weeks":"5-2"}"""
        val msg = fail(doc(oneCourse + "," + broken))
        assertTrue(msg, msg.contains("第 2 门课"))
        assertTrue(msg, msg.contains("高数"))
        assertTrue(msg, msg.contains("起点比终点大"))
    }

    @Test
    fun `节次超出范围要报错`() {
        assertTrue(
            fail(doc("""{"name":"高数","dayOfWeek":3,"nodes":[3,11],"weeks":"1-5"}"""))
                .contains("1–10")
        )
    }

    @Test
    fun `节次前后颠倒要报错`() {
        assertTrue(
            fail(doc("""{"name":"高数","dayOfWeek":3,"nodes":[8,3],"weeks":"1-5"}"""))
                .contains("起始节次比结束节次大")
        )
    }

    @Test
    fun `星期超出范围要报错`() {
        assertTrue(
            fail(doc("""{"name":"高数","dayOfWeek":9,"nodes":[3,4],"weeks":"1-5"}"""))
                .contains("1–7")
        )
    }

    @Test
    fun `缺少课程名要报错`() {
        assertTrue(fail(doc("""{"dayOfWeek":3,"nodes":[3,4],"weeks":"1-5"}""")).contains("name"))
    }

    // ============================================================
    //  三、冲突检测
    // ============================================================

    @Test
    fun `同一时段周次重叠会给出警告`() {
        val p = ok(
            doc(
                """{"name":"A课","dayOfWeek":2,"nodes":[3,4],"weeks":"1-10"},""" +
                        """{"name":"B课","dayOfWeek":2,"nodes":[3,4],"weeks":"5-15"}"""
            )
        )
        assertEquals(1, p.warnings.size)
        assertTrue(p.warnings[0], p.warnings[0].contains("A课") && p.warnings[0].contains("B课"))
        assertTrue(p.warnings[0], p.warnings[0].contains("5"))
    }

    @Test
    fun `同一时段但周次不重叠不算冲突`() {
        // 这正是内置课表里的真实情况：周四 7-8 节，
        // 人工智能概论占单周，程序设计基础B 占第 2 周。
        // 如果只比时段不比周次，这里会误报 —— 那就成了「狼来了」，用户很快就不看警告了。
        val p = ok(
            doc(
                """{"name":"人工智能概论","dayOfWeek":4,"nodes":[7,8],"weeks":"3-17/2"},""" +
                        """{"name":"程序设计基础B","dayOfWeek":4,"nodes":[7,8],"weeks":"2"}"""
            )
        )
        assertTrue("不该有警告，实际：${p.warnings}", p.warnings.isEmpty())
    }

    @Test
    fun `不同时段的课不算冲突`() {
        val p = ok(
            doc(
                """{"name":"A课","dayOfWeek":2,"nodes":[3,4],"weeks":"1-10"},""" +
                        """{"name":"B课","dayOfWeek":2,"nodes":[5,6],"weeks":"1-10"}"""
            )
        )
        assertTrue(p.warnings.isEmpty())
    }

    // ============================================================
    //  四、往返：导出再导入必须一模一样
    //
    //  这条是整条标准最重要的一致性保证 ——
    //  用户「导出 → 改 → 导入」的整个工作流都建立在它上面。
    // ============================================================

    @Test
    fun `内置课表导出再导入完全一致`() {
        val json = ScheduleFormat.serialize("测试", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST)
        val back = coursesOf(json)

        assertEquals(BuiltinCourses.LIST.size, back.size)
        assertEquals(
            BuiltinCourses.LIST.map { signature(it) },
            back.map { signature(it) }
        )
    }

    @Test
    fun `内置课表没有任何时段冲突`() {
        val json = ScheduleFormat.serialize("测试", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST)
        val parsed = ok(json)
        assertTrue("内置数据不该有冲突：${parsed.warnings}", parsed.warnings.isEmpty())
    }

    @Test
    fun `App 内展示的示例 JSON 必须自己能解析`() {
        // 示例是给用户抄的，如果连它自己都解析不了，那就是在教错的东西
        val p = ok(ScheduleFormat.exampleJson())
        assertEquals(3, p.courses!!.size)
        assertEquals(LocalDate.of(2026, 9, 7), p.term!!.startDate)
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `导出的 JSON 能被第二次导出完全相同地重现`() {
        val a = ScheduleFormat.serialize("x", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST)
        val b = ScheduleFormat.serialize("x", LocalDate.of(2026, 9, 7), 19, coursesOf(a))
        assertEquals(a, b)
    }

    // ---------- 输出稳定性 ----------

    @Test
    fun `导出的字段顺序是固定的`() {
        // 这两条断言看着琐碎，但它们守住的是一个真实踩过的坑：
        // 原来用 JSONObject.toString(2)，Android 和标准 JDK 上的字段顺序不一样
        // （一个用 LinkedHashMap 一个用 HashMap），导致手机导出的文件
        // 和文档里的例子长得不同。手写序列化之后顺序才固定下来。
        val json = ScheduleFormat.serialize("x", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST)
        val lines = json.lines()

        assertEquals("""  "format": "timetable",""", lines[1])
        assertTrue(lines[2], lines[2].startsWith("""  "version""""))
        assertTrue(lines[3], lines[3].startsWith("""  "term""""))
        assertTrue(json.indexOf("\"courses\"") > json.indexOf("\"term\""))
    }

    @Test
    fun `nodes 写成一行而不是展开成四行`() {
        val json = ScheduleFormat.serialize(
            "x", LocalDate.of(2026, 9, 7), 19,
            listOf(Course("1", "测试课", 1, 3, 4, setOf(1), "X-101"))
        )
        assertTrue(json, json.contains("\"nodes\": [3, 4]"))
    }

    @Test
    fun `课程名里的引号和反斜杠会被正确转义`() {
        // 没有转义的话，课程名里一个引号就能生成一份坏掉的文件，
        // 而且症状是「导出看起来成功了，导入却报 JSON 错误」—— 非常难查。
        val nasty = """他说 "你好" 和 \反斜杠\"""
        val json = ScheduleFormat.serialize(
            "x", LocalDate.of(2026, 9, 7), 19,
            listOf(Course("1", nasty, 1, 1, 2, setOf(1), ""))
        )
        assertEquals(nasty, coursesOf(json)[0].name)
    }

    @Test
    fun `停课状态会被导出并且能读回来`() {
        val courses = BuiltinCourses.LIST.map {
            if (it.name == "军事理论") it.copy(enabled = false) else it
        }
        val json = ScheduleFormat.serialize("x", LocalDate.of(2026, 9, 7), 19, courses)
        val back = coursesOf(json)

        assertEquals(1, back.count { !it.enabled })
        assertEquals("军事理论", back.first { !it.enabled }.name)
    }

    /** 比较课程内容，忽略 id —— id 是 App 内部生成的，不属于标准的一部分 */
    private fun signature(c: Course) =
        listOf(c.name, c.dayOfWeek, c.startNode, c.endNode, c.weeks.sorted(), c.place, c.enabled)

    // ============================================================
    //  五、作息模板（v2 新增）
    // ============================================================

    private val oneTemplate = """
"templates": {
  "A": [
    { "start": "00:00", "end": "06:55", "title": "睡觉", "kind": "SLEEP" },
    { "start": "06:55", "end": "08:30", "title": "起床洗漱", "kind": "CHORE" },
    { "start": "08:30", "end": "10:05", "title": "第 1-2 节", "kind": "CLASS", "nodes": [1, 2] },
    { "start": "10:05", "end": "24:00", "title": "白天", "kind": "STUDY" }
  ]
}
""".trimIndent()

    /**
     * 注意 [templates] 参数本身已经**带了引号和键名**（形如 `"templates": {...}`），
     * 所以这里不能再套一层引号 —— 套了就变成 `""templates"`，生成一份坏 JSON。
     * （这个错误我自己踩过一次，11 个测试同时挂掉。）
     */
    private fun docWithTemplates(courses: String, templates: String) =
        """{"format":"timetable","version":2,$templates,"courses":[$courses]}"""

    @Test
    fun `v2 模型能解析出模板`() {
        val p = ok(docWithTemplates(oneCourse, oneTemplate))
        assertTrue(p.templates != null)
        val a = p.templates!![DayType.A]!!
        assertEquals(4, a.size)
        assertEquals(Kind.SLEEP, a[0].kind)
        assertEquals(6 * 60 + 55, a[1].start)
        assertEquals(1..2, a[2].nodes)
    }

    @Test
    fun `v1 的文件在 v2 里照常能用`() {
        // 这是「只增不改」原则的兑现：加模板段没有破坏旧文件
        val v1 = """{"format":"timetable","version":1,"courses":[$oneCourse]}"""
        val p = ok(v1)
        assertEquals(1, p.courses!!.size)
        assertNull("v1 文件不该带模板", p.templates)
    }

    @Test
    fun `模板只写了部分日型时其余回落到内置`() {
        val p = ok(docWithTemplates(oneCourse, oneTemplate))
        val set = TemplateSet(p.templates!!)

        assertEquals(6 * 60 + 55, TimelineEngine.wakeMinute(set.of(DayType.A)))
        // SATURDAY 没在文件里，应回落到内置的 09:00
        assertEquals(9 * 60, TimelineEngine.wakeMinute(set.of(DayType.SATURDAY)))
    }

    @Test
    fun `kind 省略时默认是 CHORE`() {
        val t = """"templates": { "A": [ { "start": "00:00", "end": "24:00", "title": "随便" } ] }"""
        val p = ok(docWithTemplates(oneCourse, t))
        assertEquals(Kind.CHORE, p.templates!![DayType.A]!![0].kind)
    }

    @Test
    fun `kind 大小写不敏感`() {
        val t = """"templates": { "A": [ { "start": "00:00", "end": "24:00", "title": "x", "kind": "sleep" } ] }"""
        val p = ok(docWithTemplates(oneCourse, t))
        assertEquals(Kind.SLEEP, p.templates!![DayType.A]!![0].kind)
    }

    // ---------- 模板的坏输入 ----------

    @Test
    fun `无法识别的日型要报错并列出可用值`() {
        val t = """"templates": { "MONDAY": [ { "start": "00:00", "end": "24:00", "title": "x" } ] }"""
        val msg = fail(docWithTemplates(oneCourse, t))
        assertTrue(msg, msg.contains("MONDAY"))
        assertTrue(msg, msg.contains("A"))
        assertTrue(msg, msg.contains("SATURDAY"))
    }

    @Test
    fun `模板时间重叠必须报错而不是静默截断`() {
        // 这是这个校验存在的全部理由：重叠时引擎会「先到先得」把后一格截掉，
        // 用户写的某一格就这么没了，界面上还看不出来。
        val t = """
"templates": { "A": [
  { "start": "00:00", "end": "08:00", "title": "睡觉" },
  { "start": "07:00", "end": "24:00", "title": "早起" }
] }
""".trimIndent()
        val msg = fail(docWithTemplates(oneCourse, t))
        assertTrue(msg, msg.contains("重叠"))
        assertTrue(msg, msg.contains("睡觉"))
        assertTrue(msg, msg.contains("早起"))
    }

    @Test
    fun `模板时段没铺满一整天只给提醒不报错`() {
        val t = """"templates": { "A": [ { "start": "08:00", "end": "12:00", "title": "上午" } ] }"""
        val p = ok(docWithTemplates(oneCourse, t))
        assertTrue(p.warnings.any { it.contains("空档") })
    }

    @Test
    fun `模板缺 title 要报错且指出是第几项`() {
        val t = """"templates": { "A": [ { "start": "00:00", "end": "24:00" } ] }"""
        val msg = fail(docWithTemplates(oneCourse, t))
        assertTrue(msg, msg.contains("第 1 项"))
        assertTrue(msg, msg.contains("title"))
    }

    @Test
    fun `模板时刻写法不对要报错`() {
        val t = """"templates": { "A": [ { "start": "8点", "end": "24:00", "title": "x" } ] }"""
        assertTrue(fail(docWithTemplates(oneCourse, t)).contains("start"))
    }

    @Test
    fun `模板的 end 可以写 24 时`() {
        val t = """"templates": { "A": [ { "start": "23:00", "end": "24:00", "title": "睡前" } ] }"""
        val p = ok(docWithTemplates(oneCourse, t))
        assertEquals(1440, p.templates!![DayType.A]!![0].end)
    }

    @Test
    fun `模板的 start 不能写 24 时`() {
        val t = """"templates": { "A": [ { "start": "24:00", "end": "24:00", "title": "x" } ] }"""
        assertTrue(fail(docWithTemplates(oneCourse, t)).contains("start"))
    }

    // ---------- 模板往返 ----------

    @Test
    fun `内置模板导出再导入完全一致`() {
        val templates = TemplateSet.BUILTIN.expanded()
        val json = ScheduleFormat.serialize(
            "测试", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST, templates
        )
        val back = ok(json).templates!!

        assertEquals(6, back.size)
        for (type in Templates.ALL_TYPES) {
            assertEquals(
                "$type 往返不一致",
                signature2(templates.getValue(type)),
                signature2(back.getValue(type))
            )
        }
    }

    @Test
    fun `导出的完整模板能被真正用于合成时间轴`() {
        // 端到端：导出 → 解析 → 建 TemplateSet → 算时间轴，结果要能和内置模板一致
        val json = ScheduleFormat.serialize(
            "x", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST,
            TemplateSet.BUILTIN.expanded()
        )
        val set = TemplateSet(ok(json).templates!!)

        val date = LocalDate.of(2026, 9, 21)
        val fromBuiltin = TimelineEngine.moments(date, 3, BuiltinCourses.LIST)
        val fromExported = TimelineEngine.moments(date, 3, BuiltinCourses.LIST, set)

        assertEquals(fromBuiltin.size, fromExported.size)
        fromBuiltin.zip(fromExported).forEach { (a, b) ->
            assertEquals(a.start, b.start)
            assertEquals(a.end, b.end)
            assertEquals(a.title, b.title)
            assertEquals(a.kind, b.kind)
        }
    }

    @Test
    fun `不带模板时导出的文件里没有 templates 段`() {
        val json = ScheduleFormat.serialize("x", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST)
        assertFalse(json.contains("templates"))
        assertNull(ok(json).templates)
    }

    @Test
    fun `模板的字段顺序也是固定的`() {
        val json = ScheduleFormat.serialize(
            "x", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST,
            mapOf(DayType.A to Templates.A)
        )
        // 挑一行带 kind 的（CHORE 会被省略，因为它是默认值）
        val line = json.lines().first { it.contains("\"kind\": \"SLEEP\"") }
        val iStart = line.indexOf("\"start\"")
        val iEnd = line.indexOf("\"end\"")
        val iTitle = line.indexOf("\"title\"")
        val iKind = line.indexOf("\"kind\"")
        assertTrue(line, iStart in 0 until iEnd)
        assertTrue(line, iEnd in 0 until iTitle)
        assertTrue(line, iTitle in 0 until iKind)
    }

    @Test
    fun `CHORE 会被省略因为它就是默认值`() {
        val json = ScheduleFormat.serialize(
            "x", LocalDate.of(2026, 9, 7), 19, BuiltinCourses.LIST,
            mapOf(DayType.A to Templates.A)
        )
        // 「起床、洗漱」是 CHORE，那一行不该出现 kind
        val line = json.lines().first { it.contains("起床、洗漱") }
        assertFalse(line, line.contains("kind"))
    }

    private fun signature2(blocks: List<Block>) =
        blocks.map { listOf(it.start, it.end, it.title, it.note, it.kind, it.nodes?.first, it.nodes?.last) }

    // ============================================================
    //  七、模板的存取往返
    //
    //  这一组是「测试抓到一个真 bug」的直接产物，而且那个 bug 已经发出去了。
    //
    //  存模板用 templatesToJson，读回来用 templatesFromJson。
    //  两个函数一开始共用同一段写出逻辑，那段逻辑会输出 `"templates": { ... }`
    //  —— 也就是**带键名的片段**。
    //
    //  问题是：`"templates": {...}` **不是合法的 JSON**（一个裸的键值对，
    //  没有外层大括号）。存进去之后 JSONObject 构造直接抛异常，
    //  然后被「解析失败就返回空表」的兜底吞掉。
    //
    //  后果：**用户改的模板静默消失**，一点报错都没有。
    //  导入时预览是对的（预览用的是内存里的对象），关掉界面再打开就打回原形。
    //
    //  这类「不报错的错」只有「存进去再读出来」这种往返测试才能抓住。
    // ============================================================

    @Test
    fun `模板存进本地存储再读回来必须一致`() {
        val original = mapOf(DayType.A to Templates.A)
        val text = ScheduleFormat.templatesToJson(original)

        // 第一步就要确认它是合法 JSON —— 这是 bug 的根源所在。
        // 少了这一句，后面的断言会以「读回来是空的」这种看不出原因的方式失败。
        assertTrue(
            "存进本地存储的必须是完整 JSON，不能是 \"templates\": {...} 这种片段",
            runCatching { JSONObject(text) }.isSuccess
        )

        val back = ScheduleFormat.templatesFromJson(text)
        assertEquals(1, back.size)
        assertEquals(signature2(Templates.A), signature2(back.getValue(DayType.A)))
    }

    @Test
    fun `六套模板全部存取往返一致`() {
        val original = TemplateSet.BUILTIN.expanded()
        val back = ScheduleFormat.templatesFromJson(ScheduleFormat.templatesToJson(original))

        assertEquals(6, back.size)
        for (type in Templates.ALL_TYPES) {
            assertEquals(
                "$type 存取往返不一致",
                signature2(original.getValue(type)),
                signature2(back.getValue(type))
            )
        }
    }

    @Test
    fun `改过的起床时间必须真的存下来`() {
        // 这条直接模拟用户操作：把 A 型日的起床时间从 06:55 改成 07:30，
        // 存进去再读出来，改的值必须还在
        val custom = listOf(
            Block(0, 7 * 60 + 30, "睡觉", "", Kind.SLEEP),
            Block(7 * 60 + 30, 1440, "自定义", "", Kind.STUDY)
        )
        val original = mapOf(DayType.A to custom)
        val back = ScheduleFormat.templatesFromJson(ScheduleFormat.templatesToJson(original))

        assertEquals(7 * 60 + 30, TimelineEngine.wakeMinute(back.getValue(DayType.A)))
    }

    @Test
    fun `存储损坏时返回空表而不是抛异常`() {
        // 存储坏了要让程序能起来 —— 用户至少还能进去改，
        // 大不了重改一遍模板，比起不了应用好得多
        val bad = listOf("", "   ", "不是 json", "\"templates\": {\"A\": []}", "null", "[]", "123")
        for (s in bad) {
            assertEquals("输入 ${s.take(20)} 应该安全返回空表", 0, ScheduleFormat.templatesFromJson(s).size)
        }
    }

    // ============================================================
    //  六、只改作息、不动课表
    //
    //  这一组测试是「写提示词时发现设计缺陷」的直接产物。
    //
    //  最初 courses 段是必需的、且不能为空。结果「我只想把起床时间从 06:55
    //  改成 07:15，课表别动」这个完全正当的需求根本走不通 ——
    //  用户必须把自己已有的课程又抄一遍塞进文件里。
    //
    //  现在规则改成：**courses 可以缺失或为空，前提是 templates 有内容。**
    //  此时 courses 解析为 null，含义是「不要动现有课程」。
    // ============================================================

    @Test
    fun `只带模板、不带课程的文件可以解析`() {
        val json = """{"format":"timetable","version":2,$oneTemplate,"courses":[]}"""
        val p = ok(json)

        assertNull("courses 为空时应该解析成 null，表示「不要动」", p.courses)
        assertTrue("模板应该被解析出来", p.templates != null)
        assertEquals(4, p.templates!![DayType.A]!!.size)
    }

    @Test
    fun `连 courses 字段都没有、只有模板也能解析`() {
        val json = """{"format":"timetable","version":2,$oneTemplate}"""
        val p = ok(json)
        assertNull(p.courses)
        assertTrue(p.templates != null)
    }

    @Test
    fun `课程为 null 和课程为空列表是两回事`() {
        // 带课程的文件 → courses 非 null
        val withCourses = ok(doc(oneCourse))
        assertTrue("有课程时不该是 null", withCourses.courses != null)
        assertEquals(1, withCourses.courses!!.size)

        // 不带课程的文件 → courses 为 null
        val templatesOnly = ok("""{"format":"timetable","version":2,$oneTemplate,"courses":[]}""")
        assertNull(templatesOnly.courses)
    }

    @Test
    fun `既没有课程也没有模板也没有学期要拒绝`() {
        // 一份什么都没说的文件，导入它没有任何意义，
        // 而且多半意味着用户选错了文件，或者 AI 输出的东西是坏的。
        // 明确报错比「导入成功但什么都没变」好得多。
        assertTrue(
            fail("""{"format":"timetable","version":2,"courses":[]}""").contains("没有可导入的东西")
        )
        assertTrue(
            fail("""{"format":"timetable","version":2}""").contains("没有可导入的东西")
        )
    }

    @Test
    fun `只有 term 没有课程没有模板可以解析为「只改学期」`() {
        // 场景：学期起始日调整了，只想更新这一项
        val json = """{"format":"timetable","version":2,"term":{"startDate":"2026-09-14","totalWeeks":18},"courses":[]}"""
        val p = ok(json)
        assertEquals(LocalDate.of(2026, 9, 14), p.term!!.startDate)
        assertEquals(18, p.term!!.totalWeeks)
        assertNull(p.courses)
        assertNull(p.templates)
    }
}
