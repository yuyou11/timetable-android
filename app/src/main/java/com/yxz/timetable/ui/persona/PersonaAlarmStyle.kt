package com.yxz.timetable.ui.persona

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import com.yxz.timetable.R
import java.util.Random

/**
 * 闹钟页在 P3 模式下的外观：深蓝上半屏 + 对话框样式的卡片。
 *
 * ## 为什么底色要自己画
 *
 * 「上半屏深蓝、下半屏卡片色」这条分界线要按**实际高度**算（不同机型差得多），
 * 而 XML 里的 layer-list 不支持按百分比切分 —— 只能自己算。所以做成一个
 * Drawable，由它按 bounds 的高度铺两段颜色。
 *
 * ## 对话框样式
 *
 * 照游戏里主角那套对话框：深藏青底 + 亮蓝细描边 + 两个斜切角。
 * 用 ShapeDrawable 的圆角做不出「斜切」，所以这里直接画 Path。
 */
object PersonaAlarmPalette {
    /** 和日历页同一套蓝：上浅下深 */
    const val BLUE_TOP = 0xFF1EA7FE.toInt()
    const val BLUE_MID = 0xFF0E49BE.toInt()
    const val BLUE_DEEP = 0xFF0524B1.toInt()

    /**
     * 卡片：深藏青底 + 亮蓝描边。
     *
     * 底色带一点透明度（alpha 0xB8 ≈ 72%）：「今天」页的海报背景会透出来一层，
     * 卡片变成「毛玻璃」；在背景是纯渐变的页面（闹钟页）上，透出来的那层蓝
     * 也让卡片和底色贴得更近。白字压在叠完的深色上依旧够亮。
     */
    const val CARD_FILL = 0xB81B2270.toInt()
    const val CARD_STROKE = 0xFF4660DC.toInt()
    /** 卡片底下那层亮蓝斜底 —— **半透明**版：双层卡现在两层都是玻璃，
     *  海报从面板和蓝底层后面都能透出来，不会再出现「面板透了、垫在下面的蓝板不透」的割裂 */
    const val CARD_BLUE_GLASS = 0xB04884FF.toInt()
    /** 实底亮蓝：**按钮**专用（按钮要像个按钮，不能透出底下的东西） */
    const val CARD_BLUE = 0xFF4884FF.toInt()
    /** 白色圆角条上的文字色 */
    const val CARD_TITLE = 0xFF101E96.toInt()

    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_DIM = 0xFFA9BEE8.toInt()
    const val ACCENT = 0xFF7FB2FF.toInt()
}

/** 整页背景：上半截深蓝渐变，下半截卡片底色。 */
class PersonaAlarmBackground(private val bottomColor: Int) : Drawable() {

    /**
     * 分界线的绝对位置（相对这个 Drawable 的 bounds 顶部，px）。
     *
     * 由 [com.yxz.timetable.ui.AlarmFragment] 按「下次响铃」那一行的实际底边算出来 ——
     * 写死比例的话，表盘大小一换、或者机型一换，线就会压到别的字上。
     * 负数表示还没算过，退回按 [SPLIT] 的比例。
     */
    var splitY: Float = -1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stops = intArrayOf(
        PersonaAlarmPalette.BLUE_TOP,
        PersonaAlarmPalette.BLUE_MID,
        PersonaAlarmPalette.BLUE_DEEP
    )

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val split = if (splitY > 0f) b.top + splitY else b.top + b.height() * SPLIT
        // 分界线是一条很浅的弧：左端略高、右端略低，中间再往下鼓一点点。
        // 直线太硬，和 P3 那种手绘感不搭；弧度也不能大 —— 两行字之间只有 10dp 余量。
        val slant = b.height() * 0.008f
        val sag = b.height() * 0.012f

        paint.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(),
            b.left.toFloat(), split,
            stops, floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(
            b.left.toFloat(), b.top.toFloat(),
            b.right.toFloat(), split + slant + sag, paint
        )

        paint.shader = null
        paint.color = bottomColor
        val lower = Path().apply {
            moveTo(b.left.toFloat(), split)
            quadTo(
                (b.left + b.right) / 2f, split + sag,
                b.right.toFloat(), split + slant
            )
            lineTo(b.right.toFloat(), b.bottom.toFloat())
            lineTo(b.left.toFloat(), b.bottom.toFloat())
            close()
        }
        canvas.drawPath(lower, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        /** 分界线在整页高度的比例：钟 + 下一次响铃那一行大致落在里面 */
        const val SPLIT = 0.46f
    }
}

