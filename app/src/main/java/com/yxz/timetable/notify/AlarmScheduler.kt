package com.yxz.timetable.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yxz.timetable.data.Store
import com.yxz.timetable.data.TimelineEngine
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 闹钟排程 —— 这个 App 省电的全部秘密就在这里。
 *
 * ## 反面做法（千万别这么写）
 *
 * ```
 * // 每 30 秒醒一次，看看该不该更新通知
 * handler.postDelayed(poll, 30_000)
 * ```
 * 一天 2880 次唤醒，每次都要拉起 CPU、读一遍数据、比一遍时间。
 * 更糟的是这需要开一个前台服务常驻，通知栏还得再挂一条「App 正在运行」。
 *
 * ## 这里的做法：只排「下一个切换点」
 *
 * 时间轴的每一格都有明确的结束时刻。所以：
 *
 * ```
 * 醒来 → 改通知 → 算出下一个切换点 → 排一个闹钟 → 立刻死掉
 * ```
 *
 * 一天大约 20~25 个切换点，每次存活几十毫秒。
 * **没有轮询、没有常驻服务，空闲期 App 进程根本不存在。**
 *
 * ## 为什么必须用 setExactAndAllowWhileIdle
 *
 * 普通闹钟（set）在系统进入 Doze（深度休眠）后会被推迟，
 * 可能晚十几分钟 —— 对于「10:05 该去下一节课」这种场景是不可接受的。
 *
 * setExactAndAllowWhileIdle 能穿透 Doze 精确触发，
 * 代价是系统限制它大约每 9 分钟最多一次 —— 而我们的闹钟间隔最短也有 5 分钟，
 * 绝大多数在 20 分钟以上，完全不受影响。
 */
object AlarmScheduler {

    private const val REQUEST_CODE = 2001

    /** 重新排下一次唤醒。任何时候只要「当前状态」变了，就调它 */
    fun schedule(ctx: Context) {
        val store = Store(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx)

        if (!store.enabled) {
            am.cancel(pi)
            return
        }

        val now = System.currentTimeMillis()
        val at = nextTriggerAt(store, now)

        if (canExact(am)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                return
            } catch (_: SecurityException) {
                // 权限在运行中被撤销，退化为不精确闹钟，至少不会崩
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx))
    }

    /** 系统是否允许我们使用「精确闹钟」（Android 12+ 需要用户授权） */
    fun canExact(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true

    fun canExact(ctx: Context): Boolean =
        canExact(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    // ------------------------------------------------------------------

    /**
     * 算出「下一次需要醒来的时刻」。
     *
     * 醒来的理由有两种，取更早的那个：
     *
     *   1. **时段切换** —— 通知内容要换成新的一格，排在下格的开始时间。
     *      注意是开始时间不是结束时间：通知说的是「现在该干什么」，
     *      只有换了格子才需要改。
     *
     *   2. **课前提醒** —— 用户设了提前 N 分钟，就排在上课时刻减去 N 分钟。
     *
     * 用「一个闹钟 + 取最小值」而不是「两个独立闹钟各自排程」，是因为
     * 后者在时刻接近时会连续唤醒两次，而每次唤醒都要重建进程。
     * 合并成一个之后，醒来时统一检查该做什么（见 AlarmReceiver），
     * 一天的总唤醒次数不变。
     *
     * 跨天是这里最容易写错的地方：23:10 上床之后今天已经没有切换点了，
     * 得去明天的模板里找。所以用 dayOffset = 0..3 逐天往后找，而不是只看今天。
     */
    private fun nextTriggerAt(store: Store, nowMillis: Long): Long {
        // 暂停中：到点叫醒自己恢复
        val snooze = store.snoozeUntil
        if (snooze > nowMillis) return snooze + 1000L

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val nowMinute = now.hour * 60 + now.minute
        val courses = store.courses()
        val templates = store.templates()
        val lead = store.remindLead

        for (dayOffset in 0..3) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            val week = store.weekOf(date)
            val dayStart = date.atStartOfDay(zone)

            val moments = TimelineEngine.moments(date, week, courses, templates)

            // 候选时刻 = 所有格的开始时间 + 所有课的「提前 N 分钟」
            val candidates = mutableListOf<Int>()
            candidates += moments.map { it.start }
            if (lead > 0) {
                candidates += moments.filter { it.isCourse }.map { it.start - lead }
            }

            for (m in candidates.distinct().sorted()) {
                // m == 0 是「前一天晚上睡觉」这一格的延续，不是真正的切换点，
                // 排在这上面会在 00:00 白发一次通知。
                if (m <= 0) continue
                if (dayOffset == 0 && m <= nowMinute) continue

                val t = dayStart.plusMinutes(m.toLong()).toInstant().toEpochMilli()
                if (t > nowMillis) return t
            }
        }

        // 兜底：正常永远不会走到这里。万一数据异常，6 小时后再试一次，
        // 而不是让闹钟链彻底断掉 —— 一个断掉的闹钟链意味着 App 从此再不更新。
        return nowMillis + 6 * 60 * 60 * 1000L
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TICK
            setPackage(ctx.packageName)
        }
        return PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
