package com.yxz.timetable.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.doOnLayout
import com.yxz.timetable.R
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.SlotCourse
import com.yxz.timetable.data.Slots
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
     * @param items       这一格全学期的课，已排好序（见 SlotCourses.at）
     * @param viewedWeek  正在浏览的教学周
     * @param currentWeek 正在浏览的是不是真实本周（决定徽标写「本周」还是「第 N 周」）
     */
    fun show(
        anchor: View,
        day: Int,
        nodes: IntRange,
        items: List<SlotCourse>,
        set: TemplateSet,
        viewedWeek: Int,
        currentWeek: Boolean
    ): Dialog {
        val ctx = anchor.context
        val inflater = LayoutInflater.from(ctx)
        val binding = DialogSlotCoursesBinding.inflate(inflater)

        binding.tvTitle.text = "${weekdayCn(day)} · ${nodeLabel(nodes.first, nodes.last)}"
        val activeCount = items.count { it.activeInWeek != null }
        binding.tvSubtitle.text = "全学期 ${items.size} 门课 · " +
                if (activeCount == 0) "第 $viewedWeek 教学周这一格没课"
                else "第 $viewedWeek 教学周有 $activeCount 门"

        for ((i, item) in items.withIndex()) {
            val row = ItemCourseCardBinding.inflate(inflater, binding.list, false)
            bindItem(row, item, set, currentWeek)
            // 最后一张不留外边距：卡片底边和面板底边的间距才是同一个
            if (i == items.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.list.addView(row.root)
        }

        // 进场前先缩到最小并透明 —— 不留「先闪一帧原尺寸」的空隙
        binding.card.scaleX = 0.2f
        binding.card.scaleY = 0.2f
        binding.card.alpha = 0f

        val dialog = CardDialog(ctx, binding.card)
        dialog.setContentView(binding.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.4f)
            setGravity(Gravity.CENTER)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(R.style.CourseCardWindowAnim)
        }

        // 面板自己要接住点击：不然点在卡片上会一路穿透到根布局的「点外面收起」，
        // 读着读着随手一碰就关了。
        binding.card.setOnClickListener { /* 只消费，不做动作 */ }
        binding.root.setOnClickListener { dialog.dismiss() }

        val maxList =
            (anchor.resources.displayMetrics.heightPixels * LIST_MAX_SCREEN_RATIO).roundToInt()
        binding.list.doOnLayout { list ->
            if (list.height > maxList) {
                binding.scroll.layoutParams = binding.scroll.layoutParams.apply { height = maxList }
            }
        }
        binding.card.doOnLayout { popIn(it, anchor) }
        dialog.show()
        return dialog
    }

    /**
     * 填一张卡。
     *
     * 空教室 / 空老师都写「未注明」而不是留白 —— 空白分不清「没有」和「没加载出来」。
     */
    private fun bindItem(
        row: ItemCourseCardBinding, item: SlotCourse, set: TemplateSet, currentWeek: Boolean
    ) {
        val c = item.course
        row.tvName.text = c.name

        when {
            !c.enabled -> {
                row.tvBadge.text = "已停课"
                row.tvBadge.alpha = 0.6f
                row.tvBadge.visibility = View.VISIBLE
            }
            item.activeInWeek != null -> {
                row.tvBadge.text = if (currentWeek) "本周" else "第 ${item.activeInWeek} 周"
                row.tvBadge.alpha = 1f
                row.tvBadge.visibility = View.VISIBLE
            }
            else -> row.tvBadge.visibility = View.GONE
        }

        row.tvWhen.text = "${weekdayCn(c.dayOfWeek)} · ${nodeLabel(item.fromNode, item.toNode)}"
        row.tvTime.text =
            "${Slots.fmt(set.slotStart(item.fromNode))} – ${Slots.fmt(set.slotEnd(item.toNode))}"
        row.tvPlace.text = c.place.ifBlank { "未注明" }
        row.tvTeacher.text = c.teacher.ifBlank { "未注明" }
        row.tvWeeks.text = formatWeeks(c.weeks)
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

    /**
     * 弹出动画：以被点格子的中心为圆心放大，配一点回弹。
     *
     * pivot 用屏幕坐标相减得到「格子中心落在面板坐标系里的哪里」，
     * 再钳进面板实际尺寸 —— 格子在屏幕边上时也不会算出界外的 pivot。
     */
    private fun popIn(card: View, anchor: View) {
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
}

/**
 * 面板的 Dialog。唯一的存在理由是**收起也要有动画**：
 * 直接 dismiss() 的话面板瞬间蒸发，缩回格子的动作没机会播。
 *
 * 所有收场（点外面、按返回键、页面销毁）都从 dismiss() 这一个口子走，
 * 统一先缩回再真正关闭。closing 标志挡住连点两下的重复收场。
 */
private class CardDialog(context: Context, private val card: View) : Dialog(context) {

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
