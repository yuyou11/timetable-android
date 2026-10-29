package com.yxz.timetable.ui.persona

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.yxz.timetable.R
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * P3 换皮页共用的调色板与绘制底座。
 *
 * ## 为什么单独一个文件
 *
 * 课表页（[PersonaWeekView]）和今天页（[PersonaTodayView]）是**同一套版式语言**：
 * 同一张蓝色水彩底、同一批颜色、同一套「重体斜切字 + 描深边 + 品红胶带」。
 * 这两页的网页样稿（`p3web/week-p3-v2.html`、`p3web/today-p3-v3.html`）里的
 * `:root` 变量是逐字相同的 —— 所以 Kotlin 这边也只能有一份。
 *
 * 日历页和闹钟页早于这套约定，各自在文件里留了一份颜色常量；
 * 新写的两页从这里取，**不要再往页面里抄第三份**。
 *
 * ## 尺寸：k 比例 + 不跟 fontScale
 *
 * 和 [PersonaCalendarView] 一样按 393dp 宽的设计稿等比缩放（`k`，夹在 [0.78, 1.35]）。
 * 但文字**不乘 fontScale**：这两页是「一张画」，不是排版的文字块 ——
 * 系统字号放到 1.5 倍时，课程块里 10dp 的课名会把方块撑破，
 * 而缩放后的画布没有地方重新排版。代价是无障碍用户拿不到大字号，
 * 补偿是每个自绘元素都设 [View.contentDescription]（见两个子类的实现）。
 */
object PersonaP3 {

    // ---- 蓝底（和日历页 / 闹钟页同一套，改一处要同步） ----
    const val BLUE_TOP = 0xFF1EA7FE.toInt()
    const val BLUE_MID = 0xFF0E49BE.toInt()
    const val BLUE_DEEP = 0xFF0524B1.toInt()

    /** 深墨蓝：标题描边、幽灵数字、胶带上的字 */
    const val INK = 0xFF04106B.toInt()

    // ---- 强调色（P3 模式下**不跟主题色走**，固定这三个） ----
    const val CYAN = 0xFF18D8F8.toInt()
    const val MAGENTA = 0xFFFF3FD8.toInt()
    const val RED = 0xFFFF5470.toInt()

    // ---- 白纸 ----
    const val PAPER = 0xFFF2F7FF.toInt()
    const val PAPER_BOTTOM = 0xFFEDF4FF.toInt()
    /** 白纸上的正文（近黑偏蓝） */
    const val PINK = 0xFF0A1230.toInt()
    /** 白纸上的次要正文 */
    const val PSUB = 0xFF5C6D8E.toInt()
    /** 白纸上的分隔线 */
    const val PLINE = 0xFFD5DFEF.toInt()
    /** 白纸上那行「06 / 12」的刻度字 */
    const val BAR_LABEL = 0xFF8A9AB8.toInt()

    // ---- 深底上的字 ----
    const val TEXT = 0xFFF4F8FF.toInt()
    const val DIM = 0xFF9FBCF2.toInt()
    const val STROKE = 0xFF5E8BFF.toInt()

    // ---- 卡片（今天页的「此刻」「明天」） ----
    const val CARD_TOP = 0xFF16265F.toInt()
    const val CARD_BOTTOM = 0xFF0A1740.toInt()

    /**
     * 标题字体：Impact 的替身。
     *
     * Android 没有 Impact，`sans-serif-condensed` 是最接近的窄体重体；
     * 原稿的字面更黑、更挤，所以再叠一个 [Paint.isFakeBoldText]。
     * 斜切不写在这里 —— 每处字的角度不同（-8° / -10° / -7°），
     * 由调用方给 [PersonaPageView.drawTitle]。
     */
    val titleFace: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    /** 中文标题（「周二」「第 8 周」这种）：sans-serif-black，够黑且汉字有字形 */
    val cnHeavy: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    /** 中文正文加粗 */
    val cnBold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** 说明文字 */
    val body: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    /** 小字标签（NOW / TOMORROW / WEEK DENSITY）：同一套「字距很开的小大写」 */
    val tag: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
}

/**
 * 一行里可以混色的文字。网页样稿里那些 `<b>` / `<i>` / `<span>` 就靠它表达：
 *
 *   「今天：周三 · **A 型日** · *有早八*」
 *
 * 不用 SpannableString 的原因：这一层全是 [Canvas.drawText]，
 * 没有 TextView 来解析 span。
 */
data class TextRun(
    val text: String,
    val color: Int,
    val bold: Boolean = true,
    /** 字号相对整行基准的倍数（一般是 1，个别小标签用 0.85） */
    val sizeScale: Float = 1f
)

