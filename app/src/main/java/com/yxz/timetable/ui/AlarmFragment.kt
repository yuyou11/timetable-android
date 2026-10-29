package com.yxz.timetable.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
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
import com.yxz.timetable.ui.persona.PersonaAlarmPalette
import com.yxz.timetable.ui.persona.PersonaAlarmStyle
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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

    /**
     * 每秒看一眼响铃状态。
     *
     * ⚠️ **不响的时候也要接着跑**。以前这里是「不响就 removeCallbacks」，
     * 结果留下一个很难复现的 bug：App 已经在前台、用户就停在闹钟页时，
     * 系统不会为了 full-screen intent 再拉起一次 Activity（那个通道只对
     * 后台 / 锁屏生效），于是 `onNewIntent` 不来、没人调 [renderRinging]，
     * 「正在响铃」那一条就不出现 —— 非得切到别的页再切回来才刷新。
     *
     * 可见期间每秒一次的空转很便宜（就是个 `isRinging()` 判断），
     * 换掉的是「闹钟响了却停不掉」这种要命的体验。
     * 页面隐藏 / onPause / 销毁时仍由那三处 removeCallbacks 停掉。
     */
    private val ringWatcher = object : Runnable {
        override fun run() {
            if (_binding == null || isHidden) return
            renderRinging()
        }
    }

    /**
     * 响铃状态一变就立刻刷页面。
     *
     * 1 秒轮询（[ringWatcher]）只是保底：它在 onPause / 页面藏起来时会被摘掉，
     * 「开始响 / 停了」这两个瞬间得靠 AlarmRinger 的回调。
     * 少了它，闹钟在后台响、用户切回闹钟页那一下可能先看见一只蓝色的钟。
     */
    private val onRingStateChanged: () -> Unit = { if (_binding != null) render() }

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
        val p3 = store.p3Mode
        // P3 彩蛋：表盘换成照游戏「影时间」那只钟重建的蓝色版，
        // 两只钟同在布局里、按开关二选一（另一只 GONE，不测量也不重画）
        binding.clock.visibility = if (p3) View.GONE else View.VISIBLE
        binding.personaClock.visibility = if (p3) View.VISIBLE else View.GONE
        applySkin(p3)

        binding.btnAdd.setOnClickListener { openEditor(null) }
        AlarmRinger.addStartListener(onRingStateChanged)
        AlarmRinger.addStopListener(onRingStateChanged)

        // 上滑只做平移：表盘 + 数字钟 + 日期整组向上走 0.32 倍滚动距离
        binding.scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            binding.heroInner.translationY = -y * 0.32f
        }

        // 首帧先给一个大约视口高，避免白纸在 layout 前闪到顶部
        binding.spacer.layoutParams.height = resources.displayMetrics.heightPixels
        binding.root.doOnLayout { layoutHero() }
        render()
    }

    /** spacer / 白纸的视口高度：白纸必须能盖满一屏，滚动才刚好停在列表顶端 */
    private fun layoutHero() {
        val b = _binding ?: return
        val h = b.scroll.height
        if (h <= 0) return
        b.spacer.layoutParams = b.spacer.layoutParams.apply { height = h }
        b.spacer.requestLayout()
        b.sheet.minimumHeight = h
        positionRing(h)
    }

    /** 响铃浮层落在数字钟下面；屏幕矮的时候往上收，别压到导航栏 */
    private fun positionRing(height: Int = _binding?.root?.height ?: 0) {
        val b = _binding ?: return
        if (height <= 0) return
        val lp = b.rowRinging.layoutParams as? FrameLayout.LayoutParams ?: return
        val maxTop = (height - dp(210)).coerceAtLeast(dp(120))
        lp.topMargin = (height * 0.72f).toInt().coerceIn(dp(120), maxTop)
        b.rowRinging.layoutParams = lp
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
        AlarmRinger.removeStartListener(onRingStateChanged)
        AlarmRinger.removeStopListener(onRingStateChanged)
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
        binding.tvListTitle.text = if (alarms.isEmpty()) "闹钟" else "闹钟 · ${alarms.size}"
        renderNowCard(alarms)
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
        CardAlertDialogBuilder(requireContext())
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
    /**
     * 「正在响铃」那张卡的出现 / 消失。
     *
     * 为什么不能直接切 `visibility`：这张卡是闹钟响起来时唯一能停掉声音的入口，
     * 凭空「啪」地跳出来会让人以为界面出错了。所以做成从上方落下 + 淡入，
     * 收起来时反向淡出 —— 和页面里其它卡片的动画语速一致。
     *
     * [ringingShown] 记住当前状态：`renderRinging()` 每秒都会调一次，
     * 不记住的话每次都会重播一遍入场动画。
     */
    private var ringingShown = false

    private fun showRingingRow(visible: Boolean) {
        val row = _binding?.rowRinging ?: return
        if (visible == ringingShown) return
        ringingShown = visible

        row.animate().cancel()
        if (visible) {
            row.visibility = View.VISIBLE
            row.alpha = 0f
            row.translationY = -dp(16).toFloat()
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            row.animate()
                .alpha(0f)
                .translationY(-dp(10).toFloat())
                .setDuration(160L)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    // 动画期间可能又响了，别把已经该显示的行藏掉
                    if (!ringingShown) {
                        row.visibility = View.GONE
                        row.translationY = 0f
                        row.alpha = 1f
                    }
                }
                .start()
        }
    }

    private fun renderRinging() {
        val b = _binding ?: return
        // 「在响」看会话，不看有没有声音：铃声起不来时 player 是 null，
        // 但闹钟确实响了（通知还在）—— 用 isRinging() 判会让钟不转绿、响铃卡也不出。
        // 判据和 MainActivity.onResume 那句保持一致。
        val ringing = AlarmRinger.isRinging() || AlarmRinger.isSessionActive()
        // 变色摆在最前面：后面 updateClockText / showRingingRow 出任何岔子
        // 都不能把「影时间」这层颜色挡掉
        b.personaClock.setDarkHour(ringing)
        if (store.p3Mode) {
            b.tvDigital.setTextColor(
                Color.parseColor(if (ringing) "#6FE0A0" else "#18D8F8")
            )
        }
        b.tvHint.visibility = if (ringing) View.GONE else View.VISIBLE
        updateClockText()
        showRingingRow(ringing)

        // 不管响没响都继续排下一次 —— 见 [ringWatcher] 上的注释：
        // 停掉轮询就等于「App 在前台时永远发现不了闹钟开始响」。
        handler.removeCallbacks(ringWatcher)
        handler.postDelayed(ringWatcher, 1000L)

        if (!ringing) return

        val id = AlarmRinger.currentIds().firstOrNull()
        val alarm = store.alarms().firstOrNull { it.id == id }
        val nowHm = "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute)
        b.tvRingTime.text = when (alarm?.source) {
            AlarmSource.COURSE -> "课前 ${alarm.leadMinutes} 分钟"
            AlarmSource.MOMENT -> alarm.leadText
            AlarmSource.MANUAL -> alarm.timeText
            null -> nowHm
        }
        b.tvRinging.text = "正在响铃" +
                (alarm?.let { " · " + Alarms.describe(it, store.courses()) } ?: "")
        b.btnRingSnooze.text = "贪睡 ${AlarmRinger.currentSnoozeMinutes()} 分钟"
        b.btnRingStop.setOnClickListener {
            // 「把盖在锁屏上的窗口还回去」不用在这里做 —— AlarmRinger 会把所有停止路径
            // 汇到一个回调上，MainActivity 在那里统一收尾（见 onAlarmStopped）
            AlarmRinger.stop(requireContext())
            renderRinging()
            render()
        }
        b.btnRingSnooze.setOnClickListener { snoozeFromApp() }

        // 「解锁手机」只在设备真的锁着时出现。响铃窗口是盖在锁屏**上面**的，
        // 锁屏并没有被解开 —— 想用手机只能先停闹钟，这条给了另一个出口。
        // 每秒重算一次，所以用户自己解锁之后它会自己消失。
        val locked = (requireContext().getSystemService(Context.KEYGUARD_SERVICE)
                as? KeyguardManager)?.isKeyguardLocked == true
        b.btnRingUnlock.visibility = if (locked) View.VISIBLE else View.GONE
        b.btnRingUnlock.setOnClickListener {
            (activity as? MainActivity)?.requestUnlock()
        }
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
        renderRinging()
        render()
    }

    /**
     * 「此刻」卡：左边白色时间条是现在，右边大字是下一次响铃。
     *
     * 卡片背景是上一代闹钟记录卡的三层（蓝玻璃底 + 深藏青面板 + 白条），
     * 只是把缺角改成了直角。
     */
    private fun renderNowCard(alarms: List<Alarm>) {
        val now = LocalDateTime.now()
        val next = Alarms.nextFire(
            alarms = alarms,
            from = now,
            courses = store.courses(),
            templates = store.templates(),
            holidays = store.holidayTable(),
            weekOf = store::weekOf
        )
        if (next == null) {
            binding.tvNowNext.text = "暂无"
            binding.tvNowNext.textSize = 24f
            binding.tvNowLeft.text = if (alarms.isEmpty()) "暂无闹钟" else "没有启用的闹钟"
            binding.tvNowMeta.text =
                if (alarms.isEmpty()) "点下面的「新建闹钟」添加" else "打开一条，或新建一条"
        } else {
            binding.tvNowNext.textSize = 34f
            binding.tvNowNext.text =
                "%02d:%02d".format(next.minuteOfDay / 60, next.minuteOfDay % 60)
            val left = Duration.between(now, next.at).toMinutes().coerceAtLeast(0)
            binding.tvNowLeft.text = "还有 ${durationText(left)}"
            val today = now.toLocalDate()
            val date = next.at.toLocalDate()
            val day = when (date) {
                today -> "今天"
                today.plusDays(1) -> "明天"
                else -> "${date.monthValue} 月 ${date.dayOfMonth} 日"
            }
            binding.tvNowMeta.text = "$day · ${weekdayCn(date.dayOfWeek.value)} · " +
                    Alarms.describe(next.alarm, store.courses())
        }
        updateClockText()
    }

    /** 数字钟、日期、白条上的当前时间：每秒调一次，开销很小 */
    private fun updateClockText() {
        val now = LocalDateTime.now()
        val hm = "%02d:%02d".format(now.hour, now.minute)
        binding.tvDigital.text = hm
        binding.tvDigitalOutline.text = hm
        binding.tvNowTime.text = hm
        binding.tvDate.text = "${weekdayCn(now.dayOfWeek.value)} · 第 " +
                "${store.weekOf(now.toLocalDate())} 周 · " +
                "%02d/%02d".format(now.monthValue, now.dayOfMonth)
        binding.personaClock.invalidate()
    }

    private fun durationText(minutes: Long): String {
        val d = minutes / (24 * 60)
        val h = minutes % (24 * 60) / 60
        val m = minutes % 60
        return when {
            d > 0 -> "$d 天 $h 小时"
            h > 0 -> "$h 小时 $m 分"
            else -> "$m 分钟"
        }
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

    /**
     * 新布局的皮肤。普通主题用 XML 默认值，P3 只覆盖颜色和背景。
     *
     * 布局是两种模式共用的：固定表盘 + 透明滚动区 + 白纸。
     * 普通主题下这只表盘是 [ClockView]，P3 换成 [PersonaClockView]；
     * 颜色走主题强调色，只有 P3 才切到固定的蓝/青/藏青。
     */
    private fun applySkin(p3: Boolean) {
        val ctx = requireContext()
        if (!p3) {
            // 普通主题的 ClockView 自己带一个数字钟，别再叠一个大数字
            binding.tvDigital.visibility = View.GONE
            binding.tvDigitalOutline.visibility = View.GONE
            (binding.tvDate.layoutParams as? FrameLayout.LayoutParams)?.topMargin = dp(400)
            binding.tvNowTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            return
        }
        binding.tvDigital.visibility = View.VISIBLE
        binding.tvDigitalOutline.visibility = View.VISIBLE
        (binding.tvDate.layoutParams as? FrameLayout.LayoutParams)?.topMargin = dp(510)

        val cyan = Color.parseColor("#18D8F8")
        val arrowBlue = Color.parseColor("#6FA8FF")
        val tagRed = Color.parseColor("#FF9AA8")

        // 手绘天空图 + 从上到下的深蓝压暗；底下那层渐变只是图片加载失败时的兜底
        binding.root.setBackgroundResource(R.drawable.bg_alarm_page)
        // 黑边：底下那层黑字描粗一圈，上面青字压住中间 → 硬边黑框
        binding.tvDigitalOutline.paint.apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = textSize * 0.11f
        }
        binding.sheet.setBackgroundColor(ContextCompat.getColor(ctx, R.color.p3_paper))
        binding.sheetTopLine.setBackgroundColor(cyan)

        binding.tvDigital.setTextColor(cyan)
        binding.tvDate.setTextColor(PersonaAlarmPalette.TEXT_DIM)
        binding.tvHint.setTextColor(PersonaAlarmPalette.TEXT_DIM)
        binding.thread.visibility = View.VISIBLE

        binding.nowCard.setBackgroundResource(R.drawable.bg_now_card)
        binding.tvNowTime.background = ContextCompat.getDrawable(ctx, R.drawable.bg_time_bar)
        binding.tvNowTime.setTextColor(ContextCompat.getColor(ctx, R.color.p3_ink))
        binding.tvNowLabel.setTextColor(cyan)
        binding.tvNowNext.setTextColor(PersonaAlarmPalette.TEXT)
        binding.tvNowLeft.setTextColor(cyan)
        binding.tvNowMeta.setTextColor(PersonaAlarmPalette.TEXT_DIM)

        listOf(
            binding.tvDigital, binding.tvDate, binding.tvHint, binding.tvNowTime,
            binding.tvNowLabel, binding.tvNowNext, binding.tvNowLeft, binding.tvNowMeta,
            binding.tvHolidayHint, binding.tvLastRang, binding.tvEmpty, binding.tvListTitle,
            binding.tvMissed, binding.tvWarning, binding.tvRinging, binding.tvRingTime,
            binding.tvRingTag, binding.addTitle, binding.addSub
        ).forEach { it.typeface = PersonaAlarmStyle.face }

        // 白纸上的字用正常深色，别用浅蓝
        binding.tvListTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        binding.tvHolidayHint.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        binding.tvLastRang.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        binding.tvEmpty.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))

        listOf(binding.tvMissed, binding.tvWarning).forEach {
            it.background = ContextCompat.getDrawable(ctx, R.drawable.bg_alarm_card)
            it.setTextColor(PersonaAlarmPalette.TEXT)
            it.setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // 新建按钮：左青块 + 深藏青长条；加号是矢量，几何居中
        binding.btnAdd.background = ContextCompat.getDrawable(ctx, R.drawable.bg_new_alarm)
        binding.addPlus.setBackgroundColor(cyan)
        binding.addIcon.setImageTintList(ColorStateList.valueOf(Color.parseColor("#02124F")))
        binding.addTitle.setTextColor(PersonaAlarmPalette.TEXT)
        binding.addSub.setTextColor(PersonaAlarmPalette.TEXT_DIM)
        binding.addArrow.setImageTintList(ColorStateList.valueOf(arrowBlue))

        // 响铃卡
        binding.rowRinging.background = ContextCompat.getDrawable(ctx, R.drawable.bg_ring_card)
        binding.tvRingTag.setTextColor(tagRed)
        binding.tvRingTime.setTextColor(PersonaAlarmPalette.TEXT)
        binding.tvRinging.setTextColor(PersonaAlarmPalette.TEXT_DIM)
        binding.btnRingStop.background = ContextCompat.getDrawable(ctx, R.drawable.bg_stop)
        binding.btnRingStop.setTextColor(PersonaAlarmPalette.TEXT)
        binding.btnRingSnooze.background = ContextCompat.getDrawable(ctx, R.drawable.bg_snooze)
        binding.btnRingSnooze.setTextColor(PersonaAlarmPalette.TEXT)
        binding.btnRingUnlock.setTextColor(PersonaAlarmPalette.TEXT_DIM)
        binding.btnRingStop.typeface = PersonaAlarmStyle.face
        binding.btnRingSnooze.typeface = PersonaAlarmStyle.face
    }

    /** 一行闹钟：左边时刻 + 说明，右边开关 */
    private fun row(a: Alarm): View {
        val ctx = requireContext()
        val p3 = store.p3Mode
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(78)
            ).apply { bottomMargin = dp(8) }
            setBackgroundResource(if (p3) R.drawable.bg_alarm_card else R.drawable.bg_card)
            setPadding(dp(14), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
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
        val head = TextView(ctx).apply {
            text = headline
            textSize = if (a.source == AlarmSource.MANUAL) 23f else 17f
            typeface = if (p3) PersonaAlarmStyle.face else android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(
                if (p3) PersonaAlarmPalette.TEXT
                else ContextCompat.getColor(ctx, R.color.text_primary)
            )
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(head)

        val subView = label(
            ctx, sub(a),
            if (p3) 8.5f else 9.5f,
            R.color.text_secondary, top = 3
        )
        val nextView = label(
            ctx, perAlarmNext(a),
            if (p3) 7.5f else 8.5f,
            R.color.text_secondary, top = 2
        )
        if (p3) {
            listOf(subView, nextView).forEach {
                it.setTextColor(PersonaAlarmPalette.TEXT_DIM)
                it.typeface = PersonaAlarmStyle.face
            }
        }
        col.addView(subView)
        col.addView(nextView)
        card.addView(col)

        card.addView(
            Switch(ctx).apply {
                isChecked = a.enabled
                if (p3) {
                    thumbTintList = ColorStateList.valueOf(Color.parseColor("#18D8F8"))
                    trackTintList = ColorStateList.valueOf(Color.parseColor("#2F62FF"))
                }
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
