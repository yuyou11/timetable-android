package com.yxz.timetable.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 《时间规划表》数据格式标准 v1 —— 解析与生成
 *
 * 这个文件就是「标准」的可执行版本：文档写在 docs/数据格式标准.md，
 * 但真正说了算的是这里的代码。文档和代码不一致时，以代码为准。
 *
 * ## 设计这条标准的四条原则
 *
 * **1. 给人写的部分要宽容，给机器读的部分要严格。**
 *    用户手写 `"weeks": "2-4，6-17"`（中文逗号）应该能work，
 *    但 `"dayOfWeek": 9` 必须报错而不是猜。
 *
 * **2. 报错要能直接照着改。**
 *    `startDate` 不是周一的时候，错误信息里直接把正确的日期算出来告诉用户。
 *    报错的价值不在于"告诉用户错了"，而在于"告诉用户改成什么"。
 *
 * **3. 未知字段一律忽略。**
 *    这样将来标准加了字段，老版本 App 拿到新文件不会崩，
 *    只会安静地少读一个字段。这是让格式能演进的唯一办法。
 *
 * **4. 版本号只增不减。**
 *    碰到比自己新的版本要**明确拒绝**，而不是硬着头皮解析 ——
 *    硬解析的后果是数据被静默截断，用户以为导入成功了，其实丢了一半。
 */
object ScheduleFormat {

    const val FORMAT_ID = "timetable"

    /**
     * 当前支持的格式版本。
     *
     * v1 → v2 新增了可选的 `templates` 段。
     * v2 → v3 新增了可选的 `dayTypes` 段。
     *
     * 两次都是**只增不改**，没有动过任何已有字段，所以老文件照常能用 ——
     * 这就是「只增不改」原则的价值：升级格式不需要任何数据迁移。
     *
     * ## 既然只是「新增一段」，为什么还要升版本号
     *
     * 因为**老版本 App 会误解新文件，而且不会报错**。
     *
     * v2 的 App 不认识 `dayTypes`，它会照旧用六种日型跑 ——
     * 用户明明写了「只启用 A 和周末」，装回老版本却会看到训练日又冒出来。
     * 这种「悄悄用错」正是版本号要防的东西。
     *
     * 升到 v3 之后，老 App 会明确拒绝：
     * 「文件是 v3 格式，这个 App 只认到 v2，请更新 App 后再导入」——
     * **拒绝比误解好**，这一条在任何数据交换的场合都成立。
     */
    const val VERSION = 3

    /** 节次上限，和 Slots 保持一致 */
    const val MAX_NODE = 10

    /** 周次上限。比 19 宽一些，给不同学制的学校留余量 */
    const val MAX_WEEK_LIMIT = 30

    const val DEFAULT_TOTAL_WEEKS = 19

    // ============================================================
    //  结果类型
    // ============================================================

    data class Term(val name: String, val startDate: LocalDate, val totalWeeks: Int)

    data class Parsed(
        val term: Term?,
        /**
         * 课程列表。
         *
         * **null 表示「不要动现有课程」**，而不是「清空课程」。
         * 什么情况下会是 null？文件里只写了 templates 段、没写 courses（或写了空数组）——
         * 也就是「我只想改作息，课表别动」。
         *
         * 这个区分很重要：如果把 null 当成空列表去覆盖，用户改一次起床时间
         * 就会把自己的课表全删了。
         */
        val courses: List<Course>?,
        /** 只有文件里带了 templates 段时才有值；null 表示「不要动现有模板」 */
        val templates: Map<DayType, List<Block>>?,
        /**
         * 只有文件里带了 `dayTypes` 段时才有值；**null 表示「不要动现有策略」**。
         *
         * 和 courses 一样用「null = 不动」而不是「null = 用默认」——
         * 否则用户每次导入一份只改课表的文件，作息策略都会被打回默认值。
         */
        val dayTypes: DayTypePolicy?,
        val warnings: List<String>
    )

    /** 用 sealed class 而不是抛异常：调用方必须显式处理失败分支，编译器会盯着 */
    sealed class Result {
        data class Ok(val parsed: Parsed) : Result()
        data class Failed(val message: String) : Result()
    }

    class FormatException(message: String) : Exception(message)

    // ============================================================
    //  一、周次写法
    //
    //  这是整条标准里唯一需要发明语法的地方，所以单独拎出来。
    // ============================================================

    object Weeks {

