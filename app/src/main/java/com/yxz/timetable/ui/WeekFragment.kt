package com.yxz.timetable.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.Course
import com.yxz.timetable.data.DayType
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TimelineEngine
import com.yxz.timetable.databinding.FragmentWeekBinding
import java.time.LocalDate

/**
 * 课表页。
 *
 * 网格是用代码现搭的，而不是写死在 XML 里。原因是课表的结构是「5 个节次段 × 7 天」，
 * 用代码两层循环几行就搭完了；写成 XML 得手写 40 多个格子，还改不动。
 *
 * **判断标准：结构固定、数量少 → 写 XML；结构重复、随数据变 → 用代码生成。**
 * 这条在网页开发里完全一样（手写 5 个 div 还是 v-for 循环）。
 *
 * ============================================================
 *  这个网格曾经连续踩了四个坑，改之前请先读完
 * ============================================================
 *
 * **坑 1：不要用 Unicode 符号当图标。**
 *   最初「上一周 / 下一周」按钮用的是「◀ ▶」(U+25C0/U+25B6)，
 *   在荣耀手机上完全看不见 —— 国产 ROM 的定制字体里没有这两个字形。
 *   现在用矢量图，不依赖字体。
 *
 * **坑 2：格子之间必须留间隙。**
 *   格子高度顶满整行、四周零边距时，上下相邻的蓝框会严丝合缝贴在一起，
 *   圆角被对方挤掉。每个格子留 2dp 外边距就解决了。
 *
 * **坑 3（最隐蔽）：固定行高 + 未限制行数的文字 = 文字从底部溢出。**
 *   之前给格子设了固定 76dp 高，文字却允许排 4 行以上。
 *   中文字体的行高比数字字体大不少（还有 includeFontPadding 额外加的量），
 *   4 行 10sp 中文正好把 76dp 撑满甚至撑破 —— 表现就是最后一行贴着边框，
 *   看起来像「蓝色框的下半部分被切掉了」。
 *
 *   这一版用三重保险把它堵死：
 *     a. 行高固定，格子高度用 MATCH_PARENT 撑满 —— 同一行的格子必然等高
 *     b. 课程名和教室拆成两个 TextView，各自限制行数（3 行 + 1 行）
 *     c. 关掉 includeFontPadding，让中文行高可预期
 *   现在内容最多 4 行，而格子留了接近两倍的余量，字号调大也不会溢出。
 *
 * **坑 4：列数一变，所有横向的「余量」都要重算。**
 *   从 5 列加到 7 列，屏幕总宽度**一点没变**，多出来的两列全靠从原来 5 列里挤。
 *   按 360dp 宽的常见机型算：一列从约 59dp 掉到约 36dp，格子里能放下的字
 *   从 4 个掉到 3 个 —— 这个变化不是「稍微窄一点」，是「放不下」。
 *
 *   所以加列的时候必须连着做三件事，缺一个都会出现「字被吃掉」或「排版发虚」：
 *     a. 把页面的横向 padding 从 14dp 收到 10dp、网格内 padding 从 6dp 收到 4dp，
 *        先凭空挣回 12dp（这是最划算的一步：纯留白，不牺牲任何内容）
 *     b. 把节次列的时间正文缩短到「1-2 节」，让它在 40dp 里能一行放下
 *     c. 课程名的 maxLines 从 2 放到 3 —— 一列只能排 3 个字，
 *        2 行只有 6 个字，像「中国近现代史纲要」这种 8 字课名就被截成「中国近现…」了
 *
 *   还要记住：**权重（weight）是比例，不是尺寸。** `TIME_WEIGHT` 原来 0.95，
 *   意思是「节次列拿 0.95 份，每天拿 1 份」，5 天时分母是 5.95，7 天时分母变成 7.95，
 *   节次列到手的绝对宽度**自己就缩水了**。列数变了就得重新调这个比例，
 *   否则节次列会被挤到放不下「08:30」。
 */
class WeekFragment : Fragment() {

    private var _binding: FragmentWeekBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: Store
    private var weekOffset = 0     // 相对当前周的偏移，可正可负

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWeekBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = Store(requireContext())

