package com.yxz.timetable.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.ScheduleFormat
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TemplateImpact
import com.yxz.timetable.data.TemplateSet
import com.yxz.timetable.data.Templates
import com.yxz.timetable.data.TextDecode
import com.yxz.timetable.data.TimelineEngine
import com.yxz.timetable.data.dayTypeName
import java.time.LocalDate

/**
 * 数据的导入 / 导出 / 复制 / 格式说明。
 *
 * 从 SettingsFragment 拆出来：这一整块是「文件进出」的流程，只跟
 * [Store] 和系统文件选择器打交道，改完数据后通过 [onChanged] 通知
 * Fragment 刷新界面。SAF 的两个 launcher 仍注册在 Fragment 里
 * （registerForActivityResult 必须在 Fragment 进入 STARTED 前注册），
 * 这里只负责拿到 uri 之后的事。
 *
 * （注释和逻辑从 Fragment 原样随迁，未做任何改动。）
 */
class ImportExport(
    private val fragment: Fragment,
    private val store: Store,
    /** 课表/模板真的变了之后 Fragment 要做的事：renderCourses + refresh + rescheduleNotification */
    private val onChanged: () -> Unit,
    /** 用系统「新建文件」选择器发起导出，参数是要保存的文件名 */
    private val launchSaver: (String) -> Unit
) {

    private val ctx: Context get() = fragment.requireContext()

    /** 文件选择器是异步返回的，用它把「刚才选的是哪种导出」带过去 */
    private var pendingExportIncludeTemplates = false

    private fun toast(msg: String) =
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ==================================================================
    //  导入
    // ==================================================================

    fun onFilePicked(uri: Uri) {
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
            val stream = ctx.contentResolver.openInputStream(uri)
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
        AlertDialog.Builder(ctx)
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

            // 日型配置要**在模板之后**单独说一段（v5）：
            // 它决定的是「上面那些模板里，哪几种真的会被用到、哪天用哪套」——
            // 不写出来的话，用户会以为导入了几套模板就有几套在生效。
            val dtc = parsed.dayTypeConfig
            if (dtc != null) {
                append('\n')
                append("日型配置：")
                // 星期映射按「同一目标归并」念出来，比七个格子逐个念短得多
                append(
                    Templates.ALL_TYPES.filter { it in dtc.types }
                        .filter { dtc.weekdaysOf(it).isNotEmpty() }
                        .joinToString("；") { t ->
                            "${dayTypeName(t)}←" + dtc.weekdaysOf(t)
                                .joinToString("、") { ScheduleFormat.weekdayCn(it) }
                        }
                )
                append('\n')
                if (dtc.earlyType != null) {
                    append("早八优先：").append(dayTypeName(dtc.earlyType))
                } else {
                    append("不区分早八")
                }
                append('\n')
                val idle = dtc.types.filter { dtc.weekdaysOf(it).isEmpty() && it != dtc.earlyType }
                if (idle.isNotEmpty()) {
                    append("闲置不用：").append(idle.joinToString("、") { dayTypeName(it) })
                    append('\n')
                }
            }

            // 自定义日型名（v4）也单独提一句 —— 只改名的文件不发提示，
            // 用户会以为导入「成功但什么都没发生」。
            val dtn = parsed.dayTypeNames
            if (dtn != null) {
                append('\n')
                append("自定义日型名 ").append(dtn.size).append(" 个：")
                append(dtn.entries.joinToString("、") { (t, name) -> "${dayTypeName(t)}→$name" })
                append('\n')
            }

            // 节数信息决定了课表页怎么分行，带着它导入等于换一套网格结构 ——
            // 预览里不说，用户只会在课表页莫名看到行数变了。
            parsed.periods?.let {
                append("\n节次结构：").append(it).append('\n')
            }

            if (parsed.warnings.isNotEmpty()) {
                append("\n⚠️ ").append(parsed.warnings.size).append(" 条提醒：\n")
                parsed.warnings.take(5).forEach { append("· ").append(it).append('\n') }
                if (parsed.warnings.size > 5) append("（还有更多，已省略）\n")
            }

            // 节次时间的不一致要按**导入之后**的状态算。
            //
            // ⚠️ 不能用 store.templates() —— 预览是在导入生效之前弹出来的，
            // 那时候 store 里还是旧数据。这里手动拼出「导完会是什么样」，
            // 和 doImport 里那几行赋值保持同一个口径。
            //
            // 为什么要提醒：课表页的节次列只有一列时间，
            // 如果 A 型日写 08:30、B 型日写 09:00，那一列没法同时说对。
            val effective = TemplateSet(
                custom = parsed.templates ?: store.templates().custom,
                config = parsed.dayTypeConfig ?: store.dayTypeConfig()
            )
            val conflicts = effective.slotConflicts()
            if (conflicts.isNotEmpty()) {
                append("\n⚠️ 各日型的节次时间不一致，课表页只能显示其中的一种：\n")
                conflicts.take(3).forEach { append("· ").append(it).append('\n') }
                if (conflicts.size > 3) append("（还有 ${conflicts.size - 3} 处）\n")
                append("建议统一，否则课表页只显示其中一种。\n")
            }
        }

        // 文件里没带课程时，「替换 / 合并」这两个选项没有意义 ——
        // 给一个动作单一的按钮，避免用户以为自己在做选择
        val builder = AlertDialog.Builder(ctx)
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
        // 五段各自独立：**「没写」永远表示「不要动」，而不是「清空」**。
        // 这条规则贯穿整个导入流程，是防止一次误操作毁掉用户数据的关键。
        parsed.term?.let { store.applyTerm(it) }
        parsed.templates?.let { store.saveTemplates(it) }
        // v3/v4 文件的旧 dayTypes 段在解析时已经确定性迁移成了 dayTypeConfig，
        // 所以这里只需要认新配置 —— 两条来路，一个落点。
        parsed.dayTypeConfig?.let { store.saveDayTypeConfig(it) }
        parsed.dayTypeNames?.let { store.saveDayTypeNames(it) }
        parsed.periods?.let { store.periods = it }

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
        parsed.dayTypeNames?.let { parts += "自定义日型名 ${it.size} 个" }

        // 节次结构只在与默认不同时说 —— 默认结构说「已改为 442」是噪音
        val periods = parsed.periods
        if (periods != null && periods != ScheduleFormat.DEFAULT_PERIODS) {
            parts += "节次结构已改为 $periods"
        }

        onChanged()

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
                courses = store.courses(),
                config = store.dayTypeConfig()
            )
            showAlert("导入完成", parts.joinToString("，") + "\n\n" + impact.summary(today))
        }
    }

    // ==================================================================
    //  导出
    // ==================================================================

    /**
     * 导出前问一句：只导课程，还是连作息配置一起导。
     *
     * 为什么要问？因为两者用途不一样：
     *   · **只导课程** —— 文件几十行，拿来手改课程、发给同学最合适
     *   · **带作息**   —— 文件一百多行，是完整备份；也是你**想改作息时唯一
     *                    能拿到的底稿**（不用从零手写六套模板）
     * 默认总是输出某一种都不合适，所以让用户自己选。
     */
    fun chooseExportScope() {
        AlertDialog.Builder(ctx)
            .setTitle(fragment.getString(R.string.export_choice_title))
            .setItems(
                arrayOf(
                    "仅课程（文件短，适合手改和分享）",
                    // 措辞里带上「日型」：从 v3 起这一档导出的不只是模板，
                    // 还有「启用哪几种日型」。只说模板的话，
                    // 用户会以为日型设置没被备份进去
                    "课程 + 作息（模板与启用的日型，完整备份）"
                )
            ) { _, which ->
                pendingExportIncludeTemplates = (which == 1)
                launchSaver(
                    if (which == 1) "完整备份-${store.termName}.json" else "课程-${store.termName}.json"
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun onFileChosen(uri: Uri) {
        val includeConfig = pendingExportIncludeTemplates
        val json = store.exportJson(includeConfig)

        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开输出流")
        }.isSuccess

        toast(
            if (ok) {
                "已导出 ${store.courses().size} 门课" + if (includeConfig) " + 作息配置" else ""
            } else "导出失败"
        )
    }

    fun copyJsonToClipboard() {
        val cm = ctx
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // 模板**或**日型被改过就一起带上，否则只复课表。
        //
        // 这里原先只判断 hasCustomTemplates，会漏掉一类配置：
        // 用户导入了一份只改 dayTypes 的文件 → hasCustomTemplates 是 false
        // → 复制出来的 JSON 不含 dayTypes → 他的日型设置静默丢失。
        val json = store.exportJson(store.hasCustomScheduleConfig)
        cm.setPrimaryClip(ClipData.newPlainText("数据 JSON", json))
        toast("已复制 ${store.courses().size} 门课的 JSON")
    }

    // ==================================================================
    //  格式说明
    // ==================================================================

    fun showFormatHelp() {
        // 格式说明文本在 `ScheduleFormat.formatHelp()` 里 —— 它**必须能被单元测试**：
        // 放在 Fragment 里的话，JVM 单测加载不了（Fragment 依赖 Android 框架），
        // 这份「用户看到的格式说明书」就会成了唯一没人管的地方，
        // 而它恰恰最容易和真实格式脱节（实际发生过：它一直停在 v1）。
        // 现在它紧挨着解析和生成代码，并且有测试断言里面的版本号和 dayTypes
        // 说明和代码一致。
        val help = ScheduleFormat.formatHelp()
        AlertDialog.Builder(ctx)
            .setTitle("数据格式 timetable v${ScheduleFormat.VERSION}")
            .setMessage(help)
            .setPositiveButton("复制示例 JSON") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("课表示例", ScheduleFormat.exampleJson()))
                toast("示例已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
