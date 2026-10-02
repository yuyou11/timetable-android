package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日型配置的测试 —— 「哪些日型存在、星期几用哪种、早八优先层」（v5 模型）。
 *
 * ## 模型变了，测试意图没变
 *
 * v4 及更早是 [DayTypePolicy]（启用集合 + 回落），v5 换成 [DayTypeConfig]
 * （types / weekdayMap / earlyType）：日型可删除、星期可映射、早八优先层可指定。
 * 这个文件的用例整体重写，但守的还是同两件事：
 *
 *   ① **配置不能把「有早八 / 没早八」这档区分弄丢** ——
 *      那是这个 App 存在的理由（它回答的就是「今天要不要早起」）
 *   ② 配置要真的穿透到 [TimelineEngine.moments]，而不只是改个标签
 *
 * ## 为什么两边各有一份测试
 *
 * 引擎测试（TimelineEngineTest）测时间轴本身（用恒等配置当基线），
 * 这里测配置与迁移。分开是因为**配置错了和引擎错了表现完全不同**：
 * 引擎错是时间轴不对，配置错是「用错了哪套模板」——
 * 后者更隐蔽（时间轴看起来完全正常，只是比该起的时间早/晚了半小时）。
 *
 * 用例与电脑版 tests/test_day_type_policy.py 一一对应。
 */
class DayTypePolicyTest {

    private val courses = BuiltinCourses.LIST
    private val termStart = LocalDate.of(2026, 9, 7)   // 教学第 1 周的周一

    private fun d(day: Int) = LocalDate.of(2026, 9, day)
    private fun weekOf(date: LocalDate) = TimelineEngine.weekOf(date, termStart)

    // 第 3 周：周一 9/21、周二 9/22、周四 9/24、周五 9/25、周六 9/26、周日 9/27
    private val mon = d(21)
    private val tue = d(22)
    private val thu = d(24)
    private val sat = d(26)
    private val sun = d(27)

    /** 第 5 周周五（10/9）：有课但没早八 —— 星期映射层的夹具 */
    private val friWeek5 = LocalDate.of(2026, 10, 9)

    /** 第 3 周周五：内置课表全天无课 → 休息日 */
    private val restFri = d(25)

    /**
     * 「恒等配置」——星期映射到旧自然槽位（周二→训练A、周四→训练B…）、
     * 早八优先层 = A。它让 [TimelineEngine.resolveDayType] 的行为与 v4 的
     * 自然规则 + [DayTypePolicy.ALL] **逐日一致**：
     * 测「引擎原始行为」（训练日模板还在、周二晚上真有训练）用它。
     */
    private val identity = configFromPolicy(DayTypePolicy.ALL)

    private fun typeOf(date: LocalDate, config: DayTypeConfig) =
        TimelineEngine.dayType(date, weekOf(date), courses, config)

    private fun wakeOf(date: LocalDate, config: DayTypeConfig): Int? {
        val set = TemplateSet(config = config)
        return TimelineEngine.wakeMinute(
            TimelineEngine.template(typeOf(date, config), set)
        )
    }

    /** 改映射用的便捷构造：默认配置基础上改派星期几 */
    private fun makeConfig(overrides: Map<Int, DayType>, early: DayType? = DayType.A): DayTypeConfig {
        val m = DayTypeConfig.DEFAULT.weekdayMap.toMutableList()
        overrides.forEach { (dow, t) -> m[dow - 1] = t }
        return DayTypeConfig(DayTypeConfig.DEFAULT.types, m, early)
    }

    // ============================================================
    //  一、引擎判定：默认配置与恒等配置
    // ============================================================

    @Test
    fun `有早八的工作日走早八优先层`() {
        // 第 3 周周一有英语（1-2 节）→ 走早八层 → A 型
        assertEquals(DayType.A, typeOf(mon, DayTypeConfig.DEFAULT))
        assertTrue(TimelineEngine.hasEarlyClass(mon, 3, courses))
    }

    @Test
    fun `没早八的工作日按星期映射走`() {
        assertEquals(DayType.B_NORMAL, typeOf(friWeek5, DayTypeConfig.DEFAULT))
    }

