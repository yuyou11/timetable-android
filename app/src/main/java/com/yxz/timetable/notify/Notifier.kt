package com.yxz.timetable.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yxz.timetable.MainActivity
import com.yxz.timetable.R
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TimelineEngine
import com.yxz.timetable.ui.AppTheme
import com.yxz.timetable.ui.accentColor
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 常驻通知的绘制。
 *
 * ## 省电的关键：让系统帮我倒计时
 *
 * 「还剩 23 分钟」如果由 App 每秒刷新一次，那就是一天 86400 次唤醒 —— 电老虎。
 *
 * 这里用的是 `setUsesChronometer(true)` + `setChronometerCountDown(true)`：
 * 我们只告诉系统「到 10:05 为止」，**剩下的倒计时由系统桌面自己去画**，
 * 一秒一次的重绘发生在系统进程里，我们的 App 全程在睡觉。
 *
 * 结论：这个通知从发出到下一次切换之前，App 一次都不会醒。
 *
 * ============================================================
 *  为什么不画进度条（这里踩过一个坑）
 * ============================================================
 *
 * 曾经这里有过一行 `.setProgress(100, pct, false)`，注释还写着
 * 「一次性给个百分比，而不是自己定时去改它」—— 想法是「算一次就准了」。
 *
 * **这个前提是错的**：百分比描述的是「当前这一格走了多少」，
 * 它每一分钟都在变，根本不是算一次就能定下来的东西。
 *
 * 而通知只在**时段边界**被刷新（闹钟排的就是各格的 start 时刻），
 * 那一刻 `minute == cur.start`，于是：
 *
 *     pct = (minute - cur.start) * 100 / cur.duration  =  0
 *
 * 所以进度条不是「卡住不动」，而是**恒为 0、从来就没填过一格**。
 * 用户看到的是一条永远空着的槽，观感很差。
 *
 * 那能不能让它动起来？**真正的实时和这个 App 的省电设计是互斥的**：
 *   - 每分钟更新 → 一天 1440 次唤醒，等于把省电设计整个推翻
 *   - 开前台服务渲染 → 通知栏还得多挂一条「App 正在运行」
 *   - 分档更新（25%/50%/75%）→ 会动，但每天多几十次唤醒，且仍不是实时
 *
 * 而**倒计时已经是实时的了，而且是系统免费画的**（上一条）。
 * 进度条提供的是一条重复信息，代价却是要么不准、要么费电。
 *
 * 所以：**删掉。** 与其显示一个永远不动的进度条，
 * 不如把「还剩多少」交给那个本来就免费又准确的倒计时。
 *
 * 通用教训：**一个显示不出来的信息，比没有这个信息更糟** ——
 * 用户会盯着它找原因。
 */
object Notifier {

    const val CHANNEL_ID = "now_doing"
    const val NOTIF_ID = 1001

