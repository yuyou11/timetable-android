package com.yxz.timetable.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.yxz.timetable.R
import com.yxz.timetable.data.CalEvent
import com.yxz.timetable.data.Events
import com.yxz.timetable.data.Festivals
import com.yxz.timetable.data.Lunar
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.DialogDayEventsBinding
import java.time.LocalDate

/**
 * 某一天的日程：列出来、点一条去改、底部一个「＋ 新建日程」。
 *
 * 为什么用对话框而不是在日历下面内嵌一块面板：日历页两套外观（默认的格子网格
 * 和 P3 的整页自绘）差得太远，内嵌面板得写两遍布局、还得各管一套滚动；
 * 一个对话框两边共用，而且和课表页「点格子 → 弹卡」是同一个手势语言。
 *
 * 编辑 / 新建是在它**上面**再开一张卡，而不是把它关掉：改完一条回到列表，
 * 还能接着改下一条；按返回也只是回到列表，不会一路退到日历。
 *
 * @param onChanged 增删改之后回调，宿主（日历页）重新读数据并重画格子上的标记
 */
class DayEventsDialog(
    private val ctx: Context,
    private val store: Store,
    private val date: LocalDate,
    private val onChanged: () -> Unit
) {

    private var binding: DialogDayEventsBinding? = null

    fun show(): AlertDialog {
        val b = DialogDayEventsBinding.inflate(LayoutInflater.from(ctx))
        binding = b

        b.btnAdd.setOnClickListener { openEditor(null) }

        val dialog = CardAlertDialogBuilder(ctx)
            .setView(b.root)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
        render()
        return dialog
    }

    /** 按当前存储重画列表（新增 / 修改 / 删除之后都走这里） */
    private fun render() {
        val b = binding ?: return
        val events = Events.on(store.events(), date)

        val lunar = Lunar.of(date)
        val festival = Festivals.of(date, lunar)
        b.tvDayTitle.text = "${date.monthValue} 月 ${date.dayOfMonth} 日 · ${weekdayCn(date.dayOfWeek.value)}"
        b.tvDaySub.text = listOfNotNull(
            festival,
            "农历${lunar.monthName}${lunar.dayName}",
            if (events.isEmpty()) null else "${events.size} 条日程"
        ).joinToString(" · ")

        b.tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        b.listContainer.removeAllViews()
        for (ev in events) {
            if (b.listContainer.childCount > 0) b.listContainer.addView(divider())
            b.listContainer.addView(row(ev))
        }
    }

    // ------------------------------------------------------------------

    /** 一行：左边开始时刻，右边标题 + 地点，每周的加一枚小标签 */
    private fun row(ev: CalEvent): View {
        val ctx = this.ctx
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val time = TextView(ctx).apply {
            text = clock(ev.start)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.accentColor())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = ev.title
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (ev.weekly) {
            titleRow.addView(TextView(ctx).apply {
                text = "每周"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(ctx.accentColor())
                setBackgroundResource(R.drawable.bg_card_accent)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            })
        }

        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        right.addView(titleRow)
        if (ev.place.isNotBlank()) {
            right.addView(TextView(ctx).apply {
                text = ev.place
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setBackgroundResource(selectableBackground())
            addView(time)
            addView(right)
            setOnClickListener { openEditor(ev) }
        }
    }

    private fun divider(): View {
        val density = ctx.resources.displayMetrics.density
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (density).toInt()
            )
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider))
        }
    }

    private fun selectableBackground(): Int {
        val out = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    /**
     * 打开编辑器（开在这张列表卡上面）。存完 / 删完之后重画列表 ——
     * 用户是在「这一天的日程」里点进来的，回去时应该还站在这天。
     */
    private fun openEditor(event: CalEvent?) {
        EventEditor(ctx, store, event, date) {
            onChanged()
            render()
        }.show()
    }

    private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}
