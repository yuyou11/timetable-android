package com.yxz.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yxz.timetable.data.Store

/**
 * 处理通知栏上那两个按钮：暂停 / 关闭。
 *
 * 这就是你要的「可以手动关闭」—— 不用打开 App，
 * 在锁屏界面下拉通知栏点一下「关闭常驻」，通知立刻消失、闹钟全部撤掉，
 * 之后 App 不会再主动醒来哪怕一次。
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
        }
    }

    companion object {
        const val ACTION_DISABLE = "com.yxz.timetable.ACTION_DISABLE"
        const val ACTION_SNOOZE = "com.yxz.timetable.ACTION_SNOOZE"
        const val ACTION_RESUME = "com.yxz.timetable.ACTION_RESUME"
    }
}
