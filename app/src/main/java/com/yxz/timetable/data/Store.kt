package com.yxz.timetable.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 本地存储。
 *
 * 用 SharedPreferences + JSON，而不是数据库（Room/SQLite）。
 * 判断依据是数据量：整个课表 19 条、设置 4 项，一辈子不会超过几百条。
 * 为这点数据引入数据库，等于为了放一本书去盖一座图书馆。
 *
 * 什么时候才该换数据库？当你要**按条件查询**（比如「找出所有第 3 周以后的课」）
 * 或者数据量上千、需要分页时。现在两条都不满足。
 */
class Store(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("timetable", Context.MODE_PRIVATE)

    // ---------------- 常驻通知开关 ----------------

    /** 总开关。关掉 = 撤掉通知 + 取消所有闹钟，App 彻底静止 */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** 暂停到这个时刻（epoch 毫秒）。0 表示没有暂停 */
    var snoozeUntil: Long
        get() = sp.getLong("snooze_until", 0L)
        set(v) = sp.edit().putLong("snooze_until", v).apply()

    // ---------------- 学期与周次 ----------------

    /**
     * 教学第 1 周的周一。
     * 依据：文档里「第 2 周 = 9.14–9.20」，往前推一周得 9.7。
     */
    var termStartIso: String
        get() = sp.getString("term_start", DEFAULT_TERM_START.toString())!!
        set(v) = sp.edit().putString("term_start", v).apply()

    /**
     * 手动指定周次。0 = 自动按日期算。
     * 为什么需要它：学校调课时，真实教学周次可能和日期对不上，
     * 这时候让你手动钉死一个周次，比改公式可靠得多。
     */
    var weekOverride: Int
        get() = sp.getInt("week_override", 0)
        set(v) = sp.edit().putInt("week_override", v).apply()

    /** 学期名称，只用于展示和导出，不参与任何计算 */
    var termName: String
        get() = sp.getString("term_name", DEFAULT_TERM_NAME)!!
        set(v) = sp.edit().putString("term_name", v).apply()

    /** 总周数。决定周次选择器的上限，也是导入时 weeks 的合法范围 */
    var totalWeeks: Int
        get() = sp.getInt("total_weeks", ScheduleFormat.DEFAULT_TOTAL_WEEKS)
        set(v) = sp.edit()
            .putInt("total_weeks", v.coerceIn(1, ScheduleFormat.MAX_WEEK_LIMIT))
            .apply()

    val termStart: LocalDate get() = LocalDate.parse(termStartIso)

    fun weekOf(date: LocalDate): Int {
        val forced = weekOverride
        if (forced > 0) return forced
        return TimelineEngine.weekOf(date, termStart)
    }

    // ---------------- 课程 ----------------

    fun courses(): MutableList<Course> {
        val raw = sp.getString("courses", null)
        if (raw == null) {
            // 第一次运行：把内置课表写进去，之后就以存储里的为准
            val list = BuiltinCourses.LIST.toMutableList()
            saveCourses(list)
            return list
        }
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Course>()
            var bad = 0
            for (i in 0 until arr.length()) {
                try {
                    out += fromJson(arr.getJSONObject(i))
                } catch (_: Exception) {
                    // 一条坏记录不该连坐整张课表 —— 跳过它，其余照常显示
                    bad++
                }
            }
            when {
                bad == 0 -> Unit
                out.isEmpty() -> noteLoadError("课表", "全部 $bad 门课解析失败")
                else -> noteLoadError("课表", "$bad 门课解析失败，已跳过")
            }
            if (out.isEmpty() && bad > 0) BuiltinCourses.LIST.toMutableList() else out
        } catch (e: Exception) {
            // ⚠️ 回退必须**留下痕迹**。以前这里静默退回内置课表，
            // 用户只会看到「我的课表怎么变成示例了」，却不知道发生了什么。
            noteLoadError("课表", e.message)
            BuiltinCourses.LIST.toMutableList()
        }
    }

    fun saveCourses(list: List<Course>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        sp.edit().putString("courses", arr.toString()).apply()
    }

    fun resetCourses() {
        disableCourseAlarms()
        sp.edit().remove("courses").apply()
    }

    /**
     * 清空所有课程。
     *
     * ⚠️ 注意这里存的是 `"[]"` 而不是 `remove("courses")`。
     *
     * 区别很关键：`remove` 之后 `courses()` 读到 null，会**重新填入内置课表** ——
     * 对「恢复内置」来说这是对的，但对「清空」来说完全是反效果：
     * 用户点了清空，19 门课又全回来了。
     *
     * 存一个空数组进去，`courses()` 读到的是 `"[]"`，解析出空列表，就真的是空的。
     * **「没有数据」和「数据是空的」是两种状态，不能混用同一个表示。**
     */
    fun clearCourses() {
        disableCourseAlarms()
        saveCourses(emptyList())
    }

    /**
     * 课表整个换掉 / 清空时，绑在课上的闹钟必须停用。
     *
     * 否则会出现一种很坑的状态：闹钟在列表里看着是「开着」的，
     * 但它绑的课已经没了，永远不响 —— 用户以为有保险，实际没有。
     */
    private fun disableCourseAlarms() {
        val list = alarms()
        var changed = false
        for (i in list.indices) {
            val a = list[i]
            if (a.source == AlarmSource.COURSE && a.enabled) {
                list[i] = a.copy(enabled = false)
                changed = true
            }
        }
        if (changed) saveAlarms(list)
    }

    // ---------------- 首次启动 ----------------

    /**
     * 是不是第一次打开这个 App。
     *
     * 判断条件要同时看两个：
     *   · `first_launch_done` 没写过 —— 说明没走过首次向导
     *   · `courses` 键不存在     —— 说明这份数据是全新的
     *
     * 第二个条件是为了**老用户升级**：他们装的是旧版本，没有 `first_launch_done`
     * 这个键，但他们的课表已经存在了。只看第一个条件的话，
     * 每次升级都会弹一次「第一次使用」，非常烦人。
     */
    val isFirstLaunch: Boolean
        get() = !sp.getBoolean("first_launch_done", false) && !sp.contains("courses")

    fun markLaunched() {
        sp.edit().putBoolean("first_launch_done", true).apply()
    }

    // ---------------- 作息模板 ----------------

    /**
     * 当前生效的模板集合。
     *
     * 存的是**用户导入的那部分**，不是展开后的完整六套。
     * 好处有两个：省空间；将来内置模板更新了（比如你调整了作息），
     * 用户没覆盖过的那几种会自动跟着更新，而不是被旧数据钉死。
     */
    /**
     * 取作息配置 = 自定义模板 + 日型策略。
     *
     * ⚠️ 两者必须一起去取。如果只取模板而策略另取，就可能出现
     * 「按 A 的策略算日子、却拿到 B 的模板」这种错配 ——
     * 所以 [TemplateSet] 把策略装在一起，从源头上不给分开传的机会。
     */
    fun templates(): TemplateSet {
        val raw = sp.getString("templates", "") ?: ""
        var failed = false
        val custom = if (raw.isBlank()) {
            emptyMap()
        } else {
            try {
                ScheduleFormat.templatesFromJson(raw, nodeCount)
            } catch (e: Exception) {
                failed = true
                noteLoadError("作息模板", e.message)
                emptyMap()
            }.also { parsed ->
                // 一份合法的模板 JSON 至少有一套模板；解析出空表说明内容坏了。
                // templatesFromJson 自己会吞异常，所以只能从这里补一刀。
                if (!failed && parsed.isEmpty()) noteLoadError("作息模板", "内容为空")
            }
        }
        return TemplateSet(custom, dayTypeConfig())
    }

    fun saveTemplates(map: Map<DayType, List<Block>>) {
        sp.edit().putString("templates", ScheduleFormat.templatesToJson(map)).apply()
    }

    fun resetTemplates() {
        sp.edit().remove("templates").apply()
    }

    /** 用户是否自定义过模板 */
    val hasCustomTemplates: Boolean get() = templates().isCustomized

    // ---------------- 日型策略 / 日型配置 ----------------

    /**
     * 【已退役】旧「启用/回落」策略（v3/v4 存储键 `day_types`）。
     *
     * 只剩一个用途：老用户升级后第一次读 [dayTypeConfig] 时，
     * 把它**确定性迁移**成新配置（见 [configFromPolicy]）。
     */
    fun dayTypePolicy(): DayTypePolicy {
        val raw = sp.getString(KEY_DAY_TYPES, "") ?: ""
        if (raw.isBlank()) return DayTypePolicy.DEFAULT
        return ScheduleFormat.dayTypesFromJson(raw)
    }

    /** 用户是否显式设置过**旧版**日型策略（没设过就是默认那套） */
    val hasCustomDayTypes: Boolean
        get() = !sp.getString(KEY_DAY_TYPES, "").isNullOrBlank()

    /**
     * 这份配置的日型系统（v5）：哪些日型存在、星期几用哪套、有早八优先用哪套。
     *
     * 读取顺序：
     *   1. `day_type_config` 键（v5 起写入）—— 宽容解析，坏了回默认；
     *   2. `day_types` 键（v4 及更早）—— 旧策略**确定性迁移**成映射
     *      （迁移结果与旧程序的判定逐日一致，见 [configFromPolicy]）；
     *   3. 什么都没有 —— [DayTypeConfig.DEFAULT]。
     */
    fun dayTypeConfig(): DayTypeConfig {
        val raw = sp.getString(KEY_DAY_TYPE_CONFIG, "") ?: ""
        if (raw.isNotBlank()) return ScheduleFormat.dayTypeConfigFromJson(raw)
        if (hasCustomDayTypes) return configFromPolicy(dayTypePolicy())
        return DayTypeConfig.DEFAULT
    }

    fun saveDayTypeConfig(config: DayTypeConfig) {
        sp.edit().putString(KEY_DAY_TYPE_CONFIG, ScheduleFormat.dayTypeConfigToJson(config)).apply()
    }

    /**
     * 恢复内置日型配置。
     *
     * ⚠️ 旧键 `day_types` 要**一起删**：留着它的话，下次读配置又会
     * 从旧键迁移出一份「自定义」结果，等于这个按钮白按了。
     */
    fun resetDayTypeConfig() {
        sp.edit().remove(KEY_DAY_TYPE_CONFIG).remove(KEY_DAY_TYPES).apply()
    }

    /** 用户是否自定义过日型配置（只看 v5 新键 —— 旧键的存在性由 [hasCustomDayTypes] 表达） */
    val hasCustomDayTypeConfig: Boolean
        get() = !sp.getString(KEY_DAY_TYPE_CONFIG, "").isNullOrBlank()

    // ---------------- 自定义日型名（v4） ----------------

    /**
     * 用户给日型起的名字，稀疏表：**只有改过名的 id 才有键**。
     * 显示统一走 [dayTypeName]（自定义优先，没改过用默认名）。
     *
     * 读取走宽容版清洗（坏键坏值跳过），存储能手改，改坏了不至于起不来程序。
     */
    fun dayTypeNames(): Map<DayType, String> =
        ScheduleFormat.dayTypeNamesFromJson(sp.getString(KEY_DAY_TYPE_NAMES, null))

    /**
     * 保存自定义日型名。
     *
     * · 与默认名相同的键会被剔除 —— 稀疏存储只记「改过的」，
     *   不然导出和 hasCustom 判断都得额外排除默认值；
     * · 空串视为「恢复这个 id 的默认名」，同样剔除。
     */
    fun saveDayTypeNames(names: Map<DayType, String>) {
        val clean = mutableMapOf<DayType, String>()
        for ((key, value) in names) {
            val text = value.trim()
            // 空串 = 恢复默认名；与**默认名**相同的键没有记录价值（对照桌面版口径）
            if (text.isEmpty() || text == key.label) continue
            // 超长直接拒绝 —— 调用方（编辑入口）应该已经拦过一轮，这里是最后防线
            if (text.length > ScheduleFormat.DAY_TYPE_NAME_MAX) {
                throw IllegalArgumentException("日型名太长（最多 ${ScheduleFormat.DAY_TYPE_NAME_MAX} 字）：$text")
            }
            clean[key] = text
        }
        sp.edit().putString(KEY_DAY_TYPE_NAMES, ScheduleFormat.dayTypeNamesToJson(clean)).apply()
    }

    /** 用户是否改过任何日型的名字 */
    val hasCustomDayTypeNames: Boolean
        get() = dayTypeNames().isNotEmpty()

    fun resetDayTypeNames() {
        sp.edit().remove(KEY_DAY_TYPE_NAMES).apply()
    }

    // ---------------- 节数信息 ----------------

    /**
     * 节数信息：学校每天「上午、下午、晚上」各有几节，如 "442"。
     *
     * 课表网格按它分行（见 [Periods.rows]），导入文件里可以带上自己学校的
     * 结构。App 里没有编辑入口，只能随导入改变 —— 和日型策略一个待遇：
     * 它是「学校的事实」，不该被手滑改掉。
     *
     * 存储里的值万一坏了（存储是能手改的），一律退回默认 "442" ——
     * 它只是显示结构，不能因为一个写错的字段让程序起不来。
     */
    var periods: String
        get() {
            val raw = sp.getString(KEY_PERIODS, null) ?: return ScheduleFormat.DEFAULT_PERIODS
            return ScheduleFormat.normalizePeriods(raw) ?: ScheduleFormat.DEFAULT_PERIODS
        }
        set(v) {
            val checked = ScheduleFormat.normalizePeriods(v)
                ?: throw IllegalArgumentException(
                    "节数信息不对：$v（应为 1-14 位数字，如 442）"
                )
            sp.edit().putString(KEY_PERIODS, checked).apply()
        }

    /**
     * 一天的小节数 = 节数信息各位之和（"442" → 10，"445" → 13）。
     * 课程、模板占位格的节次合法范围都由它决定。
     */
    val nodeCount: Int
        get() = ScheduleFormat.nodeCount(periods)

    // ---------------- 提醒 ----------------

    /**
     * 课前提醒提前几分钟。0 表示关闭。
     *
     * 存成分钟数而不是布尔值，是因为「提前多久」本来就是个量 ——
     * 用户可能想提前 5 分钟，也可能想提前 15 分钟，做成开关会把这个需求憋死。
     */
    var remindLead: Int
        get() = sp.getInt("remind_lead", 0)
        set(v) = sp.edit().putInt("remind_lead", v.coerceIn(0, 60)).apply()

    /**
     * 上一条已发出的提醒的去重键。
     *
     * 存下来而不是只放内存里，是因为这个 App 的进程**随时可能被系统回收** ——
     * 闹钟唤醒时进程是新建的，内存里什么都不剩。要塞进持久化存储，
     * 去重才会跨进程生存期有效。
     */
    var lastReminderKey: String
        get() = sp.getString("last_reminder", "")!!
        set(v) = sp.edit().putString("last_reminder", v).apply()

    // ---------------- 锁屏 ----------------

    /**
     * 是否允许通知出现在锁屏上。
     *
     * 这一项是**本 App 自己能控制的**：对应通知的 visibility 属性。
     * 关掉时用 VISIBILITY_SECRET，通知根本不会送到锁屏。
     *
     * ⚠️ 但它管不了系统那一层：荣耀/华为还有一道「锁屏通知」总开关，
     * 默认是关的，且**没有给第三方应用任何查询或修改的接口**。
     * 所以这里打开只是「App 愿意显示」，能不能真显示还得看系统那道开关。
     * 界面上必须把这点说清楚，否则用户会觉得这个开关没用。
     */
    var lockscreenVisible: Boolean
        get() = sp.getBoolean("lockscreen_visible", true)
        set(v) = sp.edit().putBoolean("lockscreen_visible", v).apply()

    // ---------------- 课表页显示 ----------------

    /**
     * 课表页周次卡片里的「近五周点阵预览」开关。
     *
     * 默认开。关掉后卡片只剩标题行 —— 有人就是想要干净的一行，
     * 预览是增值信息，不该强迫所有人看。
     */
    var weekDotsEnabled: Boolean
        get() = sp.getBoolean("week_dots_enabled", true)
        set(v) = sp.edit().putBoolean("week_dots_enabled", v).apply()

    // ---------------- 主题配色 ----------------

    /**
     * 强调色主题的 key（"blue" / "green"）。
     *
     * ⚠️ 这里**只存字符串，不认识 AppTheme 枚举**。
     *
     * 原因是 Store 在 data 层，而 AppTheme 要引用 `R.style` 才能拿到主题资源 ——
     * 那是 UI 层的东西。让 data 层去认识 style 资源，等于把两层绑死：
     * 以后想把这个 Store 复用到别的界面框架（比如电脑版那种 WebView 界面）就带不动了。
     *
     * **默认值也不在这里写** —— 空串表示「没设置过」，由 AppTheme.from() 决定
     * 默认是哪套。两处各写一个默认值的话，改了其中一处就会出现
     * 「存的时候按蓝色、读的时候按绿色」这种对不上的情况。
     */
    var themeKey: String
        get() = sp.getString("theme", "").orEmpty()
        set(v) = sp.edit().putString("theme", v).apply()

    // ---------------- 彩蛋 ----------------

    /**
     * Persona 3 彩蛋模式，默认关。
     *
     * 打开后使用 P3 外观的那几页会换皮（目前只有日历页，见
     * [com.yxz.timetable.ui.persona.PersonaCalendarView]）。
     *
     * 为什么不复用 [themeKey]：主题枚举管的是「强调色」，换的是
     * `?attr/accentColor` 那几个色值；P3 不是换色，是整页重画 —— 把两件事
     * 塞进一个字段，选择器里就会同时出现「紫色」和「P3」，用户在两种完全
     * 不同量级的东西之间做选择。分开存，主题选择器保持干净。
     */
    var p3Mode: Boolean
        get() = sp.getBoolean("p3_mode", false)
        set(v) = sp.edit().putBoolean("p3_mode", v).apply()

    // ---------------- 导入 / 导出 ----------------

    /**
     * 导出。
     *
     * [includeScheduleConfig] = true 时输出**完整的作息配置**：
     * 展开后的七套模板（含休息日）+ 日型配置 + 自定义日型名。
     * 这样导出的文件是一份能直接编辑的完整底稿 —— 想改起床时间，在那
     * 100 多行里找到对应那一行改掉就行，不用从零写一套模板。
     *
     * ⚠️ **这些东西必须一起导出。** 只带模板不带 `dayTypeConfig` 的话，
     * 别人导入后拿到的星期映射和你的不一样（同一份模板，你周二走训练日，
     * 他却按默认映射走 B 型）——**半份配置比没有配置更容易让人困惑。**
     * 自定义日型名同理：不带 `dayTypeNames`，你改的「力量日」
     * 在别人那里就叫回「B 型日 · 训练日 力量A」了。
     *
     * v5 起文件里写 `dayTypeConfig` 段、不再写旧 `dayTypes` 段
     * （旧段只在给 v3/v4 的兼容输出里出现，见 serialize 的 version 参数）。
     */
    // ---------------- 闹钟 ----------------

    /**
     * 默认铃声（URI 字符串，空 = 系统默认闹钟铃声）。每个闹钟可以单独覆盖它。
     *
     * ⚠️ 铃声是**由 App 自己播放**的，不走通知渠道。原因：Android 的通知渠道
     * 一旦创建，声音就固定了，代码再也改不了（以用户的选择为准）。
     * 想「设置里换声音」就只能自己放音 —— 这也是正经闹钟应用的做法。
     */
    var alarmSoundUri: String
        get() = sp.getString("alarm_sound", "") ?: ""
        set(v) = sp.edit().putString("alarm_sound", v).apply()

    /**
     * 贪睡中的闹钟 id 列表（空 = 没在贪睡）。
     *
     * 用列表而不是单个 id：两个闹钟设在同一分钟很常见（起床 + 吃药），
     * 它们会一起响；只记一个的话，另一个按了贪睡就永远丢了。
     * 旧版本存的是单个 `alarm_snooze_id`，这里仍读它作为迁移来源。
     */
    var snoozeAlarmIds: List<String>
        get() {
            val raw = sp.getString("alarm_snooze_ids", "") ?: ""
            if (raw.isNotBlank()) {
                return runCatching {
                    val arr = JSONArray(raw)
                    (0 until arr.length()).map { arr.getString(it) }
                }.getOrDefault(emptyList())
            }
            val legacy = sp.getString("alarm_snooze_id", "") ?: ""
            return if (legacy.isBlank()) emptyList() else listOf(legacy)
        }
        set(v) {
            sp.edit().putString("alarm_snooze_ids", JSONArray(v).toString()).apply()
        }

    /** 贪睡到什么时候（epoch 毫秒，0 = 没在贪睡） */
    var snoozeAlarmUntil: Long
        get() = sp.getLong("alarm_snooze_until", 0L)
        set(v) = sp.edit().putLong("alarm_snooze_until", v).apply()

    /**
     * 系统里**排着的下一次用户闹钟**（epoch 毫秒，0 = 没有）。
     *
     * 它不参与响铃，只有一个用途：发现「该响却没响」。
     * 手机没电、被 ROM 杀掉、或系统晚了十几分钟才放行时，闹钟会彻底沉默；
     * 没有这个记录，用户永远不知道发生了什么。
     */
    var alarmExpectedAt: Long
        get() = sp.getLong("alarm_expected_at", 0L)
        set(v) = sp.edit().putLong("alarm_expected_at", v).apply()

    var alarmExpectedLabel: String
        get() = sp.getString("alarm_expected_label", "") ?: ""
        set(v) = sp.edit().putString("alarm_expected_label", v).apply()

    /** 被判定为「错过」的那一次（epoch 毫秒，0 = 没有）。用户点掉提示后清空 */
    var missedAlarmAt: Long
        get() = sp.getLong("missed_alarm_at", 0L)
        set(v) = sp.edit().putLong("missed_alarm_at", v).apply()

    var missedAlarmLabel: String
        get() = sp.getString("missed_alarm_label", "") ?: ""
        set(v) = sp.edit().putString("missed_alarm_label", v).apply()

    /** 最近一次**真的响了**的时刻与名称（闹钟页显示「上次响铃」，24 小时内有效） */
    var lastRangAt: Long
        get() = sp.getLong("last_rang_at", 0L)
        set(v) = sp.edit().putLong("last_rang_at", v).apply()

    var lastRangLabel: String
        get() = sp.getString("last_rang_label", "") ?: ""
        set(v) = sp.edit().putString("last_rang_label", v).apply()

    /** 响铃持续几分钟后自动停（1..10，默认 3）。再长就没意义了，叫醒不是把人吵到起床 */
    var ringMinutes: Int
        get() = sp.getInt("ring_minutes", 3).coerceIn(1, 10)
        set(v) = sp.edit().putInt("ring_minutes", v.coerceIn(1, 10)).apply()

    /** 去重键「日期|闹钟id」—— 同一次响铃不因为进程被杀重新走一遍而响第二遍 */
    var lastAlarmKey: String
        get() = sp.getString("last_alarm_key", "") ?: ""
        set(v) = sp.edit().putString("last_alarm_key", v).apply()

    fun alarms(): MutableList<Alarm> {
        val raw = sp.getString("alarms", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Alarm>()
            var bad = 0
            for (i in 0 until arr.length()) {
                try {
                    out += fromAlarmJson(arr.getJSONObject(i))
                } catch (_: Exception) {
                    bad++
                }
            }
            if (bad > 0) noteLoadError("闹钟", "$bad 条解析失败，已跳过")
            out
        } catch (e: Exception) {
            noteLoadError("闹钟", e.message)
            mutableListOf()
        }
    }

    fun saveAlarms(list: List<Alarm>) {
        val arr = JSONArray()
        for (a in list) arr.put(toAlarmJson(a))
        sp.edit().putString("alarms", arr.toString()).apply()
    }

    /**
     * 下一个闹钟编号。用「现有最大号 +1」而不是「条数 +1」——
     * 后者在删掉中间一个之后会和现有 id 撞上，而闹钟是靠 id 定位的。
     */
    fun nextAlarmId(): String {
        val max = alarms().mapNotNull { it.id.removePrefix("a").toIntOrNull() }.maxOrNull() ?: 0
        return "a%03d".format(max + 1)
    }

    /** 用户手动加的放假 / 补班日（覆盖内置表，见 [HolidayTable]） */
    fun customHolidays(): MutableList<HolidayDay> {
        val raw = sp.getString("custom_holidays", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<HolidayDay>()
            var bad = 0
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    out += HolidayDay(
                        name = o.optString("name", ""),
                        date = LocalDate.parse(o.getString("date")),
                        offDay = o.optBoolean("off", true)
                    )
                } catch (_: Exception) {
                    bad++
                }
            }
            if (bad > 0) noteLoadError("节假日", "$bad 天解析失败，已跳过")
            out
        } catch (e: Exception) {
            noteLoadError("节假日", e.message)
            mutableListOf()
        }
    }

    fun saveCustomHolidays(list: List<HolidayDay>) {
        val arr = JSONArray()
        for (d in list) {
            arr.put(
                JSONObject().apply {
                    put("name", d.name)
                    put("date", d.date.toString())
                    put("off", d.offDay)
                }
            )
        }
        sp.edit().putString("custom_holidays", arr.toString()).apply()
    }

    /** 生效中的节假日表 = 内置那份 + 用户手动那份 */
    fun holidayTable(): HolidayTable = HolidayTable(customHolidays())

    // ------------------------------------------------------------------
    //  日历日程
    //
    //  存在本地（SharedPreferences 里一串 JSON），**不进课表导出** ——
    //  导出格式 v5 描述的是「课表 + 作息」，日程是这台手机上的私人安排；
    //  塞进去就得升格式版本、改电脑版的解析，收益不值当。代价是换机 / 卸载会丢，
    //  将来要做的话是在 v6 里加一个 events 段。
    // ------------------------------------------------------------------

    fun events(): MutableList<CalEvent> {
        val raw = sp.getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        val parsed = Events.parse(raw)
        if (parsed.bad > 0) noteLoadError("日程", "${parsed.bad} 条解析失败，已跳过")
        return parsed.events.toMutableList()
    }

    fun saveEvents(list: List<CalEvent>) {
        sp.edit().putString(KEY_EVENTS, Events.toJson(list)).apply()
    }

    /**
     * 下一条日程的编号。和 [nextAlarmId] 同一套：现有最大号 +1，
     * 而不是「条数 +1」—— 删掉中间一条之后，条数会撞上现有 id。
     */
    fun nextEventId(): String {
        val max = events().mapNotNull { it.id.removePrefix("e").toIntOrNull() }.maxOrNull() ?: 0
        return "e%03d".format(max + 1)
    }

    private fun toAlarmJson(a: Alarm): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("h", a.hour)
        put("m", a.minute)
        put("days", JSONArray(a.days.sorted()))
        put("enabled", a.enabled)
        put("label", a.label)
        put("sound", a.soundUri)
        put("skipHolidays", a.skipHolidays)
        put("source", a.source.name)
        put("courseId", a.courseId)
        put("lead", a.leadMinutes)
        put("snooze", a.snoozeMinutes)
        put("once", a.onceDate?.toString() ?: "")
        put("skip", a.skipDate?.toString() ?: "")
        // 按 tag 响的闹钟（v1.13.6）。旧版本读到这几个键只会当作没看见 ——
        // 它们出现在闹钟的本地存储里，不参与课表导出，所以不影响和电脑版互通
        put("moment", a.momentTitle)
        put("momentKind", a.momentKind?.name ?: "")
        put("momentDayType", a.momentDayType?.id ?: "")
    }

    private fun fromAlarmJson(o: JSONObject): Alarm {
        val arr = o.optJSONArray("days")
        val days = (0 until (arr?.length() ?: 0)).map { arr!!.getInt(it) }.toSet()
        return Alarm(
            id = o.getString("id"),
            hour = o.optInt("h", 7),
            minute = o.optInt("m", 0),
            days = days,
            enabled = o.optBoolean("enabled", true),
            label = o.optString("label", ""),
            soundUri = o.optString("sound", ""),
            skipHolidays = o.optBoolean("skipHolidays", true),
            source = runCatching { AlarmSource.valueOf(o.optString("source", "MANUAL")) }
                .getOrDefault(AlarmSource.MANUAL),
            courseId = o.optString("courseId", ""),
            leadMinutes = o.optInt("lead", 0),
            snoozeMinutes = o.optInt("snooze", Alarms.MIN_SNOOZE_MINUTES),
            onceDate = o.optString("once", "").takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            skipDate = o.optString("skip", "").takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            momentTitle = o.optString("moment", ""),
            momentKind = o.optString("momentKind", "").takeIf { it.isNotBlank() }
                ?.let { runCatching { Kind.valueOf(it) }.getOrNull() },
            // 日型按 id 存（"1"…"7"），不按枚举名 —— 名字是给人看的，
            // 而且 REST 的 id 是 "7"，和枚举顺序无关
            momentDayType = o.optString("momentDayType", "").takeIf { it.isNotBlank() }
                ?.let { id -> DayType.entries.firstOrNull { it.id == id } }
        )
    }

    fun exportJson(includeScheduleConfig: Boolean = false): String =
        ScheduleFormat.serialize(
            termName,
            termStart,
            totalWeeks,
            courses(),
            if (includeScheduleConfig) templates().expanded() else emptyMap(),
            null,
            periods,
            if (includeScheduleConfig) dayTypeNames().ifEmpty { null } else null,
            if (includeScheduleConfig) dayTypeConfig() else null
        )

    /**
     * 「复制 JSON」按钮该不该带上作息配置。
     *
     * 判据是**模板、日型配置、自定义名任意一个被改过**。
     *
     * ⚠️ 以前这里只判断 `hasCustomTemplates`，于是有一类配置会丢：
     * 用户导入了一份**只改 dayTypes、没改模板**的文件，此时
     * `hasCustomTemplates` 是 false，复制出来的 JSON 不含 dayTypes ——
     * 粘到别处或留作备份时，他那份日型设置就**静默消失了**。
     *
     * 这类漏判特别隐蔽：功能没报错，只是一个字段没被带上。
     * **判断「要不要带上某个东西」时，要把所有相关的来源都数一遍。**
     * （旧 `day_types` 键也在数 —— 它还没迁移成新配置之前，同样代表用户的自定义。）
     */
    val hasCustomScheduleConfig: Boolean
        get() = hasCustomTemplates || hasCustomDayTypeConfig ||
                hasCustomDayTypeNames || hasCustomDayTypes

    /** 导入时一并套用学期设置 */
    fun applyTerm(term: ScheduleFormat.Term) {
        termName = term.name
        termStartIso = term.startDate.toString()
        totalWeeks = term.totalWeeks
    }

    /** 替换模式：文件里的课表完全取代现有的 */
    fun replaceCourses(incoming: List<Course>) {
        // 旧课表与旧闹钟都要在重编号**之前**取出来：课程闹钟按 courseId 绑定，
        // 替换后要按自然键把它们接回新 id（见 Alarms.remapCourseAlarms）。
        val old = courses()
        val fresh = renumber(incoming)
        saveAlarms(Alarms.remapCourseAlarms(alarms(), old, fresh))
        saveCourses(fresh)
    }

    /**
     * 合并模式：只加入现有课表里没有的课，返回新增数量。
     *
     * 判重用的是「课程名 + 星期 + 起始节次」这个组合。
     * 为什么不直接用课程名？因为同一门课一周可能上两次（比如习题课和正课
     * 在不同时段），只按名字去重会把第二次误判成重复。
     */
    fun mergeCourses(incoming: List<Course>): Int {
        val existing = courses()
        val seen = existing.map { keyOf(it) }.toMutableSet()
        var next = (existing.mapNotNull { it.id.removePrefix("c").toIntOrNull() }.maxOrNull() ?: 0) + 1
        var added = 0
        for (c in incoming) {
            if (seen.add(keyOf(c))) {
                // ⚠️ 只给**新加入**的课编号，existing 的 id 一个都不动 ——
                // 以前整表重编号，课程闹钟会跟着指向别的课。
                existing += c.copy(id = "c%03d".format(next++))
                added++
            }
        }
        saveCourses(existing)
        return added
    }

    private fun keyOf(c: Course) = Alarms.courseKey(c)

    /**
     * 重新编号。
     *
     * 为什么每次导入/合并都要重编一遍？因为 id 是用来「按 id 找到某一门课」
     * 的（设置页的勾选框靠它定位）。如果导入的数据和现有数据的 id 撞了，
     * 勾选一门课会连带勾上另一门 —— 这种 bug 很难查，不如从源头保证唯一。
     */
    private fun renumber(list: List<Course>): MutableList<Course> =
        list.mapIndexed { i, c -> c.copy(id = "c%03d".format(i + 1)) }.toMutableList()

    /**
     * ⚠️ 这里曾经漏过 `teacher`：格式层（[ScheduleFormat]）加了字段，
     * Store 自己那份 JSON 没跟上 —— 导入的文件里老师读得进来，
     * 但 App 一存一读就没了。**同一个数据有两个序列化实现，就要有两处同步**，
     * 所以现在有一条测试盯着「Course 的字段数 = 这里的键数」（StoreCourseJsonTest）。
     */
    private fun toJson(c: Course): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("dow", c.dayOfWeek)
        put("sn", c.startNode)
        put("en", c.endNode)
        put("weeks", JSONArray(c.weeks.sorted()))
        put("place", c.place)
        put("teacher", c.teacher)
        put("enabled", c.enabled)
    }

    private fun fromJson(o: JSONObject): Course {
        val weeksArr = o.getJSONArray("weeks")
        val weeks = (0 until weeksArr.length()).map { weeksArr.getInt(it) }.toSet()
        return Course(
            id = o.getString("id"),
            name = o.getString("name"),
            dayOfWeek = o.getInt("dow"),
            startNode = o.getInt("sn"),
            endNode = o.getInt("en"),
            weeks = weeks,
            place = o.optString("place", ""),
            teacher = o.optString("teacher", ""),
            enabled = o.optBoolean("enabled", true)
        )
    }

    // ---------------- 读取失败记录 ----------------

    /**
     * 最近一次读取失败的对象（"课表" / "闹钟" / "作息模板" / "节假日"），null = 一切正常。
     *
     * 存进 SharedPreferences 而不是放内存字段：Store 是每次用都新建的，
     * 失败发生在哪个实例上不重要 —— 设置页新建一个 Store 也得能读到它。
     */
    val lastLoadError: String?
        get() {
            val err = sp.getString(KEY_LOAD_ERROR, null) ?: return null
            // 被用户忽略过的同一条错误不再弹；错误内容变了（换了别的对象 / 原因）会重新提示
            return if (err == sp.getString(KEY_LOAD_ERROR_DISMISSED, null)) null else err
        }

    /** 用户确认过提示之后不再显示这一条（数据本身没修，只是不再打扰） */
    fun clearLoadError() {
        val err = sp.getString(KEY_LOAD_ERROR, null) ?: return
        sp.edit().putString(KEY_LOAD_ERROR_DISMISSED, err).apply()
    }

    /** 读坏了一次，记下是谁、什么原因。同一句话不反复写盘 */
    private fun noteLoadError(what: String, detail: String?) {
        val text = "$what|${detail.orEmpty().take(120)}"
        if (sp.getString(KEY_LOAD_ERROR, null) != text) {
            sp.edit().putString(KEY_LOAD_ERROR, text).apply()
        }
    }

    companion object {
        val DEFAULT_TERM_START: LocalDate = LocalDate.of(2026, 9, 7)
        const val DEFAULT_TERM_NAME = "示例大学 2026 级 · 大一上"

        /** 旧版日型策略（启用/回落）在 SharedPreferences 里的键（v3/v4 遗留，只读不写） */
        private const val KEY_DAY_TYPES = "day_types"

        /** 日型配置（v5：types / weekdayMap / earlyType）在 SharedPreferences 里的键 */
        private const val KEY_DAY_TYPE_CONFIG = "day_type_config"

        /** 自定义日型名（v4，稀疏表）在 SharedPreferences 里的键 */
        private const val KEY_DAY_TYPE_NAMES = "day_type_names"

        /** 节数信息在 SharedPreferences 里的键 */
        private const val KEY_PERIODS = "periods"

        /** 日历日程的本地存储键（一串 JSON 数组，见 [Events]） */
        private const val KEY_EVENTS = "calendar_events"

        /** 最近一次读取失败的记录（`对象|原因`），见 [lastLoadError] */
        private const val KEY_LOAD_ERROR = "load_error"

        /** 用户忽略过的那一条错误原文 */
        private const val KEY_LOAD_ERROR_DISMISSED = "load_error_dismissed"
    }
}
