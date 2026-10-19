package com.yxz.timetable.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.DatePicker
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.yxz.timetable.R
import com.yxz.timetable.data.HolidayDay
import com.yxz.timetable.data.Holidays
import com.yxz.timetable.data.Store
import java.time.LocalDate

/**
 * 手动维护「放假 / 补班日」。
 *
 * ============================================================
 *  为什么需要这个入口
 * ============================================================
 *
 * 内置表只覆盖国务院已经发布通知的年份（现在只有 2026）。出现下面这些情况时，
 * 内置表一定不够用：
 *
 *   · **跨年**：2027 年的安排要到 2026 年 11 月才发布，那之前表里没有
 *   · **学校自己的安排**：校庆、调课、寒暑假起止，国务院的通知里当然没有
 *   · **临时调休**：今天早上才通知的半天假
 *
 * 所以给一个手动入口，规则和内置表完全一样（放假 / 补班两种），
 * **手动那份优先**，可以覆盖内置表里写错或过期的判断。
 */
class HolidayEditor(
    private val ctx: Context,
    private val store: Store,
    private val onChanged: () -> Unit
) {

    private val listBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(0))
    }
    private var dialog: AlertDialog? = null

    fun show() {
        val d = CardAlertDialogBuilder(ctx)
            .setTitle("放假 / 补班日")
            .setView(listBox)
            .setPositiveButton("添加一天") { _, _ -> askAdd() }
            .setNegativeButton("关闭", null)
            .create()
        dialog = d
        d.show()
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val builtin = Holidays.BUILTIN.size
        listBox.addView(
            TextView(ctx).apply {
                text = "内置 $builtin 天（${Holidays.COVERED_YEARS.sorted().joinToString("、")} 年）；" +
                        "下面是你手动加的，优先于内置"
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                includeFontPadding = false
                setPadding(0, 0, 0, dp(8))
            }
        )

        val custom = store.customHolidays()
        if (custom.isEmpty()) {
            listBox.addView(
                TextView(ctx).apply {
                    text = "还没有手动加过。"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    includeFontPadding = false
                }
            )
            return
        }

        for (day in custom.sortedBy { it.date }) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            row.addView(
                TextView(ctx).apply {
                    text = buildString {
                        append(day.date.toString())
                        append("  ")
                        append(if (day.offDay) "放假" else "补班")
                        if (day.name.isNotBlank()) append(" · ${day.name}")
                    }
                    textSize = 13.5f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(
                TextView(ctx).apply {
                    text = "删除"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ctx.accentColor())
                    includeFontPadding = false
                    setOnClickListener { remove(day) }
                }
            )
            listBox.addView(row)
        }
    }

    private fun remove(day: HolidayDay) {
        val list = store.customHolidays()
        list.removeAll { it.date == day.date }
        store.saveCustomHolidays(list)
        onChanged()
        render()
    }

    private fun askAdd() {
        val picker = DatePicker(ctx).apply { init(2027, 0, 1) { _, _, _, _ -> } }
        val name = EditText(ctx).apply { hint = "名称（可不填，例如「校庆」）" }
        val kind = TextView(ctx).apply {
            text = "类型：放假（点一下切换成补班）"
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            includeFontPadding = false
            setPadding(0, dp(6), 0, dp(6))
        }
        var offDay = true
        kind.setOnClickListener {
            offDay = !offDay
            kind.text = if (offDay) "类型：放假（点一下切换成补班）" else "类型：补班 / 上班（点一下切换成放假）"
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(picker)
            addView(kind)
            addView(name)
        }

        CardAlertDialogBuilder(ctx)
            .setTitle("添加一天")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val date = LocalDate.of(picker.year, picker.month + 1, picker.dayOfMonth)
                val list = store.customHolidays()
                // 同一天只能是一种性质：先清掉旧的再放新的
                list.removeAll { it.date == date }
                list.add(HolidayDay(name.text.toString().trim(), date, offDay))
                store.saveCustomHolidays(list)
                onChanged()
                render()
                Toast.makeText(
                    ctx,
                    "$date 已标记为${if (offDay) "放假" else "补班"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