/**
 * 对话框样式的卡片背景 —— 照游戏里主角那套对话框做的**两层**结构：
 *
 * ```
 *   ┌───────────────────────────┐  ← 亮蓝斜底（往左上偏）
 *   │  ╱─────────────────────╲  │     左边露出一条，右边被面板盖住
 *   │ ╱  深藏青不规则多边形    ╲ │
 *   │ ╲        ↘ 尖尾          ╱ │
 *   └───────────────────────────┘
 * ```
 *
 * 关键点（第一版做错的地方）：
 *  · 不是单层描边矩形，而是**两层**：蓝底在下、深色面板在上，两者错开；
 *  · 面板是**不规则多边形**：上边微斜、左上斜切、右下拖一个尖尾；
 *  · 所以左边和上边会露出蓝底，右边没有。
 *
 * [topPeak] 控制要不要保留顶上那对尖角（蓝底和面板的上边中峰）。
 * 游戏里那是「对话框拖出的尾巴」，闹钟页保留；此刻卡嫌它扎眼，传 false ——
 * 两层都少一个顶点，各自变成四边形，顶边只剩一条平缓的斜线。
 *
 * [chamfer] 控制斜切与错位的尺度（px），卡片矮的时候它会自动收敛，
 * 保证尖尾和斜切不会把内容压掉。
 */
class PersonaDialogBackground(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val blueColor: Int,
    private val strokeWidth: Float,
    private val chamfer: Float,
    private val topPeak: Boolean = true
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@PersonaDialogBackground.strokeWidth
        strokeJoin = Paint.Join.MITER
    }

    // ---------------------------------------------------------------- 三个角会抖

    /**
     * 三个角各自抖动的幅度（px）。0 = 完全不动，默认不动 ——
     * 闹钟页那些行卡不需要「呼吸」，只有「今天」页的此刻卡要。
     *
     * 抖动规则和日历页那张粉色纸片完全一致（见 PersonaCalendarView）：
     * 每 [TICK_MS] 一拍，每个角各有 32% 的概率换一个随机目标，
     * 然后朝目标插值 22% —— 中途换目标也是平滑的，看起来像被风带着抖，
     * 而不是逐帧乱跳。
     *
     * 抖的是**深色面板**的三个角：左上、左下、右上。
     * 右下的尖尾不动 —— 那是这块面板「钉」在蓝底上的那头，动了会像脱胶。
     */
    var jitterAmp: Float = 0f

    private val jitter = FloatArray(6)
    private val jitterTo = FloatArray(6)
    private val random = Random()
    private var host: View? = null
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            val v = host
            // 看不见就停 —— 别让一个 72ms 的定时器在后台一直唤醒
            if (!running || v == null || !v.isAttachedToWindow || !v.isShown) {
                running = false
                return
            }
            for (i in 0 until 6) {
                if (random.nextFloat() < 0.32f) {
                    jitterTo[i] = (random.nextFloat() * 2f - 1f) * jitterAmp
                }
                jitter[i] += (jitterTo[i] - jitter[i]) * 0.22f
            }
            invalidateSelf()
            v.postDelayed(this, TICK_MS)
        }
    }

    /** 只在卡片可见时调用（页面看得见才抖，切走就停） */
    fun startJitter(view: View) {
        host = view
        if (running || jitterAmp <= 0f) return
        running = true
        view.post(tick)
    }

    fun stopJitter() {
        running = false
        host?.removeCallbacks(tick)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bo = b.bottom.toFloat()
        val w = r - l
        val h = bo - t
        // 归一化坐标：和 docs/persona3/mock_card.py 里的顶点一一对应
        fun px(fx: Float) = l + w * fx
        fun py(fy: Float) = t + h * fy

        // 1) 浅蓝斜底。顶点来自用户按原图量的坐标（右下角为原点、y 向上），
        //    这里换成归一化的屏幕坐标（左上为 0,0）。比例固定，尺寸随卡片。
        //    topPeak=false 时去掉上边中峰 (0.305, 0.000)，四边形。
        val blue = Path().apply {
            moveTo(px(0.000f), py(0.035f))
            if (topPeak) lineTo(px(0.305f), py(0.000f))
            lineTo(px(0.963f), py(0.216f))
            lineTo(px(0.897f), py(1.000f))
            lineTo(px(0.005f), py(0.895f))
            close()
        }
        fill.color = blueColor
        canvas.drawPath(blue, fill)

        // 2) 深色面板：同一个原点量出来的顶点
        //    （左上 → 下 → 右下 → 右上 [→ 上边中峰]）
        //    topPeak=false 时去掉上边中峰 (0.271, 0.045)，四边形。
        //    三个角各带一份抖动偏移（左上 / 左下 / 右上），右下尖尾不动。
        val panel = Path().apply {
            moveTo(px(0.042f) + jitter[0], py(0.122f) + jitter[1])
            lineTo(px(0.073f) + jitter[2], py(0.941f) + jitter[3])
            lineTo(px(0.951f), py(0.895f))
            lineTo(px(1.000f) + jitter[4], py(0.153f) + jitter[5])
            if (topPeak) lineTo(px(0.271f), py(0.045f))
            close()
        }
        fill.color = fillColor
        canvas.drawPath(panel, fill)
        stroke.color = strokeColor
        canvas.drawPath(panel, stroke)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        /** 和日历页纸片同一拍：72ms。更快像发抖，更慢像漂移 */
        const val TICK_MS = 72L
    }
}

