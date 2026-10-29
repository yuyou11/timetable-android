package com.yxz.timetable.ui.persona

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
import android.view.View
import com.yxz.timetable.R
import com.yxz.timetable.data.Lunar
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * P3 风格的「今天」页头部日期横幅（彩蛋），照 `docs/persona3/ref/今天.png` 重建，
 * 网页原型是 `p3web/today-hud.html` —— 三处坐标一一对应，改一处记得同步另外两处。
 *
 * ## 画布坐标
 *
 * 原型画布是 586×183（原图视频帧去掉黑边后的画面区），这里沿用同一坐标系：
 * [s] = 1 设计像素 = 多少真实像素，所有常量都写在设计坐标里，乘上 [s] 再画。
 * 高度跟着宽走（[onMeasure] 锁死 586:183），所以 x、y 用同一个 [s] 就够了。
 *
 * ## 和原型的元素对应
 *
 * | 原型 | 这里 |
 * |---|---|
 * | 透明底（按需求：横幅不带底色，露出页面背景；原型的夜蓝渐变底已去掉） | ——（不画底，文字色从资源解析，日夜间自动跟） |
 * | 大号 WEEKDAY 底字 | 按星期名缩号恰好铺满画布宽，帽顶离顶边 6 设计像素，完整显示 |
 * | 潮汐光带（6 个半透明正圆相叠） | **已删**：页面顶上现在是海报主角的脸，任何一层蓝色罩上去都会把人糊掉 |
 * | 三角标 + 周几字 | 黑三角旗 + 旗子里画当天的「一..日」；字比旗子大，左半截探出旗子压在背景上（描深边保可见），右半截被旗子右边缘切住 —— 几何与原型逐点一致 |
 * | 黄月亮 | 辉光 + 黄盘 + 暗面；暗面按农历日推受照比例，界线用「半圆 + 半椭圆」的真几何画 |
 * | 白色斜体日期 | sans-serif-black + textSkewX，长日期自动缩号 |
 * | 描边青色时段（夜晚） | 先 STROKE 后 FILL 两遍 |
 * | ——（原型没有） | 左下角补了「年份 · 第 N 教学周」，原头部那行字的信息不能丢 |
 *
 * ## 为什么文字不跟 sp
 *
 * 横幅高度由宽度锁死，是一个固定比例的「画」，不是排版的文字块：
 * 跟随系统字体缩放会把大字挤出画面（日历页那套 compact 降级在这里没地方施展）。
 * 代价是无障碍用户拿不到大字号 —— 补偿是 [contentDescription] 把整条横幅的信息
 * 完整念出来，每次数据变化都同步。
 *
 * ## 重画时机
 *
 * 没有常驻动画：只在日期 / 教学周 / 时段真的变了才 [invalidate]。
 * 「今天」页本来就有 30 秒一次的 render 轮询，喂一次数据就够了。
 */
