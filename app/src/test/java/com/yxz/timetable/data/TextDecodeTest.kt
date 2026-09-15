package com.yxz.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * 导入解码的测试。
 *
 * ## 这个文件是被一个真实 bug 逼出来的
 *
 * 用户导入课表时弹出一屏乱码。查下去发现**真正的问题不是他报的那个**：
 *
 *   他报的      ：导入直接失败，弹一屏乱码
 *   更严重的那个：文件存成 GBK 时会「导入成功」，但课名全变乱码，**一声不吭**
 *
 * 后者的成因是 `bytes.toString(Charsets.UTF_8)` ——
 * 这个函数对**任何**字节都不会失败，遇到不合法的字节就悄悄塞一个「�」进去。
 * JSON 的大括号引号全是 ASCII，照样合法，于是解析通过、导入成功。
 *
 * 所以下面每个用例都在守一条明确的规则，而不是"跑一下看看有没有报错"。
 */
class TextDecodeTest {

    // ============================================================
    //  一、正常文件必须照常通过（防止修 bug 修出新 bug）
    // ============================================================

    @Test
    fun `普通 UTF-8 文件正常解码`() {
        val json = """{"format":"timetable","version":2,"courses":[]}"""
        val r = TextDecode.fromBytes(json.toByteArray(Charsets.UTF_8))
        assertTrue("应当是 Ok", r is TextDecode.Decoded.Ok)
        assertEquals(json, (r as TextDecode.Decoded.Ok).text)
    }

    @Test
    fun `中文课名不会被误判`() {
        // 这段中文是合法 UTF-8，一个「�」都不该有 —— 不能误伤正常文件
        val json = """{"name":"高等数学","place":"教一-203"}"""
        val r = TextDecode.fromBytes(json.toByteArray(Charsets.UTF_8))
        assertTrue(r is TextDecode.Decoded.Ok)
        assertEquals(json, (r as TextDecode.Decoded.Ok).text)
    }

    @Test
    fun `空文件被拒绝`() {
        val r = TextDecode.fromBytes(ByteArray(0))
        assertTrue(r is TextDecode.Decoded.Failed)
    }

    // ============================================================
    //  二、UTF-8 BOM —— 必须自动剥掉，而不是报错
    // ============================================================

    @Test
    fun `UTF-8 BOM 会被剥掉，文件照常可用`() {
        // Windows 记事本「另存为 UTF-8」、以及不少编辑器默认就会加这个
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val json = """{"format":"timetable","version":2,"courses":[]}"""
        val r = TextDecode.fromBytes(bom + json.toByteArray(Charsets.UTF_8))

        assertTrue("带 BOM 不该被拒绝，应当剥掉后继续", r is TextDecode.Decoded.Ok)
        assertEquals(json, (r as TextDecode.Decoded.Ok).text)
    }

    @Test
    fun `带 BOM 的文件能真正解析成课表`() {
        // 上一条只验了解码，这条验到「解析器真能用」——
        // 因为剥 BOM 的**唯一**目的就是让 JSONObject 能认出来
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val json = """{"format":"timetable","version":2,""" +
                """"courses":[{"name":"高等数学","dayOfWeek":1,"nodes":[1,2],"weeks":"1-5"}]}"""

        val decoded = TextDecode.fromBytes(bom + json.toByteArray(Charsets.UTF_8))
        val text = (decoded as TextDecode.Decoded.Ok).text

        val parsed = ScheduleFormat.parse(text, 19)
        assertTrue("应当解析成功", parsed is ScheduleFormat.Result.Ok)
        assertEquals(
            "高等数学",
            (parsed as ScheduleFormat.Result.Ok).parsed.courses!![0].name
        )
    }

    // ============================================================
    //  三、GBK —— 这次修复的核心：必须拦下，不能静默变乱码
    // ============================================================

    @Test
    fun `GBK 文件必须被拒绝，而不是静默变成乱码`() {
        // 这是本次 bug 的主角。
        //
        // 修复之前：这里会返回 Ok，课名变成「˼������뷨��」，
        // 然后**导入成功、没有任何报错**。用户过很久才会发现课表毁了。
        //
        // 修复之后：必须 Failed，而且提示里要有「UTF-8」告诉用户怎么办。
        val json = """{"name":"高等数学","place":"教一-203"}"""
        val bytes = json.toByteArray(Charset.forName("GBK"))

        val r = TextDecode.fromBytes(bytes)
        assertTrue("GBK 文件必须被拒绝", r is TextDecode.Decoded.Failed)
        val msg = (r as TextDecode.Decoded.Failed).message
        assertTrue("提示要告诉用户怎么改，实际：$msg", msg.contains("UTF-8"))
        assertTrue("要提到 ANSI/GBK，实际：$msg", msg.contains("GBK"))
    }

    @Test
    fun `GBK 编码下即使 JSON 结构完好也要拒绝`() {
        // 进阶版：整个 JSON 都是 GBK 的，只有 ASCII 部分（大括号、键名）不受影响。
        // 这就解释了为什么它**能**解析成功 —— 语法骨架是 ASCII，坏掉的只有中文。
        val json = """{"format":"timetable","version":2,"courses":[""" +
                """{"name":"大学英语","dayOfWeek":1,"nodes":[1,2],"weeks":"1-5"}]}"""
        // ⚠️ 必须直接编成 GBK。
        // 别写成 `json.toByteArray(UTF_8).toString(gbk).toByteArray(gbk)` ——
        // 同一个字符集先解码再编码会**互相抵消**，得到的还是原来的 UTF-8 字节，
        // 测试就变成了「拿正常文件去验能不能拦下乱码」，必然失败。
        // （第一版就是这么写的，跑出来两条红，查了半天发现是测试自己的锅。）
        val bytes = json.toByteArray(Charset.forName("GBK"))

        // 先证明「如果不拦，它确实会解析成功」—— 这正是危险之处
        val naive = bytes.toString(Charsets.UTF_8)
        assertTrue(
            "前提验证：这份字节按 UTF-8 硬读，JSON 语法居然是合法的（所以才会静默成功）",
            ScheduleFormat.parse(naive, 19) is ScheduleFormat.Result.Ok
        )

        // 再确认新的解码器把它拦住了
        assertTrue(
            "必须被 TextDecode 拦下",
            TextDecode.fromBytes(bytes) is TextDecode.Decoded.Failed
        )
    }

