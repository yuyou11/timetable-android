package com.yxz.timetable.ui.persona

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 底部导航在 P3 模式下的底板。
 *
 * ## 只画一件事
 *
 * 导航栏自己的背景在 P3 模式下是置空的，底色由这一层画 —— 这样页面内容的最下沿
 * 和导航栏之间不会出现一条「系统色」的过渡带。
 *
 * ## 栏顶那条线已经撤掉
 *
 * 以前这里还画过一条墨蓝细线（先浅灰、后来换墨蓝），当作「纸的上沿」。
 * 现在没有线了：天空 / 白纸本身到栏顶就是一条干净的硬边，
 * 再加一条深色线等于给页签底部描边，页签切来切去时它一直在闪。
 *
 * ## 现在没有的东西
 *
 * 选中项背后的深藏青斜块、「今天」那块（圆 → 裁角方板 → 扁矩形）、栏顶的线 ——
 * 全撤了。底栏就是「一条白底 + 五个图标文字」，选中只靠颜色。
 * 要恢复任何一版，看 git 历史。
 */
class PersonaNavBackdrop @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 导航栏本身的高度（px） */
    var barHeight: Float = 0f

    /** 这层比栏高出多少（px）。留出来是为了将来还要往上画东西时不改布局 */
    var poke: Float = 0f

    var barColor: Int = 0xFFFFFFFF.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = barColor
        canvas.drawRect(0f, poke, w, h, paint)
    }
}
