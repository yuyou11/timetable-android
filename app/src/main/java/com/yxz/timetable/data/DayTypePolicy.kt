package com.yxz.timetable.data

/**
 * 日型配置 —— 「哪几种日型存在、星期几用哪种、有早八优先用哪种」。
 *
 * ============================================================
 *  v5：DayTypePolicy → DayTypeConfig
 * ============================================================
 *
 * 日型可创建/删除、星期可映射之后，旧的「启用集合 + 回落」模型
 * （[DayTypePolicy]）从运行时退役，换成三样东西：
 *
 *     types        现存的日型 id（**必含休息日 "7"**，最多 7 个）
 *     weekdayMap   周一..周日 各映射到哪种日型（恰好 7 项）
 *     earlyType    工作日有早八时优先用的日型；null = 不区分早八
 *
 * 引擎判定顺序（[TimelineEngine.resolveDayType]）：
 *
 *     ① 工作日全天无课              → 休息日（客观事实，最高优先）
 *     ② 工作日有早八且指定了早八型   → earlyType
 *     ③ 其余                        → weekdayMap[星期几]
 *
 * 休息日不参与 weekdayMap 和 earlyType —— 它只由「工作日全天没课」
 * 这一条客观事实触发，跟星期映射是两个层面。
 *
 * ## DayTypePolicy 为什么还在
 *
 * v3/v4 文件的 dayTypes 段和旧 SharedPreferences 里的 day_types 键还在用它。
 * 读进来之后由 [configFromPolicy] **确定性迁移**成 DayTypeConfig，
 * 迁移结果与旧程序的默认行为逐日一致（见那边的对照表）。
 * 它不再参与任何运行时判定。
 */

/** 日型数量上限（id 空间 "1"-"7"，其中 "7" 恒为休息日） */
const val MAX_DAY_TYPES = 7

/**
 * 一份配置里「日型系统」的全貌。
 *
 * 做成 data class（val 全靠它）：它被当作**值**在存储序列化、引擎、
 * 界面之间传来传去，可变对象只会带来「谁把它改了」的追查成本。
 */
data class DayTypeConfig(
    /** 现存的日型 id 集合（按 id 升序）。**必须含 REST** —— 休息日是引擎的自动判定层，不存在「删掉休息日」这个操作。 */
    val types: List<DayType>,
    /** 周一..周日 各映射到哪种日型。恰好 7 项；每一项都必须 ∈ [types] 且不能是 REST（休息日不走映射）。 */
    val weekdayMap: List<DayType>,
    /**
     * 工作日有早八（第 1-2 节有课）时优先用的日型。
     * null = 不区分早八 —— 有早八的工作日也按星期映射走。
     */
    val earlyType: DayType?
) {

    /** 这个 id 是否现存（删除守卫 / 编辑入口都用它） */
    fun manages(dayType: DayType): Boolean = dayType in types

    /** 这个日型管哪些星期几（1=周一 … 7=周日），升序 */
    fun weekdaysOf(dayType: DayType): List<Int> =
        weekdayMap.mapIndexed { index, t -> if (t == dayType) index + 1 else null }
            .filterNotNull()

    companion object {

        /**
         * 默认配置 —— 与 [DayTypePolicy.DEFAULT] 的行为**逐日一致**：
         * 有早八 → A 型日；其余工作日 → B 型日（训练日默认闲置，
         * 效果上周二/周四也走 B 型日）；周六/周日各自的模板。
         * 对照：configFromPolicy(DayTypePolicy.DEFAULT) 的结果就是这个。
         */
        val DEFAULT: DayTypeConfig = DayTypeConfig(
            types = Templates.ALL_TYPES + DayType.REST,
            weekdayMap = listOf(
                DayType.B_NORMAL, DayType.B_NORMAL, DayType.B_NORMAL,
                DayType.B_NORMAL, DayType.B_NORMAL,
                DayType.SATURDAY, DayType.SUNDAY
            ),
            earlyType = DayType.A
        )
    }
}

