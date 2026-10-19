package com.yxz.timetable.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.yxz.timetable.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 小时 / 分钟的表盘。
 *
 * ## 结构
 *
 * ```
 *            00
 *       22 ┌────┐ 02        ← 里圈：12 格，每 2 小时（分钟盘是每 5 分钟）一个数字
 *     20   │ ●  │   04      ← 中心：当前值
 *       18 └────┘ 06
 *            12             ← 外圈：24 格（分钟盘 60 格），真正可以选的那一圈
 * ```
 *
 * 外圈每格都能拖选，里圈只负责「一眼看出指针大概指到哪」。
 *
 * ## 为什么自己画
 *
 * 系统 TimePicker 的指针是**一格一格跳**的：手指从 7 划到 8，画面只会在两格上
 * 各停一下，中间那段什么都没有。而这东西是早上半梦半醒时用的，要的是
 * 「手指拖到哪，指针就在哪」。
 *
 * 所以这里把手势分成两段：
 *
 *   · **拖动中** —— [handAngle] 直接用手指的角度，**不做吸附**，一像素一像素跟手；
 *     只有「选中的那一格」和中间的数字按格变化，给人明确的落点反馈。
 *   · **松手时** —— 才用动画吸附到格子的正中（[settle]，带回弹）。
 *
 * 换句话说：连续的部分保持连续，离散的部分（选中格子）才离散。
 */
class TimeDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一圈分几格：小时 24、分钟 60 */
    var cellCount: Int = 24
        set(v) {
            if (v <= 0 || v == field) return
            field = v
            currentCell %= v
            handAngle = DialMath.angleOf(currentCell, v)
            invalidate()
        }

    /** 当前选中的值（小时 0–23 / 分钟 0–59） */
    var value: Int
        get() = currentCell
        set(v) {
            currentCell = ((v % cellCount) + cellCount) % cellCount
            handAngle = DialMath.angleOf(currentCell, cellCount)
            invalidate()
        }

    /** 中心那个大数字下面的一行小字：「时」/「分」 */
    var unit: String = "时"
        set(v) {
            field = v
            invalidate()
        }

    /** 选中的格子变了就回调（拖动经过、松手、以及外部赋值都会触发） */
    var onPick: ((Int) -> Unit)? = null

    private var currentCell = 7

    /** 指针现在的角度（度，0 = 12 点方向，顺时针）。拖动时是连续值 */
    private var handAngle = DialMath.angleOf(7, 24)

    private var settling: ValueAnimator? = null
    private var dragging = false

    private val accent = context.accentColor()
    private val lineColor = ContextCompat.getColor(context, R.color.divider)
    private val numberColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val cardColor = ContextCompat.getColor(context, R.color.card_bg)

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        color = lineColor
    }
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = alpha(accent, 0.16f)
    }
    private val wedgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        color = alpha(accent, 0.35f)
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(3.5f)
        strokeCap = Paint.Cap.ROUND
        color = accent
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = numberColor
    }

    /**
     * 数字底下的「光晕」：先按这个画一遍描边，再画字。
     *
     * 作用只有一个 —— 指针从数字底下穿过去时，数字仍然看得清。
     * 不画的话，灰字压在蓝线上就糊成一团。
     */
    private val haloPaint = Paint(numberPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(4f)
        color = cardColor
    }

    private val wedgePath = Path()

    /** 中心那个大数字。字号每帧按表盘半径算，所以画笔只建一次 */
    private val bigPaint = Paint(numberPaint).apply {
        color = accent
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val unitPaint = Paint(numberPaint)

    /** dp → px，带小数。线宽和圆点半径都要小数，UiUtil 里那个只收 Int */
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    init {
        // 拖动时要自己处理手势，不能被外面的 ScrollView / 对话框抢走
        isClickable = true
    }

    // ==================================================================
    //  画
    // ==================================================================

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - context.dp(6)
        if (r <= 0f) return

        val rOuter = r
        val rOuterIn = r * 0.74f
        val rLabel = r * 0.62f
        val rInnerIn = r * 0.50f
        val step = 360f / cellCount

        // 外圈刻度：小时盘每格一条；分钟盘 60 格太密，只在每 5 分钟处拉长
        for (i in 0 until cellCount) {
            val deg = i * step
            val major = cellCount <= 24 || i % 5 == 0
            val from = if (major) rOuterIn else (rOuterIn + rOuter) * 0.5f
            canvas.drawLine(x(cx, from, deg), y(cy, from, deg),
                x(cx, rOuter, deg), y(cy, rOuter, deg), tickPaint)
        }

        // 里圈 12 格
        for (i in 0 until 12) {
            canvas.drawLine(x(cx, rInnerIn, i * 30f), y(cy, rInnerIn, i * 30f),
                x(cx, rOuterIn, i * 30f), y(cy, rOuterIn, i * 30f), tickPaint)
        }

        // 三条圆
        canvas.drawCircle(cx, cy, rOuter, tickPaint)
        canvas.drawCircle(cx, cy, rOuterIn, tickPaint)
        canvas.drawCircle(cx, cy, rInnerIn, tickPaint)

        // 选中的那一格
        val mid = currentCell * step
        wedgePath.reset()
        val outerRect = RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
        val innerRect = RectF(cx - rOuterIn, cy - rOuterIn, cx + rOuterIn, cy + rOuterIn)
        // Canvas 的 0° 在 3 点方向，我们的 0° 在 12 点 —— 减 90
        wedgePath.arcTo(outerRect, mid - step / 2f - 90f, step, true)
        wedgePath.arcTo(innerRect, mid + step / 2f - 90f, -step, false)
        wedgePath.close()
        canvas.drawPath(wedgePath, wedgePaint)
        canvas.drawPath(wedgePath, wedgeStroke)

        // 指针：从中心数字的外侧一路到外圈
        val tipR = rOuter * 0.95f
        canvas.drawLine(x(cx, r * 0.30f, handAngle), y(cy, r * 0.30f, handAngle),
            x(cx, tipR, handAngle), y(cy, tipR, handAngle), handPaint)
        canvas.drawCircle(x(cx, tipR, handAngle), y(cy, tipR, handAngle), dpf(4.5f), knobPaint)

        // 里圈 12 个数字（带光晕，压在指针上面）
        numberPaint.textSize = r * 0.115f
        haloPaint.textSize = numberPaint.textSize
        for (i in 0 until 12) {
            val v = i * (cellCount / 12)
            val deg = i * 30f
            val tx = x(cx, rLabel, deg)
            val ty = y(cy, rLabel, deg) + numberPaint.textSize * 0.36f
            val text = "%02d".format(v)
            val active = v == currentCell
            haloPaint.color = cardColor
            canvas.drawText(text, tx, ty, haloPaint)
            numberPaint.color = if (active) accent else numberColor
            canvas.drawText(text, tx, ty, numberPaint)
        }
        numberPaint.color = numberColor

        // 中心：当前值 + 单位
        bigPaint.textSize = r * 0.26f
        canvas.drawText("%02d".format(currentCell), cx, cy + bigPaint.textSize * 0.34f, bigPaint)

        unitPaint.textSize = r * 0.10f
        canvas.drawText(unit, cx, cy + bigPaint.textSize * 0.34f + r * 0.19f, unitPaint)
    }

    private fun x(cx: Float, r: Float, deg: Float): Float =
        cx + r * cos(Math.toRadians((deg - 90f).toDouble())).toFloat()

    private fun y(cy: Float, r: Float, deg: Float): Float =
        cy + r * sin(Math.toRadians((deg - 90f).toDouble())).toFloat()

    // ==================================================================
    //  拖
    // ==================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 一按下去就锁住手势：否则父容器（对话框的滚动区）会在手指
                // 上下移动时把事件抢走，指针跟到一半就断了
                parent?.requestDisallowInterceptTouchEvent(true)
                settling?.cancel()
                dragging = true
                follow(event.x, event.y, cx, cy)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                follow(event.x, event.y, cx, cy)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                settle()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 手指在哪，指针就在哪 —— 这里**不吸附**，一格一格的只是「选中」 */
    private fun follow(px: Float, py: Float, cx: Float, cy: Float) {
        val deg = DialMath.angleOfTouch(px, py, cx, cy)
        handAngle = deg
        val cell = DialMath.cellAt(deg, cellCount)
        if (cell != currentCell) {
            currentCell = cell
            // 一格一次轻震：手感上的「咔哒」，但不影响指针的连续转动
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onPick?.invoke(cell)
        }
        invalidate()
    }

    /** 松手：动画吸附到格子正中，带一点回弹（免得看起来像硬切过去） */
    private fun settle() {
        val target = DialMath.angleOf(currentCell, cellCount)
        val from = handAngle
        val delta = DialMath.shortestDelta(from, target)
        if (abs(delta) < 0.05f) {
            handAngle = target
            invalidate()
            return
        }
        settling?.cancel()
        settling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener {
                handAngle = from + delta * (it.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    handAngle = target
                    invalidate()
                }
            })
            start()
        }
    }

    /** 外部换了值（切到分钟盘）时，别让上一轮的吸附动画继续跑 */
    fun cancelAnimation() {
        settling?.cancel()
        settling = null
        dragging = false
    }

    override fun onDetachedFromWindow() {
        settling?.cancel()
        settling = null
        super.onDetachedFromWindow()
    }

    private fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or (((a * 255).toInt().coerceIn(0, 255)) shl 24)
}