        binding.btnPrevWeek.setOnClickListener { weekOffset--; render() }
        binding.btnNextWeek.setOnClickListener { weekOffset++; render() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()   // 从设置页改完课程回来，课表要跟着变
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================================================================
    //  数据 -> 页面
    // ==================================================================

    private fun render() {
        val thisWeek = store.weekOf(LocalDate.now())
        val week = (thisWeek + weekOffset).coerceIn(1, store.totalWeeks)
        val courses = store.courses()

        val monday = store.termStart.plusDays(((week - 1) * 7).toLong())
        val sunday = monday.plusDays(6)

        binding.tvWeek.text =
            if (week == thisWeek) "第 $week 教学周 · 本周" else "第 $week 教学周"
        binding.tvWeekRange.text =
            "${monday.monthValue}.${monday.dayOfMonth} – ${sunday.monthValue}.${sunday.dayOfMonth}"

        // 日型提示分两行：工作日一行、周末一行。
        // 七条挤在一行会换行，而中文是「任意位置都能断」的 —— 断在「早八」中间
        // 就会变成「早」/「八」两行，看着像乱码。周末单独一行还能顺带把
        // 「这周上不上课」和「周末」在视觉上分开。
        binding.tvDayTypeHint.text =
            WEEKDAY_DAYS.joinToString("　") { dayTypeLabel(it, monday, week, courses) } +
                    "\n" +
                    WEEKEND_DAYS.joinToString("　") { dayTypeLabel(it, monday, week, courses) }

        binding.tvWeekFooter.text =
            "点蓝色格子看完整周次与教室。空白表示这一周不上这门课。"

        buildGrid(week, courses)
    }

    /**
     * 日型提示里的一个小标签，形如「一·早八」。day 是 1..7，monday 是本周周一。
     *
     * 用的是 [TimelineEngine.dayType] 而不是 naturalDayType ——
     * 显示的必须是**实际会生效**的日型。如果这里显示 B 型、
     * 实际却按 A 型的模板走，用户看到的就是一条假信息。
     */
    private fun dayTypeLabel(
        day: Int, monday: LocalDate, week: Int, courses: List<Course>
    ): String {
        val date = monday.plusDays((day - 1).toLong())
        val policy = store.dayTypePolicy()
        val natural = TimelineEngine.naturalDayType(date, week, courses)
        val used = policy.resolve(natural)
        return "${weekdayCn(day).removePrefix("周")}·${shortType(natural, used)}"
    }

    /**
     * 一两字的短标签。
     *
     * ⚠️ **不能只看 [used]**。这里原来写的是 `A -> "早八"`，
     * 那在默认配置下没问题（A 型日 ⟺ 当天有早八），
     * 但用户可以把工作日全回落到 A 型模板 —— 那时周二会被标成「早八」，
     * 而那天根本没有早课。
     *
     * 所以「早八」这个说法只在**日历确实算出 A** 时才用；
     * 光是用 A 的模板、当天却没早课时，说「A型」——
     * 同样简洁，而且是真的。
     */
    private fun shortType(natural: DayType, used: DayType): String = when (used) {
        DayType.A -> if (natural == DayType.A) "早八" else "A型"
        DayType.B_TRAIN_A -> "训A"
        DayType.B_TRAIN_B -> "训B"
        DayType.B_NORMAL -> "无早八"
        DayType.SATURDAY -> "周六"
        DayType.SUNDAY -> "周日"
    }

    // ==================================================================
    //  网格搭建
    // ==================================================================

    private val nodePairs = listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10)

