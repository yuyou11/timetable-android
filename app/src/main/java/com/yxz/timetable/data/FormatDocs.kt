package com.yxz.timetable.data

/**
 * 数据格式标准里「给人看」的文本：格式说明 + 示例 JSON。
 *
 * 从 ScheduleFormat 拆出来：那份文件里原本混着 140 行纯文本，
 * 解析逻辑反而被淹没。文本单独成文件后，ScheduleFormat 剩下的全是代码。
 *
 * 调用入口仍是 `ScheduleFormat.formatHelp()` / `exampleJson()`（一行委托），
 * 所以 FormatTextTest 对它们的断言（版本号、dayTypes 说明与代码一致）
 * 一个字都不用改。
 *
 * 通用教训（随原文保留）：**凡是「用户会照着做」的文本，都值得放在
 * 能被测试的地方。** 它离代码越远，脱节得越久才被发现 ——
 * 它原本写在设置页的 Fragment 里，格式从 v1 升到 v3，它一直写着「当前是 1」。
 */
internal object FormatDocs {

    /**
     * App 内「格式说明」页显示的文字。
     * 版本号用 [ScheduleFormat.VERSION] 插值，不写死数字。
     */
    fun help(): String = """
        【顶层】
        format    固定为 "${ScheduleFormat.FORMAT_ID}"
        version   格式版本，当前是 ${ScheduleFormat.VERSION}
        term      学期设置（可选）
        periods   节数信息（可选，如 "442"）
        dayTypes  启用哪些日型（可选）
        courses   课程数组（可选）
        templates 作息模板（可选）

        上面几段都是「没写 = 不要动」，不是「清空」。

        【periods 节数信息】
        一串数字（1-14 位，每位 1-9），从左到
        右是当天从早到晚各时段的节数；各位
        之和 = 一天有多少节：
          "442"  共 10 节（默认，内置结构）
          "441"  共 9 节，没有第 10 节
          "445"  共 13 节，第 11-13 节的时间
                 按晚课节奏外推
        之和不能超过 14：第 11 节起 45 分钟
        一节、间歇 5 分钟、20:40 起始，到
        第 14 节 23:55 下课，一天排不下更多。
        课表网格按它分行；导出时只有非默认
        结构才会写出这个字段。

        【term 段】
        name        学期名称，随便写
        startDate   教学第 1 周的周一，YYYY-MM-DD
        totalWeeks  总周数，默认 19

        【dayTypes 段】
        决定哪几种日型真的会被用到。不写就用默认那套。

        enabled   启用的日型，至少一个，如
                  ["A", "B_NORMAL", "SATURDAY", "SUNDAY"]
        fallback  算出来的日型没启用时，改用哪一套

        全天没课的工作日按休息日过（无课、无晚自习），
        不参与 enabled / fallback 的映射，文件里也写不了它。

        六种日型：A（有早八）、B_TRAIN_A（周二力量A）、
        B_TRAIN_B（周四力量B）、B_NORMAL（没早八）、
        SATURDAY、SUNDAY

        【每门课】
        name       课程名
        dayOfWeek  1–7（周一=1，周日=7）
                   也接受 "周一" 这种写法
        nodes      [起始节次, 结束节次]，节次
                   1–14（实际范围由 periods 声明
                   的节数决定，默认 442 即 1–10；
                   第 11-14 节按晚课节奏外推）
        weeks      周次，写法见下
        place      教室（可选）
        enabled    false 表示先停掉这门课（可选）

        【weeks 周次写法】
        "3"          第 3 周
        "2-4"        第 2 到 4 周
        "3-17/2"     单周：3、5、7…17
        "2-16/2"     双周：2、4、6…16
        "2-4,6-17"   分段
        "*"          全学期

        中文逗号「，」和波浪号「～」也会被识别。
        weeks 也可以写成数组，如 [2,3,4,6,7]。

        【设计约定】
        · 周次不能超过 totalWeeks
        · startDate 必须是周一
        · 未知字段会被忽略，所以将来标准加字段
          不会让老版本 App 出错
        · 同一时段可以有多门课，只要周次不重叠
          （比如单周一门、双周另一门）
    """.trimIndent()

    /**
     * 一份最小可用的示例，用于 App 内的「格式说明」。
     *
     * 版本号用 [ScheduleFormat.VERSION] 而不是写死数字 —— 否则格式升级后
     * 这里会被忘掉，用户照着复制出去的示例反而是一份旧格式的文件。
     */
    fun exampleJson(): String = """
{
  "format": "timetable",
  "version": ${ScheduleFormat.VERSION},
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
}
