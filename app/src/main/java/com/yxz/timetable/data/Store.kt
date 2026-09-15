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
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.toMutableList()
        }.getOrElse { BuiltinCourses.LIST.toMutableList() }
    }

    fun saveCourses(list: List<Course>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        sp.edit().putString("courses", arr.toString()).apply()
    }

    fun resetCourses() {
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
        saveCourses(emptyList())
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
        val custom = if (raw.isBlank()) {
            emptyMap()
        } else {
            ScheduleFormat.templatesFromJson(raw)
        }
        return TemplateSet(custom, dayTypePolicy())
    }

    fun saveTemplates(map: Map<DayType, List<Block>>) {
        sp.edit().putString("templates", ScheduleFormat.templatesToJson(map)).apply()
    }

    fun resetTemplates() {
        sp.edit().remove("templates").apply()
    }

    /** 用户是否自定义过模板 */
    val hasCustomTemplates: Boolean get() = templates().isCustomized

    // ---------------- 日型策略 ----------------

    /**
     * 这份配置实际启用哪几种日型。
     *
     * 没设置过就是 [DayTypePolicy.DEFAULT]（A + 没早八的 B + 周末）。
     */
    fun dayTypePolicy(): DayTypePolicy {
        val raw = sp.getString(KEY_DAY_TYPES, "") ?: ""
        if (raw.isBlank()) return DayTypePolicy.DEFAULT
        return ScheduleFormat.dayTypesFromJson(raw)
    }

    fun saveDayTypePolicy(policy: DayTypePolicy) {
        sp.edit().putString(KEY_DAY_TYPES, ScheduleFormat.dayTypesToJson(policy)).apply()
    }

    fun resetDayTypePolicy() {
        sp.edit().remove(KEY_DAY_TYPES).apply()
    }

    /** 用户是否显式设置过日型策略（没设过就是默认那套） */
    val hasCustomDayTypes: Boolean
        get() = !sp.getString(KEY_DAY_TYPES, "").isNullOrBlank()

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

    // ---------------- 导入 / 导出 ----------------

    /**
     * 导出。
     *
     * [includeScheduleConfig] = true 时输出**完整的作息配置**：
     * 展开后的六套模板 + 日型策略。这样导出的文件是一份能直接编辑的
     * 完整底稿 —— 想改起床时间，在那 100 多行里找到对应那一行改掉就行，
     * 不用从零写一套模板。
     *
     * ⚠️ **两个东西必须一起导出。** 只带模板不带 `dayTypes` 的话，
     * 别人导入后拿到的日型和你的不一样（同一份模板，你只启用四种，
     * 他却六种全开）——**半份配置比没有配置更容易让人困惑。**
     *
     * 参数名从 `includeTemplates` 改成了 `includeScheduleConfig`：
     * 它现在同时管模板和日型，还叫原名就是**名字在说谎**。
     * 一个名字和实际行为不符的参数，早晚会有人按名字去理解它。
     */
    fun exportJson(includeScheduleConfig: Boolean = false): String =
        ScheduleFormat.serialize(
            termName,
            termStart,
            totalWeeks,
            courses(),
            if (includeScheduleConfig) templates().expanded() else emptyMap(),
            if (includeScheduleConfig) dayTypePolicy() else null
        )

    /**
     * 「复制 JSON」按钮该不该带上作息配置。
     *
     * 判据是**模板或日型任意一个被改过**。
     *
     * ⚠️ 以前这里只判断 `hasCustomTemplates`，于是有一类配置会丢：
     * 用户导入了一份**只改 dayTypes、没改模板**的文件，此时
     * `hasCustomTemplates` 是 false，复制出来的 JSON 不含 dayTypes ——
     * 粘到别处或留作备份时，他那份日型设置就**静默消失了**。
     *
     * 这类漏判特别隐蔽：功能没报错，只是一个字段没被带上。
     * **判断「要不要带上某个东西」时，要把所有相关的来源都数一遍。**
     */
    val hasCustomScheduleConfig: Boolean
        get() = hasCustomTemplates || hasCustomDayTypes

    /** 导入时一并套用学期设置 */
    fun applyTerm(term: ScheduleFormat.Term) {
        termName = term.name
        termStartIso = term.startDate.toString()
        totalWeeks = term.totalWeeks
    }

    /** 替换模式：文件里的课表完全取代现有的 */
    fun replaceCourses(incoming: List<Course>) {
        saveCourses(renumber(incoming))
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
        var added = 0
        for (c in incoming) {
            if (seen.add(keyOf(c))) {
                existing += c
                added++
            }
        }
        saveCourses(renumber(existing))
        return added
    }

    private fun keyOf(c: Course) = "${c.name}|${c.dayOfWeek}|${c.startNode}"

    /**
     * 重新编号。
     *
     * 为什么每次导入/合并都要重编一遍？因为 id 是用来「按 id 找到某一门课」
     * 的（设置页的勾选框靠它定位）。如果导入的数据和现有数据的 id 撞了，
     * 勾选一门课会连带勾上另一门 —— 这种 bug 很难查，不如从源头保证唯一。
     */
    private fun renumber(list: List<Course>): MutableList<Course> =
        list.mapIndexed { i, c -> c.copy(id = "c%03d".format(i + 1)) }.toMutableList()

    private fun toJson(c: Course): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("dow", c.dayOfWeek)
        put("sn", c.startNode)
        put("en", c.endNode)
        put("weeks", JSONArray(c.weeks.sorted()))
        put("place", c.place)
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
            enabled = o.optBoolean("enabled", true)
        )
    }

    companion object {
        val DEFAULT_TERM_START: LocalDate = LocalDate.of(2026, 9, 7)
        const val DEFAULT_TERM_NAME = "示例大学 2026 级 · 大一上"

        /** 日型策略在 SharedPreferences 里的键 */
        private const val KEY_DAY_TYPES = "day_types"
    }
}
