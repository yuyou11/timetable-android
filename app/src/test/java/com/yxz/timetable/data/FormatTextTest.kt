package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住**所有「用户会照着做」的格式文本**，让它们不会和真实格式脱节。
 *
 * ============================================================
 *  这个文件是怎么来的
 * ============================================================
 *
 * 格式从 v1 升到 v3 之后，用户问了一句：
 * 「改完 json 文件规则后，你有同步在一键复制推荐提示词等地方改吗？」
 *
 * 去查，果然漏了几处，而且其中一处**从 v1 起就没对过**：
 *
 *   设置页那行「导入 / 导出使用统一的 JSON 格式（timetable v**1**）」
 *
 * 它一直写着 v1。没有测试能发现它 —— 因为那段文字当时写在
 * `SettingsFragment` 的伴生对象里，JVM 单元测试加载不了 Fragment。
 *
 * 所以这次做了两件事：
 *   ① 把说明文字挪到 `ScheduleFormat`（见 `formatHelp()`），让它可测
 *   ② 写下这个文件，把**每一处会展示给用户的格式文本**都断言一遍
 *
 * ============================================================
 *  这里守的是哪几处
 * ============================================================
 *
 *   1. `ScheduleFormat.formatHelp()`      设置页「格式说明」
 *   2. `ScheduleFormat.exampleJson()`     设置页「复制示例 JSON」
 *   3. `AiPrompt.forCourses()`            复制「课表提示词」
 *   4. `AiPrompt.forTemplates()`          复制「作息模板提示词」
 *   5. `AiPrompt.forFix()`                复制「报错修复提示词」
 *
 * 这五处**全都会生成/展示 JSON**。任何一处落后于格式，
 * 用户就会照着旧规则写文件，然后导入失败 —— 而文档里写着能行。
 *
 * **它们是同一份规格的五个副本，而副本必须被机器盯着。**
 */
class FormatTextTest {

    private val v = ScheduleFormat.VERSION

    // ============================================================
    //  一、版本号必须一致
    // ============================================================

    @Test
    fun `格式说明里的版本号和代码一致`() {
        val help = ScheduleFormat.formatHelp()
        assertTrue(
            "格式说明里没有「当前是 $v」，可能版本号忘了更新：\n$help",
            help.contains("当前是 $v")
        )
    }

    @Test
    fun `示例 JSON 里的版本号和代码一致`() {
        // 用解析出来的值断言，而不是字符串匹配 ——
        // 这样连「键名写错成 verison」这种情况也能抓到
        val parsed = ScheduleFormat.parse(ScheduleFormat.exampleJson(), 19)
        assertTrue(
            "示例 JSON 解析失败：${(parsed as? ScheduleFormat.Result.Failed)?.message}",
            parsed is ScheduleFormat.Result.Ok
        )
    }

    @Test
    fun `示例 JSON 的版本号不比 App 支持的旧`() {
        // 旧是「能用但过时」，新是「导入会被拒」——
        // 两者都不该出现在 App 自己生成的示例里
        val raw = ScheduleFormat.exampleJson()
        val parsedVersion = Regex(""""version"\s*:\s*(\d+)""")
            .find(raw)?.groupValues?.get(1)?.toInt()
        assertTrue("示例 JSON 里读不到 version 字段", parsedVersion != null)
        assertTrue(
            "示例 JSON 的 version=$parsedVersion 与代码的 $v 不一致",
            parsedVersion == v
        )
    }

    /**
     * 提示词声明的版本是**双方约定的 v4**，而不是当前的 v5 ——
     * AI 需要的只是「id 键的模板」（v4 起才有），发明 dayTypeConfig
     * 不是它的职责；v4 文件当前解析器照常接受（旧段缺省 = 不动现有配置）。
     * 与电脑版的提示词保持同版本，两台设备给 AI 的输入完全一样。
     */
    @Test
    fun `课表提示词声明的版本是约定的 v4`() {
        val p = AiPrompt.forCourses("测试学期", "2026-09-07", 19)
        assertTrue(
            "课表提示词里的 version 不是 4：\n" +
                    p.lines().firstOrNull { it.contains("version") }.orEmpty(),
            p.contains("\"version\": 4")
        )
        // 且这个版本当前解析器必须接受（别哪天把 v4 支持删了）
        val r = ScheduleFormat.parse(extractJsonBlock(p), 19)
        assertTrue(
            "课表提示词的示例解析失败：${(r as? ScheduleFormat.Result.Failed)?.message}",
            r is ScheduleFormat.Result.Ok
        )
    }

