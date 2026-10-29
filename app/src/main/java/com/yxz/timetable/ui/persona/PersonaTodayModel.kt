package com.yxz.timetable.ui.persona

import com.yxz.timetable.data.Kind
import com.yxz.timetable.data.Moment

/**
 * 今天页（P3 版）的纯逻辑：24 小时色带怎么切、时间线怎么滚。
 *
 * ## 为什么单独一个文件
 *
 * 这几件事都是**纯计算**、都能在电脑上单测：色带的每一段宽度就是分钟数，
 * 滚动位置是「当前格居中 + 两头夹住」。放在 View 里就只能靠装 APK 用眼睛验，
 * 而「滚到底之后还能再往上拖出空白」这种错眼睛很难发现。
 *
 * 版式相关的常量留在 [PersonaTodayView]；这里只放**跟数据有关**的规则。
 */
object PersonaTodayModel {

    /** 时间线上一行的状态。样式由 View 决定，状态由这里决定 */
    enum class RowState {
        /** 已经过去的格子：整行压暗 */
        PAST,

        /** 此刻所在的格子：青色光带 + 品红左边线 */
        NOW,

        /** 还没到的格子 */
        NEXT
    }

    /**
     * 某一格此刻是什么状态。
     *
     * 「现在」用**左闭右开**判定：22:00 那一刻属于 22:00–22:40 这一格，
     * 不属于上一格。整点和格尾都落在同一格里，不会出现一秒的「两格都算现在」。
     */
    fun stateOf(moment: Moment, minute: Int): RowState = when {
        minute >= moment.start && minute < moment.end -> RowState.NOW
        moment.end <= minute -> RowState.PAST
        else -> RowState.NEXT
    }

    /**
     * 当前格的下标。
     *
     * 跨午夜或数据异常（所有格子都结束了）时退回最后一格 ——
     * 返回 -1 会让初始滚动位置算到屏幕外，而「最后一格」至少是真实存在的。
     */
    fun indexAt(moments: List<Moment>, minute: Int): Int {
        val exact = moments.indexOfFirst { minute >= it.start && minute < it.end }
        if (exact >= 0) return exact
        val before = moments.indexOfLast { it.start <= minute }
        return if (before >= 0) before else 0
    }

    /** 内容比视口高出的部分，也就是滚动位置的上限。内容装得下时是 0（不许滚） */
    fun maxScroll(contentHeight: Float, viewportHeight: Float): Float =
        (contentHeight - viewportHeight).coerceAtLeast(0f)

    /**
     * 初始滚动位置：让「当前格」落在视口正中间，然后夹进 `[0, maxScroll]`。
     *
     * 为什么要居中而不是顶格：这一页打开时最该看到的是「现在」，
     * 而上面还压着「接下来」和明天卡；把当前格放在正中，上下两边的余量一样多，
     * 用户往哪个方向滑都是同一个动作距离。
     *
     * 凌晨 / 深夜这种「当前格在最前 / 最后」的情况会被夹住 ——
     * 直接居中会算出一个负数或者超出末尾，画出来是一片空白。
     */
    fun initialScroll(
        index: Int, rowHeight: Float, viewportHeight: Float, contentHeight: Float
    ): Float {
        if (rowHeight <= 0f || viewportHeight <= 0f) return 0f
        val centered = index * rowHeight - (viewportHeight - rowHeight) / 2f
        return centered.coerceIn(0f, maxScroll(contentHeight, viewportHeight))
    }

    /** 24 小时色带上的一段：从 [start] 分钟到 [end] 分钟，宽度就是两个的差 */
    data class Segment(val start: Int, val end: Int, val kind: Kind) {
        val minutes: Int get() = (end - start).coerceAtLeast(0)
    }

    /**
     * 整天的色带分段。
     *
     * 时间轴在 [com.yxz.timetable.data.TimelineEngine] 里已经被保证「首尾相接、
     * 无空洞、无重叠」，这里仍然做一次裁剪：分段可能跨出 0..1440
     * （有的作息模板最后一段写着 24:00 之后），超出画布的部分不该被画到条外。
     */
    fun segments(moments: List<Moment>, dayMinutes: Int = 1440): List<Segment> {
        val out = ArrayList<Segment>(moments.size)
        for (m in moments) {
            val s = m.start.coerceIn(0, dayMinutes)
            val e = m.end.coerceIn(0, dayMinutes)
            if (e > s) out += Segment(s, e, m.kind)
        }
        return out
    }

    /**
     * 时段性质 → 色带颜色。
     *
     * 色带只有 10dp 高，颜色必须**一眼分得开**：睡觉接近黑、上课品红、自习青、
     * 吃饭橙、其余几种「杂事」用几档灰蓝。TRAIN（训练）原稿没有，
     * 补一档橙色 —— 训练日和上课日的时间表差很多，这一条不该和 MEAL 撞色。
     */
    fun kindColor(kind: Kind): Int = when (kind) {
        Kind.SLEEP -> 0xFF071A5C.toInt()
        Kind.STUDY -> 0xFF18D8F8.toInt()
        Kind.CLASS -> 0xFFFF3FD8.toInt()
        Kind.MEAL -> 0xFFFFC44D.toInt()
        Kind.FREE -> 0xFF9FB6D9.toInt()
        Kind.CHORE -> 0xFF7C8FB5.toInt()
        Kind.TRANSIT -> 0xFF4D6FA8.toInt()
        Kind.TRAIN -> 0xFFFF8A3D.toInt()
    }
}