        /**
         * 把周次字符串解析成集合。
         *
         * 支持的写法：
         *   "3"          第 3 周
         *   "2-4"        第 2 到 4 周
         *   "1-17/2"     1、3、5…17 周（单周）
         *   "2-16/2"     2、4、6…16 周（双周）
         *   "2-4,6-17"   分段
         *   "*"          全部周次
         *
         * 容错：中文逗号「，」、全角空格、波浪号「～」、破折号「—」都会先normalize掉。
         */
        fun parse(raw: String, totalWeeks: Int): Set<Int> {
            val s = normalize(raw)
            if (s.isEmpty()) throw FormatException("weeks 不能为空")

            if (s == "*") return (1..totalWeeks).toSet()

            val out = sortedSetOf<Int>()

            for (segment in s.split(',')) {
                if (segment.isEmpty()) {
                    throw FormatException("weeks 里有多余的逗号（\"$raw\"）")
                }

                val slash = segment.indexOf('/')
                val rangePart = if (slash >= 0) segment.substring(0, slash) else segment
                val stepPart = if (slash >= 0) segment.substring(slash + 1) else null

                val step = when {
                    stepPart == null -> 1
                    stepPart.isEmpty() -> throw FormatException("weeks 的 \"/\" 后面缺少步长，正确写法如 \"1-17/2\"")
                    else -> stepPart.toIntOrNull()
                        ?: throw FormatException("weeks 的步长 \"$stepPart\" 不是数字，正确写法如 \"1-17/2\"")
                }
                if (step < 1) throw FormatException("weeks 的步长必须大于 0，现在是 $step")

                val dash = rangePart.indexOf('-')
                val from: Int
                val to: Int
                if (dash < 0) {
                    from = rangePart.toIntOrNull()
                        ?: throw FormatException("weeks 里的 \"$rangePart\" 既不是周次也不是区间")
                    to = from
                } else {
                    val a = rangePart.substring(0, dash)
                    val b = rangePart.substring(dash + 1)
                    from = a.toIntOrNull()
                        ?: throw FormatException("weeks 区间的起点 \"$a\" 不是数字")
                    to = b.toIntOrNull()
                        ?: throw FormatException("weeks 区间的终点 \"$b\" 不是数字")
                }

                if (from > to) {
                    throw FormatException("weeks 区间 \"$rangePart\" 起点比终点大")
                }
                if (from < 1 || to > totalWeeks) {
                    throw FormatException(
                        "weeks 的周次必须在 1–$totalWeeks 之间，\"$rangePart\" 超出范围"
                    )
                }

                var w = from
                while (w <= to) {
                    out += w
                    w += step
                }
            }

            if (out.isEmpty()) throw FormatException("weeks 没有解析出任何周次")
            return out
        }

        /**
         * 反过来：把集合压成最短的字符串。
         *
         * {3,5,7,9,11,13,15,17} -> "3-17/2"   （认出等差数列，优先用步长写法）
         * {2,3,4,6,...,17}      -> "2-4,6-17" （不是等差，退回分段）
         *
         * 导出时优先输出步长写法，是因为「单周 / 双周」在课表语境下
         * 比一长串数字更容易核对 —— 人一眼就能看出对不对。
         */
        fun format(weeks: Set<Int>): String {
            if (weeks.isEmpty()) return ""

            val s = weeks.sorted()
            if (s.size == 1) return s[0].toString()

            // 等差数列且步长大于 1 -> 压成 "起点-终点/步长"
            if (s.size >= 3) {
                val step = s[1] - s[0]
                if (step > 1 && s.zipWithNext().all { (a, b) -> b - a == step }) {
                    return "${s.first()}-${s.last()}/$step"
                }
            }

            // 否则合并连续区间
            val parts = mutableListOf<String>()
            var from = s[0]
            var prev = s[0]
            for (w in s.drop(1)) {
                if (w == prev + 1) {
                    prev = w
                } else {
                    parts += if (from == prev) "$from" else "$from-$prev"
                    from = w
                    prev = w
                }
            }
            parts += if (from == prev) "$from" else "$from-$prev"
            return parts.joinToString(",")
        }

        private fun normalize(raw: String): String = raw
            .replace('，', ',')
            .replace('～', '-')
            .replace('~', '-')
            .replace('—', '-')   // em dash
            .replace('–', '-')   // en dash
            .replace('－', '-')   // 全角减号
            .filter { !it.isWhitespace() }
    }

    // ============================================================
    //  二、解析
    // ============================================================

    /**
     * 把 JSON 解析异常翻译成一句**人话**。
     *
     * ## 为什么不能直接拼 `${e.message}`
     *
     * org.json 抛异常时，会把**出错位置前后的原始文本**夹带在消息里。
     * 正常 JSON 出错时这很有用（能看到是哪个字段写错了），
     * 但如果喂进来的是二进制文件，这段「原始文本」就是几百上千字节的乱码 ——
     *
     * 用户看到的是一个撑满整屏、全是问号和方块的对话框，
     * 连「你可以试试别的文件」这句话都挤没了。
     * 用户报障时发的截图就是这样：**信息全被噪声吃掉了**。
     *
     * 所以这里做两件事：
     *   ① 消息长度超过 [MAX_ECHO] 就整段砍掉，只保留「哪一行哪一列」
     *   ② 内容里控制字符太多（说明根本不是文本）时也砍掉
     *
     * **给用户看的错误信息，宁可短，也不要把原始数据摊上去。**
     */
    private fun jsonErrorMessage(json: String, e: JSONException): String {
        val raw = e.message.orEmpty()

        // org.json 的消息形如 "... at 1 [character 2 line 1]"，这段是定位信息，要保住
        val location = Regex("""at \d+ \[character \d+ line \d+]""").find(raw)?.value

        val noisy = raw.length > MAX_ECHO || controlRatio(json.take(MAX_ECHO)) > 0.02
        if (!noisy) {
            return "不是合法的 JSON：$raw\n\n" +
                    "常见原因：多了一个逗号、少了一个引号，或者括号没配对。"
        }

        return buildString {
            append("这个文件不是合法的 JSON。")
            if (location != null) append("\n出错位置：$location")
            append("\n\n")
            append("而且它的内容看起来不像是文本 —— 说明选中的很可能不是课表文件。\n")
            append("请选择从 App 里导出的 .json 文件。")
        }
    }