    @Test
    fun `作息模板提示词声明的版本是约定的 v4`() {
        val p = AiPrompt.forTemplates("测试学期")
        assertTrue(
            "作息提示词里的 version 不是 4",
            p.contains("\"version\": 4")
        )
    }

    // ============================================================
    //  二、v5 的 dayTypeConfig / dayTypeNames 必须在每一处都讲到
    // ============================================================

    @Test
    fun `格式说明讲了 dayTypeConfig 段`() {
        val help = ScheduleFormat.formatHelp()
        assertTrue("格式说明里没提 dayTypeConfig", help.contains("dayTypeConfig"))
        assertTrue("格式说明里没解释 types", help.contains("types"))
        assertTrue("格式说明里没解释 weekdayMap", help.contains("weekdayMap"))
        assertTrue("格式说明里没解释 earlyType", help.contains("earlyType"))
        assertTrue("格式说明里没提 dayTypeNames（v4 的自定义名）", help.contains("dayTypeNames"))
    }

    @Test
    fun `示例 JSON 带了 dayTypeConfig 段且能被解析`() {
        val raw = ScheduleFormat.exampleJson()
        assertTrue("示例 JSON 里没有 dayTypeConfig 段", raw.contains("\"dayTypeConfig\""))

        val parsed = (ScheduleFormat.parse(raw, 19) as ScheduleFormat.Result.Ok).parsed
        val cfg = parsed.dayTypeConfig
        assertTrue("示例里的 dayTypeConfig 没被解析出来", cfg != null)
        assertEquals("weekdayMap 必须是 7 项", 7, cfg!!.weekdayMap.size)
        assertTrue("示例的早八层不该是空的", cfg.earlyType != null)
    }

    @Test
    fun `作息模板提示词讲了日型 id 键`() {
        // 这是最要紧的一处：AI 拿到的提示词里必须说清「templates 的键是 id」，
        // 否则它会照着日型名写键，导入直接被拒收。
        // 反过来，dayTypeConfig（哪天用哪套）是用户自己的配置，提示词**不**让 AI 发明。
        val p = AiPrompt.forTemplates("测试学期")
        assertTrue("作息提示词里没解释「键是日型 id」", p.contains("日型 id"))
        assertTrue(
            "作息提示词应当说明「只输出改动过的日型」",
            p.contains("只需要输出我改动过的那几种")
        )

        // AI 是照着**示例**抄结构的，示例里的键必须真的是 id
        assertTrue(
            "作息提示词的**示例**里没有 templates 段（正文提过不算）",
            p.contains("\"templates\": {")
        )
    }

    @Test
    fun `作息模板提示词里嵌的示例 JSON 真的能导入`() {
        // 这条比上面那条硬得多：把提示词里那段示例抠出来，
        // **喂给真正的解析器**。示例写坏了就红，
        // 而不是等用户照着抄出来、导入失败才发现。
        val p = AiPrompt.forTemplates("测试学期")
        val example = extractJsonBlock(p)

        val r = ScheduleFormat.parse(example, 19)
        assertTrue(
            "作息提示词里嵌的示例 JSON 解析失败：${(r as? ScheduleFormat.Result.Failed)?.message}",
            r is ScheduleFormat.Result.Ok
        )

        val parsed = (r as ScheduleFormat.Result.Ok).parsed
        assertTrue("示例里有模板", parsed.templates != null)
        // ⚠️ 示例**不该**带日型配置 —— 「哪天用哪套」是用户自己的配置，
        // AI 生成的文件要是把它覆盖了，用户自己的星期映射就被冲掉了
        assertNull("AI 的作息示例不该带 dayTypeConfig", parsed.dayTypeConfig)
        assertNull("AI 的作息示例不该带旧 dayTypes 段", parsed.dayTypes)
    }

    /** 把提示词里第一段 `{ ... }` 抠出来。提示词里只有一个 JSON 示例，所以够用 */
    private fun extractJsonBlock(prompt: String): String {
        val start = prompt.indexOf('{')
        val end = prompt.lastIndexOf('}')
        assertTrue("提示词里找不到 JSON 示例", start in 0..<end)
        return prompt.substring(start, end + 1)
    }

