package com.yxz.timetable.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住时间轴行的布局结构：**地点必须在标题下方单独一行**。
 *
 * ## 这个测试守的是什么
 *
 * 旧写法把地点作为根 LinearLayout 的最后一个兄弟节点（居中、不限宽），
 * 挂在标题右边。结果课名和地点**都长**的课（如「C语言与人工智能基础」+
 * 「子良楼107（公共计算机实验室一）」）在窄屏上会把标题挤到
 * 一两个字的宽度 —— TextView 只能一个字一行竖着排，整行被撑得很高，
 * 看起来像「标题消失了」。
 *
 * 「给地点限宽」治不了本：屏幕本来就不宽，限完地点标题还是没剩多少。
 * 唯一稳的结构是**地点独占一行**，标题永远占满整行宽。
 */
class TimelineRowLayoutTest {

    private val xml: String = listOf(
        File("../app/src/main/res/layout/item_timeline.xml"),   // Gradle 从 app/ 跑测试
        File("app/src/main/res/layout/item_timeline.xml")       // 万一从仓库根跑
    ).first { it.exists() }.readText()

    @Test
    fun `地点在标题和备注的下方 而不是挤在标题右边`() {
        val iTitle = xml.indexOf("@+id/tvTitle")
        val iNote = xml.indexOf("@+id/tvNote")
        val iPlace = xml.indexOf("@+id/tvPlace")
        assertTrue("找不到 tvTitle", iTitle >= 0)
        assertTrue("找不到 tvNote", iNote >= 0)
        assertTrue("找不到 tvPlace", iPlace >= 0)
        assertTrue(
            "tvPlace 必须写在 tvNote 之后（同一列容器里、标题下方），" +
                    "不能是根布局的兄弟节点",
            iTitle < iNote && iNote < iPlace
        )
    }

    @Test
    fun `地点单行省略 旧写法的居中兄弟节点不许回来`() {
        assertTrue("地点要单行 + 省略号兜底", xml.contains("""android:ellipsize="end""""))
        assertFalse(
            "layout_gravity=\"center_vertical\" 是旧写法的标志（挂在根布局上居中），" +
                    "地点挪到标题下方后这行不该再出现",
            xml.contains("layout_gravity")
        )
    }
}
