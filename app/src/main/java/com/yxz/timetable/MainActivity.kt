package com.yxz.timetable

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yxz.timetable.data.Store
import com.yxz.timetable.databinding.ActivityMainBinding
import com.yxz.timetable.notify.AlarmScheduler
import com.yxz.timetable.notify.Notifier
import com.yxz.timetable.ui.AppTheme
import com.yxz.timetable.ui.SettingsFragment
import com.yxz.timetable.ui.TodayFragment
import com.yxz.timetable.ui.WeekFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * 通知权限的申请回调。
     *
     * 拿到权限后立刻重画一次通知 —— 因为用户很可能是在
     * 「常驻开关已经打开、但通知被系统挡住」的状态下进来的。
     */
    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Notifier.update(this)
            AlarmScheduler.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ setTheme 必须在 super.onCreate **之前**。
        //
        // Activity 的界面（布局解析、主题属性解析）是在 super.onCreate 里
        // 开始构建的。在那之后再 setTheme，这一次创建已经用不上新主题了 ——
        // 得等下一次 recreate 才生效，表现就是「切了主题但界面没变」。
        //
        // 换成绿色主题时走的是同一条路：设置页改完 store 再 recreate()，
        // 这里重新读一遍，整个界面就换色了。
        setTheme(AppTheme.from(Store(this).themeKey).resId)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 渠道要尽早建好：一旦用户从通知栏点进设置改了重要性，这个渠道就固定下来了
        Notifier.ensureChannel(this)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_week -> WeekFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> TodayFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
            true
        }

        // savedInstanceState == null 表示这是「全新启动」而不是「屏幕旋转后重建」。
        // 旋转时系统会自动恢复原来的 Fragment，这时再设一次 selectedItemId
        // 会导致 Fragment 被创建两遍。
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_today
        }

        askNotificationPermission()

        if (savedInstanceState == null) askFirstLaunch()
    }

    /**
     * 首次启动时问一句：用内置示例课表，还是从空白开始。
     *
     * ## 为什么需要这一步
     *
     * App 里内置的是一份虚构的示例课表（见 BuiltinCourses）。这个 App 是要发给同学用的，
     * 而**用别的课表的同学装上之后会看到一堆跟自己无关的课** ——
     * 他要么不知道这些课可以删，要么得一门一门去关掉。
     *
     * 给一个明确的开场选择，比让他自己去设置页里找要友好得多。
     *
     * 只在真正的首次启动弹出（判断逻辑见 Store.isFirstLaunch），
     * 老用户升级不会被打扰。
     */
    private fun askFirstLaunch() {
        val store = Store(this)
        if (!store.isFirstLaunch) return

        AlertDialog.Builder(this)
            .setTitle(R.string.first_launch_title)
            .setMessage(R.string.first_launch_msg)
            .setPositiveButton(R.string.first_launch_blank) { _, _ ->
                store.clearCourses()
                store.markLaunched()
                reboot()
            }
            .setNegativeButton(R.string.first_launch_keep) { _, _ ->
                store.markLaunched()
                reboot()
            }
            .setCancelable(false)
            .show()
    }

    /** 重建当前界面，让改动立刻反映出来 */
    private fun reboot() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, TodayFragment())
            .commit()
        binding.bottomNav.selectedItemId = R.id.nav_today
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时（比如刚开完精确闹钟权限），补一次排程，
        // 否则会出现「权限开了但闹钟还是按旧的（不精确）方式排着」的尴尬状态。
        AlarmScheduler.schedule(this)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