    /** 一段文本里控制字符（不含制表/换行/回车）的占比 */
    private fun controlRatio(s: String): Double {
        if (s.isEmpty()) return 0.0
        val n = s.count { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }
        return n.toDouble() / s.length
    }

    /** 错误消息里最多回显多少字符的原始内容 */
    private const val MAX_ECHO = 200

    fun parse(json: String, fallbackTotalWeeks: Int = DEFAULT_TOTAL_WEEKS): Result {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return Result.Failed(jsonErrorMessage(json, e))
        }

        val format = root.optString("format", "")
        if (format != FORMAT_ID) {
            return Result.Failed(
                "这不是本 App 的课表文件。\n" +
                        "format 字段应该是 \"$FORMAT_ID\"，实际读到的是 \"$format\"。"
            )
        }

        val version = root.optInt("version", 0)
        if (version < 1) {
            return Result.Failed("缺少 version 字段，无法确认文件格式版本。")
        }
        if (version > VERSION) {
            return Result.Failed(
                "文件是 v$version 格式，这个 App 只认到 v$VERSION。\n" +
                        "请更新 App 后再导入 —— 强行导入会丢掉新格式里多出来的内容。"
            )
        }

        // ---- term 段（可选）----
        var term: Term? = null
        var totalWeeks = fallbackTotalWeeks

        val termObj = root.optJSONObject("term")
        if (termObj != null) {
            totalWeeks = termObj.optInt("totalWeeks", DEFAULT_TOTAL_WEEKS)
            if (totalWeeks < 1 || totalWeeks > MAX_WEEK_LIMIT) {
                return Result.Failed("term.totalWeeks 必须在 1–$MAX_WEEK_LIMIT 之间，现在是 $totalWeeks。")
            }

            val startRaw = termObj.optString("startDate", "").trim()
            if (startRaw.isEmpty()) {
                return Result.Failed("term.startDate 不能为空，正确写法如 \"2026-09-07\"。")
            }
            val start = try {
                LocalDate.parse(startRaw)
            } catch (e: DateTimeParseException) {
                return Result.Failed("term.startDate \"$startRaw\" 不是合法日期，正确写法如 \"2026-09-07\"。")
            }
            if (start.dayOfWeek != DayOfWeek.MONDAY) {
                return Result.Failed(
                    "term.startDate 必须是周一。\n" +
                            "$startRaw 是${weekdayCn(start.dayOfWeek.value)}，" +
                            "应该填 ${TimelineEngine.mondayOf(start)}。"
                )
            }

            val name = termObj.optString("name", "").trim().ifBlank { "未命名学期" }
            term = Term(name, start, totalWeeks)
        }

        // ---- templates 段（可选，v2 新增）----
        val warnings = mutableListOf<String>()
        var templates: Map<DayType, List<Block>>? = null

        val tmplObj = root.optJSONObject("templates")
        if (tmplObj != null) {
            templates = try {
                parseTemplates(tmplObj, warnings)
            } catch (e: FormatException) {
                return Result.Failed(e.message ?: "templates 段格式有误。")
            }
        }

        // ---- dayTypes 段（可选，v2 新增）----
        var dayTypes: DayTypePolicy? = null
        val dtObj = root.optJSONObject("dayTypes")
        if (dtObj != null) {
            dayTypes = try {
                parseDayTypes(dtObj)
            } catch (e: FormatException) {
                return Result.Failed(e.message ?: "dayTypes 段格式有误。")
            }
        }

        // ---- courses 段 ----
        // 允许缺失或为空，解析结果就是 null，含义是「不要动现有课程」。
        //
        // 为什么允许？因为「我只想改作息，课表别动」是完全正当的需求。
        // 强制要求带课程会把这条最简单的路堵死 —— 用户只好把自己已有的课程
        // 又抄一遍塞进文件里，纯属没事找事。
        //
        // 注意 null 和空列表是两回事：空列表会去清空用户的课表，null 不会。
        val arr = root.optJSONArray("courses")
        var courses: List<Course>? = null