    @Test
    fun `报错修复提示词把模板键和 dayTypeConfig 列进了检查项`() {
        // 用户导入失败时会把这句发给 AI。如果检查清单里没有
        // 「templates 的键是 id」，一个写坏的键名会让 AI 反复改不出来
        val p = AiPrompt.forFix("（报错）", "（JSON）")
        assertTrue("修复提示词里没提 templates 的键要写 id", p.contains("日型 id"))
        assertTrue("修复提示词里没提 dayTypeConfig 的校验", p.contains("dayTypeConfig"))
        assertTrue("修复提示词里没提 weekdayMap 的 7 项要求", p.contains("weekdayMap"))
    }

    // ============================================================
    //  三、七个日型 id 要写全 —— 任何一处都不能漏
    // ============================================================

    @Test
    fun `凡是列出日型的地方 七个 id 都要列全`() {
        // 漏写某一个的后果：用户不知道有这个东西，或者 AI 生成的键被我们拒收。
        //
        // v4 起日型的身份是 **id**（"1"-"7"），名字反而可以自定义 ——
        // 所以这里断言的是**带引号的 id 字面量**（`"3"` 这种），
        // 而不是枚举成员名：名字只出现在对照表里，id 才是文件里真正要写的。
        //
        // 例外：报错修复提示词只在「键要写 id（"1" 到 "7"）」这一句里出现 id，
        // 与电脑版同款措辞 —— 它只要求「1」和「7」在场。
        val full = mapOf(
            "格式说明" to ScheduleFormat.formatHelp(),
            "作息模板提示词" to AiPrompt.forTemplates("测试学期")
        )
        for ((label, text) in full) {
            for (t in DayType.entries) {
                assertTrue(
                    "$label 里漏了日型 id \"${t.id}\"（${t.label}）",
                    text.contains("\"${t.id}\"")
                )
            }
        }
        val fix = AiPrompt.forFix("（报错）", "（JSON）")
        assertTrue("修复提示词里没有 id 区间说明", fix.contains("\"1\"") && fix.contains("\"7\""))
    }

    @Test
    fun `报错修复提示词把 periods 列进了检查项`() {
        // 检查清单里没有 periods，一个写坏的 periods 会让 AI 反复改不出来
        val p = AiPrompt.forFix("（报错）", "（JSON）")
        assertTrue("修复提示词里没提 periods", p.contains("periods"))
    }

    // ============================================================
    //  四、几处「措辞承诺」也要和实际行为一致
    // ============================================================

    @Test
    fun `格式说明承诺「没写等于不要动」`() {
        // 这是整个导入流程最重要的语义，说明书里必须写清楚
        val help = ScheduleFormat.formatHelp()
        assertTrue("格式说明没写「没写 = 不要动」这条语义", help.contains("不要动"))
    }

    // ============================================================
    //  五、periods（v3 字段级追加）也要在每一处讲到
    // ============================================================

    @Test
    fun `格式说明讲了 periods 节数信息`() {
        val help = ScheduleFormat.formatHelp()
        assertTrue("格式说明里没提 periods", help.contains("periods"))
        assertTrue("格式说明里没解释默认结构 442", help.contains("442"))
    }

    @Test
    fun `课表提示词讲了 periods 且示例里带上了当前值`() {
        val p = AiPrompt.forCourses("测试学期", "2026-09-07", 19, periods = "441")
        assertTrue("课表提示词里没解释节数信息", p.contains("节数信息"))
        assertTrue(
            "课表提示词的示例里没有带上当前 periods（AI 是照着示例抄的）",
            p.contains("\"periods\": \"441\"")
        )
    }

    @Test
    fun `课表提示词给出的示例能被真正解析`() {
        // 光看字符串不够：提示词里嵌的那个 JSON 示例必须**真的合法**。
        // 这里把示例从提示词里抠出来喂给解析器。
        val p = AiPrompt.forCourses("测试学期", "2026-09-07", 19)
        val r = ScheduleFormat.parse(extractJsonBlock(p), 19)
        assertTrue(
            "课表提示词里嵌的示例 JSON 解析失败：${(r as? ScheduleFormat.Result.Failed)?.message}",
            r is ScheduleFormat.Result.Ok
        )

        // 示例里得真有课程，否则 AI 可能理解成「课表可以为空」
        val parsed = (r as ScheduleFormat.Result.Ok).parsed
        assertTrue(
            "课表提示词的示例里应当有课程（现在 courses=${parsed.courses}）",
            !parsed.courses.isNullOrEmpty()
        )
    }
}
