package com.yxz.timetable.ui.persona

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import com.yxz.timetable.data.Course
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.ui.WeekDotsView
import com.yxz.timetable.ui.WeekGrid
import kotlin.math.abs
import kotlin.math.tan

/** 一个节次的钟点（第 [node] 节从 [start] 到 [end] 分钟）。时刻来源是模板占位格，不是写死的表 */
data class SlotClock(val node: Int, val start: Int, val end: Int)

/**
 * P3 版课表页（整页自绘），照 `p3web/week-p3-v2.html` 一比一搬过来。
 *
 * ## 为什么是整页重画，而不是给原网格换皮
 *
 * 原课表页是 [com.yxz.timetable.ui.WeekFragment] 里一行行 LinearLayout 拼的：
 * 固定行高、1dp 分隔线、今天整列淡底。换皮能改颜色，但改不了这几件事：
 *
 *   · 原稿里**没有竖格线**，课是浮在蓝底上的玻璃块 —— 留着格线就不是 P3 的样子；
 *   · 今天不是一格淡底，是一条**青色光柱**，表头上还钉着一条品红胶带；
 *   · 巨号 `WEEK` + 幽灵周次压在标题后面，周次选择收进标题区（两个键帽）；
 *   · 近五周点阵从顶部卡片挪到底部一条密度条。
 *
 * 这些都不是「改颜色」，所以只能自绘。原网格仍然保留（默认外观走它），
 * 两者按 `Store.p3Mode` 二选一挂载 —— 和日历页同一套做法。
 *
 * ## 数据从哪来
 *
 * 这个 View **不碰数据**：周次、课程、节次、点阵都由
 * [com.yxz.timetable.ui.WeekFragment] 算好喂进来（[submit]）。翻周和点格子通过回调
 * 抛回去 —— 翻周要改写 `weekOffset`、点格子要弹 `SlotCoursesCard`，本来就归 Fragment 管。
 *
 * ## 坐标
 *
 * 画布 393×720dp 为 1.0（网页样稿是 393×800，扣掉顶部状态栏和底部导航栏）。
 * 所有常量都写设计稿里的数，画的时候乘 `k`（见 [PersonaPageView]）。
 */
class PersonaWeekView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : PersonaPageView(context, attrs, defStyleAttr) {

    // ================================================================ 对外状态

    private var week = 1
    private var todayDay: Int? = null
    private var slots: List<SlotClock> = emptyList()
    private var courses: List<Course> = emptyList()
    private var densityWeeks: List<WeekDotsView.Week> = emptyList()
    private var meta: List<TextRun> = emptyList()
    private var rangeText = ""
    private var dotsEnabled = true

    /** 翻周：-1 上一周、+1 下一周 */
    var onWeekStep: ((Int) -> Unit)? = null

    /**
     * 点了一格（空格子也算）。
     *
     * [pivot] 是**屏幕坐标**下这一格的矩形 —— 弹卡的放大动画要以它为圆心。
     * 传坐标而不是传 View：这个页面里没有「每格一个 View」，只有一份 onDraw 和一张命中表。
     */
    var onSlotClick: ((day: Int, nodes: IntRange, pivot: RectF) -> Unit)? = null

    /**
     * 喂数据。
     *
     * 课表页会在 `onResume` / `onHiddenChanged` 时无条件调它 —— 内容没变就别重画
     * （翻周之后每次切页都白重绘一遍整张表，是这个页面最容易犯的浪费）。
     */
    fun submit(
        week: Int,
        todayDay: Int?,
        slots: List<SlotClock>,
        courses: List<Course>,
        density: List<WeekDotsView.Week>,
        meta: List<TextRun>,
        rangeText: String,
        dotsEnabled: Boolean
    ) {
        val same = this.week == week &&
                this.todayDay == todayDay &&
                this.slots == slots &&
                this.rangeText == rangeText &&
                this.dotsEnabled == dotsEnabled &&
                this.courses === courses &&
                this.densityWeeks == density &&
                this.meta == meta
        this.week = week
        this.todayDay = todayDay
        this.slots = slots
        this.courses = courses
        this.densityWeeks = density
        this.meta = meta
        this.rangeText = rangeText
        this.dotsEnabled = dotsEnabled

        if (!same) {
            syncDescription()
            invalidate()
        }
    }

