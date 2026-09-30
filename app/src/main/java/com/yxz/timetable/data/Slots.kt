package com.yxz.timetable.data

/**
 * 节次时间表 —— 直接来自《时间规划表》第二节。
 *
 * 下标 0 是占位（不用），所以数组长度是 11，用 START[1] 表示第 1 节。
 * 这种「从 1 开始」的数组看着浪费一格，但能让代码里写 START[n] 时
 * n 就是课表上的节次，不用到处写 n-1 —— 少一个 off-by-one 的机会。
 *
 * ⚠️ 注意连堂：第 1-2 节中间只有 5 分钟换场，
 *    所以实际占用的是一个完整的 08:30–10:05 时间块，
 *    而不是「08:30–09:15 + 09:20–10:05」两个独立的块。
 *
 * ## 节次上限：不写死 10，由「一天」决定
 *
 * 内置时间表只到第 10 节（20:35 下课），但节数信息允许学校声明更多的节。
 * 第 11 节起按**晚课节奏**外推：每节 45 分钟、中间歇 5 分钟，从 20:40 开始。
 * 外推到第 14 节（23:10–23:55）就到头了 —— 一天只有 24 小时，这是物理上限，
 * 不是谁拍脑袋定的数字。
 */
object Slots {

    private val START = intArrayOf(
        0,
        8 * 60 + 30,   // 1   08:30
        9 * 60 + 20,   // 2   09:20
        10 * 60 + 25,  // 3   10:25
        11 * 60 + 15,  // 4   11:15
        14 * 60,       // 5   14:00
        14 * 60 + 50,  // 6   14:50
        15 * 60 + 55,  // 7   15:55
        16 * 60 + 45,  // 8   16:45
        19 * 60,       // 9   19:00
        19 * 60 + 50   // 10  19:50
    )

    private val END = intArrayOf(
        0,
        9 * 60 + 15,   // 1   09:15
        10 * 60 + 5,   // 2   10:05
        11 * 60 + 10,  // 3   11:10
        12 * 60,       // 4   12:00
        14 * 60 + 45,  // 5   14:45
        15 * 60 + 35,  // 6   15:35
        16 * 60 + 40,  // 7   16:40
        17 * 60 + 30,  // 8   17:30
        19 * 60 + 45,  // 9   19:45
        20 * 60 + 35   // 10  20:35
    )

    /**
     * 节次的一天上限。第 11 节起外推（每节 45 分钟、间歇 5 分钟、20:40 起始），
     * 到第 14 节 23:55 下课为止 —— 再多就跨进第二天了。
     */
    const val MAX_NODE = 14

    /** 某节的 (开始, 结束) 分钟数：内置表 1-10，之外按晚课节奏外推 */
    private fun slot(node: Int): Pair<Int, Int> {
        if (node <= 10) return START[node] to END[node]
        val k = node - 10
        val start = END[10] + 5 * k + 45 * (k - 1)
        return start to start + 45
    }

    private fun check(node: Int) {
        if (node !in 1..MAX_NODE) {
            throw IllegalArgumentException("节次必须在 1-$MAX_NODE 之间，现在是 $node")
        }
    }

    fun start(node: Int): Int {
        check(node)
        return slot(node).first
    }

    fun end(node: Int): Int {
        check(node)
        return slot(node).second
    }

    /** 把「距 00:00 的分钟数」格式化回 HH:mm，比如 510 -> "08:30" */
    fun fmt(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}
