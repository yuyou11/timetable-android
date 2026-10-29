package com.yxz.timetable.ui

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.Course
import com.yxz.timetable.data.DayType
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
import com.yxz.timetable.ui.persona.PersonaTodayState
import com.yxz.timetable.ui.persona.PersonaTodayView
import com.yxz.timetable.ui.persona.PersonaTomorrow
import java.time.LocalDate
import java.time.LocalDateTime

class TodayFragment : Fragment() {

    private var _binding: FragmentTodayBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: Store
    private var suppressSwitch = false

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

    /** P3 彩蛋打开时才有值（整屏自绘的那一页）；默认外观下是 null。见 [showPersona3] */
    private var p3: PersonaTodayView? = null

    /** 这一屏渲染出来的「今天」的几个事实。
     *
     * P3 外观的时间线点击要用它们弹信息卡（哪一天、第几周、什么日型）——
     * 回调触发时再去重算一遍的话，跨零点那几秒里算出来的可能是「另一天」。
     */
    private var todayDate: LocalDate = LocalDate.now()
    private var todayWeek = 1
    private var dayTypeNow: DayType = DayType.B_NORMAL
    private var dayTypeLabelNow = ""
    private var coursesNow: List<Course> = emptyList()

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

        // 下内边距清掉：紧接着的就是下半页那块深蓝（blueSection），
        // 隔断线要贴着「此刻」卡的下沿，不能先漏一条浅色带出来。
        val pad = resources.getDimensionPixelSize(R.dimen.page_padding)
        binding.content.setPadding(pad, pad, pad, 0)