    private fun buildGrid(week: Int, courses: List<Course>) {
        val ctx = requireContext()
        val container = binding.gridContainer
        container.removeAllViews()

        // ---- 表头 ----
        val header = makeRow(ctx, HEADER_HEIGHT_DP)
        header.addView(makeLabelCell("节次", TIME_WEIGHT, 11f))
        for (d in WEEK_DAYS) {
            header.addView(makeLabelCell(weekdayCn(d).removePrefix("周"), 1f, 11f))
        }
        container.addView(header)

        // 表头与内容之间的分隔线
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1)
            ).apply { setMargins(0, ctx.dp(2), 0, ctx.dp(4)) }
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider))
        })

        // ---- 5 个节次段 ----
        for ((startNode, endNode) in nodePairs) {
            val row = makeRow(ctx, ROW_HEIGHT_DP)
            row.addView(makeTimeCell(startNode, endNode))

            for (day in WEEK_DAYS) {
                val hit = courses.firstOrNull {
                    it.enabled && it.dayOfWeek == day &&
                            it.startNode == startNode && week in it.weeks
                }
                row.addView(makeCourseCell(day, startNode, endNode, hit))
            }
            container.addView(row)
        }
    }

    /**
     * 一行。**高度写死**是这个网格能对齐的关键 ——
     * 行高固定之后，格子只要用 MATCH_PARENT 就必然等高，不用再操心内容多长。
     */
    private fun makeRow(ctx: Context, heightDp: Int): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(heightDp)
            )
        }

    /**
     * 格子的尺寸参数。
     *
     * 高度用 MATCH_PARENT 而不是具体数值 —— 格子会自动撑满所在行，
     * 所以同一行里所有格子高度严格相等。宽度 0 + weight 用来按比例分配横向空间。
     *
     * 那 2dp 的外边距是坑 2 的解药，不是装饰。
     */
    private fun cellParams(weight: Float): LinearLayout.LayoutParams {
        val gap = requireContext().dp(2)
        return LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, weight
        ).apply { setMargins(gap, gap, gap, gap) }
    }

    private fun makeLabelCell(label: String, weight: Float, size: Float): TextView =
        TextView(requireContext()).apply {
            layoutParams = cellParams(weight)
            gravity = Gravity.CENTER
            text = label
            textSize = size
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            includeFontPadding = false
        }

    /**
     * 节次列的一格。
     *
     * 正文写「1-2 节」而不是「第 1-2 节」：表头那一列已经写着「节次」了，
     * 加个「第」字是重复的；而 7 列版里这一行能不能放下，
     * 差的正好就是这一个字（约 8.5dp，占这格可用宽度的四分之一）。
     * **窄容器里的文案，优先砍掉语境已经提供的信息。**
     */
    private fun makeTimeCell(startNode: Int, endNode: Int): TextView =
        TextView(requireContext()).apply {
            layoutParams = cellParams(TIME_WEIGHT)
            gravity = Gravity.CENTER
            text = "$startNode-$endNode 节\n" +
                    "${Slots.fmt(Slots.start(startNode))}\n${Slots.fmt(Slots.end(endNode))}"
            textSize = 8.5f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            includeFontPadding = false
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }

    /**
     * 一个课表格子。
     *
     * 有课时返回一个**纵向容器**，里面放两个各自限制行数的 TextView：
     *   课程名  最多 3 行
     *   教室    最多 1 行
     *
     * 为什么不用一个 TextView 加 "\n" 拼起来？因为那样没法分别限行 ——
     * 名字长的时候会挤掉教室，或者整体撑破格子。拆开之后**内容最多 4 行**，
     * 在 84dp 的行高里仍有接近两倍余量，怎么都不会溢出。
     *
     * 课程名为什么是 3 行而不是 2 行？7 列版里一列只能排 3 个汉字，
     * 2 行 = 6 个字，「中国近现代史纲要」这 8 个字就要被截断；
     * 3 行给到 9 个字，常见课名都能完整显示。
     * **限制行数的时候，要按「一行能放几个字」算，而不是随手定个 2。**
     */
    private fun makeCourseCell(
        day: Int, startNode: Int, endNode: Int, hit: Course?
    ): View {
        val ctx = requireContext()

        if (hit == null) {
            // 空格子真正留白，不打「—」占位：
            // 35 个格子里大多数是空的，全打上横杠会非常吵，反而看不清哪里有课
            return TextView(ctx).apply { layoutParams = cellParams(1f) }
        }

        val accent = ContextCompat.getColor(ctx, R.color.accent)

        return LinearLayout(ctx).apply {
            layoutParams = cellParams(1f)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(3), ctx.dp(2), ctx.dp(3), ctx.dp(2))
            setBackgroundResource(R.drawable.bg_cell_course)

            addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = hit.name
                textSize = 10f
                setTextColor(accent)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })

            if (hit.place.isNotBlank()) {
                addView(TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = ctx.dp(2) }
                    text = hit.place
                    textSize = 8.5f
                    setTextColor(accent)
                    alpha = 0.8f          // 教室比课程名淡一点，主次分明
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }

            setOnClickListener {
                Toast.makeText(
                    ctx,
                    "${hit.name}\n${weekdayCn(day)} 第 $startNode-$endNode 节\n" +
                            "教室：${hit.place.ifBlank { "未注明" }}\n" +
                            "周次：${formatWeeks(hit.weeks)}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

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

    private companion object {

        /**
         * 课表显示到星期几。**这是整个页面唯一的「几天」定义**。
         *
         * 之前这个数字以 `1..5` 的字面量散在三个地方（表头、内容行、日型提示），
         * 想加周末就得改三处、还得记住别漏。收敛成常量之后，改一处就够。
         * 网页里对应的是「只写一遍 `const DAYS = [1,2,3,4,5]` 然后到处遍历它」，
         * 而不是把数组字面量复制到每个用到的地方。
         */
        val WEEK_DAYS = 1..7

        /** 工作日 / 周末，只用来把日型提示拆成两行 */
        val WEEKDAY_DAYS = 1..5
        val WEEKEND_DAYS = 6..7

        /**
         * 节次列的宽度权重。
         *
         * ⚠️ **它是比例不是尺寸**：节次列拿 TIME_WEIGHT 份，每一天各拿 1 份。
         * 5 天时分母是 5.95，7 天时变成 7.95 —— 同一份数到手的绝对宽度自己就变小了。
         * 所以列数一改，这个值必须跟着重算，否则节次列会窄到放不下「08:30」。
         *
         * 7 列版按 360dp 宽机型配平：8 列 × 每格 2dp 外边距 + 页面/网格 padding
         * 扣掉之后约 300dp 可分，节次列拿到 300 × 1.25 ÷ 8.25 ≈ 45dp，
         * 每天拿到 300 ÷ 8.25 ≈ 36dp —— 刚好够「1-2 节」一行 + 课名每行 3 个字。
         */
        const val TIME_WEIGHT = 1.25f

        /** 表头行高 */
        const val HEADER_HEIGHT_DP = 30

        /**
         * 每个节次段的行高。
         * 内容最多 4 行（课程名 3 行 + 教室 1 行），约 48dp，
         * 这里给到 84dp —— 仍是接近两倍余量，用户把系统字号调大也不会溢出。
         */
        const val ROW_HEIGHT_DP = 84
    }
}
