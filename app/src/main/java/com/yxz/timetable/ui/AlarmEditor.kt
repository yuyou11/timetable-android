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
import java.time.LocalDateTime

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
    private val isMoment = existing?.source == AlarmSource.MOMENT

    /**
     * 时刻不是用户填的那两种：课程闹钟和「按格子响」的闹钟。
     *
     * 它们的时刻都从课表 / 作息模板推出来，所以时间选择器、周几、名称
     * 这几块要整块收起来 —— 留着让用户填，填了也不生效，比没有更糟。
     */
    private val isBound = isCourse || isMoment

    private val days = (existing?.days ?: Alarms.WORKDAYS).toMutableSet()
    private var lead = existing?.leadMinutes ?: 10
    private var snooze = (existing?.snoozeMinutes ?: Alarms.MIN_SNOOZE_MINUTES)
        .coerceAtLeast(Alarms.MIN_SNOOZE_MINUTES)
    private var sound = existing?.soundUri ?: ""
    private var dialog: AlertDialog? = null

    /**
     * 当前选的时刻。圆盘里改的是这两个数，**保存时才落库** ——
     * 中途取消不该留下任何痕迹。
     *
     * 新建时默认就是**此刻**：早上想设一个「20 分钟后」的闹钟，
     * 不用先把一只停在 07:00 的盘拖到当前时间。
     * [openedAt] 只取一次 —— 分两次 now() 有可能跨过整分，时分就对不上了。
     */
    private val openedAt = LocalDateTime.now()
    private var hour = existing?.hour ?: openedAt.hour
    private var minute = existing?.minute ?: openedAt.minute

    private val dayChips = mutableMapOf<Int, TextView>()
    private val presetChips = mutableListOf<Pair<Set<Int>, TextView>>()
    private val leadChips = mutableMapOf<Int, TextView>()
    private val snoozeChips = mutableMapOf<Int, TextView>()

    private companion object {
        val EVERY_DAY = setOf(1, 2, 3, 4, 5, 6, 7)

        /**
         * 提前量档位。**0 是正经一档**：今天页上「午餐」「睡觉」这些格子
         * 要的是到点提醒，提前 5 分钟反而是错位。
         */
        val LEAD_PRESETS = listOf(0, 1, 5, 10, 20)
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
            isMoment -> "格子闹钟"
            existing == null -> "新建闹钟"
            else -> "编辑闹钟"
        }
        val d = CardAlertDialogBuilder(ctx)
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
        if (!isBound) return
        binding.tvTarget.visibility = View.VISIBLE
        binding.tvTarget.text = if (isCourse) {
            val name = courses.firstOrNull { it.id == existing?.courseId }?.name ?: "已删除的课"
            "绑定课程：$name"
        } else {
            // 响铃范围（每周三 / 每个 A 型日）是点格子设的时候定的，
            // 这里只如实写出来 —— 改它要回今日页点那一格
            val a = existing!!
            "绑定：${Alarms.momentScope(a, store.dayTypeNames())} · ${a.momentTitle}"
        }
        // 时刻由课表 / 模板推，不是用户填的 —— 这几块整个收起来。
        // 「重复」这个小标题也要跟着收，否则会留下一个没有内容的标题
        binding.timeBox.visibility = View.GONE
        binding.tvRepeatTitle.visibility = View.GONE
        binding.dayRow.visibility = View.GONE
        binding.presetRow.visibility = View.GONE
        binding.etLabel.visibility = View.GONE
        binding.tvLeadTitle.visibility = View.VISIBLE
        binding.tvLeadTitle.text = "提前多久响（0 = 到点提醒）"
        binding.leadRow.visibility = View.VISIBLE
        binding.leadCustomRow.visibility = View.VISIBLE
    }

    private fun setupTime() {
        if (isBound) return
        binding.timeBox.setOnClickListener {
            TimeDialDialog(ctx, hour, minute) { h, m ->
                hour = h
                minute = m
                renderTime()
                oneShotHint()
            }.show()
        }
        renderTime()
    }

    private fun renderTime() {
        binding.tvTime.text = "%02d:%02d".format(hour, minute)
    }

    private fun setupDays() {
        if (isBound) return
        for (d in 1..7) {
            val chip = chip(weekdayCn(d).removePrefix("周"), d in days, weight = 1f) { toggleDay(d) }
            dayChips[d] = chip
            binding.dayRow.addView(chip)
        }
        for ((label, set) in listOf(
            "工作日" to Alarms.WORKDAYS,
            "周末" to Alarms.WEEKEND,
            "每天" to EVERY_DAY,
            // 七天全取消 = 不重复、只响一次。这是闹钟的常规语义，
            // 而原来保存时被一句「至少选一天」拦住了 —— 文案和逻辑不一致，是 bug
            "只响一次" to emptySet()
        )) {
            val c = chip(label, days == set, weight = 1f) { days.clear(); days += set; refreshDays() }
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
        oneShotHint()
    }

    /**
     * 七天一个都没选时，把「只响一次」的具体时刻写在对话框顶部。
     *
     * 用户取消掉最后一个勾时最想知道的是「那它到底什么时候响」——
     * 不写出来的话，一个勾都不选的对话框看着像没设置好。
     */
    private fun oneShotHint() {
        if (isBound) return               // 这一行绑定闹钟用来写「绑定：xxx」
        if (days.isNotEmpty()) {
            binding.tvTarget.visibility = View.GONE
            return
        }
        val at = Alarms.oneShotDate(hour, minute, LocalDateTime.now())
        binding.tvTarget.visibility = View.VISIBLE
        binding.tvTarget.text = "只响一次：${at.monthValue} 月 ${at.dayOfMonth} 日" +
                "（${weekdayCn(at.dayOfWeek.value)}）" +
                "%02d:%02d".format(hour, minute)
    }

    private fun setupLead() {
        if (!isBound) return
        for (m in LEAD_PRESETS) {
            // 「0 分」写得和别的档一样短：六档挤一行时，「0 分钟」也照样被压成「0 分…」
            val chip = chip("$m 分", lead == m, weight = 1f) { lead = m; refreshLead() }
            leadChips[m] = chip
            binding.leadRow.addView(chip)
        }
        val custom = chip("自定义…", lead !in LEAD_PRESETS, weight = 1f) { askCustomLead() }
        leadChips[-1] = custom
        binding.leadCustomRow.addView(custom)
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
        CardAlertDialogBuilder(ctx)
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
        binding.tvHolidayNote.text = when {
            isCourse -> "法定放假日不响；这门课没上的那一周本来就不会响"
            isMoment -> "法定放假日不响；那天时间轴上没有这一格，本来也不会响"
            else -> "法定放假日不响；补班日（周末上班）按工作日响"
        }
    }

    private fun setupSnooze() {
        for (m in SNOOZE_OPTIONS) {
            val chip = chip("$m 分钟", snooze == m, weight = 1f) { snooze = m; refreshSnooze() }
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
        val built = when {
            isCourse -> {
                val base = existing ?: Alarms.forCourse(store.nextAlarmId(), "", lead)
                base.copy(
                    leadMinutes = lead,
                    soundUri = sound,
                    skipHolidays = binding.swHoliday.isChecked,
                    snoozeMinutes = snooze
                )
            }
            // 按格子响的闹钟：时刻和响铃范围都不是这里能改的，
            // 只让改提前量 / 铃声 / 贪睡 / 节假日
            isMoment -> {
                existing!!.copy(
                    leadMinutes = lead,
                    soundUri = sound,
                    skipHolidays = binding.swHoliday.isChecked,
                    snoozeMinutes = snooze
                )
            }
            else -> {
                Alarm(
                    id = existing?.id ?: store.nextAlarmId(),
                    hour = hour,
                    minute = minute,
                    days = days.toSet(),
                    // 七天全不选 = 只响一次：把「下一次那个时刻」算成具体日期固定下来
                    onceDate = if (days.isEmpty()) {
                        Alarms.oneShotDate(hour, minute, LocalDateTime.now())
                    } else {
                        null
                    },
                    enabled = existing?.enabled ?: true,
                    label = binding.etLabel.text.toString().trim(),
                    soundUri = sound,
                    skipHolidays = binding.swHoliday.isChecked,
                    snoozeMinutes = snooze
                )
            }
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
        // 空串的语义是「跟随设置页里的默认铃声」，不是静音 —— 文案不能撒谎
        binding.tvSound.text = if (uri.isBlank()) "默认（系统铃声）" else SoundPicker.nameOf(ctx, uri)
    }

    // ------------------------------------------------------------------
    //  小工具：chips
    // ------------------------------------------------------------------

    /**
     * 一行里的一个 chip。
     *
     * [weight] > 0 时**按权重平分这一行的宽度**，而不是各自 wrap_content。
     * 这不是审美问题：wrap_content 的 chip 在放不下时不会换行，只会被挤出屏幕 ——
     * 第一版周一到周日七个 chip 就是这样，第 7 个「日」直接看不见；
     * 贪睡那行的「30 分钟」也被挤成了两行（换行还会把整行高度顶起来）。
     *
     * 按权重分就永远不会溢出：屏幕再窄也只是七个各占 1/7、字号小一点。
     */
    private fun chip(
        text: String, selected: Boolean, weight: Float = 0f, onClick: () -> Unit
    ): TextView =
        TextView(ctx).apply {
            val gap = dp(if (weight > 0f) 4 else 6)
            layoutParams = if (weight > 0f) {
                // 宽度给 0 + weight：宽度由权重分配，而不是由文字长度决定
                LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
                ).apply { marginEnd = gap }
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
            }
            gravity = Gravity.CENTER
            setText(text)
            textSize = if (weight > 0f) 12.5f else 13f
            includeFontPadding = false
            // 宁可挤窄、宁可省略，也不许换行 —— 换行会把整行高度顶起来，看着像坏了
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val h = dp(if (weight > 0f) 6 else 11)
            setPadding(h, dp(6), h, dp(6))
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