        suppressSwitch = true
        binding.swEnabled.isChecked = store.enabled
        suppressSwitch = false

        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            setEnabled(checked)
        }

        binding.btnSetAlarm.setOnClickListener { setWakeAlarm() }

        // 两套外观二选一：默认是原长页，P3 模式下换成整屏自绘（见 PersonaTodayView）。
        // 后者没有常驻开关那张卡、没有「明天」那张卡，但**功能一个不少**：
        // 开关收成右上角胶囊，明天卡在纸的底部。
        if (store.p3Mode) showPersona3()

        render()
    }

    /**
     * 开 / 关常驻通知。两个外观共用这一条路径 ——
     * 一份逻辑写在两处的话，迟早有一边忘了重排闹钟。
     */
    private fun setEnabled(on: Boolean) {
        store.enabled = on
        if (on) {
            store.snoozeUntil = 0L
            Notifier.update(requireContext())
            AlarmScheduler.schedule(requireContext())
        } else {
            AlarmScheduler.cancel(requireContext())
            Notifier.cancel(requireContext())
        }
        // 常驻开关是用户改过的状态，隐藏的 Switch 也要跟上，否则下次切回默认外观
        // 会看到开关还停在旧位置
        suppressSwitch = true
        binding.swEnabled.isChecked = on
        suppressSwitch = false
        render()
    }

    /**
     * 换上 P3 外观（`p3web/today-p3-v3.html` 那一版整页重画）。
     *
     * 只换「怎么画」：日期、教学周、此刻、明天、闹钟都还走下面那套方法 ——
     * 自绘 View 通过回调把开关、点格子、设闹钟抛回来，和默认外观走同一条路径。
     * 结构和 [WeekFragment.showPersona3]、[CalendarFragment] 一致。
     */
    private fun showPersona3() {
        val view = PersonaTodayView(requireContext())
        binding.scroll.visibility = View.GONE
        binding.p3Host.visibility = View.VISIBLE
        binding.p3Host.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        view.onToggleEnabled = { setEnabled(!store.enabled) }
        view.onWakeAlarm = { setWakeAlarm() }
        view.onMomentClick = { moment, pivot ->
            detailCard?.dismiss()
            detailCard = MomentCard.show(
                anchor = view,
                moment = moment,
                date = todayDate,
                week = todayWeek,
                // 课程格才有的东西（老师、周次）顺着 courseId 找回来
                course = coursesNow.firstOrNull { it.id == moment.courseId },
                dayType = dayTypeNow,
                dayTypeLabel = dayTypeLabelNow,
                // 自绘页没有「每格一个 View」，动画圆心是它按屏幕坐标算好给的
                pivot = pivot
            )
        }
        p3 = view
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
        // p3 这个 View 挂在 p3Host 上，Fragment 的 View 销毁后必须一并丢掉，
        // 否则 fragment 实例还活着时会一直攥着一个已经没人用的 View
        p3 = null
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

        todayDate = today
        todayWeek = week
        coursesNow = courses
        dayTypeNow = TimelineEngine.dayType(today, week, courses, templates.config)
        // 用 dayTypeDisplay 而不是 .label：后者只说模板名，
        // 加上「有早八」得看日历，见该函数的注释。
        // names = 用户改过的日型名（v4）—— 改名不改行为，但显示要跟。
        dayTypeLabelNow = TimelineEngine.dayTypeDisplay(
            today, week, courses, templates.config, store.dayTypeNames()
        )

        val tomorrow = buildTomorrow(courses, templates)
        // 设闹钟要用的是「明天几点起」，两套外观都得先把它记下来 ——
        // 记在写控件的那一侧（applyTomorrow）的话，P3 外观下这个值永远是 null
        tomorrowWake = tomorrow.wake

        // 开关状态 / 权限提示：两个外观都要用（P3 那页把它们画进胶囊下面的小字）
        val stateText = when {
            !store.enabled -> "已关闭"
            !Notifier.isAllowed(ctx) -> "缺少通知权限"
            store.snoozeUntil > System.currentTimeMillis() -> "暂停中，到点自动恢复"
            else -> "运行中"
        }
        val hints = mutableListOf<String>()
        if (store.enabled && !AlarmScheduler.canExact(ctx)) {
            hints += "精确闹钟未开启，切换可能延迟。到「设置」页一键开启。"
        }
        if (store.enabled && store.remindLead > 0 && !Notifier.isAllowed(ctx)) {
            hints += "课前提醒需要通知权限。"
        }

        // 课间（两节课之间的短空档）单独处理：这时候「此刻」本身没什么信息量，
        // 用户真正要的是「下一节课在哪间教室」。
        val breakNext = TimelineEngine.breakNextCourse(moments, minute)
        val cur = TimelineEngine.currentAt(moments, minute)

        val persona = p3
        if (persona != null) {
            persona.submit(
                PersonaTodayState(
                    date = today,
                    week = week,
                    minute = minute,
                    dayType = dayTypeLabelNow,
                    moments = moments,
                    enabled = store.enabled,
                    // 状态本身由胶囊表达；这里只放「需要用户做点什么」的提示
                    note = hints.firstOrNull(),
                    tomorrow = tomorrow.toPersona()
                )
            )
            return
        }

        // ---- 头部 ----
        binding.tvHeaderDate.text =
            "${today.year} 年 ${today.monthValue} 月 ${today.dayOfMonth} 日 · " +
                    "${weekdayCn(today.dayOfWeek.value)} · 第 $week 教学周"
        binding.tvHeaderDayType.text = dayTypeLabelNow

        // ---- 此刻 ----
        if (cur != null) {
            binding.tvNowTitle.text = if (breakNext != null) "课间" else cur.title
            val place = if (cur.place.isNotBlank()) " · ${cur.place}" else ""
            val remain = (cur.end - minute).coerceAtLeast(0)
            binding.tvNowMeta.text =
                "${Slots.fmt(cur.start)}–${Slots.fmt(cur.end)}$place · 还剩 $remain 分钟"
            binding.nowProgress.progress =
                if (cur.duration > 0) ((minute - cur.start) * 100 / cur.duration).coerceIn(0, 100) else 0
        }

        val next = TimelineEngine.nextAfter(moments, minute)
        binding.tvNowNext.text = when {
            breakNext != null -> buildString {
                append("下一节 ").append(Slots.fmt(breakNext.start)).append(" · ").append(breakNext.title)
                if (breakNext.place.isNotBlank()) append(" · ").append(breakNext.place)
            }

            next != null -> "接下来 ${Slots.fmt(next.start)} · ${next.title}"
            else -> "今天没有下一项了"
        }

        // ---- 开关状态 ----
        binding.tvSwitchState.text = stateText

        // ---- 权限提示 ----
        binding.tvHint.visibility = if (hints.isEmpty()) View.GONE else View.VISIBLE
        binding.tvHint.text = hints.joinToString("\n")

        applyTomorrow(tomorrow)
        renderTimeline(moments, cur, today, week, courses, templates)
    }

    // ------------------------------------------------------------------
    //  明天的一格
    // ------------------------------------------------------------------

    /**
     * 明天那一格里要显示的**全部**东西。
     *
     * 先算成这个对象，再分别喂给两套外观 —— 默认外观写进那几个 TextView，
     * P3 外观交给 [PersonaTomorrow]。两个外观各算一遍的话，卡片上写「A 型日」
     * 而按钮按 B 型排这种错就不可避免。
     */
    private class TomorrowInfo(
        val date: LocalDate,
        val week: Int,
        val header: String,
        val wake: Int?,
        val first: String,
        val courseCount: Int,
        val courseText: String,
        val tail: String,
        val dayTypeLabel: String,
        /** 起床闹钟已经设成了几点（「06:55」）；null = 还没设 */
        val alarmTime: String?
    ) {
        fun toPersona() = PersonaTomorrow(
            date = date,
            week = week,
            wakeMinute = wake,
            dayType = dayTypeLabel,
            courseCount = courseCount,
            firstItem = first.ifBlank { null },
            alarmText = alarmTime
        )
    }

    private fun buildTomorrow(courses: List<Course>, templates: TemplateSet): TomorrowInfo {
        val tomorrow = LocalDate.now().plusDays(1)
        val week = store.weekOf(tomorrow)
        val type = TimelineEngine.dayType(tomorrow, week, courses, templates.config)
        val template = TimelineEngine.template(type, templates)
        val moments = TimelineEngine.moments(tomorrow, week, courses, templates)
        val label = TimelineEngine.dayTypeDisplay(
            tomorrow, week, courses, templates.config, store.dayTypeNames()
        )

        val wake = TimelineEngine.wakeMinute(template)

        // 第一件事 / 最后一件 / 明天的课
        val first = moments.firstOrNull { it.kind != Kind.SLEEP && it.start > 0 }
        val last = moments.lastOrNull { it.kind != Kind.SLEEP && it.end < 1440 }
        val tomorrowCourses = moments.filter { it.isCourse }

        // 按钮上直接写「已经设成几点」：点完一键设闹钟就忘了设没设，是这类操作的
        // 老毛病。每次渲染都按闹钟列表重算，所以用户去闹钟页改／关掉之后，回到这里
        // 也能对得上，按钮不会撒谎。
        val wakeAlarm = Alarms.findWakeOnce(store.alarms(), tomorrow)

        return TomorrowInfo(
            date = tomorrow,
            week = week,
            header = "${weekdayCn(tomorrow.dayOfWeek.value)} · 第 $week 周 · $label",
            wake = wake,
            first = if (first != null) "第一项 ${Slots.fmt(first.start)} · ${first.title}" else "",
            courseCount = tomorrowCourses.size,
            courseText = if (tomorrowCourses.isEmpty()) {
                "明天没有课"
            } else {
                "${tomorrowCourses.size} 节课：" + tomorrowCourses.joinToString("、") { it.title }
            },
            tail = if (last != null) {
                "最后 ${Slots.fmt(last.start)}–${Slots.fmt(last.end)} · ${last.title}"
            } else {
                ""
            },
            dayTypeLabel = label,
            alarmTime = wakeAlarm?.takeIf { it.enabled }?.timeText
        )
    }

    /** 默认外观的「明天」卡 */
    private fun applyTomorrow(t: TomorrowInfo) {
        binding.tvTomorrowHeader.text = t.header

        // ---- 起床时间 ----
        if (t.wake != null) {
            binding.rowWake.visibility = View.VISIBLE
            binding.tvWakeTime.text = Slots.fmt(t.wake)
        } else {
            // 这套模板里没有上午睡眠段（比如夜班排法）—— 与其显示一个错的时间，不如不显示
            binding.rowWake.visibility = View.GONE
        }

        binding.btnSetAlarm.text = t.alarmTime?.let { "⏰ $it · 改" } ?: "⏰ 设闹钟"
        binding.tvTomorrowFirst.text = t.first
        binding.tvTomorrowCourses.text = t.courseText
        binding.tvTomorrowCourses.setTextColor(
            if (t.courseCount > 0) ContextCompat.getColor(requireContext(), R.color.text_primary)
            else ContextCompat.getColor(requireContext(), R.color.text_secondary)
        )
        binding.tvTomorrowTail.text = t.tail
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

        // 这里以前有一段「首次显示时把当前行滚到屏幕中间附近」的逻辑。
        // 去掉了：每次进 App 都发现页面不在最顶上、还得手动滑回去，
        // 比「自己找当前那节课」更烦。当前那节课仍然高亮（见上面 isNow 分支），
        // 只是不再替你动滚动位置。
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
