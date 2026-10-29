package com.yxz.timetable.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 内容区容器：在**任意位置**右滑时回调 [onSwipeRight]。
 *
 * 为什么不直接用 DrawerLayout 自带的边缘滑动：
 * 全面屏手势导航下，屏幕左右边缘各 20dp 属于系统的「返回」手势区，
 * 从边缘往里滑会被系统先吃掉，抽屉根本收不到事件。
 * 所以抽屉改成程序控制（[androidx.drawerlayout.widget.DrawerLayout.setDrawerLockMode] 锁死手势），
 * 手势自己在这层拦。
 *
 * 判定写在 onInterceptTouchEvent 里，而不是给子 View 挂 OnTouchListener：
 * ViewGroup 只有在没有子 View 消费事件时才会走自己的 onTouchEvent，
 * 挂监听器的话，手指落在按钮/卡片上就收不到事件了。拦截这一层能看到全部事件。
 *
 * 只在「明显是横滑」时才抢：dx 超过 4 倍 touchSlop 且明显大于 dy。
 * 竖着划（列表滚动）一路放行，不会出现「想滚列表却把抽屉拉出来」。
 */
class SwipeToOpenLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onSwipeRight: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                tracking = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (dx > slop * 4 && dx > abs(dy) * 2f) {
                    tracking = false
                    onSwipeRight?.invoke()
                    return true
                }
                // 已经明确是竖滑，本轮不再关心
                if (abs(dy) > slop * 2 && abs(dy) > dx) tracking = false
            }

            else -> tracking = false
        }
        return false
    }
}
