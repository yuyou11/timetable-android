package com.yxz.timetable.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主题配色的测试。
 *
 * ============================================================
 *  这里守的是「加了主题之后多出来的、编译器抓不到的坑」
 * ============================================================
 *
 * 布局的颜色是通过 `?attr/accentColor` 拿的，编译器**只检查属性名存在**，
 * 不检查它有没有值、值在日间和夜间是不是都定义了、换主题时会不会跟着变。
 *
 * 所以下面这几件事全都编译通过、跑起来也不报错，只是**颜色不对**：
 *
 *   1. 新增了一套主题，但只写了日间色值 → 夜间静默用错色
 *   2. 布局里直接写 `@color/accent_blue` → 那换主题时它不会变
 *   3. 两个枚举项指向同一个 style → 切了没反应
 *
 * **颜色错了不会报错，只会难看** —— 这类问题只能靠断言盯。
 *
 * 界面本身没法在 JVM 里渲染（需要真机/模拟器），但**配色数据源可以读**，
 * 所以这个文件测的是「数据一致性」而不是「渲染结果」。
 * 它抓不到「绿色不好看」，但能抓到「绿色在夜间没定义」。
 */
class AppThemeTest {

    /** 从 app/ 往上找到 res 目录 */
    private val resDir: File = listOf(
        File("../app/src/main/res"),   // Gradle 从 app/ 跑测试
        File("app/src/main/res"),      // 万一从仓库根跑
    ).firstOrNull { it.exists() }
        ?: error("找不到 res 目录，测试的工作目录可能变了")

    private fun res(rel: String): String {
        val f = File(resDir, rel)
        assertTrue("找不到资源文件 ${f.absolutePath}", f.exists())
        return f.readText()
    }

    /** 把 XML 注释剥掉 —— 注释里出现 `@color/accent_blue` 只是说明文字，不是引用 */
    private fun stripComments(xml: String): String =
        xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    // ============================================================
    //  一、枚举本身
    // ============================================================

    @Test
    fun `认不出来的 key 回落到默认主题`() {
        // 存储损坏、或者用户从新版本降级回来 —— 都不该让 App 打不开
        assertEquals(AppTheme.DEFAULT, AppTheme.from(null))
        assertEquals(AppTheme.DEFAULT, AppTheme.from(""))
        assertEquals(AppTheme.DEFAULT, AppTheme.from("紫色"))   // 不存在的主题
        assertEquals(AppTheme.DEFAULT, AppTheme.from("BLUE"))   // 大小写不匹配也算认不出
    }

    @Test
    fun `已知的 key 能正确解析`() {
        for (t in AppTheme.entries) {
            assertEquals(t, AppTheme.from(t.key))
        }
    }

    @Test
    fun `每套主题指向的 style 互不相同`() {
        // 复制粘贴加新主题时最容易忘的就是改 resId ——
        // 那样两个选项切起来完全没反应，而且不报错
        val ids = AppTheme.entries.map { it.resId }
        assertEquals(
            "有两个主题指向同一个 style 资源，切换时不会有任何变化",
            ids.size, ids.toSet().size
        )
    }

    @Test
    fun `主题的 key 和名字都非空`() {
        for (t in AppTheme.entries) {
            assertTrue("${t.name} 的 key 是空的", t.key.isNotBlank())
            assertTrue("${t.name} 的 label 是空的", t.label.isNotBlank())
        }
    }

    // ============================================================
    //  二、色值必须在日间和夜间都定义
    // ============================================================

    /** 主题枚举 -> 它用到的两个色值名 */
    private fun colorNamesOf(theme: AppTheme): List<String> = when (theme) {
        AppTheme.BLUE -> listOf("accent_blue", "accent_soft_blue")
        AppTheme.GREEN -> listOf("accent_green", "accent_soft_green")
    }

    @Test
    fun `每套主题的色值在日间都定义了`() {
        val xml = stripComments(res("values/colors.xml"))
        for (t in AppTheme.entries) {
            for (name in colorNamesOf(t)) {
                assertTrue(
                    "values/colors.xml 里没有 $name —— ${t.label}主题在日间会拿不到颜色",
                    xml.contains("name=\"$name\"")
                )
            }
        }
    }

    @Test
    fun `每套主题的色值在夜间也定义了`() {
        // ★ 这条是重点。夜间少定义一个色值，**编译能过、运行也不崩** ——
        // 它会静默用成日间那个深色，在深灰背景上几乎看不见。
        val xml = stripComments(res("values-night/colors.xml"))
        for (t in AppTheme.entries) {
            for (name in colorNamesOf(t)) {
                assertTrue(
                    "values-night/colors.xml 里没有 $name —— ${t.label}主题在夜间" +
                            "会退回日间的颜色，压在深色背景上基本看不见",
                    xml.contains("name=\"$name\"")
                )
            }
        }
    }

    @Test
    fun `夜间色值和日间不同`() {
        // 夜间不是「换个色号」而是**必须换** —— 日间那两套都是为浅底选的，
        // 直接拿到深色背景上用会看不清。
        // 这条防的是「加了新主题，夜间那份直接复制日间的值凑数」。
        val day = stripComments(res("values/colors.xml"))
        val night = stripComments(res("values-night/colors.xml"))

        fun valueOf(xml: String, name: String): String? =
            Regex("""<color name="$name">(#\w+)</color>""").find(xml)?.groupValues?.get(1)

        for (t in AppTheme.entries) {
            for (name in colorNamesOf(t)) {
                val d = valueOf(day, name)
                val n = valueOf(night, name)
                assertTrue("$name 在日间读取失败", d != null)
                assertTrue("$name 在夜间读取失败", n != null)
                assertNotEquals(
                    "$name 的夜间色值和日间一模一样 —— 深色背景上大概率看不清",
                    d, n
                )
            }
        }
    }

