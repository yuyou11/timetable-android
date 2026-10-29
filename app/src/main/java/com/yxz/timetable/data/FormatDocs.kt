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
        format        固定为 "${ScheduleFormat.FORMAT_ID}"
        version       格式版本，当前是 ${ScheduleFormat.VERSION}
        periods       节数信息（可选，如 "442"）
        term          学期设置（可选）
        dayTypeConfig 日型配置（可选）
        dayTypeNames  自定义日型名（可选）
        courses       课程数组（可选）
        templates     作息模板（可选）
        alarms        闹钟数组（可选，v6）
        events        日历日程数组（可选，v6）

        上面几段都是「没写 = 不要动」，不是「清空」。
        写了空数组 [] 才是「把它清空」。

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

        【dayTypeConfig 段】
        日型用 id "1"-"7" 标识，名字可以改（见下一段）：

          "1"  A 型日            有早八的工作日优先用
          "2"  B 型日            无早八的普通工作日
          "3"  训练日 · 力量A    默认闲置，映射后启用
          "4"  训练日 · 力量B    默认闲置，映射后启用
          "5"  周六
          "6"  周日
          "7"  无课 · 休息日     引擎自动判定，不可删除

        types        现存的日型 id；"7" 缺了自动补回
        weekdayMap   恰好 7 项（周一..周日），
                     每项 ∈ types 且不能是 "7"
        earlyType    有早八时优先用的日型；
                     省略 / null = 不区分早八

        例：
        "dayTypeConfig": {
          "types": ["1", "2", "5", "6", "7"],
          "weekdayMap": ["2", "2", "2", "2", "2", "5", "6"],
          "earlyType": "1"
        }
        想恢复经典的「周二/周四训练日」排法，把 weekdayMap
        改成 ["2","3","2","4","2","5","6"] 即可。

        全天没课的工作日按休息日过（无课、无晚自习），
        不受星期映射影响，文件里也映射不了它。

        【dayTypeNames 段】
        给日型起自己的名字，只影响显示不改行为：
          "dayTypeNames": { "3": "力量日" }
        键是 id，值最多 20 个字。写空对象 {} = 清空
        全部自定义名、恢复默认。

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
        teacher    任课老师（可选）
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

        【templates 段】
        键是日型 id（"1"-"7"）；v3 老文件里的
        A、B_TRAIN_A 等旧键名也认识。
        "7"（休息日）的模板同样可以自定义。
        **只写想改的那几种**，没写的回落到内置模板。
        占位格（带 nodes 的格子）里写的时刻就是
        上课时间 —— 改上课时间就是改这里的时刻。

        【alarms 段】（v6，完整备份才带）
        hour / minute   几点几分
        days            星期几 [1-7]，1 = 周一；
                        [] 表示「只响一次」，配合 once
        once            只响一次的那天，YYYY-MM-DD
        label           闹钟名字（可选）
        source          MANUAL / COURSE / MOMENT
        courseId        课程闹钟绑的课程 id
        lead            提前几分钟（0 = 到点）
        snooze          贪睡分钟，默认 10
        skipHolidays    节假日不响，默认 true
        导入时课程闹钟会按「课名 + 星期 + 起始节」
        重新绑到本机的课程上，id 不参与去重。

        【events 段】（v6，完整备份才带）
        date            哪一天，YYYY-MM-DD
        start / end     起止分钟（距当天 00:00）
        title           标题（必填）
        place / note    地点、备注（可选）
        weekly          每周重复，默认 false
        until           重复到哪天为止（可选）

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
  "dayTypeConfig": {
    "types": ["1", "2", "5", "6", "7"],
    "weekdayMap": ["2", "2", "2", "2", "2", "5", "6"],
    "earlyType": "1"
  },
  "courses": [
    {
      "name": "大学英语",
      "dayOfWeek": 1,
      "nodes": [1, 2],
      "weeks": "2-4,6-17",
      "place": "教一-101",
      "teacher": "王雅琴"
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
  ],
  "templates": {
    "1": [
      { "start": "00:00", "end": "06:55", "title": "睡觉", "kind": "SLEEP" },
      { "start": "06:55", "end": "07:10", "title": "起床、洗漱" },
      { "start": "08:30", "end": "10:05", "title": "第 1-2 节", "kind": "CLASS", "nodes": [1, 2] }
    ]
  }
}
""".trim()
}