        if (arr != null && arr.length() > 0) {
            val list = mutableListOf<Course>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                    ?: return Result.Failed("courses 第 ${i + 1} 项不是对象。")
                list += try {
                    parseCourse(obj, i, totalWeeks)
                } catch (e: FormatException) {
                    return Result.Failed(e.message ?: "courses 第 ${i + 1} 项格式有误。")
                }
            }
            courses = list
            warnings += detectConflicts(list)
        }

        // ---- 兜底：这份文件至少得说点什么 ----
        // 四段全空的文件导入它没有任何意义，而且多半意味着用户选错了文件
        // 或者 AI 输出的东西是坏的 —— 明确报错比「导入成功但什么都没变」好。
        if (term == null && courses == null && templates == null && dayTypes == null) {
            return Result.Failed(
                "这份文件里 term、courses、templates、dayTypes 四段都没有内容，没有可导入的东西。\n\n" +
                        "如果你是想导入课表，检查一下 courses 数组是不是空的；\n" +
                        "如果是想让 AI 生成，把「报错修复」提示词发回给它。"
            )
        }

        return Result.Ok(Parsed(term, courses, templates, dayTypes, warnings))
    }

    /**
     * 解析 dayTypes 段 —— 「这份配置实际启用哪几种日型」。
     *
     * 写法：
     * ```json
     * "dayTypes": {
     *   "enabled": ["A", "B_NORMAL", "SATURDAY", "SUNDAY"],
     *   "fallback": "B_NORMAL"
     * }
     * ```
     *
     * ## 两个刻意的设计决定
     *
     * **① `enabled` 为空数组要报错，而不是当成「什么都不启用」。**
     * 那样的话每天都会落到 fallback 上，等于把整套日型系统废掉 ——
     * 几乎不可能是用户的本意，多半是写错了。报错比静默接受好。
     *
     * **② `fallback` 允许不在 `enabled` 里。**
     * 它表达的是「用哪套模板兜底」，跟「启用了哪些日型」不是一回事。
     * 比如只启用 A 和周末、却希望周中没早八时回落到 B 型，
     * 就写成 `enabled: [A, SATURDAY, SUNDAY]` + `fallback: B_NORMAL`。
     * 这是完全正当的用法，所以不拦。
     *
     * **③ 名字大小写不敏感。** 用户手写时写成 `"a"` 或 `"B_normal"`
     * 都不该导入失败 —— 和别处解析 kind / dayOfWeek 的宽容度保持一致。
     */
    private fun parseDayTypes(obj: JSONObject): DayTypePolicy {
        val raw = obj.opt("enabled")
            ?: throw FormatException(
                "dayTypes 段缺少 enabled 字段。\n" +
                        "它要列出启用的日型，比如 \"enabled\": [\"A\", \"B_NORMAL\", \"SATURDAY\", \"SUNDAY\"]"
            )

        val arr = raw as? JSONArray
            ?: throw FormatException("dayTypes.enabled 必须是一个数组，现在读到的是 ${raw.javaClass.simpleName}。")

        if (arr.length() == 0) {
            throw FormatException(
                "dayTypes.enabled 是空数组 —— 至少要启用一种日型。\n" +
                        "如果想让「有早八 / 没早八」都能区分，用 [\"A\", \"B_NORMAL\", \"SATURDAY\", \"SUNDAY\"]。"
            )
        }

        val enabled = mutableSetOf<DayType>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            val type = dayTypeByName(v)
                ?: throw FormatException(
                    "dayTypes.enabled 第 ${i + 1} 项 \"$v\" 不是可识别的日型。\n" +
                            "可用的值是：${DAY_TYPE_NAMES.keys.joinToString("、")}"
                )
            enabled += type
        }

        // fallback 可以省略；省略时取 enabled 里在标准顺序中最靠前的那种。
        // 用固定顺序而不是 HashSet 的遍历顺序 —— 后者每次运行可能不一样，
        // 会导致同一份文件解析出不同结果。
        val fallbackRaw = obj.optString("fallback", "").trim()
        val fallback = if (fallbackRaw.isEmpty()) {
            Templates.ALL_TYPES.first { it in enabled }
        } else {
            dayTypeByName(fallbackRaw)
                ?: throw FormatException(
                    "dayTypes.fallback \"$fallbackRaw\" 不是可识别的日型。\n" +
                            "可用的值是：${DAY_TYPE_NAMES.keys.joinToString("、")}"
                )
        }

        return DayTypePolicy(enabled, fallback)
    }

    /** 按名字查日型，大小写不敏感 */
    private fun dayTypeByName(name: String): DayType? {
        if (name.isEmpty()) return null
        val upper = name.uppercase()
        return Templates.ALL_TYPES.firstOrNull { it.name.uppercase() == upper }
    }

    // ============================================================
    //  二·五、作息模板（v2）
    //
    //  模板定义了「正常情况下这个点该干嘛」，课表再盖在它上面。
    //  所以它也是课表的一部分 —— 用户想改起床时间，改的就是这里。
    // ============================================================

    /**
     * 解析 templates 段。
     *
     * 只要求用户提供**想改的那几种日型**，没提供的会回落到内置模板。
     * 这样改一个起床时间只要写十来行，而不是把六套模板整套抄一遍。
     */
    private fun parseTemplates(
        obj: JSONObject,
        warnings: MutableList<String>
    ): Map<DayType, List<Block>> {
        val out = mutableMapOf<DayType, List<Block>>()

        for (key in obj.keys().asSequence().toList()) {
            val type = DAY_TYPE_NAMES[key]
                ?: throw FormatException(
                    "templates 里有无法识别的日型 \"$key\"。\n" +
                            "可用的值是：${DAY_TYPE_NAMES.keys.joinToString("、")}"
                )

            val arr = obj.optJSONArray(key)
                ?: throw FormatException("templates.$key 必须是一个数组。")
            if (arr.length() == 0) {
                throw FormatException("templates.$key 是空数组 —— 与其不写，不如整个删掉这一段让它用内置模板。")
            }

            val blocks = mutableListOf<Block>()
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i)
                    ?: throw FormatException("templates.$key 第 ${i + 1} 项不是对象。")
                blocks += try {
                    parseBlock(b)
                } catch (e: FormatException) {
                    throw FormatException("templates.$key 第 ${i + 1} 项：${e.message}")
                }
            }

            checkTemplate(blocks, key, warnings)
            out[type] = blocks.sortedBy { it.start }
        }

        if (out.isEmpty()) {
            throw FormatException("templates 段是空的，删掉它或者填至少一种日型。")
        }
        return out
    }

    private fun parseBlock(o: JSONObject): Block {
        val startRaw = o.optString("start", "").trim()
        val endRaw = o.optString("end", "").trim()
        if (startRaw.isEmpty()) throw FormatException("缺少 start（如 \"06:55\"）")
        if (endRaw.isEmpty()) throw FormatException("缺少 end（如 \"07:10\"）")

        val start = parseHhmm(startRaw, isEnd = false)
            ?: throw FormatException("start \"$startRaw\" 不是合法时刻，写法如 \"06:55\"")
        val end = parseHhmm(endRaw, isEnd = true)
            ?: throw FormatException("end \"$endRaw\" 不是合法时刻，写法如 \"07:10\"；一天的最后一段可以写 \"24:00\"")

        if (end <= start) {
            throw FormatException("end ($endRaw) 必须晚于 start ($startRaw)")
        }

        val title = o.optString("title", "").trim()
        if (title.isEmpty()) throw FormatException("缺少 title（这一格显示什么）")

        val kindRaw = o.optString("kind", "CHORE").trim().uppercase()
        val kind = KIND_NAMES[kindRaw]
            ?: throw FormatException(
                "kind \"$kindRaw\" 无法识别。可用值：${KIND_NAMES.keys.joinToString("、")}"
            )

        val nodes = o.optJSONArray("nodes")?.let { a ->
            if (a.length() != 2) throw FormatException("nodes 必须是两个数字，例如 [1, 2]")
            val s = a.optInt(0, -1)
            val e = a.optInt(1, -1)
            if (s !in 1..MAX_NODE || e !in 1..MAX_NODE) {
                throw FormatException("nodes 的节次必须在 1–$MAX_NODE 之间，现在是 [$s, $e]")
            }
            if (s > e) throw FormatException("nodes 的起始节次比结束节次大：[$s, $e]")
            s..e
        }

        return Block(
            start = start,
            end = end,
            title = title,
            note = o.optString("note", "").trim(),
            kind = kind,
            nodes = nodes
        )
    }

    /**
     * 模板的结构检查。
     *
     * **重叠必须报错**，不能只警告。原因是引擎在合成时间轴时，
     * 遇到两个重叠的固定日程会按「先到先得」把后面的截断 ——
     * 结果是用户写的某一格被静默吃掉，界面上看不出来，只有对时间才发现少了东西。
     * 这种「不报错的错」比直接失败糟糕得多。
     */
    private fun checkTemplate(blocks: List<Block>, key: String, warnings: MutableList<String>) {
        val sorted = blocks.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            if (cur.start < prev.end) {
                throw FormatException(
                    "templates.$key 里有两格时间重叠：\n" +
                            "  ${Slots.fmt(prev.start)}–${Slots.fmt(prev.end)}　${prev.title}\n" +
                            "  ${Slots.fmt(cur.start)}–${Slots.fmt(cur.end)}　${cur.title}\n" +
                            "同一时刻只能有一件固定的事。如果是想上两门课，那属于课表，不在这里写。"
                )
            }
        }

        // 没铺满一整天是可以接受的：引擎会自动填成「空档 · 机动」。
        // 但提醒一句，因为漏写往往是手滑而不是本意。
        val gaps = mutableListOf<String>()
        var cursor = 0
        for (b in sorted) {
            if (b.start > cursor) gaps += "${Slots.fmt(cursor)}–${Slots.fmt(b.start)}"
            cursor = b.end
        }
        if (cursor < 1440) gaps += "${Slots.fmt(cursor)}–24:00"
        if (gaps.isNotEmpty()) {
            warnings += "templates.$key 有 ${gaps.size} 段没排到（${gaps.take(3).joinToString("、")}" +
                    if (gaps.size > 3) "…），会被自动填成「空档 · 机动」" else "），会被自动填成「空档 · 机动」"
        }
    }

    /** "06:55" -> 415；"24:00" -> 1440（只允许出现在 end 位置） */
    private fun parseHhmm(s: String, isEnd: Boolean): Int? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (m !in 0..59) return null
        if (h == 24) return if (isEnd && m == 0) 1440 else null
        if (h !in 0..23) return null
        return h * 60 + m
    }

    private fun parseCourse(obj: JSONObject, index: Int, totalWeeks: Int): Course {
        // 报错时带上课程名，否则用户面对 19 条数据不知道是哪一条错了
        val rawName = obj.optString("name", "").trim()
        fun err(msg: String): Nothing {
            val who = if (rawName.isNotEmpty()) "第 ${index + 1} 门课（$rawName）" else "第 ${index + 1} 门课"
            throw FormatException("$who：$msg")
        }

        if (rawName.isEmpty()) err("缺少 name（课程名）")

        val dayOfWeek = parseDay(obj.opt("dayOfWeek"), ::err)

        val nodesArr = obj.optJSONArray("nodes")
            ?: err("缺少 nodes。写法是 [起始节次, 结束节次]，例如 [1, 2]。")
        if (nodesArr.length() != 2) err("nodes 必须是两个数字，例如 [1, 2]")
        val startNode = nodesArr.optInt(0, -1)
        val endNode = nodesArr.optInt(1, -1)
        if (startNode !in 1..MAX_NODE || endNode !in 1..MAX_NODE) {
            err("nodes 的节次必须在 1–$MAX_NODE 之间，现在是 [$startNode, $endNode]")
        }
        if (startNode > endNode) err("nodes 的起始节次比结束节次大：[$startNode, $endNode]")

        val weeksRaw = obj.opt("weeks") ?: err("缺少 weeks")
        val weeks = parseWeeks(weeksRaw, totalWeeks, ::err)

        return Course(
            id = "i%03d".format(index + 1),
            name = rawName,
            dayOfWeek = dayOfWeek,
            startNode = startNode,
            endNode = endNode,
            weeks = weeks,
            place = obj.optString("place", "").trim(),
            enabled = obj.optBoolean("enabled", true)
        )
    }

    private fun parseDay(value: Any?, err: (String) -> Nothing): Int {
        when (value) {
            is Int -> {
                if (value !in 1..7) err("dayOfWeek 必须在 1–7 之间（周一=1，周日=7），现在是 $value")
                return value
            }
            is String -> {
                val t = value.trim()
                t.toIntOrNull()?.let {
                    if (it !in 1..7) err("dayOfWeek 必须在 1–7 之间（周一=1，周日=7），现在是 $it")
                    return it
                }
                DAY_NAMES[t]?.let { return it }
                err("dayOfWeek 写的是 \"$t\"，只接受 1–7 或「周一」这类写法")
            }
        }
        err("缺少 dayOfWeek（1–7，周一=1）")
    }

    /** weeks 同时支持两种写法：字符串（给人手写）和数组（给程序生成） */
    private fun parseWeeks(value: Any?, totalWeeks: Int, err: (String) -> Nothing): Set<Int> {
        when (value) {
            is String -> {
                return try {
                    Weeks.parse(value, totalWeeks)
                } catch (e: FormatException) {
                    err(e.message ?: "weeks 格式有误")
                }
            }
            is JSONArray -> {
                if (value.length() == 0) err("weeks 数组是空的")
                val out = sortedSetOf<Int>()
                for (i in 0 until value.length()) {
                    val w = value.optInt(i, -1)
                    if (w !in 1..totalWeeks) {
                        err("weeks 数组里的 $w 超出 1–$totalWeeks 的范围")
                    }
                    out += w
                }
                return out
            }
        }
        err("weeks 必须是字符串（如 \"2-4,6-17\"）或数组（如 [2,3,4,6,7]）")
    }

    /**
     * 找出「同一时段有两门课，且周次有重叠」的情况。
     *
     * 注意这里必须比较**周次是否重叠**，不能只比时段。
     * 内置示例课表里就有一个正当的重叠例子：周四 7-8 节，
     * 数据结构占单周，程序设计基础占第 2 周 —— 时段相同但周次不相交，
     * 这是合法的，不能报警告。
     */
    private fun detectConflicts(courses: List<Course>): List<String> {
        val warnings = mutableListOf<String>()
        for (i in courses.indices) {
            for (j in i + 1 until courses.size) {
                val a = courses[i]
                val b = courses[j]
                if (a.dayOfWeek != b.dayOfWeek) continue
                if (a.startNode != b.startNode || a.endNode != b.endNode) continue

                val overlap = a.weeks intersect b.weeks
                if (overlap.isNotEmpty()) {
                    warnings += "${weekdayCn(a.dayOfWeek)}第 ${a.startNode}-${a.endNode} 节：" +
                            "「${a.name}」和「${b.name}」在第 " +
                            "${overlap.sorted().joinToString("、")} 周冲突"
                }
            }
        }
        return warnings
    }

    // ============================================================
    //  三、生成
    // ============================================================

    /**
     * 生成 JSON 文本。
     *
     * ## 为什么不用 JSONObject.toString(2)
     *
     * 一开始我用的是它，结果发现同一个函数在两个平台上产出的文件**字段顺序不同**：
     *
     *   Android 的 org.json 内部用 LinkedHashMap  -> 保持插入顺序
     *   标准版 org.json（单元测试用的）用 HashMap  -> 顺序随机
     *
     * 对一份「给人看、给人改」的文件来说这是致命的：用户照着手机上导出的文件
     * 学会了格式，再去读文档里的例子，会发现两者长得不一样。
     *
     * 所以这里**手写序列化**，把输出完全握在自己手里：
     * 字段顺序固定、数组写成一行、缩进统一。
     *
     * 教训：**只要输出是给人看的产物，就不要把它交给第三方库的默认行为。**
     * 库的实现细节（内部用什么 Map）不该泄漏到你的产品形态里。
     */
    fun serialize(
        termName: String,
        startDate: LocalDate,
        totalWeeks: Int,
        courses: List<Course>,
        templates: Map<DayType, List<Block>> = emptyMap(),
        dayTypes: DayTypePolicy? = null
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")

        sb.append("  \"format\": \"").append(FORMAT_ID).append("\",\n")
        sb.append("  \"version\": ").append(VERSION).append(",\n")

        sb.append("  \"term\": {\n")
        sb.append("    \"name\": \"").append(escape(termName)).append("\",\n")
        sb.append("    \"startDate\": \"").append(startDate).append("\",\n")
        sb.append("    \"totalWeeks\": ").append(totalWeeks).append("\n")
        sb.append("  },\n")

        // dayTypes 段：只有调用方明确给了策略才写。
        //
        // 放在 term 之后、courses 之前，是因为它描述的是「整份配置怎么跑」，
        // 属于全局设置；课表和模板都是它的下游。
        // 给人改的文件，**顺序本身就是一种说明**。
        if (dayTypes != null) {
            sb.append("  \"dayTypes\": {\n")
            sb.append("    \"enabled\": [")
            // 按标准顺序输出，保证同一份数据每次导出结果完全一致
            sb.append(
                Templates.ALL_TYPES.filter { it in dayTypes.enabled }
                    .joinToString(", ") { "\"${it.name}\"" }
            )
            sb.append("],\n")
            sb.append("    \"fallback\": \"").append(dayTypes.fallback.name).append("\"\n")
            sb.append("  },\n")
        }

        sb.append("  \"courses\": [\n")
        courses.forEachIndexed { i, c ->
            sb.append("    {\n")
            sb.append("      \"name\": \"").append(escape(c.name)).append("\",\n")
            sb.append("      \"dayOfWeek\": ").append(c.dayOfWeek).append(",\n")
            sb.append("      \"nodes\": [").append(c.startNode).append(", ").append(c.endNode).append("],\n")
            sb.append("      \"weeks\": \"").append(Weeks.format(c.weeks)).append('"')
            if (c.place.isNotBlank()) {
                sb.append(",\n      \"place\": \"").append(escape(c.place)).append('"')
            }
            // 默认值不写出来，文件更干净；解析时缺省即 true
            if (!c.enabled) {
                sb.append(",\n      \"enabled\": false")
            }
            sb.append('\n').append("    }")
            if (i < courses.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]")

        // templates 段。为空时整段不写 —— 导出的文件是给人看、给人改的，
        // 没改过的模板没必要把它那 100 多行铺进去。
        if (templates.isNotEmpty()) {
            sb.append(",\n")
            appendTemplates(sb, templates)
        }

        sb.append("\n}\n")
        return sb.toString()
    }

    /**
     * 写出 templates 段。
     * 按 Templates.ALL_TYPES 的固定顺序输出，保证同一份数据每次导出结果完全一致。
     *
     * ## withKey 这个参数是踩坑之后加的
     *
     * 同一个函数有两个用途，而它们需要的**外层形状不一样**：
     *
     *   withKey = true   写进整份课表文件里 → `"templates": { ... }`
     *   withKey = false  存进本地存储         → `{ ... }`（自己就是一份完整 JSON）
     *
     * 一开始两种都用了 true，于是存进 SharedPreferences 的是 `"templates": { ... }` ——
     * 这**不是合法的 JSON**（一个裸的键值对，没有外层大括号）。
     * 读回来时 JSONObject 构造直接抛异常，然后被 templatesFromJson 里
     * 「解析失败就返回空表」的兜底吞掉，用户改的模板**静默消失**。
     *
     * 症状特别隐蔽：导入时预览是对的（预览用的是内存里的对象），
     * 关掉界面再打开就打回原形，而且**一句报错都没有**。
     *
     * 这类「不报错的错」只有「存进去再读出来」的往返测试才能抓住。
     */
    private fun appendTemplates(
        sb: StringBuilder,
        templates: Map<DayType, List<Block>>,
        withKey: Boolean = true
    ) {
        val indent = if (withKey) "  " else ""
        val inner = if (withKey) "    " else "  "
        val deeper = if (withKey) "      " else "    "

        if (withKey) sb.append(indent).append("\"templates\": ")
        sb.append("{\n")

        val types = Templates.ALL_TYPES.filter { templates.containsKey(it) }
        types.forEachIndexed { ti, type ->
            sb.append(inner).append('"').append(type.name).append("\": [\n")
            val blocks = templates.getValue(type)
            blocks.forEachIndexed { bi, b ->
                sb.append(deeper).append("{ \"start\": \"").append(Slots.fmt(b.start)).append('"')
                sb.append(", \"end\": \"").append(Slots.fmt(b.end)).append('"')
                sb.append(", \"title\": \"").append(escape(b.title)).append('"')
                if (b.note.isNotBlank()) {
                    sb.append(", \"note\": \"").append(escape(b.note)).append('"')
                }
                // CHORE 是解析时的默认值，写出来是噪音
                if (b.kind != Kind.CHORE) {
                    sb.append(", \"kind\": \"").append(b.kind.name).append('"')
                }
                if (b.nodes != null) {
                    sb.append(", \"nodes\": [").append(b.nodes.first)
                        .append(", ").append(b.nodes.last).append(']')
                }
                sb.append(" }")
                if (bi < blocks.size - 1) sb.append(',')
                sb.append('\n')
            }
            sb.append(inner).append(']')
            if (ti < types.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append(indent).append("}")
    }

    /** JSON 字符串转义。少了这一步，课程名里出现引号就会生成一份坏文件。 */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 一份最小可用的示例，用于 App 内的「格式说明」。
     *
     * 版本号用 [VERSION] 而不是写死数字 —— 否则格式升级后这里会被忘掉，
     * 用户照着复制出去的示例反而是一份旧格式的文件。
     */
    fun exampleJson(): String = """
{
  "format": "timetable",
  "version": $VERSION,
  "term": {
    "name": "示例大学 2026 级 · 大一上",
    "startDate": "2026-09-07",
    "totalWeeks": 19
  },
  "dayTypes": {
    "enabled": ["A", "B_NORMAL", "SATURDAY", "SUNDAY"],
    "fallback": "B_NORMAL"
  },
  "courses": [
    {
      "name": "大学英语",
      "dayOfWeek": 1,
      "nodes": [1, 2],
      "weeks": "2-4,6-17",
      "place": "教一-101"
    },
    {
      "name": "数据结构",
      "dayOfWeek": 4,
      "nodes": [7, 8],
      "weeks": "3-17/2",
      "place": "实验楼-201"
    },
    {
      "name": "程序设计基础",
      "dayOfWeek": 4,
      "nodes": [7, 8],
      "weeks": "2",
      "place": "实验楼-201"
    }
  ]
}
""".trim()

    // ============================================================
    //  四、给本地存储用的模板序列化
    //
    //  Store 需要在 SharedPreferences 里存一份模板。这两个函数复用了上面
    //  同一套解析和写出逻辑，保证「存进去的」和「导出的」格式完全一致 ——
    //  不会出现「App 里跑得好好的模板，导出后反而导入不了」这种荒唐事。
    // ============================================================

    /**
     * 给本地存储用。
     *
     * ⚠️ `withKey = false` —— 写出的是**一份完整的 JSON 对象**（不带 "templates" 键名），
     * 因为它存进去之后要能被独立解析回来。详见 appendTemplates 的注释。
     */
    fun templatesToJson(templates: Map<DayType, List<Block>>): String {
        if (templates.isEmpty()) return ""
        val sb = StringBuilder()
        appendTemplates(sb, templates, withKey = false)
        return sb.toString()
    }

    /**
     * 读回本地存储里的模板。
     *
     * 解析失败返回空表，而不是抛异常 —— **存储损坏不能变成「程序打不开」**，
     * 否则用户连进去修的入口都没有。大不了重新改一遍模板，比起不了应用好得多。
     */
    fun templatesFromJson(json: String): Map<DayType, List<Block>> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            parseTemplates(obj, mutableListOf())
        }.getOrDefault(emptyMap())
    }

    /**
     * 给本地存储用：把日型策略写成一份独立的 JSON。
     *
     * 和 [templatesToJson] 一样，这里写出的是**不带外层键名的完整对象**
     * （`{"enabled":[...],"fallback":"..."}`），因为它要能被 [dayTypesFromJson]
     * 独立解析回来。这个「存进去的和导出的格式不一致」的坑，
     * templates 那边已经踩过一次了，详见 appendTemplates 的注释。
     */
    fun dayTypesToJson(policy: DayTypePolicy): String {
        val enabled = Templates.ALL_TYPES.filter { it in policy.enabled }
            .joinToString(",") { "\"${it.name}\"" }
        return "{\"enabled\":[$enabled],\"fallback\":\"${policy.fallback.name}\"}"
    }

    /**
     * 读回本地存储里的日型策略。
     *
     * 解析失败回落到 [DayTypePolicy.DEFAULT]，而不是抛异常 ——
     * 和 templatesFromJson 同样的理由：**存储损坏不能变成「程序打不开」**，
     * 否则用户连进去修的入口都没有。
     */
    fun dayTypesFromJson(json: String): DayTypePolicy {
        if (json.isBlank()) return DayTypePolicy.DEFAULT
        return runCatching {
            parseDayTypes(JSONObject(json))
        }.getOrDefault(DayTypePolicy.DEFAULT)
    }

    // ------------------------------------------------------------

    /** 日型的 JSON 键名 -> 枚举。导出和导入都靠它，保证两边一致。 */
    private val DAY_TYPE_NAMES: Map<String, DayType> =
        Templates.ALL_TYPES.associateBy { it.name }

    /** 时段性质的名字 -> 枚举。大小写不敏感（解析时统一转成大写再查）。 */
    private val KIND_NAMES: Map<String, Kind> =
        Kind.values().associateBy { it.name }

    private val DAY_NAMES = mapOf(
        "周一" to 1, "星期一" to 1, "禮拜一" to 1,
        "周二" to 2, "星期二" to 2,
        "周三" to 3, "星期三" to 3,
        "周四" to 4, "星期四" to 4,
        "周五" to 5, "星期五" to 5,
        "周六" to 6, "星期六" to 6,
        "周日" to 7, "周天" to 7, "星期日" to 7, "星期天" to 7
    )

    fun weekdayCn(dow: Int): String = when (dow) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }
}
