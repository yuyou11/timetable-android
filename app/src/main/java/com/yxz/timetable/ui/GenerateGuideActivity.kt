package com.yxz.timetable.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.yxz.timetable.R
import com.yxz.timetable.data.AiPrompt
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.ActivityGenerateGuideBinding

/**
 * 「用 AI 生成数据」向导。
 *
 * ## 为什么要有这个页面
 *
 * 手写 timetable JSON 对不熟悉的人来说门槛太高：要理解星期编号从 1 开始、
 * 周次要写成 "2-4,6-17"、连堂不能拆成两条…… 这些规则对程序来说很清楚，
 * 但对人来说是一堆要背的东西。
 *
 * 而「把课表丢给 AI，让它转成 JSON」是真实可行的路径 —— 只要提示词写清楚了。
 * 这个页面的全部价值就是**把提示词写到足够清楚**，让用户复制粘贴就能得到能用的文件。
 *
 * ## 关键设计：提示词不是写死的文字
 *
 * 提示词里最要紧的三个值是学期名称、第 1 周周一日期、总周数 —— 它们决定整张表
 * 的时间对不对。如果让 AI 去猜，它大概率会编一个日期，而错一天的后果是每一周的课都错位。
 *
 * 所以这里从 Store 读出真实值填进提示词。用户复制到的东西**天然带着正确的学期信息**，
 * 不需要他填，也就没有填错的机会。
 */
class GenerateGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGenerateGuideBinding
    private lateinit var store: Store

    /** 当前的课表提示词，预览和复制用的是同一份 */
    private var coursePrompt: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGenerateGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = Store(this)

        coursePrompt = AiPrompt.forCourses(
            termName = store.termName,
            startDate = store.termStartIso,
            totalWeeks = store.totalWeeks,
            periods = store.periods
        )

        binding.btnBack.setOnClickListener { finish() }

        binding.tvIntro.text =
            "不用学 JSON —— 让 AI 替你转。\n" +
                    "把提示词复制给豆包 / DeepSeek / Kimi / ChatGPT，" +
                    "连同课表（截图、Excel、文字都行）一起发过去，它就会吐出能直接导入的文件。"

        // 预览只显示课表提示词 —— 模板那段更长，要看的话复制走看更方便
        binding.tvPromptPreview.text = coursePrompt

        binding.btnCopyCoursePrompt.setOnClickListener {
            copy("课表提示词", coursePrompt)
        }
        binding.btnCopyTemplatePrompt.setOnClickListener {
            copy(
                "作息模板提示词",
                // 提示词里的日型表念用户改过的名字（v4）—— AI 输出的键仍是 id
                AiPrompt.forTemplates(store.termName, store.periods, store.dayTypeNames())
            )
        }
        binding.btnCopyFixPrompt.setOnClickListener {
            copy(
                "报错修复提示词",
                AiPrompt.forFix(
                    errorMessage = "（把 App 导入时弹出的报错原文粘在这里）",
                    generatedJson = "（把 AI 上次生成的 JSON 粘在这里）"
                )
            )
        }

        buildSteps()
    }

    // ==================================================================

    /**
     * 步骤清单。
     *
     * 写成数据而不是 XML，是因为步骤里夹着不少需要**加粗强调**的地方
     * （尤其是第 6 步那几个文件命名的坑），用代码拼更容易控制。
     */
    private data class Step(val title: String, val body: String)

    private fun steps(): List<Step> = listOf(
        Step(
            "复制提示词",
            "点上面「① 复制课表提示词」。\n" +
                    "提示词里已经带好学期信息（${store.termName}，第 1 周从 ${store.termStartIso} 起，" +
                    "共 ${store.totalWeeks} 周）。"
        ),
        Step(
            "发给 AI",
            "豆包、DeepSeek、Kimi、ChatGPT 都行。\n" +
                    "把提示词和课表一起发过去 —— 截图、Excel、文字都可以。\n" +
                    "周次务必说清：单周 / 双周照课表原文写。"
        ),
        Step(
            "检查输出",
            "只要 JSON：第一行 {、最后一行 }，没有开场白，没有被代码块包住。\n" +
                    "中文变成 \\u4e00 这种转义的话，回它一句「直接输出中文」。"
        ),
        Step(
            "存成 .json",
            "存成 课表.json，编码选 UTF-8。\n" +
                    "Windows 先打开「文件扩展名」，否则会变成 课表.json.txt；" +
                    "记事本另存为时「保存类型」选「所有文件」。"
        ),
        Step(
            "导入",
            "设置 → 数据 → 导入数据。\n" +
                    "先看预览：课名是乱码说明编码不对，别点导入。"
        ),
        Step(
            "失败了",
            "把报错原文和 AI 生成的 JSON 一起发回去让它改；" +
                    "点上面「③」复制修复提示词更省事。"
        )
    )

    private fun buildSteps() {
        val ctx = this
        val container = binding.stepContainer
        container.removeAllViews()

        steps().forEachIndexed { i, step ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) topMargin = ctx.dp(14) }
            }

            // 左侧编号圆点。用 TextView + 圆形背景，而不是 Unicode 圈码像 ① ② ——
            // 那些字符在国产 ROM 字体里不一定有字形，会渲染成空白（踩过这个坑）。
            row.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24))
                gravity = Gravity.CENTER
                text = "${i + 1}"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ctx.accentColor())
                setBackgroundResource(R.drawable.bg_step_number)
                includeFontPadding = false
            })

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = ctx.dp(10) }
            }

            col.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = step.title
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                includeFontPadding = false
            })

            col.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = ctx.dp(4) }
                text = step.body
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setLineSpacing(ctx.dp(2).toFloat(), 1f)
                includeFontPadding = false
            })

            row.addView(col)
            container.addView(row)
        }
    }

    // ==================================================================

    private fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label 已复制（${text.length} 字）", Toast.LENGTH_SHORT).show()
    }
}
