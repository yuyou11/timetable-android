package com.yxz.timetable.data

/**
 * 一天属于哪种模板。
 *
 * 关键点：**这个类型不是按星期几写死的，而是每天现算的**。
 * 「哪天用哪套」在 v5 里由 [DayTypeConfig]（星期映射 + 早八优先层）决定，
 * 判定见 [TimelineEngine.resolveDayType]。
 *
 * ## v4/v5：id 是格式的一部分
 *
 * 日型在文件、存储里的身份是 **id "1"–"7"**；成员名（A / B_TRAIN_A / …）
 * 只是代码里的语义别名，方便引擎和测试读。id 与规则的对应关系是
 * **格式标准的一部分**，改名也不能动：
 *
 *     id 1  工作日有早八（第 1-2 节有课）
 *     id 2  无早八的普通工作日
 *     id 3  周二（默认闲置，映射后启用）
 *     id 4  周四（默认闲置，映射后启用）
 *     id 5  周六
 *     id 6  周日
 *     id 7  工作日全天无课（休息日，自动判定，不可删除）
 *
 * v3 及更早的文件里写的是语义名（"A"、"B_TRAIN_A"…），解析层负责翻译。
 */
/**
 * `label` 描述的是**这套模板的默认名**，不是「今天有没有早八」，也不是
 * 用户当前看到的名字 —— 用户可以在文件里给任何 id 改名（dayTypeNames 段）。
 *
 * ⚠️ A 的 label 是「A 型日」，**不带「有早八」**。理由见 [TimelineEngine]
 * 顶部对 dayTypeDisplay 的说明：把「用哪套模板」和「今天有没有早八」
 * 焊在同一个字符串里，等其中一件事能独立变化时就会说假话。
 *
 * 所有「界面要显示日型名」的地方都应该走 [dayTypeName]，而不是直接查
 * label —— 否则用户的改名在某些页面会静默失效。
 */
enum class DayType(val id: String, val label: String) {
    A("1", "A 型日"),
    B_NORMAL("2", "B 型日"),
    B_TRAIN_A("3", "B 型日 · 训练日 力量A"),
    B_TRAIN_B("4", "B 型日 · 训练日 力量B"),
    SATURDAY("5", "周六"),
    SUNDAY("6", "周日"),

    /**
     * 无课休息日（假期 / 停课 / 课表没排到的工作日）—— 引擎**推导**出来的状态，
     * 不是用户可选的日型。见 [TimelineEngine.resolveDayType] 的规则 ①。
     *
     * ⚠️ REST（id "7"）**故意不进** [Templates.ALL_TYPES]。它不是用户可映射的日型，
     * 而是「那天没课」这一客观事实的体现。不进 ALL_TYPES 的连锁效果全部是故意的：
     *   · dayTypeConfig 的 weekdayMap / earlyType 写不了它（写了会报错）
     *   · 旧 dayTypes 段的 enabled / fallback 也写不了它
     * 与 v3 的区别：从 v4 起它的**名字和模板**可以自定义、可以随文件携带
     * （templates 段允许键 "7"）—— 只是「哪天算休息日」这件事仍然由引擎定。
     */
    REST("7", "无课 · 休息日")
}

/**
 * 当前生效的日型名：**自定义优先，没改过用默认**。
 *
 * [custom] 是 store 里那份稀疏的名字表（只有用户改过的 id 才有键）。
 * 所有「界面要显示日型名」的地方都必须走这里 —— 直接用 [DayType.label]
 * 的话，用户的改名在那个页面会静默失效。
 */
fun dayTypeName(type: DayType, custom: Map<DayType, String> = emptyMap()): String =
    custom[type]?.takeIf { it.isNotBlank() } ?: type.label