    @Test
    fun `工作日全天没课算休息日`() {
        // 第 1 周还没开课的周一 → 全天无课 → 休息日，
        // 不是按星期映射把人按 07:25 的节奏排满
        assertEquals(
            DayType.REST,
            typeOf(LocalDate.of(2026, 9, 7), DayTypeConfig.DEFAULT)
        )
        assertEquals(DayType.REST, typeOf(restFri, DayTypeConfig.DEFAULT))
    }

    @Test
    fun `周末跟星期映射走即使没课`() {
        // 周末不判休息日（周末作息本来就不由课表驱动）
        assertEquals(DayType.SATURDAY, typeOf(sat, DayTypeConfig.DEFAULT))
        assertEquals(DayType.SUNDAY, typeOf(sun, DayTypeConfig.DEFAULT))
    }

    @Test
    fun `早八层可以关掉`() {
        // earlyType = null = 不区分早八：有早八的日子也按星期映射走
        val noEarly = DayTypeConfig(
            DayTypeConfig.DEFAULT.types, DayTypeConfig.DEFAULT.weekdayMap, null
        )
        assertEquals(
            "取消早八区分后，周一（有早八）该按映射走 B 型",
            DayType.B_NORMAL, typeOf(mon, noEarly)
        )
    }

    // ============================================================
    //  二、迁移的金测试：恒等配置必须与 v4 逐日一致
    // ============================================================

    @Test
    fun `恒等配置复刻 v4 自然规则对照表`() {
        val golden = listOf(
            Triple(mon, 3, DayType.A),          // 周一有早八
            Triple(tue, 3, DayType.B_TRAIN_A),  // 周二晚训练（无早八）
            Triple(thu, 3, DayType.B_TRAIN_B),  // 周四力量B
            Triple(friWeek5, 5, DayType.B_NORMAL),   // 周五有课无早八
            Triple(sat, 3, DayType.SATURDAY),
            Triple(sun, 3, DayType.SUNDAY),
            Triple(LocalDate.of(2026, 9, 7), 1, DayType.REST),   // 开学前周一无课
            Triple(LocalDate.of(2026, 10, 28), 8, DayType.REST)  // 第 8 周周三无课
        )
        for ((date, week, want) in golden) {
            assertEquals(
                "$date 与 v4 自然规则不一致",
                want, TimelineEngine.dayType(date, week, courses, identity)
            )
        }
    }

    @Test
    fun `恒等映射就是旧自然槽位`() {
        assertEquals(
            listOf(
                DayType.B_NORMAL, DayType.B_TRAIN_A, DayType.B_NORMAL,
                DayType.B_TRAIN_B, DayType.B_NORMAL, DayType.SATURDAY,
                DayType.SUNDAY
            ),
            identity.weekdayMap
        )
        assertEquals(DayType.A, identity.earlyType)
    }

    @Test
    fun `默认配置就是旧默认策略迁移的结果`() {
        // 结构性金测试：新默认 == 旧默认策略迁移的结果
        assertEquals(configFromPolicy(DayTypePolicy.DEFAULT), DayTypeConfig.DEFAULT)
    }

    @Test
    fun `默认配置下训练日闲置`() {
        // 默认配置下周二晚上不该出现训练块 —— 「训练日」不是通用规律
        val ms = TimelineEngine.moments(tue, 3, courses, TemplateSet(config = DayTypeConfig.DEFAULT))
        val m = TimelineEngine.currentAt(ms, 19 * 60 + 30)!!
        assertFalse("不该出现训练块，实际：${m.title}", m.title.contains("训练"))
        assertFalse(m.kind == Kind.TRAIN)
    }

    @Test
    fun `恒等配置下训练块真的在`() {
        // 对照：恒等配置（周二映射到训练日）下训练块真的在
        val ms = TimelineEngine.moments(tue, 3, courses, TemplateSet(config = identity))
        val m = TimelineEngine.currentAt(ms, 19 * 60 + 30)!!
        assertTrue("实际得到: ${m.title}", m.title.contains("训练"))
        assertEquals(Kind.TRAIN, m.kind)
    }

