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
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import com.yxz.timetable.data.Moment
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.Slots
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min

/**
 * 明天那一格里要显示的东西（由 [com.yxz.timetable.ui.TodayFragment] 算好）。
 *
 * 为什么不让 View 自己去算：明天的日型、起床时刻、第一件事都要走
 * [com.yxz.timetable.data.TimelineEngine]，而那套东西**已经在 Fragment 里跑了一遍**
 * （设置闹钟、按钮文案都基于它）。View 再算一次就会出现「卡片说 A 型、按钮按 B 型排」
 * 这种两边不一致，而这类 bug 极难复现。
 */
data class PersonaTomorrow(
    val date: LocalDate,
    val week: Int,
    /** 起床时刻（分钟）。作息模板里没有上午睡眠段时为 null */
    val wakeMinute: Int?,
    /** 日型的显示名，可能带「· 有早八」 */
    val dayType: String,
    /** 明天几节课 */
    val courseCount: Int,
    /** 第一件事：「06:55 起床、洗漱」 */
    val firstItem: String?,
    /** 起床闹钟已经设成了几点；null = 还没设。按钮文案由它决定，见 [PersonaTodayView] */
    val alarmText: String?
)

/** 今天页一屏需要的全部数据 */
data class PersonaTodayState(
    val date: LocalDate,
    val week: Int,
    /** 现在第几分钟（0..1439）。30 秒一次的重绘靠它走字 */
    val minute: Int,
    /** 日型显示名，如「B 型日 · 无早八」 */
    val dayType: String,
    val moments: List<Moment>,
    /** 常驻通知栏开着没有 —— 右上角那颗胶囊 */
    val enabled: Boolean,
    /** 开关下面的那行状态（暂停中 / 缺权限）。null = 一切正常 */
    val note: String?,
    val tomorrow: PersonaTomorrow?
)

/**
 * P3 版今天页（整页自绘），照 `p3web/today-p3-v3.html` 一比一搬过来。
 *
 * ## 这一版和「HUD + 卡片换皮」那版的区别
 *
 * 旧版是**给现有布局逐控件刷皮**：顶部一条 HUD 横幅，下面还是原来那些卡片。
 * 样稿 v3 换了个画法 —— 上半屏是一整块日期 HUD，下半屏是一张**斜边的白色日程纸**，
 * 中间那道斜口就是这两块的分界。纸上的东西（24 小时色带 / 此刻 / 时间线 / 明天）
 * 全部是纸上的排版，没有一张「卡片」是圆角浮起的。
 *
 * 这些用 XML + background 拼不出来（斜口纸、色带、时间线的竖轨），所以整页自绘。
 *
 * ## 页面不滚动
 *
 * 原稿就是一屏：时间线只露当前格附近的几行（[PersonaTodayModel.window]）。
 * 全天完整列表仍然在默认外观里，两边不是替代关系 —— 一边是「现在该干什么」，
 * 一边是「今天怎么安排」。
 *
 * ## 交互
 *
 * 三种点击，全通过回调交给 Fragment：
 *   · 右上角胶囊 → 开 / 关常驻通知（[onToggleEnabled]）
 *   · 时间线任意一行 → 这一格的信息卡（[onMomentClick]，锚点用屏幕坐标矩形）
 *   · 明天卡右下角按钮 → 设起床闹钟（[onWakeAlarm]）
 */
class PersonaTodayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : PersonaPageView(context, attrs, defStyleAttr) {

    private var state: PersonaTodayState? = null

    var onToggleEnabled: (() -> Unit)? = null
    var onMomentClick: ((Moment, RectF) -> Unit)? = null
    var onWakeAlarm: (() -> Unit)? = null

    fun submit(s: PersonaTodayState) {
        // 30 秒一次的喂数据里，大部分时候只有 minute 变了 —— 那也是要重画的（走字、光带）
        if (state == s) return
        state = s
        syncDescription(s)
        invalidate()
    }

    private fun syncDescription(s: PersonaTodayState) {
        val now = s.moments.firstOrNull { s.minute >= it.start && s.minute < it.end }
        contentDescription = "${s.date.monthValue}月${s.date.dayOfMonth}日 " +
                "${weekdayCn(s.date.dayOfWeek.value)}，第 ${s.week} 教学周，${s.dayType}" +
                (now?.let { "，现在：${it.title}，到 ${Slots.fmt(it.end)}" } ?: "")
    }

    // ================================================================ 版式（设计稿 dp）

    /** 上半屏 HUD 的高度。白纸从这一行往下铺，左端再低 18dp（斜口） */
    private val heroH = 196f

    private val edge = 16f            // 白纸的左右内边距
    private val paperSkew = 18f       // 白纸左端比右端低多少

    // ---- 命中表 ----

    private class Hit(val rect: RectF, val action: () -> Unit)

    private val hits = ArrayList<Hit>(16)
    private val residentRect = RectF()

    private var paperTop = 0f
    private var downX = 0f
    private var downY = 0f

    // ---- 时间线滚动 ----
    //
    // 视口和内容高度在 onDraw 里算出来（它们依赖纸张的实际高度），触摸处理直接用。
    // 位置用**像素**而不是「第几行」：`OverScroller` 按像素走，行号再换算一次只会引入取整误差。

    /** 视口上沿 / 高度 / 可滚动上限 / 行高，onDraw 写、onTouchEvent 读 */
    private var tlTop = 0f
    private var tlHeight = 0f
    private var tlScrollMax = 0f
    private var tlRowH = 0f

    /** 内容滚到哪了（0 = 第一格贴顶） */
    private var scrollY = 0f

