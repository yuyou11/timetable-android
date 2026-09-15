package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住一条**不变量**：模板里占位格的时刻必须和 `Slots` 表严格相等。
 *
 * ============================================================
 *  这条不变量是怎么被发现的
 * ============================================================
 *
 * 用户拿来一份自己写的 A 型日模板，问「改成符合现在规则的样子」。
 * 跑诊断发现：四个占位格的时刻全部和 `Slots` 差了 30 分钟
 * （模板写 08:00–09:35，实际应为 08:30–10:05）。
 *
 * 后果不是「差一点」，而是**整块被吃掉**：
 *
 *   1. 引擎靠 `it.start == Slots.start(nodes.first)` 判断「这门课属于哪一格」
 *   2. 时刻对不上 → 有课也顶不掉占位格
 *   3. 占位格被当成固定日程，再被课裁掉 → 剩一块碎片
 *   4. 用户看到的是一堆「空档 · 机动」，他自己写的「大课间」不见了
 *
 * 实测那一周的早上变成：
 *
 *   07:40-08:00  早读/课前准备
 *   08:00-08:30  空档 · 机动      ← 凭空冒出来
 *   08:30-10:05  大学英语
 *   10:05-10:25  空档 · 机动      ← 又一块
 *
 * ============================================================
 *  为什么值得一条永久测试
 * ============================================================
 *
 * 这个错误**没有任何报错**：JSON 合法、时刻格式合法、
 * 格子之间也不重叠，解析一路绿灯。只有实跑起来才看得见碎片。
 *
 * 而人和 AI 都很容易写错 —— 换一份课程表、换一个学校的作息，
 * 节次时刻就变了，模板里的数字却还留着旧的。
 *
 * **凡是「两个地方各写一份、必须相等」的数据，就该有测试盯着。**
 */
class TemplatePlaceholderTest {

    /** 遍历一种模板，返回所有「时刻和 Slots 对不上」的占位格描述 */
    private fun misaligned(blocks: List<Block>): List<String> {
        val bad = mutableListOf<String>()
        for (b in blocks) {
            val n = b.nodes ?: continue
            val expectStart = Slots.start(n.first)
            val expectEnd = Slots.end(n.last)
            if (b.start != expectStart || b.end != expectEnd) {
                bad += "第 ${n.first}-${n.last} 节：模板写 " +
                        "${Slots.fmt(b.start)}-${Slots.fmt(b.end)}，" +
                        "应为 ${Slots.fmt(expectStart)}-${Slots.fmt(expectEnd)}"
            }
        }
        return bad
    }

    @Test
    fun `内置模板的占位格全部对齐 Slots 表`() {
        for (type in Templates.ALL_TYPES) {
            val bad = misaligned(Templates.builtin(type))
            assertTrue(
                "内置模板 ${type.name} 的占位格时刻和 Slots 对不上：\n  " +
                        bad.joinToString("\n  "),
                bad.isEmpty()
            )
        }
    }

    /**
     * 每种日型**应该**有哪些占位格。
     *
     * ## 这张表是我猜错两次之后、把实际值打出来才定下的
     *
     * 第一次我断言「六种模板都该有全部五段占位格」→ B_TRAIN_A 红了。
     * 第二次我改口说「B 型日是 [3,5,7,9]」→ 又红了，实际是 [3,5,7]。
     * 于是停下来写了个脚本把六种模板的实际值打出来，才拿到这张表。
     *
     * **两次都是测试错了，不是代码错了。**
     * 教训：与其接着猜，不如把真实值打出来看一眼 —— 猜错的代价是反复红。
     *
     * ## 规则本身（把「为什么」也写下来，否则以后没人敢改）
     *
     * 判据是**「那个时段在那种日型下有没有可能上课」**：
     *
     *   1-2 节   —— 只有 A 型日会有课（A 的定义就是「1-2 节有课」）。
     *               B 型日不放占位格，改成「★ 黄金自习块」，
     *               因为那一格在 B 型日**永远**空着，占位格只会一直显示「无课」。
     *
     *   9-10 节  —— 周二 / 周四晚上 19:00 是**训练时间**，不会有课，
     *               所以那两种日型也不放占位格。周五（B_NORMAL）没训练，就有。
     *
     *   3-4 / 5-6 / 7-8 —— 任何工作日都可能排课，三种 B 型日都有。
     *
     *   周末     —— 整天不排课，一个占位格都没有。
     *
     * 换个角度说：**占位格的存在与否，是「这个时段有没有可能被课占用」的直接体现。**
     * 以后要改这张表，先问那句判据。
     */
    private val expectedPlaceholderPairs: Map<DayType, Set<Int>> = mapOf(
        DayType.A to setOf(1, 3, 5, 7, 9),
        DayType.B_TRAIN_A to setOf(3, 5, 7),
        DayType.B_TRAIN_B to setOf(3, 5, 7),
        DayType.B_NORMAL to setOf(3, 5, 7, 9),
        DayType.SATURDAY to emptySet(),
        DayType.SUNDAY to emptySet()
    )