/**
 * 五套作息模板，逐条抄自《时间规划表》第四、五、六节。
 *
 * 写模板时有两个刻意为之的地方：
 *
 * 1. **每一格首尾相接，不留缝**。00:00 到 24:00 被完整覆盖，
 *    这样引擎只要按顺序摆下来就一定不会有「空白时间」。
 *
 * 2. **课表格子标了 nodes**。像 08:30–10:05 这一格，模板里写的是
 *    「第 1-2 节 / 自习」，标上 nodes = 1..2。有课时引擎会拿课程信息
 *    整格替换掉它；没课时就显示成「第 1-2 节 · 无课」。
 *    这样你永远不会在通知栏看到「第 1-2 节」和「大学英语」两条打架。
 */
object Templates {

    /** "08:30" -> 510 */
    private fun t(s: String): Int {
        val p = s.split(":")
        return p[0].toInt() * 60 + p[1].toInt()
    }

    private fun b(
        from: String, to: String, title: String, note: String = "",
        kind: Kind = Kind.CHORE, nodes: IntRange? = null
    ) = Block(t(from), t(to), title, note, kind, nodes)

    /** A 型 · 有早八（周一、周三） */
    val A: List<Block> = listOf(
        b("00:00", "06:55", "睡觉", "", Kind.SLEEP),
        b("06:55", "07:10", "起床、洗漱", "10 月后 06:30 恢复供电，正好接上", Kind.CHORE),
        b("07:10", "07:35", "早餐", "按你的实际节奏留 20 分钟", Kind.MEAL),
        b("07:35", "08:15", "早读", "英语单词 + 当天课程预习，40 分钟", Kind.STUDY),
        b("08:15", "08:30", "前往教室", "15 分钟缓冲，够走到 EI 楼或 B 楼", Kind.TRANSIT),
        b("08:30", "10:05", "第 1-2 节", "自习 / 预习", Kind.CLASS, 1..2),
        b("10:05", "10:25", "大课间", "全天最长课间", Kind.FREE),
        b("10:25", "12:00", "第 3-4 节", "自习", Kind.CLASS, 3..4),
        b("12:00", "12:40", "午餐", "", Kind.MEAL),
        b("12:40", "13:30", "午睡（硬性）", "周三有晚课，这觉必须睡", Kind.SLEEP),
        b("13:30", "14:00", "醒神 + 前往教室", "", Kind.TRANSIT),
        b("14:00", "15:35", "第 5-6 节", "自习", Kind.CLASS, 5..6),
        b("15:35", "15:55", "下午课间", "第二个大课间", Kind.FREE),
        b("15:55", "17:30", "第 7-8 节", "自习 / 轻松跑", Kind.CLASS, 7..8),
        b("17:30", "18:30", "晚餐 + 散步", "热水 17:30 起，可先洗澡再吃饭", Kind.MEAL),
        b("18:30", "19:00", "当日复盘 + 明日待办", "10 分钟写清", Kind.STUDY),
        b("19:00", "20:35", "第 9-10 节", "晚自习", Kind.CLASS, 9..10),
        b("20:35", "21:40", "晚自习", "当天作业清零，65 分钟，做完就停", Kind.STUDY),
        b("21:40", "22:40", "★ 自由时间", "游戏 / 番剧 / 聊天，到点就玩，不设条件", Kind.FREE),
        b("22:40", "23:10", "洗漱、收尾", "热水供应至 23:30", Kind.CHORE),
        b("23:10", "24:00", "睡觉", "23:30 关楼门、熄灯", Kind.SLEEP)
    )

