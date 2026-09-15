package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 提示词的测试。
 *
 * 为什么提示词也要测？因为它其实**是代码** —— 只不过执行它的是 AI 而不是编译器。
 * 用户复制走这段文字，AI 照着它产出文件，文件再被解析器读。
 * 提示词里少写一个字段名，用户就会拿到一份导入失败的文件，
 * 而报错信息指向的是 JSON，不是提示词 —— 排查起来会很绕。
 *
 * 所以这里守两件事：
 *   1. 学期信息被正确填进去（填错了会导致整张课表错位）
 *   2. 格式的关键字段名一个都没漏（漏了 AI 就会瞎编字段名）
 */
class AiPromptTest {

    private val termName = "西电 2026 级集成电路 · 大一上"
    private val start = "2026-09-07"
    private val weeks = 19

    private val coursePrompt = AiPrompt.forCourses(termName, start, weeks)
    private val templatePrompt = AiPrompt.forTemplates(termName)

    // ============================================================
    //  一、学期信息必须被正确填进去
    // ============================================================

    @Test
    fun `课表提示词里带着真实的学期信息`() {
        assertTrue(coursePrompt, coursePrompt.contains("\"name\": \"$termName\""))
        assertTrue(coursePrompt, coursePrompt.contains("\"startDate\": \"$start\""))
        assertTrue(coursePrompt, coursePrompt.contains("\"totalWeeks\": $weeks"))
    }

    @Test
    fun `总周数变了提示词里的值也要跟着变`() {
        // 这条防的是「有人把总周数写死在提示词里」。
        // 写死的话，用户改成 16 周的学制后，AI 会按 19 周生成，
        // 于是出现「第 17-19 周」这种根本不存在的周次。
        val p = AiPrompt.forCourses(termName, start, 16)
        assertTrue(p, p.contains("\"totalWeeks\": 16"))
        assertFalse(p, p.contains("\"totalWeeks\": 19"))
    }

    @Test
    fun `提示词里没有未展开的模板占位符`() {
        // Kotlin 字符串模板写错（比如该用 ${x} 却写成 $x 被解析成别的变量）
        // 会留下痕迹。这个断言能抓住那类错误。
        for (p in listOf(coursePrompt, templatePrompt)) {
            assertFalse("提示词里有未展开的 \$：\n$p", p.contains("\${"))
        }
    }

    // ============================================================
    //  二、格式的关键字段名一个都不能漏
    // ============================================================

    @Test
    fun `课表提示词提到了全部必需字段`() {
        val required = listOf(
            "format", "version", "term", "courses",
            "name", "dayOfWeek", "nodes", "weeks", "place", "enabled",
            "startDate", "totalWeeks"
        )
        for (field in required) {
            assertTrue("提示词里没提到字段「$field」，AI 会瞎编一个", coursePrompt.contains(field))
        }
    }

    @Test
    fun `课表提示词给出了全部六种周次写法`() {
        val syntaxes = listOf("\"3\"", "\"2-4\"", "\"3-17/2\"", "\"2-16/2\"", "\"2-4,6-17\"", "\"*\"")
        for (s in syntaxes) {
            assertTrue("提示词里缺少周次写法 $s", coursePrompt.contains(s))
        }
    }

    @Test
    fun `课表提示词说明了周一等于 1`() {
        // 这是最容易出错的地方：JS 的 Date.getDay() 里周日是 0，
        // 而本格式里周日是 7。不写清楚 AI 很可能按 JS 的习惯生成。
        assertTrue(coursePrompt, coursePrompt.contains("周一=1"))
        assertTrue(coursePrompt, coursePrompt.contains("周日=7"))
    }

    @Test
    fun `课表提示词里有节次时间对照表`() {
        // AI 需要它来判断「早八」是第几节、「晚课」是第几节
        assertTrue(coursePrompt, coursePrompt.contains("08:30"))
        assertTrue(coursePrompt, coursePrompt.contains("19:50"))
    }