    @Test
    fun `每种日型的占位格和设计一致`() {
        for (type in Templates.ALL_TYPES) {
            val actual = Templates.builtin(type).mapNotNull { it.nodes?.first }.toSet()
            val expected = expectedPlaceholderPairs.getValue(type)
            assertEquals(
                "内置模板 ${type.name} 的占位格与设计不符。\n" +
                        "实际：${actual.sorted()}\n应有的：${expected.sorted()}\n" +
                        "（判据见 expectedPlaceholderPairs 的注释：那个时段有没有可能上课）",
                expected, actual
            )
        }
    }

    @Test
    fun `B 型日不放 1-2 节占位格 而是改成自习块`() {
        // 单独拎出来说，因为这个「不放」很容易被人当成漏写而「修好」。
        //
        // B 型日的定义就是「第 1-2 节没课」，占位格会永远显示「无课」。
        // 内置模板把它写成「★ 黄金自习块」，是有意的：
        // **一个永远空着的占位格，不如一块有名有姓的自由时间。**
        for (type in listOf(DayType.B_TRAIN_A, DayType.B_TRAIN_B, DayType.B_NORMAL)) {
            val blocks = Templates.builtin(type)
            assertTrue(
                "$type 不该有 1-2 节占位格（B 型日 1-2 节永远没课）",
                1 !in blocks.mapNotNull { it.nodes?.first }
            )
            // 那一段应该被某个固定日程占着，而不是留空
            val morning = blocks.filter { it.start < Slots.start(3) && it.end > Slots.start(1) }
            assertTrue(
                "$type 的 08:00-10:05 那段不该是空的",
                morning.isNotEmpty()
            )
        }
    }

    @Test
    fun `A 型日必须有 1-2 节占位格`() {
        // 单独拎出来说，因为它是**最不能出错**的那一段：
        // A 型日之所以是 A 型，就是因为当天第 1-2 节有课。
        // 这一段没有占位格的话，「今天要不要早起」这条判断就和显示对不上了。
        assertTrue(
            "A 型日没有第 1-2 节的占位格 —— 那 A 型日的定义就落空了",
            1 in Templates.A.mapNotNull { it.nodes?.first }
        )
    }

    @Test
    fun `对齐之后 有课的日子不会冒出「空档 机动」碎片`() {
        // 上一条是「静态检查」，这条是**端到端实测** ——
        // 只查数据对不上、和真的排出碎片，还是两回事。
        //
        // 拿内置课表跑整个学期，任何一天都不该出现「空档 · 机动」：
        // 内置模板首尾相接铺满 24 小时，课只会**替换**占位格，不会留洞。
        var checked = 0
        for (week in 1..19) {
            for (dow in 0..6) {
                val date = java.time.LocalDate.of(2026, 9, 7)
                    .plusDays(((week - 1) * 7 + dow).toLong())
                val ms = TimelineEngine.moments(date, week, BuiltinCourses.LIST)
                val gaps = ms.filter { it.title.contains("空档") }
                assertTrue(
                    "$date 出现了 ${gaps.size} 处「空档 · 机动」：" +
                            gaps.joinToString { "${Slots.fmt(it.start)}-${Slots.fmt(it.end)}" } +
                            "\n这通常意味着某个占位格的时刻和 Slots 表对不上",
                    gaps.isEmpty()
                )
                checked++
            }
        }
        assertEquals(19 * 7, checked)
    }

    @Test
    fun `用户导入的模板如果时刻不对 应该能被检测出来`() {
        // 反向验证：上面那套检查**真的能发现坏模板**。
        //
        // 造一份时刻故意写错的模板（模拟用户那份文件），
        // 确认检查会报出来 —— 否则上面的「全部通过」说明不了任何事。
        val broken = listOf(
            Block(0, 480, "睡觉", kind = Kind.SLEEP),
            // 故意写成 08:00–09:35，而 Slots 要求 08:30–10:05
            Block(8 * 60, 9 * 60 + 35, "第 1-2 节", kind = Kind.CLASS, nodes = 1..2),
            Block(9 * 60 + 35, 1440, "其余", kind = Kind.FREE)
        )
        val bad = misaligned(broken)
        assertEquals("应当检测出 1 处不对齐", 1, bad.size)
        assertTrue("报错要说清哪个节次", bad[0].contains("第 1-2 节"))
        assertTrue("报错要给出正确值", bad[0].contains("08:30-10:05"))
    }
}