    /** B 型 · 训练日（周二 = 力量A / 周四 = 力量B） */
    fun bTrainVariants(): Pair<List<Block>, List<Block>> {
        fun build(split: String, detail: String): List<Block> = listOf(
            b("00:00", "07:25", "睡觉", "", Kind.SLEEP),
            b("07:25", "07:40", "起床、洗漱", "比 A 型晚 30 分钟", Kind.CHORE),
            b("07:40", "08:00", "早餐", "20 分钟", Kind.MEAL),
            b("08:00", "10:05", "★ 黄金自习块", "图书馆或空教室，2 小时 5 分整块不打断", Kind.STUDY),
            b("10:05", "10:25", "大课间：收拾换楼", "", Kind.TRANSIT),
            b("10:25", "12:00", "第 3-4 节", "自习", Kind.CLASS, 3..4),
            b("12:00", "12:40", "午餐", "", Kind.MEAL),
            b("12:40", "13:30", "午睡（硬性）", "下午连堂，必须睡", Kind.SLEEP),
            b("13:30", "14:00", "醒神 + 前往教室", "", Kind.TRANSIT),
            b("14:00", "15:35", "第 5-6 节", "自习", Kind.CLASS, 5..6),
            b("15:35", "15:55", "下午课间", "", Kind.FREE),
            b("15:55", "17:30", "第 7-8 节", "自习", Kind.CLASS, 7..8),
            b("17:30", "18:15", "晚餐（七分饱）", "训练前 1.5 小时吃完", Kind.MEAL),
            b("18:15", "18:45", "消食 + 换装备", "", Kind.CHORE),
            b("18:45", "19:00", "前往场地", "路上算热身", Kind.TRANSIT),
            b("19:00", "20:20", "★ 训练 80 分钟", "$split（$detail）热身10+力量60+拉伸10", Kind.TRAIN),
            b("20:20", "20:50", "洗澡", "博远有淋浴间，洗完再骑车回宿舍", Kind.CHORE),
            b("20:50", "21:40", "晚自习 50 分钟", "训练日只做当天作业", Kind.STUDY),
            b("21:40", "22:40", "★ 自由时间", "训练日也不取消", Kind.FREE),
            b("22:40", "23:10", "洗漱、收尾", "", Kind.CHORE),
            b("23:10", "24:00", "睡觉", "此档睡眠 8 小时 15 分", Kind.SLEEP)
        )
        return build("力量 A：推 / 上肢", "卧推、肩推、划船") to
                build("力量 B：拉 / 下肢", "深蹲、硬拉、引体辅助")
    }

    /** B 型 · 非训练日（周五） */
    val B_NORMAL: List<Block> = listOf(
        b("00:00", "07:25", "睡觉", "", Kind.SLEEP),
        b("07:25", "07:40", "起床、洗漱", "", Kind.CHORE),
        b("07:40", "08:00", "早餐", "", Kind.MEAL),
        b("08:00", "10:05", "★ 黄金自习块", "2 小时 5 分整块", Kind.STUDY),
        b("10:05", "10:25", "大课间：收拾换楼", "", Kind.TRANSIT),
        b("10:25", "12:00", "第 3-4 节", "自习", Kind.CLASS, 3..4),
        b("12:00", "12:40", "午餐", "", Kind.MEAL),
        b("12:40", "13:30", "午睡（硬性）", "", Kind.SLEEP),
        b("13:30", "14:00", "醒神 + 前往教室", "", Kind.TRANSIT),
        b("14:00", "15:35", "第 5-6 节", "自习", Kind.CLASS, 5..6),
        b("15:35", "15:55", "下午课间", "", Kind.FREE),
        b("15:55", "17:30", "第 7-8 节", "自习 / 运动", Kind.CLASS, 7..8),
        b("17:30", "18:30", "晚餐 + 散步", "", Kind.MEAL),
        b("18:30", "19:00", "当日复盘 + 明日待办", "", Kind.STUDY),
        b("19:00", "20:35", "第 9-10 节", "晚自习", Kind.CLASS, 9..10),
        b("20:35", "21:40", "晚自习", "当天作业清零", Kind.STUDY),
        b("21:40", "22:40", "★ 自由时间", "本周最稳定的一块，不设条件", Kind.FREE),
        b("22:40", "23:10", "洗漱、收尾", "", Kind.CHORE),
        b("23:10", "24:00", "睡觉", "", Kind.SLEEP)
    )