    /** 上一次摆位置用的数据指纹：换了数据才重新居中，走字不打断用户滚动 */
    private var timelineKey = 0

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 这次手势是不是「拖时间线」（而不是点某一格） */
    private var draggingTimeline = false
    private var lastTouchY = 0f

    // ================================================================ 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 背景：和闹钟页**同一张手绘天空**（渐变 + 云带 + 顶部压暗）。
        // 取不到资源才退回自己画的那套渐变 —— 两页的蓝必须是同一块蓝，
        // 否则切页时底色会跳一下。
        if (!drawPaintedBackdrop(canvas, w, h)) {
            drawBackdrop(canvas, w, h)
        }

        val s = state ?: return
        hits.clear()

        paperTop = min(dp(heroH), h * 0.46f)

        drawHero(canvas, w, s)
        drawPaper(canvas, w, h, s)
    }

    // ---------------------------------------------------------------- 上半屏 HUD

    private fun drawHero(canvas: Canvas, w: Float, s: PersonaTodayState) {
        // 「TODAY」小标签：字距拉得很开，是这一页的「栏目名」
        drawTag(canvas, "TODAY", dp(14f), dp(37f), ts(8f), PersonaP3.CYAN, 0.30f)

        drawResidentCapsule(canvas, w, s)

        // 巨号日期：描墨蓝边 + 斜切。右缘不许压到三角旗，放不下就缩号
        val dateText = "${s.date.monthValue}/${s.date.dayOfMonth}"
        val markerX = dp(203f)
        var size = ts(80f)
        text.typeface = PersonaP3.titleFace
        text.isFakeBoldText = true
        text.textSize = size
        val avail = markerX - dp(9f) - dp(4f)
        val measured = text.measureText(dateText)
        if (measured > avail) size *= avail / measured
        drawTitle(
            canvas, dateText,
            x = dp(9f),
            baseline = dp(44f) + size * 0.74f,
            size = size,
            skew = -0.1228f,          // CSS skewX(-7deg)
            strokeWidth = dp(2.5f),
            shadowDx = dp(6f), shadowDy = dp(6f),
            shadowColor = 0x47020C42
        )

        drawMarker(canvas, markerX, dp(62f), s.date.dayOfWeek.value)

        // 右上角：此刻 + 月相 + 时段
        drawClock(canvas, w, s)
        drawWeekday(canvas, s)
        drawChips(canvas, s)
        s.note?.let { drawNote(canvas, w, it) }
    }

    /**
     * 需要用户做点什么的那一行小字（精确闹钟没开、缺少通知权限）。
     *
     * 原稿里没有这一行 —— 但**省电设计依赖它**：精确闹钟没开时通知会晚十几分钟，
     * 用户不知道的话只会以为 App 坏了。默认外观里这行是在开关卡片下面，
     * 这一页没有那张卡，就压在 HUD 的右下角。颜色用琥珀色（警告），不是品红（装饰）。
     */
    private fun drawNote(canvas: Canvas, w: Float, note: String) {
        val size = ts(7f)
        val one = wrapText(
            note, size, w - dp(34f), 1, bold = false, face = PersonaP3.body
        ).firstOrNull() ?: return
        text.typeface = PersonaP3.body
        text.textSize = size
        text.isFakeBoldText = false
        text.color = 0xFFFFC44D.toInt()
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(one, w - dp(17f), dp(178f), text)
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * 常驻开关收成右上角一颗胶囊（原稿 `.res`）。
     *
     * 旧版这是一整行卡片 —— 在「今天该干什么」的页面上占一整行太贵了。
     * 开关的**状态**仍然要能一眼看出来（青色=开、灰=关），所以胶囊本身就是那个开关。
     */
    private fun drawResidentCapsule(canvas: Canvas, w: Float, s: PersonaTodayState) {
        val label = if (s.enabled) "常驻" else "已关"
        text.typeface = PersonaP3.body
        text.textSize = ts(8f)
        val labelW = text.measureText(label)
        val swW = dp(24f)
        val swH = dp(13f)
        val padL = dp(10f)
        val padR = dp(8f)
        val gap = dp(6f)
        val capW = padL + labelW + gap + swW + padR
        val capH = dp(21f)
        val left = w - dp(14f) - capW
        val top = dp(23f)
        residentRect.set(left, top, left + capW, top + capH)

        fill.shader = null
        fill.color = 0x1AFFFFFF
        canvas.drawRect(residentRect, fill)
        stroke.color = 0x59DCEEFF
        stroke.strokeWidth = dp(1f)
        canvas.drawRect(residentRect, stroke)

        text.style = Paint.Style.FILL
        text.color = 0xFFEAF4FF.toInt()
        text.textAlign = Paint.Align.LEFT
        val fm = text.fontMetrics
        canvas.drawText(label, left + padL, top + capH / 2f - (fm.ascent + fm.descent) / 2f, text)

        // 开关本体：开的时青底、圆钮靠右；关的时半透明白底、圆钮靠左
        val swL = left + padL + labelW + gap
        val swT = top + (capH - swH) / 2f
        val swR = swL + swW
        val swB = swT + swH
        fill.color = if (s.enabled) PersonaP3.CYAN else 0x40DCEEFF
        canvas.drawRoundRect(swL, swT, swR, swB, swH / 2f, swH / 2f, fill)
        val knobR = dp(4.5f)
        val knobX = if (s.enabled) swR - dp(2f) - knobR else swL + dp(2f) + knobR
        fill.color = 0xFF06237A.toInt()
        canvas.drawCircle(knobX, (swT + swB) / 2f, knobR, fill)

        hits += Hit(
            RectF(residentRect).apply { inset(-dp(6f), -dp(8f)) },
            { onToggleEnabled?.invoke() }
        )
    }

    /**
     * 三角旗 + 当天的周几字。
     *
     * 几何和 [PersonaDateHud.drawMarker] 同一套（那份是从游戏截图量的）：
     * 旗子是三角形，字比旗子大，**左半截探出旗子压在蓝底上**、
     * 右半截齐着右边缘被切住 —— 所以裁剪区不是三角形本身，
     * 而是「三角形右边缘的左侧」那个四边形。探出去那半截没有旗子托底，
     * 先描一圈旗子同色的深边再填白。
     */
    private fun drawMarker(canvas: Canvas, x: Float, y: Float, dow: Int) {
        val mw = dp(56f)
        val mh = dp(42.4f)

        fill.shader = null
        fill.color = 0xFF0A1026.toInt()
        val tri = Path().apply {
            moveTo(x, y)
            lineTo(x + mw, y)
            lineTo(x + mw * 0.507f, y + mh)
            close()
        }
        canvas.drawPath(tri, fill)

        val clip = Path().apply {
            moveTo(x - mw * 0.3226f, y)
            lineTo(x + mw, y)
            lineTo(x + mw * 0.5f, y + mh)
            lineTo(x - mw * 0.3226f, y + mh * 2.915f)
            close()
        }
        val save = canvas.save()
        canvas.clipPath(clip)

        text.typeface = PersonaP3.cnBold
        text.textSize = ts(31f)
        text.textAlign = Paint.Align.CENTER
        text.style = Paint.Style.STROKE
        text.strokeWidth = dp(3f)
        text.strokeJoin = Paint.Join.ROUND
        text.color = 0xFF0A1026.toInt()
        val char = weekdayCn(dow).removePrefix("周")
        val baseline = y + mh * 0.745f
        canvas.drawText(char, x + mw * 0.45f, baseline, text)
        text.style = Paint.Style.FILL
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(char, x + mw * 0.45f, baseline, text)

        canvas.restoreToCount(save)
        text.textAlign = Paint.Align.LEFT
    }

    /** 此刻 + 时段（青字深描边） */
    private fun drawClock(canvas: Canvas, w: Float, s: PersonaTodayState) {
        val right = w - dp(17f)
        val clock = Slots.fmt(s.minute)

        text.typeface = PersonaP3.titleFace
        text.textSize = ts(39f)
        text.isFakeBoldText = true
        text.textAlign = Paint.Align.RIGHT
        val baseline = dp(96f) + ts(39f) * 0.78f
        text.style = Paint.Style.STROKE
        text.strokeWidth = dp(1f) * 2f
        text.color = 0x8C04106B.toInt()
        canvas.drawText(clock, right, baseline, text)
        text.style = Paint.Style.FILL
        text.color = PersonaP3.CYAN
        canvas.drawText(clock, right, baseline, text)
        text.isFakeBoldText = false

        // 时段那行：小月亮 + 两个字，靠右
        val phase = PersonaDateHud.phaseOf(s.minute / 60)
        text.typeface = PersonaP3.cnBold
        text.textSize = ts(9f)
        text.letterSpacing = 0.22f
        text.textAlign = Paint.Align.RIGHT
        text.color = 0xFFDDEBFF.toInt()
        val phaseY = dp(96f) + ts(39f) * 0.78f + dp(15f)
        canvas.drawText(phase, right, phaseY, text)
        text.letterSpacing = 0f
        // 小月亮：黄盘 + 左侧一小块暗面（缩到 10dp，只求「是月亮」）
        val moonCx = right - text.measureText(phase) - dp(12f)
        val moonCy = phaseY - dp(3.5f)
        fill.color = 0xFFFFD93B.toInt()
        canvas.drawCircle(moonCx, moonCy, dp(5f), fill)
        fill.color = 0xFF0C3AB8.toInt()
        canvas.drawCircle(moonCx - dp(2.2f), moonCy, dp(4.2f), fill)
        text.textAlign = Paint.Align.LEFT
    }

    /** 大号星期：白字、斜切 */
    private fun drawWeekday(canvas: Canvas, s: PersonaTodayState) {
        text.typeface = PersonaP3.cnHeavy
        text.textSize = ts(22f)
        text.isFakeBoldText = false
        text.textSkewX = -0.0875f       // skewX(-5deg)
        text.textAlign = Paint.Align.LEFT
        text.style = Paint.Style.FILL
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(
            weekdayCn(s.date.dayOfWeek.value), dp(14f), dp(126f) + ts(22f) * 0.78f, text
        )
        text.textSkewX = 0f
    }

    /** 「第 8 周」青胶带 + 「B 型日 · 无早八」 */
    private fun drawChips(canvas: Canvas, s: PersonaTodayState) {
        val y = dp(156f)
        val hgt = dp(15f)
        val weekLabel = "第 ${s.week} 周"
        text.typeface = PersonaP3.cnBold
        text.textSize = ts(8.5f)
        val chipW = text.measureText(weekLabel) + dp(14f)
        val skew = skewOf(hgt, 8f)
        fill.color = PersonaP3.CYAN
        canvas.drawPath(skewRect(dp(14f), y, dp(14f) + chipW, y + hgt, skew), fill)

        text.color = 0xFF02124F.toInt()
        text.textAlign = Paint.Align.LEFT
        val fm = text.fontMetrics
        canvas.drawText(
            weekLabel, dp(14f) + dp(7f) + skew / 2f,
            y + hgt / 2f - (fm.ascent + fm.descent) / 2f, text
        )

        text.typeface = PersonaP3.body
        text.textSize = ts(8.5f)
        text.color = 0xFFDDEBFF.toInt()
        canvas.drawText(
            s.dayType, dp(14f) + chipW + dp(8f),
            y + hgt / 2f - (fm.ascent + fm.descent) / 2f, text
        )
    }

    // ---------------------------------------------------------------- 下半屏白纸

    private fun drawPaper(canvas: Canvas, w: Float, h: Float, s: PersonaTodayState) {
        val path = Path().apply {
            moveTo(0f, paperTop + dp(paperSkew))
            lineTo(w, paperTop)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        fill.shader = LinearGradient(
            0f, paperTop, 0f, h,
            intArrayOf(PersonaP3.PAPER, PersonaP3.PAPER_BOTTOM),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null

        val save = canvas.save()
        canvas.clipPath(path)

        drawGhostDay(canvas, w, s)

        var y = paperTop + dp(20f)
        y = drawDayBar(canvas, w, y, s)
        y = drawNowCard(canvas, w, y + dp(15f), s)
        y = drawTimeline(canvas, w, y + dp(8f), h, s)
        drawTomorrow(canvas, w, y + dp(12f), s)

        canvas.restoreToCount(save)
    }

    /** 纸后那个巨号日号：「27」。它压在所有内容后面，只做质感 */
    private fun drawGhostDay(canvas: Canvas, w: Float, s: PersonaTodayState) {
        val size = ts(150f)
        text.typeface = PersonaP3.titleFace
        text.textSize = size
        text.isFakeBoldText = true
        text.textAlign = Paint.Align.RIGHT
        text.style = Paint.Style.FILL
        text.color = 0xFFE1E9F6.toInt()
        canvas.drawText(
            s.date.dayOfMonth.toString(), w + dp(6f),
            paperTop + dp(26f) + size * 0.75f, text
        )
        text.isFakeBoldText = false
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * 24 小时色带 + 刻度 + 此刻游标。
     *
     * 色带每一段的宽度就是那一段的分钟数 —— 它回答的是「这一天的时间都花在哪」，
     * 一眼能看出睡觉占了多少、课有多长。**不做交互**：10dp 高的东西点不准，
     * 要查某一段直接点下面的时间线。
     */
    private fun drawDayBar(canvas: Canvas, w: Float, top: Float, s: PersonaTodayState): Float {
        val l = dp(edge)
        val r = w - dp(edge)
        val barH = dp(10f)
        val radius = dp(2f)

        val save = canvas.save()
        // 圆角要在**裁剪**里做：直接给分段铺色再描一圈圆角框，四角会露出方角
        val clip = Path().apply {
            addRoundRect(RectF(l, top, r, top + barH), radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        fill.color = 0xFFDCE6F5.toInt()
        canvas.drawRect(l, top, r, top + barH, fill)

        val total = 1440f
        for (seg in PersonaTodayModel.segments(s.moments)) {
            val x0 = l + (r - l) * seg.start / total
            val x1 = l + (r - l) * seg.end / total
            fill.color = PersonaTodayModel.kindColor(seg.kind)
            canvas.drawRect(x0, top, x1, top + barH, fill)
        }
        canvas.restoreToCount(save)
        // 圆角只在两头：用两条短横「削」出圆角太脏，直接画一层圆角描边框
        stroke.color = 0x14000000
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(l, top, r, top + barH, radius, radius, stroke)

        // 刻度：00 / 06 / 12 / 18 / 24
        val labelY = top + barH + dp(11f)
        text.typeface = PersonaP3.body
        text.textSize = ts(6f)
        text.color = PersonaP3.BAR_LABEL
        for (hr in 0..24 step 6) {
            val x = l + (r - l) * hr / 24f
            text.textAlign = when (hr) {
                0 -> Paint.Align.LEFT
                24 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(hr.toString().padStart(2, '0'), x, labelY, text)
        }
        text.textAlign = Paint.Align.LEFT

        // 此刻游标：品红细线 + 顶上一个小三角
        val nowX = l + (r - l) * s.minute / total
        fill.color = PersonaP3.MAGENTA
        canvas.drawRect(nowX - dp(1f), top - dp(3f), nowX + dp(1f), top + barH + dp(3f), fill)
        val tri = Path().apply {
            moveTo(nowX - dp(4f), top - dp(7f))
            lineTo(nowX + dp(4f), top - dp(7f))
            lineTo(nowX, top - dp(2f))
            close()
        }
        canvas.drawPath(tri, fill)

        return labelY + dp(2f)
    }

    /**
     * 「此刻」卡：深藏青底 + 亮蓝描边 + 左侧一条品红 NOW 标签。
     *
     * 这是**这一页唯一的深色卡片**，因为它是全页的主结论。
     * 内边距按内容实际高度给，不写死卡片高度 —— 中文换行的行高因机型而异。
     */
    private fun drawNowCard(canvas: Canvas, w: Float, top: Float, s: PersonaTodayState): Float {
        val l = dp(edge)
        val r = w - dp(edge)
        val padL = dp(14f)
        val padR = dp(14f)

        val cur = s.moments.firstOrNull { s.minute >= it.start && s.minute < it.end }
        val idx = cur?.let { s.moments.indexOf(it) } ?: -1
        val next = if (idx >= 0 && idx + 1 < s.moments.size) s.moments[idx + 1] else null

        // 文字排版先量后画：卡片高度 = 内容高度之和
        val titleSize = ts(25f)
        val metaSize = ts(9f)
        val nextSize = ts(8.5f)
        val tabH = dp(15f)
        val titleTop = top + dp(12f) + dp(10f)
        val titleH = titleSize * 1.05f
        val metaTop = titleTop + titleH + dp(4f)
        val trackTop = metaTop + metaSize * 1.25f + dp(9f)
        val nextTop = trackTop + dp(4f) + dp(8f)
        val cardBottom = nextTop + nextSize * 1.3f + dp(11f)

        fill.shader = LinearGradient(
            l, top, r, cardBottom,
            intArrayOf(PersonaP3.CARD_TOP, PersonaP3.CARD_BOTTOM),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(l, top, r, cardBottom, fill)
        fill.shader = null
        stroke.color = 0xFF6FA8FF.toInt()
        stroke.strokeWidth = dp(1f)
        canvas.drawRect(l, top, r, cardBottom, stroke)

        // 左上角 NOW 标签
        text.typeface = PersonaP3.tag
        text.textSize = ts(7f)
        text.letterSpacing = 0.18f
        val tabW = text.measureText("NOW") + dp(18f)
        fill.color = PersonaP3.MAGENTA
        canvas.drawRect(l, top, l + tabW, top + tabH, fill)
        text.style = Paint.Style.FILL
        text.color = 0xFF18002F.toInt()
        text.textAlign = Paint.Align.LEFT
        var fm = text.fontMetrics
        canvas.drawText(
            "NOW", l + dp(9f), top + tabH / 2f - (fm.ascent + fm.descent) / 2f, text
        )
        text.letterSpacing = 0f

        // 标题
        text.typeface = PersonaP3.cnHeavy
        text.textSize = titleSize
        text.color = 0xFFFFFFFF.toInt()
        val title = wrapText(
            cur?.title ?: "—", titleSize, r - l - padL - padR, 1, true, PersonaP3.cnHeavy
        ).firstOrNull() ?: "—"
        canvas.drawText(title, l + padL, titleTop - text.fontMetrics.ascent, text)

        // 时间 | 还剩
        if (cur != null) {
            val remain = (cur.end - s.minute).coerceAtLeast(0)
            text.typeface = PersonaP3.body
            text.textSize = metaSize
            text.color = 0xFFA9C3F5.toInt()
            fm = text.fontMetrics
            val metaBase = metaTop - fm.ascent
            var x = l + padL
            val head = "${Slots.fmt(cur.start)} – ${Slots.fmt(cur.end)} · "
            canvas.drawText(head, x, metaBase, text)
            x += text.measureText(head)
            text.typeface = PersonaP3.cnBold
            text.color = PersonaP3.CYAN
            val tail = "还剩 $remain 分钟"
            canvas.drawText(tail, x, metaBase, text)
            x += text.measureText(tail)
            if (cur.place.isNotBlank()) {
                text.typeface = PersonaP3.body
                text.color = 0xFFA9C3F5.toInt()
                canvas.drawText(" · ${cur.place}", x, metaBase, text)
            }
        } else {
            text.typeface = PersonaP3.body
            text.textSize = metaSize
            text.color = 0xFFA9C3F5.toInt()
            canvas.drawText("这一天的时段已经走完", l + padL, metaTop - text.fontMetrics.ascent, text)
        }

        // 进度槽
        val trackL = l + padL
        val trackR = r - padR
        fill.color = 0x29FFFFFF
        canvas.drawRect(trackL, trackTop, trackR, trackTop + dp(4f), fill)
        if (cur != null && cur.duration > 0) {
            val pct = ((s.minute - cur.start).toFloat() / cur.duration).coerceIn(0f, 1f)
            fill.color = PersonaP3.CYAN
            canvas.drawRect(trackL, trackTop, trackL + (trackR - trackL) * pct, trackTop + dp(4f), fill)
        }

        // 接下来一行
        text.typeface = PersonaP3.body
        text.textSize = nextSize
        text.color = 0xFFC9DBFF.toInt()
        val nextBase = nextTop - text.fontMetrics.ascent
        if (next != null) {
            val head = "接下来 ${Slots.fmt(next.start)} · "
            canvas.drawText(head, l + padL, nextBase, text)
            text.typeface = PersonaP3.cnBold
            text.color = 0xFFFFFFFF.toInt()
            canvas.drawText(next.title, l + padL + text.measureText(head), nextBase, text)
        } else {
            canvas.drawText("今天没有下一项了", l + padL, nextBase, text)
        }

        return cardBottom
    }

    /**
     * 时间线：整天的格子铺在一条**可上下滑动**的视口里。
     *
     * 以前这里只画「当前格附近 2–6 行」、不能滚：那一屏确实只回答「现在干什么」，
     * 但想看「上午那节课几点下课」就只能去默认外观。现在改成整条铺开、视口裁剪，
     * 打开时仍然把**当前格摆在正中间**（[PersonaTodayModel.initialScroll]），
     * 想往回翻或者往后看就上下滑；右上角有一条细滚动条表示还有多少没看到。
     *
     * 返回值是视口下沿，明天卡接着往下排。
     */
    private fun drawTimeline(
        canvas: Canvas, w: Float, top: Float, h: Float, s: PersonaTodayState
    ): Float {
        val l = dp(edge)
        val r = w - dp(edge)

        val hintY = top + dp(10f)
        val rowsTop = hintY + dp(6f)
        val rowsSpace = (h - dp(8f) - dp(76f) - dp(12f)) - rowsTop
        if (rowsSpace < dp(60f)) return top

        // 一屏放几行仍按剩余高度算：矮屏少放几行，但内容不再被砍掉，滑一下就有
        val maxRows = (rowsSpace / dp(42f)).toInt().coerceIn(2, 6)
        val rowH = (rowsSpace / maxRows).coerceAtMost(dp(52f))
        val contentH = rowH * s.moments.size
        val scrollMax = PersonaTodayModel.maxScroll(contentH, rowsSpace)

        // 数据变了（换了一天、导入了课表）就把「现在」重新摆到视口中间；
        // 只是 30 秒走字的话保持用户当前的滚动位置，别把人正在看的地方拽走
        val key = s.date.hashCode() * 31 + s.moments.hashCode()
        if (key != timelineKey) {
            timelineKey = key
            scrollY = PersonaTodayModel.initialScroll(
                PersonaTodayModel.indexAt(s.moments, s.minute), rowH, rowsSpace, contentH
            )
        }
        scrollY = scrollY.coerceIn(0f, scrollMax)

        // 交给触摸处理（拖 / 甩）用
        tlTop = rowsTop
        tlHeight = rowsSpace
        tlScrollMax = scrollMax
        tlRowH = rowH

        // 「更早」提示：**滚上去之后才出现**。不滚的时候上面就是第一格，
        // 写「更早」等于说这里还藏着东西
        if (scrollY > dp(2f)) {
            text.typeface = PersonaP3.body
            text.textSize = ts(7f)
            text.letterSpacing = 0.10f
            text.color = 0xFF8A9AB8.toInt()
            text.textAlign = Paint.Align.LEFT
            canvas.drawText("更早", l, hintY, text)
            text.letterSpacing = 0f
            val hintW = text.measureText("更早")
            stroke.color = PersonaP3.PLINE
            stroke.strokeWidth = dp(1f)
            canvas.drawLine(l + hintW + dp(6f), hintY - dp(2.5f), r, hintY - dp(2.5f), stroke)
        } else {
            stroke.color = PersonaP3.PLINE
            stroke.strokeWidth = dp(1f)
            canvas.drawLine(l, hintY - dp(2.5f), r, hintY - dp(2.5f), stroke)
        }

        // 视口裁剪：滑出去的行走纸外面去，不能压到「此刻」卡和明天卡
        val save = canvas.save()
        canvas.clipRect(l - dp(12f), rowsTop, r + dp(12f), rowsTop + rowsSpace)

        // 只画可见的那几行（一天的格子有 20 来个，全画一遍纯属浪费）
        val first = ((scrollY / rowH).toInt() - 1).coerceAtLeast(0)
        val last = (((scrollY + rowsSpace) / rowH).toInt() + 1)
            .coerceAtMost(s.moments.size - 1)
        if (first > last) {
            canvas.restoreToCount(save)
            return rowsTop + rowsSpace
        }

        val railX = l + dp(57f)
        val dotCx = l + dp(64f)
        val bodyX = l + dp(80f)
        val durRight = r

        // 竖轨：从第一格圆点到最后一格圆点（按内容坐标，再减滚动量）
        stroke.color = 0xFFD3DEED.toInt()
        stroke.strokeWidth = dp(1f)
        canvas.drawLine(
            railX, rowsTop - scrollY + rowH * 0.5f,
            railX, rowsTop - scrollY + contentH - rowH * 0.5f, stroke
        )

        for (i in first..last) {
            val m = s.moments[i]
            val state = PersonaTodayModel.stateOf(m, s.minute)
            val isNow = state == PersonaTodayModel.RowState.NOW
            val isPast = state == PersonaTodayModel.RowState.PAST
            val y = rowsTop + i * rowH - scrollY

            if (isNow) {
                // 当前行向左探出 10dp，压一条青色的横向渐隐光带
                val nl = l - dp(10f)
                val nr = r + dp(10f)
                fill.shader = LinearGradient(
                    nl, 0f, nr, 0f,
                    intArrayOf(0x2918D8F8, 0x0818D8F8),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawRect(nl, y, nr, y + rowH, fill)
                fill.shader = null
                fill.color = PersonaP3.MAGENTA
                canvas.drawRect(nl, y, nl + dp(2f), y + rowH, fill)
            }

            val dim = if (isPast) 1f - 0.48f else 1f

            // 时间：开始 + 结束
            text.typeface = PersonaP3.titleFace
            text.textSize = ts(12f)
            text.isFakeBoldText = true
            text.color = if (isNow) PersonaP3.MAGENTA
            else ColorUtils.setAlphaComponent(PersonaP3.PINK, (255 * dim).toInt())
            val cy = y + rowH * 0.5f
            var fm = text.fontMetrics
            canvas.drawText(
                Slots.fmt(m.start), l, cy - dp(4f) - (fm.ascent + fm.descent) / 2f, text
            )
            text.isFakeBoldText = false
            text.typeface = PersonaP3.body
            text.textSize = ts(6.5f)
            text.color = ColorUtils.setAlphaComponent(PersonaP3.BAR_LABEL, (255 * dim).toInt())
            fm = text.fontMetrics
            canvas.drawText(
                Slots.fmt(m.end), l, cy + dp(7f) - (fm.ascent + fm.descent) / 2f, text
            )

            // 圆点：当前格青 + 一圈光晕
            if (isNow) {
                fill.color = 0x3818D8F8
                canvas.drawCircle(dotCx, cy, dp(5.5f), fill)
                fill.color = PersonaP3.CYAN
            } else {
                fill.color = ColorUtils.setAlphaComponent(
                    0xFFB8C7DD.toInt(), (255 * dim).toInt()
                )
            }
            canvas.drawCircle(dotCx, cy, dp(3f), fill)

            // 课名 / 备注。两行以行中心为界上下分：课名的基线上移半个行高，
            // 备注压在它下面 11dp —— 不这么分开的话（课名减 ascent、备注加常数）
            // 两行会画在同一条基线上，字直接叠在一起
            text.typeface = PersonaP3.cnHeavy
            text.textSize = ts(11.5f)
            text.color = if (isNow) 0xFF071033.toInt()
            else ColorUtils.setAlphaComponent(PersonaP3.PINK, (255 * dim).toInt())
            val nameW = durRight - bodyX - dp(8f) - dp(46f)
            wrapText(m.title, text.textSize, nameW, 1, true, PersonaP3.cnHeavy).firstOrNull()
                ?.let { canvas.drawText(it, bodyX, cy - dp(1f), text) }

            val note = buildString {
                if (m.place.isNotBlank()) append(m.place)
                if (m.note.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(m.note)
                }
            }
            if (note.isNotBlank()) {
                text.typeface = PersonaP3.body
                text.textSize = ts(7.5f)
                text.color = ColorUtils.setAlphaComponent(PersonaP3.PSUB, (255 * dim).toInt())
                wrapText(note, text.textSize, nameW, 1, false, PersonaP3.body).firstOrNull()
                    ?.let { canvas.drawText(it, bodyX, cy + dp(11f), text) }
            }

            // 时长小片（右端）
            val dur = durationLabel(m.duration)
            text.typeface = PersonaP3.body
            text.textSize = ts(7f)
            text.isFakeBoldText = true
            val chipW = text.measureText(dur) + dp(12f)
            val chipH = dp(14f)
            val chipL = durRight - chipW
            val chipT = cy - chipH / 2f
            fill.color = if (isNow) PersonaP3.CYAN else ColorUtils.setAlphaComponent(
                0xFFE3EBF8.toInt(), (255 * dim).toInt()
            )
            canvas.drawRect(chipL, chipT, durRight, chipT + chipH, fill)
            text.style = Paint.Style.FILL
            text.color = if (isNow) 0xFF02124F.toInt()
            else ColorUtils.setAlphaComponent(0xFF6A7C9C.toInt(), (255 * dim).toInt())
            text.textAlign = Paint.Align.CENTER
            fm = text.fontMetrics
            canvas.drawText(dur, (chipL + durRight) / 2f, cy - (fm.ascent + fm.descent) / 2f, text)
            text.textAlign = Paint.Align.LEFT
            text.isFakeBoldText = false

            // 行分隔线（当前行不画：它自己有一条光带）
            if (!isNow) {
                stroke.color = PersonaP3.PLINE
                stroke.strokeWidth = dp(1f)
                canvas.drawLine(l, y + rowH, r, y + rowH, stroke)
            }

            // 点一行的命中区（行高本来就够手指点；滑动时不会走到这里，见 onTouchEvent）
            val hitTop = y
            val hitBottom = y + rowH
            hits += Hit(
                RectF(l, hitTop, r, hitBottom),
                {
                    onMomentClick?.invoke(
                        m,
                        screenRect(l - dp(10f), hitTop, r, hitBottom)
                    )
                }
            )
        }

        canvas.restoreToCount(save)

        // 滚动条：只表示「还有多少没看到」，不参与触摸（手指在纸面上直接拖）
        if (scrollMax > 0f) {
            val barX = r + dp(6f)
            val barW = dp(3f)
            val minThumb = dp(24f)
            val thumbH = (rowsSpace * rowsSpace / contentH).coerceIn(minThumb, rowsSpace)
            val thumbTop = rowsTop + (rowsSpace - thumbH) * (scrollY / scrollMax)
            fill.shader = null
            fill.color = 0x2E18D8F8
            canvas.drawRect(barX, rowsTop, barX + barW, rowsTop + rowsSpace, fill)
            fill.color = 0xB318D8F8.toInt()
            canvas.drawRect(barX, thumbTop, barX + barW, thumbTop + thumbH, fill)
        }

        return rowsTop + rowsSpace
    }

    /**
     * 明天卡：深藏青底 + 亮蓝描边，左边一列「几点起 / 什么日型 / 几节课 / 第一件事」，
     * 右边一枚「设闹钟」按钮。
     *
     * 按钮上的字会跟着**已经设好的闹钟**变（「06:55 · 改」）——
     * 这类「点完就忘」的操作，不写清楚设没设，用户只能去闹钟页翻。
     */
    private fun drawTomorrow(canvas: Canvas, w: Float, top: Float, s: PersonaTodayState) {
        val t = s.tomorrow ?: return
        val l = dp(edge)
        val r = w - dp(edge)
        val hgt = dp(76f)

        fill.shader = LinearGradient(
            l, top, r, top + hgt,
            intArrayOf(0xFF12245E.toInt(), 0xFF081234.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(l, top, r, top + hgt, fill)
        fill.shader = null
        stroke.color = 0xFF5E8BFF.toInt()
        stroke.strokeWidth = dp(1f)
        canvas.drawRect(l, top, r, top + hgt, stroke)

        val padL = dp(12f)
        val btnW = dp(56f)
        val btnH = dp(26f)
        val btnL = r - dp(12f) - btnW
        val btnT = top + (hgt - btnH) / 2f

        // 右上角的小标签
        drawTag(
            canvas, "TOMORROW",
            l + padL, top + dp(15f), ts(6.5f), PersonaP3.CYAN, 0.18f
        )

        // 日期跟在 TOMORROW 后面
        text.typeface = PersonaP3.body
        text.textSize = ts(7f)
        text.color = 0xFF7FA6E8.toInt()
        text.textAlign = Paint.Align.LEFT
        val tagW = runWidth("TOMORROW", ts(6.5f), true, 0.18f)
        canvas.drawText(
            "· ${t.date.monthValue}/${t.date.dayOfMonth} ${weekdayCn(t.date.dayOfWeek.value)}",
            l + padL + tagW + dp(6f), top + dp(15f), text
        )

        // 起床时刻：这一卡的主信息
        text.typeface = PersonaP3.cnHeavy
        text.textSize = ts(15f)
        text.color = 0xFFFFFFFF.toInt()
        val wake = t.wakeMinute?.let { "${Slots.fmt(it)} 起床" } ?: "作息里没有起床时间"
        canvas.drawText(wake, l + padL, top + dp(34f), text)

        // 日型 / 几节课
        text.typeface = PersonaP3.body
        text.textSize = ts(7.5f)
        text.color = 0xFFA9C3F5.toInt()
        val metaY = top + dp(46f)
        val head = "第 ${t.week} 周 · "
        canvas.drawText(head, l + padL, metaY, text)
        var x = l + padL + text.measureText(head)
        text.typeface = PersonaP3.cnBold
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(t.dayType, x, metaY, text)
        x += text.measureText(t.dayType)
        text.typeface = PersonaP3.body
        text.color = 0xFFA9C3F5.toInt()
        val tail = " · " + if (t.courseCount > 0) "${t.courseCount} 节课" else "没有课"
        canvas.drawText(tail, x, metaY, text)

        // 第一件事
        t.firstItem?.let { first ->
            text.typeface = PersonaP3.body
            text.textSize = ts(7f)
            text.color = 0xFF7FA6E8.toInt()
            val one = wrapText(first, text.textSize, btnL - padL - l - dp(8f), 1, false, PersonaP3.body)
            one.firstOrNull()?.let { canvas.drawText(it, l + padL, top + dp(62f), text) }
        }

        // 按钮
        fill.color = 0x384884FF
        canvas.drawRect(btnL, btnT, btnL + btnW, btnT + btnH, fill)
        stroke.color = 0xFF6FA8FF.toInt()
        stroke.strokeWidth = dp(1f)
        canvas.drawRect(btnL, btnT, btnL + btnW, btnT + btnH, stroke)
        text.typeface = PersonaP3.cnBold
        text.textSize = ts(9f)
        text.color = 0xFFFFFFFF.toInt()
        text.textAlign = Paint.Align.CENTER
        val label = t.alarmText?.let { "$it · 改" } ?: "设闹钟"
        val fm = text.fontMetrics
        canvas.drawText(
            label, btnL + btnW / 2f, btnT + btnH / 2f - (fm.ascent + fm.descent) / 2f, text
        )
        text.textAlign = Paint.Align.LEFT

        hits += Hit(
            // 只认按钮那一块（往外扩 6dp 好点）：整张卡都能设闹钟的话，
            // 想看清「明天几点起」而随手点一下的人会被直接塞一条闹钟
            RectF(btnL - dp(6f), btnT - dp(6f), r, btnT + btnH + dp(6f)),
            { onWakeAlarm?.invoke() }
        )
    }

    // ---------------------------------------------------------------- 小工具

    /** 45 → 「45 分」，95 → 「1 时 35 分」，和卡片里的时长写法一致 */
    private fun durationLabel(minutes: Int): String {
        if (minutes <= 0) return ""
        if (minutes < 60) return "$minutes 分"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h 时" else "$h 时 $m 分"
    }

    /** 局部矩形 → 屏幕矩形（弹卡动画的圆心要屏幕坐标） */
    private fun screenRect(l: Float, t: Float, r: Float, b: Float): RectF {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return RectF(loc[0] + l, loc[1] + t, loc[0] + r, loc[1] + b)
    }

    // ================================================================ 手势

    /**
     * 时间线可以上下拖 / 甩，其它地方仍是点击。
     *
     * 判定顺序很重要：**先在 DOWN 时决定这次手势归谁**（手指落在时间线视口内且内容
     * 确实能滚，才接管），否则一次点击会被当成「拖了 0 像素」而丢掉。
     * 接管之后，MOVE 超过 touchSlop 就划为拖动，松手时不再触发晶格的点击 ——
     * 不然「想滑一下」会顺手弹出一张卡片。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchY = event.y
                draggingTimeline = false

                val inTimeline = tlScrollMax > 0f && tlHeight > 0f &&
                        event.y >= tlTop - dp(4f) && event.y <= tlTop + tlHeight + dp(4f)
                if (inTimeline) {
                    // 手指一按就停住甩动：这是「抓住内容」的标准手感
                    scroller.forceFinished(true)
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().apply { addMovement(event) }
                } else {
                    velocity?.recycle()
                    velocity = null
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val vt = velocity ?: return true
                vt.addMovement(event)
                val dy = event.y - lastTouchY
                lastTouchY = event.y

                if (!draggingTimeline) {
                    val totalDy = event.y - downY
                    val totalDx = event.x - downX
                    // 竖向为主才算拖时间线；横向为主的划动不算（这一页没有横滑动作）
                    if (abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx)) {
                        draggingTimeline = true
                    }
                }
                if (draggingTimeline) {
                    scrollY = (scrollY - dy).coerceIn(0f, tlScrollMax)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val vt = velocity
                velocity = null

                if (draggingTimeline) {
                    if (vt != null && event.actionMasked == MotionEvent.ACTION_UP) {
                        vt.computeCurrentVelocity(1000)
                        val vy = vt.yVelocity
                        // 甩出去：手指往上滑（vy < 0）= 往后看，滚动量增加
                        if (abs(vy) > dp(120f)) {
                            scroller.fling(
                                0, scrollY.toInt(), 0, (-vy).toInt(),
                                0, 0, 0, tlScrollMax.toInt()
                            )
                            invalidate()
                        }
                    }
                    vt?.recycle()
                    draggingTimeline = false
                    performClick()
                    return true
                }

                vt?.recycle()
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) < dp(14f) && abs(dy) < dp(14f)) {
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

    /** 甩动由 [OverScroller] 驱动，这里每帧把位置抄回来 */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceIn(0f, tlScrollMax)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        // 页面销毁时别留一个还在跑的动画（它会一直 invalidate 一个没人看的 View）
        scroller.abortAnimation()
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
