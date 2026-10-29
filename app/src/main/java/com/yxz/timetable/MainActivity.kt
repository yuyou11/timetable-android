package com.yxz.timetable

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.ActivityMainBinding
import com.yxz.timetable.notify.AlarmRinger
import com.yxz.timetable.notify.AlarmScheduler
import com.yxz.timetable.notify.Notifier
import com.yxz.timetable.ui.AppTheme
import com.yxz.timetable.ui.AlarmFragment
import com.yxz.timetable.ui.CalendarFragment
import com.yxz.timetable.ui.CardAlertDialogBuilder
import com.yxz.timetable.ui.SettingsFragment
import com.yxz.timetable.ui.TodayFragment
import com.yxz.timetable.ui.WeekFragment
import com.yxz.timetable.ui.accentColor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 当前停在哪个 tab（R.id.nav_*）。重建时用它把底部导航的高亮补回来。 */
    private var currentTab = R.id.nav_today

    /** P3 模式下底部导航换了画法（纸 + 斜块 + 圆），着色规则也不一样。见 [applyPersona3Nav] */
    private var p3Nav = false

    /**
     * 这次窗口是不是「被闹钟拉起来的」。
     *
     * 它决定两件事：响铃期间窗口要盖在锁屏上；响铃一结束就得把这个窗口还回去
     * （见 [onAlarmStopped]）。用户自己打开 App 时它是 false ——
     * 权限该按需给，不按身份给。
     */
    private var alarmLaunch = false

    /**
     * 响铃结束的回调，注册在 [AlarmRinger] 上。
     *
     * 挂在铃声那边而不是让每个「停止」按钮自己收尾：停止闹钟有四条路
     * （通知按钮 / 页面上的按钮 / 自动停 / 关掉开关），漏一条就会留下
     * 一个压着锁屏的窗口 —— 那正是「解不开锁」的成因。
     */
    private val onRingingEnd = { onAlarmStopped() }

    /**
     * 闹钟开始响、而 App 已经在前台时走这里：把页面切到闹钟页。
     *
     * 为什么需要它：full-screen intent 只对「后台 / 锁屏」生效，用户就停在
     * App 里时系统不会再拉起一次 Activity，`onNewIntent` 也就不会来 ——
     * 表现是「闹钟在响，但页面上什么都没有」，非得手动切页才刷新。
     *
     * 这里只切页 + 让闹钟页重画，不做窗口相关的操作（那些仍由
     * [onAlarmStopped] 和 full-screen intent 那条路负责）。
     */
    private val onRingingStart = {
        if (!isFinishing && !isDestroyed) {
            selectTab(R.id.nav_alarm)
            (supportFragmentManager.findFragmentByTag(TAG_ALARM) as? AlarmFragment)
                ?.onAlarmLaunched()
        }
    }

    /**
     * 通知权限的申请回调。
     *
     * 拿到权限后立刻重画一次通知 —— 因为用户很可能是在
     * 「常驻开关已经打开、但通知被系统挡住」的状态下进来的。
     */
    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Notifier.update(this)
            AlarmScheduler.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ setTheme 必须在 super.onCreate **之前**。
        //
        // Activity 的界面（布局解析、主题属性解析）是在 super.onCreate 里
        // 开始构建的。在那之后再 setTheme，这一次创建已经用不上新主题了 ——
        // 得等下一次 recreate 才生效，表现就是「切了主题但界面没变」。
        //
        // 换成绿色主题时走的是同一条路：设置页改完 store 再 recreate()，
        // 这里重新读一遍，整个界面就换色了。
        setTheme(AppTheme.from(Store(this).themeKey).resId)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 底部导航的选中色跟着主题走（蓝 / 绿）。
        // 用代码设而不是写 color selector：?attr 在 color 资源里要靠主题解析，
        // 这里直接取当前主题的 accentColor 更直白；换主题 recreate 时会重新执行。
        // 中间那颗「今天」永远白图标白字（外面是强调色实心圆），所以不参与着色。
        binding.navWeek.setOnClickListener { selectTab(R.id.nav_week) }
        binding.navCalendar.setOnClickListener { selectTab(R.id.nav_calendar) }
        binding.navToday.setOnClickListener { selectTab(R.id.nav_today) }
        binding.navAlarm.setOnClickListener { selectTab(R.id.nav_alarm) }
        binding.navSettings.setOnClickListener { selectTab(R.id.nav_settings) }

        // P3 彩蛋：底部导航只动两处 —— 字体、中间那颗圆。
        // 背景色 / 图标 / 选中色都沿用原来的，免得和用户选的主题色打架。
        if (Store(this).p3Mode) applyPersona3Nav()

        // 是不是被闹钟拉起来的？是的话这次要:停在闹钟页 + 锁屏上也能显示 + 屏幕别灭
        val fromAlarm = intent?.getBooleanExtra(EXTRA_FROM_ALARM, false) == true
        // 从状态栏闹钟图标点进来：只停在闹钟页，不亮屏、不盖锁屏
        val openAlarmTab = intent?.getBooleanExtra(EXTRA_OPEN_ALARM_TAB, false) == true
        if (fromAlarm) {
            alarmLaunch = true
            showOverLockScreen()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // 渠道要尽早建好：一旦用户从通知栏点进设置改了重要性，这个渠道就固定下来了
        Notifier.ensureChannel(this)

        // 四个页面各用一个固定 tag 装着，切页只 hide/show ——
        // 用 replace 会把页面整个重建：课表翻到第 12 周、切去设置再回来就回到本周，
        // 设置页滚到一半的位置也会丢。hide/show 保住实例，页面状态跟着一起保住。

        // savedInstanceState == null 表示这是「全新启动」而不是「屏幕旋转后重建」。
        // 旋转时系统会自动恢复原来的 Fragment，这时再选一次 tab
        // 会导致页面被创建两遍 —— 但高亮得补回来，否则停在设置页、
        // 底部却亮着「今天」。
        if (savedInstanceState == null) {
            selectTab(if (fromAlarm || openAlarmTab) R.id.nav_alarm else R.id.nav_today)
        } else {
            currentTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_today)
            paintTabs(currentTab)
        }

        askNotificationPermission()

        if (savedInstanceState == null) askFirstLaunch()
    }

    /**
     * 响应第二次「闹钟拉起」（App 已经在运行时，系统不再新建实例而是送进这里）。
     *
     * 少了这个方法会出现一个很别扭的情况：App 正在后台（比如停在课表页），
     * 闹钟响了、窗口被拉到前台，但停在课表页 —— 用户看不到停止按钮。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_ALARM, false)) {
            alarmLaunch = true
            showOverLockScreen()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            selectTab(R.id.nav_alarm)
            // 如果本来就停在闹钟页，再选一次也不会重建页面 ——
            // 这里主动补一次，让「正在响铃」那条立刻出现。
            // 它是响铃时唯一一定停得掉声音的入口，不能等下一次 onResume。
            (supportFragmentManager.findFragmentByTag(TAG_ALARM) as? AlarmFragment)
                ?.onAlarmLaunched()
        } else if (intent.getBooleanExtra(EXTRA_OPEN_ALARM_TAB, false)) {
            // 状态栏闹钟图标：只切页，不做任何「响铃」相关的事
            selectTab(R.id.nav_alarm)
        }
    }

    /** 切到某个 tab：更新底部导航的高亮，再切页面。 */
    private fun selectTab(itemId: Int) {
        currentTab = itemId
        paintTabs(itemId)
        showTab(itemId)
    }

    /**
     * 底部导航的高亮。
     *
     * 中间那颗「今天」不参与：它外面是强调色实心圆，图标和文字永远是白的，
     * 选不选中都长一样 —— 它是「回今天」的常驻入口，不是普通 tab。
     */
    private fun paintTabs(selected: Int) {
        val on = accentColor()
        val off = ContextCompat.getColor(this, R.color.text_secondary)

        fun paint(item: View, icon: ImageView, label: TextView) {
            val on_ = item.id == selected
            val c = when {
                // 浅灰底：未选中用默认的次要灰；P3 的选中不跟主题强调色走，固定墨蓝
                !on_ -> off
                p3Nav -> P3_NAV_INK
                else -> on
            }
            icon.setColorFilter(c)
            label.setTextColor(c)
        }

        paint(binding.navWeek, binding.iconWeek, binding.labelWeek)
        paint(binding.navCalendar, binding.iconCalendar, binding.labelCalendar)
        paint(binding.navAlarm, binding.iconAlarm, binding.labelAlarm)
        paint(binding.navSettings, binding.iconSettings, binding.labelSettings)
        // 默认外观下「今天」不参与着色：它外面是实心强调色圆，图标和文字永远白。
        // P3 下那块底板已经撤掉了，它就和其他项一样按「选中 / 未选中」上色
        if (p3Nav) {
            paint(binding.navToday, binding.iconToday, binding.labelToday)
        }
    }

    /**
     * P3 彩蛋下的底部导航。
     *
     * 现在只有两处改动，**没有装饰**：
     *
     * 1. 五个标签换成 P3 那种窄体粗斜（`sans-serif-condensed` + `BOLD_ITALIC`）；
     * 2. 底色和栏顶那条墨蓝硬线交给 [PersonaNavBackdrop] 画（导航栏自己的背景置空，
     *    否则那份 `card_bg` 会盖住页面的最下沿）。
     *
     * 曾经画过、已经撤掉的：中间「今天」那颗探出栏顶的圆 / 裁角方板 / 扁矩形，
     * 以及选中项背后的深藏青斜块。要恢复的话看 git 历史，
     * 尺寸比例当时是 `PLATE_W_RATIO` / `PLATE_H_RATIO` / `CHAMFER_RATIO` 那组常量。
     */
    private fun applyPersona3Nav() {
        p3Nav = true
        val face = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
        listOf(
            binding.labelWeek, binding.labelCalendar,
            binding.labelToday, binding.labelAlarm, binding.labelSettings
        ).forEach { label ->
            label.typeface = face
            label.letterSpacing = 0.06f
            label.includeFontPadding = true

            // ⚠️ 宽度必须撑满整格，不能用 wrap_content。
            //
            // 中文在 sans-serif-condensed 里没有字形，会落到后备字体上，而
            // BOLD_ITALIC 是**合成**出来的：斜体靠切变把墨迹往右推，但 TextView
            // 的 wrap_content 是按字符的「步进宽度」量的 —— 量出来的盒子比实际
            // 墨迹窄，右边那一截就被自己的边界切掉（真机上「表」「置」的右缘
            // 是一条直线）。撑满整格再居中，左右都有富余，怎么斜都不会碰到边界。
            label.layoutParams = label.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
            label.gravity = Gravity.CENTER_HORIZONTAL
        }

        // 导航项的水波纹要撤掉。原来用的 selectableItemBackgroundBorderless 是
        // 「无边界」水波：它会按视图的最大边长向外扩散，而 P3 模式下导航栏自己
        // 没有底色（底色和大圆都由下面那层 backdrop 画），水波就会在蓝圆上面
        // 摊开一个同样大的灰圆，还会溢出到栏外。
        // 换成最简单的按压反馈：按下时整项淡一点，松手复原。
        listOf(
            binding.navWeek, binding.navCalendar, binding.navToday,
            binding.navAlarm, binding.navSettings
        ).forEach { item ->
            item.background = null
            item.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.55f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }

        // 原来那圈 54dp 的实心圆也撤掉：底栏回到「一条底 + 五个图标文字」的朴素样子
        binding.todayBubble.background = null
        // XML 里给「今天」图标留的是白色 tint（原来衬在实心圆上）。现在它和别的项
        // 一样按选中态上色，白 tint 会压住 setColorFilter，必须先清掉
        binding.iconToday.imageTintList = null

        // 「今天」这一项原来是为**实心圆**配的：图标 20dp、字号 10sp，都比旁边四个
        // （22dp / 11sp）小一号。圆撤掉之后五颗并排，大小不一致非常显眼，所以拉齐。
        binding.iconToday.layoutParams = binding.iconToday.layoutParams.apply {
            width = dp(22f).toInt()
            height = dp(22f).toInt()
        }
        binding.iconToday.requestLayout()
        binding.labelToday.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX, binding.labelWeek.textSize
        )

        // 导航栏的底色与阴影交给下面那层 backdrop
        binding.bottomBar.background = null
        binding.bottomBar.elevation = 0f

        binding.bottomBar.post {
            val barH = binding.bottomBar.height
            if (barH <= 0) return@post
            val poke = dp(POKE_DP)
            val backdrop = binding.personaBackdrop

            backdrop.barHeight = barH.toFloat()
            backdrop.poke = poke
            // 底栏底色：P3 用浅灰（比白纸深一档，页签区和页面白纸之间有条边）；
            // 默认外观仍是 card_bg 的白
            backdrop.barColor = P3_NAV_BAR
            backdrop.visibility = View.VISIBLE

            // 高度 = 栏高 + 预留高度。宿主是一个 0 高度、位置正好在栏顶的容器，
            // 所以孩子 bottom 对齐后顶端在 -高度 处；再下移一个栏高，
            // 就变成「顶端高出栏顶 poke、底端正好落在屏幕底」。
            val lp = backdrop.layoutParams
            lp.height = (barH + poke).toInt()
            backdrop.layoutParams = lp
            backdrop.translationY = barH.toFloat()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /**
     * 切页：只 hide 其它、show 目标，不销毁实例。
     *
     * 用 commitNow 而不是 commit：commit 是异步的，连着点两个标签时
     * 第一次的 add 还没执行、第二次就找不到它，会同时留下两个可见页面。
     */
    private fun showTab(itemId: Int) {
        val tag = tagOf(itemId)
        val fm = supportFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        var changed = false

        for (t in TAB_TAGS) {
            val f = fm.findFragmentByTag(t) ?: continue
            if (t != tag && !f.isHidden) {
                tx.hide(f)
                changed = true
            }
        }

        val target = fm.findFragmentByTag(tag)
        if (target == null) {
            tx.add(R.id.container, newTab(itemId), tag)
            changed = true
        } else if (target.isHidden) {
            tx.show(target)
            changed = true
        }

        if (changed) tx.commitNow()
    }

    private fun newTab(itemId: Int): Fragment = when (itemId) {
        R.id.nav_week -> WeekFragment()
        R.id.nav_alarm -> AlarmFragment()
        R.id.nav_calendar -> CalendarFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> TodayFragment()
    }

    private fun tagOf(itemId: Int): String = when (itemId) {
        R.id.nav_week -> TAG_WEEK
        R.id.nav_calendar -> TAG_CALENDAR
        R.id.nav_alarm -> TAG_ALARM
        R.id.nav_settings -> TAG_SETTINGS
        else -> TAG_TODAY
    }

    /**
     * 锁屏上也要能看到并操作（闹钟响时被拉起来的这一次才算）。
     *
     * 不用在 Manifest 上全局加 showWhenLocked：那会让 App **任何时候**都能盖住
     * 锁屏，包括用户自己正常打开的时候 —— 权限该按需给，不按身份给。
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    /**
     * 首次启动时问一句：用内置示例课表，还是从空白开始。
     *
     * ## 为什么需要这一步
     *
     * App 里内置的是一份虚构的示例课表（见 BuiltinCourses）。这个 App 是要发给同学用的，
     * 而**用别的课表的同学装上之后会看到一堆跟自己无关的课** ——
     * 他要么不知道这些课可以删，要么得一门一门去关掉。
     *
     * 给一个明确的开场选择，比让他自己去设置页里找要友好得多。
     *
     * 只在真正的首次启动弹出（判断逻辑见 Store.isFirstLaunch），
     * 老用户升级不会被打扰。
     */
    private fun askFirstLaunch() {
        val store = Store(this)
        if (!store.isFirstLaunch) return

        CardAlertDialogBuilder(this)
            .setTitle(R.string.first_launch_title)
            .setMessage(R.string.first_launch_msg)
            .setPositiveButton(R.string.first_launch_blank) { _, _ ->
                store.clearCourses()
                store.markLaunched()
                reboot()
            }
            .setNegativeButton(R.string.first_launch_keep) { _, _ ->
                store.markLaunched()
                reboot()
            }
            .setCancelable(false)
            .show()
    }

    /** 重建当前界面，让改动立刻反映出来 */
    private fun reboot() {
        // 整页重建交给 recreate：FragmentManager 会把当前页一起重建，
        // 数据重新读一遍。手动 replace 会破坏 hide/show 的页面集合，
        // 下次切页还可能蹦出两个叠在一起的页面。
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onStart() {
        super.onStart()
        // 兜底：进程被回收后重建，理论上 FragmentManager 会恢复页面；
        // 万一没有（比如系统只恢复了 Activity），至少别留一个空容器。
        if (supportFragmentManager.findFragmentById(R.id.container) == null) {
            selectTab(currentTab)
        }
        // 窗口在前台时才值得收尾：在后台时窗口本来就不显示，标签也就不用跟着改
        AlarmRinger.addStopListener(onRingingEnd)
        // 同理，只有在前台才需要「响了就把页面切到闹钟页」这件事
        AlarmRinger.addStartListener(onRingingStart)
    }

    override fun onStop() {
        AlarmRinger.removeStopListener(onRingingEnd)
        AlarmRinger.removeStartListener(onRingingStart)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时（比如刚开完精确闹钟权限），补一次排程，
        // 否则会出现「权限开了但闹钟还是按旧的（不精确）方式排着」的尴尬状态。
        // 两条链都要排：schedule 是通知刷新点，scheduleAlarms 是用户闹钟 ——
        // 后者漏掉的话，系统「强行停止」后重开 App 闹钟不会恢复。
        AlarmScheduler.schedule(this)
        AlarmScheduler.scheduleAlarms(this)

        // 响铃可能在窗口不在前台时就结束了（自动停、或用户从通知栏点了「停止」）。
        // 那种情况下监听器收不到回调，所以回到前台再补一次判断 ——
        // 否则一个已经不响了的窗口继续压着锁屏，用户照样解不开锁。
        // 判断用「响铃会话还在不在」而不是「有没有声音」：铃声起不来时窗口也该显示。
        if (alarmLaunch && !AlarmRinger.isSessionActive()) onAlarmStopped()
    }

    /**
     * 响铃结束：把「盖在锁屏上」的那个窗口还给锁屏。
     *
     * ## 这个 bug 是怎么来的
     *
     * 闹钟响时会调 [setShowWhenLocked]（旧系统是 FLAG_SHOW_WHEN_LOCKED）把窗口
     * **画在锁屏上面**。注意：这只是盖住，锁屏本身并没有被解开。只要这个标志还在，
     * 用户划屏、按指纹想输 PIN，碰到的都是这块窗口，锁屏根本轮不到显示。
     *
     * 以前停掉闹钟之后这个标志从来没撤过：声音停了、通知也撤了，窗口却还压着锁屏。
     * 用户看到的是一个闹钟页面，怎么划都解不开锁 —— 只能长按电源键重启。
     *
     * ## 现在怎么收
     *
     * 1. 撤掉「屏幕常亮」；
     * 2. 撤掉「盖住锁屏」和「亮屏」这两个窗口标志（它们不是一次性动作，set 过就得 clear）；
     * 3. **如果设备还锁着，直接把窗口交回去**（finishAndRemoveTask）——
     *    用户立刻看到正常的锁屏，该输 PIN 输 PIN。只是清标志不够可靠：
     *    有些厂商的锁屏不会马上重新浮上来。
     *
     * 设备已经解锁（闹钟响的时候人正在用手机）就不关窗口 —— 那是用户自己的 App，
     * 没理由替他关掉，他只想要个安静。
     */
    fun onAlarmStopped() {
        if (isFinishing || isDestroyed) return

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val wasAlarmWindow = alarmLaunch
        alarmLaunch = false
        if (wasAlarmWindow && keyguardLocked()) finishAndRemoveTask()
    }

    /** 现在锁着没有。用来决定「响铃结束后要不要把窗口交还给锁屏」 */
    private fun keyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    /**
     * 用户在响铃界面上主动要求解锁（见闹钟页那条「解锁手机」）。
     *
     * 用系统的 [KeyguardManager.requestDismissKeyguard] 而不是自己去解：
     * 解锁界面（PIN / 图案 / 指纹）是锁屏的事，App 只要能「申请」就够了，
     * 也没办法绕过它 —— 这正是应该的。
     *
     * 验证通过后窗口留在原地，只是从此变成一次「正常使用」：响铃继续，
     * 想停再按停止；这时候停掉也不会把窗口关掉（见 [onUserUnlocked]）。
     */
    fun requestUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = onUserUnlocked()
        })
    }

    /**
     * 用户已经自己解锁了：把这个窗口降级成普通的 App 窗口。
     *
     * 不降级的话，用户下次锁屏时它还会盖上去 —— 那就把同一个 bug 又制造了一遍。
     * 「屏幕常亮」留着：闹钟还在响，用户可能正看着它。
     */
    private fun onUserUnlocked() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        alarmLaunch = false
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /**
         * 「这次是被闹钟拉起来的」。
         *
         * 带上它有三个后果：停在闹钟页、锁屏上也能显示、响铃期间屏幕不灭。
         * 之所以用一个 extra 而不是专门的 Activity：**响铃时要弹的本来就是
         * 用户平时的 App 窗口**，用户在那里既能看到是哪个闹钟、也能顺手改它，
         * 不必再维护第二套界面。
         */
        const val EXTRA_FROM_ALARM = "com.yxz.timetable.extra.FROM_ALARM"

        /** 从状态栏闹钟图标打开：只停在闹钟页，不亮屏、不盖锁屏 */
        const val EXTRA_OPEN_ALARM_TAB = "com.yxz.timetable.extra.OPEN_ALARM_TAB"

        private const val STATE_TAB = "state_tab"

        /** 底板比导航栏多留出来的高度（dp）。现在没东西往上画，留着只是为了不改布局 */
        private const val POKE_DP = 8f

        /** P3 的墨蓝：选中页签的字与图标 */
        private val P3_NAV_INK = Color.rgb(4, 16, 107)

        /** P3 底栏的底色：浅灰（比白纸深一档，页签区不会和页面白纸糊成一片） */
        private val P3_NAV_BAR = Color.rgb(228, 231, 236)

        private const val TAG_TODAY = "tab_today"
        private const val TAG_WEEK = "tab_week"
        private const val TAG_CALENDAR = "tab_calendar"
        private const val TAG_ALARM = "tab_alarm"
        private const val TAG_SETTINGS = "tab_settings"

        private val TAB_TAGS =
            listOf(TAG_TODAY, TAG_WEEK, TAG_CALENDAR, TAG_ALARM, TAG_SETTINGS)

        /**
         * 闹钟响时用来拉起窗口的 Intent。
         *
         * FLAG_ACTIVITY_CLEAR_TOP：App 已经在跑就直接把原来那个实例拉到前台
         * （走 onNewIntent），而不是再叠一个新的。配合 Manifest 里的
         * launchMode="singleTop"，反复响铃也不会堆出一摞窗口。
         */
        fun alarmIntent(ctx: android.content.Context): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_FROM_ALARM, true)
            }
    }
}
