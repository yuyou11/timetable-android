package com.yxz.timetable.ui

import android.app.Dialog
import android.content.Context
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.doOnLayout
import com.yxz.timetable.data.AlarmSource
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.Course
import com.yxz.timetable.data.DayType
import com.yxz.timetable.data.Kind
import com.yxz.timetable.data.Moment
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.dayTypeName
import com.yxz.timetable.databinding.DialogSlotCoursesBinding
import com.yxz.timetable.databinding.ItemCourseCardBinding
import com.yxz.timetable.notify.AlarmScheduler
import com.yxz.timetable.ui.persona.PersonaCardSkin
import java.time.LocalDate

/**
 * 今日页点时间线上的一格，弹出来的信息卡：**这一格到底是什么**。
 *
 * ## 为什么不是照抄课表页那张
 *
 * 课表页回答的是「这一格全学期上过什么课」，所以那边是**一门课一张卡**。
 * 今日页的时间线上，一格就是一段具体的时间 —— 有起点、有终点、有备注，
 * 可能是一节课，也可能是「午餐」「午睡（硬性）」「空档 · 机动」。
 * 所以这边**只有一张卡**，说的是这一格本身。
 *
 * 除了课表页那几行（时间 / 教室 / 老师 / 周次），这里多两样东西：
 *
 *   · **备注** —— 模板里每一格的第二行字（「周三有晚课，这觉必须睡」）。
 *     这句话只有今日页说得出，课表页的课没有备注。
 *   · **这一格属于哪套作息** —— 「A 型日模板中的这一格」。同名的格子在
 *     A / B 型日上时刻不同，不写清楚的话，用户改完闹钟会发现明天提前了十分钟。
 *
 * ## 设置闹钟：两种目标，一个按钮
 *
 * 课程格绑课程（[AlarmSource.COURSE]），其余格子绑这一格的标签
 * （[AlarmSource.MOMENT]，见 [com.yxz.timetable.data.Alarm] 顶部那段）。
 * 用户看到的都是「提前多久」一个选择器，差别只在存下去的东西。
 */
object MomentCard {

