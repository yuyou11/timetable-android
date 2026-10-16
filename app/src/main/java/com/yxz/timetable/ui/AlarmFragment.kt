package com.yxz.timetable.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.Alarm
import com.yxz.timetable.data.AlarmSource
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.FragmentAlarmBinding
import com.yxz.timetable.notify.AlarmScheduler
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 闹钟页。
 *
 * 上半部分是那只钟（[ClockView]，只在页面可见时走），
 * 下半部分是闹钟列表：每条一行，左边时刻与说明，右边一个开关。
 *
 * 这一页也是「两种闹钟同框」的地方：
 *   · 手建闹钟显示时刻本身（06:30）
 *   · 课程闹钟显示「课前 N 分钟」—— 它的时刻每天不同，写死一个数字是假信息
 *
 * 列表用代码生成（条数随数据变），和课表网格、课程面板同一个判据。
 */
class AlarmFragment : Fragment() {

    private var _binding: FragmentAlarmBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: Store
    private var editor: AlarmEditor? = null

    /** 铃声选择器的回调：结果送回当前开着的那个编辑对话框 */
    private val soundPick = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = SoundPicker.uriFromResult(result.resultCode, result.data)
        if (uri != null) editor?.onSoundPicked(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlarmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = Store(requireContext())
        binding.btnAdd.setOnClickListener { openEditor(null) }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()                                  // 从别的页面改完课回来，课程闹钟的说明要跟着变
        AlarmScheduler.scheduleAlarms(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        SoundPicker.stop()
        editor = null
        _binding = null
    }

    // ==================================================================

    private fun render() {
        val alarms = store.alarms().sortedWith(
            compareBy({ it.source != AlarmSource.MANUAL }, { it.hour * 60 + it.minute }, { it.id })
        )
        binding.list.removeAllViews()
        for (a in alarms) binding.list.addView(row(a))
        binding.tvEmpty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNext.text = nextText(alarms)
        holidayHint()
    }

    /** 页面顶上那句「下次响铃」—— 整个页面最重要的一条信息 */
    private fun nextText(alarms: List<Alarm>): String {
        val next = Alarms.nextFire(
            alarms = alarms,
            from = LocalDateTime.now(),
            courses = store.courses(),
            templates = store.templates(),
            holidays = store.holidayTable(),
            weekOf = store::weekOf
        ) ?: return if (alarms.isEmpty()) "还没有闹钟" else "没有启用的闹钟"

        val whenText = "${weekdayCn(next.date.dayOfWeek.value)} " +
                "%02d:%02d".format(next.minuteOfDay / 60, next.minuteOfDay % 60)
        return "下次响铃：$whenText · ${Alarms.describe(next.alarm, store.courses())}"
    }

    /**
     * 节假日表没覆盖到的年份，明说一句。
     *
     * 2027 年的安排要到 2026 年 11 月才发布，那之前「智能识别节假日」在
     * 2027 年只能按周末判断 —— 不说清楚，用户会以为 App 算错了。
     */
    private fun holidayHint() {
        val needSkip = store.alarms().any { it.enabled && it.skipHolidays }
        if (!needSkip) {
            binding.tvHolidayHint.visibility = View.GONE
            return
        }
        val soon = LocalDate.now().plusMonths(2)
        val table = store.holidayTable()
        if (table.covers(soon.year)) {
            binding.tvHolidayHint.visibility = View.GONE
        } else {
            binding.tvHolidayHint.visibility = View.VISIBLE
            binding.tvHolidayHint.text =
                "${soon.year} 年的放假安排还没有内置，那段时间只按周末判断"
        }
    }

    /** 一行闹钟：左边时刻 + 说明，右边开关 */
    private fun row(a: Alarm): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(10), dp(12))
            alpha = if (a.enabled) 1f else 0.45f
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 课程闹钟没有固定时刻，写死一个数字是假信息 —— 显示提前量
        val headline =
            if (a.source == AlarmSource.COURSE) "课前 ${a.leadMinutes} 分钟" else a.timeText
        col.addView(
            TextView(ctx).apply {
                text = headline
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                includeFontPadding = false
            }
        )
        col.addView(label(ctx, sub(a), 12f, R.color.text_secondary, top = 3))
        col.addView(label(ctx, perAlarmNext(a), 11.5f, R.color.text_secondary, top = 2))
        card.addView(col)

        card.addView(
            Switch(ctx).apply {
                isChecked = a.enabled
                setOnCheckedChangeListener { _, checked ->
                    if (checked != a.enabled) toggle(a, checked)
                }
            }
        )
        card.setOnClickListener { openEditor(a) }
        return card
    }

    private fun sub(a: Alarm): String = when (a.source) {
        AlarmSource.MANUAL -> buildString {
            if (Alarms.isOneShot(a)) {
                // 只响一次：说清是哪一天，否则列表上只看得出「不重复」
                append("只响一次")
                a.onceDate?.let { append(" · ${it.monthValue} 月 ${it.dayOfMonth} 日") }
            } else {
                append(Alarms.daySummary(a.days))
            }
            if (a.label.isNotBlank()) append(" · ${a.label}")
            if (!a.skipHolidays) append(" · 不看节假日")
        }

        AlarmSource.COURSE -> {
            val c = store.courses().firstOrNull { it.id == a.courseId }
            if (c == null) "课程已删除"
            else "${c.name} · ${weekdayCn(c.dayOfWeek)}第 ${c.startNode}-${c.endNode} 节"
        }
    }

    /** 这一条下一次什么时候响。课程闹钟会自己跨过没课的那几周 */
    private fun perAlarmNext(a: Alarm): String {
        if (!a.enabled) return "已关闭"
        val one = Alarms.nextFire(
            alarms = listOf(a),
            from = LocalDateTime.now(),
            courses = store.courses(),
            templates = store.templates(),
            holidays = store.holidayTable(),
            weekOf = store::weekOf
        ) ?: return "接下来两周都不会响"
        return "下次：${weekdayCn(one.date.dayOfWeek.value)} " +
                "%02d:%02d".format(one.minuteOfDay / 60, one.minuteOfDay % 60)
    }

    private fun toggle(a: Alarm, enabled: Boolean) {
        val list = store.alarms()
        val at = list.indexOfFirst { it.id == a.id }
        if (at < 0) return
        list[at] = list[at].copy(enabled = enabled)
        store.saveAlarms(list)
        AlarmScheduler.scheduleAlarms(requireContext())
        render()
    }

    private fun openEditor(a: Alarm?) {
        val ctx = requireContext()
        editor = AlarmEditor(
            ctx = ctx,
            store = store,
            existing = a,
            courses = store.courses(),
            onSaved = { render() },
            pickSound = { current -> soundPick.launch(SoundPicker.intent(current)) }
        ).also { it.show() }
    }

    private fun label(
        ctx: android.content.Context, text: String, size: Float, colorRes: Int, top: Int
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(ContextCompat.getColor(ctx, colorRes))
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (top > 0) {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(top) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
