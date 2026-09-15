package com.yxz.timetable.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.ScheduleFormat
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TemplateImpact
import com.yxz.timetable.data.TextDecode
import com.yxz.timetable.data.TimelineEngine
import com.yxz.timetable.databinding.FragmentSettingsBinding
import com.yxz.timetable.databinding.ItemCourseCheckBinding
import com.yxz.timetable.notify.AlarmScheduler
import com.yxz.timetable.notify.Notifier
import java.time.LocalDate

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: Store
    private var suppressSwitch = false
    private var suppressLockscreen = false

    /** 文件选择器是异步返回的，用它把「刚才选的是哪种导出」带过去 */
    private var pendingExportIncludeTemplates = false

    /**
     * 文件选择器 / 保存器。
     *
     * 这两个**必须在 Fragment 构造阶段就注册好**。registerForActivityResult
     * 要求注册发生在 Fragment 进入 STARTED 状态之前，写在 onViewCreated 里
     * 在某些机型上会抛 IllegalStateException。字段初始化器正好在构造时执行，
     * 是最安全的写法。
     *
     * 用 SAF（系统文件选择器）而不是直接读写存储路径，好处是**完全不需要
     * 任何存储权限**：用户选中哪个文件，系统就只把那个文件的访问权临时借给 App，
     * 用完即收回。所以这个 App 的权限清单里一个存储权限都没有。
     */
    private val pickJsonFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::onFilePicked) }

    private val saveJsonFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(::onFileChosen) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
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
            refresh()
        }

        binding.btnNotif.setOnClickListener { openNotificationSettings() }
        binding.btnExact.setOnClickListener { openExactAlarmSettings() }
        binding.btnBattery.setOnClickListener { openBatterySettings() }
        binding.btnAutostart.setOnClickListener { openAutostartSettings() }

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
        binding.btnExport.setOnClickListener { chooseExportScope() }
        binding.btnCopyJson.setOnClickListener { copyJsonToClipboard() }
        binding.btnFormatHelp.setOnClickListener { showFormatHelp() }

        binding.btnLockscreen.setOnClickListener { openLockScreenHelp() }
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

        binding.btnRemindMinus.setOnClickListener { changeRemindLead(-1) }
        binding.btnRemindPlus.setOnClickListener { changeRemindLead(+1) }

        binding.tvTemplateState.setOnClickListener { resetTemplates() }

        renderCourses()
    }

    override fun onResume() {
        super.onResume()
        refresh()   // 从系统设置页回来，权限状态可能已经变了
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================================================================
    //  刷新
    // ==================================================================

    private fun refresh() {
        val ctx = requireContext()

        suppressSwitch = true
        binding.swEnabled.isChecked = store.enabled
        suppressSwitch = false

        binding.tvSwitchState.text =
            if (store.enabled) "运行中" else "已关闭 · App 不会消耗任何电量"

        // ---- 1. 通知权限 ----
        val notifOk = Notifier.isAllowed(ctx)
        setPerm(binding.tvNotifStatus, binding.btnNotif, notifOk, "已允许", "未允许，通知不会出现")

        // ---- 2. 精确闹钟 ----
        val exactOk = AlarmScheduler.canExact(ctx)
        setPerm(
            binding.tvExactStatus, binding.btnExact, exactOk,
            "已允许 · 会准时切换",
            "未允许 · 通知可能晚十几分钟，建议开启"
        )

        // ---- 3. 电池优化 ----
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        setPerm(
            binding.tvBatteryStatus, binding.btnBattery, batteryOk,
            "已加入白名单",
            "未加入 · 深度休眠时可能不唤醒"
        )

        // ---- 4. 锁屏显示 ----
        suppressLockscreen = true
        binding.swLockscreen.isChecked = store.lockscreenVisible
        suppressLockscreen = false

        binding.tvLockscreenStatus.text = if (!store.lockscreenVisible) {
            "已关闭 · 锁屏上不会出现通知（解锁后通知栏照常显示）"
        } else {
            "已开启 · App 愿意在锁屏显示。\n" +
                    "⚠️ 但荣耀/华为还有一道系统总开关，默认是关的 —— " +
                    "不打开它，上面这个开关开了也没用。点下面的按钮去开。"
        }

        // ---- 5. 自启动 ----
        // 这一项系统也没有提供查询接口，只能靠引导 + 用户自己确认
        binding.tvAutostartStatus.text = "请手动确认已允许自启动与后台活动"
        binding.btnAutostart.visibility = View.VISIBLE

        // ---- 课前提醒 ----
        val lead = store.remindLead
        binding.tvRemindLead.text = if (lead == 0) "关闭" else "$lead 分"
        binding.tvRemindStatus.text = when {
            lead == 0 -> "已关闭 · 不会在上课前打扰你"
            !notifOk -> "需要通知权限才能生效"
            else -> "每节课开始前 $lead 分钟响铃提醒一次"
        }

        // ---- 学期 ----
        binding.tvTermName.text = store.termName

        val start = store.termStart
        binding.tvTermStart.text =
            "$start（${weekdayCn(start.dayOfWeek.value)}）"

        val total = store.totalWeeks
        binding.tvTotalWeeks.text = "$total 周"
        // 让用户能一眼看出「今天落在第几周」，这比一个孤零零的数字有用
        val today = java.time.LocalDate.now()
        val curWeek = store.weekOf(today)
        binding.tvTotalWeeksHint.text = when {
            curWeek < 1 -> "今天是第 $curWeek 周 —— 学期还没开始"
            curWeek > total -> "今天是第 $curWeek 周 —— 已经放完了"
            else -> "今天是第 $curWeek 周"
        }

        binding.tvWeekOverride.text =
            if (store.weekOverride > 0) "第 ${store.weekOverride} 周" else "自动（第 $curWeek 周）"

        // ---- 数据 ----
        binding.tvDataSummary.text = "当前：共 ${store.totalWeeks} 周 · ${store.courses().size} 门课"
        binding.tvFooter.text = "${store.termName}\n数据格式 timetable v${ScheduleFormat.VERSION}"

        val custom = store.templates().custom
        binding.tvTemplateState.text = if (custom.isEmpty()) {
            "作息模板：使用内置（A 型 / B 型 / 周六 / 周日）"
        } else {
            "作息模板：已自定义 ${custom.size} 种 · 点此恢复内置"
        }
    }

    /** 课表一变，通知内容和下一个闹钟时刻就都变了，必须重排 */
    private fun rescheduleNotification() {
        val ctx = requireContext()
        if (store.enabled) {
            Notifier.update(ctx)
            AlarmScheduler.schedule(ctx)
        }
    }

    private fun setPerm(
        status: android.widget.TextView, button: android.widget.Button,
        ok: Boolean, okText: String, badText: String
    ) {
        status.text = if (ok) "✅ $okText" else "⚠️ $badText"
        status.setTextColor(
            ContextCompat.getColor(requireContext(), if (ok) R.color.text_secondary else R.color.accent)
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
        val current = store.weekOf(java.time.LocalDate.now())
        if (reset) {
            store.weekOverride = 0
        } else {
            val base = if (store.weekOverride > 0) store.weekOverride else current
            store.weekOverride = (base + delta).coerceIn(1, store.totalWeeks)
        }
        refresh()
    }

    // ==================================================================
    //  跳转系统设置
    // ==================================================================

    private fun openNotificationSettings() {
        val ctx = requireContext()
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        launch(intent) { openAppDetails() }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toast("当前系统版本无需此权限")
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${requireContext().packageName}"))
        launch(intent) { openAppDetails() }
    }

    /**
     * 申请加入电池优化白名单。
     *
     * 这个 Action 在部分厂商 ROM（尤其荣耀/华为/小米）上会被拦掉，
     * 所以失败时退回到「电池优化列表」页面，让用户自己去列表里找 ——
     * 少一步自动跳转，但至少不会点了没反应。
     */
    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        val ctx = requireContext()
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
        launch(direct) {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) {
                openAppDetails()
            }
        }
    }

    /**
     * 自启动管理页。
     *
     * 这个页面**没有标准 Action**，是各家 ROM 自己加的，
     * 所以只能硬编码一串厂商组件名挨个试。
     * 这是 Android 生态里出了名的脏活，属于「必须接受的不优雅」。
     * 全部失败就退回应用详情页 —— 至少用户能从这里找到「电池」「权限」入口。
     */
    private fun openAutostartSettings() {
        val candidates = listOf(
            // 荣耀 / 华为
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            // 荣耀独立后的包名
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // 小米 / OPPO / vivo
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )

        var launched = false
        for (c in candidates) {
            val ok = runCatching {
                startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) { launched = true; break }
        }
        if (!launched) {
            toast("未找到自启动管理页，已打开应用详情。请手动进入「应用启动管理」")
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${requireContext().packageName}"))
        launch(intent) { toast("无法打开系统设置") }
    }

    private fun launch(intent: Intent, onFail: () -> Unit) {
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { onFail() }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    // ==================================================================
    //  导入
    // ==================================================================

    private fun onFilePicked(uri: Uri) {
        // 读字节 -> 解码，全交给 TextDecode。
        //
        // ⚠️ 这里**不能**再写成 `runCatching { ... }.getOrNull()`。那样写有两个问题：
        //
        //   1. 失败的**真实原因被丢掉**了 —— 不管是没权限、网盘文件没下完，
        //      还是 provider 出错，用户看到的都是同一句「读取失败，或者文件是空的」，
        //      完全没法排查。
        //   2. 解码规则（BOM / GBK / 二进制）散在 UI 层就没法单测，
        //      而这次的 bug 恰恰就藏在这条规则里。
        //
        // 所以：**异常还是要捕获，但要把原因带出来；判断逻辑挪进纯函数。**
        val decoded = try {
            val stream = requireContext().contentResolver.openInputStream(uri)
            if (stream == null) {
                TextDecode.Decoded.Failed("系统没能打开这个文件（openInputStream 返回了 null）。")
            } else {
                stream.use { TextDecode.readStream(it) }
            }
        } catch (e: Exception) {
            TextDecode.Decoded.Failed(
                "读取文件时出错：${e.message ?: e.javaClass.simpleName}\n\n" +
                        "如果这个文件在网盘、微信或 SD 卡里，先把它复制到手机本机再试一次。"
            )
        }

        when (decoded) {
            is TextDecode.Decoded.Failed -> showAlert("无法读取这个文件", decoded.message)
            is TextDecode.Decoded.Ok -> applyImport(decoded.text)
        }
    }

    /** 错误弹窗统一从这里出，免得每处各写一遍 AlertDialog.Builder */
    private fun showAlert(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun applyImport(text: String) {
        when (val result = ScheduleFormat.parse(text, store.totalWeeks)) {
            // 解析失败时把错误信息直接摊给用户，不做二次包装 ——
            // 错误信息里已经写清楚了「哪一门、哪个字段、应该改成什么」
            is ScheduleFormat.Result.Failed -> showAlert("导入失败", result.message)

            is ScheduleFormat.Result.Ok -> confirmImport(result.parsed)
        }
    }

    // ==================================================================
    //  导入预览
    // ==================================================================

    /**
     * 导入前先给一份「预览 + 确认」。
     *
     * 这一步不能省：导入是要覆盖现有数据的，如果用户选错了文件
     * （比如选成了别的 App 的配置文件），没有预览就会静默毁掉他的课表。
     * **任何会覆盖用户数据的操作，都要先让他看见将要发生什么。**
     */
    private fun confirmImport(parsed: ScheduleFormat.Parsed) {
        val msg = buildString {
            val term = parsed.term
            if (term != null) {
                append("学期：").append(term.name).append('\n')
                append("起始：").append(term.startDate).append("（周一）\n")
                append("总周数：").append(term.totalWeeks).append("\n\n")
            } else {
                append("学期：文件里没写，保持不变\n\n")
            }

            val courses = parsed.courses
            if (courses == null) {
                append("课程：文件里没写，**保持不变**\n")
            } else {
                append("课程：").append(courses.size).append(" 门\n")

                // 列出前几门的**课名**，而不是只报个数字。
                //
                // 这不是锦上添花，是给「编码读错」上的第二道保险：
                // 只显示「24 门」的话，用户点确认时完全看不出课名已经变成了
                //「˼������뷨��」—— 数量是对的，一切看起来都正常。
                //
                // **预览要给「能暴露问题」的信息，而不是给「让人安心」的信息。**
                if (courses.isNotEmpty()) {
                    append("　")
                    append(courses.take(3).joinToString("、") { it.name })
                    if (courses.size > 3) append(" 等")
                    append('\n')
                }
            }

            val tmpl = parsed.templates
            if (tmpl == null) {
                append("作息模板：文件里没写，保持不变")
            } else {
                append("作息模板：将替换 ").append(tmpl.size).append(" 种日型\n")
                tmpl.keys.forEach { type ->
                    val wake = TimelineEngine.wakeMinute(tmpl.getValue(type))
                    append("· ").append(type.name)
                    if (wake != null) append("　").append(Slots.fmt(wake)).append(" 起床")
                    append('\n')
                }
            }

            if (parsed.warnings.isNotEmpty()) {
                append("\n⚠️ ").append(parsed.warnings.size).append(" 条提醒：\n")
                parsed.warnings.take(5).forEach { append("· ").append(it).append('\n') }
                if (parsed.warnings.size > 5) append("（还有更多，已省略）\n")
            }
        }

        // 文件里没带课程时，「替换 / 合并」这两个选项没有意义 ——
        // 给一个动作单一的按钮，避免用户以为自己在做选择
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("确认导入")
            .setMessage(msg)

        if (parsed.courses == null) {
            builder.setPositiveButton("应用") { _, _ -> doImport(parsed, replace = false) }
        } else {
            builder.setPositiveButton("替换现有课表") { _, _ -> doImport(parsed, replace = true) }
                .setNeutralButton("合并") { _, _ -> doImport(parsed, replace = false) }
        }
        builder.setNegativeButton("取消", null).show()
    }

    private fun doImport(parsed: ScheduleFormat.Parsed, replace: Boolean) {
        // 三段各自独立：**「没写」永远表示「不要动」，而不是「清空」**。
        // 这条规则贯穿整个导入流程，是防止一次误操作毁掉用户数据的关键。
        parsed.term?.let { store.applyTerm(it) }
        parsed.templates?.let { store.saveTemplates(it) }

        val parts = mutableListOf<String>()

        val incoming = parsed.courses
        if (incoming == null) {
            parts += "课表保持不变"
        } else if (replace) {
            store.replaceCourses(incoming)
            parts += "已导入 ${incoming.size} 门课（原课表已替换）"
        } else {
            val added = store.mergeCourses(incoming)
            parts += "合并完成：新增 $added 门，跳过 ${incoming.size - added} 门重复"
        }

        parsed.templates?.let { parts += "作息模板 ${it.size} 种" }

        renderCourses()
        refresh()
        rescheduleNotification()

        // 作息模板单独给一个说明框，不跟课表一起塞进 toast。
        //
        // 原因是这两件事的「见效时机」完全不同：
        //   换课表   → 立刻就能在主页和周课表上看到，不需要解释
        //   换作息   → **可能今天根本看不到**（见 TemplateImpact 的注释）
        //
        // 而 toast 一闪而过，装不下「为什么今天没变、哪天才会变」这种话。
        // 用户报的「显示成功但主页没反应」正是缺了这一句。
        val tmpl = parsed.templates
        if (tmpl == null) {
            toast(parts.joinToString("，"))
        } else {
            val today = LocalDate.now()
            val impact = TemplateImpact.compute(
                changed = tmpl.keys,
                today = today,
                termStart = store.termStart,
                courses = store.courses()
            )
            showAlert("导入完成", parts.joinToString("，") + "\n\n" + impact.summary(today))
        }
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
                val picked = java.time.LocalDate.of(year, month + 1, day)
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
                AlertDialog.Builder(requireContext())
                    .setTitle("有课程会被挡在学期外")
                    .setMessage(
                        "把总周数改成 $next 周后，下面这些课有部分周次超出了范围，" +
                                "那些周次不会再显示：\n\n" +
                                orphan.take(5).joinToString("\n") { "· ${it.name}" } +
                                (if (orphan.size > 5) "\n（还有 ${orphan.size - 5} 门）" else "") +
                                "\n\n课程数据不会被删除，改回去就恢复。"
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
        AlertDialog.Builder(requireContext())
            .setTitle("清空所有课程")
            .setMessage(
                "会删掉全部 $count 门课，课表变空。\n\n" +
                        "适用场景：把这个 App 给别的学校的同学用，让他从空白开始。\n\n" +
                        "⚠️ 建议先「导出课表」备份一份 —— 清空之后只能靠导入恢复，" +
                        "内置课表可以用「恢复内置课表」找回来。"
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

    private fun resetTemplates() {
        if (!store.hasCustomTemplates) {
            toast("当前用的就是内置模板")
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("恢复内置作息模板")
            .setMessage("会丢弃你导入的作息模板，重新使用 A 型 / B 型 / 周六 / 周日的内置版本。\n\n课表不受影响。")
            .setPositiveButton("恢复") { _, _ ->
                store.resetTemplates()
                refresh()
                rescheduleNotification()
                toast("已恢复内置作息模板")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================================================================
    //  锁屏显示
    // ==================================================================

    /**
     * 锁屏通知**没有标准开关**：Android 的每个应用都有一组「通知类别」设置，
     * 其中「锁屏通知」在多数 ROM 上是独立的一项，但位置各不相同。
     *
     * 这里能做的：跳到本应用的通知设置页（多数 ROM 的锁屏开关就在那一页），
     * 并把要找什么说清楚。查不到、点不准是常态，所以**说清楚比跳得准更重要**。
     */
    private fun openLockScreenHelp() {
        AlertDialog.Builder(requireContext())
            .setTitle("让通知出现在锁屏上")
            .setMessage(
                "锁屏显示受两层控制：\n\n" +
                        "【第一层 · App】\n" +
                        "就是上面那个开关，控制 App 愿不愿意把内容送到锁屏。\n\n" +
                        "【第二层 · 系统】← 多数人卡在这里\n" +
                        "荣耀 / 华为**默认不允许**第三方应用在锁屏显示通知，必须手动开：\n\n" +
                        "　设置 → 通知和状态栏 → 找到「时间规划表」→\n" +
                        "　· 打开「允许通知」\n" +
                        "　· 打开「锁屏通知」（有的机型叫「在锁屏上显示」）\n" +
                        "　· 把「此刻该做什么」这一条也打开\n\n" +
                        "点下面的按钮跳到本应用的通知设置页。\n\n" +
                        "⚠️ 系统没有提供查询这个开关的接口，所以 App 没法自动检测它开没开 —— " +
                        "只能靠你自己确认一次。"
            )
            .setPositiveButton("打开通知设置") { _, _ -> openNotificationSettings() }
            .setNeutralButton("打开应用详情") { _, _ -> openAppDetails() }
            .setNegativeButton("知道了", null)
            .show()
    }

    // ==================================================================
    //  导出
    // ==================================================================

    /**
     * 导出前问一句：只导课表，还是连作息模板一起导。
     *
     * 为什么要问？因为两者用途不一样：
     *   · **只导课表** —— 文件几十行，拿来手改课程、发给同学最合适
     *   · **带模板**   —— 文件一百多行，是完整备份；也是你**想改作息时唯一
     *                    能拿到的底稿**（不用从零手写六套模板）
     * 默认总是输出某一种都不合适，所以让用户自己选。
     */
    private fun chooseExportScope() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.export_choice_title))
            .setItems(
                arrayOf(
                    "仅课表（文件短，适合手改和分享）",
                    "课表 + 作息模板（完整备份，可改起床时间）"
                )
            ) { _, which ->
                pendingExportIncludeTemplates = (which == 1)
                saveJsonFile.launch(
                    if (which == 1) "完整备份-${store.termName}.json" else "课表-${store.termName}.json"
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onFileChosen(uri: Uri) {
        val includeTemplates = pendingExportIncludeTemplates
        val json = store.exportJson(includeTemplates)

        val ok = runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开输出流")
        }.isSuccess

        toast(
            if (ok) {
                "已导出 ${store.courses().size} 门课" + if (includeTemplates) " + 作息模板" else ""
            } else "导出失败"
        )
    }

    private fun copyJsonToClipboard() {
        val cm = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // 自定义过模板就一起带上，否则只复课表 —— 和导出按钮的默认判断保持一致
        val json = store.exportJson(store.hasCustomTemplates)
        cm.setPrimaryClip(ClipData.newPlainText("课表 JSON", json))
        toast("已复制 ${store.courses().size} 门课的 JSON")
    }

    // ==================================================================
    //  格式说明
    // ==================================================================

    private fun showFormatHelp() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("数据格式 timetable v${ScheduleFormat.VERSION}")
            .setMessage(FORMAT_HELP)
            .setPositiveButton("复制示例 JSON") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("课表示例", ScheduleFormat.exampleJson()))
                toast("示例已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun editTermName() {
        val input = EditText(requireContext()).apply {
            setText(store.termName)
            setSelection(text.length)
            val pad = (requireContext().resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(requireContext())
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

    private companion object {
        val FORMAT_HELP = """
            【顶层】
            format    固定为 "timetable"
            version   格式版本，当前是 1
            term      学期设置（可选）
            courses   课程数组（必需）

            【term 段】
            name        学期名称，随便写
            startDate   教学第 1 周的周一，YYYY-MM-DD
            totalWeeks  总周数，默认 19

            【每门课】
            name       课程名
            dayOfWeek  1–7（周一=1，周日=7）
                       也接受 "周一" 这种写法
            nodes      [起始节次, 结束节次]，节次 1–10
            weeks      周次，写法见下
            place      教室（可选）
            enabled    false 表示先停掉这门课（可选）

            【weeks 周次写法】
            "3"          第 3 周
            "2-4"        第 2 到 4 周
            "3-17/2"     单周：3、5、7…17
            "2-16/2"     双周：2、4、6…16
            "2-4,6-17"   分段
            "*"          全学期

            中文逗号「，」和波浪号「～」也会被识别。
            weeks 也可以写成数组，如 [2,3,4,6,7]。

            【设计约定】
            · 周次不能超过 totalWeeks
            · startDate 必须是周一
            · 未知字段会被忽略，所以将来标准加字段
              不会让老版本 App 出错
            · 同一时段可以有多门课，只要周次不重叠
              （比如单周一门、双周另一门）
        """.trimIndent()
    }
}
