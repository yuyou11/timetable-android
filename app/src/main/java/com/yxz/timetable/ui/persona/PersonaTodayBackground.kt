package com.yxz.timetable.ui.persona

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.yxz.timetable.R

/**
 * 「今天」页 P3 全页底色：蓝色三段渐变打底，顶部叠一张 P3R 主视觉海报，加两团柔光。
 *
 * ## 海报的处理（p3_today_bg.jpg，构建前缩到 1152 宽）
 *
 *  · **上沿裁掉 25%**：原图顶部是「PERSONA 3 RELOAD」大 LOGO，直接压在 HUD 横幅
 *    上会和星期底字 / 日期打架；裁掉之后露出的是结城理的头发，深色正好衬白字。
 *  · **下沿裁到 88%**：原图右下角还有一块「RE」角标，不要。
 *  · **向下渐隐**：裁好的图在合成时用 DST_IN 蒙版从 [FADE_START] 高度淡到透明 ——
 *    脸完整显示，往下融进蓝色渐变，页面下半段还是原来那套纯渐变。
 *
 * 裁剪 + 渐隐只在**首次解码时做一次**（结果缓存在伴生对象，页面重建不重付解码的
 * 钱和内存），draw() 里只是一句 drawBitmap，滚动时不付任何滤镜的代价。
 * 解码失败（资源被裁掉之类）就退回纯渐变 —— 背景图是锦上添花，不是依赖。
 */
class PersonaTodayBackground private constructor(private val artwork: Bitmap?) : Drawable() {

    /** 应用侧入口：只在这里拿一次资源，Bitmap 缓存是全局的，不持有任何 Context */
    constructor(context: Context) : this(loadArtwork(context.applicationContext))

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val l = b.left.toFloat()
        val t = b.top.toFloat()

        // 1) 三段渐变打底（海报淡出之后由它接管，两段色要接得上）
        paint.shader = LinearGradient(
            l, t, l, t + h,
            intArrayOf(
                PersonaAlarmPalette.BLUE_TOP,
                PersonaAlarmPalette.BLUE_MID,
                PersonaAlarmPalette.BLUE_DEEP
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(l, t, l + w, t + h, paint)

        // 2) 海报：盖住整宽、顶对齐，底部已经预渐隐过
        artwork?.let { art ->
            val dstH = art.height * (w / art.width)
            canvas.drawBitmap(art, null, RectF(l, t, l + w, t + dstH), artPaint)
        }

        // 3) 两团柔光：右上一团天光，左下一团深海，同时把海报往页面色系里压一点
        paint.shader = RadialGradient(
            l + w * 0.82f, t + h * 0.06f, w * 0.72f,
            intArrayOf(Color.argb(52, 150, 225, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(l, t, l + w, t + h, paint)

        paint.shader = RadialGradient(
            l + w * 0.16f, t + h * 0.88f, w * 0.85f,
            intArrayOf(Color.argb(110, 1, 13, 100), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(l, t, l + w, t + h, paint)

        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {

        /** 海报从原图高度的哪里开始取（0.25 = 裁掉顶部 LOGO 区） */
        private const val SRC_TOP = 0.25f

        /** 海报到原图高度的哪里为止（0.88 = 裁掉右下角「RE」角标） */
        private const val SRC_BOTTOM = 0.88f

        /** 海报从自身高度的哪里开始向下渐隐（脸在最亮区，正好在它上面） */
        private const val FADE_START = 0.50f

        @Volatile
        private var cached: Bitmap? = null

        private fun loadArtwork(app: Context): Bitmap? {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val src = BitmapFactory.decodeResource(
                    app.resources, R.drawable.p3_today_bg
                ) ?: return null
                val top = (src.height * SRC_TOP).toInt()
                val bottom = (src.height * SRC_BOTTOM).toInt()
                val croppedH = bottom - top
                val out = Bitmap.createBitmap(src.width, croppedH, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawBitmap(
                    src,
                    android.graphics.Rect(0, top, src.width, bottom),
                    RectF(0f, 0f, out.width.toFloat(), croppedH.toFloat()),
                    null
                )
                val fade = Paint().apply {
                    shader = LinearGradient(
                        0f, croppedH * FADE_START, 0f, croppedH.toFloat(),
                        intArrayOf(Color.BLACK, Color.TRANSPARENT),
                        null, Shader.TileMode.CLAMP
                    )
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                c.drawRect(0f, croppedH * FADE_START, out.width.toFloat(), croppedH.toFloat(), fade)
                src.recycle()
                cached = out
                return out
            }
        }
    }
}