    // ============================================================
    //  三、星期映射 + 早八层的组合行为
    // ============================================================

    @Test
    fun `星期映射决定工作日用哪套`() {
        val cfg = makeConfig(mapOf(2 to DayType.B_TRAIN_A))   // 周二 → 训练日A
        assertEquals(DayType.B_TRAIN_A, typeOf(tue, cfg))
        assertEquals("没被改派的星期仍走默认映射", DayType.B_NORMAL, typeOf(friWeek5, cfg))
    }

    @Test
    fun `映射改了要真的穿透到时间轴而不只是改标签`() {
        val msDefault = TimelineEngine.moments(
            tue, 3, courses, TemplateSet(config = DayTypeConfig.DEFAULT)
        )
        val msTrain = TimelineEngine.moments(
            tue, 3, courses, TemplateSet(config = makeConfig(mapOf(2 to DayType.B_TRAIN_A)))
        )
        assertNotEquals(
            "映射改了，时间轴却一模一样 —— 配置没穿透到引擎",
            msDefault.map { it.start to it.title },
            msTrain.map { it.start to it.title }
        )
    }

    @Test
    fun `改映射后起床时间跟着变`() {
        // 训练日模板 07:25 起、B 型也是 07:25 —— 换成 A 型（06:55）看得清
        assertEquals(7 * 60 + 25, wakeOf(tue, makeConfig(mapOf(2 to DayType.B_TRAIN_A))))
        assertEquals(6 * 60 + 55, wakeOf(tue, makeConfig(mapOf(2 to DayType.A))))
    }

    @Test
    fun `早八层优先于星期映射`() {
        // 周一有早八：早八层优先于星期映射
        val cfg = makeConfig(mapOf(1 to DayType.B_NORMAL), early = DayType.A)
        assertEquals("有早八的日子没走早八层", DayType.A, typeOf(mon, cfg))
    }

    @Test
    fun `没早八的日子仍然 07 点 25 起床`() {
        // ★ 这条是默认值取舍的核心：「有早八 06:55 / 没早八 07:25」
        // 是这个 App 存在的理由，配置再怎么改也不能把这档弄丢
        assertEquals(7 * 60 + 25, wakeOf(friWeek5, DayTypeConfig.DEFAULT))
        assertEquals(7 * 60 + 25, wakeOf(tue, DayTypeConfig.DEFAULT))
        assertEquals(6 * 60 + 55, wakeOf(mon, DayTypeConfig.DEFAULT))
        assertEquals(9 * 60, wakeOf(restFri, DayTypeConfig.DEFAULT))
    }

    // ============================================================
    //  四、REST：自动判定层
    // ============================================================

    @Test
    fun `休息日在任何映射下都不被改派`() {
        // ★ 无课工作日永远是休息日 —— 星期映射管不到它
        for (override in listOf(DayType.A, DayType.B_TRAIN_A, DayType.SATURDAY)) {
            val cfg = makeConfig(mapOf(1 to override), early = null)
            assertEquals("$override 的映射不该影响休息日", DayType.REST, typeOf(restFri, cfg))
        }
    }

    @Test
    fun `休息日的界面标签是无课休息日`() {
        assertEquals(
            "无课 · 休息日",
            TimelineEngine.dayTypeDisplay(restFri, 3, courses, DayTypeConfig.DEFAULT)
        )
    }

    // ============================================================
    //  五、显示：标签不能说谎
    // ============================================================

    @Test
    fun `早八日显示日历事实`() {
        val label = TimelineEngine.dayTypeDisplay(mon, 3, courses, DayTypeConfig.DEFAULT)
        assertTrue("实际：$label", label.contains("A 型日"))
        assertTrue("实际：$label", label.contains("有早八"))
    }