    private fun syncDescription() {
        val today = todayDay?.let { "今天${weekdayCn(it)}" } ?: "非本周"
        val active = courses.count { it.enabled && week in it.weeks }
        contentDescription = "第 $week 教学周课表，$today，本周 $active 门课"
    }

    // ================================================================ 版式（设计稿 dp）

    private val headH = 140f
    private val daysH = 46f
    private val sidePad = 12f
    private val timeColW = 34f
    private val densityH = 50f
    private val densityGap = 6f

    /** WEEK 的基线。CSS 是 `font-size:78 / line-height:.82 / top:20`，换算成基线约在 83 */
    private val titleBase = 83f

    // 潮汐光斑：左上那团最亮，右上那团最大，右侧中间一团收边
    private val tides = arrayOf(
        Tide(15f, 55f, 105f, 0x3D0F86FF),
        Tide(255f, 5f, 125f, 0x330F86FF),
        Tide(335f, 126f, 80f, 0x2E0F86FF)
    )

    // ---- 命中表（onDraw 里重建，触摸时查） ----

    private class Hit(val rect: RectF, val action: () -> Unit)

    private val hits = ArrayList<Hit>(64)
    private val prevKey = RectF()
    private val nextKey = RectF()

    /** onDraw 里算出来的网格几何，触摸时要用同一套，不能重算一遍 */
    private var gridTop = 0f
    private var rowH = 0f
    private var colW = 0f
    private var gridLeft = 0f

    private var downX = 0f
    private var downY = 0f

    // ================================================================ 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        hits.clear()

        // 行数随 periods 变（默认 10 行），所以行高只能现算：先求「铺满到密度条」时
        // 一行有多高，再**只取其中的 2/3**。
        //
        // 为什么不再铺满：长屏上（比如 2400px / 810dp 高）铺满会把行高顶到 56dp 的上限，
        // 10 行的表看着又稀又空，课块之间的蓝底比课名还多。矮一点之后整张表更紧凑，
        // 看起来才像「一眼扫完一周」。
        //
        // 密度条跟着网格走、不再钉在屏幕最下面 —— 网格矮了，它也自然往上收。
        // 再往下让两个格子的高度：收紧后的网格上方留白少、下方留白多，
        // 卡片贴着网格会显得「挤在中间」；让出两行之后，上下两侧的留白才平衡。
        gridLeft = dp(sidePad)
        gridTop = dp(headH) + dp(daysH)
        val rows = slots.size.coerceAtLeast(1)
        val fullRow = ((h - dp(densityH) - dp(densityGap)) - gridTop) / rows
        rowH = (fullRow * ROW_FILL).coerceIn(dp(24f), dp(56f))
        val gridBottom = gridTop + rowH * rows
        val densTop = gridBottom + dp(densityGap) + rowH * 2f

        val gridRight = w - dp(sidePad)
        colW = (gridRight - gridLeft - dp(timeColW)) / 7f