/**
 * 旧「自然规则」里每个星期几对应的槽位 —— [configFromPolicy] 的迁移依据。
 * （v4 及更早：周二 → 训练日A、周四 → 训练日B，其余工作日 → 普通日。）
 */
private val LEGACY_NATURAL_SLOT = mapOf(
    1 to DayType.B_NORMAL,
    2 to DayType.B_TRAIN_A,
    3 to DayType.B_NORMAL,
    4 to DayType.B_TRAIN_B,
    5 to DayType.B_NORMAL,
    6 to DayType.SATURDAY,
    7 to DayType.SUNDAY
)

/**
 * 把旧 [DayTypePolicy] **确定性迁移**成 [DayTypeConfig]。
 *
 * 规则：每个星期几先落到旧自然槽位（周二→训练日A…），槽位在
 * enabled 里就用槽位，不在就用 fallback —— 这正是旧 resolve()
 * 对那一天给出的答案，所以迁移前后**逐日等价**。
 * 早八优先层同理：A 启用则早八用 A，否则用 fallback。
 */
fun configFromPolicy(policy: DayTypePolicy): DayTypeConfig {
    fun slot(dow: Int): DayType {
        val natural = LEGACY_NATURAL_SLOT.getValue(dow)
        return if (natural in policy.enabled) natural else policy.fallback
    }

    val early = if (DayType.A in policy.enabled) DayType.A else policy.fallback
    return DayTypeConfig(
        types = Templates.ALL_TYPES + DayType.REST,
        weekdayMap = (1..7).map { slot(it) },
        earlyType = early
    )
}

/**
 * 【已退役】旧模型：「启用哪几种 + 没启用时回落到哪种」。
 *
 * 还活着的原因只有一个：v3/v4 文件的 dayTypes 段和旧 SharedPreferences
 * 仍在用它表达配置，解析后立刻经 [configFromPolicy] 迁移。
 * 新代码请用 [DayTypeConfig]。
 */
data class DayTypePolicy(
    /** 这份配置里**真正会被用到**的日型集合，不能为空 */
    val enabled: Set<DayType>,

    /**
     * 算出来的日型不在 [enabled] 里时，改用哪一种
     * （允许不在 enabled 里 —— 「用这套模板兜底」是独立指定）
     */
    val fallback: DayType
) {

    /**
     * 旧引擎的映射落点：启用就用原样，没启用就退回 [fallback]。
     *
     * 只在迁移路径上还有意义（旧策略不再参与运行时判定）。
     */
    fun resolve(computed: DayType): DayType =
        if (computed in enabled) computed else fallback

    companion object {

        /**
         * 不裁剪任何日型 —— 引擎算出什么就用什么。
         *
         * 只剩一个用途：经 [configFromPolicy] 迁移出「恒等配置」
         * （星期映射 = 旧自然槽位），用它测「v4 旧规则本身」。
         *
         * ⚠️ 用 [Templates.ALL_TYPES] 而不是 `DayType.entries` ——
         * 后者会把 [DayType.REST] 也算进「启用」，而它是引擎推导出来的状态，
         * 根本不是可选日型（理由见枚举上的注释）。
         */
        val ALL: DayTypePolicy = DayTypePolicy(
            enabled = Templates.ALL_TYPES.toSet(),
            fallback = DayType.B_NORMAL
        )

        /**
         * 旧默认策略（A + 没早八的 B + 周末，训练日不启用）。
         * 新模型的默认值在 [DayTypeConfig.DEFAULT]；这个对象只服务旧文件解析。
         */
        val DEFAULT: DayTypePolicy = DayTypePolicy(
            enabled = setOf(
                DayType.A,
                DayType.B_NORMAL,
                DayType.SATURDAY,
                DayType.SUNDAY
            ),
            fallback = DayType.B_NORMAL
        )
    }
}
