package com.yxz.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.Store
import java.time.LocalDateTime

/**
 * 闹钟到点时被系统唤醒。
 *
 * 这是全 App 唯一会「主动醒来」的地方，所以它必须极短：
 * 改一下通知，排下一个闹钟，结束。**不要在这里做网络请求、读文件、算复杂东西。**
 *
 * 有一个必须理解的点：这个 Receiver 运行时，App 的进程是**临时被创建**出来的。
 * 跑完 onReceive 之后，系统随时可能把进程回收掉。所以这里不能依赖任何
 * 保存在内存里的状态 —— 所有需要的数据都从 SharedPreferences 现读。
 *
 * 两个入口：
 *   · [ACTION_TICK]  —— 常驻通知那条链（换通知 + 课前提醒）
 *   · [ACTION_ALARM] —— 用户闹钟那条链（响铃 + 排下一次）
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> onAlarm(context)
            ACTION_TICK -> onTick(context)
        }
    }

    /**
     * 用户闹钟到点。
     *
     * 这里**重新算一遍**「此刻该响哪些」，而不是把闹钟信息塞在 Intent 里带过来：
     * 排程到触发之间可能过了十几个小时，用户完全可能改了时间或停掉了它。
     * 顺带把同一分钟里的多个闹钟一起收进来（起床 + 吃药设在同一分钟是常事），
     * 合并成一次响铃 —— 排程时只排了最早那个，后一个到期时也在这个窗口里了。
     */
    private fun onAlarm(context: Context) {
        val store = Store(context)
        val nowMs = System.currentTimeMillis()

        // 贪睡到点：直接响当初贪睡的那一批闹钟（同一分钟响的可能不止一个）
        val snoozedIds = if (store.snoozeAlarmUntil > 0L && nowMs >= store.snoozeAlarmUntil) {
            store.snoozeAlarmIds
        } else {
            emptyList()
        }
        store.snoozeAlarmUntil = 0L
        store.snoozeAlarmIds = emptyList()

        val due = if (snoozedIds.isNotEmpty()) {
            store.alarms().filter { it.id in snoozedIds }
        } else {
            Alarms.dueAt(
                alarms = store.alarms(),
                at = LocalDateTime.now(),
                courses = store.courses(),
                templates = store.templates(),
                holidays = store.holidayTable(),
                weekOf = store::weekOf,
                // 系统在 Doze 下可能晚几分钟才放行，窗口给宽一点才不会漏响
                windowMinutes = 3
            )
        }

        // 贪睡的闹钟被删掉了：这次作废，别记成「错过」
        if (snoozedIds.isNotEmpty() && due.isEmpty()) {
            store.alarmExpectedAt = 0L
            store.alarmExpectedLabel = ""
        }

        if (due.isNotEmpty()) {
            // 真的响了 —— 这次不算「错过」
            store.alarmExpectedAt = 0L
            store.alarmExpectedLabel = ""
            AlarmRinger.ring(context, due)
            // 「只响一次」的闹钟响过就关掉 —— 它是按日期固定的，不关的话
            // 明天同一时刻还会再响一次（那就成了每天响）
            val oneShots = due.filter { Alarms.isOneShot(it) }.map { it.id }.toSet()
            if (oneShots.isNotEmpty()) {
                val list = store.alarms()
                var changed = false
                for (i in list.indices) {
                    if (list[i].id in oneShots && list[i].enabled) {
                        list[i] = list[i].copy(enabled = false)
                        changed = true
                    }
                }
                if (changed) store.saveAlarms(list)
            }
        }

        // 这一步不能漏：排下一次。漏了的话闹钟只响这一次，之后永远不响
        AlarmScheduler.scheduleAlarms(context)
    }

    /** 常驻通知那条链（原样保留） */
    private fun onTick(context: Context) {
        val store = Store(context)
        if (!store.enabled) {
            Notifier.cancel(context)
            AlarmScheduler.cancel(context)
            return
        }

        Notifier.update(context)            // 1. 换上「此刻」这一格
        ReminderNotifier.fireDue(context)   // 2. 到点的话发一条课前提醒
        AlarmScheduler.schedule(context)    // 3. 排下一次（这一步不能漏！）
    }

    companion object {
        const val ACTION_TICK = "com.yxz.timetable.TICK"
        const val ACTION_ALARM = "com.yxz.timetable.ALARM"
    }
}
