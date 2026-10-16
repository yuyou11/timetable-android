package com.yxz.timetable.notify

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.yxz.timetable.data.Alarms
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.ActivityAlarmRingBinding

/**
 * 响铃界面：**闹钟响了以后，一定有一个能关掉它的地方**。
 *
 * ============================================================
 *  为什么必须有这个 Activity（这是用户报上来的 bug）
 * ============================================================
 *
 * 第一版只做了通知上的「停止 / 贪睡」两个按钮。结果用户遇到的情况是：
 * 闹钟响了、通知没出现（通知权限被系统挡掉时 `notify` 会静默失败），
 * 于是**声音一直响、屏幕上没有任何能关它的东西**，最后只能重启手机。
 *
 * 教训很直接：**响铃这种"正在发生的坏事"，停止入口不能只挂在一条链路上。**
 * 现在有四条：这个全屏界面、通知上的两个按钮、划掉通知、以及 3 分钟自动停。
 * 任何一条活着，用户就能让声音停下来。
 *
 * 锁屏上也要能直接操作，所以 Manifest 里给它加了 `showWhenLocked` +
 * `turnScreenOn`，并由 [AlarmRinger] 用 `setFullScreenIntent` 拉起。
 */
class AlarmRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingBinding
    private val handler = Handler(Looper.getMainLooper())

    /** 每秒看一眼还在不在响：在别处（通知按钮 / 划掉通知）停掉了，这一页也要跟着关 */
    private val watcher = object : Runnable {
        override fun run() {
            if (!AlarmRinger.isRinging()) {
                finish()
                return
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 进程被杀过、或者已经从别处停掉了：这一页没有存在的意义
        if (!AlarmRinger.isRinging()) {
            finish()
            return
        }

        val store = Store(this)
        val id = AlarmRinger.currentIds().firstOrNull()
        val alarm = store.alarms().firstOrNull { it.id == id }

        binding.tvTime.text = alarm?.timeText ?: "--:--"
        binding.tvName.text = alarm?.let { Alarms.describe(it, store.courses()) } ?: "闹钟"
        val minutes = AlarmRinger.currentSnoozeMinutes()
        binding.btnSnooze.text = "贪睡 $minutes 分钟"

        binding.btnStop.setOnClickListener {
            AlarmRinger.stop(this)
            finish()
        }
        binding.btnSnooze.setOnClickListener {
            // 和通知上那个「贪睡」按钮走同一套逻辑：记下贪睡哪个、到几点，然后重排
            val target = AlarmRinger.currentIds().firstOrNull()
            if (target != null) {
                store.snoozeAlarmId = target
                store.snoozeAlarmUntil = System.currentTimeMillis() + minutes * 60_000L
                AlarmScheduler.scheduleAlarms(this)
            }
            AlarmRinger.stop(this)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(watcher)
        handler.post(watcher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watcher)
    }
}