    /** 周六 · 训练日，白纸黑字不排学习 */
    val SATURDAY: List<Block> = listOf(
        b("00:00", "09:00", "睡觉", "一周唯一可以睡到自然醒的一天", Kind.SLEEP),
        b("09:00", "09:30", "自然醒（不设闹钟）", "", Kind.CHORE),
        b("09:30", "10:30", "早餐 / 早午餐", "下午要练，吃扎实一点，别凑合", Kind.MEAL),
        b("10:30", "12:30", "★ 自由块 1", "游戏 / 番剧，整块不打断", Kind.FREE),
        b("12:30", "13:30", "午餐（七分饱）", "离训练还有 1.5 小时", Kind.MEAL),
        b("13:30", "14:45", "午休 + 消食", "别吃完就去跑", Kind.SLEEP),
        b("14:45", "15:00", "换装备、前往操场", "跑鞋 + 水", Kind.TRANSIT),
        b("15:00", "16:40", "★ 训练 100 分钟", "热身10 + 跑步40~60 + 核心15 + 拉伸10", Kind.TRAIN),
        b("16:40", "17:30", "洗澡", "热水供应至 23:30", Kind.CHORE),
        b("17:30", "18:30", "晚餐", "", Kind.MEAL),
        b("18:30", "22:30", "★ 自由块 2", "本周最大的一块，游戏 / 电影 / 开黑，随便", Kind.FREE),
        b("22:30", "23:30", "洗漱、聊天、睡前刷手机", "", Kind.CHORE),
        b("23:30", "24:00", "睡觉", "次日想 08:30 起，就别超过 24:00", Kind.SLEEP)
    )

    /** 周日 · 缓冲日：半天收心 + 半天自由 */
    val SUNDAY: List<Block> = listOf(
        b("00:00", "08:30", "睡觉", "", Kind.SLEEP),
        b("08:30", "09:00", "起床、洗漱", "", Kind.CHORE),
        b("09:00", "09:40", "早餐", "", Kind.MEAL),
        b("09:40", "11:40", "高数周预习（2 小时）", "全周性价比最高的 2 小时", Kind.STUDY),
        b("11:40", "13:30", "午餐 + 午休", "", Kind.MEAL),
        b("13:30", "17:00", "★ 自由块", "整块，不打断，别切成碎片", Kind.FREE),
        b("17:00", "18:30", "晚餐 + 洗澡", "", Kind.MEAL),
        b("18:30", "19:00", "周复盘 + 下周待办", "顺便确认下周有没有调课", Kind.STUDY),
        b("19:00", "21:00", "晚自习：补作业 + 周一英语单词", "2 小时，为早八做准备", Kind.STUDY),
        b("21:00", "22:40", "★ 自由块", "游戏 / 放松，收个尾", Kind.FREE),
        b("22:40", "23:10", "洗漱", "", Kind.CHORE),
        b("23:10", "24:00", "睡觉", "保证周一 06:55 起得来", Kind.SLEEP)
    )

    /**
     * 「无课休息日」模板 —— 引擎把「全天没课的工作日」判为 REST
     * （假期、停课、课表没排到的日子），那天按这一套过：
     * 没有课程格子、没有晚自习，全是自由块。
     *
     * REST 不在 [ALL_TYPES] 里（不参与星期映射，理由见枚举注释），
     * 但从 v4 起它的模板可以自定义并随文件携带 —— 用户导入了 templates["7"]
     * 时 [TemplateSet.of] 会优先用自定义的，没导入就落到这里的内置版。
     */
    val REST: List<Block> = listOf(
        b("00:00", "09:00", "睡觉", "", Kind.SLEEP),
        b("09:00", "09:30", "起床、洗漱"),
        b("09:30", "10:15", "早餐", "", Kind.MEAL),
        b("10:15", "12:00", "★ 自由块", "没课的日子，做点自己想做的事", Kind.FREE),
        b("12:00", "12:40", "午餐", "", Kind.MEAL),
        b("12:40", "13:40", "午休", "闭眼躺一会儿，不用睡着", Kind.SLEEP),
        b("13:40", "17:30", "★ 自由块", "整块时间，别切成碎片", Kind.FREE),
        b("17:30", "18:30", "晚餐 + 散步", "", Kind.MEAL),
        b("18:30", "22:30", "★ 自由块", "晚上没有晚自习，放松、收尾都行", Kind.FREE),
        b("22:30", "23:30", "洗漱、聊天、睡前刷手机"),
        b("23:30", "24:00", "睡觉", "没有闹钟的一天也别熬太晚", Kind.SLEEP)
    )