class PersonaDateHud @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ---------------------------------------------------------------- 对外状态

    /**
     * 深色底模式：横幅落在 P3 全页深蓝上时，日期 / 星期底字 / 左下角小字换浅色。
     *
     * 横幅本来是照游戏里「压在夜景画面上」画的，浅色页面用的是深字版
     * （textPrimary / textSecondary，日夜间自动跟）；这里补上真正的深底浅字版。
     * 时段字（青字深描边）和月亮不用换 —— 它们本来就按深底调的。
     */
    var dark: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 农历日（1..30），月相靠它。随 [date] 一起更新 */
    private var lunarDay = Lunar.of(LocalDate.now()).day

    /** 横幅显示的日子。改它会自己重画 */
    var date: LocalDate = LocalDate.now()
        set(value) {
            if (field == value) return
            field = value
            lunarDay = Lunar.of(value).day
            syncDescription()
            invalidate()
        }

    /** 「第 N 教学周」，画在左下角那行小字里 */
    var teachingWeek: Int = 0
        set(value) {
            if (field == value) return
            field = value
            syncDescription()
            invalidate()
        }

    /** 当前时刻，只用来推时段文案（清晨 / 上午 / …）。时段没变就不重画 */
    var clock: LocalDateTime = LocalDateTime.now()
        set(value) {
            val phaseChanged = phaseOf(field.hour) != phaseOf(value.hour)
            field = value
            if (phaseChanged) {
                syncDescription()
                invalidate()
            }
        }

    private fun syncDescription() {
        contentDescription = "${date.year}年${date.monthValue}月${date.dayOfMonth}日 " +
            "${weekdayCn(date.dayOfWeek.value)} · 第 $teachingWeek 教学周 · ${phaseOf(clock.hour)}"
    }

    // ---------------------------------------------------------------- 尺寸 / 笔

    /** 1 设计像素 = 多少真实像素，见类注释。onSizeChanged 之后才有意义 */
    private var s = 0f

    /** 外部硬塞了不按比例的高度时，把设计稿区域往下挪这么多 */
    private var letterbox = 0f

    /**
     * 左右各「出血」多少像素（值 = 页面的 `page_padding`）。
     *
     * 横幅在布局里是**全宽子控件**（fragment_today.xml 那层不带横向内边距），
     * 但**内容**仍按 586 宽的设计稿排：所有坐标先平移 [bleed]，横向比例也按
     * `(宽 − 2×bleed)` 算。原来全宽的是潮汐光带，光带删掉之后它只剩排版职责 ——
     * 让日期、三角标、月亮、左下角小字跟页面其它内容的左右边线对齐。为 0 时
     * 内容框 = 视图。
     */
    var bleed: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** 内容框宽度：视图宽减去左右两条 [bleed]，兜一个下限免得除零 */
    private fun contentWidth(w: Int): Float = (w - 2f * bleed).coerceAtLeast(1f)

    // 文字颜色跟页面走（资源层自动切日/夜间）：横幅透明，只留元素
    private val textPrimary = context.getColor(R.color.text_primary)
    private val textSecondary = context.getColor(R.color.text_secondary)

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 三角标里的周几字：白字、居中（x 坐标就是旗子的对称轴） */
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    private val weekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        isSubpixelText = true
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        isSubpixelText = true
        textSkewX = -0.1405f      // 原型的 skewX(-8°)
    }
    private val phasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        isSubpixelText = true
        letterSpacing = 0.375f    // 原型 letter-spacing 12px @ 32px
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSubpixelText = true
    }

    // 位置相关的 shader / 裁剪路径，onSizeChanged 时建一次
    private var glowShader: Shader? = null
    private var moonYShader: Shader? = null
    private var moonBShader: Shader? = null

    /**
     * 三角标的剪切区：只保留「三角形右边缘的左侧」（原型的 markerClip 同一顶点）。
     * 注意不是三角形本身 —— 周几字的左半截要靠它探出旗子、压在背景上。
     */
    private var markerClip: Path? = null

    /** 暗面圆盘的裁剪范围 = 月盘圆。不裁的话满月前后它移出月盘，会压到日期和时段字上 */
    private var moonClip: Path? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        s = contentWidth(w) / DESIGN_W
        letterbox = ((h - DESIGN_H * s) / 2f).coerceAtLeast(0f)
        buildShaders()
        markerClip = Path().apply {
            moveTo(-20 * s, 0f)
            lineTo(406 * s, 46 * s)
            lineTo(375 * s, 93 * s)
            lineTo(-20 * s, 183 * s)
            close()
        }
        moonClip = Path().apply {
            addCircle(MOON_X * s, MOON_Y * s, MOON_R * s, Path.Direction.CW)
        }
    }

    private fun buildShaders() {
        // 辉光：半径 61 = 黄盘 43 + 高斯模糊 σ9 的可见范围
        glowShader = RadialGradient(
            MOON_X * s, MOON_Y * s, 61 * s,
            intArrayOf(MOON_GLOW, MOON_GLOW, Color.TRANSPARENT),
            floatArrayOf(0f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        moonYShader = RadialGradient(
            (MOON_X - 0.20f * MOON_R) * s, (MOON_Y - 0.32f * MOON_R) * s, 1.9f * MOON_R * s,
            intArrayOf(MOON_Y0, MOON_Y1, MOON_Y2), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        moonBShader = RadialGradient(
            (MOON_X - 0.64f * MOON_R) * s, (MOON_Y - 0.40f * MOON_R) * s, 1.4f * MOON_R * s,
            intArrayOf(MOON_B0, MOON_B1, Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        // 高度锁死 586:183（设计稿比例）；只有父容器硬给 EXACTLY 时才听它的
        if (hMode == MeasureSpec.EXACTLY) {
            setMeasuredDimension(w, h)
        } else {
            // 高度按**内容框**算，不按视图宽 —— 否则多出来的 bleed 会把横幅整体拉高
            setMeasuredDimension(w, (contentWidth(w) * DESIGN_H / DESIGN_W).roundToInt())
        }
    }

    // ================================================================ 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 全部元素画在「内容框」里（平移到 bleed、比例扣掉两条 bleed）。
        // 横幅本身不带底色也不带光带 —— 页面顶上是海报主角的脸，
        // 任何一层蓝色罩上去都会把人糊掉，所以这里只有字、三角标和月亮。
        canvas.save()
        canvas.translate(bleed, letterbox)
        drawWeekday(canvas)
        canvas.restore()

        val save = canvas.save()
        canvas.translate(bleed, letterbox)

        drawMarker(canvas)
        drawMoon(canvas)
        drawDate(canvas)
        drawPhase(canvas)
        drawCaption(canvas)

        canvas.restoreToCount(save)
    }

    /**
     * 大号星期底字：按当天星期名**缩号到恰好铺满画布宽** —— 再长的 WEDNESDAY 也完整显示，
     * 短的词几乎不缩。帽顶钉在离顶边 6 设计像素处，顶部不再冒出画面被裁。
     */
    private fun drawWeekday(canvas: Canvas) {
        val label = WEEKDAYS[date.dayOfWeek.value]
        weekdayPaint.textSize = 150 * s
        // measureText 和字号成正比，缩放系数可以直接除出来
        weekdayPaint.textSize *= DESIGN_W * s / weekdayPaint.measureText(label)
        weekdayPaint.color = Color.WHITE
        // 深蓝底上白字底纹要略亮一点才看得见（浅底深字 0.10，深底白字 0.13）
        weekdayPaint.alpha = if (dark) 33 else 26
        weekdayPaint.textAlign = Paint.Align.CENTER
        // sans-serif-black 的大写帽高约 0.72em（Roboto 字体度量）
        val baseline = 6f * s + 0.72f * weekdayPaint.textSize
        canvas.drawText(label, DESIGN_W / 2f * s, baseline, weekdayPaint)
    }

    /**
     * 三角标 + 当天的周几字。几何照原型（today-hud.html）一比一：
     *
     *  · 字比旗子**大**（字号 34，占满原来三条杠那块 32×35 的区域）；
     *  · 剪切区不是三角形，而是「三角形**右边缘的左侧**」这个多边形 ——
     *    于是字的**左半截探出旗子**、直接压在背景上，**右半截钻进旗子里**、
     *    齐着右边缘被切住（HTML 注释原话：三条白杠左边探到三角形外面，原图如此）；
     *  · 探出去的左半截落在海报上，白字会没进亮蓝天里 —— 所以照时段字的做法
     *    先描一圈旗子同色的深边再填白，旗子里那半截看不出来，旗子外那半截有轮廓。
     *
     * 字按当天周几画（一..日）。原型钉死三条杠 = 「三」，因为原图截的是周三那一帧。
     */
    private fun drawMarker(canvas: Canvas) {
        shapePaint.shader = null
        shapePaint.color = TRI_INK
        val tri = Path().apply {
            moveTo(344 * s, 46 * s)
            lineTo(406 * s, 46 * s)
            lineTo(375 * s, 93 * s)
            close()
        }
        canvas.drawPath(tri, shapePaint)

        val clip = markerClip ?: return
        val label = WEEKDAY_KANJI[date.dayOfWeek.value % 7]
        markerPaint.textSize = 34 * s
        val save = canvas.save()
        canvas.clipPath(clip)
        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = 3f * s
        markerPaint.strokeJoin = Paint.Join.ROUND
        markerPaint.color = TRI_INK
        canvas.drawText(label, 372 * s, 81 * s, markerPaint)
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = Color.WHITE
        canvas.drawText(label, 372 * s, 81 * s, markerPaint)
        canvas.restoreToCount(save)
    }

    /**
     * 月亮：辉光 + 黄盘（带一点环境蓝）+ 暗面。
     *
     * 暗面按**真实的明暗界线**画：暗侧半圆 + 界线半椭圆，椭圆短轴 = |cos θ|·r
     * （θ = 相位角，f = (1−cos θ)/2 是受照比例）。月牙时界线往亮侧鼓（暗区越过
     * 直径），凸月时往暗侧鼓（暗区缩成一条边），满月时椭圆与月盘重合、暗区归零。
     *
     * 旧实现把暗面圆盘整体往暗侧平移 2f·r，f ≳ 0.91 之后圆盘完全移出月面 ——
     * 农历十三到十八整整六天全画成 100% 满月，月牙形状也是错的。现在按真几何来：
     * 十四有一条细黑边、十五十六基本全亮（「十五的月亮十六圆」，这两天本来
     * 就都是圆的）、十七起黑边从另一侧慢慢长回来。
     */
    private fun drawMoon(canvas: Canvas) {
        val cx = MOON_X * s
        val cy = MOON_Y * s
        val r = MOON_R * s

        moonPaint.shader = glowShader
        canvas.drawCircle(cx, cy, 61 * s, moonPaint)

        moonPaint.shader = moonYShader
        canvas.drawCircle(cx, cy, r, moonPaint)
        moonPaint.shader = moonBShader
        canvas.drawCircle(cx, cy, r, moonPaint)

        val f = moonLitFraction(lunarDay)
        val k = (1 - 2f * f).coerceIn(-1f, 1f)      // cos θ：+1 = 朔（全暗），-1 = 望（全亮）
        // 上弦（农历前半月）亮面在右、暗面朝左；下弦反之 —— 北半球看月就是这样
        val darkDir = if (lunarDay <= 15) -1f else 1f

        val dark = Path()
        val disc = RectF(cx - r, cy - r, cx + r, cy + r)
        // 1) 暗侧半圆：从直径的一端沿月盘边走到另一端
        if (darkDir < 0) dark.addArc(disc, 90f, 180f) else dark.addArc(disc, 270f, 180f)
        // 2) 界线半椭圆：接在半圆终点后面折回来。短轴 |k|·r，鼓向哪边看月牙还是凸月
        val ka = (abs(k) * r).coerceAtLeast(0.5f)   // k≈0（弦月）时兜一个半像素，别画退化椭圆
        val term = RectF(cx - ka, cy - r, cx + ka, cy + r)
        val bulge = if (k > 0) -darkDir else darkDir
        when {
            darkDir < 0 && bulge > 0 -> dark.addArc(term, -90f, 180f)   // 顶→右→底
            darkDir < 0 && bulge < 0 -> dark.addArc(term, -90f, -180f)  // 顶→左→底
            bulge > 0                -> dark.addArc(term, 90f, -180f)   // 底→右→顶
            else                     -> dark.addArc(term, 90f, 180f)    // 底→左→顶
        }
        dark.close()

        moonPaint.shader = RadialGradient(
            cx + darkDir * r * 0.4f, cy - r * 0.15f, 1.8f * r,
            intArrayOf(MOON_D0, MOON_D1, MOON_D2), floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        // 暗面只许画在月盘里
        val save = canvas.save()
        moonClip?.let { canvas.clipPath(it) }
        canvas.drawPath(dark, moonPaint)
        canvas.restoreToCount(save)
        moonPaint.shader = null
    }

    /** 白色斜体日期。右缘钉在三角标左边，放不下就缩号（原型用 textLength 干这事） */
    private fun drawDate(canvas: Canvas) {
        val label = "${date.monthValue}/${date.dayOfMonth}"
        val x = 192 * s
        val avail = 336 * s - x
        datePaint.textSize = 74.3f * s
        datePaint.color = if (dark) Color.WHITE else textPrimary
        val measured = datePaint.measureText(label)
        if (measured > avail) datePaint.textSize *= avail / measured
        canvas.drawText(label, x, 93 * s, datePaint)
    }

    /** 时段文案：先描一圈深蓝边再填青色，压在暗底上才够亮（原型的 paint-order） */
    private fun drawPhase(canvas: Canvas) {
        val label = phaseOf(clock.hour)
        phasePaint.textSize = 32 * s
        phasePaint.style = Paint.Style.STROKE
        phasePaint.strokeWidth = 4 * s
        phasePaint.strokeJoin = Paint.Join.ROUND
        phasePaint.color = PHASE_STROKE
        canvas.drawText(label, 318 * s, 126 * s, phasePaint)
        phasePaint.style = Paint.Style.FILL
        phasePaint.color = PHASE_FILL
        canvas.drawText(label, 318 * s, 126 * s, phasePaint)
    }

    /** 左下角小字：年份 + 教学周。原头部那行日期字里的信息，HUD 里一样都不能少 */
    private fun drawCaption(canvas: Canvas) {
        captionPaint.textSize = 17 * s
        captionPaint.color = if (dark) CAPTION_DARK else textSecondary
        canvas.drawText("${date.year} · 第 $teachingWeek 教学周", 52 * s, 168 * s, captionPaint)
    }

    companion object {
        /** 设计稿尺寸，见 p3web/today-hud.html 的 viewBox */
        const val DESIGN_W = 586f
        const val DESIGN_H = 183f

        /** ISO 星期（1=周一）→ 游戏里的英文星期底字 */
        val WEEKDAYS = arrayOf(
            "", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
        )

        /**
         * 三角旗里的周几字，下标 = ISO 星期 % 7：周一..周六 → 一..六，周日(7) → 日。
         * 原型那三条白杠其实是「三」—— 原图截的是周三那一帧，这里换成跟着日期走的真周几。
         */
        val WEEKDAY_KANJI = arrayOf("日", "一", "二", "三", "四", "五", "六")

        // ---- 三角标 ----
        private const val TRI_INK = 0xFF0A1026.toInt()

        // ---- 月亮 ----
        private const val MOON_X = 481.5f
        private const val MOON_Y = 86.5f
        private const val MOON_R = 39.5f
        private const val MOON_GLOW = 0x38F5CA00.toInt()   // 0.22 × 255 ≈ 56
        private const val MOON_Y0 = 0xFFFFD52E.toInt()
        private const val MOON_Y1 = 0xFFF5CA00.toInt()
        private const val MOON_Y2 = 0xFFE7B800.toInt()
        private const val MOON_B0 = 0x242F8DFF.toInt()     // 环境蓝 0.14 × 255 ≈ 36
        private const val MOON_B1 = 0x0D2F8DFF.toInt()     // 0.05 × 255 ≈ 13
        private const val MOON_D0 = 0xFF2A2B35.toInt()
        private const val MOON_D1 = 0xFF1F202A.toInt()
        private const val MOON_D2 = 0xFF191A23.toInt()

        // ---- 文字 ----
        // 日期/小字用页面的 text_primary / text_secondary（资源解析，日夜间自动跟）；
        // 时段字维持青字深描边 —— 浅底上靠描边保持可读，深底上也不违和
        private const val PHASE_FILL = 0xFF12E9F5.toInt()
        private const val PHASE_STROKE = 0xFF040E27.toInt()

        /** 深色底（P3 全页蓝）模式下左下角小字的颜色，和对话框说明字同一档 */
        private const val CAPTION_DARK = 0xFFA9BEE8.toInt()

        /**
         * 一天里的时段文案，照游戏「朝 / 昼 / 夕 / 夜」的思路切成两字词。
         * 边界故意留成纯函数 —— 算错了是「半夜显示上午」这种一眼能指出的错。
         */
        internal fun phaseOf(hour: Int): String = when (hour) {
            in 5..7 -> "清晨"
            in 8..10 -> "上午"
            in 11..12 -> "中午"
            in 13..16 -> "下午"
            in 17..18 -> "黄昏"
            in 19..22 -> "夜晚"
            else -> "深夜"
        }

        /**
         * 月相的受照比例（0 = 朔，1 = 望），农历日线性铺在一个 29.53 天的朔望月上。
         * 初一 0、初八 0.46、十五 0.99、廿三 0.52、三十 0 —— 和真实月相的对应足够了。
         */
        internal fun moonLitFraction(lunarDay: Int): Float {
            val p = (lunarDay - 1).coerceIn(0, 29) / 29.53
            return ((1 - cos(2 * PI * p)) / 2).toFloat()
        }
    }
}