    // ============================================================
    //  四、UTF-16
    // ============================================================

    @Test
    fun `UTF-16 带 BOM 会被识别并给出具体改法`() {
        val json = """{"format":"timetable"}"""
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                json.toByteArray(Charset.forName("UTF-16LE"))

        val r = TextDecode.fromBytes(le)
        assertTrue(r is TextDecode.Decoded.Failed)
        val msg = (r as TextDecode.Decoded.Failed).message
        assertTrue("要指明是 UTF-16，实际：$msg", msg.contains("UTF-16"))
        assertTrue("要给出「另存为 UTF-8」的操作，实际：$msg", msg.contains("另存为"))
    }

    @Test
    fun `UTF-16BE 的 BOM 也能识别`() {
        val json = """{"format":"timetable"}"""
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
                json.toByteArray(Charset.forName("UTF-16BE"))
        assertTrue(TextDecode.fromBytes(be) is TextDecode.Decoded.Failed)
    }

    // ============================================================
    //  五、二进制文件（用户选错了文件）
    // ============================================================

    @Test
    fun `含 NUL 字节的二进制文件被拒绝`() {
        // PNG / JPEG / WORD / 压缩包里满地都是 0x00，
        // 而真正的文本文件（UTF-8 / GBK / GB18030）里一个都不会有
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
        )
        val r = TextDecode.fromBytes(pngHeader)
        assertTrue(r is TextDecode.Decoded.Failed)
        assertTrue((r as TextDecode.Decoded.Failed).message.contains("二进制"))
    }

    @Test
    fun `没有 NUL 但也不是文本的内容会被控制字符规则拦下`() {
        // 有些二进制碰巧是合法 UTF-8（短文件尤其容易），第 4 步抓不住，
        // 靠控制字符比例这条兜底
        val bytes = ByteArray(200) { (it % 0x1F + 1).toByte() }   // 全是控制字符
        assertTrue(TextDecode.fromBytes(bytes) is TextDecode.Decoded.Failed)
    }

    @Test
    fun `把普通文字存成 json 会被拦下并提示不是课表文件`() {
        // 很常见的场景：把 AI 的回答直接存成了 .json
        val md = "# 我的课表\n\n| 课程 | 周次 |\n|------|------|\n".toByteArray(Charsets.UTF_8)
        val r = TextDecode.fromBytes(md)

        // 这一步解码是**成功**的（它确实是合法 UTF-8 文本），
        // 拦住它的应该是下一层的 JSON 解析，而不是编码层 ——
        // 分层要清楚：编码层管「字节能不能变成文本」，解析层管「文本是不是课表」
        assertTrue("合法 UTF-8 文本不该在编码层被拒", r is TextDecode.Decoded.Ok)

        // 再走解析层，这里必须失败
        val parsed = ScheduleFormat.parse((r as TextDecode.Decoded.Ok).text, 19)
        assertTrue(parsed is ScheduleFormat.Result.Failed)
    }

    // ============================================================
    //  六、读流：大小上限
    // ============================================================

    @Test
    fun `超过大小上限的文件被拒绝而不是吃光内存`() {
        val big = ByteArray(TextDecode.MAX_BYTES + 1024) { 'a'.code.toByte() }
        val r = TextDecode.readStream(ByteArrayInputStream(big))
        assertTrue(r is TextDecode.Decoded.Failed)
        assertTrue((r as TextDecode.Decoded.Failed).message.contains("太大"))
    }

    @Test
    fun `正常大小的流可以正常读出来`() {
        val json = """{"format":"timetable","version":2,"courses":[]}"""
        val r = TextDecode.readStream(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        assertTrue(r is TextDecode.Decoded.Ok)
        assertEquals(json, (r as TextDecode.Decoded.Ok).text)
    }

    // ============================================================
    //  七、错误信息本身的质量
    // ============================================================

    @Test
    fun `喂进二进制时错误信息里不能再摊出原始乱码`() {
        // 用户截的图里，错误弹窗被几百字节的乱码撑满，
        // 有用的信息全被挤没了。这条守着「错误信息必须是人话」。
        val garbage = ByteArray(2000) { (it % 251).toByte() }
        val r = ScheduleFormat.parse(garbage.toString(Charsets.UTF_8), 19)

        assertTrue(r is ScheduleFormat.Result.Failed)
        val msg = (r as ScheduleFormat.Result.Failed).message
        assertTrue(
            "错误信息过长（${msg.length} 字符），说明原始内容又被摊进来了",
            msg.length < 400
        )
    }

    @Test
    fun `正常 JSON 语法错误还是要保留具体原因`() {
        // 反向保证：不能为了防乱码，把有用的诊断信息也一起砍掉
        val r = ScheduleFormat.parse("""{"format":"timetable","version":2,""", 19)
        assertTrue(r is ScheduleFormat.Result.Failed)
        val msg = (r as ScheduleFormat.Result.Failed).message
        assertTrue("短文件应当保留 org.json 的原始说明，实际：$msg", msg.contains("不是合法的 JSON"))
    }
}
