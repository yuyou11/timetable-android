package com.yxz.timetable.notify

/**
 * 闹钟的渐强曲线：**前 3 秒音量逐渐增大，然后保持不变**。
 *
 * ## 为什么单独一个文件
 *
 * 这段算法原本写在 [AlarmRinger] 里。但 [AlarmRinger] 是个 object，
 * 类初始化时会 `Handler(Looper.getMainLooper())` —— 在电脑上的单元测试里
 * 那行直接抛 "Method getMainLooper not mocked"，于是连「第几秒该多大声」
 * 这种纯算术都测不了。
 *
 * 把纯计算挪出来后：这里不 import 任何 android.*，测试可以放心直接调。
 * 响铃本身仍然要真机（MediaPlayer、音频焦点），但曲线不用。
 */
object AlarmVolume {

    /**
     * 渐强时长。3 秒是「别一上来就炸」和「别让人睡过去」之间的折中：
     * 拖到 20 秒才满音量，人已经醒了在等它变大，反而显得没劲；
     * 直接满音量又容易吓到半夜同屋的人。
     */
    const val FADE_MS = 3_000L

    /**
     * 起步音量。不从 0 起：0 起步的前几百毫秒等于没声音，
     * 用户会以为「闹钟没响」；而且循环播放的铃声从 0 出来第一声也很突兀。
     */
    const val FADE_FROM = 0.25f

    /**
     * 渐强期间的刷新间隔。3 秒里按 100ms 走约 30 档 ——
     * 间隔再大（比如 250ms）就只有 12 档，能听出「一格一格」涨上去的台阶。
     */
    const val STEP_MS = 100L

    /**
     * 响铃第 [elapsedMs] 毫秒时的音量，取值 0~1。
     *
     * 3 秒内线性升到 1.0，**之后恒为 1.0**（不再继续涨，也不回落）。
     * 负数按 0 处理 —— 调用方用的是 `now - start`，理论上不会是负的，
     * 但系统时间被改过时（闹钟应用恰恰经常遇到）就会，不能让它算出怪值。
     */
    fun at(elapsedMs: Long): Float {
        if (elapsedMs <= 0L) return FADE_FROM
        val ratio = (elapsedMs.toFloat() / FADE_MS).coerceIn(0f, 1f)
        return FADE_FROM + (1f - FADE_FROM) * ratio
    }
}