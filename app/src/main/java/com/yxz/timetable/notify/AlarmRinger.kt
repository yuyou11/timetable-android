package com.yxz.timetable.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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

    /** 渐强：从 15% 在 20 秒内升到 100% */
    private const val FADE_MS = 20_000f
    private const val FADE_FROM = 0.15f

    /** 通知按钮的动作（转发给 [ActionReceiver]） */
    const val ACTION_STOP = "com.yxz.timetable.ALARM_STOP"
    const val ACTION_SNOOZE = "com.yxz.timetable.ALARM_SNOOZE"

    private var player: MediaPlayer? = null
    private var ringingIds: List<String> = emptyList()
    private var snoozeMinutes: Int = Alarms.MIN_SNOOZE_MINUTES
    private var lastKey: String = ""
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var fadeStart = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop(AppContextHolder.ctx) }

    /** 正在响着哪几个闹钟（贪睡按钮要用它决定贪睡谁） */
    fun currentIds(): List<String> = ringingIds

    /** 这次该贪睡多久：取正在响的闹钟里最短的那个，但不低于下限 */
    fun currentSnoozeMinutes(): Int = snoozeMinutes

    fun isRinging(): Boolean = player != null

    /**
     * 这一轮响铃的「会话」还在不在 —— 从 [ring] 到 [stop] 之间都算。
     *
     * 和 [isRinging] 的区别：铃声文件坏了、MediaPlayer 起不来时 player 是空的，
     * 但闹钟**确实响了**（通知还在、窗口也该弹出来）。用「有没有声音」来判断
     * 会让这种时候的窗口一打开就被自己关掉。
     */
    fun isSessionActive(): Boolean = ringingIds.isNotEmpty()

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
        // 「上次响铃」留一条记录：自动停之后页面上还能看到它响过
        store.lastRangAt = System.currentTimeMillis()
        store.lastRangLabel = due.joinToString("、") { Alarms.describe(it, store.courses()) }

        ensureChannel(ctx)
        startSound(ctx, due.first(), store)
        notify(ctx, due, store)
    }

    /**
     * 响铃结束的监听者。目前只有 [MainActivity] 用：把「盖在锁屏上」的窗口收回去。
     *
     * 为什么挂监听而不是让调用方各自去收：停响铃的路有四条（通知按钮、页面上的按钮、
     * 自动停、关掉开关），漏掉任何一条，那个窗口都会继续压着锁屏 ——
     * 表现就是「闹钟早停了，手机却解不开锁」。
     *
     * 全部调用方都在主线程（广播接收器、点击、Handler），所以这里不额外加锁。
     */
    private val stopListeners = mutableListOf<() -> Unit>()

    fun addStopListener(l: () -> Unit) {
        if (l !in stopListeners) stopListeners += l
    }

    fun removeStopListener(l: () -> Unit) {
        stopListeners -= l
    }

    /** 停音 + 撤通知（通知里的「停止」、自动停、以及关掉开关时都走这里） */
    fun stop(ctx: Context?) {
        // 只有「本来真的在响」才算一次结束：startSound 开头那次静默清理也走
        // stopInternal，但它不该惊动监听者 —— 否则新闹钟一响，窗口就被收起来了
        val wasRinging = player != null || ringingIds.isNotEmpty()
        stopInternal(ctx)
        if (!wasRinging) return
        for (l in stopListeners.toList()) runCatching { l() }
    }

    private fun stopInternal(ctx: Context?) {
        runCatching {
            player?.let { if (it.isPlaying) it.stop(); it.release() }
        }
        player = null
        ringingIds = emptyList()
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(fade)
        abandonFocus()
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
        stopInternal(null)               // 上一首先停掉，避免叠在一起
        AppContextHolder.ctx = ctx.applicationContext
        requestFocus(ctx)
        val uri = soundUri(alarm, store)
        val ok = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(alarmAttributes())
                // 没有这行，息屏后 CPU 睡下去，声音可能被冻住 ——
                // 闹钟成了「响到一半自己闭嘴」。只在响铃这几分钟持有，代价可忽略。
                setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(ctx, uri)
                isLooping = true
                setVolume(FADE_FROM, FADE_FROM)
                prepare()
                start()
            }
        }.getOrNull()
        player = ok
        if (ok != null) startFade() else abandonFocus()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, store.ringMinutes * 60_000L)
    }

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /**
     * 渐强音量：20 秒从 15% 升到 100%。
     *
     * 突然满音量会把人吓醒，半夜也容易惊到室友；渐进是正经闹钟应用的标配。
     */
    private fun startFade() {
        fadeStart = System.currentTimeMillis()
        handler.removeCallbacks(fade)
        handler.post(fade)
    }

    private val fade = object : Runnable {
        override fun run() {
            val p = player ?: return
            val ratio = ((System.currentTimeMillis() - fadeStart).toFloat() / FADE_MS)
                .coerceIn(0f, 1f)
            val volume = FADE_FROM + (1f - FADE_FROM) * ratio
            runCatching { p.setVolume(volume, volume) }
            if (ratio < 1f) handler.postDelayed(this, 250L)
        }
    }

    /** 响铃时抢一次音频焦点：别让正在放的音乐和闹钟混在一起 */
    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarmAttributes())
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { am.requestAudioFocus(req) }
        audioManager = am
        focusRequest = req
    }

    private fun abandonFocus() {
        val am = audioManager
        val req = focusRequest
        if (am != null && req != null) runCatching { am.abandonAudioFocusRequest(req) }
        audioManager = null
        focusRequest = null
    }

    private fun notify(ctx: Context, due: List<Alarm>, store: Store) {
        val courses = store.courses()
        val title = if (due.size == 1) Alarms.describe(due.first(), courses) else "闹钟"
        val text = due.joinToString("、") { a ->
            "${a.timeText} ${Alarms.describe(a, courses)}"
        }
        val alarmWindow = alarmWindow(ctx)
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
            .setContentIntent(alarmWindow)
            // 锁屏 / 息屏时**直接把 App 窗口拉起来并停在闹钟页**（见 MainActivity）。
            // 亮屏且用户正在用手机时，系统会按设计降级成横幅通知 —— 那是系统行为，
            // 不是这里能决定的。
            .setFullScreenIntent(alarmWindow, true)
            .setDeleteIntent(action(ctx, ACTION_STOP, 54))
            .addAction(0, "停止", action(ctx, ACTION_STOP, 51))
            .addAction(0, "贪睡 $snoozeMinutes 分钟", action(ctx, ACTION_SNOOZE, 52))
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
            // 通知权限被撤销时静默跳过：声音已经由 App 自己放了，
            // 少一条通知不等于闹钟不响
        }
    }

    /**
     * 响铃时要拉起的窗口：**App 自己的窗口，直接停在闹钟页**。
     *
     * 点通知进的是这里，锁屏自动弹出的也是这里 —— 一个入口，不会出现
     * 「通知点进去是一个界面、自动弹出是另一个界面」这种分裂。
     * 闹钟页顶部那条「正在响铃 · 停止」由 AlarmFragment 负责显示。
     */
    private fun alarmWindow(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 55, MainActivity.alarmIntent(ctx),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

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
    @SuppressLint("StaticFieldLeak")   // 存的是 applicationContext，随进程走，不是泄漏
    private object AppContextHolder {
        var ctx: Context? = null
    }
}
