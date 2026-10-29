package com.yxz.timetable.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.Festivals
import com.yxz.timetable.data.Lunar
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.FragmentCalendarBinding
import com.yxz.timetable.ui.persona.PersonaCalendarView
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历页：一个月一屏，格子形式和课表页同一套。
 *
 * ## 为什么是「自己拼的网格」而不是 RecyclerView
 *
 * 课表页（[WeekFragment]）就是一行行 LinearLayout 拼出来的：固定行高、
 * 格与格之间 1dp 分隔线、今天那一列整列高亮。日历既然以后要往里放日程
 * （一格能装几条、点开是哪一天），就得和课表用同一套格子语言 ——
 * 行高、分隔线、高亮方式都能一一对上，用户不用学两套视觉。
 * RecyclerView + GridLayoutManager 也能画线（ItemDecoration），
 * 但格子的「高亮整格」「以后往格子里塞条目」这些都要额外的行容器，
 * 不如直接拼。
 *
 * ## 为什么固定 6 行
 *
 * 一个月的天数加首日偏移需要 4~6 行。行数随月份变的话，翻月时整块网格
 * 的高度会跳；固定 6 行，多出来的格子显示下个月日期并压暗，高度恒定。
 *
 * ## 农历
 *
 * 见 [Lunar]：走系统自带的 ICU 中国历，不打包万年历表，不联网。
 */
class CalendarFragment : Fragment() {

    private var _binding: FragmentCalendarBinding? = null
    private val binding get() = _binding!!

    private var month: YearMonth = YearMonth.now()

    /** P3 彩蛋打开时才有值；普通外观下是 null。见 [showPersona3] */
    private var p3: PersonaCalendarView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val store = Store(requireContext())
        if (store.p3Mode) {
            showPersona3()
        } else {
            binding.btnPrev.setOnClickListener { showMonth(month.minusMonths(1)) }
            binding.btnNext.setOnClickListener { showMonth(month.plusMonths(1)) }
            showMonth(month)
        }
    }

    /**
     * 换上 P3 外观。
     *
     * 只换「怎么画」：月份状态、农历、节日都还用下面那套方法，
     * 自绘 View 通过回调把翻月和点日期抛回来，和普通外观走同一条路径。
     */
    private fun showPersona3() {
        val view = PersonaCalendarView(requireContext())
        binding.normalCalendar.visibility = View.GONE
        binding.p3Host.visibility = View.VISIBLE
        binding.p3Host.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        view.yearMonth = month
        view.selectedDate = LocalDate.now()
        view.onMonthStep = { delta -> showMonth(month.plusMonths(delta.toLong())) }
        view.onDayClick = { date -> view.selectedDate = date }
        p3 = view
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 从别的页切回来时重画：「今天」可能已经变了（App 在后台放了一夜）
        if (hidden) return
        val p3View = p3
        if (p3View != null) {
            p3View.invalidate()
            return
        }
        showMonth(month)
    }

    private fun showMonth(ym: YearMonth) {
        month = ym

        p3?.let { view ->
            view.yearMonth = ym
            // 翻到别的月时，选中日跟着落到 1 号 —— 否则日期条还停在「今天」的号数上，
            // 看起来像那一个月也有这一天
            if (YearMonth.from(view.selectedDate) != ym) view.selectedDate = ym.atDay(1)
            return
        }

        binding.tvMonth.text = getString(R.string.calendar_month_title, ym.year, ym.monthValue)
        buildGrid(ym)
    }

    // ------------------------------------------------------------------
    // 网格
    // ------------------------------------------------------------------

    private fun buildGrid(ym: YearMonth) {
        val ctx = requireContext()
        val container = binding.gridContainer
        container.removeAllViews()

        val first = ym.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        val today = LocalDate.now()

        val divider = ContextCompat.getColor(ctx, R.color.divider)

        for (row in 0 until 6) {
            if (row > 0) container.addView(hLine(ctx, divider))
            container.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(CELL_HEIGHT_DP)
                )
                for (col in 0 until 7) {
                    if (col > 0) addView(vLine(ctx, divider))
                    val date = start.plusDays((row * 7 + col).toLong())
                    addView(dayCell(ctx, date, YearMonth.from(date) == ym, date == today))
                }
            })
        }
    }

    /** 一格：日期 + 农历/节日，下方留白给以后的日程条目 */
    private fun dayCell(ctx: Context, date: LocalDate, inMonth: Boolean, isToday: Boolean): View {
        val lunar = Lunar.of(date)
        val festival = Festivals.of(date, lunar)

        val dim = if (inMonth) 1f else 0.35f

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(2), ctx.dp(6), ctx.dp(2), ctx.dp(4))
            // 今天整格上底色，和课表页「今天那一列」同一个思路
            if (isToday) setBackgroundColor(ctx.accentSoftColor())

            addView(TextView(ctx).apply {
                text = date.dayOfMonth.toString()
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (isToday) ctx.accentColor() else ContextCompat.getColor(ctx, R.color.text_primary))
                setTypeface(null, if (isToday) Typeface.BOLD else Typeface.NORMAL)
                alpha = dim
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            addView(TextView(ctx).apply {
                text = festival ?: lunar.shortName
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(
                    if (festival != null) ctx.accentColor()
                    else ContextCompat.getColor(ctx, R.color.text_secondary)
                )
                alpha = dim
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = ctx.dp(3) }
            })
        }
    }

    private fun hLine(ctx: Context, color: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1))
        setBackgroundColor(color)
    }

    private fun vLine(ctx: Context, color: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        setBackgroundColor(color)
    }

    private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        // p3 这个 View 挂在 p3Host 上，Fragment 的 View 销毁后必须一并丢掉，
        // 否则 fragment 实例还活着时会一直攥着一个已经没人用的 View
        p3 = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** 一格的高度：够放「日期 + 农历」两行，再留一行给以后的日程 */
        const val CELL_HEIGHT_DP = 58
    }
}