    @Test
    fun `自定义映射下标签不说谎`() {
        // 「A 型日 · 有早八」是句假话的情形：
        // 把工作日全映射到 A、又关掉早八层之后，周二（无早八）也会用 A 模板 ——
        // 但那天没有早八，标签**不能**带「· 有早八」。
        val cfg = DayTypeConfig(
            DayTypeConfig.DEFAULT.types,
            listOf(DayType.A, DayType.A, DayType.A, DayType.A, DayType.A, DayType.A, DayType.A),
            null
        )
        // 注意：周六周日映射成 A 是非法配置（weekdayMap 允许，只是引擎周末也走映射）；
        // 这里只关心周二的标签
        val label = TimelineEngine.dayTypeDisplay(tue, 3, courses, cfg)
        assertEquals("A 型日", label)
    }

    @Test
    fun `自定义日型名参与显示`() {
        val label = TimelineEngine.dayTypeDisplay(
            mon, 3, courses, DayTypeConfig.DEFAULT, mapOf(DayType.A to "早八日")
        )
        assertTrue("实际：$label", label.contains("早八日"))
    }

    @Test
    fun `周末标签用各自的名字`() {
        assertEquals(
            "周六", TimelineEngine.dayTypeDisplay(sat, 3, courses, DayTypeConfig.DEFAULT)
        )
        assertEquals(
            "周日", TimelineEngine.dayTypeDisplay(sun, 3, courses, DayTypeConfig.DEFAULT)
        )
    }

    // ============================================================
    //  六、迁移：configFromPolicy（旧策略 → 新配置）
    // ============================================================

    @Test
    fun `迁移 默认策略得到默认配置`() {
        assertEquals(DayTypeConfig.DEFAULT, configFromPolicy(DayTypePolicy.DEFAULT))
    }

    @Test
    fun `迁移 恒等策略得到恒等配置`() {
        assertEquals(identity, configFromPolicy(DayTypePolicy.ALL))
    }

