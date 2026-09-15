package com.yxz.timetable.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yxz.timetable.MainActivity
import com.yxz.timetable.R
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TimelineEngine
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 课前提醒。
 *
 * ## 和常驻通知的区别
 *
 * 常驻通知回答「现在该干什么」，是**状态**，所以你随时看一眼它都在那儿，
 * 但它必须安静 —— 上课时手机在口袋里震一下是很糟糕的体验。
 *
 * 课前提醒回答「再过 10 分钟要上课了」，是**事件**，它必须能打断你：
 * 响铃、震动、弹出横幅。用完就消失了，不需要常驻。
 *
 * 两者性质相反，所以必须用**两个不同的通知渠道**。
 * 而且要理解一点：渠道的重要性（IMPORTANCE）创建之后就**不能再由代码修改**，
 * 用户永远有最终决定权。所以这里给提醒渠道 HIGH 是「建议值」，
 * 真正说了算的是用户在系统设置里的选择 —— 这也是为什么设置页里
 * 要放一个跳转到通知设置的入口。
 */
object ReminderNotifier {

    const val CHANNEL_ID = "class_reminder"
    private const val NOTIF_ID_BASE = 2000

    /**
     * 判断「提醒时刻到了」的时间窗（分钟）。
     *
     * 闹钟在 Doze 下可能晚几秒到几分钟才触发，所以不能要求「正好等于」。
     * 用 ±2 分钟窗口兜住这点偏差；配合下面的去重，不会重复提醒。
     */
    private const val WINDOW_MINUTES = 2

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID,
            "上课提醒",
            // HIGH：会响铃 + 震动 + 弹出横幅。提醒的意义就在于打断你。
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "在上课前若干分钟提醒你"
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 检查此刻是否有提醒该发出。由闹钟唤醒 App 时调用。
     *
     * 为什么要**重新计算**而不是把课名塞进闹钟的 Intent 里带过来？
     * 因为闹钟排下去到触发之间可能过了好几个小时，中间用户完全可能
     * 改了课表或者导入了一份新数据。重新算一遍拿到的才是当下正确的信息，
     * 而 Intent 里带着的是排程那一刻的旧快照。
     */
    fun fireDue(ctx: Context) {
        val store = Store(ctx)
        val lead = store.remindLead
        if (lead <= 0 || !store.enabled) return
        if (!Notifier.isAllowed(ctx)) return

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val today = now.toLocalDate()
        val nowMinute = now.hour * 60 + now.minute

        val moments = TimelineEngine.moments(
            today, store.weekOf(today), store.courses(), store.templates()
        )

        // 只对「课」提醒，不对吃饭睡觉提醒 —— 那些不需要你提前十分钟准备
        val due = moments.filter { m ->
            m.isCourse && m.start - lead in (nowMinute - WINDOW_MINUTES)..(nowMinute + WINDOW_MINUTES)
        }
        if (due.isEmpty()) return

        ensureChannel(ctx)

        for (m in due) {
            // 去重键：日期 + 开始时刻 + 课程名。
            // 少了它，同一节课在窗口期内可能被提醒两次 ——
            // 因为闹钟触发和界面刷新都可能走到这里。
            val key = "${today}|${m.start}|${m.title}"
            if (store.lastReminderKey == key) continue
            store.lastReminderKey = key

            val minutesLeft = m.start - nowMinute
            val whenText = if (minutesLeft <= 0) "现在就开始" else "$minutesLeft 分钟后"

            val where = if (m.place.isNotBlank()) "　·　${m.place}" else ""
            // 注意 ${whenText} 的花括号不能省：中文是合法的标识符字符，
            // 写成 "$whenText开始" 时 Kotlin 会把它整体当成变量名 whenText开始，
            // 报 Unresolved reference。**在中文旁边插值，一律加花括号。**
            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFFEF5350.toInt())
                .setContentTitle("${whenText}：${m.title}")
                .setContentText("${Slots.fmt(m.start)}–${Slots.fmt(m.end)}$where")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("${whenText}开始：${m.title}\n${Slots.fmt(m.start)}–${Slots.fmt(m.end)}$where")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                // 和常驻通知用同一个开关：用户说「别在锁屏显示」就两个都不显示。
                // 反正提醒依然会响铃震动，不会因为锁屏看不见就失效。
                .setVisibility(
                    if (store.lockscreenVisible) NotificationCompat.VISIBILITY_PUBLIC
                    else NotificationCompat.VISIBILITY_SECRET
                )
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)

            runCatching {
                NotificationManagerCompat.from(ctx)
                    .notify(NOTIF_ID_BASE + m.start, builder.build())
            }
        }
    }

    /** 关掉常驻时把残留的提醒也一并清掉 */
    fun cancelAll(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        runCatching { nm.cancelAll() }
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 40, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
