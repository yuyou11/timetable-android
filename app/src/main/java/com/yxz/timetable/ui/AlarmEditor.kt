package com.yxz.timetable.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.yxz.timetable.R
import com.yxz.timetable.data.Alarm
import com.yxz.timetable.data.AlarmSource
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.Course
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.DialogAlarmEditBinding
import com.yxz.timetable.notify.AlarmScheduler

/**
 * 新建 / 编辑一个闹钟。
 *
 * 普通闹钟和课程闹钟共用同一个对话框（见 dialog_alarm_edit.xml 的说明）：
 * 课程闹钟藏掉时间选择器和周几，换成一行「课程：xxx」+ 提前量档位。
 *
 * 这里**不碰 Android 的闹钟**，只负责把 [Alarm] 存进 [Store]，
 * 存完叫一次 [AlarmScheduler.scheduleAlarms] —— 排程的事全在那一边，
 * 免得两个地方各排一遍、互相覆盖。
 */
class AlarmEditor(
    private val ctx: Context,
    private val store: Store,
    private val existing: Alarm?,
    private val courses: List<Course>,
    private val onSaved: () -> Unit,
    /** 由 Fragment 用 ActivityResult 实现：打开系统铃声选择器 */
    private val pickSound: (current: String) -> Unit
) {

    private val binding = DialogAlarmEditBinding.inflate(LayoutInflater.from(ctx))
    private val isCourse = existing?.source == AlarmSource.COURSE

    private val days = (existing?.days ?: Alarms.WORKDAYS).toMutableSet()
    private var lead = existing?.leadMinutes ?: 10
    private var snooze = (existing?.snoozeMinutes ?: Alarms.MIN_SNOOZE_MINUTES)
        .coerceAtLeast(Alarms.MIN_SNOOZE_MINUTES)
    private var sound = existing?.soundUri ?: ""
    private var dialog: AlertDialog? = null

    private val dayChips = mutableMapOf<Int, TextView>()
    private val presetChips = mutableListOf<Pair<Set<Int>, TextView>>()
    private val leadChips = mutableMapOf<Int, TextView>()
    private val snoozeChips = mutableMapOf<Int, TextView>()

    private companion object {
        val EVERY_DAY = setOf(1, 2, 3, 4, 5, 6, 7)
        val LEAD_PRESETS = listOf(1, 5, 10, 20)
        val SNOOZE_OPTIONS = listOf(10, 15, 20, 30)
    }

    init {
        setupTarget()
        setupTime()
        setupDays()
        setupLead()
        setupSound()
        setupHoliday()
        setupSnooze()
    }

    fun show(): AlertDialog {
        val title = when {
            isCourse -> "课程闹钟"
            existing == null -> "新建闹钟"
            else -> "编辑闹钟"
        }
        val d = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .apply { if (existing != null) setNeutralButton("删除") { _, _ -> delete() } }
            .create()
        // 保存要校验（一天都没选就别存），所以自己接管点击，不让它直接关掉对话框
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(d) }
        }
        dialog = d
        d.show()
        return d
    }

    // ------------------------------------------------------------------
    //  界面
    // ------------------------------------------------------------------

    private fun setupTarget() {
        if (!isCourse) return
        val name = courses.firstOrNull { it.id == existing?.courseId }?.name ?: "已删除的课"
        binding.tvTarget.visibility = View.VISIBLE
        binding.tvTarget.text = "绑定课程：$name"
        // 课程闹钟的时刻由课表推，不是用户填的 —— 这两块整个收起来
        binding.timePicker.visibility = View.GONE
        binding.dayRow.visibility = View.GONE
        binding.presetRow.visibility = View.GONE
        binding.etLabel.visibility = View.GONE
        binding.tvLeadTitle.visibility = View.VISIBLE
        binding.leadRow.visibility = View.VISIBLE
    }

    private fun setupTime() {
        if (isCourse) return
        binding.timePicker.setIs24HourView(true)
        binding.timePicker.hour = existing?.hour ?: 7
        binding.timePicker.minute = existing?.minute ?: 0
    }

    private fun setupDays() {
        if (isCourse) return
        for (d in 1..7) {
            val chip = chip(weekdayCn(d).removePrefix("周"), d in days) { toggleDay(d) }
            dayChips[d] = chip
            binding.dayRow.addView(chip)
        }
        for ((label, set) in listOf(
            "工作日" to Alarms.WORKDAYS,
            "周末" to Alarms.WEEKEND,
            "每天" to EVERY_DAY
        )) {
            val c = chip(label, days == set) { days.clear(); days += set; refreshDays() }
            presetChips += set to c
            binding.presetRow.addView(c)
        }
    }

    private fun toggleDay(d: Int) {
        if (d in days) days.remove(d) else days.add(d)
        refreshDays()
    }

    private fun refreshDays() {
        for ((d, chip) in dayChips) styleChip(chip, d in days)
        for ((set, chip) in presetChips) styleChip(chip, days == set)
    }

    private fun setupLead() {
        if (!isCourse) return
        for (m in LEAD_PRESETS) {
            val chip = chip("$m 分钟", lead == m) { lead = m; refreshLead() }
            leadChips[m] = chip
            binding.leadRow.addView(chip)
        }
        val custom = chip("自定义", lead !in LEAD_PRESETS) { askCustomLead() }
        leadChips[-1] = custom
        binding.leadRow.addView(custom)
    }

    private fun refreshLead() {
        for ((m, chip) in leadChips) {
            styleChip(chip, if (m == -1) lead !in LEAD_PRESETS else lead == m)
        }
    }

    private fun askCustomLead() {
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "提前多少分钟（0–120）"
            setText(lead.toString())
        }
        AlertDialog.Builder(ctx)
            .setTitle("自定义提前量")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                input.text.toString().toIntOrNull()?.let {
                    lead = it.coerceIn(0, 120)
                    refreshLead()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupSound() {
        binding.tvSound.text = if (sound.isBlank()) "默认（系统铃声）"
        else SoundPicker.nameOf(ctx, sound)
        binding.btnPickSound.setOnClickListener { pickSound(sound) }
        // 点名字试听，长按「更换」也试听 —— 试听入口藏在二级菜单里没人找得到
        binding.tvSound.setOnClickListener { SoundPicker.play(ctx, sound) }
        binding.btnPickSound.setOnLongClickListener {
            SoundPicker.play(ctx, sound)
            true
        }
    }

    private fun setupHoliday() {
        binding.swHoliday.isChecked = existing?.skipHolidays ?: true
        binding.tvHolidayNote.text = if (isCourse) {
            "法定放假日不响；这门课没上的那一周本来就不会响"
        } else {
            "法定放假日不响；补班日（周末上班）按工作日响"
        }
    }

    private fun setupSnooze() {
        for (m in SNOOZE_OPTIONS) {
            val chip = chip("$m 分钟", snooze == m) { snooze = m; refreshSnooze() }
            snoozeChips[m] = chip
            binding.snoozeRow.addView(chip)
        }
    }

    private fun refreshSnooze() {
        for ((m, chip) in snoozeChips) styleChip(chip, snooze == m)
    }

    // ------------------------------------------------------------------
    //  存取
    // ------------------------------------------------------------------

    private fun save(d: AlertDialog) {
        if (!isCourse && days.isEmpty()) {
            Toast.makeText(ctx, "至少选一天", Toast.LENGTH_SHORT).show()
            return
        }
        val built = if (isCourse) {
            val base = existing ?: Alarms.forCourse(store.nextAlarmId(), "", lead)
            base.copy(
                leadMinutes = lead,
                soundUri = sound,
                skipHolidays = binding.swHoliday.isChecked,
                snoozeMinutes = snooze
            )
        } else {
            Alarm(
                id = existing?.id ?: store.nextAlarmId(),
                hour = binding.timePicker.hour,
                minute = binding.timePicker.minute,
                days = days.toSet(),
                enabled = existing?.enabled ?: true,
                label = binding.etLabel.text.toString().trim(),
                soundUri = sound,
                skipHolidays = binding.swHoliday.isChecked,
                snoozeMinutes = snooze
            )
        }

        val list = store.alarms()
        val at = list.indexOfFirst { it.id == built.id }
        if (at >= 0) list[at] = built else list.add(built)
        store.saveAlarms(list)
        AlarmScheduler.scheduleAlarms(ctx)
        onSaved()
        d.dismiss()
    }

    private fun delete() {
        val id = existing?.id ?: return
        val list = store.alarms()
        list.removeAll { it.id == id }
        store.saveAlarms(list)
        AlarmScheduler.scheduleAlarms(ctx)
        onSaved()
        dialog?.dismiss()
    }

    /** 铃声选择器回来了 */
    fun onSoundPicked(uri: String) {
        sound = uri
        binding.tvSound.text = if (uri.isBlank()) "静音" else SoundPicker.nameOf(ctx, uri)
    }

    // ------------------------------------------------------------------
    //  小工具：chips
    // ------------------------------------------------------------------

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            gravity = Gravity.CENTER
            setText(text)
            textSize = 13f
            includeFontPadding = false
            setPadding(dp(11), dp(6), dp(11), dp(6))
            styleChip(this, selected)
            setOnClickListener { onClick() }
        }

    /**
     * 选中态：强调色实底 + 白字；未选中：透明底 + 描边。
     *
     * 用代码画而不是 XML shape，理由和 WeekFragment.todayColumnBg 一样：
     * 「强调色」有蓝/绿 × 日/夜四种组合，写 XML 要备四份，漏一份就是静默错色。
     */
    private fun styleChip(v: TextView, selected: Boolean) {
        val accent = ctx.accentColor()
        v.setTextColor(if (selected) 0xFFFFFFFF.toInt() else accent)
        v.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            if (selected) {
                setColor(accent)
            } else {
                setColor(0x00000000)
                setStroke(dp(1), ContextCompat.getColor(ctx, R.color.divider))
            }
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
