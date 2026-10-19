package com.yxz.timetable.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.doOnLayout
import com.yxz.timetable.data.Alarm
import com.yxz.timetable.data.AlarmSource
import com.yxz.timetable.data.Block
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.SlotCourse
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TemplateSet
import com.yxz.timetable.databinding.DialogSlotCoursesBinding
import com.yxz.timetable.databinding.ItemCourseCardBinding
import kotlin.math.roundToInt

/**
 * 点课表格子弹出来的「这一格全学期的课」面板：**一门课一张卡**。
 *
 * 为什么不是一张卡？因为「这一格是什么课」格子本身已经写着答案了。
 * 真正答不上来的是「第 9 周以后这一格换成什么课了」「这学期这门课一共上几周」——
 * 这些要跨周次看，而格子一次只能显示一周。
 * 所以点开的应该是一份**清单**：这一格全学期出现过几门课，各自什么时间、在哪、谁教。
 *
 * ## 空格子也能点
 *
 * 课表上的空格子有两种意思：「这一周这一格没课」和「整学期这一格都没课」。
 * 前者点开能看到别的周在这格上排了什么课；后者点开是一张**自习卡**——
 * 格子里什么都没有，但这一段时间是真实存在的，它按作息模板过。
 * 两种情况下格子本身都不加任何标记（大多数格子是空的，打满符号会很吵），
 * 只是变得可点。
 *
 * ## 动画：从哪来，回哪去
 *
 * 整个面板从**被点的那个格子**放大冒出来（pivot 落在格子中心），收起时再缩回去 ——
 * 「这张面板说的是哪一格」不用解释。弹出带一点回弹（Overshoot），
 * 收起用加速缩回，260ms / 160ms。
 * 窗口那一层只做淡入淡出（CourseCardWindowAnim），压暗的背景跟着一起渐显。
 *
 * ⚠️ pivot 必须等**布局完成之后**再算：show() 的那一刻面板刚进窗口、宽高还是 0，
 * 直接算会得到 (0,0)，表现是面板从左上角飞出来 —— 所以用 doOnLayout 接住时机。
 *
 * ⚠️ 格子和面板在**两个不同的窗口**里，各自 getLocationInWindow 的原点不一样，
 * 算 pivot 两边都得用 getLocationOnScreen —— 屏幕坐标才是同一个坐标系。
 *
 * ## 课多的时候不能盖住整屏
 *
 * 面板里那个列表单独限高（[LIST_MAX_SCREEN_RATIO]）：一天最多 14 节的课表里，
 * 同一格全学期凑出五六门课是可能的，列表不限高的话面板会长到顶天立地，
 * 用户反而失去了「我在看课表的哪一格」这个参照。
 */
object SlotCoursesCard {

    /** 卡片列表最高占屏幕高度的比例 */
    private const val LIST_MAX_SCREEN_RATIO = 0.58f

