package com.yxz.timetable.ui

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.yxz.timetable.R
import com.yxz.timetable.data.AlarmSource
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.DialogSlotCoursesBinding
import com.yxz.timetable.databinding.ItemCourseCardBinding
import com.yxz.timetable.notify.AlarmScheduler

/**
 * 课表页和今日页**共用**的那一块「点一格弹出来的面板」。
 *
 * 两处弹的东西不是同一种数据 —— 课表页是「这一格全学期有哪些课」，
 * 今日页是「此刻这一格具体是什么」。但用户看到的必须是同一套东西：
 * 同一个卡片布局、同一段「从被点的格子放大冒出来」的动画、同一个提前量选择器。
 * 所以这里只放**两边一模一样**的部分，文案由各自组装。
 *
 * 拆成独立文件的理由很实际：不拆的话这些代码会在两份里各有一遍，
 * 而其中任何一处改了（比如这次加「0 分钟」档位）另一边就会悄悄落下。
 */

/**
 * 一张卡的文案。课程卡 / 自习卡 / 今日页的时刻卡都先转成它再渲染 ——
 * **渲染只有一条路径**，两份迟早会长歪。
 *
 * 可空字段表示「这一行不显示」：自习卡没有教室，与其写「未注明」不如不写。
 */
internal data class CardText(
    val name: String,
    val whenText: String,
    val timeText: String,
    val place: String? = null,
    val teacher: String? = null,
    /** 周次那一行的标签。课程卡是「周次」，今日页的固定日程是「规律」 */
    val weeksLabel: String = "周次",
    val weeksText: String = "",
    val note: String? = null,
    val badge: String? = null,
    val badgeDim: Boolean = false
)

/** 把 [CardText] 填进一张卡。所有「空着就不显示」的判断都在这一个地方 */
internal fun ItemCourseCardBinding.bindText(t: CardText) {
    tvName.text = t.name

    if (t.badge == null) {
        tvBadge.visibility = View.GONE
    } else {
        tvBadge.text = t.badge
        tvBadge.alpha = if (t.badgeDim) 0.6f else 1f
        tvBadge.visibility = View.VISIBLE
    }

    tvWhen.text = t.whenText
    tvTime.text = t.timeText

    if (t.place == null) {
        rowPlace.visibility = View.GONE
    } else {
        tvPlace.text = t.place
        rowPlace.visibility = View.VISIBLE
    }

    if (t.teacher == null) {
        rowTeacher.visibility = View.GONE
    } else {
        tvTeacher.text = t.teacher
        rowTeacher.visibility = View.VISIBLE
    }

    if (t.note == null) {
        rowNote.visibility = View.GONE
    } else {
        tvNote.text = t.note
        rowNote.visibility = View.VISIBLE
    }

    tvWeeksLabel.text = t.weeksLabel
    tvWeeks.text = t.weeksText
}

/**
 * 面板的 Dialog。唯一的存在理由是**收起也要有动画**：
 * 直接 dismiss() 的话面板瞬间蒸发，缩回格子的动作没机会播。
 *
 * 所有收场（点外面、按返回键、页面销毁）都从 dismiss() 这一个口子走，
 * 统一先缩回再真正关闭。closing 标志挡住连点两下的重复收场。
 */
internal class CardDialog(context: Context, private val card: View) : Dialog(context) {

    private var closing = false

    override fun dismiss() {
        if (closing) return
        closing = true
        card.animate()
            .scaleX(0.2f).scaleY(0.2f).alpha(0f)
            .setDuration(160)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { super.dismiss() }
            .start()
    }
}

/** 面板窗口：透明背景、压暗 0.4、居中、宽度撑满、用 CourseCardWindowAnim 淡入淡出 */
internal fun Dialog.applyCardWindow() {
    window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setDimAmount(0.4f)
        setGravity(Gravity.CENTER)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setWindowAnimations(R.style.CourseCardWindowAnim)
    }
}

/**
 * 弹出动画：以被点格子的中心为圆心放大，配一点回弹。
 *
 * pivot 用屏幕坐标相减得到「格子中心落在面板坐标系里的哪里」，
 * 再钳进面板实际尺寸 —— 格子在屏幕边上时也不会算出界外的 pivot。
 *
 * ⚠️ 必须等布局完成之后再调（doOnLayout）：show() 的那一刻面板刚进窗口、
 * 宽高还是 0，直接算会得到 (0,0)，表现是面板从左上角飞出来。
 *
 * ⚠️ 格子和面板在**两个不同的窗口**里，各自 getLocationInWindow 的原点不一样，
 * 算 pivot 两边都得用 getLocationOnScreen —— 屏幕坐标才是同一个坐标系。
 */
internal fun popInFrom(card: View, anchor: View) {
    val cardLoc = IntArray(2)
    card.getLocationOnScreen(cardLoc)
    val anchorLoc = IntArray(2)
    anchor.getLocationOnScreen(anchorLoc)
    card.pivotX = (anchorLoc[0] + anchor.width / 2f - cardLoc[0])
        .coerceIn(0f, card.width.toFloat())
    card.pivotY = (anchorLoc[1] + anchor.height / 2f - cardLoc[1])
        .coerceIn(0f, card.height.toFloat())
    card.animate()
        .scaleX(1f).scaleY(1f).alpha(1f)
        .setDuration(260)
        .setInterpolator(OvershootInterpolator(1.1f))
        .start()
}

/** 进场前先把面板缩到最小并透明 —— 不留「先闪一帧原尺寸」的空隙 */
internal fun shrinkBeforeEntrance(root: View) {
    root.scaleX = 0.2f
    root.scaleY = 0.2f
    root.alpha = 0f
}

