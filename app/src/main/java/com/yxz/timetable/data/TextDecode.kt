package com.yxz.timetable.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 字节 -> 文本，并且**在解不出来的时候说清楚为什么**。
 *
 * ============================================================
 *  为什么要单独抽出来
 * ============================================================
 *
 * 原来导入那一行是这么写的：
 *
 * ```kotlin
 * it.readBytes().toString(Charsets.UTF_8)
 * ```
 *
 * 这行代码「能跑」，但它藏了一个**最坏的那种 bug**：
 *
 * > `toString(UTF_8)` 对任何字节都不会失败。
 * > 遇到不合法的字节，它不抛异常，而是悄悄塞一个「�」(U+FFFD) 进去。
 *
 * 于是：
 *
 *   文件是 GBK 编码  →  读出来课名全是「˼������뷨��」
 *                    →  JSON 语法部分（大括号引号，全是 ASCII）照样合法
 *                    →  **解析成功、导入成功、一句报错都没有**
 *
 * 用户看到的是「课程：24 门」，确认之后课表里的课名全成了乱码。
 * 这比直接报错糟糕得多 —— **报错至少知道自己没成功**。
 *
 * ============================================================
 *  判断顺序，以及每一步为什么这么排
 * ============================================================
 *
 * **① UTF-16 的 BOM（FF FE / FE FF）→ 直接拒，并给出具体操作**
 *   记事本「另存为」时编码选「Unicode」就是这个。它的中文是两个字节一个字符，
 *    按 UTF-8 读会得到一堆问号，报出来的错完全看不懂。
 *
 * **② UTF-8 的 BOM（EF BB BF）→ 剥掉，不是报错**
 *   这一步是**修 bug 不是挑刺**：BOM 长得像空白但不是空白，
 *   而 JSON 解析器要求第一个字符就是 `{`。带着 BOM 的文件会直接解析失败，
 *   报「A JSONObject text must begin with '{'」—— 用户看到这句话完全不知道
 *   是 BOM 的锅，因为文件用记事本打开看着完全正常。
 *   **BOM 是 Windows 世界很常见的东西，能自动处理就别拿去麻烦用户。**
 *
 * **③ NUL 字节 → 判定为二进制文件**
 *   真正的文本文件（UTF-8 / GBK / GB18030）里不会出现 0x00。
 *   图片、Word、压缩包里满地都是。这条最干脆。
 *
 * **④ 按 UTF-8 解码后数「�」→ 判定编码不对**
 *   这是本文件的核心：**把「静默失败」变成「响亮失败」**。
 *   一个都不能有 —— 课表 JSON 里正常不该出现 U+FFFD 这个字符，
 *   真出现了必然是解码解坏了。
 *
 * **⑤ 控制字符比例过高 → 再兜一层「这不像文本」**
 *   有些二进制内容碰巧是合法 UTF-8（短文件尤其容易），
 *   第 ④ 步抓不住它，但里面的控制字符会露馅。
 *
 * ============================================================
 *  设计上的一个取舍
 * ============================================================
 *
 * 四条判断全都**偏向「宁可误拒，不要错收」**。
 *
 * 理由是这两种错误的代价不对称：
 *   - 误拒：用户看到一段说得清楚的话，知道该怎么办，损失是几秒钟
 *   - 错收：课表被静默写成一堆乱码，用户可能过了一周才发现
 *
 * **当两种错误的代价差很多时，判断阈值就该往代价大的那一侧偏。**
 */
object TextDecode {

    sealed interface Decoded {
        /** 成功。text 已经是剥离 BOM 之后的干净文本 */
        data class Ok(val text: String) : Decoded

        /** 失败。message 是直接给用户看的话，要能照着做 */
        data class Failed(val message: String) : Decoded
    }

    /**
     * 文件大小上限。
     *
     * 课表 JSON 通常 15 KB 左右（内置那份完整示例也才 14.8 KB），
     * 2 MB 已经宽到不可能误伤，但能挡住「手滑选了个几百兆的视频」
     * —— 不加限制的话 `readBytes()` 会当场把内存吃光。
     */
    const val MAX_BYTES = 2 * 1024 * 1024

