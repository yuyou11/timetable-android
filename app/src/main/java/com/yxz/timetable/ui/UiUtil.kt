package com.yxz.timetable.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.yxz.timetable.R
import com.yxz.timetable.data.Kind
import java.time.LocalDate

/** 时段性质 -> 颜色。用色条一眼区分「在上课」还是「自由时间」 */
fun kindColor(kind: Kind): Int = when (kind) {
    Kind.SLEEP -> Color.parseColor("#78909C")
    Kind.MEAL -> Color.parseColor("#FFA726")
    Kind.CLASS -> Color.parseColor("#42A5F5")
    Kind.STUDY -> Color.parseColor("#7E57C2")
    Kind.TRAIN -> Color.parseColor("#EF5350")
    Kind.FREE -> Color.parseColor("#66BB6A")
    Kind.CHORE -> Color.parseColor("#26A69A")
    Kind.TRANSIT -> Color.parseColor("#BDBDBD")
}

// 注意：中文星期名（周一…周日）的唯一实现在 ScheduleFormat.weekdayCn ——
// 解析报错信息也用它，两处各写一份迟早会说两样话。UI 里直接 import 使用。

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/** 建一个等宽的格子（课表用） */
fun Context.makeCell(text: String, weight: Float = 1f): TextView =
    TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(this@makeCell, R.color.text_primary))
        textSize = 10f
        this.text = text
        setPadding(dp(2), dp(4), dp(2), dp(4))
        maxLines = 5
    }

fun LocalDate.rangeWithWeekEnd(): Pair<LocalDate, LocalDate> = this to this.plusDays(6)
