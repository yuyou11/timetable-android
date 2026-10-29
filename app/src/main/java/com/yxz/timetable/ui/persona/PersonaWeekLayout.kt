package com.yxz.timetable.ui.persona

/**
 * P3 课表页的纵向算术：**一行多高、能滚多远**。
 *
 * 单独拎出来是因为它是纯数：没有 Canvas、没有 View、不碰任何 Android 类型，
 * 所以能像 [PersonaTodayModel] 一样在电脑上单测 —— 而这类错误恰好是「不崩」的：
 * 算小了只是字挤成一团、算大了只是滚不到底，装个 APK 用眼睛很难发现。
 *
 * ## 规则（和 [PersonaWeekView] 的版式一一对应）
 *
 * - 表头下方到近五周密度条之间的那段高度是「可用高度」，**一行 = 可用高度 ÷ 节数**，
 *   刚好铺满，不多留白；
 * - 节数超过 [MAX_FIT_ROWS]（17）时不再往下压行高（再压 13pt 的字就放不下了），
 *   行高锁在「17 行刚好铺满」的值，整张表改成上下滑；
 * - 密度条钉在屏幕最下面，所以它**不参与**滚动，可滚的只有网格区。
 */
internal object PersonaWeekLayout {

    /**
     * 最多压到这么多行：超过就不再缩行高，改为整表上下滑。
     *
     * 17 这个数是两条约束的交点：常见学校是 10~13 节（含晚上 3~5 节），
     * 17 行在 430dp 左右的网格区里还有 25dp 一行，两行课名放得下；
     * 再往下（20 行 = 21dp）课名和教室就会叠在一起，不如让人自己滑。
     */
    const val MAX_FIT_ROWS = 17

    /**
     * 一行的高度（px）。
     *
     * @param availablePx 网格区可视高度（表头下沿到密度条上沿，已扣掉间距）
     * @param rows 当天总节数（来自 `periods`，至少 1）
     */
    fun rowHeight(availablePx: Float, rows: Int): Float {
        val n = rows.coerceAtLeast(1)
        val divisor = if (n > MAX_FIT_ROWS) MAX_FIT_ROWS else n
        return (availablePx.coerceAtLeast(0f) / divisor)
    }

    /**
     * 整表比可视区高出来的那一截（px）。返回 0 表示装得下、不需要滚动。
     *
     * 调用方拿它当 `scrollY` 的上界：滑到 [scrollRange] 时最后一行刚好贴住网格区下沿。
     */
    fun scrollRange(availablePx: Float, rows: Int, rowHeightPx: Float): Float {
        val contentH = rowHeightPx * rows.coerceAtLeast(1)
        return (contentH - availablePx.coerceAtLeast(0f)).coerceAtLeast(0f)
    }
}