    /**
     * @param moment  被点的那一格
     * @param date    这一格属于哪一天（用来算星期几、日型）
     * @param week    教学周
     * @param course  [moment] 是课程时对应的那门课；其余为 null
     * @param dayType 当天生效的日型（决定「按日型响」那一档的条件）
     * @param dayTypeLabel 日型的显示名（可能带「· 有早八」）
     */
    fun show(
        anchor: View,
        moment: Moment,
        date: LocalDate,
        week: Int,
        course: Course?,
        dayType: DayType,
        dayTypeLabel: String,
        /** P3 自绘页给的动画圆心（屏幕坐标）；null 时按 [anchor] 的位置算 */
        pivot: RectF? = null
    ): Dialog {
        val ctx = anchor.context
        val inflater = LayoutInflater.from(ctx)
        val binding = DialogSlotCoursesBinding.inflate(inflater)
        val store = Store(ctx)
        val day = date.dayOfWeek.value

        binding.tvTitle.text = "${weekdayCn(day)} · 第 $week 周"
        binding.tvSubtitle.text = dayTypeLabel

        val row = ItemCourseCardBinding.inflate(inflater, binding.list, false)
        val dayName = weekdayCn(day)
        row.bindText(
            CardText(
                name = moment.title,
                // 有课就写节次，没课就写它属于哪套作息 ——
                // 同名的一格在 A / B 型日上的时刻不一样，不写清楚，
                // 用户改完闹钟第二天会发现它提前了十分钟
                whenText = if (course != null) {
                    "$dayName · 第 ${course.startNode}-${course.endNode} 节"
                } else {
                    "$dayName · 「$dayTypeLabel」模板"
                },
                timeText = "${Slots.fmt(moment.start)} – ${Slots.fmt(moment.end)}" +
                        " · ${durationText(moment.duration)}",
                place = moment.place.ifBlank { null },
                teacher = course?.teacher?.ifBlank { null },
                weeksLabel = if (course != null) "周次" else "规律",
                weeksText = if (course != null) {
                    formatWeeks(course.weeks)
                } else {
                    // 中文紧跟在变量后面时一定要写成 ${}：Kotlin 允许汉字做标识符，
                    // "$dayName都会…" 会被当成一个叫 dayName都会… 的变量
                    "每${dayName}都会过一遍，时刻跟着模板走"
                },
                note = moment.note.ifBlank { null },
                badge = kindLabel(moment.kind),
                badgeDim = moment.kind == Kind.FREE
            )
        )
        binding.list.addView(row.root)
        // 只有一张卡，底边距去掉才和面板底边的间距一致
        (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0

        bindAlarm(anchor = anchor, row = row, store = store, moment = moment,
            date = date, course = course, dayType = dayType)

        shrinkBeforeEntrance(binding.card)
        val dialog = CardDialog(ctx, binding.card)
        dialog.setContentView(binding.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.applyCardWindow()
        // P3 模式：和课表页的弹卡同一套皮肤（黑卡 + 青字 + 描边按钮）
        if (store.p3Mode) {
            PersonaCardSkin.apply(binding.root)
            dialog.window?.setDimAmount(0.62f)
        }
        wireCardDialog(dialog, binding)
        binding.card.doOnLayout { card ->
            if (pivot != null) popInFrom(card, pivot) else popInFrom(card, anchor)
        }
        dialog.show()
        return dialog
    }

    // ------------------------------------------------------------------
    //  设置闹钟
    // ------------------------------------------------------------------

    private fun bindAlarm(
        anchor: View, row: ItemCourseCardBinding, store: Store, moment: Moment,
        date: LocalDate, course: Course?, dayType: DayType
    ) {
        val ctx = anchor.context
        val days = setOf(date.dayOfWeek.value)

        // 课程格：绑课程本身。这样第 5 周这门课不上，闹钟自然不响，
        // 而且课表改了时刻它也跟着改 —— 比按标签匹配更准
        val existingCourse = course?.let { c ->
            store.alarms().firstOrNull {
                it.source == AlarmSource.COURSE && it.courseId == c.id
            }
        }
        // 其余格子：绑这一格的标签（标题 + 性质）
        val existingMoment = if (course == null) {
            store.alarms().firstOrNull {
                it.source == AlarmSource.MOMENT &&
                        it.momentTitle == moment.title &&
                        it.momentKind == moment.kind
            }
        } else {
            null
        }

        val lead = existingCourse?.leadMinutes ?: existingMoment?.leadMinutes
        // 措辞跟着目标走：课说「课前」，其余格子说「提前」。
        // 「课前 0 分钟」是句病话，0 一律说「到点提醒」
        fun leadText(m: Int): String = when {
            m == 0 -> "到点提醒"
            course != null -> "课前 $m 分钟"
            else -> "提前 $m 分钟"
        }

        row.btnAlarm.visibility = View.VISIBLE
        row.btnAlarm.text = lead?.let { "${leadText(it)} · 点此修改" } ?: "设置闹钟"

        row.btnAlarm.setOnClickListener {
            AlarmQuick.pickLead(
                ctx = ctx,
                title = "${moment.title}：提前多久提醒",
                lead = lead,
                // 课程格的闹钟按课表算，日型对它没意义
                dayTypeName = if (course == null) momentDayTypeName(store, dayType) else null,
                dayTypeChecked = existingMoment?.momentDayType != null,
                defaultLead = if (course != null) 5 else 0
            ) { minutes, byDayType ->
                if (course != null) {
                    applyCourseAlarm(ctx, store, course.id, minutes)
                } else {
                    applyMomentAlarm(ctx, store, moment, days, dayType, minutes, byDayType)
                }
                row.btnAlarm.text = "${leadText(minutes)} · 点此修改"
            }
        }
    }

    /**
     * 课程闹钟：去重规则在 [CourseAlarm]（课表页共用那份），这里只管提示。
     */
    private fun applyCourseAlarm(ctx: Context, store: Store, courseId: String, lead: Int) {
        CourseAlarm.apply(ctx, store, courseId, lead)
        Toast.makeText(
            ctx, "已加入闹钟页：${leadTextFor(lead, true)}，仅在这门课有课时响", Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 「按标签响」的闹钟。
     *
     * 同一个标签只留一个：再设一次是改提前量 / 改响铃范围，不是多出一条。
     * 匹配只比「标题 + 性质」—— 日型是可以改的，把它算进身份的话，
     * 用户勾一下「按日型」就会多出一条，而不是改掉原来那条。
     */
    private fun applyMomentAlarm(
        ctx: Context, store: Store, moment: Moment, days: Set<Int>,
        dayType: DayType, lead: Int, byDayType: Boolean
    ) {
        val list = store.alarms()
        val at = list.indexOfFirst {
            it.source == AlarmSource.MOMENT &&
                    it.momentTitle == moment.title &&
                    it.momentKind == moment.kind
        }
        // 勾了「按日型」时判断只看日型（见 Alarms.fireMinuteOfDay），
        // 但 days 照样存着 —— 取消按日型之后要退回「每周三」，不能变成「每天」
        val built = Alarms.forMoment(
            id = if (at >= 0) list[at].id else store.nextAlarmId(),
            title = moment.title,
            kind = moment.kind,
            days = days,
            dayType = if (byDayType) dayType else null,
            leadMinutes = lead
        )
        if (at >= 0) {
            // 保留用户可能已经改过的铃声 / 节假日设置
            list[at] = list[at].copy(
                days = built.days,
                leadMinutes = built.leadMinutes,
                momentDayType = built.momentDayType,
                enabled = true
            )
        } else {
            list.add(built)
        }
        store.saveAlarms(list)
        AlarmScheduler.scheduleAlarms(ctx)
        val scope = if (byDayType) {
            "每个「${momentDayTypeName(store, dayType)}」"
        } else {
            "每周${weekdayCn(days.first()).removePrefix("周")}"
        }
        Toast.makeText(
            ctx, "已加入闹钟页：$scope ${leadTextFor(lead, false)}，只在时间轴上有这一格时响",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 日型名（用户改过名就跟着改，见 dayTypeName 的说明） */
    private fun momentDayTypeName(store: Store, type: DayType): String =
        dayTypeName(type, store.dayTypeNames())

    /** 「课前 5 分钟」/「提前 5 分钟」/「到点提醒」 */
    private fun leadTextFor(lead: Int, course: Boolean): String = when {
        lead == 0 -> "到点提醒"
        course -> "课前 $lead 分钟"
        else -> "提前 $lead 分钟"
    }

    // ------------------------------------------------------------------
    //  文案
    // ------------------------------------------------------------------

    /** 徽标上那几个字：这一格是「上课」还是「吃饭」「睡觉」 */
    private fun kindLabel(kind: Kind): String = when (kind) {
        Kind.SLEEP -> "睡觉"
        Kind.MEAL -> "吃饭"
        Kind.CLASS -> "上课"
        Kind.STUDY -> "自习"
        Kind.TRAIN -> "训练"
        Kind.FREE -> "机动"
        Kind.CHORE -> "事务"
        Kind.TRANSIT -> "路上"
    }

    /**
     * 把 {2,3,4,6,7,...,17} 压成「2-4, 6-17 周」。和课表页卡片同一套写法 ——
     * 同一个概念在两个页面上必须长得一样，否则用户会以为是两种东西。
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
