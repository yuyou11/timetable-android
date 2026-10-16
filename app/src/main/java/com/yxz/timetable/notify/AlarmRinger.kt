package com.yxz.timetable.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yxz.timetable.MainActivity
import com.yxz.timetable.R
import com.yxz.timetable.data.Alarm
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.Slots
import com.yxz.timetable.data.Store

/**
 * 闹钟响铃。
 *
 * ## 为什么声音由 App 自己放，而不是交给通知渠道
 *
 * Android 的通知渠道**创建之后声音就固定了**，代码再也改不了（以用户的选择为准）。
 * 所以「设置里换声音」这件事根本没法用渠道做 —— 只能自己播。
 * 这也是正经闹钟应用的做法：渠道只负责把界面弹出来，声音归自己管。
 *
 * ## 会不会一直响
 *
 * 不会。[AUTO_STOP_MINUTES] 分钟之后自己停 —— 闹钟是为了叫醒你，
 * 不是为了在你出门之后把整间宿舍吵醒。停止的三种方式：通知里的「停止」按钮、
 * 「贪睡」按钮、或者响够时间自动停。
 *
 * ## 进程被杀会怎样（诚实说明）
 *
 * 这里没有前台服务：响铃期间如果系统把进程回收了，声音会中断，**但通知还在**。
 * 要绝对可靠就得挂一个前台服务，而那意味着响铃期间常驻一条「App 正在运行」的通知 ——
 * 和这个 App「空闲期进程根本不存在」的设计直接冲突。所以先按这个折中做。
 */
object AlarmRinger {

    const val CHANNEL_ID = "alarm_ring"
    private const val NOTIF_ID = 3001

    /** 响多久自动停 */
    private const val AUTO_STOP_MINUTES = 3

    /** 通知按钮的动作（转发给 [ActionReceiver]） */
    const val ACTION_STOP = "com.yxz.timetable.ALARM_STOP"
    const val ACTION_SNOOZE = "com.yxz.timetable.ALARM_SNOOZE"

    private var player: MediaPlayer? = null
    private var ringingIds: List<String> = emptyList()
    private var snoozeMinutes: Int = Alarms.MIN_SNOOZE_MINUTES
    private var lastKey: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop(AppContextHolder.ctx) }

    /** 正在响着哪几个闹钟（贪睡按钮要用它决定贪睡谁） */
    fun currentIds(): List<String> = ringingIds

    /** 这次该贪睡多久：取正在响的闹钟里最短的那个，但不低于下限 */
    fun currentSnoozeMinutes(): Int = snoozeMinutes

    fun isRinging(): Boolean = player != null

    /**
     * 响铃。[due] 是这一分钟里所有该响的闹钟（可能不止一个：
     * 起床 + 吃药设在同一分钟是常事，合并成一次唤醒 + 一次响铃）。
     */
    fun ring(ctx: Context, due: List<Alarm>) {
        if (due.isEmpty()) return
        AppContextHolder.ctx = ctx.applicationContext

        // 去重：同一次闹钟可能因为进程重启、或通知链和闹钟链同时走到这里而响两遍。
        // 键是「日期 + 闹钟 id 列表」，和提醒那边的 lastReminderKey 一个路子。
        val key = java.time.LocalDate.now().toString() + "|" + due.map { it.id }.sorted()
        if (key == lastKey && isRinging()) return
        lastKey = key

        val store = Store(ctx)
        ringingIds = due.map { it.id }
        snoozeMinutes = due.minOf { it.snoozeMinutes }.coerceAtLeast(Alarms.MIN_SNOOZE_MINUTES)

        ensureChannel(ctx)
        startSound(ctx, due.first(), store)
        notify(ctx, due, store)
    }

    /** 停音 + 撤通知（通知里的「停止」、自动停、以及关掉开关时都走这里） */
    fun stop(ctx: Context?) {
        runCatching {
            player?.let { if (it.isPlaying) it.stop(); it.release() }
        }
        player = null
        ringingIds = emptyList()
        handler.removeCallbacks(autoStop)
        if (ctx != null) {
            runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 选铃声：闹钟自己设的 → 设置页的默认 → 系统默认闹钟铃声。
     * 三级兜底，保证「一定能响」，不会因为 URI 失效变成无声闹钟。
     */
    private fun soundUri(alarm: Alarm, store: Store): Uri {
        for (s in listOf(alarm.soundUri, store.alarmSoundUri)) {
            if (s.isNotBlank()) {
                val u = runCatching { Uri.parse(s) }.getOrNull()
                if (u != null) return u
            }
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    private fun startSound(ctx: Context, alarm: Alarm, store: Store) {
        stop(null)                       // 上一首先停掉，避免叠在一起
        AppContextHolder.ctx = ctx.applicationContext
        val uri = soundUri(alarm, store)
        val ok = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        player = ok
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MINUTES * 60_000L)
    }

    private fun notify(ctx: Context, due: List<Alarm>, store: Store) {
        val courses = store.courses()
        val title = if (due.size == 1) Alarms.describe(due.first(), courses) else "闹钟"
        val text = due.joinToString("、") { a ->
            "${a.timeText} ${Alarms.describe(a, courses)}"
        }
        val ringScreen = ringScreen(ctx)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFEF5350.toInt())
            .setContentTitle("⏰ $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 不设成 ongoing：**划掉通知也算一种「停」**，由 deleteIntent 接管。
            // 响铃这种"正在发生的坏事"，出口能多一条就多一条。
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(ringScreen)
            // 锁屏 / 息屏时直接把响铃界面拉起来（拿不到权限就退化成横幅通知）
            .setFullScreenIntent(ringScreen, true)
            .setDeleteIntent(action(ctx, ACTION_STOP, 54))
            .addAction(0, "停止", action(ctx, ACTION_STOP, 51))
            .addAction(0, "贪睡 $snoozeMinutes 分钟", action(ctx, ACTION_SNOOZE, 52))
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, builder.build()) }
    }

    /** 响铃界面（点通知、或锁屏直接弹出）—— 那里一定有「停止」和「贪睡」两个大按钮 */
    private fun ringScreen(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 55, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun action(ctx: Context, action: String, code: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            this.action = action
            setPackage(ctx.packageName)
        }
        return PendingIntent.getBroadcast(
            ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 响铃用的是**静默渠道**：声音已经由 App 自己放了，渠道再响一次就成了双份。
     * 渠道在这里只负责「高优先级 + 锁屏可见 + 有按钮」。
     */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "闹钟响铃时弹出，带停止 / 贪睡按钮（声音由 App 播放）"
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /** 自动停的时候需要一个 Context —— 响铃时记下来，之后就不用再要了 */
    private object AppContextHolder {
        var ctx: Context? = null
    }
}
