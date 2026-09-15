package com.yxz.timetable.data

/**
 * 日型策略 —— 决定「哪几种日型真的会被用到」。
 *
 * ============================================================
 *  为什么需要它
 * ============================================================
 *
 * 引擎原来会算出**六种**日型：
 *
 *   A          有早八的日子
 *   B_TRAIN_A  周二 · 力量A     ← 这两条来自原文档里的训练安排
 *   B_TRAIN_B  周四 · 力量B     ← 只对排了力量训练的人有意义
 *   B_NORMAL   没早八的日子
 *   SATURDAY / SUNDAY
 *
 * 但「周二练力量A」是**某一份具体规划表**里的安排，不是通用规律。
 * 别人的课表里可能根本没有训练日，那两套模板就纯属噪声 ——
 * 更要紧的是，它会让「周二到底该几点起」这件事变得难以回答。
 *
 * 所以我们把「引擎能算出什么」和「这套配置实际用哪些」分开：
 *
 *   [naturalDayType]  纯日历规则 —— 今天第 1-2 节有没有课
 *   [resolve]         把它映射到这份配置真正启用的日型上
 *
 * **这是很通用的一招：把「算得出什么」和「要用什么」拆成两层，
 * 中间那层策略就变成了可配置的。** 直接改计算逻辑是做不到这点的，
 * 因为一旦写死就没有回旋余地了。
 *
 * ============================================================
 *  默认值为什么是这四个
 * ============================================================
 *
 * `enabled = [A, B_NORMAL, SATURDAY, SUNDAY]`、`fallback = B_NORMAL`
 *
 * 效果是：
 *   有早八的日子（周一、周三） → A 型日，06:55 起床
 *   没早八的日子（周二、周四、周五） → B 型日，07:25 起床
 *   周末 → 周六 / 周日模板
 *
 * 也就是说 —— **「有早八 / 没早八」这个最核心的区分完整保留了**，
 * 被裁掉的只有「力量A / 力量B」这个细分。
 *
 * 为什么这么定：这两档区分是整个 App 存在的理由
 * （它回答的就是「今天要不要早起」），不能动；
 * 而训练日的细分只对少数人有意义，让它默认不出现、需要时再显式打开。
 *
 * 换句话说：**默认值应该保留「所有人都需要的行为」，
 * 把「只有部分人需要的行为」变成可选。** 反过来做的话，
 * 大多数人会觉得这个软件在自顾自地替他们安排事情。
 *
 * @param enabled  这份配置里**真正会被用到**的日型集合，不能为空
 * @param fallback 算出来的日型不在 [enabled] 里时，改用哪一种
 */
data class DayTypePolicy(
    val enabled: Set<DayType>,
    val fallback: DayType
) {

    /**
     * 把「日历算出来的日型」映射成「这次实际要用的日型」。
     *
     * 只有一个分支，但它是整个策略的落点：
     * 启用就用原样的，没启用就退回 [fallback]。
     *
     * 注意 [fallback] **不必**在 [enabled] 里 —— 它表示的是
     * 「用这套模板来兜底」，是一个独立的指定，不是「启用了一种日型」。
     */
    fun resolve(computed: DayType): DayType =
        if (computed in enabled) computed else fallback

    companion object {

        /**
         * 不裁剪任何日型 —— 引擎算出什么就用什么。
         *
         * 测试「原始日历规则」时用这个：它让 [resolve] 变成恒等映射，
         * 于是测的就是 [TimelineEngine.naturalDayType] 那条规则本身。
         */
        val ALL: DayTypePolicy = DayTypePolicy(
            enabled = DayType.entries.toSet(),
            fallback = DayType.B_NORMAL
        )

        /**
         * 默认策略（也是没写 `dayTypes` 段时的回落值）。
         *
         * 只保留「有早八 / 没早八」这档最核心的区分，去掉训练日的细分。
         * 详见类注释里对默认值取舍的说明。
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