/**
 * 表盘的几何：**不碰 Android**，所以能在电脑上直接测。
 *
 * 约定：0° 在 12 点方向，顺时针为正 —— 和 Canvas 的约定（0° 在 3 点）差 90°，
 * 两者的换算只在 [TimeDialView] 画图那一层做。
 */
internal object DialMath {

    /** 值 → 指针角度 */
    fun angleOf(value: Int, cells: Int): Float = 360f * value / cells

    /** 手指角度 → 最近的那一格（跨 0°/360° 的接缝也算得对） */
    fun cellAt(deg: Float, cells: Int): Int {
        var d = deg % 360f
        if (d < 0) d += 360f
        val step = 360f / cells
        return (((d + step / 2f) / step).toInt()) % cells
    }

    /** 触摸点 → 角度（0 = 12 点，顺时针） */
    fun angleOfTouch(x: Float, y: Float, cx: Float, cy: Float): Float {
        var deg = Math.toDegrees(atan2((x - cx).toDouble(), (cy - y).toDouble())).toFloat()
        if (deg < 0) deg += 360f
        return deg
    }

    /**
     * 从 [from] 转到 [to] 的**最短**路程（可能是负数）。
     *
     * 吸附动画必须走这条：350° → 10° 如果按绝对值插值，指针会倒着扫过
     * 大半个表盘；按最短路径只走 20°。
     */
    fun shortestDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