    @Test
    fun `迁移 未启用的槽位落到回落项`() {
        // 例：只启用 A + 周末、回落 B 型 → 周二（自然槽位训练日A，未启用）落 B 型。
        // 这正是旧 resolve() 对那天给出的答案。
        val p = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY),
            fallback = DayType.B_NORMAL
        )
        assertEquals("周二应落到回落项", DayType.B_NORMAL, configFromPolicy(p).weekdayMap[1])
    }

    @Test
    fun `迁移 回落项可以不在启用集合里`() {
        val p = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.SATURDAY),
            fallback = DayType.B_NORMAL
        )
        assertEquals(DayType.B_NORMAL, configFromPolicy(p).weekdayMap[0])
    }

    @Test
    fun `迁移 A 没启用时早八层落到回落项`() {
        val p = DayTypePolicy(
            enabled = setOf(DayType.B_NORMAL, DayType.SATURDAY, DayType.SUNDAY),
            fallback = DayType.B_NORMAL
        )
        assertEquals(DayType.B_NORMAL, configFromPolicy(p).earlyType)
    }

    @Test
    fun `迁移结果必含休息日`() {
        assertEquals(DayType.REST, DayTypeConfig.DEFAULT.types.last())
        assertTrue(identity.manages(DayType.REST))
    }

    @Test
    fun `weekdaysOf 报出日型管哪几天`() {
        assertEquals(listOf(1, 2, 3, 4, 5), DayTypeConfig.DEFAULT.weekdaysOf(DayType.B_NORMAL))
        assertEquals(listOf(6), DayTypeConfig.DEFAULT.weekdaysOf(DayType.SATURDAY))
        assertEquals(emptyList<Int>(), DayTypeConfig.DEFAULT.weekdaysOf(DayType.B_TRAIN_A))
    }

    // ============================================================
    //  七、JSON 解析（v3/v4 旧段照常导入）
    // ============================================================

    private fun doc(dayTypes: String, version: Int = 4) =
        """{"format":"timetable","version":$version,"dayTypes":$dayTypes,"courses":[]}"""

    private fun parsed(json: String): ScheduleFormat.Parsed {
        val r = ScheduleFormat.parse(json, 19)
        assertTrue("期望解析成功，实际失败：${(r as? ScheduleFormat.Result.Failed)?.message}", r is ScheduleFormat.Result.Ok)
        return (r as ScheduleFormat.Result.Ok).parsed
    }

    private fun failed(json: String): String {
        val r = ScheduleFormat.parse(json, 19)
        assertTrue("期望解析失败，实际成功", r is ScheduleFormat.Result.Failed)
        return (r as ScheduleFormat.Result.Failed).message
    }

    @Test
    fun `旧 dayTypes 段默认值迁移成默认配置`() {
        val p = parsed(
            doc("""{"enabled":["A","B_NORMAL","SATURDAY","SUNDAY"],"fallback":"B_NORMAL"}""")
        )
        assertEquals(DayTypeConfig.DEFAULT, p.dayTypeConfig)
    }

    @Test
    fun `旧 dayTypes 段恒等值迁移成恒等配置`() {
        val p = parsed(
            doc("""{"enabled":["A","B_NORMAL","B_TRAIN_A","B_TRAIN_B","SATURDAY","SUNDAY"],"fallback":"B_NORMAL"}""")
        )
        assertEquals(identity, p.dayTypeConfig)
    }

    @Test
    fun `旧段解析结果仍在 dayTypes 字段里供预览`() {
        val p = parsed(doc("""{"enabled":["A","SATURDAY"],"fallback":"B_NORMAL"}"""))
        assertEquals(setOf(DayType.A, DayType.SATURDAY), p.dayTypes!!.enabled)
        assertEquals(DayType.B_NORMAL, p.dayTypes!!.fallback)
        // 同时给出了迁移后的配置
        assertEquals(DayType.B_NORMAL, p.dayTypeConfig!!.weekdayMap[0])
    }

    @Test
    fun `v3 文件的旧段照常导入`() {
        val p = parsed(doc("""{"enabled":["A"],"fallback":"A"}""", version = 3))
        assertTrue(p.dayTypeConfig != null)
    }

    @Test
    fun `没写 dayTypes 段时是 null 表示不动`() {
        val json = """{"format":"timetable","version":5,""" +
                """"courses":[{"name":"高数","dayOfWeek":1,"nodes":[1,2],"weeks":"1-5"}]}"""
        assertNull(parsed(json).dayTypes)
        assertNull(parsed(json).dayTypeConfig)
    }

    @Test
    fun `enabled 为空数组要报错`() {
        val msg = failed(doc("""{"enabled":[],"fallback":"A"}"""))
        assertTrue("要说明至少启用一种，实际：$msg", msg.contains("至少要启用一种"))
    }

    @Test
    fun `缺少 enabled 字段要报错`() {
        val msg = failed(doc("""{"fallback":"A"}"""))
        assertTrue("要提示缺 enabled，实际：$msg", msg.contains("enabled"))
    }

    @Test
    fun `旧名与 id 都认识且大小写不敏感`() {
        val p = parsed(doc("""{"enabled":["a","b_normal","5"],"fallback":"b_normal"}"""))
        assertEquals(
            setOf(DayType.A, DayType.B_NORMAL, DayType.SATURDAY),
            p.dayTypes!!.enabled
        )
        assertEquals(DayType.B_NORMAL, p.dayTypes!!.fallback)
    }

    @Test
    fun `不认识的名字要报错并列出可用 id`() {
        val msg = failed(doc("""{"enabled":["A","FOO"],"fallback":"A"}"""))
        assertTrue("要指出第几项，实际：$msg", msg.contains("第 2 项"))
        assertTrue("要列出可用 id，实际：$msg", msg.contains("\"4\""))
    }

    @Test
    fun `旧段里写休息日要被专门拦下`() {
        // 写 "7" 的人不是拼错了，是误解了休息日的定位 ——
        // 给一句能纠正误解的话比「可用的值是…」有用
        val msg = failed(doc("""{"enabled":["A","7"],"fallback":"A"}"""))
        assertTrue("实际：$msg", msg.contains("休息日"))
        val msg2 = failed(doc("""{"enabled":["A"],"fallback":"7"}"""))
        assertTrue("实际：$msg2", msg2.contains("休息日"))
    }

    @Test
    fun `省略 fallback 时按 id 升序取第一个已启用项`() {
        // 用固定顺序而不是集合遍历顺序 —— 后者每次运行可能不同，
        // 会让同一份文件解析出不同结果
        val p = parsed(doc("""{"enabled":["SUNDAY","B_TRAIN_B"]}"""))
        // id 升序里 B_TRAIN_B(4) 比 SUNDAY(6) 靠前
        assertEquals(DayType.B_TRAIN_B, p.dayTypes!!.fallback)
    }

    @Test
    fun `旧段单独存在也算有效内容`() {
        val p = parsed(doc("""{"enabled":["A"],"fallback":"A"}"""))
        assertNull(p.courses)
        assertNull(p.templates)
        assertTrue(p.dayTypes != null)
    }

    // ============================================================
    //  八、往返与存储
    // ============================================================

    @Test
    fun `v3 兼容导出再导入 日型策略不变`() {
        val policy = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY),
            fallback = DayType.B_NORMAL
        )
        val json = ScheduleFormat.serialize(
            termName = "测试学期",
            startDate = termStart,
            totalWeeks = 19,
            courses = emptyList(),
            templates = TemplateSet(config = DayTypeConfig.DEFAULT).expanded(),
            dayTypes = policy,
            version = 3
        )
        val back = parsed(json).dayTypes!!
        assertEquals(policy.enabled, back.enabled)
        assertEquals(policy.fallback, back.fallback)
    }

    @Test
    fun `旧存储键的输出顺序是固定的`() {
        // 输入顺序不同、内容相同 → 输出必须一致。
        val a = DayTypePolicy(
            enabled = setOf(DayType.SUNDAY, DayType.A, DayType.SATURDAY),
            fallback = DayType.A
        )
        val b = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.SATURDAY, DayType.SUNDAY),
            fallback = DayType.A
        )
        assertEquals(
            ScheduleFormat.dayTypesToJson(a),
            ScheduleFormat.dayTypesToJson(b)
        )
        // v4 起存储也写 id
        assertTrue(
            ScheduleFormat.dayTypesToJson(a).contains("""["1","5","6"]""")
        )
    }

    @Test
    fun `存进本地再读回来 策略不变`() {
        val policy = DayTypePolicy(
            enabled = setOf(DayType.A, DayType.B_TRAIN_A, DayType.SATURDAY),
            fallback = DayType.B_TRAIN_A
        )
        val json = ScheduleFormat.dayTypesToJson(policy)
        val back = ScheduleFormat.dayTypesFromJson(json)
        assertEquals(policy.enabled, back.enabled)
        assertEquals(policy.fallback, back.fallback)
    }

    @Test
    fun `存储损坏时回落到默认策略而不是崩溃`() {
        // 存储损坏不能变成「程序打不开」—— 大不了重新配一遍
        assertEquals(DayTypePolicy.DEFAULT, ScheduleFormat.dayTypesFromJson(""))
        assertEquals(DayTypePolicy.DEFAULT, ScheduleFormat.dayTypesFromJson("{坏掉的"))
        assertEquals(DayTypePolicy.DEFAULT, ScheduleFormat.dayTypesFromJson("""{"enabled":[]}"""))
    }

    @Test
    fun `默认策略自己也经得起一次往返`() {
        val back = ScheduleFormat.dayTypesFromJson(
            ScheduleFormat.dayTypesToJson(DayTypePolicy.DEFAULT)
        )
        assertEquals(DayTypePolicy.DEFAULT, back)
    }

    // ============================================================
    //  九、v5 本地存储编解码
    // ============================================================

    @Test
    fun `dayTypeConfig 存储往返`() {
        val config = DayTypeConfig(
            DayTypeConfig.DEFAULT.types, DayTypeConfig.DEFAULT.weekdayMap, null
        )
        val json = ScheduleFormat.dayTypeConfigToJson(config)
        assertEquals(config, ScheduleFormat.dayTypeConfigFromJson(json))
    }

    @Test
    fun `dayTypeConfig 存储损坏回默认`() {
        for (bad in listOf("", "{", "[]", "null", """{"types":[]}""")) {
            assertEquals(DayTypeConfig.DEFAULT, ScheduleFormat.dayTypeConfigFromJson(bad))
        }
    }
}
