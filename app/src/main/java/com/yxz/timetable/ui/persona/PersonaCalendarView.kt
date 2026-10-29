package com.yxz.timetable.ui.persona

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.yxz.timetable.data.Festivals
import com.yxz.timetable.data.Lunar
import java.time.LocalDate
import java.time.YearMonth
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Persona 3 风格的日历页（彩蛋）。
 *
 * ## 为什么整页自绘，而不是拼 XML
 *
 * P3 的日历不是「表格」：没有格线，日期浮在水彩蓝底上，今天是一块撕下来的粉纸，
 * 白纸是从右上斜切下来的一大片弧。这些东西用 LinearLayout + background 拼出来
 * 会有三个问题 —— 弧线要 9-patch、撕纸边要矢量、每个日期都要单独套一层背景。
 * 自绘反而更短：一份 [onDraw] 里画完，尺寸变化（横竖屏、大屏小屏）只是几个比例。
 *
 * 版式常量全部按 dp 写，和 `docs/persona3/mock_calendar.py` 里那张效果图一一对应，
 * 改那边记得同步这边。
 *
 * ## 和普通日历的关系
 *
 * 这里**只负责画和点**，不碰数据：[yearMonth] 和 [selectedDate] 由外部给，
 * 翻月 / 点日期通过回调抛回去（[onMonthStep]、[onDayClick]）。这样
 * [com.yxz.timetable.ui.CalendarFragment] 里那套「月份状态 + 农历 + 节日」
 * 逻辑可以原样复用，彩蛋只是换了层皮。
 *
 * ## 农历为什么不在 onDraw 里算
 *
 * [Lunar.of] 每次要 new 一个 ICU 的 `ChineseCalendar`，一屏 42 格就是 42 次，
 * 每帧都算会明显掉帧。所以换月时算一次、存成 [festivalDates]，绘制时只查集合。
 */
class PersonaCalendarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---------------------------------------------------------------- 对外状态

    /** 当前显示的月份。改它会自己重画。 */
    var yearMonth: YearMonth = YearMonth.now()
        set(value) {
            if (field != value) {
                field = value
                refreshMonthCache()
                invalidate()
            }
        }

    /** 选中的那一天（右侧日期条显示的是它）。 */
    var selectedDate: LocalDate = LocalDate.now()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 翻月：-1 上一月，+1 下一月。 */
    var onMonthStep: ((Int) -> Unit)? = null

    /** 点了某一天。 */
    var onDayClick: ((LocalDate) -> Unit)? = null

    // ---------------------------------------------------------------- 尺寸 / 笔

    private val density = resources.displayMetrics.density
    private val fontScale = resources.displayMetrics.scaledDensity

    /**
     * 全局比例系数，以 393×800dp（Pixel 7 那一档）为基准。
     *
     * 整页自绘的好处是「一处比例管全部」，坏处也是一样的：所有尺寸都写成了
     * 固定 dp，换到 320dp 宽的小屏或 480dp 的大屏上，标题会顶到翻月按钮、
     * 数字会挤出格子。所以这里按屏幕算一个 k，[dp] 和 [sp] 都乘上它 ——
     * 等价于「整套版式等比缩放」，而不是逐个元素去适配。
     *
     * 取宽、高两个比例的**较小者**：窄屏和矮屏都该整体缩小，
     * 取大的那个会让另一维溢出。上下限是为了不让超小屏糊成一团、
     * 也不让平板上的日历变成一幅小画。
     */
    private var k = 1f

    private fun dp(v: Float) = v * density * k
    private fun sp(v: Float) = v * fontScale * k

    /** 单位和 k 无关的 dp，只用来算 k 本身。 */
    private fun rawDp(v: Float) = v * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val kW = w / rawDp(393f)
        val kH = h / rawDp(800f)
        k = minOf(kW, kH).coerceIn(0.78f, 1.35f)
    }

    /** 重体（标题 / 数字）。Android 自带 Roboto Condensed，倾斜靠 textSkewX。 */
    private val heavyFace: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    /** 中文 / 说明文字。 */
    private val textFace: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = heavyFace
        isSubpixelText = true
    }

    // ---------------------------------------------------------------- 配色

    private val cInk = 0xFF010D64.toInt()
    private val cRed = 0xFFF01030.toInt()
    private val cMagenta = 0xFFFF3FD8.toInt()
    private val cCyan = 0xFF18D8F8.toInt()
    private val cCyanBar = 0xFF48F8F8.toInt()
    private val cGold = 0xFF908838.toInt()
    private val cPaper = 0xFFEAF6FF.toInt()
    private val cGray = 0xFF969CA2.toInt()
    private val cGuide = 0xFF5A6E82.toInt()

    private val bgTop = 0xFF1EA7FE.toInt()
    private val bgMid = 0xFF0E49BE.toInt()
    private val bgDeep = 0xFF0524B1.toInt()

    /** 页面蓝底：和闹钟页 / 今天页同一张手绘天空，见 [drawBackdrop] */
    private val backdrop = PersonaBackdropCache(com.yxz.timetable.R.drawable.bg_alarm_page)

    // ---------------------------------------------------------------- 版式（dp）

    private val pad = 14f
    private val gridTop = 122f
    private val rowCount = 6

    private val weekdayNames = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    private val weekdayCn = arrayOf("一", "二", "三", "四", "五", "六", "日")

    // ---------------------------------------------------------------- 命中的矩形

    private val prevRect = RectF()
    private val nextRect = RectF()
    private val btnSize = 28f
    private val btnGap = 6f

    private var downX = 0f
    private var downY = 0f

    // ---------------------------------------------------------------- 每帧算出来的几何

    private var cellW = 0f
    private var rowH = 0f

    /** 白纸在左右两端的高度。左低右高，但两端都在网格下方。 */
    private var paperLeftY = 0f
    private var paperRightY = 0f

    // ---------------------------------------------------------------- 今天纸片的抖动

    /**
     * 三个顶点各自的偏移，按「dx0, dy0, dx1, dy1, dx2, dy2」排，单位 dp。
     *
     * 为什么不用 ValueAnimator：这里要的不是「从 A 动到 B」，而是**一直缓慢地飘**。
     * 做法是每隔 [JITTER_TICK_MS] 让每个顶点有概率换一个随机目标，
     * 然后朝目标插值一小步 —— 中途换目标都是平滑的，看起来才像纸片被风带着抖，
     * 而不是逐帧随机跳。
     */
    private val jitter = FloatArray(6)
    private val jitterTo = FloatArray(6)
    private val random = Random()
    private var jitterRunning = false

    private val jitterAmp = 3.0f

    private val jitterTick = object : Runnable {
        override fun run() {
            // 看不见就停 —— 别让一个 72ms 的定时器在后台一直唤醒
            if (!isAttachedToWindow || !isShown) {
                jitterRunning = false
                return
            }
            for (i in 0 until 6) {
                // 只有一部分一拍会换目标：换得越少，整体动得越慢
                if (random.nextFloat() < 0.32f) {
                    jitterTo[i] = (random.nextFloat() * 2f - 1f) * jitterAmp
                }
                jitter[i] += (jitterTo[i] - jitter[i]) * 0.22f
            }
            invalidate()
            postDelayed(this, JITTER_TICK_MS)
        }
    }

    /** 本月里有节日的那些天。换月时算一次，见 [refreshMonthCache]。 */
    private var festivalDates: Set<LocalDate> = emptySet()

    init {
        refreshMonthCache()
    }

    /**
     * 把本月的「有节日」标出来。
     *
     * 只算本月内的日子：上下月补白的格子压得很暗，上面再点个小圆点也看不见。
     */
    private fun refreshMonthCache() {
        val ym = yearMonth
        val found = HashSet<LocalDate>(8)
        var d = ym.atDay(1)
        val last = ym.atEndOfMonth()
        while (!d.isAfter(last)) {
            if (Festivals.of(d, Lunar.of(d)) != null) found.add(d)
            d = d.plusDays(1)
        }
        festivalDates = found
    }

    /** 本月的 1 号是星期几（周日 = 0），也就是网格第一行要往前补几天。 */
    private fun leadingBlanks(ym: YearMonth): Int = ym.atDay(1).dayOfWeek.value % 7

    // ================================================================ 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        cellW = (w - dp(pad) * 2) / 7f

        // 网格占上方 56%，但行高要夹住：太矮的屏幕上不能把 6 行压成一条线，
        // 太高的屏幕上也不能让一行高得离谱（数字会漂在一大片空地里）。
        val gridTopPx = dp(gridTop)
        rowH = ((h * 0.56f - gridTopPx) / rowCount)
            .coerceIn(dp(40f), dp(84f))
        val gridBottom = gridTopPx + rowH * rowCount

        // 白纸：左端低、右端高（保留原图那条弧的走向），但整体压在网格下面。
        // 下限 [dp(168f)] 是给底部信息区留的最低高度 —— 屏幕再矮也不能把
        // 日期条和 Guide 挤到一起。
        val paperFloor = h - dp(168f)
        paperLeftY = minOf(gridBottom + dp(78f), paperFloor)
        paperRightY = minOf(gridBottom + dp(16f), paperFloor)

        drawBackdrop(canvas, w, h)
        drawPaper(canvas, w, h)
        drawHeaderStrip(canvas, w)
        drawTitle(canvas)
        drawWeekdayRow(canvas)
        drawDates(canvas)
        drawInfoPanel(canvas, w, h)
    }

    /**
     * 蓝色水彩底。
     *
     * **实际铺的是闹钟页那张手绘天空**（`R.drawable.bg_alarm_page`：渐变 + 云带 + 顶部压暗），
     * 和今天页、闹钟页共用同一份资源 —— 一个 App 里只能有一块蓝，否则页签切换时底色会跳。
     *
     * 下面那段「三段渐变 + 两团柔光」保留成兜底：万一 drawable 解码失败（主题资源被裁、
     * 或以后有人把 jpg 删了），日历页至少还有一块像样的蓝，而不是纯黑。
     */
    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        if (backdrop.draw(context, canvas, w, h)) return

        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(bgTop, bgMid, bgDeep),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)

        fill.shader = RadialGradient(
            w * 0.34f, h * 0.44f, w * 0.62f,
            intArrayOf(Color.argb(110, 1, 13, 100), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)

        fill.shader = RadialGradient(
            w * 0.78f, -h * 0.06f, w * 0.58f,
            intArrayOf(Color.argb(46, 150, 225, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    /** 那块从右上斜切下来的白纸。 */
    private fun drawPaper(canvas: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(0f, paperLeftY)
            cubicTo(
                w * 0.34f, paperLeftY - dp(8f),
                w * 0.66f, paperRightY + dp(14f),
                w, paperRightY
            )
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        fill.shader = null
        fill.color = Color.WHITE
        canvas.drawPath(path, fill)
    }

    /** 星期表头下面那条白纸带子（边是撕的）。 */
    private fun drawHeaderStrip(canvas: Canvas, w: Float) {
        val top = dp(90f)
        val bottom = dp(114f)
        val left = dp(9f)
        val right = w - dp(9f)
        val jagTop = floatArrayOf(0f, -1.6f, 1.2f, 0.4f, -0.8f, 1.6f, 0f, -1.2f, 0.8f, 0f)
        val jagBottom = floatArrayOf(0f, 1.2f, -0.8f, 1.6f, 0f, -1.4f, 0.6f, 0f, 1.4f, -0.6f)
        val n = 14
        val path = Path()
        for (i in 0 until n) {
            val x = left + (right - left) * i / (n - 1)
            val y = top + dp(jagTop[i % jagTop.size])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in n - 1 downTo 0) {
            val x = left + (right - left) * i / (n - 1)
            val y = bottom + dp(jagBottom[i % jagBottom.size])
            path.lineTo(x, y)
        }
        path.close()
        fill.shader = null
        fill.color = Color.argb(150, 255, 255, 255)
        canvas.drawPath(path, fill)
    }

    /** CALENDAR + 年份 + 右上角翻月按钮。 */
    private fun drawTitle(canvas: Canvas) {
        text.typeface = heavyFace
        text.textAlign = Paint.Align.LEFT
        text.textSkewX = -0.22f
        text.textSize = sp(40f)

        // 描边：先画一圈深墨蓝，再把白字盖上去。
        // 注意 STROKE 的宽度是「以笔画为中心」的，所以这里给的是效果图（外扩 1.6dp）
        // 的两倍，肉眼才对得上
        text.style = Paint.Style.STROKE
        text.strokeWidth = dp(3.2f)
        text.color = cInk
        canvas.drawText("CALENDAR", dp(pad), dp(58f), text)

        text.style = Paint.Style.FILL
        text.color = Color.WHITE
        canvas.drawText("CALENDAR", dp(pad), dp(58f), text)

        text.textSize = sp(12f)
        text.style = Paint.Style.STROKE
        text.strokeWidth = dp(1.6f)
        text.color = cInk
        canvas.drawText(yearMonth.year.toString(), dp(pad + 150f), dp(74f), text)
        text.style = Paint.Style.FILL
        text.color = Color.WHITE
        canvas.drawText(yearMonth.year.toString(), dp(pad + 150f), dp(74f), text)

        text.textSkewX = 0f
        drawStepButtons(canvas)
    }

    private fun drawStepButtons(canvas: Canvas) {
        val size = dp(btnSize)
        val y = dp(18f)
        val right = width - dp(pad)
        val nextLeft = right - size
        val prevLeft = nextLeft - dp(btnGap) - size
        prevRect.set(prevLeft, y, prevLeft + size, y + size)
        nextRect.set(nextLeft, y, nextLeft + size, y + size)

        stroke.color = Color.WHITE
        stroke.strokeWidth = dp(1.3f)
        val r = dp(3f)
        canvas.drawRoundRect(prevRect, r, r, stroke)
        canvas.drawRoundRect(nextRect, r, r, stroke)

        text.style = Paint.Style.FILL
        text.typeface = heavyFace
        text.textSize = sp(17f)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        drawCentered(canvas, "‹", prevRect.centerX(), prevRect.centerY())
        drawCentered(canvas, "›", nextRect.centerX(), nextRect.centerY())
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawWeekdayRow(canvas: Canvas) {
        text.typeface = heavyFace
        text.textSize = sp(12.5f)
        text.textAlign = Paint.Align.LEFT
        text.style = Paint.Style.FILL

        // 今天的星期名压在一块白纸标签上、字换成深墨蓝 —— 原图里 THU 那个样子
        val today = LocalDate.now()
        val todayCol = if (YearMonth.from(today) == yearMonth) today.dayOfWeek.value % 7 else -1

        for (i in 0 until 7) {
            val left = dp(pad) + i * cellW + dp(2f)
            if (i == todayCol) {
                fill.color = Color.argb(240, 255, 255, 255)
                canvas.drawPath(tagPath(left - dp(3f), dp(90f), left + cellW - dp(3f), dp(114f)), fill)
            }
            text.color = when {
                i == todayCol -> cInk
                i == 0 -> cRed
                i == 6 -> cCyan
                else -> Color.WHITE
            }
            canvas.drawText(weekdayNames[i], left, dp(108f), text)
        }
    }

    /** 一块边缘是撕的纸标签（今天那一列的星期名压在上面）。 */
    private fun tagPath(left: Float, top: Float, right: Float, bottom: Float): Path {
        val jag = floatArrayOf(0f, 1.0f, -0.6f, 1.4f, 0f, -1.0f, 0.6f)
        val path = Path()
        val n = 6
        for (i in 0 until n) {
            val x = left + (right - left) * i / (n - 1)
            val y = top + dp(jag[i % jag.size])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.lineTo(right, bottom)
        path.lineTo(left, bottom)
        path.close()
        return path
    }

    private fun drawDates(canvas: Canvas) {
        val ym = yearMonth
        val start = ym.atDay(1).minusDays(leadingBlanks(ym).toLong())
        val today = LocalDate.now()

        for (r in 0 until rowCount) {
            for (c in 0 until 7) {
                val date = start.plusDays((r * 7 + c).toLong())
                val cx = dp(pad) + c * cellW + cellW / 2f
                val cy = dp(gridTop) + r * rowH + rowH / 2f
                val inMonth = YearMonth.from(date) == ym

                if (date == today) {
                    drawToday(canvas, date, cx, cy)
                    continue
                }

                text.typeface = heavyFace
                text.textSize = sp(23f)
                text.textAlign = Paint.Align.CENTER
                text.style = Paint.Style.FILL
                text.color = when (c) {
                    0 -> cRed
                    6 -> cCyan
                    else -> cPaper
                }
                text.alpha = if (inMonth) 255 else 84
                drawCentered(canvas, date.dayOfMonth.toString(), cx, cy - dp(2f))
                text.alpha = 255

                if (inMonth && festivalDates.contains(date)) {
                    fill.color = Color.argb(235, 255, 255, 255)
                    canvas.drawCircle(cx, cy + dp(15f), dp(1.7f), fill)
                }

                if (inMonth && date == selectedDate) {
                    stroke.color = cGold
                    stroke.strokeWidth = dp(1.4f)
                    canvas.drawCircle(cx, cy, dp(19f), stroke)
                }
            }
        }
    }

    /**
     * 今天：一块三角形纸片 + 上方 TODAY + 黑色数字。
     *
     * 形状是从游戏截图里量出来的 —— 它就是**三角形**，三条直边、右下和左下各一个
     * 尖角，不是「撕纸矩形」。一开始我按撕纸做（四边加毛刺），真机上是一团
     * 圆乎乎的粉色团块，和原图完全不像。
     *
     * 三个顶点按包围盒的归一化坐标：顶点 (0.54, 0)、右尖角 (1.00, 0.67)、
     * 左下尖角 (0.13, 0.95)；数字中心落在 (0.54, 0.37)。
     *
     * 左下角原本是 (0.00, 1.00)（一直伸到包围盒最左最下），实际看着尾巴太长、
     * 还会压到左边一格的数字，所以往里收了一截。
     *
     * 三个顶点各自带一份 [jitter] 偏移 —— 游戏里这块纸片一直在极轻微地飘，
     * 幅度只有几 dp，但慢下来能看出来。抖动只作用于纸片，数字和 TODAY 不动。
     */
    private fun drawToday(canvas: Canvas, date: LocalDate, cx: Float, cy: Float) {
        // 尺寸跟着行高走：行高在不同机器上差得多（我这套稿子按 56dp 画，
        // 真机常见 50dp 上下），纸片写死会让它在大行高的机器上偏小、在小行高的
        // 机器上又大又低。原图里纸片高度 ≈ 行距的 1.06 倍，宽高比 1.32。
        val shardH = rowH * 1.06f
        val shardW = shardH * 1.32f

        // 数字和其他日期**同一个中心、同一个字号** —— 这是「22 和 21、23 对齐」的唯一做法
        val numberCenterY = cy - dp(2f)

        // 纸片挂在数字下方：原图里数字中心大约在纸片高度 37% 处。
        // 实际用下来 0.37 偏低，改成 0.42 —— 纸片整体上移约 5% 高度（≈3dp），
        // 数字在纸片里的位置不变，因为数字是独立按行中心画的。
        val left = cx - shardW / 2f
        val top = numberCenterY - shardH * 0.42f

        val path = Path().apply {
            moveTo(left + shardW * 0.54f + dp(jitter[0]), top + dp(jitter[1]))           // 顶点
            lineTo(left + shardW + dp(jitter[2]), top + shardH * 0.67f + dp(jitter[3]))  // 右尖角
            lineTo(
                left + shardW * 0.13f + dp(jitter[4]),
                top + shardH * 0.95f + dp(jitter[5])
            )                                                                            // 左下尖角
            close()
        }
        fill.color = cMagenta
        canvas.drawPath(path, fill)

        // TODAY 压在纸片身上（原图就是这样：T/O 在蓝底上，D/A/Y 压在粉色上，
        // 顶点从字母中间探出来），所以基线贴着数字的顶端往回退 1dp，
        // 而不是「浮在纸片上方 N dp」—— 后者会随行高变化飘走。
        text.typeface = heavyFace
        text.textAlign = Paint.Align.CENTER
        text.style = Paint.Style.FILL
        text.textSize = sp(23f)
        val capPx = text.textSize * 0.72f      // 大写的视觉高度，用来定位数字顶端
        val numberTop = numberCenterY - capPx / 2f

        text.textSize = sp(7.5f)
        text.color = Color.WHITE
        val fm = text.fontMetrics
        canvas.drawText("TODAY", cx, numberTop - dp(1.5f) - fm.descent, text)

        text.textSize = sp(23f)
        text.color = Color.BLACK
        drawCentered(canvas, date.dayOfMonth.toString(), cx, numberCenterY)
    }

    /**
     * 白纸上的日期条 + 两条说明 + 右下角斜体 Guide + 白纸右侧的大号日期。
     *
     * ⚠️ 这个函数里画笔是**借来的**（同一个 [text] 反复改字号 / 颜色 / 对齐），
     * 所以每段文字之前都必须自己把状态设全 —— 少设一个字号，后面那段就会
     * 按上一段的字号画（Guide 被画成 132sp、占满半屏，就是这么来的）。
     */
    private fun drawInfoPanel(canvas: Canvas, w: Float, h: Float) {
        val date = selectedDate

        // 面板有多高决定能放几样东西：矮屏上先砍农历那行、再砍大号日期，
        // 保住「日期条 + 一条说明 + Guide」这三样最要紧的
        val panelH = h - paperLeftY
        val compact = panelH < dp(160f)

        val barTop = paperLeftY + dp(18f)
        val barH = dp(26f)
        val barW = dp(158f)

        fill.color = cCyanBar
        canvas.drawRect(dp(20f), barTop, dp(20f) + barW, barTop + barH, fill)

        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = sp(14.5f)
        text.textAlign = Paint.Align.LEFT
        text.style = Paint.Style.FILL
        text.color = cInk
        val weekdayChar = weekdayCn[(date.dayOfWeek.value - 1).coerceIn(0, 6)]
        drawCenteredLeft(
            canvas,
            "${date.monthValue}月${date.dayOfMonth}日（$weekdayChar）",
            dp(30f), barTop + barH / 2f
        )

        val lunar = Lunar.of(date)
        val festival = Festivals.of(date, lunar)
        text.typeface = textFace
        text.textSize = sp(12f)
        text.color = cGray
        drawCenteredLeft(canvas, "· " + (festival ?: "没什么特别的事"), dp(28f), barTop + dp(46f))
        if (!compact) {
            drawCenteredLeft(
                canvas, "· 农历${lunar.monthName}${lunar.dayName}", dp(28f), barTop + dp(68f)
            )
        }


        // 白纸上那个巨号日期 —— 原图里「5」就是骑在白纸上的，
        // 竖屏把整个网格铺满宽度后，只有这块白纸还有地方放得下它。
        // 字号按「日期条下沿到 Guide 上沿」的可用高度算；空间不够就不画，
        // 而不是硬塞一个压住 Guide 的大字（矮屏上会糊成一团）。
        val numberTop = barTop + dp(50f)
        val guideY = h - dp(46f)
        val room = guideY - numberTop - dp(14f)
        if (room > dp(36f)) {
            text.typeface = heavyFace
            text.textSize = (room / 1.16f).coerceIn(sp(44f), sp(150f))
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.argb(238, 1, 13, 100)
            val fm = text.fontMetrics
            canvas.drawText(date.dayOfMonth.toString(), w - dp(16f), numberTop - fm.ascent, text)
        }

        // Guide：字号必须自己设回来，绝不能沿用上面那段的
        val guide = "要查看什么时候的日程安排？"
        text.typeface = textFace
        text.textSize = sp(12f)
        text.color = cGuide
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(guide, w - dp(22f), guideY, text)
        val gw = text.measureText(guide)
        stroke.color = cGuide
        stroke.strokeWidth = dp(0.5f)
        canvas.drawLine(w - dp(22f) - gw, guideY + dp(3f), w - dp(22f), guideY + dp(3f), stroke)

        text.typeface = heavyFace
        text.textSize = sp(7f)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("Guide", w - dp(22f), guideY + dp(12f), text)
        text.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- 小工具

    /** 以 (cx, cy) 为视觉中心画一行字（不依赖基线）。 */
    private fun drawCentered(canvas: Canvas, s: String, cx: Float, cy: Float) {
        val fm = text.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(s, cx, baseline, text)
    }

    private fun drawCenteredLeft(canvas: Canvas, s: String, x: Float, cy: Float) {
        val fm = text.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(s, x, baseline, text)
    }

    // ================================================================ 手势

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > dp(60f) && abs(dx) > abs(dy) * 1.5f) {
                    // 横滑翻月：向左滑 = 下个月
                    onMonthStep?.invoke(if (dx < 0) 1 else -1)
                    performClick()
                    return true
                }
                when {
                    prevRect.contains(event.x, event.y) -> onMonthStep?.invoke(-1)
                    nextRect.contains(event.x, event.y) -> onMonthStep?.invoke(1)
                    else -> cellAt(event.x, event.y)?.let { onDayClick?.invoke(it) }
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 屏幕坐标落在哪一天（只认本月的那几格，点空白不处理）。 */
    private fun cellAt(x: Float, y: Float): LocalDate? {
        if (y < dp(gridTop) || y > dp(gridTop) + rowH * rowCount) return null
        val col = ((x - dp(pad)) / cellW).toInt()
        if (col !in 0 until 7) return null
        val row = ((y - dp(gridTop)) / rowH).toInt().coerceIn(0, rowCount - 1)
        val date = yearMonth.atDay(1)
            .minusDays(leadingBlanks(yearMonth).toLong())
            .plusDays((row * 7 + col).toLong())
        return if (YearMonth.from(date) == yearMonth) date else null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 关掉动画（省电模式 / 无障碍里的「移除动画」）时不做抖动：
        // 这种一直跑的小动画最该尊重系统的这个开关
        if (!jitterRunning && ValueAnimator.areAnimatorsEnabled()) {
            jitterRunning = true
            postDelayed(jitterTick, JITTER_TICK_MS)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(jitterTick)
        jitterRunning = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 切到别的 tab 时 View 还在（Fragment 用 show/hide），
        // 但已经看不见了 —— 这时候继续按帧重画纯属浪费电
        if (visibility == VISIBLE && isAttachedToWindow && !jitterRunning &&
            ValueAnimator.areAnimatorsEnabled()
        ) {
            jitterRunning = true
            postDelayed(jitterTick, JITTER_TICK_MS)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 没有明确高度时（比如放进 wrap_content 的容器）给一个够用的默认值
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            setMeasuredDimension(measuredWidth, dp(800f).roundToInt())
        }
    }

    private companion object {
        /** 抖动的采样间隔。70ms 左右 ≈ 14fps，对这种慢速漂移足够了，也不费电。 */
        const val JITTER_TICK_MS = 72L
    }
}