/** 面板外壳的公共接线：卡片自己消费点击，点空白处收起 */
internal fun wireCardDialog(dialog: Dialog, binding: DialogSlotCoursesBinding) {
    binding.card.setOnClickListener { /* 只消费，不做动作 */ }
    binding.root.setOnClickListener { dialog.dismiss() }
}

/**
 * 「提前多久提醒」选择器 —— 课表格子和今日页格子用的是同一个。
 *
 * 六档：**到点 / 1 / 5 / 10 / 20 / 自定义**。
 *
 * 为什么要有「到点」这一档：后四档是从「赶上课」倒推的（早八提前 20 分钟，
 * 临时小测提前 1 分钟）。但今日页上那些格子不是课 ——「午餐」「睡觉」
 * 「该写作业了」要的是**这一刻提醒我**，提前 5 分钟反而错位。
 * 提前量 0 是个正经档位，不是「忘了填」。
 *
 * @param dayTypeName 非 null 时多一个「只在这种日型响」的勾选框（今日页的格子才有）
 * @param lead        当前提前量，null 表示还没设过（这时预选 [defaultLead]）
 * @param defaultLead 没设过时的预选档。课默认提前 5 分钟，格子默认到点 ——
 *                    「上课铃响了才提醒」对赶课没用，而「12:00 该吃饭了」
 *                    提前 5 分钟反而错位
 */
internal object AlarmQuick {

    /** 固定档位。0 排在最前面：它是「到点」，不是「提前零分钟」 */
    val LEADS: List<Int> = listOf(0, 1, 5, 10, 20)

    fun leadLabel(minutes: Int): String =
        if (minutes == 0) "到点提醒（0 分钟）" else "提前 $minutes 分钟"

    fun pickLead(
        ctx: Context,
        title: String,
        lead: Int?,
        dayTypeName: String? = null,
        dayTypeChecked: Boolean = false,
        defaultLead: Int = 5,
        onPick: (minutes: Int, byDayType: Boolean) -> Unit
    ) {
        val selected = lead ?: defaultLead
        val pad = dp(ctx, 20)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(ctx, 4), pad, 0)
        }

        val group = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
        // 档位 → 单选框。用 generateViewId 而不是写死 id：
        // 写死的话两次弹窗之间会和 Activity 里现有的 id 撞上
        val ids = mutableMapOf<Int, Int>()
        for (m in LEADS) {
            val rb = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = leadLabel(m)
                isChecked = selected == m
                setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
            }
            ids[m] = rb.id
            group.addView(rb)
        }
        val custom = RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "自定义…"
            // 存着的提前量不在六档里（比如 30）→ 停在「自定义」上
            isChecked = selected !in LEADS
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        group.addView(custom)
        box.addView(group)

        // 「按日型」只在今日页出现：课表格子的闹钟绑在课上，日型对它没意义
        val dayTypeBox = if (dayTypeName == null) null else CheckBox(ctx).apply {
            text = "只在「$dayTypeName」响（不限于这一天）"
            isChecked = dayTypeChecked
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        dayTypeBox?.let { box.addView(it) }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        // 走「自定义」时要再弹一个输入框，所以按钮点击自己接管，
        // 让确认之后的路径只有一个出口（都汇到 onPick）
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = ids.entries.firstOrNull { it.value == group.checkedRadioButtonId }?.key
                val byType = dayTypeBox?.isChecked == true
                dialog.dismiss()
                if (chosen != null) {
                    onPick(chosen, byType)
                } else if (custom.isChecked) {
                    askCustomLead(ctx, lead) { onPick(it, byType) }
                }
            }
        }
        dialog.show()
    }

    private fun askCustomLead(ctx: Context, current: Int?, onOk: (Int) -> Unit) {
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "提前多少分钟（0–120）"
            current?.let { setText(it.toString()) }
        }
        AlertDialog.Builder(ctx)
            .setTitle("自定义提前量")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                input.text.toString().toIntOrNull()?.let { onOk(it.coerceIn(0, 120)) }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

/**
 * 课程闹钟的落库规则 —— 课表页和今日页两个卡片共用。
 *
 * **同一门课只留一个**：再设一次是改提前量，不是多出一个闹钟。
 * 这条规则必须只有一份实现：两个页面各写一遍的话，迟早会有一边忘了去重，
 * 于是同一门课出现两条闹钟、响两遍。
 */
internal object CourseAlarm {

    fun apply(ctx: Context, store: Store, courseId: String, lead: Int) {
        val list = store.alarms()
        val at = list.indexOfFirst { it.source == AlarmSource.COURSE && it.courseId == courseId }
        if (at >= 0) {
            list[at] = list[at].copy(leadMinutes = lead, enabled = true)
        } else {
            list.add(Alarms.forCourse(store.nextAlarmId(), courseId, lead))
        }
        store.saveAlarms(list)
        // 存完必须重排 —— 不排的话用户回到闹钟页看到「下次：…」还停在旧时刻
        AlarmScheduler.scheduleAlarms(ctx)
    }
}

/**
 * 把分钟数写成人话：45 -> 「45 分」，95 -> 「1 时 35 分」，120 -> 「2 时」。
 *
 * 为什么不用「1h35m」这种紧凑写法？因为这是给中文用户看的日常界面，
 * 「1 时 35 分」读起来不需要在脑子里做一次翻译。
 */
internal fun durationText(minutes: Int): String {
    if (minutes <= 0) return ""
    if (minutes < 60) return "$minutes 分"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "$h 时" else "$h 时 $m 分"
}
