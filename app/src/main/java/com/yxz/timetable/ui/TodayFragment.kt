package com.yxz.timetable.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.Kind
import com.yxz.timetable.data.Moment
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TimelineEngine
import com.yxz.timetable.databinding.FragmentTodayBinding
import com.yxz.timetable.databinding.ItemTimelineBinding
import com.yxz.timetable.notify.AlarmScheduler
import com.yxz.timetable.notify.Notifier
import java.time.LocalDate
import java.time.LocalDateTime

class TodayFragment : Fragment() {

    private var _binding: FragmentTodayBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: Store
    private var suppressSwitch = false
    private var scrolledOnce = false

    /** 明天的起床时刻。点了「设闹钟」要用，所以存下来 */
    private var tomorrowWake: Int? = null

    /**
     * 界面上那个「还剩 23 分钟」需要每分钟变一次。
     *
     * 这里用 30 秒的轮询，看起来和我在通知模块里极力避免的做法一样 ——
     * 但两者性质完全不同：
     *
     *   通知栏的轮询 → 要求 App 7×24 常驻后台，代价极大
     *   这里的轮询   → 只在**这个界面可见时**才跑，切走就停（见 onPause）
     *
     * 判断标准不是「能不能轮询」，而是「App 不在前台时还要不要跑」。
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (_binding == null) return
            render()
            Handler(Looper.getMainLooper()).postDelayed(this, 30_000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTodayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = Store(requireContext())

        suppressSwitch = true
        binding.swEnabled.isChecked = store.enabled
        suppressSwitch = false

        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            store.enabled = checked
            if (checked) {
                store.snoozeUntil = 0L
                Notifier.update(requireContext())
                AlarmScheduler.schedule(requireContext())
            } else {
                AlarmScheduler.cancel(requireContext())
                Notifier.cancel(requireContext())
            }
            render()
        }

        binding.btnSetAlarm.setOnClickListener { setSystemAlarm() }

        render()
    }

    override fun onResume() {
        super.onResume()
        scrolledOnce = false
        Handler(Looper.getMainLooper()).post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Handler(Looper.getMainLooper()).removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Handler(Looper.getMainLooper()).removeCallbacks(ticker)
        _binding = null
    }

    // ==================================================================
    //  渲染
    // ==================================================================

    private fun render() {
        val ctx = context ?: return
        val today = LocalDate.now()
        val week = store.weekOf(today)
        val courses = store.courses()
        val templates = store.templates()
        val moments = TimelineEngine.moments(today, week, courses, templates)
        val now = LocalDateTime.now()
        val minute = now.hour * 60 + now.minute

        // ---- 头部 ----
        binding.tvHeaderDate.text =
            "${today.year} 年 ${today.monthValue} 月 ${today.dayOfMonth} 日 · " +
                    "${weekdayCn(today.dayOfWeek.value)} · 第 $week 教学周"
        // 用 dayTypeDisplay 而不是 .label：后者只说模板名，
        // 加上「有早八」得看日历，见该函数的注释
        binding.tvHeaderDayType.text =
            TimelineEngine.dayTypeDisplay(today, week, courses, templates.policy)

        // ---- 此刻 ----
        val cur = TimelineEngine.currentAt(moments, minute)
        if (cur != null) {
            binding.tvNowTitle.text = cur.title
            val place = if (cur.place.isNotBlank()) " · ${cur.place}" else ""
            val remain = (cur.end - minute).coerceAtLeast(0)
            binding.tvNowMeta.text =
                "${Slots.fmt(cur.start)}–${Slots.fmt(cur.end)}$place · ${formatDuration(cur.duration)}" +
                        " · 还剩 $remain 分钟"
            binding.nowProgress.progress =
                if (cur.duration > 0) ((minute - cur.start) * 100 / cur.duration).coerceIn(0, 100) else 0
        }

        val next = TimelineEngine.nextAfter(moments, minute)
        binding.tvNowNext.text =
            if (next != null) "接着　${Slots.fmt(next.start)}　${next.title}"
            else "今天没有下一项了"

        // ---- 开关状态 ----
        binding.tvSwitchState.text = when {
            !store.enabled -> "已关闭 · App 不会消耗任何电量"
            !Notifier.isAllowed(ctx) -> "缺少通知权限，通知不会显示"
            store.snoozeUntil > System.currentTimeMillis() -> "暂停中 · 到点自动恢复"
            else -> "运行中 · 通知栏随时显示此刻该做什么"
        }

        // ---- 权限提示 ----
        val hints = mutableListOf<String>()
        if (store.enabled && !AlarmScheduler.canExact(ctx)) {
            hints += "⚠️ 精确闹钟未开启，通知可能延迟十几分钟才切换。到「设置」页一键开启。"
        }
        if (store.enabled && store.remindLead > 0 && !Notifier.isAllowed(ctx)) {
            hints += "⚠️ 课前提醒需要通知权限。"
        }
        binding.tvHint.visibility = if (hints.isEmpty()) View.GONE else View.VISIBLE
        binding.tvHint.text = hints.joinToString("\n")

        renderTomorrow(courses, templates)
        renderTimeline(moments, cur)
    }

    // ------------------------------------------------------------------
    //  明日预告
    // ------------------------------------------------------------------

    private fun renderTomorrow(courses: List<com.yxz.timetable.data.Course>, templates: com.yxz.timetable.data.TemplateSet) {
        val tomorrow = LocalDate.now().plusDays(1)
        val week = store.weekOf(tomorrow)
        val type = TimelineEngine.dayType(tomorrow, week, courses, templates.policy)
        val template = TimelineEngine.template(type, templates)
        val moments = TimelineEngine.moments(tomorrow, week, courses, templates)

        binding.tvTomorrowHeader.text =
            "${weekdayCn(tomorrow.dayOfWeek.value)} · 第 $week 周 · " +
                    TimelineEngine.dayTypeDisplay(tomorrow, week, courses, templates.policy)

        // ---- 起床时间 ----
        tomorrowWake = TimelineEngine.wakeMinute(template)
        if (tomorrowWake != null) {
            binding.rowWake.visibility = View.VISIBLE
            binding.tvWakeTime.text = Slots.fmt(tomorrowWake!!)
        } else {
            // 这套模板里没有上午睡眠段（比如夜班排法）—— 与其显示一个错的时间，不如不显示
            binding.rowWake.visibility = View.GONE
        }

        // ---- 第一件事 ----
        val first = moments.firstOrNull { it.kind != Kind.SLEEP && it.start > 0 }
        binding.tvTomorrowFirst.text =
            if (first != null) "接着　${Slots.fmt(first.start)}　${first.title}" else ""

        // ---- 明天的课 ----
        val tomorrowCourses = moments.filter { it.isCourse }
        if (tomorrowCourses.isEmpty()) {
            binding.tvTomorrowCourses.text = "明天没有课"
            binding.tvTomorrowCourses.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
        } else {
            binding.tvTomorrowCourses.text = "有 ${tomorrowCourses.size} 节课：" +
                    tomorrowCourses.joinToString("、") { it.title }
            binding.tvTomorrowCourses.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_primary)
            )
        }

        // ---- 收尾 ----
        val last = moments.lastOrNull { it.kind != Kind.SLEEP && it.end < 1440 }
        binding.tvTomorrowTail.text =
            if (last != null) "最后一项　${Slots.fmt(last.start)}–${Slots.fmt(last.end)}　${last.title}" else ""
    }

    // ------------------------------------------------------------------
    //  一键设置系统闹钟
    // ------------------------------------------------------------------

    /**
     * 调用系统时钟的「设置闹钟」能力。
     *
     * 这是让别的 App 帮我们做事的标准做法：**发出一个约定好的 Intent，
     * 由系统或其它应用去处理**。好处是我们完全不用碰闹钟的实现，
     * 也不用申请任何权限 —— 只是请时钟应用帮个忙。
     *
     * 两个容易踩的点：
     *
     * 1. **Android 11 起必须声明包可见性**（见 AndroidManifest 里的 `<queries>`）。
     *    不声明的话系统会装作没有应用能处理这个 Intent，表现为「点了没反应」。
     *
     * 2. **`EXTRA_SKIP_UI` 要小心用。** 设成 true 表示「别弹确认界面，直接建好」。
     *    确实一键，但如果用户的时钟应用不支持这个参数，行为不可预测。
     *    这里的做法是：先试 skipUI，失败就退回到带确认界面的版本 ——
     *    **降级总比点了没反应好。**
     */
    private fun setSystemAlarm() {
        val wake = tomorrowWake
        if (wake == null) {
            toast("这套作息模板里没有起床时间")
            return
        }
        val ctx = requireContext()
        val hour = wake / 60
        val minute = wake % 60

        val base = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "起床 · 时间规划表")
            // 只在周一至周五重复是错的：A/B 型日的起床时间不同，
            // 让用户自己在时钟应用里决定要不要重复，比替他猜更靠谱。
        }

        val direct = Intent(base).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val ok = runCatching { startActivity(direct) }.isSuccess
        if (ok) {
            toast("已设置 ${Slots.fmt(wake)} 的闹钟")
            return
        }

        // 降级：打开时钟应用并填好时间，让用户自己按确认
        val fallback = runCatching { startActivity(base) }.isSuccess
        if (fallback) return

        // 再降级：有些机型根本没装时钟应用
        toast("没有找到可以设置闹钟的应用")
    }

    // ------------------------------------------------------------------
    //  今日时间线
    // ------------------------------------------------------------------

    private fun renderTimeline(moments: List<Moment>, current: Moment?) {
        val container = binding.timelineContainer
        container.removeAllViews()

        val inflater = LayoutInflater.from(requireContext())
        var currentRow: View? = null

        for (m in moments) {
            val row = ItemTimelineBinding.inflate(inflater, container, false)

            row.tvTimeStart.text = Slots.fmt(m.start)
            row.tvTimeEnd.text = Slots.fmt(m.end)
            row.tvDuration.text = formatDuration(m.duration)
            row.tvTitle.text = m.title
            row.colorBar.setBackgroundColor(kindColor(m.kind))

            if (m.note.isNotBlank()) {
                row.tvNote.visibility = View.VISIBLE
                row.tvNote.text = m.note
            } else {
                row.tvNote.visibility = View.GONE
            }

            if (m.place.isNotBlank()) {
                row.tvPlace.visibility = View.VISIBLE
                row.tvPlace.text = m.place
            } else {
                row.tvPlace.visibility = View.GONE
            }

            val isNow = current != null && m.start == current.start && m.end == current.end
            if (isNow) {
                row.root.setBackgroundResource(R.drawable.bg_row_current)
                row.tvTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
                currentRow = row.root
            }

            container.addView(row.root)
        }

        // 首次显示时把当前行滚到屏幕中间附近，省得每次打开都要手动找
        if (!scrolledOnce && currentRow != null) {
            scrolledOnce = true
            val target = currentRow
            binding.scroll.post {
                binding.scroll.smoothScrollTo(0, (target.top - 200).coerceAtLeast(0))
            }
        }
    }

    /**
     * 把分钟数写成人话：45 -> 「45 分」，95 -> 「1 时 35 分」，120 -> 「2 时」。
     *
     * 为什么不用「1h35m」这种紧凑写法？因为这是给中文用户看的日常界面，
     * 「1 时 35 分」读起来不需要在脑子里做一次翻译。
     */
    private fun formatDuration(minutes: Int): String {
        if (minutes <= 0) return ""
        if (minutes < 60) return "$minutes 分"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h 时" else "$h 时 $m 分"
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