/**
 * 「不规则矩形」按钮：四条边各偏几 dp，看着像手撕的，而不是死板的长方形。
 *
 * 和卡片那种大斜切不同，这里只偏一点点 —— 按钮还是要有按钮的样子，
 * 只是别和系统控件长得一模一样。
 */
class PersonaButtonBackground(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val strokeWidth: Float,
    private val slant: Float
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@PersonaButtonBackground.strokeWidth
        strokeJoin = Paint.Join.MITER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val inset = strokeWidth / 2f
        val l = b.left + inset
        val t = b.top + inset
        val r = b.right - inset
        val bo = b.bottom - inset
        val s = slant

        val path = Path().apply {
            moveTo(l, t + s * 0.8f)          // 左上略低
            lineTo(r - s * 0.6f, t)          // 右上略高
            lineTo(r, bo - s * 0.7f)         // 右下
            lineTo(l + s * 0.5f, bo)         // 左下
            close()
        }
        fill.color = fillColor
        canvas.drawPath(path, fill)
        stroke.color = strokeColor
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** 把 P3 外观贴到闹钟页的各个控件上。 */
object PersonaAlarmStyle {

    /**
     * 字体：游戏里对话框那种**宽体重体**，不是日历页的窄体。
     *
     * 第一版直接复用了日历页的 `sans-serif-condensed`，字被压得很窄，
     * 和原图里「只有我刚来的时候」那种方正的粗字对不上。
     */
    val face: Typeface by lazy { Typeface.create("sans-serif-black", Typeface.NORMAL) }

    fun dialog(ctx: Context, chamferDp: Float = 10f, topPeak: Boolean = true): Drawable {
        val d = ctx.resources.displayMetrics.density
        return PersonaDialogBackground(
            fillColor = PersonaAlarmPalette.CARD_FILL,
            strokeColor = PersonaAlarmPalette.CARD_STROKE,
            // 蓝底层用半透明版：双层卡两层都是玻璃（按钮才用实心 CARD_BLUE）
            blueColor = PersonaAlarmPalette.CARD_BLUE_GLASS,
            strokeWidth = 1.6f * d,
            chamfer = chamferDp * d,
            topPeak = topPeak
        )
    }

    /**
     * 扁平版对话卡：不叠亮蓝底层，只一块按固定 dp 斜切的面板 + 描边。
     *
     * [dialog] 的蓝底层顶点是**按卡片高度的比例**放的，矮卡（闹钟行、此刻卡）上
     * 露出的是一条俏皮的斜边；换成时间线那种上千像素的高卡，同一组比例会把蓝底
     * 放大成一大片，第一行内容直接浮在蓝色上。高卡用这个 —— 形状语言一致
     * （同样的深藏青 + 亮蓝描边 + 斜切），只是不赌比例。
     */
    fun flatDialog(ctx: Context, slantDp: Float = 9f): Drawable {
        val d = ctx.resources.displayMetrics.density
        return PersonaButtonBackground(
            fillColor = PersonaAlarmPalette.CARD_FILL,
            strokeColor = PersonaAlarmPalette.CARD_STROKE,
            strokeWidth = 1.6f * d,
            slant = slantDp * d
        )
    }

    /**
     * 矩形卡：直角矩形 + 深藏青半透明底 + 亮蓝描边，什么斜切都没有。
     *
     * 「明天」「时间线」这种长篇内容卡用 —— 上面写字的地方多，四条边歪来歪去
     * 只会显得毛糙；方方正正反而回到了「对话框」该有的稳重。
     */
    fun rectCard(ctx: Context): Drawable {
        val d = ctx.resources.displayMetrics.density
        return PersonaButtonBackground(
            fillColor = PersonaAlarmPalette.CARD_FILL,
            strokeColor = PersonaAlarmPalette.CARD_STROKE,
            strokeWidth = 1.6f * d,
            slant = 0f
        )
    }

    /** 卡片上那条白色圆角文字条（放「06:40」这种主信息） */
    fun titleBar(ctx: Context): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 7f * ctx.resources.displayMetrics.density
        setColor(0xFFFFFFFF.toInt())
    }

    /** 不规则矩形按钮 */
    fun button(ctx: Context, fill: Int, stroke: Int, slantDp: Float = 5f): Drawable {
        val d = ctx.resources.displayMetrics.density
        return PersonaButtonBackground(
            fillColor = fill,
            strokeColor = stroke,
            strokeWidth = 1.4f * d,
            slant = slantDp * d
        )
    }

    /** 整页背景：上半深蓝、下半卡片色 */
    fun pageBackground(ctx: Context): Drawable =
        PersonaAlarmBackground(ContextCompat.getColor(ctx, R.color.card_bg))

    /** 把一组控件刷成 P3 的字体与配色（只处理传进来的那几个） */
    fun tint(view: View, color: Int) {
        if (view is android.widget.TextView) {
            view.setTextColor(color)
            view.typeface = face
        }
    }
}
