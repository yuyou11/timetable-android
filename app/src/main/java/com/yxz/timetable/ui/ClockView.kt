package com.yxz.timetable.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.yxz.timetable.R
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 闹钟页上半部分那只钟。
 *
 * ## 为什么自己画
 *
 * 系统没有「模拟时钟」控件，而要拿图片凑就得为四种主题（蓝/绿 × 日/夜）各备一份 ——
 * 漏一份就是静默错色。自己画的话，颜色全从当前主题的强调色现取，
 * 四种组合一份代码搞定（和 [WeekDotsView] 一个路子）。
 *
 * ## 省电：只在页面可见时走
 *
 * 每秒重画一次，但**只在 attached 期间**。离开页面（切 tab、退到后台、
 * 屏幕关闭）时 `onDetachedFromWindow` 会被调用，定时器立刻停 ——
 * 后台一次都不会重画。这是「低占用」那条底线在这里的具体落法：
 * 页面上的东西，只在页面存在时耗资源。
 */
class ClockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
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
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - dp(6)

        val accent = accentColor()
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val divider = ContextCompat.getColor(context, R.color.divider)

        // 表盘：强调色压到 8% 做底，边框用分隔线色
        face.style = Paint.Style.FILL
        face.color = ColorUtils.setAlphaComponent(accent, 0x14)
        canvas.drawCircle(cx, cy, r, face)
        face.style = Paint.Style.STROKE
        face.strokeWidth = dp(1.5f)
        face.color = divider
        canvas.drawCircle(cx, cy, r, face)

        // 12 个刻度：整点长、其余短
        for (i in 0 until 12) {
            val a = Math.toRadians((i * 30 - 90).toDouble())
            val long = i % 3 == 0
            val len = if (long) dp(9f) else dp(5f)
            tick.strokeWidth = if (long) dp(2.5f) else dp(1.5f)
            tick.color = if (long) accent else secondary
            val r1 = r - dp(6f)
            val r2 = r1 - len
            canvas.drawLine(
                cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(),
                cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), tick
            )
        }

        val now = LocalTime.now()
        val minuteAngle = (now.minute + now.second / 60f) * 6f - 90f
        val hourAngle = (now.hour % 12 + now.minute / 60f) * 30f - 90f
        val secondAngle = now.second * 6f - 90f

        // 时针、分针、秒针
        hand.color = accent
        hand.strokeWidth = dp(4f)
        drawHand(canvas, cx, cy, hourAngle, r * 0.5f)
        hand.strokeWidth = dp(3f)
        drawHand(canvas, cx, cy, minuteAngle, r * 0.74f)
        hand.color = ColorUtils.setAlphaComponent(accent, 0x99)
        hand.strokeWidth = dp(1.5f)
        drawHand(canvas, cx, cy, secondAngle, r * 0.82f)

        // 轴心
        face.style = Paint.Style.FILL
        face.color = accent
        canvas.drawCircle(cx, cy, dp(3.5f), face)

        // 盘心的数字时刻：模拟钟看大概，数字钟给准数
        text.color = ContextCompat.getColor(context, R.color.text_primary)
        text.textSize = dp(20f)
        text.textAlign = Paint.Align.CENTER
        val label = "%02d:%02d".format(now.hour, now.minute)
        canvas.drawText(label, cx, cy + r * 0.45f, text)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, angleDeg: Float, len: Float) {
        val a = Math.toRadians(angleDeg.toDouble())
        canvas.drawLine(
            cx - (len * 0.12f * cos(a)).toFloat(), cy - (len * 0.12f * sin(a)).toFloat(),
            cx + (len * cos(a)).toFloat(), cy + (len * sin(a)).toFloat(), hand
        )
    }

    private fun accentColor(): Int = context.accentColor()

    private fun dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
}
