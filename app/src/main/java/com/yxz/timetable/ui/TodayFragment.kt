package com.yxz.timetable.ui

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.Course
import com.yxz.timetable.data.Kind
import com.yxz.timetable.data.Moment
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TemplateSet
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

    /**
     * 上一次画时间线时的「内容指纹」。
     *
     * 30 秒一次的刷新只为了让「还剩 N 分钟」和进度条走字，
     * 时间线本身（十几二十个 View）在这段时间里一个字都没变。
     * 指纹没变就跳过 removeAllViews + 重新 inflate ——
     * 省下的不是电量（页面本来就醒着），是无谓的掉帧。
     */
    private var lastTimelineKey: Int? = null

    /** 明天的起床时刻。点了「设闹钟」要用，所以存下来 */
    private var tomorrowWake: Int? = null

    /**
     * 正在显示的「这一格是什么」面板。
     *
     * 时间线每 30 秒会重画一次（只剩内容变了才真的重画）。重画时被点的那个
     * View 会被 removeAllViews 摘掉，面板却还挂在屏幕上 —— 它锚定的东西已经没了。
     * 所以重画之前先收起来，别留一张指着空白处的卡。
     */
    private var detailCard: Dialog? = null

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

        binding.btnSetAlarm.setOnClickListener { setWakeAlarm() }

        render()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) beginSession()
    }

    override fun onPause() {
        super.onPause()
        stopTicker()
        detailCard?.dismiss()
        detailCard = null
    }

    /**
     * 切页用的是 hide/show，Fragment 的生命周期不会重走一遍。
     * 从别的页切回来时在这里补一次刷新，并重新开始每分钟的走字。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            detailCard?.dismiss()
            detailCard = null
            stopTicker()
        } else {
            beginSession()
        }
    }

    private fun beginSession() {
        scrolledOnce = false
        lastTimelineKey = null      // 回来时无条件重画一次，防止数据在后台变过
        render()
        stopTicker()
        Handler(Looper.getMainLooper()).post(ticker)
    }

    private fun stopTicker() {
        Handler(Looper.getMainLooper()).removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Handler(Looper.getMainLooper()).removeCallbacks(ticker)
        detailCard?.dismiss()
        detailCard = null
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
        // 加上「有早八」得看日历，见该函数的注释。
        // names = 用户改过的日型名（v4）—— 改名不改行为，但显示要跟。
        val names = store.dayTypeNames()
        binding.tvHeaderDayType.text =
            TimelineEngine.dayTypeDisplay(today, week, courses, templates.config, names)

        // ---- 此刻 ----
        val cur = TimelineEngine.currentAt(moments, minute)
        if (cur != null) {
            binding.tvNowTitle.text = cur.title
            val place = if (cur.place.isNotBlank()) " · ${cur.place}" else ""
            val remain = (cur.end - minute).coerceAtLeast(0)
            binding.tvNowMeta.text =
                "${Slots.fmt(cur.start)}–${Slots.fmt(cur.end)}$place · 还剩 $remain 分钟"
            binding.nowProgress.progress =
                if (cur.duration > 0) ((minute - cur.start) * 100 / cur.duration).coerceIn(0, 100) else 0
        }

        val next = TimelineEngine.nextAfter(moments, minute)
        binding.tvNowNext.text =
            if (next != null) "接下来 ${Slots.fmt(next.start)} · ${next.title}"
            else "今天没有下一项了"

        // ---- 开关状态 ----
        binding.tvSwitchState.text = when {
            !store.enabled -> "已关闭"
            !Notifier.isAllowed(ctx) -> "缺少通知权限"
            store.snoozeUntil > System.currentTimeMillis() -> "暂停中，到点自动恢复"
            else -> "运行中"
        }

        // ---- 权限提示 ----
        val hints = mutableListOf<String>()
        if (store.enabled && !AlarmScheduler.canExact(ctx)) {
            hints += "精确闹钟未开启，切换可能延迟。到「设置」页一键开启。"
        }
        if (store.enabled && store.remindLead > 0 && !Notifier.isAllowed(ctx)) {
            hints += "课前提醒需要通知权限。"
        }
        binding.tvHint.visibility = if (hints.isEmpty()) View.GONE else View.VISIBLE
        binding.tvHint.text = hints.joinToString("\n")

        renderTomorrow(courses, templates)
        renderTimeline(moments, cur, today, week, courses, templates)
    }

    // ------------------------------------------------------------------
    //  明日预告
    // ------------------------------------------------------------------

    private fun renderTomorrow(courses: List<Course>, templates: TemplateSet) {
        val tomorrow = LocalDate.now().plusDays(1)
        val week = store.weekOf(tomorrow)
        val type = TimelineEngine.dayType(tomorrow, week, courses, templates.config)
        val template = TimelineEngine.template(type, templates)
        val moments = TimelineEngine.moments(tomorrow, week, courses, templates)

        binding.tvTomorrowHeader.text =
            "${weekdayCn(tomorrow.dayOfWeek.value)} · 第 $week 周 · " +
                    TimelineEngine.dayTypeDisplay(tomorrow, week, courses, templates.config, store.dayTypeNames())

        // ---- 起床时间 ----
        tomorrowWake = TimelineEngine.wakeMinute(template)
        if (tomorrowWake != null) {
            binding.rowWake.visibility = View.VISIBLE
            binding.tvWakeTime.text = Slots.fmt(tomorrowWake!!)
        } else {
            // 这套模板里没有上午睡眠段（比如夜班排法）—— 与其显示一个错的时间，不如不显示
            binding.rowWake.visibility = View.GONE
        }

        // 按钮上直接写「已经设成几点」：点完一键设闹钟就忘了设没设，是这类操作的
        // 老毛病。每次渲染都按闹钟列表重算，所以用户去闹钟页改／关掉之后，回到这里
        // 也能对得上，按钮不会撒谎。
        val wakeAlarm = Alarms.findWakeOnce(store.alarms(), tomorrow)
        binding.btnSetAlarm.text = if (wakeAlarm != null && wakeAlarm.enabled) {
            "⏰ ${wakeAlarm.timeText} · 改"
        } else {
            "⏰ 设闹钟"
        }

        // ---- 第一件事 ----
        val first = moments.firstOrNull { it.kind != Kind.SLEEP && it.start > 0 }
        binding.tvTomorrowFirst.text =
            if (first != null) "第一项 ${Slots.fmt(first.start)} · ${first.title}" else ""

        // ---- 明天的课 ----
        val tomorrowCourses = moments.filter { it.isCourse }
        if (tomorrowCourses.isEmpty()) {
            binding.tvTomorrowCourses.text = "明天没有课"
            binding.tvTomorrowCourses.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
        } else {
            binding.tvTomorrowCourses.text = "${tomorrowCourses.size} 节课：" +
                    tomorrowCourses.joinToString("、") { it.title }
            binding.tvTomorrowCourses.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_primary)
            )
        }

        // ---- 收尾 ----
        val last = moments.lastOrNull { it.kind != Kind.SLEEP && it.end < 1440 }
        binding.tvTomorrowTail.text =
            if (last != null) "最后 ${Slots.fmt(last.start)}–${Slots.fmt(last.end)} · ${last.title}" else ""
    }

    // ------------------------------------------------------------------
    //  明天的起床闹钟
    // ------------------------------------------------------------------

    /**
     * 把「明天几点起床」变成**本 App 的一条闹钟**。
     *
     * 这里以前是 `AlarmClock.ACTION_SET_ALARM`：跳出去、请系统时钟应用建一条。
     * 能响，但**响不响不由我们掌握** —— 那条闹钟不在本 App 的闹钟页里，
     * 改作息不会跟着变，卸载 App 之后它还留在别人家。现在它和课表卡片设的闹钟
     * 进同一个列表：同样的铃声、同样的「跳过下一次」、同样的节假日开关。
     *
     * 存的是**一次性**闹钟（[Alarms.forWakeOnce]）：A 型日 06:55、B 型日 07:25，
     * 一条闹钟要每周复用只能二选一；而用户按的是「明天」这个按钮，
     * 那就只管明天那一次，响过由 AlarmReceiver 自动关掉。
     *
     * 提前量沿用课表卡片那六档（[AlarmQuick.pickLead]）：用户已经在课表上
     * 学过这个选择器，同一个 App 里不该冒出第二种问法。
     */
    private fun setWakeAlarm() {
        val wake = tomorrowWake
        if (wake == null) {
            toast("这套作息模板里没有起床时间")
            return
        }
        val ctx = requireContext()
        val tomorrow = LocalDate.now().plusDays(1)
        val existing = Alarms.findWakeOnce(store.alarms(), tomorrow)
        // 设过就停在用户上次选的那一档，别每次都退回「到点」
        val currentLead = existing?.let { wake - (it.hour * 60 + it.minute) }?.coerceAtLeast(0)

        AlarmQuick.pickLead(
            ctx = ctx,
            title = "明天 ${Slots.fmt(wake)} 起床：什么时候响",
            lead = currentLead,
            // 起床要的是「到点就叫」，不是「提前 5 分钟提醒我快起床了」
            defaultLead = 0
        ) { minutes, _ ->
            store.saveAlarms(
                Alarms.withWakeOnce(
                    alarms = store.alarms(),
                    date = tomorrow,
                    wakeMinute = wake,
                    leadMinutes = minutes,
                    newId = store::nextAlarmId
                )
            )
            // 存完必须重排 —— 不排的话闹钟页上「下次：…」还停在旧时刻
            AlarmScheduler.scheduleAlarms(ctx)
            toast("已加入闹钟页：明天 ${Slots.fmt((wake - minutes).coerceAtLeast(0))}，响过自动关掉")
            render()
        }
    }

    // ------------------------------------------------------------------
    //  今日时间线
    // ------------------------------------------------------------------

    /**
     * 画时间线。**每一格都能点**：点开是这一格的信息卡（[MomentCard]）。
     *
     * 为什么每格都能点，而不是只有上课的格子能点：这条线上真正占大多数的是
     * 「午餐」「午睡」「空档 · 机动」这些格子，而「提醒我 12:00 去吃饭」
     * 恰恰是它们在时间线上的意义。只有课程格子能点的话，这个页面就只是
     * 课表的另一个视图了。
     */
    private fun renderTimeline(
        moments: List<Moment>,
        current: Moment?,
        date: LocalDate,
        week: Int,
        courses: List<Course>,
        templates: TemplateSet
    ) {
        // 内容指纹：时间线内容 + 当前是哪一格。任一变化才需要重画
        val key = moments.hashCode() * 31 + (current?.start ?: -1)
        if (key == lastTimelineKey) return
        lastTimelineKey = key

        // 重画会把被点的那个 View 摘掉，面板还开着就成了一张指着空白处的卡
        detailCard?.dismiss()
        detailCard = null

        val container = binding.timelineContainer
        container.removeAllViews()

        // 这一屏的日型只算一次：卡片上是「A 型日」还是「无课 · 休息日」，
        // 和页面顶部那行必须说同一件事
        val dayType = TimelineEngine.dayType(date, week, courses, templates.config)
        val dayTypeLabel = TimelineEngine.dayTypeDisplay(
            date, week, courses, templates.config, store.dayTypeNames()
        )

        val inflater = LayoutInflater.from(requireContext())
        var currentRow: View? = null

        for (m in moments) {
            val row = ItemTimelineBinding.inflate(inflater, container, false)

            row.tvTimeStart.text = Slots.fmt(m.start)
            row.tvTimeEnd.text = Slots.fmt(m.end)
            row.tvDuration.text = durationText(m.duration)
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
                row.tvTitle.setTextColor(requireContext().accentColor())
                currentRow = row.root
            }

            row.root.setOnClickListener { v ->
                detailCard?.dismiss()
                detailCard = MomentCard.show(
                    anchor = v,
                    moment = m,
                    date = date,
                    week = week,
                    // 课程格才有的东西（老师、周次）顺着 courseId 找回来
                    course = courses.firstOrNull { it.id == m.courseId },
                    dayType = dayType,
                    dayTypeLabel = dayTypeLabel
                )
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

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