    /**
     * 弹出面板。
     *
     * @param anchor      被点的课表格子 —— 动画从它身上冒出来
     * @param day         星期几（1–7）
     * @param nodes       被点那**格**占的节次范围
     * @param items       这一格全学期的课，已排好序（见 SlotCourses.at）；空表示整学期都没课
     * @param viewedWeek  正在浏览的教学周
     * @param currentWeek 正在浏览的是不是真实本周（决定徽标写「本周」还是「第 N 周」）
     * @param selfStudy   [items] 为空时用的模板占位格（可能也为 null，见 selfStudyCard）
     */
    fun show(
        anchor: View,
        day: Int,
        nodes: IntRange,
        items: List<SlotCourse>,
        set: TemplateSet,
        viewedWeek: Int,
        currentWeek: Boolean,
        selfStudy: Block? = null
    ): Dialog {
        val ctx = anchor.context
        val inflater = LayoutInflater.from(ctx)
        val binding = DialogSlotCoursesBinding.inflate(inflater)

        binding.tvTitle.text = "${weekdayCn(day)} · ${nodeLabel(nodes.first, nodes.last)}"
        binding.tvSubtitle.text = if (items.isEmpty()) {
            "全学期没课 · 按作息模板安排"
        } else {
            val activeCount = items.count { it.activeInWeek != null }
            "全学期 ${items.size} 门课 · " + if (activeCount == 0) {
                "第 $viewedWeek 周这格没课"
            } else {
                "第 $viewedWeek 周有 $activeCount 门"
            }
        }

        val store = Store(ctx)
        val alarms = store.alarms()
        val cards = if (items.isEmpty()) {
            listOf(selfStudyCard(day, nodes, selfStudy, set))
        } else {
            items.map { courseCard(it, set, currentWeek, alarms) }
        }

        for ((i, card) in cards.withIndex()) {
            val row = ItemCourseCardBinding.inflate(inflater, binding.list, false)
            bindItem(row, card, store)
            // 最后一张不留外边距：卡片底边和面板底边的间距才是同一个
            if (i == cards.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.list.addView(row.root)
        }

        // 进场前先缩到最小并透明 —— 不留「先闪一帧原尺寸」的空隙
        shrinkBeforeEntrance(binding.card)

        val dialog = CardDialog(ctx, binding.card)
        dialog.setContentView(binding.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.applyCardWindow()

        // 面板自己要接住点击：不然点在卡片上会一路穿透到根布局的「点外面收起」，
        // 读着读着随手一碰就关了。
        wireCardDialog(dialog, binding)

        val maxList =
            (anchor.resources.displayMetrics.heightPixels * LIST_MAX_SCREEN_RATIO).roundToInt()
        binding.list.doOnLayout { list ->
            if (list.height > maxList) {
                binding.scroll.layoutParams = binding.scroll.layoutParams.apply { height = maxList }
            }
        }
        binding.card.doOnLayout { popInFrom(it, anchor) }
        dialog.show()
        return dialog
    }

    /**
     * 一张卡的全部文案。课程卡和自习卡**共用同一个渲染路径** ——
     * 两份布局迟早会长歪，而这里两者本来就只有内容不同。
     *
     * @param place    null 表示这一行不显示（自习卡没有教室，与其写「未注明」不如不写）
     * @param teacher  同上
     * @param badge    null 表示不显示徽标
     */
    private class Card(
        val name: String,
        val whenText: String,
        val timeText: String,
        val place: String?,
        val teacher: String?,
        val weeksText: String,
        val badge: String?,
        val badgeDim: Boolean = false,
        /** 课程 id —— 非空时这张卡才能设闹钟（自习卡没有课可绑，是空的） */
        val courseId: String = "",
        /** 这门课已经设过的课前提醒提前量；null = 还没设 */
        val alarmLead: Int? = null
    )

    private fun courseCard(
        item: SlotCourse, set: TemplateSet, currentWeek: Boolean, alarms: List<Alarm>
    ): Card {
        val c = item.course
        // 同一门课只留一个课程闹钟，所以按 courseId 找就够
        val existing = alarms.firstOrNull {
            it.source == AlarmSource.COURSE && it.courseId == c.id
        }
        return Card(
            name = c.name,
            whenText = "${weekdayCn(c.dayOfWeek)} · ${nodeLabel(item.fromNode, item.toNode)}",
            timeText = "${Slots.fmt(set.slotStart(item.fromNode))} – " +
                    Slots.fmt(set.slotEnd(item.toNode)),
            place = c.place.ifBlank { "未注明" },
            teacher = c.teacher.ifBlank { "未注明" },
            weeksText = formatWeeks(c.weeks),
            badge = when {
                !c.enabled -> "已停课"
                item.activeInWeek != null ->
                    if (currentWeek) "本周" else "第 ${item.activeInWeek} 周"
                else -> null
            },
            badgeDim = !c.enabled,
            courseId = c.id,
            alarmLead = existing?.leadMinutes
        )
    }

    /**
     * 整学期都没课时的那张**自习卡**。
     *
     * 文案来自 [block]：当天实际生效的日型模板里、覆盖这一节的那个占位格 ——
     * 它的备注就是这张卡的名字（「自习」「自习 / 轻松跑」「晚自习」…），
     * 和今日页、通知栏同源，所以三处说的必然是同一件事。
     *
     * [block] 为 null 时（周六周日、无课休息日的模板里没有课表格子）退回
     * 「自习」+ 内置作息时刻：宁可用一个通用名字，也不要留一张空卡。
     */
    private fun selfStudyCard(day: Int, nodes: IntRange, block: Block?, set: TemplateSet): Card {
        val start = block?.start ?: set.slotStart(nodes.first)
        val end = block?.end ?: set.slotEnd(nodes.last)
        return Card(
            name = block?.note?.takeIf { it.isNotBlank() } ?: "自习",
            whenText = "${weekdayCn(day)} · ${nodeLabel(nodes.first, nodes.last)}",
            timeText = "${Slots.fmt(start)} – ${Slots.fmt(end)}",
            place = block?.place?.takeIf { it.isNotBlank() },
            teacher = null,
            weeksText = "全学期无课",
            badge = "无课",
            badgeDim = true
        )
    }

    /** 空教室 / 空老师写「未注明」而不是留白 —— 空白分不清「没有」和「没加载出来」 */
    private fun bindItem(row: ItemCourseCardBinding, card: Card, store: Store) {
        row.bindText(
            CardText(
                name = card.name,
                whenText = card.whenText,
                timeText = card.timeText,
                place = card.place,
                teacher = card.teacher,
                weeksText = card.weeksText,
                badge = card.badge,
                badgeDim = card.badgeDim
            )
        )
        bindAlarmButton(row, card, store)
    }

    /**
     * 卡片底部的「设置闹钟」。
     *
     * 六档提前量（**0** / 1 / 5 / 10 / 20 / 自定义）—— 这是从你的实际用法倒推的：
     * 早八要提前 20 分钟（洗漱 + 走到教室），临时小测提前 1 分钟就够，
     * 而「到点」这一档是给「我就想在铃响那一刻被提醒」的情况。
     * 选完立刻写进闹钟列表并重排，用户回到闹钟页就能看到它。
     */
    private fun bindAlarmButton(row: ItemCourseCardBinding, card: Card, store: Store) {
        if (card.courseId.isEmpty()) {
            row.btnAlarm.visibility = View.GONE
            return
        }
        row.btnAlarm.visibility = View.VISIBLE
        row.btnAlarm.text = card.alarmLead?.let { "${courseLeadText(it)} · 点此修改" } ?: "设置闹钟"

        row.btnAlarm.setOnClickListener { v ->
            val ctx = v.context
            AlarmQuick.pickLead(
                ctx = ctx,
                title = "${card.name}：提前多久提醒",
                lead = card.alarmLead
            ) { m, _ -> applyCourseAlarm(ctx, store, card.courseId, m, row) }
        }
    }

    /** 「课前 5 分钟」/「到点提醒」—— 0 分钟不是「课前 0 分钟」 */
    private fun courseLeadText(lead: Int): String =
        if (lead == 0) "到点提醒" else "课前 $lead 分钟"

    /**
     * 落一个课程闹钟。
     *
     * 去重规则在 [CourseAlarm] 里（课表页和今日页共用一份），
     * 这里只管把按钮上的字和提示换成新的。
     */
    private fun applyCourseAlarm(
        ctx: Context, store: Store, courseId: String, lead: Int, row: ItemCourseCardBinding
    ) {
        CourseAlarm.apply(ctx, store, courseId, lead)
        row.btnAlarm.text = "${courseLeadText(lead)} · 点此修改"
        Toast.makeText(
            ctx, "已加入闹钟页：${courseLeadText(lead)}，仅在这门课有课时响", Toast.LENGTH_LONG
        ).show()
    }

    /** 第 1-2 节 / 第 3 节 */
    private fun nodeLabel(fromNode: Int, toNode: Int): String =
        if (toNode > fromNode) "第 $fromNode-$toNode 节" else "第 $fromNode 节"

    /**
     * 把 {2,3,4,6,7,...,17} 压成「2-4, 6-17 周」。
     * 连着的数字合成区间，跳号处断开 —— 这就是课表上那种写法的来源。
     */
    private fun formatWeeks(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return "—"
        val sorted = weeks.sorted()
        val parts = mutableListOf<String>()
        var from = sorted.first()
        var prev = from
        for (w in sorted.drop(1)) {
            if (w == prev + 1) {
                prev = w
            } else {
                parts += if (from == prev) "$from" else "$from-$prev"
                from = w; prev = w
            }
        }
        parts += if (from == prev) "$from" else "$from-$prev"
        return parts.joinToString(", ") + " 周"
    }
}

