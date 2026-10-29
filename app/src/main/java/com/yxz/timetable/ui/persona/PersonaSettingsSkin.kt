package com.yxz.timetable.ui.persona

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.google.android.material.switchmaterial.SwitchMaterial
import com.yxz.timetable.R
import com.yxz.timetable.ui.accentColor

/**
 * 设置页分类页的 P3 皮肤。
 *
 * ## 为什么用「按颜色认」而不是按 id 认
 *
 * 设置页有 77 个 TextView、21 个 Button、4 个开关，还有代码里现搭的课程行
 * （[com.yxz.timetable.ui.SettingsFragment.renderCourses]）—— 按 id 一个一个写，
 * 既写不完也守不住：以后加一个设置项，忘登记就变成一块白卡浮在深蓝上。
 *
 * 所以这里认的是**这套布局的语言**：
 *
 *  · 底色 = `card_bg`（白）的圆角 shape → 深藏青直角卡片 + 亮蓝描边；
 *  · 底色 = `accentSoftColor` → 深一点的面板 + 青边（「提示」那种强调卡）；
 *  · 文字色 = `text_primary` → 白，`text_secondary` → 浅蓝，`accentColor` → 青；
 *  · 分隔线色 → 半透明蓝；
 *  · `Button` → 直角描边按钮；`SwitchMaterial` → 青槽 + 亮钮。
 *
 * 全是可逆的「换色 + 换形状」，不动任何布局参数、监听器和可见性 ——
 * 设置页那套刷新逻辑一行都没碰。
 *
 * ## 什么时候调
 *
 * 皮肤是运行时刷的：`showPage`（切页）和 `refresh`（数据变了、课程行重建）之后都要调，
 * 而且必须**可重复调用**（现在是「认颜色就换」，第二次进来不会越叠越厚）。
 */
internal object PersonaSettingsSkin {

    /** 卡片底：深藏青（比页面深一档） */
    private const val CARD_FILL = 0xE60A1740.toInt()

    /** 普通卡片的描边：亮蓝 */
    private const val STROKE = 0xFF6FA8FF.toInt()

    /** 分隔线：半透明蓝 */
    private const val DIVIDER = 0x336FA8FF

    fun apply(root: View) {
        val ctx = root.context
        val cardBg = ContextCompat.getColor(ctx, R.color.card_bg)
        val primary = ContextCompat.getColor(ctx, R.color.text_primary)
        val secondary = ContextCompat.getColor(ctx, R.color.text_secondary)
        val dividerColor = ContextCompat.getColor(ctx, R.color.divider)
        val accentSoft = accentSoft(ctx)
        val accent = ctx.accentColor()
        val density = ctx.resources.displayMetrics.density

        // 第一遍：底色和形状（卡片 / 提示卡 / 分隔线）
        val cardState = ContextCompat.getDrawable(ctx, R.drawable.bg_card)?.constantState
        val accentState = ContextCompat.getDrawable(ctx, R.drawable.bg_card_accent)?.constantState
        walk(root) { v ->
            val bg = v.background as? GradientDrawable ?: return@walk
            val color = bg.color?.defaultColor
            val state = bg.constantState
            val isCard = (state != null && cardState != null && state === cardState) ||
                    (color != null && color == cardBg)
            val isAccent = (state != null && accentState != null && state === accentState) ||
                    (color != null && color == accentSoft)
            when {
                // 卡片：**换掉**背景而不是改颜色 —— GradientDrawable 的 ConstantState 是
                // 资源缓存里共享的那一份，就地 setColor 会把缓存也染黑，
                // 之后关掉 P3 重进设置页，默认外观的白卡就变成黑卡了
                isCard -> v.background = personaCard(STROKE, density)
                isAccent -> v.background = personaCard(PersonaP3.CYAN, density)
                color != null && color == dividerColor -> bg.setColor(DIVIDER)
            }
        }

        // 第二遍：文字、按钮、开关
        walk(root) { v ->
            when (v) {
                // ⚠️ 开关和勾选框必须排在 Button **前面**：SwitchMaterial / CheckBox
                // 都是 CompoundButton，而 CompoundButton 继承 Button ——
                // 先判 Button 的话它们会被当成普通按钮，套上一个描边方块，
                // 屏幕上就是一个「开关外面还有一圈框」的怪东西
                is SwitchMaterial -> {
                    v.thumbTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(PersonaP3.CYAN, 0xFFAEB8D8.toInt())
                    )
                    v.trackTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(0x664660DC.toInt(), 0x33FFFFFF.toInt())
                    )
                }

                // 勾选框：默认是主题蓝的方块，在深藏青卡上偏亮，换成青色
                is android.widget.CheckBox -> v.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(PersonaP3.CYAN, 0xFF7FA6E8.toInt())
                )

                is android.widget.RadioButton -> v.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(PersonaP3.CYAN, 0xFF7FA6E8.toInt())
                )

                is Button -> {
                    v.background = GradientDrawable().apply {
                        setColor(0x334884FF)
                        cornerRadius = 0f
                        setStroke((1f * density).toInt().coerceAtLeast(1), STROKE)
                    }
                    v.setTextColor(PersonaP3.TEXT)
                    v.typeface = PersonaP3.cnBold
                    v.isAllCaps = false
                }

                is TextView -> {
                    when (v.currentTextColor) {
                        primary -> v.setTextColor(PersonaP3.TEXT)
                        secondary -> v.setTextColor(PersonaP3.DIM)
                        accent -> v.setTextColor(PersonaP3.CYAN)
                    }
                    // 小节标题（小而青的那行字）换成 P3 的「字距拉开的标签」
                    if (v.textSize < 13f * resourcesDensity(v) && v.currentTextColor == PersonaP3.CYAN) {
                        v.letterSpacing = 0.18f
                        v.typeface = PersonaP3.tag
                    }
                }
            }
        }
    }

    /** 一张 P3 卡片：直角、深藏青、细描边 */
    private fun personaCard(strokeColor: Int, density: Float): GradientDrawable = GradientDrawable().apply {
        setColor(CARD_FILL)
        cornerRadius = 0f
        setStroke((1.5f * density).toInt().coerceAtLeast(1), strokeColor)
    }

    /** 强调浅底：主题色不同、具体色值也不同，直接按当前主题取 */
    private fun accentSoft(ctx: android.content.Context): Int {
        val tv = android.util.TypedValue()
        return if (ctx.theme.resolveAttribute(R.attr.accentSoftColor, tv, true)) {
            ContextCompat.getColor(ctx, tv.resourceId)
        } else {
            0
        }
    }

    private fun resourcesDensity(v: View): Float = v.resources.displayMetrics.density

    private fun walk(v: View, visit: (View) -> Unit) {
        visit(v)
        if (v is ViewGroup) {
            for (child in v.children) walk(child, visit)
        }
    }
}
