package com.yxz.timetable.ui.persona

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * P3 模式的设置首页：四个分类的入口。
 *
 * ## 为什么重做
 *
 * 上一版是照游戏 SKILL / ITEM 那一屏临摹的：几个**巨号英文斜词**交错左右排开，
 * 真正有意义的中文功能名缩到词尾当小字。装饰压过了内容 —— 一屏四个词，
 * 用户第一眼看到的是 DATA / FEATURE / THEME / SYSTEM，而「数据」「功能」要凑近才认得出；
 * 加上逐项交错 + 各带一个角度，读起来像海报而不是菜单。
 *
 * 现在改成**一条纵向列表**：中文名是主标题（这是要读的字），英文缩写退成上面那行
 * 小字标签；左侧 01–04 编号，右侧一枚青色方块表示「进得去」。
 * 底色换成和今天 / 课表 / 日历同一张手绘天空 —— 设置首页是 P3 里最后一个
 * 还在用自己那套渐变的地方。
 *
 * ## 交互
 *
 * 整块自绘，点击按行带命中：按住时整行亮成深藏青块（青左边线），抬起跳页。
 */
class PersonaMenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 一个条目：英文缩写（装饰性标签）+ 中文功能名（真正要读的字） */
    data class Entry(val en: String, val zh: String)

    var entries: List<Entry> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** 点中第几项。宿主接住之后跳对应分类页 */
    var onPick: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }

    /** 背景：和今天/课表/日历同一张手绘天空，取不到时退回纯渐变 */
    private val backdrop = PersonaBackdropCache(com.yxz.timetable.R.drawable.bg_alarm_page)

    /** 按住的那一项（触摸反馈：整行变深块） */
    private var pressed = -1

    // 行几何，onDraw 里算、onTouchEvent 里用同一套
    private var listTop = 0f
    private var rowH = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hSpec = MeasureSpec.getSize(heightMeasureSpec)
        // 至少能排下四个行高：fillViewport 时父容器会给整屏，屏幕太矮时允许滚动
        val minH = (w * MIN_HEIGHT_RATIO).toInt()
        setMeasuredDimension(w, max(hSpec, minH))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (!backdrop.draw(context, canvas, w, h)) {
            fill.shader = null
            fill.color = PersonaP3.BLUE_MID
            canvas.drawRect(0f, 0f, w, h, fill)
        }

        // 顶部小标签：和今天页那个 TODAY 同一个位置语法。
        // 这里原来还有一条横贯全宽的墨蓝细线，撤掉了 —— 天空本身已经有层次，
        // 再加一道硬线只会在「SETTINGS」和列表之间切出一条没必要的分界。
        text.typeface = PersonaP3.tag
        text.textSize = dp(11f)
        text.letterSpacing = 0.34f
        text.color = PersonaP3.CYAN
        text.textAlign = Paint.Align.LEFT
        text.style = Paint.Style.FILL
        canvas.drawText("SETTINGS", dp(18f), dp(44f), text)
        text.letterSpacing = 0f

        if (entries.isEmpty()) return

        // 行高：优先按可用高度均分，但**封顶** 88dp —— 长屏上均分会把四行拉成四条
        // 巨大的空带，字全挤在每带中间、上下各空一大块，看着像排版出错。
        // 封顶之后整组贴着标题栏往下排，下面留一片天（和课表页密度条下面那块空白一致）。
        val headerBottom = dp(56f)
        val available = h - headerBottom - dp(24f)
        rowH = (available / entries.size).coerceAtMost(dp(88f))
        listTop = headerBottom + dp(8f)

        val n = entries.size
        for (i in 0 until n) {
            val top = listTop + rowH * i
            val bottom = top + rowH
            val e = entries[i]
            val isPressed = i == pressed

            if (isPressed) {
                // 按住：一行深藏青块 + 左缘青边（和底栏选中项同一套语法）
                fill.shader = null
                fill.color = 0xFF101E96.toInt()
                canvas.drawRect(dp(12f), top + dp(2f), w - dp(12f), bottom - dp(2f), fill)
                fill.color = PersonaP3.CYAN
                canvas.drawRect(dp(12f), top + dp(2f), dp(15f), bottom - dp(2f), fill)
            }

            val cy = (top + bottom) / 2f

            // 01–04：青色窄体编号
            text.typeface = PersonaP3.titleFace
            text.isFakeBoldText = true
            text.textSize = dp(13f)
            text.color = 0xCC18D8F8.toInt()
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(
                (i + 1).toString().padStart(2, '0'),
                dp(18f), cy + dp(4.5f), text
            )
            text.isFakeBoldText = false

            // 英文缩写：小字、大字距，在中文名上面
            text.typeface = PersonaP3.tag
            text.textSize = dp(9f)
            text.letterSpacing = 0.26f
            text.color = 0x99A9C3F5.toInt()
            canvas.drawText(e.en, dp(56f), cy - dp(9f), text)
            text.letterSpacing = 0f

            // 中文名：主信息，白字宽体
            text.typeface = PersonaP3.cnHeavy
            text.textSize = dp(23f)
            text.color = 0xFFFFFFFF.toInt()
            canvas.drawText(e.zh, dp(56f), cy + dp(15f), text)

            // 右侧那枚青色小方块：表示「这一项进得去」
            fill.shader = null
            fill.color = PersonaP3.CYAN
            val sq = dp(6f)
            val sqCx = w - dp(26f)
            canvas.drawRect(sqCx - sq / 2f, cy - sq / 2f, sqCx + sq / 2f, cy + sq / 2f, fill)

            // 行分隔线（最后一行不画）
            if (i < n - 1) {
                stroke.color = 0x33DCEEFF
                stroke.strokeWidth = dp(1f)
                canvas.drawLine(dp(56f), bottom, w - dp(18f), bottom, stroke)
            }
        }
    }

    // ---------------------------------------------------------------- 触摸

    /** 命中行号：按纵向行带分行 */
    private fun hit(y: Float): Int {
        val n = entries.size
        if (n == 0 || rowH <= 0f) return -1
        val i = ((y - listTop) / rowH).toInt()
        return if (i in 0 until n) i else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(event.y)
                if (pressed >= 0) invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val i = hit(event.y)
                pressed = -1
                invalidate()
                if (i >= 0) {
                    performClick()
                    onPick?.invoke(i)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** 没有整屏高度时也至少要这么高（宽 × 1.15），四个行才排得开 */
        const val MIN_HEIGHT_RATIO = 1.15f
    }
}
