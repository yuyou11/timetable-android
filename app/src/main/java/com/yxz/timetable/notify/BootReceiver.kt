package com.yxz.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yxz.timetable.data.Store

/**
 * 系统级事件的重建钩子。
 *
 * ## 为什么必须有这个类
 *
 * AlarmManager 里排的闹钟**在关机后会被清空**（它们存在内存里，不落盘）。
 * 所以如果只写上面那套逻辑，用户重启一次手机，App 就永久失效了 ——
 * 而且失效得很安静，你完全不会发现，只是从此通知栏再也不更新了。
 *
 * 这类「静默失效」是移动开发里最危险的一类 bug，因为它们不报错。
 * 解决办法就是在这里把几个关键的系统广播接住，一旦发生就重新排一遍。
 *
 * 接住的四个事件各自的含义：
 *   BOOT_COMPLETED      关机重启 —— 闹钟被清空，必须重排
 *   MY_PACKAGE_REPLACED 覆盖安装（比如你重新编了一个包装上）—— 同样会清空
 *   TIME_SET            用户手动改了系统时间 —— 所有绝对时刻都偏了
 *   TIMEZONE_CHANGED    跨时区（比如坐飞机）—— 本地时刻全变
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val interesting = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
        if (action !in interesting) return

        val store = Store(context)
        if (!store.enabled) return

        Notifier.update(context)
        AlarmScheduler.schedule(context)
    }
}