    /** 通知渠道。Android 8.0 起必须先建渠道才能发通知 */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // 如果渠道已存在，直接返回。
        // 注意：渠道一旦创建，它的重要性（IMPORTANCE）就**不能再由代码修改**了 ——
        // 用户永远有最终决定权。所以这里不要反复 createNotificationChannel 试图改设置。
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID,
            "此刻该做什么",
            // IMPORTANCE_LOW：显示在通知栏，但不响铃、不震动、不弹横幅。
            // 上课时手机在口袋里震一下是很糟糕的体验，所以这里必须是 LOW。
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "常驻通知栏，显示当前时段与下一项安排"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun isAllowed(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** 重画（或首次发出）常驻通知 */
    fun update(ctx: Context) {
        val store = Store(ctx)

        // 通知里那个小图标要跟着主题染色，但传进来的 ctx 主题是**不对**的：
        // 广播接收器给的是 Application context，它身上挂的是 manifest 里
        // 声明的主题，不是用户选的那套。所以要包一层 ContextThemeWrapper
        // 才能解析 ?attr/accentColor。
        //
        // 这是 Android 里一个反复出现的模式：**主题跟着 Context 走**。
        // 「拿不到颜色」十有八九是 Context 的主题不对，而不是颜色没定义。
        val themeCtx: Context = ContextThemeWrapper(
            ctx, AppTheme.from(store.themeKey).resId
        )

        if (!store.enabled) {
            cancel(ctx)
            return
        }
        ensureChannel(ctx)
        if (!isAllowed(ctx)) return   // 没给通知权限，静默跳过

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val today = now.toLocalDate()
        val minute = now.hour * 60 + now.minute

        // ---- 暂停状态 ----
        val snoozeUntil = store.snoozeUntil
        if (snoozeUntil > System.currentTimeMillis()) {
            post(ctx, buildSnoozed(ctx, snoozeUntil))
            return
        } else if (snoozeUntil != 0L) {
            store.snoozeUntil = 0L   // 暂停已过期，清掉标记
        }

        // ---- 正常状态 ----
        val week = store.weekOf(today)
        val courses = store.courses()
        // 一次取出整份作息配置（模板 + 日型策略），后面统一用它 ——
        // 分开取两次不但多读一遍 SharedPreferences，
        // 更要紧的是给了「两处不一致」可乘之机
        val templates = store.templates()
        val moments = TimelineEngine.moments(today, week, courses, templates)
        if (moments.isEmpty()) return

        val cur = TimelineEngine.currentAt(moments, minute) ?: return
        val next = TimelineEngine.nextAfter(moments, minute)
        val type = TimelineEngine.dayType(today, week, courses, templates.policy)

        val endAtMillis = today.atStartOfDay(zone)
            .plusMinutes(cur.end.toLong())
            .toInstant().toEpochMilli()

        val rangeText = "${Slots.fmt(cur.start)}–${Slots.fmt(cur.end)}"
        val whereText = if (cur.place.isNotBlank()) " · ${cur.place}" else ""

        // 明天那一行：日型 + 起床时间。放在展开视图里，
        // 晚上躺床上拉一下通知栏就能知道明天几点起、第一节什么课。
        val tomorrow = today.plusDays(1)
        val tWeek = store.weekOf(tomorrow)
        val tType = TimelineEngine.dayType(tomorrow, tWeek, courses, templates.policy)
        val tWake = TimelineEngine.wakeMinute(
            TimelineEngine.template(tType, templates)
        )

        val expanded = buildString {
            append("▸ 现在　").append(cur.title)
            append('\n').append("　　").append(rangeText).append(whereText)
            if (cur.note.isNotBlank()) append('\n').append("　　").append(cur.note)
            append("\n\n▸ 接着　")
            append(if (next != null) "${Slots.fmt(next.start)}　${next.title}" else "今天没有下一项了")
            append("\n\n▸ 今天　第 ").append(week).append(" 周 · ").append(weekdayLabel(today.dayOfWeek.value))
            append(" · ").append(
                TimelineEngine.dayTypeDisplay(today, week, courses, templates.policy)
            )
            append("\n▸ 明天　").append(weekdayLabel(tomorrow.dayOfWeek.value))
            append(" · ").append(
                TimelineEngine.dayTypeDisplay(tomorrow, tWeek, courses, templates.policy)
            )
            if (tWake != null) append(" · ").append(Slots.fmt(tWake)).append(" 起床")
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(themeCtx.accentColor())
            .setContentTitle("现在：${cur.title}")
            // 折叠状态下只写「时间段 + 地点」。
            //
            // ⚠️ 这里本来还有一句「· 剩 N 分钟」，已经删掉了。
            // 原因是它**不会走**：通知只在时段切换时刷新一次，
            // 那句话是刷新那一刻算死的文本 —— 08:00 发出时写着「剩 125 分钟」，
            // 到 09:59 还是 125。
            //
            // 而右上角那个由系统绘制的倒计时是真的每秒在走
            // （setUsesChronometer + setChronometerCountDown）。
            // 两个时间同时摆在一条通知上、其中一个还是死的，只会让人困惑。
            //
            // 想让它真的走，就得每分钟唤醒一次 App（一天 1440 次），
            // 等于把整个省电设计推翻 —— 详见文件顶部那段说明。
            .setContentText(rangeText + whereText)
            .setSubText(
                "第 $week 周 · " +
                        TimelineEngine.dayTypeDisplay(today, week, courses, templates.policy)
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setWhen(endAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)     // 倒计时（而不是正计时），由系统绘制
            // 这里曾经有一行 setProgress —— 已删除，原因见文件顶部的注释。
            // 简单说：它在时段边界刷新时算出来恒为 0，永远不动；而倒计时
            // 已经是实时的了，进度条是重复信息。
            .setOnlyAlertOnce(true)            // 更新时不重新提醒
            .setSilent(true)
            .setOngoing(true)                  // 常驻，划不掉
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 锁屏可见性由用户在设置里决定：
            //   PUBLIC → 锁屏上能看到课程名
            //   SECRET → 通知根本不送到锁屏
            // 注意这只管到 App 这一层。系统还有一道总开关，见 Store.lockscreenVisible 的注释。
            .setVisibility(
                if (store.lockscreenVisible) NotificationCompat.VISIBILITY_PUBLIC
                else NotificationCompat.VISIBILITY_SECRET
            )
            .setContentIntent(openApp(ctx))
            .addAction(0, "暂停 1 小时", action(ctx, ActionReceiver.ACTION_SNOOZE, 31))
            .addAction(0, "关闭常驻", action(ctx, ActionReceiver.ACTION_DISABLE, 32))

        post(ctx, builder.build())
    }

    private fun buildSnoozed(ctx: Context, until: Long): Notification {
        val t = java.time.Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).toLocalTime()
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF757575.toInt())
            .setContentTitle("已暂停")
            .setContentText("$hhmm 后自动恢复")
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "立即恢复", action(ctx, ActionReceiver.ACTION_RESUME, 33))
            .addAction(0, "关闭常驻", action(ctx, ActionReceiver.ACTION_DISABLE, 32))
            .build()
    }

    private fun post(ctx: Context, n: Notification) {
        runCatching {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
        }
    }

    fun cancel(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 30, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知按钮的 PendingIntent。
     *
     * 两个必须做对的地方：
     *  1. **requestCode 必须各不相同**，否则后一个按钮会覆盖前一个的行为
     *     （PendingIntent 是按 (requestCode, Intent) 去重的）。
     *  2. **FLAG_IMMUTABLE**：Android 12 起强制要求声明可变性，
     *     我们的 Intent 不需要被外部填充，所以用 IMMUTABLE，顺便也更安全。
     */
    private fun action(ctx: Context, act: String, req: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            action = act
            setPackage(ctx.packageName)
        }
        return PendingIntent.getBroadcast(
            ctx, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun weekdayLabel(dow: Int) = when (dow) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }
}
