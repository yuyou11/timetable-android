package com.yxz.timetable.ui.persona

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import com.yxz.timetable.R

/**
 * 点格弹卡的 P3 皮肤。
 *
 * ## 为什么是「刷」而不是另做一套布局
 *
 * 课表页和今日页的弹卡（`dialog_slot_courses` + `item_course_card`）承担的是
 * **信息密度最高**的那一块：一门课的时间 / 教室 / 老师 / 周次 / 闹钟按钮。
 * 为 P3 复制一份布局，等于把这套字段和它们的绑定逻辑复制两遍 ——
 * 以后加一个字段（比如学分）就得记着改两处。
 *
 * 所以这里只换外观：把白卡片换成深藏青对话框、把强调色换成青色、
 * 把「设置闹钟」那个浅底块换成描边块。字段、顺序、绑定逻辑一律不动。
 *
 * ## 和网页样稿的对应
 *
 * `p3web/week-p3-v2.html` 的第二屏（弹卡）：深藏青渐变 + 亮蓝描边 + 青色徽标 +
 * 一条渐隐分割线 + 底部一枚描边按钮。这里逐条照抄。
 *
 * ⚠️ 皮肤是**运行时刷**的：卡片每次弹出都是新 inflate 的 View，
 * 所以必须在 `show()` 里刷，不能只在页面初始化时刷一次。
 */
object PersonaCardSkin {

    /**
     * 外层卡片的底：**不透明**的藏青渐变。
     *
     * 早先给的是 0xF2 的半透明底（想让后面的页面透一点），实测很难看：
     * 页面上的白纸和黑字会从卡片里「隐约浮出来」，读起来像是卡片没渲染全。
     * 样稿里的卡本来就是实心的，遮蔽感才是重点。
     */
    private val CARD_TOP = 0xFF13265F.toInt()
    private val CARD_BOTTOM = 0xFF0A1740.toInt()

    /** 内层「一块一块」的区域不加底，只靠分割线分开 —— 样稿就是这样 */
    private const val DIVIDER = 0x386FA8FF

    fun apply(root: View) {
        val density = root.resources.displayMetrics.density
        fun dp(v: Float) = v * density

        // 1) 外层：换成对话框（直角矩形 + 亮蓝描边）。
        //    样稿的卡片是硬边矩形，不做缺角 —— 斜切只留给标题和胶带。
        val outer = root.findViewById<View>(R.id.card)
        outer?.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(CARD_TOP, CARD_BOTTOM)).apply {
            cornerRadius = 0f
            setStroke(dp(1.5f).toInt().coerceAtLeast(1), PersonaP3.STROKE)
        }

        walk(root) { v ->
            when (v) {
                is TextView -> tintText(v, density)

                // 1dp 的分割线：给出一条「亮蓝渐隐」的感觉 —— 纯色也行，
                // 重点是别在黑卡上留一条浅灰线
                is View -> {
                    val h = v.layoutParams?.height ?: 0
                    if (v.id == View.NO_ID && h in 1..2) {
                        v.setBackgroundColor(DIVIDER)
                    }
                }
            }
        }

        // 2) 内层课程卡：去掉白底，让它读起来是「黑卡里的一段」，而不是卡里还有卡
        walk(root) { v ->
            if (v is ViewGroup && v.id == R.id.card && v !== outer) {
                v.background = null
            }
        }
    }

    private fun walk(v: View, visit: (View) -> Unit) {
        visit(v)
        if (v is ViewGroup) {
            for (child in v.children) walk(child, visit)
        }
    }

    private fun tintText(tv: TextView, density: Float) {
        fun dp(v: Float) = v * density
        // 字体统一换成 P3 的宽体；字号在 XML 里已经定过，这里只动个别
        tv.typeface = PersonaAlarmStyle.face
        when (tv.id) {
            R.id.tvTitle -> {
                tv.setTextColor(PersonaP3.TEXT)
                tv.textSize = 17f
            }

            R.id.tvSubtitle -> tv.setTextColor(PersonaP3.DIM)

            R.id.tvName -> {
                tv.setTextColor(PersonaP3.TEXT)
                tv.typeface = PersonaP3.cnHeavy
                tv.textSize = 16f
            }

            // 「本周 / 第 N 周」：青色徽标，深色字
            R.id.tvBadge -> {
                tv.background = GradientDrawable().apply {
                    setColor(PersonaP3.CYAN)
                    cornerRadius = 0f
                }
                tv.setTextColor(0xFF02124F.toInt())
                tv.setPadding(dp(7f).toInt(), dp(2f).toInt(), dp(7f).toInt(), dp(3f).toInt())
            }

            // 星期·节次、教室：这一页里「值」的强调色是青
            R.id.tvWhen, R.id.tvPlace -> tv.setTextColor(PersonaP3.CYAN)

            R.id.tvTime, R.id.tvWeeks, R.id.tvTeacher -> tv.setTextColor(PersonaP3.TEXT)

            R.id.tvNote -> tv.setTextColor(PersonaP3.DIM)

            // 底部那枚「设置闹钟」：描边按钮（样稿的 dbtn）
            R.id.btnAlarm -> {
                tv.background = GradientDrawable().apply {
                    setColor(0x384884FF)
                    cornerRadius = 0f
                    setStroke(dp(1f).toInt().coerceAtLeast(1), 0xFF6FA8FF.toInt())
                }
                tv.setTextColor(PersonaP3.TEXT)
                tv.setPadding(0, dp(10f).toInt(), 0, dp(10f).toInt())
            }

            // 没有 id 的都是「时间 / 教室 / 老师 / 周次 / 备注」那五个两字标签
            else -> if (tv.id == View.NO_ID) tv.setTextColor(0xFF7FA6E8.toInt())
        }
    }
}
