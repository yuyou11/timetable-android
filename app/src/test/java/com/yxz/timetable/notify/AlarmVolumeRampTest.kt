package com.yxz.timetable.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住闹钟的渐强曲线：**前 3 秒音量逐渐增大，然后保持不变**。
 *
 * ## 为什么测的是 [AlarmVolume] 而不是 AlarmRinger
 *
 * 响铃本身要真机、要 MediaPlayer，电脑上跑不起来；但「第几秒该有多大声」
 * 是一段纯计算。曲线放在 [AlarmVolume] 里正是为了这里能直接调 ——
 * 早先写在 AlarmRinger 里时，光类初始化那行 `Looper.getMainLooper()`
 * 就让测试起不来，等于这条曲线完全没人守。
 *
 * 旧行为是「20 秒、起步 15%」，效果是响半天还不大，和闹钟该有的行为不符。
 *
 * ## 断言咬在行为上
 *
 * 除了「3 秒」「满音量」两个用户可感知的约定，其余只断言单调递增、
 * 有上下界、升满后不再变 —— 以后微调起步音量不会把这些测试弄红。
 */
class AlarmVolumeRampTest {

    private val from = AlarmVolume.at(0L)

    @Test
    fun `刚开始音量很小 但不是静音`() {
        assertTrue("起步音量必须大于 0，否则前几百毫秒等于没响", from > 0f)
        assertTrue("起步音量不能太响，否则等于没有渐强", from <= 0.3f)
    }

    @Test
    fun `前 3 秒音量一路上涨`() {
        var last = AlarmVolume.at(0L)
        for (ms in 0L..3_000L step 50L) {
            val v = AlarmVolume.at(ms)
            assertTrue("第 ${ms}ms 音量 $v 不该小于上一档 $last", v >= last)
            last = v
        }
        assertTrue("3 秒内必须有明显增长", last > from + 0.5f)
    }

    @Test
    fun `3 秒时正好升满`() {
        assertEquals("3 秒应该刚好到 100%", 1f, AlarmVolume.at(3_000L), 0.001f)
    }

    @Test
    fun `升满之后不再变化`() {
        for (ms in longArrayOf(3_000L, 3_001L, 5_000L, 60_000L, 600_000L)) {
            assertEquals(
                "第 ${ms}ms 应该仍然保持满音量，不能继续涨也不能回落",
                1f, AlarmVolume.at(ms), 0.0001f
            )
        }
    }

    @Test
    fun `超出范围的时间不越界`() {
        assertEquals("负数时间按起步音量处理", from, AlarmVolume.at(-500L), 0.0001f)
        for (ms in longArrayOf(0L, 1_500L, 3_000L, 9_999L)) {
            val v = AlarmVolume.at(ms)
            assertTrue("音量 $v 必须落在 0~1", v in 0f..1f)
        }
    }

    @Test
    fun `不是 20 秒那种慢热曲线`() {
        // 1.5 秒（一半）时应该已经过半：慢热曲线此刻只有 15%+85%*0.075 ≈ 21%
        assertTrue(
            "1.5 秒时音量应明显过半，否则又回到「响半天还不大」的老问题",
            AlarmVolume.at(1_500L) > 0.5f
        )
    }
}