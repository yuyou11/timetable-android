package com.yxz.timetable.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
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
 *           Theme.Timetable.Green      ← accentColor = 绿色
 *   ↓
 * 色值      colors.xml / values-night  ← 蓝/绿 × 日/夜 四种具体取值
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
 */
enum class AppTheme(
    /** 存进 SharedPreferences 的值。**不要改成中文或索引** ——
     *  老用户存的是 "blue"，改名会让他们的设置失效（虽然只是回默认，不致命） */
    val key: String,
    @StyleRes val resId: Int,
    /** 设置页里显示的名字 */
    val label: String
) {
    BLUE("blue", R.style.Theme_Timetable, "蓝色"),
    GREEN("green", R.style.Theme_Timetable_Green, "绿色");

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