        drawBackdrop(canvas, w, h)
        drawHead(canvas, w)
        drawDayHeaders(canvas)
        drawGrid(canvas, w)
        drawDensity(canvas, w, densTop)
    }

    // ---------------------------------------------------------------- 表头

    private fun drawHead(canvas: Canvas, w: Float) {
        drawTides(canvas, tides)
        drawHalftone(canvas, w)
        drawGhostWeek(canvas, w)
        drawSlash(canvas)

        // WEEK 压在斜杠上：描墨蓝边 + 错位深影，最后盖白字
        drawTitle(
            canvas, "WEEK",
            x = dp(9f),
            baseline = dp(titleBase),
            size = ts(78f),
            skew = -0.1405f,          // CSS skewX(-8deg)
            strokeWidth = dp(2.5f),
            shadowDx = dp(6f), shadowDy = dp(6f),
            shadowColor = 0x52020C42
        )

        drawDateBar(canvas)
        drawMeta(canvas)
        drawKeys(canvas, w)
    }

    /**
     * 右上那片网点：样稿用 `radial-gradient(#03125C 1.1px, …)` + skew 做的半调。
     * 没有实际信息，只是让右上角**不是一片平滑的蓝**。
     */
    private fun drawHalftone(canvas: Canvas, w: Float) {
        val broad = dp(118f)
        val tall = dp(104f)
        fill.shader = null
        fill.color = 0x6B03125C
        val save = canvas.save()
        canvas.translate(w - dp(120f), 0f)
        canvas.skew(-0.1763f, 0f)     // skewX(-10deg)
        var y = -dp(4f)
        while (y < -dp(4f) + tall) {
            var x = 0f
            while (x < broad) {
                canvas.drawCircle(x, y, dp(1.1f), fill)
                x += dp(5f)
            }
            y += dp(5f)
        }
        canvas.restoreToCount(save)
    }

    /** 压在标题后面的巨号周次。它从画面上沿切出去一截 —— 原稿 `top:-40` */
    private fun drawGhostWeek(canvas: Canvas, w: Float) {
        val size = ts(172f)
        text.typeface = PersonaP3.titleFace
        text.textSize = size
        text.isFakeBoldText = true
        text.textSkewX = -0.1763f
        text.textAlign = Paint.Align.RIGHT
        text.style = Paint.Style.FILL
        text.color = 0xEB04106B.toInt()
        canvas.drawText(week.toString().padStart(2, '0'), w + dp(8f), -dp(40f) + size * 0.74f, text)
        text.isFakeBoldText = false
        text.textSkewX = 0f
        text.textAlign = Paint.Align.LEFT
    }

    /** 标题下面那条品红斜条：给「WEEK」一道贯穿的动势 */
    private fun drawSlash(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(dp(2f), dp(79f))
        canvas.rotate(-2.5f)
        val len = dp(236f)
        val thick = dp(8f)
        // skewX(-32°)：下边相对上边左移 tan(32°) × 高度
        val sk = thick * tan(Math.toRadians(32.0)).toFloat()
        val path = skewRect(0f, 0f, len, thick, -sk)
        fill.shader = LinearGradient(
            0f, 0f, len, 0f,
            intArrayOf(PersonaP3.MAGENTA, 0xFFFF7A6A.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null
        canvas.restoreToCount(save)
    }

    /** 日期条（青底墨字）+ 「第 N 周」 */
    private fun drawDateBar(canvas: Canvas) {
        val x = dp(12f)
        val top = dp(92f)
        val hgt = dp(19f)
        val chipW = runWidth(rangeText, ts(12f), true) + dp(16f)
        // skewX(-8°)：上边右移，和样稿里那枚斜着的日期条一致
        val skew = skewOf(hgt, 8f)

        fill.shader = null
        // 3dp 的深色错位影子（CSS box-shadow:3px 3px 0）
        fill.color = 0x5702124F
        canvas.drawPath(
            Path(skewRect(x, top, x + chipW, top + hgt, skew)).apply { offset(dp(3f), dp(3f)) },
            fill
        )
        fill.color = PersonaP3.CYAN
        canvas.drawPath(skewRect(x, top, x + chipW, top + hgt, skew), fill)

        // 条上的字：跟着斜切量往右挪一半，视觉上才居中
        text.typeface = PersonaP3.body
        text.textSize = ts(12f)
        text.isFakeBoldText = true
        text.style = Paint.Style.FILL
        text.color = 0xFF02124F.toInt()
        text.textAlign = Paint.Align.LEFT
        var fm = text.fontMetrics
        val midY = top + hgt / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(rangeText, x + dp(8f) + skew / 2f, midY, text)
        text.isFakeBoldText = false

        text.typeface = PersonaP3.cnBold
        text.textSize = ts(10f)
        text.color = 0xFFEAF4FF.toInt()
        text.letterSpacing = 0.08f
        fm = text.fontMetrics
        canvas.drawText("第 $week 周", x + chipW + dp(10f), midY, text)
        text.letterSpacing = 0f
    }

    /** 「今天：周三 · A 型日 · 有早八」—— 混色由 Fragment 组装成 [TextRun] */
    private fun drawMeta(canvas: Canvas) {
        drawRuns(canvas, meta, dp(13f), dp(124f), ts(9.5f))
    }

    /** 两个键帽：白底墨边、右下角一抹青影 */
    private fun drawKeys(canvas: Canvas, w: Float) {
        val size = dp(31f)
        val gap = dp(7f)
        val top = dp(92f)
        val right = w - dp(12f)
        nextKey.set(right - size, top, right, top + size)
        prevKey.set(right - size - gap - size, top, right - size - gap, top + size)

        for (r in listOf(prevKey, nextKey)) {
            fill.color = PersonaP3.CYAN
            canvas.drawRoundRect(
                RectF(r.left + dp(3f), r.top + dp(3f), r.right + dp(3f), r.bottom + dp(3f)),
                dp(5f), dp(5f), fill
            )
            fill.color = 0xFFF4F8FF.toInt()
            canvas.drawRoundRect(r, dp(5f), dp(5f), fill)
            stroke.color = PersonaP3.INK
            stroke.strokeWidth = dp(2f)
            canvas.drawRoundRect(r, dp(5f), dp(5f), stroke)
        }

        stroke.color = PersonaP3.INK
        stroke.strokeWidth = dp(3.2f)
        stroke.strokeCap = Paint.Cap.SQUARE
        stroke.strokeJoin = Paint.Join.MITER
        chevron(canvas, prevKey, true)
        chevron(canvas, nextKey, false)
        stroke.strokeCap = Paint.Cap.BUTT

        // 键帽的命中区。⚠️ 光有 rect 不够：点击是靠 hits 表分发的，
        // 忘了登记就是「画得出来、点不动」—— 比不画还糟（用户会以为卡住了）
        hits += Hit(RectF(prevKey).apply { inset(-dp(4f), -dp(4f)) }) { onWeekStep?.invoke(-1) }
        hits += Hit(RectF(nextKey).apply { inset(-dp(4f), -dp(4f)) }) { onWeekStep?.invoke(1) }
    }

    /** 键帽里的箭头。用 Path 画而不是字形：国产 ROM 的字体里常常没有 ‹ › */
    private fun chevron(canvas: Canvas, box: RectF, left: Boolean) {
        val cx = box.centerX()
        val cy = box.centerY()
        val dx = dp(3.2f)
        val dy = dp(6.5f)
        val path = Path().apply {
            if (left) {
                moveTo(cx + dx, cy - dy)
                lineTo(cx - dx, cy)
                lineTo(cx + dx, cy + dy)
            } else {
                moveTo(cx - dx, cy - dy)
                lineTo(cx + dx, cy)
                lineTo(cx - dx, cy + dy)
            }
        }
        canvas.drawPath(path, stroke)
    }

    // ---------------------------------------------------------------- 星期表头

    private fun drawDayHeaders(canvas: Canvas) {
        text.typeface = PersonaP3.tag
        text.textSize = ts(6.5f)
        text.color = 0xFF7FA6E8.toInt()
        text.textAlign = Paint.Align.LEFT
        text.letterSpacing = 0.10f
        canvas.drawText("节次", gridLeft + dp(2f), dp(headH + daysH - 6f), text)
        text.letterSpacing = 0f

        val bottom = dp(headH + daysH) - dp(3f)
        for (day in 1..7) {
            val l = gridLeft + dp(timeColW) + (day - 1) * colW
            val isToday = day == todayDay

            if (isToday) {
                // 今天的胶带：上边右移 8%、下边左移 8%，再整体逆时针歪 2°
                val tapeH = dp(44f)
                val save = canvas.save()
                canvas.rotate(-2f, l + colW / 2f, bottom - tapeH / 2f)
                val path = Path().apply {
                    moveTo(l + colW * 0.08f + dp(2f), bottom - tapeH)
                    lineTo(l + colW - dp(2f), bottom - tapeH)
                    lineTo(l + colW - dp(2f) - colW * 0.08f, bottom)
                    lineTo(l + dp(2f), bottom)
                    close()
                }
                fill.color = PersonaP3.MAGENTA
                canvas.drawPath(path, fill)
                canvas.restoreToCount(save)
            }

            // 英文缩写（今天换 TODAY）：小字号、大写字距
            text.typeface = PersonaP3.tag
            text.textSize = ts(if (isToday) 5.5f else 7f)
            text.textAlign = Paint.Align.CENTER
            text.letterSpacing = 0.12f
            text.color = if (isToday) 0xFF18002F.toInt() else 0xFF8FB7FF.toInt()
            canvas.drawText(
                if (isToday) "TODAY" else EN_DAYS[day - 1],
                l + colW / 2f, bottom - dp(22f), text
            )

            // 中文：白 / 周六青 / 周日红，斜切
            text.typeface = PersonaP3.cnHeavy
            text.textSize = ts(17f)
            text.letterSpacing = 0f
            text.textSkewX = -0.105f        // skewX(-6deg)
            text.color = when {
                isToday -> 0xFF18002F.toInt()
                day == 6 -> PersonaP3.CYAN
                day == 7 -> PersonaP3.RED
                else -> 0xFFFFFFFF.toInt()
            }
            canvas.drawText(weekdayCn(day).removePrefix("周"), l + colW / 2f, bottom - dp(3f), text)
            text.textSkewX = 0f
        }
        text.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- 网格

    private fun drawGrid(canvas: Canvas, w: Float) {
        val rows = slots.size
        val gridRight = w - dp(sidePad)

        // 1) 横行分隔线：1px、10% 白蓝。**没有竖线** —— 原稿的列是留白分出来的
        stroke.color = 0x1A9EC5FF
        stroke.strokeWidth = dp(1f)
        for (i in 0..rows) {
            val y = gridTop + i * rowH
            canvas.drawLine(gridLeft, y, gridRight, y, stroke)
        }

        // 2) 今天那一条青色光柱
        todayDay?.let { day ->
            val l = gridLeft + dp(timeColW) + (day - 1) * colW
            val r = l + colW
            fill.shader = LinearGradient(
                0f, gridTop, 0f, gridTop + rowH * rows,
                intArrayOf(0x2B18D8F8, 0x0F18D8F8, 0x0518D8F8),
                floatArrayOf(0f, 0.78f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(l, gridTop, r, gridTop + rowH * rows, fill)
            fill.shader = null
            stroke.strokeWidth = dp(1f)
            stroke.color = 0x7A18D8F8
            canvas.drawLine(l, gridTop, l, gridTop + rowH * rows, stroke)
            stroke.color = 0x4218D8F8
            canvas.drawLine(r, gridTop, r, gridTop + rowH * rows, stroke)
        }

        // 3) 节次列：节号 + 起始时刻
        val nodes = slots.map { it.node }
        text.textAlign = Paint.Align.LEFT
        for ((i, s) in slots.withIndex()) {
            val cy = gridTop + i * rowH + rowH / 2f

            text.typeface = PersonaP3.titleFace
            text.textSize = ts(13f)
            text.isFakeBoldText = true
            text.color = 0xFFDCEBFF.toInt()
            var fm = text.fontMetrics
            canvas.drawText(
                s.node.toString(), gridLeft + dp(2f),
                cy - dp(4f) - (fm.ascent + fm.descent) / 2f, text
            )
            text.isFakeBoldText = false

            text.typeface = PersonaP3.body
            text.textSize = ts(6.5f)
            text.color = 0xFF7FA6E8.toInt()
            fm = text.fontMetrics
            canvas.drawText(
                clock(s.start), gridLeft + dp(2f),
                cy + dp(7f) - (fm.ascent + fm.descent) / 2f, text
            )
        }
        // 节次列与课列之间那根竖线
        stroke.color = 0x299EC5FF
        stroke.strokeWidth = dp(1f)
        canvas.drawLine(
            gridLeft + dp(timeColW), gridTop,
            gridLeft + dp(timeColW), gridTop + rowH * rows, stroke
        )

        // 4) 课块。跨行的课合成一块，高度 = 跨过的行高之和
        for (day in 1..7) {
            for (span in WeekGrid.spans(day, nodes, week, courses)) {
                val i0 = nodes.indexOf(span.fromNode).coerceAtLeast(0)
                val i1 = nodes.indexOf(span.toNode).coerceAtLeast(i0)
                val l = gridLeft + dp(timeColW) + (day - 1) * colW
                drawSpan(
                    canvas, span,
                    l, gridTop + i0 * rowH, l + colW, gridTop + (i1 + 1) * rowH
                )
            }
        }
    }

    private fun drawSpan(
        canvas: Canvas, span: WeekGrid.Span,
        l: Float, top: Float, r: Float, bottom: Float
    ) {
        // 命中区域对空格子也有效：点空的格子要能看「这一格全学期都上过什么课」
        hits += Hit(
            RectF(l, top, r, bottom),
            { onSlotClick?.invoke(span.day, span.fromNode..span.toNode, screenRect(l, top, r, bottom)) }
        )

        val course = span.course ?: return

        val inset = dp(2f)
        val rect = RectF(l + inset, top + inset, r - inset, bottom - inset)
        if (rect.height() < dp(4f) || rect.width() < dp(6f)) return
        val isToday = span.day == todayDay

        fill.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            if (isToday) intArrayOf(0xF612347C.toInt(), 0xF6081642.toInt())
            else intArrayOf(0xF21A286C.toInt(), 0xF2081038.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, fill)
        fill.shader = null

        // 左边那条 2.5dp 的色边：普通课青、今天品红 —— 这一页唯一的「课块颜色语法」
        fill.color = if (isToday) PersonaP3.MAGENTA else PersonaP3.CYAN
        canvas.drawRect(rect.left, rect.top, rect.left + dp(2.5f), rect.bottom, fill)

        stroke.color = if (isToday) PersonaP3.CYAN else 0xD95E8BFF.toInt()
        stroke.strokeWidth = dp(1f)
        canvas.drawRect(rect, stroke)

        // 课名：块里能塞几行就画几行（1–3 行），教室压在最底下（margin-top:auto），
        // 两个都要放不下时**先舍教室** —— 课名是这一格的主信息。
        //
        // 行数按**剩余高度**现算，不写「>=66dp 给三行」这种阈值：行高降下来以后，
        // 一个两行的连堂课是 66dp 上下，差 0.4dp 就从三行掉到两行，
        // 「思想道德与法治」当场被截成「德与…」，而块里明明还空着一大片。
        val padL = dp(5.5f)
        val innerW = rect.width() - padL - dp(2f)
        val nameSize = ts(if (rect.height() >= dp(58f)) 10.5f else 10f)
        val lineH = nameSize * 1.12f
        val roomLine = rect.height() >= dp(26f) && course.place.isNotBlank()
        val roomH = if (roomLine) ts(7f) * 1.25f + dp(3f) else 0f
        val nameRows = ((rect.height() - dp(7f) - roomH) / lineH).toInt().coerceIn(1, 3)

        text.typeface = PersonaP3.cnHeavy
        text.textSize = nameSize
        text.color = 0xFFFFFFFF.toInt()
        text.textAlign = Paint.Align.LEFT
        val lines = wrapText(course.name, nameSize, innerW, nameRows, true, PersonaP3.cnHeavy)
        var baseline = rect.top + dp(4f) - text.fontMetrics.ascent
        for (line in lines) {
            canvas.drawText(line, rect.left + padL, baseline, text)
            baseline += lineH
        }

        if (roomLine) {
            text.typeface = PersonaP3.body
            text.textSize = ts(7f)
            text.color = 0xFF9FC0FF.toInt()
            val one = wrapText(course.place, text.textSize, innerW, 1, false, PersonaP3.body)
            one.firstOrNull()?.let {
                canvas.drawText(
                    it, rect.left + padL,
                    rect.bottom - dp(3f) - text.fontMetrics.descent, text
                )
            }
        }
    }

    // ---------------------------------------------------------------- 近五周密度条

    private fun drawDensity(canvas: Canvas, w: Float, densTop: Float) {
        if (!dotsEnabled || densityWeeks.isEmpty()) return

        val l = gridLeft
        val r = w - dp(sidePad)
        fill.shader = null
        fill.color = 0x85030A2C.toInt()
        canvas.drawRect(l, densTop, r, densTop + dp(densityH), fill)
        fill.color = PersonaP3.CYAN
        canvas.drawRect(l, densTop, l + dp(2f), densTop + dp(densityH), fill)

        drawTag(canvas, "WEEK DENSITY", l + dp(8f), densTop + dp(19f), ts(6f), PersonaP3.CYAN, 0.16f)
        text.typeface = PersonaP3.body
        text.textSize = ts(9f)
        text.color = 0xFFDCEBFF.toInt()
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("近五周", l + dp(8f), densTop + dp(34f), text)

        // 点阵：7 列 × 节数行。行距按可用高度收 —— 13 节的学校也不会顶出条外
        val areaL = l + dp(58f)
        val areaR = r - dp(8f)
        val rows = densityWeeks.first().occupied.firstOrNull()?.size ?: 0
        if (rows == 0) return
        val dotsTop = densTop + dp(6f)
        val dotsBottom = densTop + dp(densityH) - dp(13f)
        val pitch = minOf(dp(3.4f), (dotsBottom - dotsTop) / rows)
        val dotR = pitch * 0.34f
        val step = (areaR - areaL) / densityWeeks.size

        for ((wi, d) in densityWeeks.withIndex()) {
            val centerX = areaL + step * wi + step / 2f
            val x0 = centerX - 7 * pitch / 2f
            for (day in 0 until 7) {
                for (row in 0 until rows) {
                    fill.color = when {
                        !d.occupied[day][row] -> 0x2B9FBCF2
                        d.highlight -> 0xFFFFFFFF.toInt()
                        else -> PersonaP3.CYAN
                    }
                    canvas.drawCircle(
                        x0 + day * pitch + pitch / 2f,
                        dotsTop + row * pitch + pitch / 2f,
                        dotR, fill
                    )
                }
            }
            text.typeface = PersonaP3.body
            text.textSize = ts(6f)
            text.textAlign = Paint.Align.CENTER
            text.isFakeBoldText = d.highlight
            text.color = if (d.highlight) PersonaP3.CYAN else 0xFF7FA6E8.toInt()
            canvas.drawText(d.label.toString(), centerX, densTop + dp(densityH) - dp(4f), text)
            text.isFakeBoldText = false
        }
        text.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- 小工具

    private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** 局部矩形 → 屏幕矩形（弹卡动画的圆心要屏幕坐标，见 [onSlotClick]） */
    private fun screenRect(l: Float, t: Float, r: Float, b: Float): RectF {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return RectF(loc[0] + l, loc[1] + t, loc[0] + r, loc[1] + b)
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
                // 横滑翻周：和日历页的横滑翻月同一个手势，向左滑 = 下一周
                if (abs(dx) > dp(60f) && abs(dx) > abs(dy) * 1.5f) {
                    onWeekStep?.invoke(if (dx < 0) 1 else -1)
                    performClick()
                    return true
                }
                if (abs(dx) < dp(12f) && abs(dy) < dp(12f)) {
                    // 后加进来的命中优先（课块压在光柱和分隔线之后）
                    for (i in hits.indices.reversed()) {
                        if (hits[i].rect.contains(event.x, event.y)) {
                            hits[i].action()
                            performClick()
                            return true
                        }
                    }
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

    private companion object {
        /** 星期表头的英文缩写，和样稿一致 */
        val EN_DAYS = arrayOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

        /**
         * 行高取「铺满可用高度」的几成。
         *
         * 0.67 ≈ 2/3：长屏上铺满会让行高顶到 56dp 上限，10 行的表稀得发空；
         * 取 2/3 之后行高落在 30~38dp，一门 1-2 节的连堂课有 60~76dp，
         * 放得下三行课名 + 教室。改这个值只影响留白多少，不影响任何信息。
         */
        const val ROW_FILL = 0.67f
    }
}
