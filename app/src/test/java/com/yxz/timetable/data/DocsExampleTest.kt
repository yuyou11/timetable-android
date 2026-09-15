package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住仓库里那两份示例文件：**它们必须永远能被 App 解析成功**。
 *
 * ============================================================
 *  为什么值得单独写一个测试
 * ============================================================
 *
 * `docs/example-full.json` 和 `docs/example-schedule.json` 是文档的一部分，
 * 用户会照着它们手改自己的课表。但它们**不在编译路径上** ——
 * 格式改了、忘了同步这两份文件，`gradle test` 照样全绿，
 * 直到某个用户复制了示例、导入失败、来问「为什么文档里的例子是错的」。
 *
 * 这类「文档和代码悄悄脱节」的问题，靠人记得去同步是防不住的。
 * 让测试**真的去读那两个文件**，脱节的那一刻就会红。
 *
 * 这是「把文档也纳入测试」的一个具体例子：
 * **凡是会被人照着做的事，就值得被自动化验证一遍。**
 */
class DocsExampleTest {

    /**
     * 单元测试的工作目录是模块目录（app/），所以示例文件在上一层的 docs/。
     *
     * 这里刻意**不做「找不到就跳过」**：如果路径变了，说明要么项目结构变了，
     * 要么测试运行方式变了 —— 两种都该被人知道，静默跳过只会让这个测试
     * 变成一条永远为真的装饰。
     */
    private fun doc(name: String): File {
        val candidates = listOf(
            File("../docs/$name"),      // Gradle 从 app/ 目录跑测试
            File("docs/$name"),         // 万一从仓库根跑
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "找不到示例文件 $name（试过：${candidates.joinToString { it.absolutePath }}）。\n" +
                        "如果项目结构调整了，请同步更新这个测试的路径。"
            )
    }

    private fun parseOk(name: String): ScheduleFormat.Parsed {
        val text = doc(name).readBytes().toString(Charsets.UTF_8)
        return when (val r = ScheduleFormat.parse(text, 19)) {
            is ScheduleFormat.Result.Ok -> r.parsed
            is ScheduleFormat.Result.Failed -> error("$name 解析失败：${r.message}")
        }
    }

    @Test
    fun `example-schedule 能被解析`() {
        val p = parseOk("example-schedule.json")
        assertEquals("示例课表应当有 19 门课", 19, p.courses?.size)
        // 纯课表示例不该带模板和日型策略
        assertEquals(null, p.templates)
        assertEquals(null, p.dayTypes)
    }

    @Test
    fun `example-full 能被解析`() {
        val p = parseOk("example-full.json")
        assertEquals("完整示例应当有 19 门课", 19, p.courses?.size)
        assertEquals("完整示例应当带六套模板", 6, p.templates?.size)
        assertTrue("完整示例应当带日型策略", p.dayTypes != null)
    }

    @Test
    fun `完整示例里的日型策略是默认那套`() {
        // 示例文件同时也是「默认值长什么样」的说明书，
        // 所以它写的那套应当和代码里的默认值一致 ——
        // 不然用户照抄示例得到的配置会和「什么都不写」不一样，很费解。
        val p = parseOk("example-full.json")
        assertEquals(DayTypePolicy.DEFAULT.enabled, p.dayTypes!!.enabled)
        assertEquals(DayTypePolicy.DEFAULT.fallback, p.dayTypes!!.fallback)
    }

    @Test
    fun `示例文件的版本号不比 App 支持的更新`() {
        // 版本号写高了，用户导入自己仓库里的示例反而会被拒 —— 很荒唐。
        for (name in listOf("example-schedule.json", "example-full.json")) {
            val raw = doc(name).readBytes().toString(Charsets.UTF_8)
            val v = Regex(""""version"\s*:\s*(\d+)""").find(raw)?.groupValues?.get(1)?.toInt()
            assertTrue("$name 里找不到 version 字段", v != null)
            assertTrue(
                "$name 的 version=$v 超过了 App 支持的 ${ScheduleFormat.VERSION}",
                v!! <= ScheduleFormat.VERSION
            )
        }
    }
}
