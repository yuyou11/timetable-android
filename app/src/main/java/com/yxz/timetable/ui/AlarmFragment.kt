package com.yxz.timetable.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.MainActivity
import com.yxz.timetable.R
import com.yxz.timetable.data.Alarm
import com.yxz.timetable.data.AlarmSource
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.FragmentAlarmBinding
import com.yxz.timetable.notify.AlarmRinger
import com.yxz.timetable.notify.AlarmScheduler
import com.yxz.timetable.notify.Notifier
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

    private val handler = Handler(Looper.getMainLooper())

    /** 响铃期间每秒看一眼（在别处停了就收起那一条），不响时完全不跑 */
    private val ringWatcher = object : Runnable {
        override fun run() {
            renderRinging()
        }
    }

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
        if (!isHidden) {
            // 先排程再画：scheduleAlarms 会顺手判定「上一次是不是该响没响」，
            // 画之前判定完，「错过」提示这一屏就能看到
            AlarmScheduler.scheduleAlarms(requireContext())
            render()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            // 页面藏起来了就别再每秒看一眼响铃状态
            handler.removeCallbacks(ringWatcher)
        } else {
            AlarmScheduler.scheduleAlarms(requireContext())
            render()
        }
    }

    /** 闹钟响时由 MainActivity 拉起（可能本来就在这一页，不会触发 onResume） */
    fun onAlarmLaunched() {
        if (_binding != null) render()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ringWatcher)   // 页面不在前台就别轮询了
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ringWatcher)
        SoundPicker.stop()
        editor = null
        _binding = null
    }

    // ==================================================================

    private fun render() {
        val alarms = store.alarms()
        // 按「下次真的什么时候响」排序，而不是按 hour*60+minute ——
        // 课程闹钟的 hour 恒为 0，旧写法会让它们按 id 顺序乱排。
        val sorted = alarms.map { it to nextOf(it) }
            .sortedWith(compareBy({ it.second == null }, { it.second?.at }, { it.first.id }))
            .map { it.first }

        binding.list.removeAllViews()
        for (a in sorted) binding.list.addView(row(a))
        binding.tvEmpty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNext.text = nextText(alarms)
        renderMissed()
        renderWarnings()
        renderLastRang()
        holidayHint()
        renderRinging()
    }

    /** 这条闹钟下一次什么时候响（没有就是 null） */
    private fun nextOf(a: Alarm) = Alarms.nextFire(
        alarms = listOf(a),
        from = LocalDateTime.now(),
        courses = store.courses(),
        templates = store.templates(),
        holidays = store.holidayTable(),
        weekOf = store::weekOf
    )

    /**
     * 「该响却没响」的提示。
     *
     * 判定发生在 [AlarmScheduler.scheduleAlarms]（它是所有路径都会经过的地方），
     * 这里只负责显示和「知道了」。
     */
    private fun renderMissed() {
        // 先补一次判定：如果是冷启动直接进闹钟页，Fragment 的 onResume 跑在
        // Activity 的 onResume 之前 —— 等不到 AlarmScheduler 那一轮判定。
        val expected = store.alarmExpectedAt
        if (expected in 1 until System.currentTimeMillis() - 5 * 60_000) {
            store.missedAlarmAt = expected
            store.missedAlarmLabel = store.alarmExpectedLabel
            store.alarmExpectedAt = 0L
            store.alarmExpectedLabel = ""
        }
        val missedAt = store.missedAlarmAt
        if (missedAt <= 0L) {
            binding.tvMissed.visibility = View.GONE
            return
        }
        val t = java.time.Instant.ofEpochMilli(missedAt)
            .atZone(java.time.ZoneId.systemDefault())
        val day = if (t.toLocalDate() == LocalDate.now()) ""
        else "${t.monthValue} 月 ${t.dayOfMonth} 日 "
        binding.tvMissed.visibility = View.VISIBLE
        binding.tvMissed.text = "错过：$day${"%02d:%02d".format(t.hour, t.minute)} 的" +
                "「${store.missedAlarmLabel}」没有响。点此忽略。"
        binding.tvMissed.setOnClickListener {
            store.missedAlarmAt = 0L
            store.missedAlarmLabel = ""
            renderMissed()
        }
    }

    /**
     * 会影响响铃的设置问题。两件事都只能提示、改不了：
     * 通知权限决定「有没有停止按钮」，闹钟音量决定「听不听得见」。
     */
    private fun renderWarnings() {
        val ctx = requireContext()
        // 一条启用的闹钟都没有时，这些提示只是噪音
        if (store.alarms().none { it.enabled }) {
            binding.tvWarning.visibility = View.GONE
            binding.tvWarning.setOnClickListener(null)
            return
        }
        val noPermission = !Notifier.isAllowed(ctx)
        val volume = ctx.getSystemService(AudioManager::class.java)
            ?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 1
        val lines = buildList {
            if (noPermission) add("通知权限未开：响铃时没有停止 / 贪睡按钮")
            if (volume == 0) add("闹钟音量是 0：响铃可能完全听不到")
        }
        if (lines.isEmpty()) {
            binding.tvWarning.visibility = View.GONE
            binding.tvWarning.setOnClickListener(null)
            return
        }
        binding.tvWarning.visibility = View.VISIBLE
        binding.tvWarning.text = lines.joinToString("\n")
        binding.tvWarning.setOnClickListener {
            if (noPermission) {
                PermissionGuide(this).openNotificationSettings()
            } else {
                runCatching { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            }
        }
    }

    /** 上次响铃：自动停之后也知道它响过，而不是什么都不留 */
    private fun renderLastRang() {
        val at = store.lastRangAt
        if (at <= 0L || System.currentTimeMillis() - at > 24 * 60 * 60 * 1000L) {
            binding.tvLastRang.visibility = View.GONE
            return
        }
        val t = java.time.Instant.ofEpochMilli(at)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        binding.tvLastRang.visibility = View.VISIBLE
        binding.tvLastRang.text =
            "上次响铃 ${"%02d:%02d".format(t.hour, t.minute)} · ${store.lastRangLabel}"
    }

    /**
     * 长按一行 → 「跳过下一次」。
     *
     * 不塞进编辑对话框：那里已经有保存 / 取消 / 删除三个按钮，
     * 再加就挤了；而「跳过一次」是临时动作，长按更顺手。
     */
    private fun showSkipDialog(a: Alarm) {
        if (!a.enabled) {
            toast("请先打开这条闹钟")
            return
        }
        val next = nextOf(a)
        if (next == null) {
            toast("这条闹钟接下来两周都不会响")
            return
        }
        val date = next.date
        AlertDialog.Builder(requireContext())
            .setTitle("跳过下一次")
            .setMessage(
                "${date.monthValue} 月 ${date.dayOfMonth} 日" +
                        "（${weekdayCn(date.dayOfWeek.value)}）这一次不响，之后照常。"
            )
            .setPositiveButton("跳过这一次") { _, _ ->
                val list = store.alarms()
                val i = list.indexOfFirst { it.id == a.id }
                if (i >= 0) {
                    list[i] = list[i].copy(skipDate = date)
                    store.saveAlarms(list)
                    AlarmScheduler.scheduleAlarms(requireContext())
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 「正在响铃」那一条：**只要进程里还在响就显示**，附带停止与贪睡。
     *
     * 这是「声音关不掉」那个 bug 的最后一道保险。响铃的四条出口里，
     * 通知那条可能被系统权限挡掉、全屏界面在亮屏时不会抢前台 ——
     * 但只要你打开 App，这一条一定在，一定停得掉。
     *
     * 每秒看一眼：在别处（通知按钮 / 响铃界面）停掉了，这一条要跟着消失。
     */
    private fun renderRinging() {
        val ringing = AlarmRinger.isRinging()
        binding.rowRinging.visibility = if (ringing) View.VISIBLE else View.GONE
        if (!ringing) {
            handler.removeCallbacks(ringWatcher)
            return
        }

        val id = AlarmRinger.currentIds().firstOrNull()
        val alarm = store.alarms().firstOrNull { it.id == id }
        binding.tvRinging.text = "⏰ 正在响铃：" +
                (alarm?.let { Alarms.describe(it, store.courses()) } ?: "")
        binding.btnRingSnooze.text = "贪睡 ${AlarmRinger.currentSnoozeMinutes()} 分钟"
        binding.btnRingStop.setOnClickListener {
            AlarmRinger.stop(requireContext())
            MainActivity.clearKeepScreenOn(requireActivity())   // 不响了就别再让屏幕亮着
            renderRinging()
            render()
        }
        binding.btnRingSnooze.setOnClickListener { snoozeFromApp() }

        handler.removeCallbacks(ringWatcher)
        handler.postDelayed(ringWatcher, 1000L)
    }

    /** 和通知按钮、响铃界面走同一套贪睡逻辑：记下贪睡哪一批、到几点，然后重排 */
    private fun snoozeFromApp() {
        val ctx = requireContext()
        val ids = AlarmRinger.currentIds()
        if (ids.isNotEmpty()) {
            store.snoozeAlarmIds = ids
            store.snoozeAlarmUntil = System.currentTimeMillis() +
                    AlarmRinger.currentSnoozeMinutes() * 60_000L
            AlarmScheduler.scheduleAlarms(ctx)
        }
        AlarmRinger.stop(ctx)
        MainActivity.clearKeepScreenOn(requireActivity())
        renderRinging()
        render()
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
        ) ?: return if (alarms.isEmpty()) "暂无闹钟" else "没有启用的闹钟"

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
                "${soon.year} 年放假安排未内置，暂按周末判断"
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
            setPadding(dp(16), dp(14), dp(12), dp(14))
            alpha = if (a.enabled) 1f else 0.45f
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 课程闹钟和「按格子响」的闹钟都没有固定时刻，写死一个数字是假信息 ——
        // 显示提前量。0 秒档说的是「到点提醒」，不是「提前 0 分钟」
        val headline = when (a.source) {
            AlarmSource.COURSE -> "课前 ${a.leadMinutes} 分钟"
            AlarmSource.MOMENT -> a.leadText
            AlarmSource.MANUAL -> a.timeText
        }
        col.addView(
            TextView(ctx).apply {
                text = headline
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                includeFontPadding = false
            }
        )
        col.addView(label(ctx, sub(a), 11.5f, R.color.text_secondary, top = 4))
        col.addView(label(ctx, perAlarmNext(a), 11f, R.color.text_secondary, top = 2))
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
        card.setOnLongClickListener {
            showSkipDialog(a)
            true
        }
        return card
    }

    private fun sub(a: Alarm): String = when (a.source) {
        AlarmSource.MANUAL -> buildString {
            when {
                Alarms.isExpiredOneShot(a, LocalDate.now()) -> append("只响一次 · 已过期")
                Alarms.isOneShot(a) -> {
                    // 只响一次：说清是哪一天，否则列表上只看得出「不重复」
                    append("只响一次")
                    a.onceDate?.let { append(" · ${it.monthValue} 月 ${it.dayOfMonth} 日") }
                }
                else -> append(Alarms.daySummary(a.days))
            }
            // 只显示还没过去的「跳过」—— 过期的不该继续占着一行字
            a.skipDate?.takeIf { !it.isBefore(LocalDate.now()) }?.let {
                append(" · 已跳过 ${it.monthValue}/${it.dayOfMonth}")
            }
            if (a.label.isNotBlank()) append(" · ${a.label}")
            if (!a.skipHolidays) append(" · 不看节假日")
        }

        AlarmSource.COURSE -> {
            val c = store.courses().firstOrNull { it.id == a.courseId }
            if (c == null) "课程已删除"
            else "${c.name} · ${weekdayCn(c.dayOfWeek)}第 ${c.startNode}-${c.endNode} 节"
        }

        // 绑时间轴上的某一格：说清「哪几天 + 是哪一格」。
        // 时刻由当天的模板决定，所以这里写不出一个固定的钟点
        AlarmSource.MOMENT -> buildString {
            append(Alarms.momentScope(a, store.dayTypeNames()))
            append(" · ").append(a.momentTitle)
            // 模板被换掉、这一格改了名字 —— 说出来，别让用户以为它还开着就有保险
            if (Alarms.isLostMoment(a, store.courses(), store.templates(), store::weekOf)) {
                append(" · 这一格已不在时间轴上")
            }
            a.skipDate?.takeIf { !it.isBefore(LocalDate.now()) }?.let {
                append(" · 已跳过 ${it.monthValue}/${it.dayOfMonth}")
            }
            if (!a.skipHolidays) append(" · 不看节假日")
        }
    }

    /** 这一条下一次什么时候响。课程闹钟会自己跨过没课的那几周 */
    private fun perAlarmNext(a: Alarm): String {
        if (!a.enabled) return "已关闭"
        if (Alarms.isExpiredOneShot(a, LocalDate.now())) return "已过期，不会再响"
        val one = nextOf(a) ?: return "两周内不会响"
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

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
