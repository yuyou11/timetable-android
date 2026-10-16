package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住 `Store` 里那份**本地 JSON**：它必须覆盖 [Course] / [Alarm] 的每一个字段。
 *
 * ============================================================
 *  为什么值得单独写一个测试
 * ============================================================
 *
 * 这个项目里「课程」有两套序列化实现：
 *
 *   1. [ScheduleFormat] —— 对外的文件格式（导入导出，有文档、有示例文件、有测试）
 *   2. [Store] 里的 toJson/fromJson —— App 自己的本地存储
 *
 * v1.12.0 给 `Course` 加 `teacher` 时，我只改了第 1 套。
 * 后果是：导入的文件里老师读得进来，但 App 一存一读就**悄悄变回空串**——
 * 界面上表现为「刚导入时看得到老师，下次打开就没了」，
 * 而所有单测都是绿的（它们只测第 1 套）。
 *
 * 这类「同一个数据、两个实现、只改了一个」的坑，靠人记得是防不住的，
 * 所以这里直接读 `Store.kt` 的源码来数键。
 *
 * ============================================================
 *  ⚠️ 写这个文件的踩坑记录（改之前先看）
 * ============================================================
 *
 * 第一版用 `substringAfter` / `substringBefore` 切函数体，还写了正则来抓键，
 * 结果连续两轮都是「一个键都没解析出来」——看起来像 Store 漏了字段，
 * 其实全是**测试自己的字符串转义**在作怪（源码里落成了字面反斜杠加 n）。
 *
 * 所以这一版刻意做到**不出现任何转义字符**：
 *   · 函数体按**行**取（[bodyBetween]），换行符由 `lines()` 消化，顺带兼容 CRLF/LF
 *   · 键用手工扫描（[keysAfter]），引号用字符字面量 `'"'`，正则整个不用
 * 教训：**测试里凡是需要转义的字符串，都是脆的**。
 */
class StoreCourseJsonTest {

    private val quote = '"'

    private fun storeSource(): String {
        val candidates = listOf(
            File("src/main/java/com/yxz/timetable/data/Store.kt"),          // Gradle 从 app/ 跑
            File("app/src/main/java/com/yxz/timetable/data/Store.kt"),      // 万一从仓库根跑
        )
        val raw = candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 Store.kt（试过：${candidates.joinToString { it.absolutePath }}）")
        return raw
    }

    /**
     * 取 [startMarker] 那一行之后、到 [endMarker] 那一行之前的内容。
     *
     * ⚠️ 结束标记必须**从起点之后**开始找。第一版在全文件里 `indexOfFirst`，
     * 结果找到的是文件靠前的另一个 `private fun`（在起点之前），
     * 于是切出来是空的 —— 而症状又是「一个键都没有」，和转义那次长得一模一样。
     */
    private fun bodyBetween(src: String, startMarker: String, endMarker: String): String {
        val lines = src.lines()
        val a = lines.indexOfFirst { it.contains(startMarker) }
        if (a < 0) return ""
        val rest = lines.drop(a + 1)
        val end = rest.indexOfFirst { it.contains(endMarker) }
        return (if (end < 0) rest else rest.take(end)).joinToString(" ")
    }

    /** 手工扫描：每一处 `call` + `"` 之后到下一个 `"` 之间的内容 */
    private fun keysAfter(body: String, call: String): List<String> {
        val out = mutableListOf<String>()
        val needle = call + quote
        var from = 0
        while (true) {
            val at = body.indexOf(needle, from)
            if (at < 0) break
            val start = at + needle.length
            val end = body.indexOf(quote, start)
            if (end < 0) break
            out += body.substring(start, end)
            from = end + 1
        }
        return out
    }

    /** 手工扫描：这一整段里出现过的所有被引号包起来的字符串 */
    private fun quotedIn(body: String): Set<String> {
        val out = mutableSetOf<String>()
        var i = 0
        while (i < body.length) {
            if (body[i] == quote) {
                val end = body.indexOf(quote, i + 1)
                if (end < 0) break
                out += body.substring(i + 1, end)
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    // ----------------------------------------------------------------

    @Test
    fun `Course 的每个字段都写进了本地 JSON`() {
        val src = storeSource()
        val body = bodyBetween(src, "private fun toJson(c: Course)", "private fun fromJson")
        assertTrue("没取到 toJson 的函数体 —— Store.kt 的结构变了？", body.isNotEmpty())

        val fields = Course::class.java.declaredFields.map { it.name }
        val keys = keysAfter(body, "put(")
        assertEquals(
            "Course 有 ${fields.size} 个字段，而 Store.toJson 写了 ${keys.size} 个键。\n" +
                    "字段：$fields\n键：  $keys\n" +
                    "（加了字段却忘了同步本地存储的话，值会在「存一次再读」之后变回默认值）",
            fields.size, keys.size
        )
    }

    @Test
    fun `写出去的键在读回来时都被用到了`() {
        // 只写不读 = 那个字段存进去就再也读不出来（读的时候悄悄用默认值）
        val src = storeSource()
        val wrote = keysAfter(
            bodyBetween(src, "private fun toJson(c: Course)", "private fun fromJson"), "put("
        ).toSet()
        val read = quotedIn(
            bodyBetween(src, "private fun fromJson(o: JSONObject): Course", "private fun")
        )
        assertEquals("这些键只写没读：${wrote - read}", emptySet<String>(), wrote - read)
    }

    @Test
    fun `闹钟的本地 JSON 也覆盖了 Alarm 的每个字段`() {
        val src = storeSource()
        val body = bodyBetween(src, "private fun toAlarmJson(a: Alarm)", "private fun fromAlarmJson")
        assertTrue("没取到 toAlarmJson 的函数体", body.isNotEmpty())

        val fields = Alarm::class.java.declaredFields.map { it.name }
        val keys = keysAfter(body, "put(")
        assertEquals(
            "Alarm 有 ${fields.size} 个字段，而 Store.toAlarmJson 写了 ${keys.size} 个键。\n" +
                    "字段：$fields\n键：  $keys",
            fields.size, keys.size
        )
    }

    @Test
    fun `闹钟写出去的键在读回来时都被用到了`() {
        val src = storeSource()
        val wrote = keysAfter(
            bodyBetween(src, "private fun toAlarmJson(a: Alarm)", "private fun fromAlarmJson"), "put("
        ).toSet()
        val read = quotedIn(
            bodyBetween(src, "private fun fromAlarmJson(o: JSONObject): Alarm", "fun exportJson")
        )
        assertEquals("这些键只写没读：${wrote - read}", emptySet<String>(), wrote - read)
        assertTrue("至少得存下 id", "id" in wrote)
    }
}
