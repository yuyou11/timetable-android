package com.yxz.timetable.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import com.yxz.timetable.R
import com.yxz.timetable.data.Course
import com.yxz.timetable.data.DayType
import com.yxz.timetable.data.Periods
import com.yxz.timetable.data.ScheduleFormat.weekdayCn
import com.yxz.timetable.data.SlotCourses
import com.yxz.timetable.data.selfStudySlot
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TemplateSet
import com.yxz.timetable.data.TimelineEngine
import com.yxz.timetable.databinding.FragmentWeekBinding
import java.time.LocalDate

/**
 * 课表页。
 *
 * 网格是用代码现搭的，而不是写死在 XML 里。原因是课表的结构是
 * 「节数信息切出来的行 × 7 天」，行数随导入的 periods 变（默认 "442" 是 10 行），
 * 用代码两层循环几行就搭完了；写成 XML 得手写几十个格子，还改不动。
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

    /** 正在展示的课程详情卡。页面销毁时必须关掉，否则它的窗口挂在 Activity 上会泄漏 */
    private var detailCard: Dialog? = null

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
        detailCard?.dismiss()
        detailCard = null
        _binding = null
    }

    // ==================================================================
    //  数据 -> 页面
    // ==================================================================

    private fun render() {
        val today = LocalDate.now()
        val thisWeek = store.weekOf(today)
        val week = (thisWeek + weekOffset).coerceIn(1, store.totalWeeks)
        val courses = store.courses()

        // 「今天」只存在于本周 —— 翻到别周后那一周里没有今天，
        // 再去高亮星期几就成了假信息，所以只在 week == thisWeek 时给值。
        // week 已被 coerce 进 1..totalWeeks，学期外的 thisWeek 自然对不上，
        // 不会把学期外的「今天」错标到第 1 周或最后一周上。
        val todayDay = if (week == thisWeek) today.dayOfWeek.value else null

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
        binding.tvDayTypeHint.text = dayTypeHint(monday, week, courses, todayDay)

        buildGrid(week, courses, todayDay)
        renderWeekDots(week, courses)
    }

    /**
     * 周次卡片的近五周点阵。设置里关掉时整个预览收起（GONE），
     * 卡片回到只有标题行的样子。
     */
    private fun renderWeekDots(week: Int, courses: List<Course>) {
        if (!store.weekDotsEnabled) {
            binding.weekDots.visibility = View.GONE
            return
        }
        binding.weekDots.visibility = View.VISIBLE
        binding.weekDots.submit(weekDots(week, courses))
    }

    /**
     * 近五周点阵预览的数据：**当前浏览的周 ±2 周**，每周一张「7 天 × 节数」占用表。
     *
     * 跟着浏览的周走（而不是钉死在真实本周）：翻周时预览也往前挪，
     * 「下周会怎样」才是这排点阵真正回答的问题。
     * 学期头尾会自动收窄（第 1 周只画 1-3 周），标签就是周次号。
     *
     * 「两课并一课」的标准课表（每门课都是 1-2、3-4 这种奇数起点的两节连堂）
     * 会把点阵两行并一行 —— 一门连堂课亮一个点，而不是一竖条两个点。
     * 识别和合并都在 [Periods]（纯逻辑，有测试盯着）。
     */
    private fun weekDots(week: Int, courses: List<Course>): List<WeekDotsView.Week> {
        val rowCount = Periods.rows(store.periods).size
        val paired = Periods.isPairedSchedule(courses)
        return ((week - 2)..(week + 2))
            .filter { it in 1..store.totalWeeks }
            .map { w ->
                var grid = occupancy(w, courses, rowCount)
                if (paired) grid = Periods.mergePairs(grid)
                WeekDotsView.Week(w, grid, w == week)
            }
    }

    /**
     * 第 [week] 周的占用表：`[星期一..星期日][第 1..N 节]`。
     *
     * 判「有课」和课表网格同一条规则（节点区间有重叠就算占用）——
     * 两处口径不一致的话，点阵亮了下面的格子却是空的，比没这功能还糟。
     */
    private fun occupancy(
        week: Int, courses: List<Course>, rowCount: Int
    ): Array<BooleanArray> {
        val grid = Array(7) { BooleanArray(rowCount) }
        for (c in courses) {
            if (!c.enabled || week !in c.weeks) continue
            for (node in c.startNode..c.endNode) {
                grid[c.dayOfWeek - 1][node - 1] = true
            }
        }
        return grid
    }

    /**
     * 日型提示的完整文本：工作日一行、周末一行，今天那条用强调色加粗。
     *
     * 这行标签和网格的七列只是**顺序上**一一对应，位置并不真的对齐
     * （文本是按字排的，列是按权重分的）—— 光看提示行数不出「三是哪一列」。
     * 把今天那条染成强调色，提示行和网格的高亮列就对上号了。
     */
    private fun dayTypeHint(
        monday: LocalDate, week: Int, courses: List<Course>, todayDay: Int?
    ): CharSequence {
        val ssb = SpannableStringBuilder()
        for ((i, d) in WEEK_DAYS.withIndex()) {
            when {
                i == 0 -> Unit
                d == WEEKEND_DAYS.first -> ssb.append("\n")
                else -> ssb.append("　")
            }
            val start = ssb.length
            ssb.append(dayTypeLabel(d, monday, week, courses))
            if (d == todayDay) {
                val accent = ForegroundColorSpan(requireContext().accentColor())
                ssb.setSpan(accent, start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(
                    StyleSpan(Typeface.BOLD), start, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return ssb
    }

    /**
     * 日型提示里的一个小标签，形如「一·早八」。day 是 1..7，monday 是本周周一。
     *
     * 用的是 [TimelineEngine.dayType]（v5 起配置带着星期映射和早八层）——
     * 显示的必须是**实际会生效**的日型。如果这里显示 B 型、
     * 实际却按 A 型的模板走，用户看到的就是一条假信息。
     *
     * ⚠️ 也**不能**自己拿映射表查一遍 —— 无课休息日（REST）由引擎的
     * 第一条规则拦下（「那天没课」是客观事实，不走星期映射），
     * 自己查表会把假期那格错误地标成映射目标。
     */
    private fun dayTypeLabel(
        day: Int, monday: LocalDate, week: Int, courses: List<Course>
    ): String {
        val date = monday.plusDays((day - 1).toLong())
        val used = TimelineEngine.dayType(date, week, courses, store.templates().config)
        return "${weekdayCn(day).removePrefix("周")}·${shortType(date, week, used, courses)}"
    }

    /**
     * 一两字的短标签。
     *
     * ⚠️ **「早八」不能只看用的哪套模板**。这里原来按「模板 == A」判断，
     * 默认配置下没问题（A 型日 ⟺ 当天有早八），但自定义映射后用户可以
     * 把工作日全映射到 A 型模板 —— 那时周二会被标成「早八」，
     * 而那天根本没有早课。
     *
     * 所以「早八」这个说法只在**日历确实算出早八**（[TimelineEngine.hasEarlyClass]）
     * 时才用；光是用 A 的模板、当天却没早课时，说「A型」——
     * 同样简洁，而且是真的。
     *
     * ⚠️ 自定义日型名**有意不进**这个紧凑提示：名字最长 20 字，
     * 七个格子挤两行放不下；完整的（自定义）名字在今日页和通知栏都有。
     * 这里写的是日型的**默认语义**，不是它的名字 —— 两者都为真。
     */
    private fun shortType(
        date: LocalDate, week: Int, used: DayType, courses: List<Course>
    ): String {
        val early = date.dayOfWeek.value <= 5 && TimelineEngine.hasEarlyClass(date, week, courses)
        return when {
            used == DayType.REST -> "无课"
            early -> "早八"
            else -> when (used) {
                DayType.A -> "A型"
                DayType.B_TRAIN_A -> "训A"
                DayType.B_TRAIN_B -> "训B"
                DayType.B_NORMAL -> "无早八"
                DayType.SATURDAY -> "周六"
                DayType.SUNDAY -> "周日"
                DayType.REST -> "无课"
            }
        }
    }

    // ==================================================================
    //  网格搭建
    //
    //  行结构来自「节数信息」（store.periods，默认 "442"）：一天的小节数 =
    //  各位之和，不再写死 10 —— "442" 是 10 行，"445" 是 13 行（第 11-13 节
    //  的时间按晚课节奏外推，见 Slots）。**每一节单独成行** —— 有的学校
    //  课不成对，按大课分行会整行漏课；行标签就是节次号（每节 = 一个
    //  45 分钟小节，学校数法和内部编号一一对应）。
    //
    //  布局从「一行一个 LinearLayout」改成了**列式**：一列节次 + 七列日子，
    //  每列内部从上往下摆格子。原因是行结构变细之后，一门课会横跨多行，
    //  相邻的同课格子必须合并成一整块（行式布局里格子出不了自己那一行）；
    //  列式布局里合并格的高度 = 跨过的行高之和，各列按同一套行高累加，
    //  行天然对齐。
    // ==================================================================

    private fun buildGrid(week: Int, courses: List<Course>, todayDay: Int?) {
        val ctx = requireContext()
        val container = binding.gridContainer
        container.removeAllViews()

        // ---- 表头 ----
        val header = makeRow(ctx, HEADER_HEIGHT_DP)
        header.addView(makeLabelCell("节次", TIME_WEIGHT, 11f))
        for (d in WEEK_DAYS) {
            header.addView(
                makeLabelCell(weekdayCn(d).removePrefix("周"), 1f, 11f, d == todayDay)
            )
        }
        container.addView(header)

        // 表头与内容之间的分隔线
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1)
            ).apply { setMargins(0, ctx.dp(2), 0, ctx.dp(4)) }
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider))
        })

        val rows = Periods.rows(store.periods)
        val templateSet = store.templates()

        // ---- 内容：一列节次 + 七列日子 ----
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(timeColumn(ctx, rows, templateSet))
        for (day in WEEK_DAYS) {
            body.addView(dayColumn(ctx, rows, day, week, courses, day == todayDay, templateSet))
        }
        container.addView(body)
    }

    /** 节次列：每行一格。行标签就是节次号（每节 = 一个 45 分钟小节） */
    /**
     * 节次列的一格。
     *
     * ⚠️ 时刻取自 `templates.slotStart/slotEnd`（**模板的占位格**），
     * 不是写死的 `Slots` —— 和今日页从同一个来源推导，不可能各说各话。
     * 模板没写到的节次回落到 Slots（第 11-14 节是外推的晚课时刻）。
     *
     * 起止时间放**同一行**（"8:30-9:15"）而不是上下两行：默认 10 行的表，
     * 每行省一行文字整表高度才压得下来（电脑版同款做法）。
     * 小时的前导零也去掉了 —— 这列在 360dp 屏上分到约 45dp，
     * "10:25-11:10" 的全写正好放不下，差的就是那一个 0 的宽度。
     */
    private fun timeColumn(ctx: Context, rows: List<Periods.Row>, set: TemplateSet): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, TIME_WEIGHT
            )
            for (r in rows) {
                addView(LinearLayout(ctx).apply {
                    layoutParams = fixedParams(ROW_HEIGHT_DP)
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER

                    addView(TextView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        gravity = Gravity.CENTER
                        text = "${r.node} 节"
                        textSize = 8.5f
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                        includeFontPadding = false
                        maxLines = 1
                    })

                    addView(TextView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        gravity = Gravity.CENTER
                        text = shortTime(set.slotStart(r.node)) + "-" +
                                shortTime(set.slotEnd(r.node))
                        textSize = 8f
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                })
            }
        }

    /** 510 -> "8:30"。小时不带前导零：节次列放不下 "08:30-09:15" 的宽度 */
    private fun shortTime(minute: Int): String =
        (minute / 60).toString() + ":%02d".format(minute % 60)

    /**
     * 一天的课列。
     *
     * ⚠️ 命中必须按「节点区间有重叠」判断，不能要求 startNode 恰好等于行首 ——
     * 行是按节数信息切的，别的学校单节的课、跨在两节之间的课，
     * 一行都对不上行首，整门课会**在网格里消失**。
     *
     * 相邻两行的命中如果是同一门课（名称、教室、周次完全一致），合并成一格 ——
     * 有的学校课不成对，一门课在数据里是按小节拆开的两条记录，显示时拼回一整块。
     *
     * today 为真时整列垫一层强调色淡底（[todayColumnBg]）—— 底色打在**列**身上
     * 而不是逐格打：格子之间有 2dp 间隙（坑 2），逐格打底会把这一列变成
     * 一节一节的虚线；整列一条竖带才像「这一列」。空格子是透明的，淡底正好
     * 从下面透出来；有课的格子自带实底（bg_cell_course），压在淡底上边框依然清晰。
     */
    private fun dayColumn(
        ctx: Context, rows: List<Periods.Row>, day: Int, week: Int, courses: List<Course>,
        today: Boolean, set: TemplateSet
    ): View {
        val hits = rows.map { r ->
            courses.firstOrNull { c ->
                c.enabled && c.dayOfWeek == day && week in c.weeks &&
                        c.startNode <= r.node && c.endNode >= r.node
            }
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            if (today) background = todayColumnBg(ctx)
            var i = 0
            while (i < rows.size) {
                val hit = hits[i]
                var span = 1
                if (hit != null) {
                    while (i + span < rows.size) {
                        val nxt = hits[i + span]
                        if (nxt == null || !sameCourseVisual(nxt, hit)) break
                        span++
                    }
                }
                // 详情卡要的节次范围是**合并块实际占的行** —— 一门课拆成
                // 「1-2」「3-4」两条记录时显示上拼成了一整块，弹卡里也得写
                // 「第 1-4 节」，而不是第一条记录自带的「第 1-2 节」。
                // 空格子也有范围（就它自己那一行）—— 空格子同样能点：
                // 「这一格别的周有没有课」是个真问题，而答案不在这张网格上。
                val nodeRange =
                    if (hit == null) rows[i].node..rows[i].node
                    else rows[i].node..rows[i + span - 1].node
                addView(makeCourseCell(ctx, set, hit, span, nodeRange, day, week, courses))
                i += span
            }
        }
    }

    /**
     * 点一格：弹出「这一格全学期都有什么课」面板。
     *
     * 有课的格子和空格子走的是同一条路 —— 区别只在 [SlotCourses.at] 返回空表时，
     * 会额外带上当天生效日型模板里的占位格，面板据此渲染一张自习卡。
     * 点一格回答的从来不是「这一周这一格是什么课」（那已经写在格子里了），
     * 而是「这一格全学期都上过什么课」。
     */
    private fun openSlotPanel(
        anchor: View, day: Int, nodes: IntRange, week: Int, courses: List<Course>,
        set: TemplateSet
    ) {
        detailCard?.dismiss()
        val items = SlotCourses.at(day, nodes, courses, week)
        // 自习卡的日期要用「正在浏览的那一周」的日期算日型，不能用今天 ——
        // 翻到第 3 周还按今天的日型取模板，显示的备注就是另一天的事了
        val monday = store.termStart.plusDays(((week - 1) * 7).toLong())
        detailCard = SlotCoursesCard.show(
            anchor = anchor,
            day = day,
            nodes = nodes,
            items = items,
            set = set,
            viewedWeek = week,
            currentWeek = isCurrentWeek(week),
            selfStudy = if (items.isEmpty()) {
                selfStudySlot(monday.plusDays((day - 1).toLong()), week, nodes.first, courses, set)
            } else {
                null
            }
        )
    }

    /** 显示上算「同一门课」：同一条记录，或名称 / 教室 / 周次完全一致的相邻记录 */
    private fun sameCourseVisual(a: Course, b: Course): Boolean =
        a === b || (a.name == b.name && a.place == b.place && a.weeks == b.weeks)

    /**
     * 正在浏览的周是不是「真实的本周」。
     *
     * 卡片上的徽标写「本周」还是「第 9 周」全看这个 ——
     * 翻到别的周还写「本周」，那就是一句假信息。
     */
    private fun isCurrentWeek(week: Int): Boolean =
        week == store.weekOf(LocalDate.now())

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
     * 高度给 MATCH_PARENT 时格子自动撑满所在行（表头用）；
     * 给具体行数时就是列式布局里的固定高度 —— 同一行的格子高度一致，行才能对齐。
     *
     * 那 2dp 的外边距是坑 2 的解药，不是装饰。
     */
    private fun cellParams(weight: Float): LinearLayout.LayoutParams {
        val gap = requireContext().dp(2)
        return LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, weight
        ).apply { setMargins(gap, gap, gap, gap) }
    }

    /**
     * 列内格子的尺寸：宽度撑满所在列（横向的权重在**列**身上，不在这格身上 ——
     * 竖向 LinearLayout 里给宽度设 0 + weight 会把宽度本身归零），
     * 高度固定 —— 同一行的格子高度一致，各列才按同一套行高对齐。
     *
     * 那 2dp 的外边距是坑 2 的解药，不是装饰。
     */
    private fun fixedParams(heightDp: Int): LinearLayout.LayoutParams {
        val gap = requireContext().dp(2)
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, requireContext().dp(heightDp)
        ).apply { setMargins(gap, gap, gap, gap) }
    }

    /**
     * 合并格的高度：跨过的每行行高，加上行与行之间被省掉的间隙。
     * 单行（span=1）时正好等于普通格 —— 合并格和上下邻居的缝隙依然是 2dp。
     */
    private fun spanHeightDp(span: Int): Int =
        span * ROW_HEIGHT_DP + (span - 1) * 2 * GAP_DP

    /**
     * 一个课表格子。
     *
     * 有课时返回一个**纵向容器**，里面放两个各自限制行数的 TextView：
     *   课程名  合并格最多 3 行；单行格放不下，限 1 行
     *   教室    最多 1 行
     *
     * 为什么不用一个 TextView 加 "\n" 拼起来？因为那样没法分别限行 ——
     * 名字长的时候会挤掉教室，或者整体撑破格子。
     *
     * 课程名为什么是 3 行？7 列版里一列只能排 3 个汉字，
     * 2 行 = 6 个字，「中国近现代史纲要」这 8 个字就要被截断；
     * 3 行给到 9 个字，常见课名都能完整显示 —— 这只对合并格成立
     * （连堂课占的行多，空间是合并回来的）；单行格只能保课名和教室各一行，
     * 放不下的部分交给省略号，点开弹的详情卡里有全名。
     * **限制行数的时候，要按「一行能放几个字」算，而不是随手定个 2。**
     */
    private fun makeCourseCell(
        ctx: Context, set: TemplateSet, hit: Course?, span: Int, nodeRange: IntRange,
        day: Int, week: Int, courses: List<Course>
    ): View {
        if (hit == null) {
            // 空格子真正留白，不打「—」占位：
            // 大多数格子是空的，全打上横杠会非常吵，反而看不清哪里有课。
            // 但它**可点**（视觉上依然什么都没有）：这一格在别的周有课时点开是那份清单，
            // 整学期都没课时点开是一张自习卡。
            return TextView(ctx).apply {
                layoutParams = fixedParams(ROW_HEIGHT_DP)
                setOnClickListener { openSlotPanel(this, day, nodeRange, week, courses, set) }
            }
        }

        val accent = ctx.accentColor()
        val nameLines = if (span == 1) 1 else 3
        val vPad = if (span == 1) 1 else 2

        return LinearLayout(ctx).apply {
            layoutParams = fixedParams(spanHeightDp(span))
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(3), ctx.dp(vPad), ctx.dp(3), ctx.dp(vPad))
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
                maxLines = nameLines
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

            setOnClickListener { openSlotPanel(this, day, nodeRange, week, courses, set) }
        }
    }

    /**
     * 表头的一格。highlight 是今天那一格：字染强调色，再垫和下面课列
     * 同款的淡底（[todayColumnBg]）—— 表头和课列之间隔着一条分隔线，
     * 两截淡底对不齐也没关系，「今天的竖带从表头开始」这个意思传到了就行。
     */
    private fun makeLabelCell(
        label: String, weight: Float, size: Float, highlight: Boolean = false
    ): TextView =
        TextView(requireContext()).apply {
            layoutParams = cellParams(weight)
            gravity = Gravity.CENTER
            text = label
            textSize = size
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                if (highlight) requireContext().accentColor()
                else ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            if (highlight) background = todayColumnBg(requireContext())
            includeFontPadding = false
        }

    /**
     * 今天那一列的淡底：强调色压到 12% 透明度的圆角竖带。
     *
     * 为什么是代码现画、而不是像 bg_cell_course 那样写个 XML shape？
     * XML shape 的颜色只能引用**具名色值**，而「强调色带透明度」有
     * 蓝/绿 × 日/夜四种组合，写 XML 要为四种各备一份，漏一份就是
     * 静默错色。这里从当前主题的 [accentColor] 现场压透明度，四合一。
     *
     * 透明度定在 12%（0x1E）而不是用 accentSoftColor 全量：全量的浅底
     * 就是课程格子的填充色，垫在整列上会让课程格子「消失」在底色里
     * （只剩描边）；淡到 12% 才是「背景」，格子压在上面依然看得出层次。
     */
    private fun todayColumnBg(ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = ctx.dp(8).toFloat()
            setColor(ColorUtils.setAlphaComponent(ctx.accentColor(), 0x1E))
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
     * 每一节（网格一行）的行高。
     *
     * 行结构按节数信息切，默认 "442" 是 10 行 —— 行高必须压住，整表
     * （10 × 36dp + 表头 ≈ 400dp）才放得进一屏，课表不用滚动就能全部看到。
     * 32dp 的单行格放「课名 1 行 + 教室 1 行」刚好；连堂的课会合并成跨行格
     * （见 [spanHeightDp]），空间是合并回来的，合并格里课名放宽到 3 行。
     */
    const val ROW_HEIGHT_DP = 32

    /** 格子之间的间隙（cellParams 的外边距），合并格的高度公式也靠它 */
    const val GAP_DP = 2
}
}
