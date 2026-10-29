package com.yxz.timetable.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.yxz.timetable.BuildConfig
import com.yxz.timetable.R
import com.yxz.timetable.data.Holidays
import com.yxz.timetable.data.ScheduleFormat
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.Templates
import com.yxz.timetable.data.TimelineEngine
import com.yxz.timetable.data.dayTypeName
import com.yxz.timetable.databinding.FragmentSettingsBinding
import com.yxz.timetable.databinding.ItemCourseCheckBinding
import com.yxz.timetable.notify.AlarmScheduler
import com.yxz.timetable.notify.Notifier
import com.yxz.timetable.ui.persona.PersonaMenuView
import com.yxz.timetable.ui.persona.PersonaSettingsSkin
import java.time.LocalDate

/**
 * 设置页。
 *
 * 落地面是**分类列表**（pageHome：数据 / 功能 / 主题 / 关于 各一张卡），
 * 点卡片进入对应分类页；分类页里靠标题边的「‹」或系统返回键回列表，
 * 抽屉（右滑 / 「≡ 分类」）降级为分类页之间的快速切换器。
 *
 * 本体只保留「界面绑定 + 状态刷新 + 课程/学期/提醒/主题的直接编辑」；
 * 两块大而独立的流程拆了出去：
 *
 *   · [PermissionGuide] —— 权限与系统设置页的跳转引导
 *   · [ImportExport]    —— 导入 / 导出 / 复制 JSON / 格式说明
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    /** 当前显示的分类页。旋转重建时从 savedInstanceState 里恢复。 */
    private var currentPage = Page.HOME

    private lateinit var store: Store
    private lateinit var guide: PermissionGuide
    private lateinit var io: ImportExport
    private var suppressSwitch = false
    private var suppressLockscreen = false

    /**
     * 「返回分类列表」的返回键拦截。只在**设置页可见**且不在分类列表时启用 ——
     * 回调注册在 viewLifecycleOwner 上，切到别的页签后 Fragment 视图还活着，
     * 不按可见性拦一下的话，在别的页签按返回会被它吞掉。
     */
    private var backToHome: OnBackPressedCallback? = null

    /**
     * 文件选择器 / 保存器。
     *
     * 这两个**必须在 Fragment 构造阶段就注册好**。registerForActivityResult
     * 要求注册发生在 Fragment 进入 STARTED 状态之前，写在 onViewCreated 里
     * 在某些机型上会抛 IllegalStateException。字段初始化器正好在构造时执行，
     * 是最安全的写法。所以它们留在这里，拿到 uri 之后的事归 [ImportExport] 管。
     *
     * 用 SAF（系统文件选择器）而不是直接读写存储路径，好处是**完全不需要
     * 任何存储权限**：用户选中哪个文件，系统就只把那个文件的访问权临时借给 App，
     * 用完即收回。所以这个 App 的权限清单里一个存储权限都没有。
     */
    private val pickJsonFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { io.onFilePicked(it) } }

    private val saveJsonFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { io.onFileChosen(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = Store(requireContext())
        guide = PermissionGuide(this)
        io = ImportExport(
            fragment = this,
            store = store,
            // 课表/模板真的变了之后要做的三件事（顺序与拆分前一致）
            onChanged = {
                renderCourses()
                refresh()
                rescheduleNotification()
            },
            launchSaver = { saveJsonFile.launch(it) }
        )

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
            refresh()
        }

        binding.btnNotif.setOnClickListener { guide.openNotificationSettings() }
        binding.btnExact.setOnClickListener { guide.openExactAlarmSettings() }
        binding.btnBattery.setOnClickListener { guide.openBatterySettings() }
        binding.btnAutostart.setOnClickListener { guide.openAutostartSettings() }

        binding.btnWeekMinus.setOnClickListener { changeWeekOverride(-1) }
        binding.btnWeekPlus.setOnClickListener { changeWeekOverride(+1) }
        binding.tvWeekOverride.setOnClickListener { changeWeekOverride(0, reset = true) }

        binding.btnResetCourses.setOnClickListener {
            store.resetCourses()
            renderCourses()
            refresh()
            rescheduleNotification()
            toast("已恢复内置课表")
        }

        binding.btnClearCourses.setOnClickListener { confirmClearCourses() }

        binding.btnTermStart.setOnClickListener { pickTermStart() }
        binding.rowTermStart.setOnClickListener { pickTermStart() }

        binding.btnTotalWeeksMinus.setOnClickListener { changeTotalWeeks(-1) }
        binding.btnTotalWeeksPlus.setOnClickListener { changeTotalWeeks(+1) }

        binding.btnTermName.setOnClickListener { editTermName() }

        binding.btnImport.setOnClickListener {
            // 传 "*/*" 而不是 "application/json"：很多文件管理器把 .json
            // 认成 application/octet-stream，过滤太严会让用户根本选不中自己的文件
            pickJsonFile.launch(arrayOf("*/*"))
        }
        binding.btnExport.setOnClickListener { io.chooseExportScope() }
        binding.btnCopyJson.setOnClickListener { io.copyJsonToClipboard() }
        binding.btnFormatHelp.setOnClickListener { io.showFormatHelp() }

        binding.btnLockscreen.setOnClickListener { guide.openLockScreenHelp() }
        binding.rowTheme.setOnClickListener { pickTheme() }
        binding.btnAiGuide.setOnClickListener {
            startActivity(Intent(requireContext(), GenerateGuideActivity::class.java))
        }

        suppressLockscreen = true
        binding.swLockscreen.isChecked = store.lockscreenVisible
        suppressLockscreen = false

        binding.swLockscreen.setOnCheckedChangeListener { _, checked ->
            if (suppressLockscreen) return@setOnCheckedChangeListener
            store.lockscreenVisible = checked
            // 改完立刻重画通知，用户拉下通知栏就能看到锁屏可见性的变化
            // （在锁屏上才看得出差别，但重画这一步是必须的，否则要等下一个整点才生效）
            if (store.enabled) Notifier.update(requireContext())
            refresh()
        }

        // 周点阵预览开关：先设好状态再挂监听，不会自己触发自己，
        // 也就不需要 swLockscreen 那套 suppress 防抖（那套是给 refresh() 复位用的，
        // 这个开关不进 refresh —— 它是纯偏好，没有「系统状态变了要复核」这回事）
        binding.swWeekDots.isChecked = store.weekDotsEnabled
        binding.swWeekDots.setOnCheckedChangeListener { _, checked ->
            store.weekDotsEnabled = checked
        }

        // P3 彩蛋。换的是整页外观、不是色值，所以没有「运行时改颜色」那条路，
        // 只能像换主题一样重建 Activity：那边 setTheme 也只在 onCreate 之前生效。
        binding.swPersona3.isChecked = store.p3Mode
        binding.swPersona3.setOnCheckedChangeListener { _, checked ->
            if (store.p3Mode == checked) return@setOnCheckedChangeListener
            store.p3Mode = checked
            requireActivity().recreate()
        }

        binding.btnRemindMinus.setOnClickListener { changeRemindLead(-1) }
        binding.btnRemindPlus.setOnClickListener { changeRemindLead(+1) }

        binding.tvTemplateState.setOnClickListener { resetTemplates() }

        setupPages(savedInstanceState)

        renderCourses()
    }

    // ------------------------------------------------------------------
    // 四个分类页
    //
    // 为什么是「四块 view 互斥显示」而不是四个 Fragment：
    // 这些设置共享同一份 Store、同一套刷新（refresh() 里一次读盘、一起更新），
    // 拆成四个 Fragment 就得把刷新拆散，还会在切页时重复读盘。
    // 现在只是切 visibility，所有 view 一直存在，绑定和刷新逻辑一行没动。
    // ------------------------------------------------------------------

    private fun setupPages(savedInstanceState: Bundle?) {
        // P3 分类菜单：英文大字是装饰，中文功能名跟在词尾；点字跳分类
        binding.personaMenu.entries = listOf(
            PersonaMenuView.Entry("DATA", getString(R.string.group_data)),
            PersonaMenuView.Entry("FEATURE", getString(R.string.group_features)),
            PersonaMenuView.Entry("THEME", getString(R.string.group_theme)),
            PersonaMenuView.Entry("SYSTEM", getString(R.string.group_about)),
        )
        binding.personaMenu.onPick = { i ->
            showPage(
                when (i) {
                    0 -> Page.DATA
                    1 -> Page.FEATURES
                    2 -> Page.THEME
                    else -> Page.ABOUT
                }
            )
        }
        binding.personaMenu.contentDescription =
            "设置分类菜单：${getString(R.string.group_data)}、${getString(R.string.group_features)}、" +
                    "${getString(R.string.group_theme)}、${getString(R.string.group_about)}"

        // 分类列表（落地面）：点卡片进入对应分类页
        binding.catData.setOnClickListener { showPage(Page.DATA) }
        binding.catFeatures.setOnClickListener { showPage(Page.FEATURES) }
        binding.catTheme.setOnClickListener { showPage(Page.THEME) }
        binding.catAbout.setOnClickListener { showPage(Page.ABOUT) }
        // 抽屉里的同名条目：分类页之间的快速切换器
        binding.navData.setOnClickListener { showPage(Page.DATA) }
        binding.navFeatures.setOnClickListener { showPage(Page.FEATURES) }
        binding.navTheme.setOnClickListener { showPage(Page.THEME) }
        binding.navAbout.setOnClickListener { showPage(Page.ABOUT) }

        // 返回分类列表的两条路：标题左边的「‹」，或系统返回键。
        // 系统返回比照抽屉那条的写法：回调常驻注册，开不开随页面状态同步。
        binding.btnBackToCategories.setOnClickListener { showPage(Page.HOME) }
        val backToHome = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = showPage(Page.HOME)
        }
        this.backToHome = backToHome
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToHome)

        // 打开分类栏的两条路：内容区右滑（主力），或点标题右边那行字。
        // 不用 DrawerLayout 自带的边缘滑动 —— 手势导航下屏幕边缘是系统的返回区，
        // 从边缘滑会被系统先吃掉，抽屉收不到事件。
        binding.content.onSwipeRight = { binding.drawer.openDrawer(GravityCompat.START) }
        binding.tvSwipeHint.setOnClickListener { binding.drawer.openDrawer(GravityCompat.START) }

        // 抽屉开着时按返回键先关抽屉，而不是直接退出设置页。
        // 回调必须跟着开合状态开关 —— 常开的话会把系统返回整个吞掉。
        val backToClose = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = binding.drawer.closeDrawers()
        }
        binding.drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                backToClose.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                backToClose.isEnabled = false
            }
        })
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToClose)

        val saved = savedInstanceState?.getString(KEY_PAGE)
        showPage(Page.values().firstOrNull { it.key == saved } ?: Page.HOME, scrollTop = false)
    }

    private fun showPage(page: Page, scrollTop: Boolean = true) {
        currentPage = page

        // 落地面有两种皮：P3 模式是整幅的游戏菜单（PersonaMenuView，连标题栏都
        // 让位给它），普通模式是四张分类卡。分类页永远走普通皮。
        val onHome = page == Page.HOME
        val p3Home = onHome && store.p3Mode
        binding.pageHome.visibility = if (onHome && !p3Home) View.VISIBLE else View.GONE
        binding.personaMenu.visibility = if (p3Home) View.VISIBLE else View.GONE
        binding.titleBar.visibility = if (p3Home) View.GONE else View.VISIBLE
        // 菜单要左右通铺到屏幕两岸，内容区的横向内边距在 P3 落地面时退出
        val pad = resources.getDimensionPixelSize(R.dimen.page_padding)
        val hp = if (p3Home) 0 else pad
        (binding.scroll.getChildAt(0) as? LinearLayout)?.setPadding(hp, 0, hp, 0)

        binding.pageData.visibility = if (page == Page.DATA) View.VISIBLE else View.GONE
        binding.pageFeatures.visibility = if (page == Page.FEATURES) View.VISIBLE else View.GONE
        binding.pageTheme.visibility = if (page == Page.THEME) View.VISIBLE else View.GONE
        binding.pageAbout.visibility = if (page == Page.ABOUT) View.VISIBLE else View.GONE

        // P3：分类页不再是「蓝底菜单 + 白卡」的混搭 —— 整页铺同一张手绘天空，
        // 卡片 / 开关 / 按钮 / 文字统一换成深色皮肤（见 PersonaSettingsSkin）。
        // 落地面那张菜单自己会画天空，这里多铺一层也无妨（菜单盖在上面）。
        if (store.p3Mode && binding.content.background == null) {
            binding.content.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_alarm_page)
        }

        binding.tvPageTitle.setText(page.titleRes)

        // 分类列表本身就是分类，右侧的「≡ 分类」和返回箭头都只服务分类页
        binding.tvSwipeHint.visibility = if (onHome) View.GONE else View.VISIBLE
        binding.btnBackToCategories.visibility = if (onHome) View.GONE else View.VISIBLE

        // 选中态：文字用主题强调色 + 浅底，其余保持普通；分类列表上没有选中项
        val items = listOf(
            binding.navData to Page.DATA,
            binding.navFeatures to Page.FEATURES,
            binding.navTheme to Page.THEME,
            binding.navAbout to Page.ABOUT
        )
        for ((view, p) in items) {
            val on = p == page
            view.setTextColor(if (on) requireContext().accentColor() else ContextCompat.getColor(requireContext(), R.color.text_secondary))
            view.setBackgroundResource(if (on) R.drawable.bg_nav_selected else 0)
            view.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        // 返回键的分界：在分类页上按返回回分类列表，在分类列表上按返回交给系统
        backToHome?.isEnabled = !isHidden && !onHome

        // 换页要回到顶部，否则从长页切到短页会停在半空
        if (scrollTop) binding.scroll.scrollTo(0, 0)

        // P3 皮肤：放在最后 —— 上面刚改过抽屉里那几个导航项的颜色/背景，
        // 皮肤要按「当前这套颜色」去认，先刷就会被覆盖回去
        if (store.p3Mode) PersonaSettingsSkin.apply(binding.root)

        binding.drawer.closeDrawers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, currentPage.key)
    }

    /** 设置页的五个状态：分类列表（落地面）+ 四个分类。key 存进 Bundle，所以别随便改字符串。 */
    private enum class Page(val key: String, val titleRes: Int) {
        HOME("home", R.string.settings_title),
        DATA("data", R.string.group_data),
        FEATURES("features", R.string.group_features),
        THEME("theme", R.string.group_theme),
        ABOUT("about", R.string.group_about)
    }

    private companion object {
        const val KEY_PAGE = "settings_page"
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) refresh()   // 从系统设置页回来，权限状态可能已经变了
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
        // 切走时把「返回分类列表」的返回键拦截关掉，别吞掉别的页签的系统返回
        backToHome?.isEnabled = !hidden && currentPage != Page.HOME
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================================================================
    //  刷新
    // ==================================================================

    /** 闹钟默认铃声：系统铃声选择器 */
    private val pickAlarmSound = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = SoundPicker.uriFromResult(result.resultCode, result.data)
        if (uri != null) {
            store.alarmSoundUri = uri
            SoundPicker.play(requireContext(), uri)   // 选完立刻试听，省得再找地方试
            refreshAlarm()
        }
    }

    /** 原来是一个 120 行的大方法，按「屏幕上的几个区块」拆开，各自独立刷新 */
    private fun refresh() {
        refreshPermissions()
        refreshTerm()
        refreshAlarm()
        refreshData()
        // 放在最后：上面几块自己会读存储，读坏了才轮到这一条提示
        refreshLoadWarning()
        // P3：课程行是代码现搭的（renderCourses），每次刷新都会重建 —— 重建出来的
        // 还是白卡，所以皮肤必须跟着再刷一遍
        if (store.p3Mode) PersonaSettingsSkin.apply(binding.root)
    }

    /**
     * 存储读坏时的提示。
     *
     * 以前这种情况是**静默**回退的：课表解析失败就退回内置那 19 门示例课，
     * 用户只会觉得「我的课表怎么变成别人的了」。
     * [Store.lastLoadError] 把失败记下来，这里如实显示，点一下表示知道了。
     */
    private fun refreshLoadWarning() {
        val err = store.lastLoadError
        if (err == null) {
            binding.tvDataWarning.visibility = View.GONE
            return
        }
        binding.tvDataWarning.visibility = View.VISIBLE
        val what = err.substringBefore('|')
        binding.tvDataWarning.text = "「$what」读取失败，已回退到默认值。点此忽略。"
        binding.tvDataWarning.setOnClickListener {
            store.clearLoadError()
            refreshLoadWarning()
        }
    }

    /**
     * 闹钟那两块：默认铃声 + 放假/补班日。
     *
     * 铃声设置放在这里（而不是闹钟页）是因为它是**全局默认值** ——
     * 闹钟页那一栏管的是「每个闹钟自己用什么」，两处职责不同。
     */
    private fun refreshAlarm() {
        val ctx = requireContext()

        binding.tvAlarmSound.text =
            "${SoundPicker.nameOf(ctx, store.alarmSoundUri)}（点此试听）"
        binding.tvRingDuration.text = "${store.ringMinutes} 分钟 · 点此调整"
        binding.rowAlarmSound.setOnClickListener { SoundPicker.play(ctx, store.alarmSoundUri) }
        binding.btnAlarmSound.setOnClickListener {
            pickAlarmSound.launch(SoundPicker.intent(store.alarmSoundUri))
        }

        val custom = store.customHolidays()
        binding.tvHolidayStatus.text = if (custom.isEmpty()) {
            "内置 ${Holidays.COVERED_YEARS.sorted().joinToString("、")} 年 · 可手动补充"
        } else {
            "手动补了 ${custom.size} 天 · 优先于内置"
        }
        binding.rowHolidays.setOnClickListener {
            HolidayEditor(ctx, store) { refreshAlarm() }.show()
        }

        // 全屏响铃（Android 14+）。没开也能用，只是响铃时变成横幅 —— 文案照这个口径写
        val fullScreenOk = if (Build.VERSION.SDK_INT >= 34) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .canUseFullScreenIntent()
        } else {
            true
        }
        setPerm(
            binding.tvRingStatus, binding.btnRingFullScreen, fullScreenOk,
            "已允许 · 锁屏直接弹出",
            "未允许 · 只弹横幅通知"
        )
        binding.btnRingFullScreen.setOnClickListener { guide.openFullScreenIntentSettings() }

        // 响铃时长：点一下在 1 / 3 / 5 / 10 分钟之间循环
        binding.rowRingDuration.setOnClickListener {
            val steps = listOf(1, 3, 5, 10)
            val i = steps.indexOf(store.ringMinutes).let { if (it < 0) 1 else it }
            store.ringMinutes = steps[(i + 1) % steps.size]
            refreshAlarm()
        }
        binding.tvRingDuration.text = "${store.ringMinutes} 分钟 · 点此调整"
    }

    private fun refreshPermissions() {
        val ctx = requireContext()

        suppressSwitch = true
        binding.swEnabled.isChecked = store.enabled
        suppressSwitch = false

        binding.tvSwitchState.text =
            if (store.enabled) "运行中" else "已关闭"

        // ---- 1. 通知权限 ----
        val notifOk = Notifier.isAllowed(ctx)
        setPerm(binding.tvNotifStatus, binding.btnNotif, notifOk, "已允许", "未允许 · 通知不会出现")

        // ---- 2. 精确闹钟 ----
        val exactOk = AlarmScheduler.canExact(ctx)
        setPerm(
            binding.tvExactStatus, binding.btnExact, exactOk,
            "已开启",
            "未开启 · 切换可能延迟"
        )

        // ---- 3. 电池优化 ----
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        setPerm(
            binding.tvBatteryStatus, binding.btnBattery, batteryOk,
            "已加入白名单",
            "未加入 · 休眠时可能不唤醒"
        )

        // ---- 4. 锁屏显示 ----
        suppressLockscreen = true
        binding.swLockscreen.isChecked = store.lockscreenVisible
        suppressLockscreen = false

        binding.tvLockscreenStatus.text = if (!store.lockscreenVisible) {
            "已关闭 · 锁屏不显示"
        } else {
            "已开启 · 系统还有一道开关，需在通知渠道页打开"
        }

        // ---- 5. 自启动 ----
        // 这一项系统也没有提供查询接口，只能靠引导 + 用户自己确认
        binding.tvAutostartStatus.text = "请手动确认已允许"
        binding.btnAutostart.visibility = View.VISIBLE

        // ---- 课前提醒 ----
        val lead = store.remindLead
        binding.tvRemindLead.text = if (lead == 0) "关闭" else "$lead 分"
        binding.tvRemindStatus.text = when {
            lead == 0 -> "已关闭"
            !notifOk -> "需要通知权限"
            else -> "课前 $lead 分钟响铃"
        }
    }

    private fun refreshTerm() {
        // ---- 学期 ----
        binding.tvTermName.text = store.termName

        val start = store.termStart
        binding.tvTermStart.text =
            "$start（${weekdayCn(start.dayOfWeek.value)}）"

        val total = store.totalWeeks
        binding.tvTotalWeeks.text = "$total 周"
        // 让用户能一眼看出「今天落在第几周」，这比一个孤零零的数字有用
        val today = LocalDate.now()
        val curWeek = store.weekOf(today)
        binding.tvTotalWeeksHint.text = when {
            curWeek < 1 -> "学期未开始"
            curWeek > total -> "学期已结束"
            else -> "第 $curWeek 周"
        }

        binding.tvWeekOverride.text =
            if (store.weekOverride > 0) "第 ${store.weekOverride} 周" else "自动（第 $curWeek 周）"
    }

    private fun refreshData() {
        // ---- 数据 ----
        binding.tvDataSummary.text = "共 ${store.totalWeeks} 周 · ${store.courses().size} 门课"
        // 版本号从 ScheduleFormat.VERSION 取，不写死在 strings.xml 里 ——
        // 写死的话格式升级时这行必然被忘掉（实际上它就一直停在 v1）
        binding.tvDataDesc.text =
            getString(R.string.data_desc, ScheduleFormat.VERSION.toString())
        // 「关于」只写版本号，不写学期名 —— 学期名在上面的「学期」卡里已经有了，
        // 放在这里会让人以为它是版本信息的一部分。
        // App 版本取 BuildConfig.VERSION_NAME（跟 build.gradle 走），
        // 数据格式版本取 ScheduleFormat.VERSION（跟 JSON 结构走），两个不是一回事。
        binding.tvFooter.text =
            "Time Machine v${BuildConfig.VERSION_NAME} · 数据格式 v${ScheduleFormat.VERSION}"

        // ---- 外观 ----
        binding.tvThemeStatus.text = "${AppTheme.from(store.themeKey).label} · 点此切换"

        val custom = store.templates().custom
        val config = store.dayTypeConfig()
        val names = store.dayTypeNames()

        // 这行要同时说清两件事，因为它们容易被混为一谈：
        //
        //   模板     —— 每种日型具体长什么样
        //   日型配置 —— 哪些日型存在、星期几用哪套、有早八优先用哪套
        //
        // 只显示「使用内置（A 型 / B 型 / 周六 / 周日）」是不够的：
        // 用户会以为四套都在生效，实际只有被映射到的那几种会用到（v5）。
        //
        // 日型显示用名字（dayTypeName，含用户改过的自定义名）而不是 id ——
        // 改配置没有编辑入口、只能导入 JSON，但 v5 的键已经是 id 了，
        // 把 id 念给用户听没有信息量。
        binding.tvTemplateState.text = buildString {
            append(
                if (custom.isEmpty()) "作息模板：内置"
                else "作息模板：已自定义 ${custom.size} 种 · 点此恢复"
            )
            append('\n')
            append("日型：")
            append(
                Templates.ALL_TYPES.filter { it in config.types }
                    .filter { config.weekdaysOf(it).isNotEmpty() }
                    .joinToString("；") { t ->
                        dayTypeName(t, names) + "←" +
                                config.weekdaysOf(t).joinToString("、") { weekdayCn(it) }
                    }
            )
            append('\n')
            append(
                if (config.earlyType != null) "早八优先：${dayTypeName(config.earlyType, names)}"
                else "不区分早八"
            )
        }
    }

    /** 课表一变，通知内容、下一个闹钟时刻、课程闹钟都变了，必须重排 */
    private fun rescheduleNotification() {
        val ctx = requireContext()
        if (store.enabled) {
            Notifier.update(ctx)
            AlarmScheduler.schedule(ctx)
        }
        // 用户闹钟是另一条链，且和「常驻开关」无关 —— 导入课表后课程闹钟
        // 的绑定和时刻都可能变了，必须单独重排一次
        AlarmScheduler.scheduleAlarms(ctx)
    }

    private fun setPerm(
        status: android.widget.TextView, button: android.widget.Button,
        ok: Boolean, okText: String, badText: String
    ) {
        status.text = if (ok) "✅ $okText" else "⚠️ $badText"
        status.setTextColor(
            if (ok) ContextCompat.getColor(requireContext(), R.color.text_secondary)
            else requireContext().accentColor()
        )
        button.visibility = if (ok) View.GONE else View.VISIBLE
    }

    // ==================================================================
    //  课程列表
    // ==================================================================

    private fun renderCourses() {
        val container = binding.courseContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val courses = store.courses()

        for (c in courses) {
            val row = ItemCourseCheckBinding.inflate(inflater, container, false)
            row.tvCourseName.text = c.name
            row.tvCourseMeta.text = "${weekdayCn(c.dayOfWeek)} 第 ${c.startNode}-${c.endNode} 节 · " +
                    "${c.place.ifBlank { "教室未注明" }} · ${c.weeks.size} 周"

            // 先清空监听再赋值，避免复用时触发上一次的监听
            row.cbCourse.setOnCheckedChangeListener(null)
            row.cbCourse.isChecked = c.enabled
            row.cbCourse.setOnCheckedChangeListener { _, checked ->
                val list = store.courses()
                val idx = list.indexOfFirst { it.id == c.id }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(enabled = checked)
                    store.saveCourses(list)
                }
            }
            container.addView(row.root)
        }
    }

    // ==================================================================
    //  周次覆盖
    // ==================================================================

    private fun changeWeekOverride(delta: Int, reset: Boolean = false) {
        val current = store.weekOf(LocalDate.now())
        if (reset) {
            store.weekOverride = 0
        } else {
            val base = if (store.weekOverride > 0) store.weekOverride else current
            store.weekOverride = (base + delta).coerceIn(1, store.totalWeeks)
        }
        refresh()
    }

    // ==================================================================
    //  学期设置
    // ==================================================================

    /**
     * 选学期起始日。
     *
     * ## 一个刻意的宽容设计：自动对齐到周一
     *
     * 整个时间轴是按「第 N 周 = 起始日 + (N-1)×7 天」推出来的，
     * 起始日不是周一的话每一周都会错位。JSON 导入时遇到这种情况是**报错拒绝**的，
     * 因为那里没人能当场帮你改。
     *
     * 但这里是交互式的 —— 用户就在屏幕前面，我们能直接把事情做对。
     * 所以随便他选哪天，选中之后**自动吸附到那一周的周一**，并告诉他一句。
     *
     * 同一类问题，在不同场景下应该给不同的答案：
     * 批处理场景报错，交互场景自动纠正。
     */
    private fun pickTermStart() {
        val cur = store.termStart
        val dialog = android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = LocalDate.of(year, month + 1, day)
                val monday = TimelineEngine.mondayOf(picked)

                store.termStartIso = monday.toString()
                // 改了起始日，周次全变了；手动钉死的那个周次也就没意义了，清掉
                store.weekOverride = 0

                refresh()
                renderCourses()
                rescheduleNotification()

                toast(
                    if (monday == picked) "起始日已设为 $monday"
                    else "已对齐到那一周的周一：$monday"
                )
            },
            cur.year, cur.monthValue - 1, cur.dayOfMonth
        )
        dialog.setTitle("选择教学第 1 周的周一")
        dialog.show()
    }

    private fun changeTotalWeeks(delta: Int) {
        val next = (store.totalWeeks + delta)
            .coerceIn(1, ScheduleFormat.MAX_WEEK_LIMIT)

        if (next == store.totalWeeks) {
            toast("总周数范围是 1–${ScheduleFormat.MAX_WEEK_LIMIT}")
            return
        }

        // 缩短总周数时，如果有课程排在被砍掉的周次里，得先告诉用户 ——
        // 那些课不会消失（数据还在），但再也不会出现在课表上，
        // 用户会以为课丢了。提前说清楚比事后困惑好。
        if (delta < 0) {
            val orphan = store.courses().filter { c -> c.weeks.any { it > next } }
            if (orphan.isNotEmpty()) {
                CardAlertDialogBuilder(requireContext())
                    .setTitle("有课程会被挡在学期外")
                    .setMessage(
                        "改成 $next 周后，这些课超出范围的周次不再显示：\n\n" +
                                orphan.take(5).joinToString("\n") { "· ${it.name}" } +
                                (if (orphan.size > 5) "\n（还有 ${orphan.size - 5} 门）" else "") +
                                "\n\n数据不会删除，改回去就恢复。"
                    )
                    .setPositiveButton("确定") { _, _ -> applyTotalWeeks(next) }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }
        applyTotalWeeks(next)
    }

    private fun applyTotalWeeks(value: Int) {
        store.totalWeeks = value
        // 手动钉死的周次可能超出新范围了
        if (store.weekOverride > value) store.weekOverride = 0

        refresh()
        rescheduleNotification()
    }

    // ==================================================================
    //  清空课表
    // ==================================================================

    private fun confirmClearCourses() {
        val count = store.courses().size
        if (count == 0) {
            toast("课表已经是空的")
            return
        }
        CardAlertDialogBuilder(requireContext())
            .setTitle("清空所有课程")
            .setMessage(
                "会删掉全部 $count 门课。\n\n" +
                        "建议先「导出数据」备份；内置课表随时可以恢复。"
            )
            .setPositiveButton("确认清空") { _, _ ->
                store.clearCourses()
                renderCourses()
                refresh()
                rescheduleNotification()
                toast("已清空课表")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================================================================
    //  课前提醒
    // ==================================================================

    private fun changeRemindLead(delta: Int) {
        // 用「步进列表」而不是连续加减：提醒提前量只有这么几个有意义的档位，
        // 让你从 0 一路点到 7 分钟没有意义。0 和 60 之间循环。
        val steps = listOf(0, 5, 10, 15, 20, 30)
        val cur = store.remindLead
        val idx = steps.indexOf(cur).let { if (it >= 0) it else steps.indexOfFirst { s -> s >= cur } }
        val next = (idx + delta).coerceIn(0, steps.size - 1)
        store.remindLead = steps[next]

        refresh()
        rescheduleNotification()
    }

    // ==================================================================
    //  作息模板
    // ==================================================================

    /**
     * 恢复内置作息配置。
     *
     * ## 为什么连日型配置和自定义名一起重置
     *
     * 日型配置**只能通过导入 JSON 设置，App 里没有编辑入口**。
     * 如果这个按钮只重置模板，用户导入一份自定义了映射的文件之后
     * 就再也回不到默认状态了 —— 唯一的办法是手搓一份新的 JSON 导进来。
     *
     * 而「恢复内置」这四个字在用户听来本来就该是「全都回到出厂状态」。
     * **凡是只能从某一个入口改的东西，就必须在那附近给一个出口。**
     */
    private fun resetTemplates() {
        val hasTemplates = store.hasCustomTemplates
        val hasConfig = store.hasCustomDayTypeConfig || store.hasCustomDayTypes
        val hasNames = store.hasCustomDayTypeNames

        if (!hasTemplates && !hasConfig && !hasNames) {
            toast("当前用的就是内置配置")
            return
        }

        CardAlertDialogBuilder(requireContext())
            .setTitle("恢复内置作息")
            .setMessage(
                "会丢弃导入的模板、日型配置和自定义日型名，恢复默认。\n\n" +
                        "课表不受影响。"
            )
            .setPositiveButton("恢复") { _, _ ->
                store.resetTemplates()
                store.resetDayTypeConfig()
                store.resetDayTypeNames()
                refresh()
                rescheduleNotification()
                toast("已恢复内置作息")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================================================================
    //  外观 / 主题
    // ==================================================================

    /**
     * 切换主题配色。
     *
     * ## 为什么要 recreate()
     *
     * 布局里的颜色是 `?attr/accentColor` —— 它取的是**当前主题**的值。
     * 而主题是在 Activity 启动时定下来的（`setTheme`），运行时改不了。
     *
     * 所以换主题只有一条路：**把 Activity 重建一遍**，让它重新 setTheme、
     * 重新解析所有 `?attr`。`recreate()` 就是干这个的。
     *
     * 代价是界面会闪一下 —— 这是 Android 上换主题的标准行为，各家的
     * App 都这样。想避免闪烁就得手动给每个控件重新设色，那要维护
     * 一份「哪些控件用了强调色」的清单，**加一个新控件就得记得往清单里补**，
     * 迟早会漏。用 recreate 换来的是「布局里写了 ?attr 就自动跟着变」。
     *
     * recreate 之后会**留在设置页**：MainActivity 里 `savedInstanceState != null`
     * 时不重设底部标签，FragmentManager 会自动恢复原来的 Fragment。
     * （那条逻辑本来是为「屏幕旋转」写的，这里正好也用上了。）
     *
     * ## 为什么是画板，不是一列文字
     *
     * 之前是八行「蓝色」「绿色」……选之前得先在脑子里把名字翻译成颜色。
     * 颜色本来就该用眼睛挑，所以改成八个色块直接铺开，点哪个变哪个。
     */
    private fun pickTheme() {
        val ctx = requireContext()
        val current = AppTheme.from(store.themeKey)

        // 先建对话框，再往里填色块：色块的点击要能关掉这个对话框，
        // 而那时对话框对象必须已经存在（不能反过来先建色块）
        val dialog = CardAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.theme_title))
            .setNegativeButton("取消", null)
            .create()

        val board = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), ctx.dp(2), ctx.dp(12), ctx.dp(6))
        }

        for (rowThemes in AppTheme.entries.chunked(4)) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (t in rowThemes) {
                row.addView(
                    themeCell(ctx, t, t == current) {
                        dialog.dismiss()
                        applyTheme(t)
                    }
                )
            }
            // 最后一行不满四个时补空位：不补的话剩下的格子会被拉宽，
            // 两行的圆点对不齐
            repeat(4 - rowThemes.size) {
                row.addView(View(ctx), LinearLayout.LayoutParams(0, ctx.dp(1), 1f))
            }
            board.addView(row)
        }

        board.addView(TextView(ctx).apply {
            text = "点一个色块立刻生效"
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, ctx.dp(10), 0, 0)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        })

        dialog.setView(board)
        dialog.show()
    }

    /**
     * 画板里的一个格子：一个圆点 + 色名。
     *
     * 圆点的颜色用 [themeAccent] 现取，不用 `?attr/accentColor` ——
     * 后者是「当前主题」的颜色，八个格子会涂成同一个色。
     *
     * 选中的那一圈用正文色而不是强调色：当前主题的强调色可能正好和旁边
     * 那格撞色，「选中的是哪一格」就看不出来了。
     */
    private fun themeCell(
        ctx: Context, theme: AppTheme, selected: Boolean, onPick: () -> Unit
    ): View {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            isClickable = true
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
            setOnClickListener { onPick() }
        }

        cell.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(38), ctx.dp(38))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.themeAccent(theme))
                if (selected) setStroke(
                    ctx.dp(3), ContextCompat.getColor(ctx, R.color.text_primary)
                )
            }
        })

        cell.addView(TextView(ctx).apply {
            text = theme.label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, ctx.dp(6), 0, 0)
            setTextColor(
                ContextCompat.getColor(
                    ctx, if (selected) R.color.text_primary else R.color.text_secondary
                )
            )
        })
        return cell
    }

    /** 选了颜色：存下来再整页重建（`setTheme` 只在 onCreate 之前有效，见 MainActivity） */
    private fun applyTheme(theme: AppTheme) {
        // 选了已经在用的那套 —— 不做无谓的重建（界面闪一下也是成本）
        if (theme == AppTheme.from(store.themeKey)) return
        store.themeKey = theme.key
        requireActivity().recreate()
    }

    private fun editTermName() {
        val input = EditText(requireContext()).apply {
            setText(store.termName)
            setSelection(text.length)
            val pad = (requireContext().resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        CardAlertDialogBuilder(requireContext())
            .setTitle("学期名称")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                store.termName = v.ifBlank { Store.DEFAULT_TERM_NAME }
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
