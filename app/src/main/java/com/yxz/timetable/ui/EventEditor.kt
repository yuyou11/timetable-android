package com.yxz.timetable.ui

import android.app.DatePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.yxz.timetable.data.CalEvent
import com.yxz.timetable.data.Events
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.databinding.DialogEventEditBinding
import java.time.LocalDate

/**
 * 新建 / 编辑一条日历日程。
 *
 * 只有两件事要保存：**什么时候**（日期 + 起止分钟）和**叫什么**。
 * 地点、备注、每周重复都是可选的修饰。校验交给 [Events.normalize] ——
 * 界面只负责把「为什么不让你存」说清楚，规则本身在数据层（有单测）。
 *
 * @param initial 传 null 表示新建；传一条表示改它（此时多一个「删除」）
 * @param defaultDate 新建时默认落在哪一天 —— 就是用户在日历里点的那天
 * @param onChanged 存了 / 删了之后回调，宿主要重新读一遍并重画
 */
class EventEditor(
    private val ctx: Context,
    private val store: Store,
    private val initial: CalEvent?,
    private val defaultDate: LocalDate,
    private val onChanged: () -> Unit
) {

    private val binding = DialogEventEditBinding.inflate(LayoutInflater.from(ctx))

    private var date: LocalDate = initial?.date ?: defaultDate
    private var startMin: Int = initial?.start ?: DEFAULT_START
    private var endMin: Int = initial?.end ?: (DEFAULT_START + 60)

    /** 自己那个对话框：删除之后要把它一起关掉（它开在确认框的下面） */
    private var self: AlertDialog? = null

    fun show(): AlertDialog {
        render()

        binding.rowDate.setOnClickListener { pickDate() }
        binding.tvStart.setOnClickListener { pickTime(isStart = true) }
        binding.tvEnd.setOnClickListener { pickTime(isStart = false) }
        binding.btnDelete.visibility = if (initial == null) android.view.View.GONE
        else android.view.View.VISIBLE
        // 删除是「危险动作」，颜色按语义写死在代码里：它不该跟着八套主题变色
        // （布局里不能直连 @color/accent_*，那是 AppThemeTest 守着的规矩）
        binding.btnDelete.setTextColor(
            androidx.core.content.ContextCompat.getColor(ctx, com.yxz.timetable.R.color.accent_red)
        )
        binding.btnDelete.setOnClickListener { confirmDelete() }

        val dialog = CardAlertDialogBuilder(ctx)
            .setTitle(if (initial == null) "新建日程" else "编辑日程")
            .setView(binding.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        // 「保存」不自动关窗：校验不过要停在原地让人改，而不是弹个 Toast 把窗口关了
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(dialog) }
        }
        self = dialog
        dialog.show()
        return dialog
    }

    // ------------------------------------------------------------------

    private fun render() {
        binding.tvDate.text = "%d月%d日 %s".format(
            date.monthValue, date.dayOfMonth, weekdayCn(date.dayOfWeek.value)
        )
        binding.tvStart.text = clock(startMin)
        binding.tvEnd.text = clock(endMin)
        binding.tvWeeklyHint.text = "每周${weekdayCn(date.dayOfWeek.value)}这个时间"
        binding.swWeekly.isChecked = initial?.weekly ?: false

        initial?.let {
            binding.etTitle.setText(it.title)
            binding.etPlace.setText(it.place)
            binding.etNote.setText(it.note)
            binding.etTitle.setSelection(it.title.length)
        }
    }

    private fun pickDate() {
        DatePickerDialog(
            ctx,
            { _, y, m, d ->
                date = LocalDate.of(y, m + 1, d)
                render()
            },
            date.year, date.monthValue - 1, date.dayOfMonth
        ).show()
    }

    private fun pickTime(isStart: Boolean) {
        val cur = if (isStart) startMin else endMin
        TimeDialDialog(ctx, cur / 60, cur % 60) { h, m ->
            val picked = h * 60 + m
            if (isStart) {
                startMin = picked
                // 开始往后挪、结束没跟上时，把结束顺延一小时 ——
                // 否则「23:00 → 09:00」这种倒挂要用户自己再去改一次才存得进去
                if (endMin <= startMin) endMin = (startMin + 60).coerceAtMost(24 * 60)
            } else {
                endMin = picked
            }
            render()
        }.show()
    }

    private fun save(dialog: AlertDialog) {
        val title = binding.etTitle.text.toString()
        if (title.isBlank()) {
            toast("先给这条日程起个名字")
            return
        }
        if (endMin <= startMin) {
            toast("结束时间要晚于开始时间")
            return
        }

        val draft = CalEvent(
            id = initial?.id ?: store.nextEventId(),
            date = date,
            start = startMin,
            end = endMin,
            title = title,
            place = binding.etPlace.text.toString(),
            note = binding.etNote.text.toString(),
            weekly = binding.swWeekly.isChecked,
            // 截止日这一版没有界面，编辑时原样带回去，不把已有数据抹掉
            until = initial?.until
        )
        val event = Events.normalize(draft) ?: run {
            toast("这条日程存不了：标题是空的，或者时间不成立")
            return
        }

        val list = store.events()
        val index = list.indexOfFirst { it.id == event.id }
        if (index >= 0) list[index] = event else list += event
        store.saveEvents(list)
        dialog.dismiss()
        onChanged()
    }

    private fun confirmDelete() {
        val ev = initial ?: return
        CardAlertDialogBuilder(ctx)
            .setTitle("删除日程")
            .setMessage("「${ev.title}」${if (ev.weekly) "（每周重复）" else ""}会被删掉，不能恢复。")
            .setPositiveButton("删除") { _, _ -> delete() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun delete() {
        val ev = initial ?: return
        val list = store.events().filterNot { it.id == ev.id }
        store.saveEvents(list)
        self?.dismiss()
        onChanged()
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private companion object {
        /** 新建时默认 09:00–10:00：比"现在"更不容易踩到凌晨，用户改起来也就两步 */
        const val DEFAULT_START = 9 * 60
    }
}