    // ------------------------------------------------------------------
    //  按日型取用
    // ------------------------------------------------------------------

    private val trainPair: Pair<List<Block>, List<Block>> by lazy { bTrainVariants() }

    val B_TRAIN_A: List<Block> get() = trainPair.first
    val B_TRAIN_B: List<Block> get() = trainPair.second

    /**
     * 六种**可映射**的日型，按 id 升序排列（v4 起 id 是格式的一部分）。
     * UI 列表、旧 dayTypes 段的输出顺序都依赖它保持稳定。
     *
     * ⚠️ 这也是旧 dayTypes 段里「合法的日型」全集：REST（id "7"）不参与
     * 启用/回落与星期映射（理由见枚举注释）。
     * 序列化/模板展开用的七种完整顺序见 [FILE_ORDER]。
     */
    val ALL_TYPES: List<DayType> = listOf(
        DayType.A, DayType.B_NORMAL, DayType.B_TRAIN_A,
        DayType.B_TRAIN_B, DayType.SATURDAY, DayType.SUNDAY
    )

    /**
     * 文件里 templates 段的完整键序：id 升序，**含休息日**（"7" 从 v4 起
     * 可以自定义并随文件携带）。导出与本地存储序列化都按它输出，
     * 保证「同一份数据每次导出结果完全一致」。
     */
    val FILE_ORDER: List<DayType> = ALL_TYPES + DayType.REST

    /** 取内置模板 */
    fun builtin(type: DayType): List<Block> = when (type) {
        DayType.A -> A
        DayType.B_TRAIN_A -> B_TRAIN_A
        DayType.B_TRAIN_B -> B_TRAIN_B
        DayType.B_NORMAL -> B_NORMAL
        DayType.SATURDAY -> SATURDAY
        DayType.SUNDAY -> SUNDAY
        DayType.REST -> REST
    }
}

/**
 * 运行时的模板集合。
 *
 * ## 为什么要多这一层
 *
 * 改动之前，五套模板是 `Templates` 里的 `val` 常量，代码直接引用 `Templates.A`。
 * 现在要支持用户导入自己的模板，就必须让「模板从哪来」变成一个可替换的东西。
 *
 * `TemplateSet` 就是这个替换点：它内部只有一张表，**先查用户自定义的，
 * 查不到就回落到内置的**。于是：
 *
 *   - 用户只改了 A 型日 → 只有 A 型走自定义，其余五种继续用内置
 *   - 用户完全没导入   → `custom` 是空表，六种全部回落到内置，行为和以前一模一样
 *
 * 这种「局部覆盖 + 缺省回落」的写法，比要求用户提供全部六套模板友好得多 ——
 * 改动小、出错面小、老数据也不用迁移。
 */