/**
 * 两页 P3 自绘界面的底座：k 比例、三支笔、蓝底、标题字和混色行。
 *
 * 只放**两页完全一样**的东西。页面自己的版式一律留在子类里 ——
 * 底座一旦开始认「课表」「时间线」这些概念，第二个页面就会被它绑住。
 */
abstract class PersonaPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    protected val density = resources.displayMetrics.density

    /**
     * 全局比例：以 393dp 宽、[baseHeightDp] 高为 1.0。
     *
     * 取宽高两个比例的较小者 —— 窄屏和矮屏都该整体缩小，
     * 取大的那个会让另一维溢出。
     */
    protected var k = 1f
        private set

    /** 设计稿的基准高度（dp）。子类按「去掉状态栏和导航栏之后」的画面高度给 */
    protected open val baseHeightDp: Float get() = 720f

    /** 设计稿宽度，两页相同 */
    protected val baseWidthDp: Float get() = 393f

    protected fun dp(v: Float): Float = v * density * k
    protected fun dpInt(v: Float): Int = (v * density * k).roundToInt()

    /** 字号：**不乘 fontScale**，理由见 [PersonaP3] 的注释 */
    protected fun ts(v: Float): Float = v * density * k

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val kw = w / (baseWidthDp * density)
        val kh = h / (baseHeightDp * density)
        k = min(kw, kh).coerceIn(0.78f, 1.35f)
    }

    // ---------------------------------------------------------------- 三支笔
    //
    // 和日历页同一个约定：笔是**借来的**，同一个 Paint 反复改字号 / 颜色 / 对齐。
    // 所以每段文字之前必须把状态设全 —— 少设一个字号，下一段就会按上一段的字号画。

    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        textAlign = Paint.Align.LEFT
    }

    // ================================================================ 蓝底

    /**
     * 整页背景：三段竖渐变 + 两团柔光 + 极淡的斜向布纹。
     *
     * 两份样稿里这段 CSS 是逐字相同的（`.phone::before` / `::after`），
     * 所以只实现一次。布纹的透明度只有 4.5%，作用是让大面积蓝底**有织物的颗粒感**
     * —— 它是看得见但说不出来的那一层，删掉之后页面会显得「塑料」。
     *
     * ⚠️ 今天页已经改用 [drawPaintedBackdrop]（和闹钟页同一张手绘天空），
     * 这个函数现在只有课表页在用。
     */
    protected fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(PersonaP3.BLUE_TOP, PersonaP3.BLUE_MID, PersonaP3.BLUE_DEEP),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)

        // 右上那团亮蓝光：样稿是 radial-gradient(120% 62% at 82% -8%)
        fill.shader = RadialGradient(
            w * 0.82f, -h * 0.08f, w * 1.2f,
            intArrayOf(0xF01EA7FE.toInt(), 0x001EA7FE),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)

        // 左侧中段那团深蓝：让左下角暗下去，白纸和蓝底的接缝才有厚度
        fill.shader = RadialGradient(
            w * 0.04f, h * 0.38f, w * 0.9f,
            intArrayOf(0x8C0950DC.toInt(), 0x000950DC),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        drawCloth(canvas, w, h)
    }

    /**
     * 页面蓝底：直接铺 `R.drawable.bg_alarm_page`（渐变 + 一张手绘天空 + 顶部压暗）。
     *
     * 闹钟页从 v4 起就用它，今天页和日历页后来也换成它 —— **一个 App 里只能有一块蓝**，
     * 否则页签切来切去时底色会跳一下。那张天空是画出来的（青色云带、两朵气泡状的云），
     * 用代码描不出同一张图，只能共用同一份资源。
     *
     * @return false 表示资源取不到（理论上不会），调用方接 [drawBackdrop] 兜底
     */
    protected fun drawPaintedBackdrop(canvas: Canvas, w: Float, h: Float): Boolean =
        backdrop.draw(context, canvas, w, h)

    /** 背景不常换，取一次就存着 —— 每帧 getDrawable 是白造对象 */
    private val backdrop = PersonaBackdropCache(R.drawable.bg_alarm_page)

    /** 斜向细布纹：间隔 13dp、1dp 宽、白 11/255（≈ CSS 的 4.5% 再乘 0.5 整体不透明度） */
    private fun drawCloth(canvas: Canvas, w: Float, h: Float) {
        stroke.color = 0x0BFFFFFF
        stroke.strokeWidth = dp(1f)
        stroke.shader = null
        val cx = w / 2f
        val cy = h / 2f
        val ext = hypot(w, h)
        val save = canvas.save()
        // 渐变方向 116° 的条纹，线的走向是 26°（顺时针为正）
        canvas.rotate(26f, cx, cy)
        var x = cx - ext
        while (x < cx + ext) {
            canvas.drawLine(x, cy - ext, x, cy + ext, stroke)
            x += dp(13f)
        }
        canvas.restoreToCount(save)
    }

    /**
     * 一团潮汐光斑。[x] / [y] / [r] 是**设计稿 dp**（画的时候才乘 k），[color] 自带透明度。
     *
     * 做成一个类而不是 `Array<FloatArray>`：颜色是 Int、坐标是 Float，
     * 混在一个 FloatArray 里只能塞 alpha 再转回颜色，读代码的人得猜第 4 位是什么。
     */
    class Tide(val x: Float, val y: Float, val r: Float, val color: Int)

    /**
     * 潮汐光斑：样稿里那几团 `mix-blend-mode:screen` 的正圆。
     *
     * Canvas 没有 screen 混合模式（要 saveLayer + Xfermode，代价是一次全屏离屏绘制），
     * 这里直接用低透明度叠加近似 —— 底色本来就是亮的，两者肉眼几乎分不出来。
     */
    protected fun drawTides(canvas: Canvas, tides: Array<Tide>) {
        fill.shader = null
        fill.style = Paint.Style.FILL
        for (t in tides) {
            fill.color = t.color
            canvas.drawCircle(dp(t.x), dp(t.y), dp(t.r), fill)
        }
    }

    // ================================================================ 字

    /**
     * 大标题：先描一圈深墨蓝再盖白字（CSS 的 `-webkit-text-stroke` + `text-shadow`）。
     *
     * 描边宽度按「外扩量」给：STROKE 的笔画以路径为中心，所以实际要乘 2
     * 才和 CSS 的 `text-stroke:2.5px`（只往外长）看起来一样。
     *
     * 阴影用「先画一遍偏移的深色字」实现，不用 setShadowLayer ——
     * 后者在硬件加速下对大字会有噪点，而且和描边叠在一起时顺序不好控。
     */
    protected fun drawTitle(
        canvas: Canvas,
        s: String,
        x: Float,
        baseline: Float,
        size: Float,
        skew: Float,
        strokeWidth: Float,
        shadowDx: Float = 0f,
        shadowDy: Float = 0f,
        shadowColor: Int = 0x00000000,
        face: Typeface = PersonaP3.titleFace,
        align: Paint.Align = Paint.Align.LEFT,
        fakeBold: Boolean = true
    ) {
        // 量宽度要用真正画的那支笔：同一支笔既量又画，wrap_content 之类的坑不会出现
        text.typeface = face
        text.textSize = size
        text.textSkewX = skew
        text.textAlign = align
        text.isFakeBoldText = fakeBold
        text.letterSpacing = 0f

        if (shadowColor != 0) {
            text.style = Paint.Style.FILL
            text.color = shadowColor
            canvas.drawText(s, x + shadowDx, baseline + shadowDy, text)
        }
        if (strokeWidth > 0f) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = strokeWidth * 2f
            text.strokeJoin = Paint.Join.ROUND
            text.color = PersonaP3.INK
            canvas.drawText(s, x, baseline, text)
        }
        text.style = Paint.Style.FILL
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(s, x, baseline, text)
        text.isFakeBoldText = false
    }

    /**
     * 一行混色文字。返回结束时的 x，方便接着往右画。
     *
     * [baseline] 是**最后一段**的基线：混色行里各段的字号可能不同（[TextRun.sizeScale]），
     * 按第一段对齐会让小字浮起来。这里统一按各段自己的度量沉到同一条视觉基线上。
     */
    protected fun drawRuns(
        canvas: Canvas,
        runs: List<TextRun>,
        x: Float,
        baseline: Float,
        size: Float,
        boldFace: Typeface = PersonaP3.cnBold,
        skew: Float = 0f,
        letterSpacing: Float = 0f
    ): Float {
        var cx = x
        text.textSkewX = skew
        text.textAlign = Paint.Align.LEFT
        text.style = Paint.Style.FILL
        text.strokeWidth = 0f
        for (run in runs) {
            text.typeface = if (run.bold) boldFace else PersonaP3.body
            text.textSize = size * run.sizeScale
            text.letterSpacing = letterSpacing
            text.color = run.color
            canvas.drawText(run.text, cx, baseline, text)
            cx += text.measureText(run.text)
            // letterSpacing 是「字距」，measureText 不含最后那个间距，这里补上
            cx += letterSpacing * text.textSize * run.text.length
        }
        text.letterSpacing = 0f
        text.textSkewX = 0f
        return cx
    }

    /** 一段文字的宽度（用和 [drawRuns] 同一套字体度量量） */
    protected fun runWidth(s: String, size: Float, bold: Boolean, letterSpacing: Float = 0f): Float {
        text.typeface = if (bold) PersonaP3.cnBold else PersonaP3.body
        text.textSize = size
        text.letterSpacing = letterSpacing
        val w = text.measureText(s) + letterSpacing * size * s.length
        text.letterSpacing = 0f
        return w
    }

    /** 按宽度折行：中文逐字断（和浏览器对中文的行为一致），超出的部分用省略号收尾 */
    protected fun wrapText(
        s: String,
        size: Float,
        maxWidth: Float,
        maxLines: Int,
        bold: Boolean = true,
        face: Typeface = PersonaP3.cnBold
    ): List<String> {
        if (s.isEmpty() || maxWidth <= 0f) return emptyList()
        text.typeface = face
        text.textSize = size
        text.isFakeBoldText = bold
        val out = ArrayList<String>(maxLines)
        var start = 0
        while (start < s.length && out.size < maxLines) {
            var end = start + 1
            while (end <= s.length && text.measureText(s.substring(start, end)) <= maxWidth) end++
            var cut = (end - 1).coerceAtLeast(start + 1)
            // 最后一行还不是结尾 → 留一个字的位置放省略号
            if (cut < s.length && out.size == maxLines - 1) {
                while (cut > start + 1 && text.measureText(s.substring(start, cut) + "…") > maxWidth) cut--
                out.add(s.substring(start, cut).trimEnd() + "…")
                text.isFakeBoldText = false
                return out
            }
            out.add(s.substring(start, cut).trimEnd())
            start = cut
        }
        text.isFakeBoldText = false
        return out
    }

    /** 以 (cx, cy) 为视觉中心画一行字（不依赖基线） */
    protected fun drawCentered(canvas: Canvas, s: String, cx: Float, cy: Float) {
        val fm = text.fontMetrics
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    /** 小标签：大写字距（NOW / TOMORROW / WEEK DENSITY） */
    protected fun drawTag(
        canvas: Canvas,
        s: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        spacing: Float = 0.18f
    ) {
        text.typeface = PersonaP3.tag
        text.textSize = size
        text.letterSpacing = spacing
        text.style = Paint.Style.FILL
        text.color = color
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(s, x, y, text)
        text.letterSpacing = 0f
    }

    /**
     * 斜切平行四边形（黄/品红胶带、日期条）。给的是「正投影矩形 + 上边右移量」，
     * 相当于 CSS 的 `transform: skewX(-8deg)`。
     *
     * 为什么不用 canvas.skew()：它会把边框、文字一起斜掉，而这里只要**底**斜；
     * 而且 skew 之后 [Canvas.clipRect] 之类全跟着变，调用方很难再算坐标。
     */
    protected fun skewRect(
        left: Float, top: Float, right: Float, bottom: Float, skewPx: Float
    ): android.graphics.Path = android.graphics.Path().apply {
        moveTo(left + skewPx, top)
        lineTo(right + skewPx, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

    /** 斜切量：CSS skewX(deg) → 上边相对下边的水平偏移（高度 × tan） */
    protected fun skewOf(height: Float, deg: Float): Float = height * tan(Math.toRadians(deg.toDouble())).toFloat()
}

/**
 * 页面蓝底的那张图，带缓存。
 *
 * 日历、今天、课表三个整页自绘的页面都用 `R.drawable.bg_alarm_page`
 * （渐变 + 手绘天空 + 顶部压暗），和闹钟页是**同一份资源** —— 一个 App 里只能有一块蓝。
 *
 * 每个页面各自持一个实例，不共享：`Drawable.bounds` 是可变状态，
 * 两个 View 在同一帧里先后绘制时会互相覆盖 bounds（轻则位置错、重则只画一角）。
 * 位图本身由 Resources 缓存，多一个 Drawable 壳不占额外内存。
 */
internal class PersonaBackdropCache(private val resId: Int) {

    private var drawable: Drawable? = null
    private var loaded = false

    fun draw(context: Context, canvas: Canvas, w: Float, h: Float): Boolean {
        if (w <= 0f || h <= 0f) return false
        if (!loaded) {
            drawable = ContextCompat.getDrawable(context, resId)
            loaded = true
        }
        val d = drawable ?: return false
        d.setBounds(0, 0, w.toInt(), h.toInt())
        d.draw(canvas)
        return true
    }
}
