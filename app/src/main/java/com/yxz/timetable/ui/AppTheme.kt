package com.yxz.timetable.ui

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import com.yxz.timetable.R

/**
 * 强调色主题。
 *
 * ## 怎么让「换主题」这件事在 Android 上成立
 *
 * 布局里的颜色是**编译期绑定**的，运行时改不了。所以做法是分成两层：
 *
 * ```
 * 布局      ?attr/accentColor          ← 只说「这里要强调色」，不说具体是什么色
 *   ↓
 * 主题      Theme.Timetable            ← accentColor = 蓝色
 *           Theme.Timetable.Purple     ← accentColor = 紫色
 *   ↓
 * 色值      colors.xml / values-night  ← 八种颜色 × 日/夜 的具体取值
 * ```
 *
 * 切换时只要 `setTheme(另一套)` 再 `recreate()`，整个界面就换色了，
 * 布局文件一行都不用动。
 *
 * ## 加了这个枚举之后，多了一处「必须记住的事」
 *
 * 新增主题时**三个地方要一起改**，漏一个就会出现奇怪的半拉子状态：
 *
 *   1. [resId] 指向的 style（themes.xml 里那份）
 *   2. colors.xml 里的日间色值
 *   3. values-night/colors.xml 里的夜间色值
 *
 * 漏了 2 或 3 的话，编译不会报错，只是那个主题在日间或夜间下
 * 退回成上一套主题的颜色 —— **静默的错误**。
 * 所以 [AppThemeTest] 里有一条专门盯着这个。
 *
 * ## 为什么枚举里还要存 [accentRes]
 *
 * 设置页那个画板要给**每套主题**涂一个色块。色块上该显示的是「这套主题的
 * 主色」，不是「当前主题的主色」—— 后者会把八个格子涂成同一个颜色。
 * 所以画板不能走 `?attr/accentColor`，得直接拿到那张色号表里的值。
 */
enum class AppTheme(
    /** 存进 SharedPreferences 的值。**不要改成中文或索引** ——
     *  老用户存的是 "blue"，改名会让他们的设置失效（虽然只是回默认，不致命） */
    val key: String,
    @StyleRes val resId: Int,
    /** 设置页里显示的名字 */
    val label: String,
    /**
     * 日间主色。画板的色块直接用它。
     *
     * 为什么不写成 `accent_${key}` 拼字符串：那会把「命名约定」变成运行时
     * 依赖，改一个色值名（比如 accent_pink → accent_rose）编译器不会提醒你，
     * 只在运行时找不到资源。写成 [ColorRes] 就有编译期检查。
     */
    @ColorRes val accentRes: Int
) {
    BLUE("blue", R.style.Theme_Timetable, "蓝色", R.color.accent_blue),

    /**
     * 青色。比绿色偏冷，和蓝色区分得开 —— 两套都在的时候，
     * 「青」和「蓝」在深色模式下亮度接近，但色相一个是 174° 一个是 210°，
     * 并排放能看出来，单看也不会认错。
     */
    TEAL("teal", R.style.Theme_Timetable_Teal, "青色", R.color.accent_teal),

    GREEN("green", R.style.Theme_Timetable_Green, "绿色", R.color.accent_green),

    /**
     * 紫色。Material Design 的默认主色就是它 —— 但正因为「默认」，
     * 这里刻意把它做成一档可选，而不是默认：默认色要稳（蓝），
     * 好玩的颜色让人自己挑。
     */
    PURPLE("purple", R.style.Theme_Timetable_Purple, "紫色", R.color.accent_purple),

    PINK("pink", R.style.Theme_Timetable_Pink, "玫红", R.color.accent_pink),
    ORANGE("orange", R.style.Theme_Timetable_Orange, "橙色", R.color.accent_orange),
    RED("red", R.style.Theme_Timetable_Red, "红色", R.color.accent_red),
    INDIGO("indigo", R.style.Theme_Timetable_Indigo, "靛蓝", R.color.accent_indigo);

    companion object {
        /** 默认用蓝色 —— 老用户升级上来看到的是原来的样子，不会被突然换色 */
        val DEFAULT = BLUE

        /** 认不出来的 key 一律回落到默认，而不是抛异常（存储损坏不该让 App 打不开） */
        fun from(key: String?): AppTheme =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 读当前主题的强调色 */
fun Context.accentColor(): Int = themeColor(R.attr.accentColor, R.color.accent_blue)

/** 读当前主题的强调色浅底 */
fun Context.accentSoftColor(): Int = themeColor(R.attr.accentSoftColor, R.color.accent_soft_blue)

/**
 * 某套主题的主色（不管它是不是当前主题）。
 *
 * 画板要用它；日间 / 夜间由资源限定符自动决定 —— 深色模式下画板里的色块
 * 会显示夜间那批色，正好是「选了它会变成什么样」。
 */
fun Context.themeAccent(theme: AppTheme): Int = ContextCompat.getColor(this, theme.accentRes)

/**
 * 从当前主题里解析一个颜色属性。
 *
 * [fallback] 是**主题里没有这个属性时**的退路。什么时候会没有？——
 * 比如误传了 `applicationContext`：它身上挂的是 AndroidManifest 里声明的
 * 主题，而不是用户选的那套。
 *
 * 这时候的两个选择：
 *   · 抛异常 → 崩溃，用户完全不知道发生了什么
 *   · 回落到默认色 → 界面照常能用，只是颜色不对
 *
 * 选了后者，但**回落用的是蓝色而不是灰色之类的哨兵值** ——
 * 蓝色是「正常可用的颜色」，界面不会看起来坏掉；
 * 而如果用灰色当哨兵，用户会以为 App 出故障了，我们也拿不到额外信息。
 */
private fun Context.themeColor(@AttrRes attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) {
        return ContextCompat.getColor(this, fallback)
    }
    return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
}
