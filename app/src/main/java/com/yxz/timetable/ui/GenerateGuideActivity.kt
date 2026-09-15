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
            totalWeeks = store.totalWeeks
        )

        binding.btnBack.setOnClickListener { finish() }

        binding.tvIntro.text =
            "不知道怎么写出这个 App 要的 JSON 格式？不用学 —— 让 AI 替你转。\n\n" +
                    "把提示词复制给豆包 / DeepSeek / Kimi / ChatGPT 任意一个，" +
                    "再把你的课表（截图、Excel、文字都行）一起发过去，" +
                    "它就会吐出可以直接导入的文件。"

        // 预览只显示课表提示词 —— 模板那段更长，要看的话复制走看更方便
        binding.tvPromptPreview.text = coursePrompt

        binding.btnCopyCoursePrompt.setOnClickListener {
            copy("课表提示词", coursePrompt)
        }
        binding.btnCopyTemplatePrompt.setOnClickListener {
            copy("作息模板提示词", AiPrompt.forTemplates(store.termName))
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
            "点上面「① 复制『课表』提示词」。\n" +
                    "提示词里已经带上了你当前的学期信息（${store.termName}，" +
                    "第 1 周从 ${store.termStartIso} 起，共 ${store.totalWeeks} 周），" +
                    "所以 AI 不会在这些地方猜错。"
        ),
        Step(
            "打开一个 AI",
            "豆包、DeepSeek、Kimi、通义千问、ChatGPT 都可以，网页版或手机 App 都行。\n" +
                    "推荐用支持「传文件」和「传图」的 —— 这样你可以直接把课表截图丢给它，不用手打。"
        ),
        Step(
            "把你的课表发给它",
            "三种方式任选：\n" +
                    "· 课程表截图（最省事，拍照或截图都行）\n" +
                    "· 课表 Excel / Word 文件\n" +
                    "· 直接打字，比如「周一 1-2 节大学英语，教一-101，2-4 周和 6-17 周」\n\n" +
                    "⚠️ 一定要把「周次」说清楚。如果课表上写的是「单周」，告诉 AI 是单周。"
        ),
        Step(
            "粘贴提示词，发送",
            "把第 1 步复制的提示词贴进对话框，和课表一起发出去。"
        ),
        Step(
            "检查它输出的内容",
            "AI 有时候会说废话，检查这四点：\n" +
                    "· 第一行是不是 { ，最后一行是不是 }\n" +
                    "· 有没有混进「好的，我来帮你转换」这类开场白 → 有的话回它一句「只输出 JSON」\n" +
                    "· 有没有被 ```json 这样的代码块包起来 → 有的话让它去掉\n" +
                    "· 中文有没有变成 \\u4e00 这样的乱码 → 有的话让它「直接输出中文」"
        ),
        Step(
            "保存成文件",
            "新建一个文本文件（记事本 / VS Code / 手机备忘录都行），" +
                    "把 AI 输出的 JSON **完整**粘进去，存成：\n\n" +
                    "　　课表.json\n\n" +
                    "⚠️ 这一步最容易出错，三个坑：\n\n" +
                    "① Windows 默认**隐藏文件扩展名**。你以为存成了 课表.json，" +
                    "实际是 课表.json.txt。\n" +
                    "　 解决：文件管理器 → 上方「查看」→ 勾上「文件扩展名」，再改名。\n\n" +
                    "② 用记事本「另存为」时，「保存类型」必须选「**所有文件**」，" +
                    "否则它会自动加上 .txt。\n\n" +
                    "③ 编码选 **UTF-8**，否则中文会变乱码。\n\n" +
                    "（在手机上存的话：用「文件管理」新建 .txt，改后缀为 .json 即可。）"
        ),
        Step(
            "传到手机",
            "微信「文件传输助手」发给自己最方便。\n" +
                    "QQ、数据线、网盘都可以。"
        ),
        Step(
            "导入",
            "在微信里点开那个文件 → 右上角「…」→ 用其他应用打开（或先保存到手机）。\n" +
                    "然后回到 App：设置 → 数据 → **导入课表** → 选中那个 .json 文件。"
        ),
        Step(
            "确认预览再导入",
            "App 会先显示预览：几门课（含前几门的课名）、学期信息、" +
                    "每种模板的起床时间、启用了哪些日型、有没有时段冲突。\n\n" +
                    "⚠️ **课名那一项要仔细看**：如果显示出来是一串乱码，" +
                    "说明文件的编码不对（多半被存成了 ANSI / GBK），" +
                    "这时别点导入 —— 导入进去的就是乱码课名。\n\n" +
                    "⚠️ 如果提示「导入失败」，别急着重来 —— 见最下面那条。"
        ),
        Step(
            "失败了怎么办",
            "把 App 弹出的**报错原文**复制下来，连同 AI 上次生成的 JSON，" +
                    "一起发给 AI，让它改。\n\n" +
                    "省事的做法：点上面的「③ 复制『报错修复』提示词」，" +
                    "把里面的两处占位替换成你的报错和 JSON，直接发给 AI。"
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
                ).apply { if (i > 0) topMargin = ctx.dp(16) }
            }

            // 左侧编号圆点。用 TextView + 圆形背景，而不是 Unicode 圈码像 ① ② ——
            // 那些字符在国产 ROM 字体里不一定有字形，会渲染成空白（踩过这个坑）。
            row.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24))
                gravity = Gravity.CENTER
                text = "${i + 1}"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.accent))
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
                textSize = 15f
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
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setLineSpacing(ctx.dp(3).toFloat(), 1f)
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
