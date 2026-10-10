package com.yxz.timetable.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.yxz.timetable.R

/**
 * 课表页周次卡片里的「近五周点阵预览」。
 *
 * 每周一个 **7 列（星期一…星期日）× N 行（第 1…N 节）** 的点阵：
 * 那一格有课 = 当前主题的强调色，无课 = 灰。五周横向排开，
 * 中间是当前浏览的周（标签染强调色）。
 *
 * 为什么是自绘 View 而不是拼小圆点 View：五周 × 7 × 13 节 ≈ 455 个格子，
 * 每个格子一个 View 就是四百多个对象 + 四十多层 LinearLayout，
 * 翻周时整卡重建会肉眼可见地卡；onDraw 画圆是一次遍历、零分配，
 * 「点阵/热力图」这类东西天生就该画出来而不是拼出来。
 *
 * 为什么五周而不是只画当前周：单看一周只知道「今天有没有课」，
 * 五周能看出**节奏** —— 哪几周是满课周、哪几周快空了。
 */
class WeekDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 一周的点阵数据 */
    class Week(
        /** 第几教学周（标签文字） */
        val label: Int,
        /** occupied[星期一..星期日 = 0..6][第 1..N 节 = 0..N-1] */
        val occupied: Array<BooleanArray>,
        /** 是否是当前浏览的周 —— 只影响标签的高亮 */
        val highlight: Boolean
    )

    private var weeks: List<Week> = emptyList()

    /** 格子边长 6dp：圆点 4dp + 2dp 缝隙，五周横排约 258dp，放得进卡片 */
    private val cell = 6f * resources.displayMetrics.density

    /** 点半径 2dp */
    private val radius = 2f * resources.displayMetrics.density

    /** 周与周之间的空隙 12dp */
    private val weekGap = 12f * resources.displayMetrics.density

    /** 点阵底边到周次标签的距离 3dp */
    private val labelGap = 3f * resources.displayMetrics.density

    private val accent = context.accentColor()

    /** 无课的灰：text_secondary 压到 35% —— 要看得见，但不能抢强调色的戏 */
    private val empty = ColorUtils.setAlphaComponent(
        ContextCompat.getColor(context, R.color.text_secondary), 0x59
    )

    private val labelColor = ContextCompat.getColor(context, R.color.text_secondary)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 8f * resources.displayMetrics.scaledDensity
    }

    fun submit(data: List<Week>) {
        weeks = data
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = weeks.firstOrNull()?.occupied?.firstOrNull()?.size ?: 0
        val cols = 7
        val w = (weeks.size * cols * cell + (weeks.size - 1).coerceAtLeast(0) * weekGap)
        val h = rows * cell + labelGap + textTextHeight()
        setMeasuredDimension(
            resolveSize(w.toInt() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(h.toInt() + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    /** 标签行高：按字体上下沿算，别按 textSize 猜 —— 中文数字混排时两者差不少 */
    private fun textTextHeight(): Float =
        textPaint.fontMetrics.let { it.descent - it.ascent }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (weeks.isEmpty()) return

        val rows = weeks.first().occupied.firstOrNull()?.size ?: return
        val matrixWidth = 7 * cell
        val matrixHeight = rows * cell
        val baseline = paddingTop + matrixHeight + labelGap - textPaint.fontMetrics.ascent

        for ((i, week) in weeks.withIndex()) {
            val x0 = paddingLeft + i * (matrixWidth + weekGap)
            for (day in 0 until 7) {
                val occupied = week.occupied[day]
                for (r in 0 until rows) {
                    dotPaint.color = if (occupied[r]) accent else empty
                    canvas.drawCircle(
                        x0 + day * cell + cell / 2,
                        paddingTop + r * cell + cell / 2,
                        radius,
                        dotPaint
                    )
                }
            }
            textPaint.color = if (week.highlight) accent else labelColor
            textPaint.isFakeBoldText = week.highlight
            canvas.drawText(
                week.label.toString(),
                x0 + matrixWidth / 2,
                baseline,
                textPaint
            )
            textPaint.isFakeBoldText = false
        }
    }
}
