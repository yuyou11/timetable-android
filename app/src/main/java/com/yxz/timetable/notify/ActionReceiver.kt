package com.yxz.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yxz.timetable.data.Store

/**
 * 处理通知栏上的按钮：暂停 / 关闭 / 闹钟的停止与贪睡。
 *
 * 「暂停 / 关闭」是常驻通知那条链的：不用打开 App，在锁屏界面下拉通知栏点一下
 * 「关闭常驻」，通知立刻消失、闹钟全部撤掉，之后 App 不会再主动醒来哪怕一次。
 *
 * 「停止 / 贪睡」是闹钟响铃那条链的：贪睡会记下「哪个闹钟 + 到几点」，
 * 然后重新排一次 —— 下一次响的就是它，而不是原定时刻。
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)

        when (intent.action) {
            ACTION_DISABLE -> {
                store.enabled = false
                store.snoozeUntil = 0L
                AlarmScheduler.cancel(context)
                Notifier.cancel(context)
                ReminderNotifier.cancelAll(context)
            }

            ACTION_SNOOZE -> {
                store.snoozeUntil = System.currentTimeMillis() + 60 * 60 * 1000L
                Notifier.update(context)
                AlarmScheduler.schedule(context)
            }

            ACTION_RESUME -> {
                store.snoozeUntil = 0L
                Notifier.update(context)
                AlarmScheduler.schedule(context)
            }

            // ---- 闹钟响铃时的两个按钮 ----

            ACTION_ALARM_STOP -> AlarmRinger.stop(context)

            ACTION_ALARM_SNOOZE -> {
                // 贪睡哪个：正在响的那个。没人响着就什么都不做，
                // 免得凭一个过期的按钮把闹钟排到莫名其妙的时间
                val id = AlarmRinger.currentIds().firstOrNull()
                if (id != null) {
                    val minutes = AlarmRinger.currentSnoozeMinutes()
                    store.snoozeAlarmId = id
                    store.snoozeAlarmUntil = System.currentTimeMillis() + minutes * 60_000L
                    AlarmRinger.stop(context)
                    AlarmScheduler.scheduleAlarms(context)
                } else {
                    AlarmRinger.stop(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_DISABLE = "com.yxz.timetable.ACTION_DISABLE"
        const val ACTION_SNOOZE = "com.yxz.timetable.ACTION_SNOOZE"
        const val ACTION_RESUME = "com.yxz.timetable.ACTION_RESUME"

        /** 闹钟响铃时通知上的两个按钮 */
        const val ACTION_ALARM_STOP = AlarmRinger.ACTION_STOP
        const val ACTION_ALARM_SNOOZE = AlarmRinger.ACTION_SNOOZE
    }
}
