package com.yxz.timetable.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.yxz.timetable.notify.Notifier

/**
 * 权限与系统设置引导 —— 专门负责「把用户送到对应的系统设置页」。
 *
 * 从 SettingsFragment 拆出来：这些跳转是纯「跟操作系统打交道」的脏活，
 * 和界面上的状态刷新、数据编辑没有关系。独立成类之后，设置页剩下的
 * 就只有它本来该管的事。（注释和逻辑从 Fragment 原样随迁，未做任何改动。）
 */
class PermissionGuide(private val fragment: Fragment) {

    private val ctx: Context get() = fragment.requireContext()

    private fun toast(msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        launch(intent) { openAppDetails() }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toast("当前系统版本无需此权限")
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${ctx.packageName}"))
        launch(intent) { openAppDetails() }
    }

    /**
     * 「全屏通知」权限（Android 14 起）。
     *
     * 没有它，闹钟响时**不会在锁屏上自动弹出响铃界面**，只会变成一条横幅通知 ——
     * 点一下照样进响铃界面（停止 / 贪睡都在），只是多一次点击。
     * 所以这一项不是「必须开」，而是「开了更省事」，状态文案也照这个口径写。
     */
    fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < 34) {          // UPSIDE_DOWN_CAKE
            toast("当前系统版本无需此权限")
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:${ctx.packageName}"))
        launch(intent) { openAppDetails() }
    }

    /**
     * 申请加入电池优化白名单。
     *
     * 这个 Action 在部分厂商 ROM（尤其荣耀/华为/小米）上会被拦掉，
     * 所以失败时退回到「电池优化列表」页面，让用户自己去列表里找 ——
     * 少一步自动跳转，但至少不会点了没反应。
     */
    @SuppressLint("BatteryLife")
    fun openBatterySettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
        launch(direct) {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) {
                openAppDetails()
            }
        }
    }

    /**
     * 自启动管理页。
     *
     * 这个页面**没有标准 Action**，是各家 ROM 自己加的，
     * 所以只能硬编码一串厂商组件名挨个试。
     * 这是 Android 生态里出了名的脏活，属于「必须接受的不优雅」。
     * 全部失败就退回应用详情页 —— 至少用户能从这里找到「电池」「权限」入口。
     */
    fun openAutostartSettings() {
        val candidates = listOf(
            // 荣耀 / 华为
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            // 荣耀独立后的包名
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // 小米 / OPPO / vivo
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )

        var launched = false
        for (c in candidates) {
            val ok = runCatching {
                fragment.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) { launched = true; break }
        }
        if (!launched) {
            toast("未找到自启动管理页，已打开应用详情。请手动进入「应用启动管理」")
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
        launch(intent) { toast("无法打开系统设置") }
    }

    private fun launch(intent: Intent, onFail: () -> Unit) {
        runCatching {
            fragment.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { onFail() }
    }

    // ==================================================================
    //  锁屏显示
    // ==================================================================

    /**
     * 锁屏通知**没有标准开关**：Android 的每个应用都有一组「通知类别」设置，
     * 其中「锁屏通知」在多数 ROM 上是独立的一项，但位置各不相同。
     *
     * 这里能做的：跳到本应用的通知设置页（多数 ROM 的锁屏开关就在那一页），
     * 并把要找什么说清楚。查不到、点不准是常态，所以**说清楚比跳得准更重要**。
     */
    /**
     * 锁屏显示的引导。
     *
     * ## 这里曾经指错路（用户实际反馈）
     *
     * 原文写的是「设置 → 通知和状态栏 → Time Machine → 打开『锁屏通知』」。
     * 但用户照着走，在应用级页面上**根本找不到这一项** ——
     * 那一页只有「允许通知 / 置顶通知 / 类别」。
     *
     * 原因是 Android 的通知设置分**两层**，而「锁屏通知」在**第二层**：
     *
     *   应用级页面（Time Machine）
     *     └─ 类别
     *          └─ 此刻该做什么   ← **锁屏通知 / 横幅通知在这里面**
     *
     * 对比一下就清楚了：淘宝的通知设置页顶部写着「系统默认通道」，
     * 那是**渠道详情页**；而应用级页面顶部写的是应用版本号。
     * 两者长得像，但不是同一层。
     *
     * 所以这里做了两件事：
     *   ① 把话说清楚：告诉用户要点进「此刻该做什么」
     *   ② 给一个**直达渠道页**的按钮（见 [openChannelSettings]），
     *      省掉「自己找那一行」这一步
     *
     * 教训：**「告诉用户去哪找」不如「直接把他送到那」**。
     * 尤其是路径描述 —— 各家 ROM 的层级和叫法都不一样，
     * 写死的路径迟早会和用户手机上看到的对不上。
     */
    fun openLockScreenHelp() {
        CardAlertDialogBuilder(ctx)
            .setTitle("让通知出现在锁屏上")
            .setMessage(
                "锁屏通知受两层控制：\n\n" +
                        "【App】上面的开关，决定本 App 是否愿意送到锁屏。\n\n" +
                        "【系统】多数国产 ROM 默认不允许第三方应用在锁屏显示，必须手动开。\n" +
                        "它不在应用页面上，而在通知渠道里：\n\n" +
                        "　设置 → 通知管理 → Time Machine →\n" +
                        "　　点「此刻该做什么」→ 勾上「锁屏通知」\n\n" +
                        "点下面的按钮可直接跳到这个渠道。\n\n" +
                        "系统没有提供查询接口，App 无法检测，只能由你确认一次。"
            )
            .setPositiveButton("打开渠道设置") { _, _ -> openChannelSettings(Notifier.CHANNEL_ID) }
            .setNeutralButton("打开通知设置") { _, _ -> openNotificationSettings() }
            .setNegativeButton("知道了", null)
            .show()
    }

    /**
     * 直接跳到**某个通知渠道**的设置页。
     *
     * 和 [openNotificationSettings] 的区别很关键：
     *   ACTION_APP_NOTIFICATION_SETTINGS     → 应用级页面（只有「允许通知」等）
     *   ACTION_CHANNEL_NOTIFICATION_SETTINGS → **渠道页**（锁屏通知在这里）
     *
     * 需要 API 26，正好是本项目的最低版本，所以不用做版本判断。
     * 部分 ROM 可能拦掉这个 Action，所以失败时退回到应用级页面 ——
     * 少一步自动跳转，但不会点了没反应。
     */
    fun openChannelSettings(channelId: String) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        launch(intent) { openNotificationSettings() }
    }
}
