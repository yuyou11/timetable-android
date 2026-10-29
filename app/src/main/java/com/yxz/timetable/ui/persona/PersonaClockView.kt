package com.yxz.timetable.ui.persona

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * P3 风格的闹钟页表盘（彩蛋），照游戏里「影时间」那只钟重建 —— 换成蓝色。
 *
 * ## 和原图的对应关系
 *
 * | 原图 | 这里 |
 * |---|---|
 * | 双圈（外圈粗、内圈细） | 半径 0.955R / 0.80R 两道圆 |
 * | 圈内 12 个罗马数字，随角度旋转 | 数字中心在 0.878R、字号 0.15R，**4 点是 IIII**（钟表写法） |
 * | 圈外 12 根尖刺 | 每整点一根窄三角，12 点那根更长 |
 * | 纺锤形指针（中间粗、两头尖） | 时针 0.52R、分针 0.84R（几乎顶到数字圈），用 Path 画的四角形 |
 * | 轴心下方的 AM / PM | 轴心下方 0.30R，小字、字距拉开，跟着走字换 |
 * | ——（原图没有秒针） | 补了一根更细的浅蓝秒针，反方向带一小段配重 |
 *
 * ## 底色
 *
 * 原图是黑底发光，这里**保持页面原来的白底**：淡蓝的半透明描边落在白纸上会发脏，
 * 所以不做辉光，线全部实色。深色底盘和辉光都去掉了，表盘直接浮在卡片上。
 *
 * ## 节流
 *
 * 和 [com.yxz.timetable.ui.ClockView] 一样只在 attached 期间走，按秒重画。
 */
class PersonaClockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numerals = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private var running = false

    // ---------------------------------------------------------------- 影时间

    /**
     * 「影时间」：闹钟响的时候整只钟变绿，停了（自动停或用户按停止）再变回蓝色。
     *
     * 原游戏里影时间就是这个颜色转变 —— 平时青蓝、零点后转成那种病态的黄绿色。
     * 这里由 [com.yxz.timetable.ui.AlarmFragment] 每秒同步一次响铃状态。
     */
    private var darkHour = false

    fun setDarkHour(on: Boolean) {
        if (darkHour == on) return
        darkHour = on
        invalidate()
    }

    /** 主线颜色：平时蓝、影时间转绿 */
    private val primary: Int get() = if (darkHour) DARK_GREEN else NEON

    /** 秒针颜色，比主线浅一档 */
    private val secondColor: Int get() = if (darkHour) DARK_GREEN_SOFT else SECOND

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            // 有秒针，按秒走
            postDelayed(this, 1000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!running) {
            running = true
            post(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        // R 是表盘主半径。0.82 是给外圈尖刺让位：尖顶最远到 1.20R，
        // 再乘 0.82 正好 0.98×半边长，不会切到画布边。
        // （v3 取 0.86 时尖刺能伸出去的余量不够，看着像缩在圈里。）
        val R = min(w, h) / 2f * 0.82f

        drawRings(canvas, cx, cy, R)
        drawNumerals(canvas, cx, cy, R)
        drawSpikes(canvas, cx, cy, R)
        drawHands(canvas, cx, cy, R)
    }

    /** 外圈 + 内圈。白底上不做辉光 —— 淡蓝的半透明描边在白纸上会显得发脏。 */
    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        line.color = primary
        // 外圈往外撑到 0.975R（原来 0.955，留白太多显得盘子小），两道圈也都加粗
        line.strokeWidth = R * 0.024f
        canvas.drawCircle(cx, cy, R * 0.975f, line)
        line.strokeWidth = R * 0.020f
        canvas.drawCircle(cx, cy, R * 0.80f, line)
    }

    /**
     * 12 个罗马数字，跟着角度转（和原图一样：12 点正立，6 点倒过来）。
     *
     * 细节按游戏截图对过：
     *  · 4 点是 **IIII** 不是 IV —— 钟表商用了三百年的写法，游戏里也是这个；
     *  · 字号顶到两条圈之间的那条带子（0.15R），缩一号就变成「刻度」而不是「数字」；
     *  · 数字压在 0.878R，即内外两圈的中间偏外一点。
     */
    private fun drawNumerals(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        numerals.textSize = R * 0.15f
        val rn = R * 0.878f
        for (i in 0 until 12) {
            val a = Math.toRadians((i * 30 - 90).toDouble())
            val x = cx + (rn * cos(a)).toFloat()
            val y = cy + (rn * sin(a)).toFloat()
            val baseline = y + numerals.textSize * 0.36f

            val save = canvas.save()
            canvas.rotate(i * 30f, x, y)

            numerals.style = Paint.Style.FILL
            numerals.color = primary
            canvas.drawText(ROMAN[i], x, baseline, numerals)

            canvas.restoreToCount(save)
        }

        // 轴心下面那个 AM / PM：原图里就印在表盘上（不是指针），
        // 小小一行、字距拉开，跟着走字换。12 点制，所以只在 0/12 之间换。
        val now = LocalTime.now()
        val meridiem = if (now.hour < 12) "AM" else "PM"
        numerals.textSize = R * 0.085f
        numerals.letterSpacing = 0.18f
        numerals.color = primary
        numerals.style = Paint.Style.FILL
        canvas.drawText(meridiem, cx, cy + R * 0.30f, numerals)
        numerals.letterSpacing = 0f
    }

    /**
     * 圈外 12 根尖刺 + 圈内 12 个筝形。
     *
     * 这两处是照游戏截图一点点对出来的：
     *
     *  · **外侧**是窄三角，底边贴在外圈上往外戳。**12 / 3 / 6 / 9 四根更长**
     *    （原来只有 12 点做了加长，另外三个正点是短的一模一样，看着就不像钟）。
     *  · **内侧**不是短线，是 12 个**筝形**：一条对角线正好落在内圈上（两个侧顶点
     *    贴在圆周），另一条沿半径往外/往里各探一点 —— 所以每个整点在内圈上
     *    会「鼓」出一小块，而不是一圈干净的圆。
     */
    private fun drawSpikes(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val innerR = R * 0.80f
        solid.style = Paint.Style.FILL
        solid.color = primary

        for (i in 0 until 12) {
            val a = Math.toRadians((i * 30 - 90).toDouble())
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            val px = -uy      // 垂直于半径（切向）
            val py = ux

            // ---- 外侧尖刺 ----
            // 底边钉在**外圈的外沿**（0.975R 圆 + 半个描边 0.012R = 0.987R），
            // 整个三角形只在圈外，不和圆相交 —— v4 把底边放到 0.93R，
            // 结果每根尖都把外圈切成两半，看着像从圆里长出来的。
            // 尖顶：正点和普通点只差三四成（露在圈外的长度 0.133R vs 0.098R），
            // 不是两倍 —— v5 的 1.16 / 1.09 拉到 1.7 倍，看着像长短两套刺
            val cardinal = i % 3 == 0          // 12 / 3 / 6 / 9
            val apex = if (cardinal) R * 1.120f else R * 1.085f
            val halfW = R * 0.024f
            val base = R * 0.987f
            val outer = Path().apply {
                moveTo(cx + ux * apex, cy + uy * apex)
                lineTo(cx + ux * base + px * halfW, cy + uy * base + py * halfW)
                lineTo(cx + ux * base - px * halfW, cy + uy * base - py * halfW)
                close()
            }
            canvas.drawPath(outer, solid)

            // ---- 内侧筝形 ----
            // 侧顶点在圆周上（弦 = 筝形的一条对角线）；另外两个顶点沿半径一外一内，
            // 向内的那个更长 —— 截图里内圈的尖是朝圆心扎的。
            val side = R * 0.024f             // 贴在内圈上的那半个弦长
            val dw = side / innerR            // 换算成弧度
            val out = R * 0.014f              // 往外鼓出来一点（不要顶到数字）
            val into = R * 0.080f             // 往圆心伸得更长
            val kite = Path().apply {
                moveTo(cx + ux * (innerR + out), cy + uy * (innerR + out))
                lineTo(
                    cx + innerR * cos(a - dw).toFloat(),
                    cy + innerR * sin(a - dw).toFloat()
                )
                lineTo(cx + ux * (innerR - into), cy + uy * (innerR - into))
                lineTo(
                    cx + innerR * cos(a + dw).toFloat(),
                    cy + innerR * sin(a + dw).toFloat()
                )
                close()
            }
            canvas.drawPath(kite, solid)
        }
    }

    /** 时针、分针、秒针。时针分针照原图的纺锤形，秒针是一根更细的直线。 */
    private fun drawHands(canvas: Canvas, cx: Float, cy: Float, R: Float) {
        val now = LocalTime.now()
        val minuteAngle = (now.minute + now.second / 60f) * 6f - 90f
        val hourAngle = (now.hour % 12 + now.minute / 60f) * 30f - 90f
        val secondAngle = now.second * 6f - 90f

        // 时针短粗、分针细长顶到圈边 —— 原图里两根的差别很明显：
        // 分针几乎碰到数字圈，时针只到一半多一点
        spindle(canvas, cx, cy, hourAngle, R * 0.52f, R * 0.036f)
        spindle(canvas, cx, cy, minuteAngle, R * 0.84f, R * 0.021f)

        // 秒针：比另外两根细，颜色浅一档，反方向留一小段配重
        val a = Math.toRadians(secondAngle.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        line.color = secondColor
        line.strokeWidth = R * 0.008f
        canvas.drawLine(
            cx - ux * R * 0.14f, cy - uy * R * 0.14f,
            cx + ux * R * 0.84f, cy + uy * R * 0.84f,
            line
        )

        // 轴心：外圈用主色，中间挖一个底色的小孔（不是白色 —— 表盘现在是白线，
        // 再点一个白点就看不出「孔」了）
        solid.color = primary
        canvas.drawCircle(cx, cy, R * 0.030f, solid)
        solid.color = HOLE
        canvas.drawCircle(cx, cy, R * 0.013f, solid)
    }

    private fun spindle(
        canvas: Canvas, cx: Float, cy: Float, angleDeg: Float, len: Float, halfW: Float
    ) {
        val a = Math.toRadians(angleDeg.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        val px = -uy
        val py = ux

        val tail = len * 0.11f          // 反方向那一小段尾巴
        val shoulder = len * 0.14f      // 最粗的位置（靠中心，指针因此显得细长）

        val path = Path().apply {
            moveTo(cx + ux * len, cy + uy * len)                       // 尖
            lineTo(cx + ux * shoulder + px * halfW, cy + uy * shoulder + py * halfW)
            lineTo(cx - ux * tail, cy - uy * tail)                     // 尾巴
            lineTo(cx + ux * shoulder - px * halfW, cy + uy * shoulder - py * halfW)
            close()
        }
        solid.style = Paint.Style.FILL
        solid.color = primary
        canvas.drawPath(path, solid)
    }

    private companion object {
        /** 4 点用 IIII（钟表写法），不是 IV —— 游戏里那只是 IIII */
        val ROMAN = arrayOf("XII", "I", "II", "III", "IIII", "V", "VI", "VII", "VIII", "IX", "X", "XI")
        const val NEON = 0xFFFFFFFF.toInt()
        /** 秒针浅一档，和时针分针区分开 */
        const val SECOND = 0xB3FFFFFF.toInt()

        /** 影时间：偏黄的那种病态绿，压在白底上仍然看得清 */
        const val DARK_GREEN = 0xFF1FA84A.toInt()
        const val DARK_GREEN_SOFT = 0xFF6FC98C.toInt()

        /** 轴心那个小孔的颜色：和它背后的深蓝底一致 */
        const val HOLE = 0xFF0E49BE.toInt()
    }
}