    @Test
    fun `模板提示词提到了全部六种日型`() {
        for (type in Templates.ALL_TYPES) {
            assertTrue("模板提示词里缺少日型 ${type.name}", templatePrompt.contains(type.name))
        }
    }

    @Test
    fun `模板提示词提到了全部八种 kind`() {
        for (kind in Kind.values()) {
            assertTrue("模板提示词里缺少 kind ${kind.name}", templatePrompt.contains(kind.name))
        }
    }

    @Test
    fun `模板提示词说明了重叠是禁止的`() {
        // 引擎遇到重叠会「先到先得」静默截断，用户写的格子就没了。
        // 所以这条规则必须在提示词里说清楚，让 AI 别生成重叠的模板。
        assertTrue(templatePrompt, templatePrompt.contains("重叠"))
    }

    // ============================================================
    //  三、输出约束必须在
    // ============================================================

    @Test
    fun `两份提示词都要求只输出 JSON`() {
        // 不写这条，AI 十有八九会加一句「好的，我来帮你转换」，
        // 用户直接把整段粘进文件，导入就报「不是合法的 JSON」
        for (p in listOf(coursePrompt, templatePrompt)) {
            assertTrue(p, p.contains("只输出 JSON"))
        }
    }

    @Test
    fun `两份提示词都禁止了代码块标记`() {
        // ```json 这三个反引号是导入失败的高频原因
        for (p in listOf(coursePrompt, templatePrompt)) {
            assertTrue(p, p.contains("```json"))
            assertTrue(p, p.contains("代码块"))
        }
    }

    // ============================================================
    //  四、示例 JSON 必须真的合法
    // ============================================================

    @Test
    fun `课表提示词里的示例能解析且和真实格式一致`() {
        // 把提示词里那段示例抠出来，用真的解析器跑一遍。
        // 如果哪天有人改了示例却改错了，这个测试会立刻发现 ——
        // 示例是给 AI 抄的，它错了 AI 就跟着错。
        val json = extractFirstJsonObject(coursePrompt)
        assertTrue("没在提示词里找到示例 JSON", json.isNotEmpty())

        when (val r = ScheduleFormat.parse(json, weeks)) {
            is ScheduleFormat.Result.Ok -> {
                val parsed = r.parsed
                assertTrue("示例里应该有课程", !parsed.courses.isNullOrEmpty())
                assertEquals(termName, parsed.term?.name)
                assertEquals(LocalDate.parse(start), parsed.term?.startDate)
                assertEquals(weeks, parsed.term?.totalWeeks)
                assertTrue("示例不该有冲突：${parsed.warnings}", parsed.warnings.isEmpty())
            }
            is ScheduleFormat.Result.Failed ->
                throw AssertionError("提示词里的示例 JSON 自己就解析不了：${r.message}")
        }
    }

    @Test
    fun `模板提示词里的示例能解析`() {
        val json = extractFirstJsonObject(templatePrompt)
        assertTrue("没在提示词里找到示例 JSON", json.isNotEmpty())

        when (val r = ScheduleFormat.parse(json, weeks)) {
            is ScheduleFormat.Result.Ok ->
                assertTrue("示例里应该有模板", r.parsed.templates != null)
            is ScheduleFormat.Result.Failed ->
                throw AssertionError("提示词里的示例 JSON 自己就解析不了：${r.message}")
        }
    }

    /**
     * 从提示词里抠出第一个完整的 JSON 对象。
     *
     * 用**大括号配对**而不是正则找 ```json ... ``` —— 因为提示词里恰好也提到了
     * ```json 这个字符串（在「不要用代码块包起来」那句里），正则会被它误导。
     */
    private fun extractFirstJsonObject(text: String): String {
        val begin = text.indexOf('{')
        if (begin < 0) return ""
        var depth = 0
        var inString = false
        var escaped = false
        for (i in begin until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> { /* 字符串里的括号不计入配对 */ }
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(begin, i + 1)
                }
            }
        }
        return ""
    }
}