data class TemplateSet(
    val custom: Map<DayType, List<Block>> = emptyMap(),
    /**
     * 这份配置的日型系统（v5）：哪些日型存在、星期几用哪套、有早八优先用哪套。
     *
     * 放在这里而不是让调用方另外传，是因为**配置和模板必须同源**：
     * 如果模板来自 A 处方、配置来自 B 处，就可能出现
     * 「按配置这是 A 型日，但取到的却是 B 型的模板」这种错配。
     * 让它们待在一个对象里，就没法分开传错了。
     */
    val config: DayTypeConfig = DayTypeConfig.DEFAULT
) {

    fun of(type: DayType): List<Block> = custom[type] ?: Templates.builtin(type)

    // ------------------------------------------------------------------
    //  节次 → 时刻
    //
    //  ⚠️ 这里曾经是个真 bug：节次时间写死在 [Slots] 里，模板里写的时刻
    //  对**课程**完全不生效 —— 引擎永远按 Slots 摆课，模板的占位格
    //  只被用来「找位置」。于是用户改了模板里的上课时间，
    //  今日页看着变了（周围格子错位），课表页却纹丝不动。
    //
    //  现在反过来：**占位格里写的就是准的**，Slots 只作为兜底
    //  （某种日型根本没有那一段占位格时，比如 B 型日的第 1-2 节）。
    //
    //  这样「课表页的节次时间」「今日页里课的位置」「课表数据的 nodes」
    //  三者由同一个来源推导，不可能再各说各话。
    // ------------------------------------------------------------------

    /** node -> 该节次段的开始时刻，从占位格里读出来 */
    private val slotStarts: Map<Int, Int> by lazy { buildSlotEdge(start = true) }

    /** node -> 该节次段的结束时刻 */
    private val slotEnds: Map<Int, Int> by lazy { buildSlotEdge(start = false) }

    private fun buildSlotEdge(start: Boolean): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        // 按 FILE_ORDER 的固定顺序遍历（含休息日 —— 它的模板从 v4 起
        // 也可以带占位格），结果才是确定的
        for (type in Templates.FILE_ORDER) {
            for (b in of(type)) {
                val n = b.nodes ?: continue
                // 第一次见到就用它 —— 各种日型之间本该一致，
                // 真不一致的话以排在前面的为准（导入时会另外提醒）
                if (start) out.putIfAbsent(n.first, b.start) else out.putIfAbsent(n.last, b.end)
            }
        }
        return out
    }

    /** 第 [node] 节所在的节次段几点开始。模板里没写就回落到内置作息表 */
    fun slotStart(node: Int): Int = slotStarts[node] ?: Slots.start(node)

    /** 第 [node] 节所在的节次段几点结束 */
    fun slotEnd(node: Int): Int = slotEnds[node] ?: Slots.end(node)

    /**
     * 覆盖第 [node] 节的**占位格**（模板里标了 `nodes` 的那一格），没有就返回 null。
     *
     * 用来回答「这一格没课时，这段时间本来安排的是什么」—— 课表页的自习卡
     * 就是从这里取文案的，和今日页、通知栏同一个来源，
     * 不会出现「课表页说自习、通知栏说空档」这种自相矛盾。
     *
     * 注意必须**按日型取**：同一节次在不同日型下备注可能不同
     * （A 型第 7-8 节是「自习 / 轻松跑」，B 型训练日是「自习」）。
     * 周六 / 周日 / 休息日的模板里本来就没有课表格子，这里如实返回 null，
     * 由调用方退回内置作息时刻。
     */
    fun blockAt(type: DayType, node: Int): Block? =
        of(type).firstOrNull { it.nodes?.contains(node) == true }

    /**
     * 各种日型对同一个节次段的时刻是否一致。
     *
     * 课表页只有一列节次时间，如果 A 型日写 08:30、B 型日写 09:00，
     * 那一列就没法同时说对。返回不一致的项，供导入时提醒用户。
     */
    fun slotConflicts(): List<String> {
        val seen = mutableMapOf<Int, Pair<Int, Int>>()
        val bad = mutableListOf<String>()
        for (type in Templates.FILE_ORDER) {
            for (b in of(type)) {
                val n = b.nodes ?: continue
                val prev = seen.putIfAbsent(n.first, b.start to b.end)
                if (prev != null && prev != (b.start to b.end)) {
                    bad += "第 ${n.first}-${n.last} 节：${dayTypeName(type)} 写的是 " +
                            "${Slots.fmt(b.start)}-${Slots.fmt(b.end)}，" +
                            "而另一种日型写的是 " +
                            "${Slots.fmt(prev.first)}-${Slots.fmt(prev.second)}"
                }
            }
        }
        return bad
    }

    /** 这一套是否是用户自定义过的 */
    val isCustomized: Boolean get() = custom.isNotEmpty()

    /**
     * 展开成「七种都在」的完整表，导出时用。
     *
     * 注意仍然展开**全部七种（含休息日）**：导出的文件是一份可以拿来手改的
     * 完整底稿，没映射到的那几种也一起写出去，用户想改星期映射时直接改
     * `dayTypeConfig` 就行，不用再去找模板正文。
     */
    fun expanded(): Map<DayType, List<Block>> =
        Templates.FILE_ORDER.associateWith { of(it) }

    companion object {
        val BUILTIN = TemplateSet()
    }
}