    // ==================================================================
    //  对外入口
    // ==================================================================

    /** 从已经读进来的字节解码。这个函数是**纯函数**，所以能被单元测试直接覆盖 */
    fun fromBytes(bytes: ByteArray): Decoded {
        if (bytes.isEmpty()) return Decoded.Failed("文件是空的。")

        // ---- ① UTF-16 的 BOM ----
        val b0 = bytes[0].toInt() and 0xFF
        if (bytes.size >= 2) {
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                return Decoded.Failed(
                    "这个文件是 UTF-16 编码的（记事本里把编码选成「Unicode」就是这个格式），" +
                            "App 只能读 UTF-8。\n\n" +
                            "用记事本打开它 →「文件 → 另存为」→ 把底部的「编码」改成 UTF-8 → 再导入一次。"
                )
            }
        }

        // ---- ② UTF-8 的 BOM：剥掉而不是报错 ----
        val body = if (bytes.size >= 3 &&
            b0 == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

        if (body.isEmpty()) return Decoded.Failed("文件是空的（只有一个 BOM）。")

        // ---- ③ NUL 字节 = 二进制 ----
        if (body.any { it == 0.toByte() }) {
            return Decoded.Failed(BINARY_MESSAGE)
        }

        val text = body.toString(Charsets.UTF_8)

        // ---- ④ 「�」的个数 = 有多少字节不是合法 UTF-8 ----
        val broken = text.count { it == '�' }
        if (broken > 0) {
            return Decoded.Failed(
                "这个文件不是 UTF-8 编码，有 $broken 处内容无法解码。\n\n" +
                        "如果它确实是课表 JSON，多半是被存成了「ANSI / GBK」编码" +
                        "（Windows 中文系统里记事本「另存为」的默认选项）。\n" +
                        "改法：用记事本打开它 →「文件 → 另存为」→ 把「编码」改成 UTF-8 → 再导入一次。\n\n" +
                        "如果你选的其实是截图或文档，请改选从 App 里导出的 .json 文件。\n\n" +
                        "—— 这一步必须拦下来：硬按 UTF-8 读进去的话，课名会全部变成乱码，" +
                        "而且不会报任何错，你要过很久才会发现。"
            )
        }

        // ---- ⑤ 控制字符比例 ----
        val control = text.count { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }
        if (control * 100 > text.length * 2) {
            return Decoded.Failed(BINARY_MESSAGE)
        }

        return Decoded.Ok(text)
    }

    /**
     * 从输入流读到上限为止再解码。
     *
     * 为什么不直接 `readBytes()`：那个函数会把文件**整个**读进内存，
     * 文件多大就吃多少。选错成一个大视频就是一次 OOM 崩溃。
     * 边读边数，超了就提前收手，把「崩溃」变成「一句提示」。
     */
    fun readStream(input: InputStream): Decoded {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                return Decoded.Failed(
                    "读取文件时出错：${e.message ?: e.javaClass.simpleName}\n\n" +
                            "如果这个文件在网盘、微信或 SD 卡里，先把它复制到手机本机再试一次。"
                )
            }
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) {
                return Decoded.Failed(
                    "文件太大了（超过 ${MAX_BYTES / 1024 / 1024} MB）。\n\n" +
                            "课表 JSON 正常只有几十 KB，请确认你选的是从 App 里导出的那个文件。"
                )
            }
        }
        return fromBytes(out.toByteArray())
    }

    private const val BINARY_MESSAGE =
        "这个文件不是文本文件，里面是二进制内容（图片、Word 文档、压缩包都是这样）。\n\n" +
                "请选择从 App 里导出的 .json 课表文件。" +
                "\n\n如果不知道是哪个：在 App 里点「设置 → 数据 → 导出」，先导出一份做对照。"
}
