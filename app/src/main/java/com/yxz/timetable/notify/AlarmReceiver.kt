package com.yxz.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yxz.timetable.data.Store

/**
 * 闹钟到点时被系统唤醒。
 *
 * 这是全 App 唯一会「主动醒来」的地方，所以它必须极短：
 * 改一下通知，排下一个闹钟，结束。**不要在这里做网络请求、读文件、算复杂东西。**
 *
 * 有一个必须理解的点：这个 Receiver 运行时，App 的进程是**临时被创建**出来的。
 * 跑完 onReceive 之后，系统随时可能把进程回收掉。所以这里不能依赖任何
 * 保存在内存里的状态 —— 所有需要的数据都从 SharedPreferences 现读。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) return

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
    }
}
