package com.yxz.timetable.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri

/**
 * 铃声选择与试听。
 *
 * 用系统的铃声选择器（[RingtoneManager.ACTION_RINGTONE_PICKER]），
 * 而不是自己列一遍系统铃声：那个界面用户熟悉、能试听、还能选「静音」，
 * 而我们要做的只是把选中的 URI 存下来。
 *
 * ⚠️ 声音**必须由 App 自己播放**（见 [com.yxz.timetable.notify.AlarmRinger]）：
 * 通知渠道的声音创建之后就改不了了，靠渠道就没法「设置里换声音」。
 */
object SoundPicker {

    /** 系统默认闹钟铃声 */
    fun defaultUri(): Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    /**
     * 打开系统铃声选择器。[current] 是当前铃声（空串表示用默认）。
     */
    fun intent(current: String): Intent {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择闹钟铃声")
            val existing = current.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing ?: defaultUri())
        }
        return i
    }

    /** 从选择器结果里取 URI。返回 "" 表示用户选了「静音」，null 表示没选 */
    fun uriFromResult(resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        return uri?.toString() ?: ""
    }

    /** 界面上显示的名字：「默认（系统铃声）」/ 铃声标题 */
    fun nameOf(ctx: Context, uriString: String): String {
        if (uriString.isBlank()) return "默认（系统铃声）"
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return "默认（系统铃声）"
        return runCatching {
            RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "自定义铃声"
    }

    private var preview: Ringtone? = null

    /** 试听：播一小段就停，试听本来就不该一直响 */
    fun play(ctx: Context, uriString: String) {
        stop()
        val uri = if (uriString.isBlank()) defaultUri()
        else runCatching { Uri.parse(uriString) }.getOrNull() ?: defaultUri()
        if (uri == null) return
        runCatching {
            RingtoneManager.getRingtone(ctx, uri)?.also {
                preview = it
                it.play()
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { stop() }, 5_000L
        )
    }

    fun stop() {
        runCatching { preview?.stop() }
        preview = null
    }
}