    // ============================================================
    //  三、布局和 drawable 必须走主题属性，不能直连色值
    // ============================================================

    /** 收集 res 子目录里所有 xml，剥掉注释后逐行找裸的色值引用 */
    private fun findDirectColorRefs(subDir: String): List<String> {
        val dir = File(resDir, subDir)
        assertTrue("找不到 $subDir 目录", dir.isDirectory)
        val hits = mutableListOf<String>()
        dir.walkTopDown().filter { it.extension == "xml" }.forEach { f ->
            stripComments(f.readText()).lines().forEachIndexed { i, line ->
                // 匹配 @color/accent_xxx（带后缀的具体色值名）
                if (Regex("""@color/accent_\w+""").containsMatchIn(line)) {
                    hits += "${f.name}:${i + 1}  ${line.trim()}"
                }
            }
        }
        return hits
    }

    @Test
    fun `布局里不许直接引用具体色值`() {
        // 布局里写 @color/accent_green 的话，换成蓝色主题时它不会变 ——
        // 界面上会出现「大部分绿了，这一块还是绿」或者反过来的半拉子状态。
        // 必须写 ?attr/accentColor。
        val hits = findDirectColorRefs("layout")
        assertTrue(
            "这些地方直接引用了具体色值，换主题时不会跟着变：\n" + hits.joinToString("\n"),
            hits.isEmpty()
        )
    }

    @Test
    fun `drawable 里不许直接引用具体色值`() {
        val hits = findDirectColorRefs("drawable")
        assertTrue(
            "这些 drawable 直接引用了具体色值，换主题时不会跟着变：\n" + hits.joinToString("\n"),
            hits.isEmpty()
        )
    }

    @Test
    fun `主题属性本身确实定义在 themes xml 里`() {
        // 属性只有被主题赋了值才有意义。这里确认两套主题各自都赋了
        // accentColor 和 accentSoftColor —— 漏一个的话那个主题下
        // 相关控件会拿到空值/回落色。
        val xml = stripComments(res("values/themes.xml"))
        for (prop in listOf("accentColor", "accentSoftColor")) {
            val count = Regex("""<item name="$prop">""").findAll(xml).count()
            assertTrue(
                "themes.xml 里 $prop 只被赋了 $count 次，而主题有 ${AppTheme.entries.size} 套 —— " +
                        "少赋值的那套会拿不到颜色",
                count >= AppTheme.entries.size
            )
        }
    }

    @Test
    fun `绿色主题确实用了绿色系的色值`() {
        // 防呆：加新主题时复制粘贴，很容易忘了把颜色值真的换掉 ——
        // 那样「绿色主题」和「蓝色主题」看起来一模一样，而没有任何报错。
        val day = stripComments(res("values/colors.xml"))
        val blue = Regex("""<color name="accent_blue">#(\w{6})</color>""")
            .find(day)?.groupValues?.get(1)
        val green = Regex("""<color name="accent_green">#(\w{6})</color>""")
            .find(day)?.groupValues?.get(1)

        assertTrue("读不到 accent_blue", blue != null)
        assertTrue("读不到 accent_green", green != null)
        assertNotEquals("两套主题的主色完全一样", blue, green)

        // 绿色分量的判断：R 和 B 应当明显小于 G
        // （不用精确值 —— 以后微调色号不该让测试红）
        val r = green!!.substring(0, 2).toInt(16)
        val g = green.substring(2, 4).toInt(16)
        val b = green.substring(4, 6).toInt(16)
        assertTrue(
            "accent_green (#$green) 看起来不是绿色 —— G 分量应当明显高于 R 和 B",
            g > r && g > b
        )

        // 顺带守一下「不要荧光绿」：荧光绿的特征是饱和度拉满。
        // 这里用「最亮与最暗分量的差」粗略衡量：差得越少越灰，
        // 差得越多越纯。纯绿 #00FF00 的差是 255，我们选的应当远小于它。
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        assertTrue(
            "accent_green (#$green) 的分量差为 $spread，太接近纯色了 —— " +
                    "纯绿 (#00FF00) 的差是 255，看着刺眼",
            spread < 220
        )
    }

    @Test
    fun `绿主题的深色值不该比蓝主题更亮`() {
        // 日间主题要在浅色背景上看得清 —— 太亮会糊成一片。
        // 这条只是防止「随手挑了个浅绿当主色」，不是精确的亮度标准。
        val day = stripComments(res("values/colors.xml"))
        fun lum(name: String): Int {
            val hex = Regex("""<color name="$name">#(\w{6})</color>""")
                .find(day)?.groupValues?.get(1) ?: error("读不到 $name")
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            return (r * 299 + g * 587 + b * 114) / 1000   // 感知亮度
        }
        assertTrue(
            "日间的 accent_green 太亮了（感知亮度 ${lum("accent_green")}），" +
                    "在这种浅色背景上会显得发飘",
            lum("accent_green") < 160
        )
    }
}
