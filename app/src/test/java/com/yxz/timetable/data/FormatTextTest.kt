package com.yxz.timetable.data

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

    @Test
    fun `课表提示词里的版本号和代码一致`() {
        val p = AiPrompt.forCourses("测试学期", "2026-09-07", 19)
        assertTrue(
            "课表提示词里的 version 不是 $v —— AI 会照旧版本生成文件：\n" +
                    p.lines().firstOrNull { it.contains("version") }.orEmpty(),
            p.contains(""""version": $v""")
        )
    }

    @Test
    fun `作息模板提示词里的版本号和代码一致`() {
        val p = AiPrompt.forTemplates("测试学期")
        assertTrue(
            "作息提示词里的 version 不是 $v",
            p.contains(""""version": $v""")
        )
    }

    // ============================================================
    //  二、v3 的 dayTypes 必须在每一处都讲到
    // ============================================================

    @Test
    fun `格式说明讲了 dayTypes 段`() {
        val help = ScheduleFormat.formatHelp()
        assertTrue("格式说明里没提 dayTypes", help.contains("dayTypes"))
        assertTrue("格式说明里没解释 enabled", help.contains("enabled"))
        assertTrue("格式说明里没解释 fallback", help.contains("fallback"))
    }

    @Test
    fun `示例 JSON 带了 dayTypes 段且能被解析`() {
        val raw = ScheduleFormat.exampleJson()
        assertTrue("示例 JSON 里没有 dayTypes 段", raw.contains("\"dayTypes\""))

        val parsed = (ScheduleFormat.parse(raw, 19) as ScheduleFormat.Result.Ok).parsed
        assertTrue("示例里的 dayTypes 没被解析出来", parsed.dayTypes != null)
        assertTrue(
            "示例里的 enabled 是空的",
            parsed.dayTypes!!.enabled.isNotEmpty()
        )
    }

    @Test
    fun `作息模板提示词讲了 dayTypes 段`() {
        // 这是最要紧的一处：AI 拿到的提示词里不写 dayTypes，
        // 它就永远不会生成这一节，用户也就没法裁剪日型
        val p = AiPrompt.forTemplates("测试学期")
        assertTrue("作息提示词里没解释 enabled", p.contains("enabled"))
        assertTrue("作息提示词里没解释 fallback", p.contains("fallback"))
        assertTrue(
            "作息提示词应当劝 AI 别把用不到的日型也启用",
            p.contains("不要为了凑数")
        )

        // ⚠️ 上面这几条只证明「正文里提到过这些词」，
        // 证明不了「示例 JSON 里真的有这一段」——
        // 而 AI 是照着**示例**抄结构的。所以下面单独把示例抠出来验。
        assertTrue(
            "作息提示词的**示例**里没有 dayTypes 这个键（正文提过不算）",
            p.contains("\"dayTypes\": {")
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

        // 而且它必须真的带上 dayTypes —— 否则 AI 照着抄也不会写这一段
        val parsed = (r as ScheduleFormat.Result.Ok).parsed
        assertTrue("示例里没有解析出 dayTypes", parsed.dayTypes != null)
        assertTrue("示例里有模板", parsed.templates != null)
    }

    /** 把提示词里第一段 `{ ... }` 抠出来。提示词里只有一个 JSON 示例，所以够用 */
    private fun extractJsonBlock(prompt: String): String {
        val start = prompt.indexOf('{')
        val end = prompt.lastIndexOf('}')
        assertTrue("提示词里找不到 JSON 示例", start in 0..<end)
        return prompt.substring(start, end + 1)
    }

    @Test
    fun `报错修复提示词把 dayTypes 也列进了检查项`() {
        // 用户导入失败时会把这句发给 AI。如果检查清单里没有 dayTypes，
        // 一个写坏的 dayTypes 段会让 AI 反复改不出来
        val p = AiPrompt.forFix("（报错）", "（JSON）")
        assertTrue("修复提示词里没提 dayTypes", p.contains("dayTypes"))
        assertTrue(
            "修复提示词应当列出六个合法的日型名",
            p.contains("B_TRAIN_A") && p.contains("SUNDAY")
        )
    }

    // ============================================================
    //  三、六种日型名要写全 —— 任何一处都不能漏
    // ============================================================

    @Test
    fun `凡是列出日型名的地方 六个都要列全`() {
        // 漏写某一种的后果：用户不知道有这个东西，或者 AI 生成的键名被我们拒收。
        //
        // ⚠️ 这里**不能**直接用 `text.contains(name)`。
        //
        // 第一版就是那么写的，跑出来全绿 —— 但其中有一条是废的：
        // `contains("A")` 检查的是单个字母 A，而任何含 A 的词都会命中它，
        // 比如 "SATURDAY"、"B_TRAIN_A"。也就是说**哪怕日型 A 从清单里被删掉，
        // 这条断言照样通过**。
        //
        // 现在用「前后不能是字母或下划线」把它锚成整词：
        //   · "SATURDAY" 里的 A 前面是 S  → 不匹配，符合预期
        //   · "B_TRAIN_A" 里的 A 前面是 _ → 不匹配，符合预期
        //   · 清单里单独的 "A、"           → 匹配
        //
        // 教训重申：**写完断言要问一句「它在出问题的时候真的会红吗？」**
        fun containsDayType(text: String, name: String): Boolean =
            Regex("(?<![A-Z_])${Regex.escape(name)}(?![A-Z_])").containsMatchIn(text)

        val sources = mapOf(
            "格式说明" to ScheduleFormat.formatHelp(),
            "作息模板提示词" to AiPrompt.forTemplates("测试学期"),
            "报错修复提示词" to AiPrompt.forFix("（报错）", "（JSON）")
        )

        for ((label, text) in sources) {
            for (name in Templates.ALL_TYPES.map { it.name }) {
                assertTrue("$label 里漏了日型 $name", containsDayType(text, name))
            }
        }
    }

    @Test
    fun `整词匹配本身是有效的`() {
        // 上一条依赖的匹配规则，自己也得被验一次 ——
        // 否则「整词匹配写错了」会让上一条变成永远为真，而没人发现
        fun containsDayType(text: String, name: String): Boolean =
            Regex("(?<![A-Z_])${Regex.escape(name)}(?![A-Z_])").containsMatchIn(text)

        assertTrue("单独的 A 应当匹配", containsDayType("可用：A（有早八）", "A"))
        assertTrue("列表里的 A 应当匹配", containsDayType("A、B_NORMAL", "A"))
        assertTrue("B_TRAIN_A 应当能匹配它自己", containsDayType("A、B_TRAIN_A", "B_TRAIN_A"))

        assertTrue(
            "SATURDAY 里的字母 A 不该被当成日型 A",
            !containsDayType("SATURDAY", "A")
        )
        assertTrue(
            "B_TRAIN_A 末尾的 A 不该被当成日型 A",
            !containsDayType("B_TRAIN_A", "A")
        )
        assertTrue(
            "B_TRAIN_A 不该被 B_TRAIN_A 之外的东西误匹配",
            !containsDayType("B_TRAIN_B", "B_TRAIN_A")
        )
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
