package com.yxz.timetable.ui.persona

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 「今天」页下半截的深蓝底：上沿由**一组斜向的斑马带**切入。
 *
 * ## 形状（全部是直边，没有任何手绘抖动）
 *
 * **4 条横穿整个页面的斜带**，斜率 0.134（≈7.6°，**左高右低**）。自上而下：
 *
 * | 条 | 厚度 | 它下面的缝 |
 * |---|---|---|
 * | 1 | 6dp  | 18dp |
 * | 2 | 28dp | 12dp |
 * | 3 | 44dp | 6dp  |
 * | 4 | 60dp | 0dp  → 直接接上实心 |
 *
 * 也就是「**缝越来越窄、带越来越宽**」，最后一条并进实心蓝。
 * 每条带的上下边是**平行的斜直线**，段与段之间是纯粹的色块关系，
 * 不画毛边、不画笔触 —— 需求给的是示意图，取的是它的**比例**，不是它的笔法。
 *
 * ## 上沿位置
 *
 * [topCutPx]：蓝色从 bounds 顶部往下多少 px 开始。取「常驻开关」卡片的**竖直中线**，
 * 并且以屏幕横向中点为锚（[edgeShift]）—— 于是整条界线是斜的，
 * 而「卡片中线」这个约定永远落在画面正中，换屏幕宽度也不会偏。
 *
 * 卡片不透明，等于一张压在这条线上的贴纸：界线在它两侧和下方可见，卡片里的字不切。
 *
 * ## 渐变
 *
 * 和日历页一致：**上沿最浅（`#1EA7FE`），往下变深，页面底部是深蓝 `#0524B1`**。
 * （中途试过反过来，观感不如原方向，已改回。）
 */
class PersonaDeepBlueBackground(private val density: Float) : Drawable() {

    /** 蓝色从 bounds 顶部往下多少 px 开始。由调用方按「卡片中线」算出来 */
    var topCutPx: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val bottom = t + h
        val cut = (t + topCutPx).coerceIn(t, bottom)

        // 渐变回到和日历页一致的「上浅下深」：天光蓝 → 正蓝 → 深蓝
        paint.shader = LinearGradient(
            0f, cut, 0f, bottom,
            intArrayOf(
                PersonaAlarmPalette.BLUE_TOP,
                PersonaAlarmPalette.BLUE_MID,
                PersonaAlarmPalette.BLUE_DEEP
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        // 四条斜带 + 最下面的实心区，共用同一份渐变，所以接缝处颜色是连着的
        var y = cut
        for (stroke in STROKES) {
            drawBand(canvas, l, w, y, y + stroke.first * density)
            y += (stroke.first + stroke.second) * density
        }
        if (y < bottom) drawSolid(canvas, l, w, y, bottom)

        // 两团柔光（和日历页同款），只落在蓝区里
        val blueH = bottom - cut
        if (blueH > 0f) {
            val save = canvas.save()
            canvas.clipRect(l, cut, l + w, bottom)

            paint.shader = RadialGradient(
                l + w * 0.34f, cut + blueH * 0.44f, w * 0.62f,
                intArrayOf(Color.argb(110, 1, 13, 100), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(l, cut, l + w, bottom, paint)

            paint.shader = RadialGradient(
                l + w * 0.78f, cut - blueH * 0.06f, w * 0.58f,
                intArrayOf(Color.argb(46, 150, 225, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(l, cut, l + w, bottom, paint)

            canvas.restoreToCount(save)
        }

        paint.shader = null
    }

    /**
     * 一条斜带：上下两条平行斜线，左右两端都探出页面（免得看到断头）。
     * 上边在 [topY]，下边在 [bottomY]。
     */
    private fun drawBand(canvas: Canvas, left: Float, w: Float, topY: Float, bottomY: Float) {
        val margin = 32f * density
        val x0 = left - margin
        val x1 = left + w + margin
        val t0 = topY + edgeShift(x0 - left, w)
        val t1 = topY + edgeShift(x1 - left, w)
        val b0 = bottomY + edgeShift(x0 - left, w)
        val b1 = bottomY + edgeShift(x1 - left, w)

        path.reset()
        path.moveTo(x0, t0)
        path.lineTo(x1, t1)
        path.lineTo(x1, b1)
        path.lineTo(x0, b0)
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * 边缘的竖向位移。以**屏幕横向中点**为锚：中点处正好落在 [topCutPx] 上，
     * 往左抬高、往右压低 —— 于是整条分界线是斜的（左高右低），而「卡片中线」这个约定
     * 仍然落在画面正中间，不会因为屏幕宽窄而漂。
     */
    /**
     * 最下面那块实心区。**上边跟着斜，下边必须是水平的** ——
     * 下边要是也斜，左下角就会空出一个三角形（这坑踩过一次）。
     */
    private fun drawSolid(canvas: Canvas, left: Float, w: Float, topY: Float, bottom: Float) {
        val margin = 32f * density
        val x0 = left - margin
        val x1 = left + w + margin
        val t0 = topY + edgeShift(x0 - left, w)
        val t1 = topY + edgeShift(x1 - left, w)

        path.reset()
        path.moveTo(x0, t0)
        path.lineTo(x1, t1)
        path.lineTo(x1, bottom)   // 竖直下去
        path.lineTo(x0, bottom)   // 底边是水平的，铺满整个宽度
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun edgeShift(dx: Float, w: Float): Float = (dx - w / 2f) * SLOPE

    // 纯装饰：不参与透明度 / 滤镜动画
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        /**
         * 四条斜带：(厚度, 它下方的缝)，单位 dp。
         * 厚度 6 → 28 → 44 → 60，缝 18 → 12 → 6 → 0。
         */
        private val STROKES = arrayOf(
            6f to 18f,
            28f to 12f,
            44f to 6f,
            60f to 0f
        )

        /** 坡度：每往右 1px 压低 0.134px（≈7.6°）。最初是 0.268（≈15°），按需求放缓了一半 */
        private const val SLOPE = 0.134f
    }
}
