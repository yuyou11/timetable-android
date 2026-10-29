# p3web · Persona 3 网页样稿

几件事放一起：

- **今天页 HUD**：照 `docs/persona3/ref/今天.png` 逐像素临摹，定完再搬 Android；
- **课表页**：游戏里没有课表，按 P3 的版式语言重新设计（方案 B：整页重画），先出样稿再写 `PersonaWeekView`；
- **闹钟页**：首屏只有表盘 + 数字时钟，下滑才露出「此刻」卡、闹钟列表和新建按钮；新建按钮重新设计。

> **落地状态（2026-10）**：`today-p3-v3` → `PersonaTodayView`、`week-p3-v2` → `PersonaWeekView`、
> `alarm-p3-v4` → `AlarmFragment`，三份样稿都已经进 APK。真机截图在同目录：
> `android-today-p3.png` / `android-today-p3-card.png`（今天页 + 弹卡）、
> `android-week-p3.png` / `android-week-p3-card.png`（课表页 + 弹卡）。
> 下面的 HTML 仍然留着 —— 改版式先改这边，比反复装 APK 快。

## 文件

| 文件 | 是什么 |
|---|---|
| `today-hud.html` | 整块 HUD，一个 SVG（坐标 = 原图像素） |
| `today-hud.png` / `today-hud@2x.png` | 586×183 / 1172×366 截图 |
| `compare-view.png` / `compare@2x.png` | 上面原图、下面复刻的对比图 |
| `marker-compare.png` | 三角标记的 8 倍特写对比 |
| `make_compare.py` | 拼对比图 |
| `week-p3.html` | 课表页**初稿**（换皮思路：保留原表格，只换颜色） |
| `week-p3.png` | 初稿截图 412×915 |
| `week-p3-v2.html` | 课表页**整页重画样稿**（方案 B）→ 已落地 `PersonaWeekView` |
| `week-p3-v2.png` | 双屏总览（主界面 + 点格弹卡），1700×1708 |
| `week-p3-v2-main.png` / `week-p3-v2-card.png` | 两张单屏 786×1600（2 倍图） |
| `android-week-p3.png` / `android-week-p3-card.png` | **真机截图**（1080×2400）：实现后的课表页与点格弹卡 |
| `android-week-p3-compact.png` | 课表页收紧行高后的实拍（行高 = 可用高度的 2/3，密度条跟着网格往上收） |
| `android-nav-plain.png` / `android-nav-plain-today.png` | 底栏特写（2×）：早先的白底版（现已改成深藏青底） |
| `android-nav-noline-calendar.png` / `android-nav-noline-week.png` | 撤掉栏顶墨蓝细线之后（白底版）：白底直接贴页面下沿 |
| `android-nav-dark-calendar.png` / `android-nav-dark-week.png` | 底栏试过的深藏青底（**已废弃**，最终改成浅灰） |
| `android-nav-gray-bar.png` / `android-nav-gray-today.png` | **最终版底栏**：浅灰底 + 选中墨蓝，五项图标与字号一致 |
| `today-p3-v3.html` | 今天页**整页重画样稿**（蓝 HUD + 白色日程纸）→ 已落地 `PersonaTodayView` |
| `today-p3-v3.png` | 双屏总览（主界面 + 点格弹卡），1700×1708 |
| `today-p3-v3-main.png` / `today-p3-v3-card.png` | 两张单屏 786×1600（2 倍图） |
| `today-p3-v3-marker.png` | 三角标记 4 倍特写（「二」探出旗子、右侧被斜边切住） |
| `android-today-p3.png` / `android-today-p3-card.png` | **真机截图**（1080×2400）：实现后的今天页与点格弹卡 |
| `android-today-p3-sky.png` | 今天页换背景后的单张实拍（上半屏 = 闹钟页那张 `bg_alarm_page` 手绘天空） |
| `android-today-scroll.png` / `android-today-scroll-card.png` | 今天页时间线滑到当天末尾的样子，以及滑动之后点一格弹出的信息卡 |
| `android-alarm-hint-scroll.png` | 闹钟页上滑时「下滑 · 查看闹钟」与表盘同步上移的对比（各 32px） |
| `android-alarm-clock-detail.png` | 表盘特写：正面视角、IIII、AM/PM、圈外尖刺、纺锤指针 |
| `android-alarm-clock-v2.png` | 表盘 v2：加粗双圈、骑在圈上的尖刺、内圈 12 个筝形 |
| `android-alarm-clock-v3.png` | 表盘 v3：外圈更阔更粗、外三角更短更宽、内三角更长 |
| `android-alarm-clock-v4.png` | 表盘 v4：外三角真正戳出圈外（1.20R / 1.11R），主半径收到 0.82 |
| `android-alarm-clock-v5.png` | 表盘 v5：外三角底边钉在外圈外沿（0.987R），不再与圆相交 |
| `android-alarm-clock-v6.png` | 表盘 v6：正点尖从 1.16R 收到 1.120R（普通 1.085R），长短只差三成 |
| `android-clock-vs-ref-v2.png` … `-v6.png` | 与游戏截图并排对照（左原图 / 右实现） |
| `android-settings-home.png` | 设置首页（P3）：01–04 分类列表 |
| `android-settings-home-clean.png` | 同上，去掉「SETTINGS」下面那条墨蓝横杠之后的样子 |
| `android-settings-data.png` / `android-settings-features.png` / `android-settings-theme.png` | 设置分类页换皮后：深藏青直角卡片 + 青色勾选/开关 + 描边按钮 |
| `android-today-bg-before-after.png` | 三联对比：换背景前 / 换背景后 / 闹钟页，同一块蓝的来历 |
| `android-calendar-p3.png` | 日历页实拍（蓝底同样是那张 `bg_alarm_page` 手绘天空） |
| `alarm-p3-v3.html` | 闹钟页 v3（表盘 + 下次响铃大字 + 列表；底部旧按钮保留） |
| `alarm-p3-v3.png` | 双屏总览（列表 + 响铃），1700×1708 |
| `alarm-p3-v3-main.png` / `alarm-p3-v3-ring.png` | 两张单屏 786×1600（2 倍图） |
| `alarm-p3-v4.html` | 闹钟页 v4：滚动重制（首屏只有钟 + 数字钟；下滑出「此刻」卡 / 列表 / 新按钮）→ 已落地 |
| `alarm-p3-v4-top.png` / `-mid.png` / `-list.png` / `-ring.png` | 四态单屏 786×1600（首屏 / 转场 / 列表 / 响铃） |
| `alarm-p3-v4-nowcard.png` | 「此刻」卡 2 倍特写（蓝玻璃底 + 深藏青面板 + 白时间条） |
| `alarm-p3-v4-clock.png` | 表盘 2 倍特写（按 `ref/时间（影时间）.png` 重画） |
| `alarm-p3-v4-scroll.gif` | 滚动动画（393×800，9 帧，约 600 KB） |

## 坐标系

原图 `../docs/persona3/ref/今天.png` 是 586×231 的视频帧，**上面 48px 是黑边**。
复刻只画去掉黑边之后的 **586×183** 画面区：

```
原图坐标 y   →   复刻坐标 y − 48
```

HTML 里所有数字都能直接和原图对上，改完立刻能算出差多少。

## 每块元素

| 元素 | 原图 bbox | 做法 |
|---|---|---|
| `WEEKDAY` 巨字 | x 55..585+（右端和上缘都被画面切掉） | Impact 150px、`skewX(-10°)`、`#162066` |
| 蓝光带 | x 20..、y 30..150 | 矩形 + 竖向渐变（上 `#0066e3`、下 `#042fe0`）+ 左端渐隐遮罩 + 高斯模糊 |
| 潮汐水波纹 | 光带内部 | 9 个**同样透明度、同样颜色**的正圆（`#0f86ff` @ 0.52，r 170–450）乱放着互压：叠一层浅、叠三四层深，明暗差是叠出来的；圆的大小和高低都不齐（cy 250–540），**不加任何模糊**。带子的上下/左边界靠两道渐隐遮罩让图案自己淡掉，没有外加的蓝色模糊底。WEEKDAY 字因此在带子里以深色字影透出来 |
| 日期 `4/22` | x 195..336、y 39..93（字高 55） | Arial Black 74.3px、`textLength=142` 压窄、`skewX(-8°)` |
| 三角标记 | 三角形 344..406 × 46..93 | 看着像「黑三角 + 三条白杠」，其实是**当天的周几字**（原图那天是周三，「三」正好是三横）。按 `PersonaDateHud.drawMarker` 实现：34px 白字 + 3px 深色描边，用「三角形右边缘左侧」的多边形（`markerClip`）裁切 —— 左半截探出旗子压在蓝底上，右半截齐着斜边被切掉。样稿 10/27 是周二，所以旗子里写「二」 |
| 月亮 | 圆 r=39.5 @ (481.5, 86.5) | 暗面 = **同心圆 r=36**，左边被一块 **椭圆（cx 486 / rx 24 / ry 40）的左弧切掉**：右边是与黄盘同心的细黄边，左边是月牙，两段弧上下相交留下两个明显的角（`moonMask`） |
| `夜晚` | x 318..392、y 100..130（字高 31） | 微软雅黑 Bold 32px、字距 12、青 `#12e9f5` + 深色描边 |

### 三角标记的三个细节

- **它不是「三条白杠」**。原型截的是 4/22（周三），旗子里的「三」正好是三横，被当成了白杠；
  Android 端（`PersonaDateHud.drawMarker`）已经改成画**当天的周几字**（一..日）。
- **裁切区不是三角形本身**，而是「三角形右边缘的左侧」这个四边形
  `(-20,0) (406,46) (375,93) (-20,183)`。所以字的左半截能探出旗子、压在蓝底上；
  右半截齐着斜边被切掉。探出去那半截没有深色旗子托底，所以先描一圈旗子同色的 3px 深边再填白。
- **字比旗子大**（34px vs 62×47）。字落到旗子下半段时会从左侧溢出去，这是原图就有的效果，
  不是画错；也正因为如此，周三的「三」看起来才像三条杠。

## 课表页（WEEK）· 整页重画样稿

`week-p3-v2.html`。画布 **393×800dp**（和 `docs/persona3` 的 k 基准一致），数据取内置示例课表**第 7 周**（周一到周五每天都有课），今天周三。

版式上的几个决定：

- **标题**：Impact 78px、`skewX(-8°)`、2.5px 深墨蓝描边；巨号 `07` 压在后面（日历页「巨号月份」同款动作）。
- **周次选择**：收进标题区 —— 两个 Q/E 键帽 + 青色日期条 + 「第 7 周」，不再单独占一张大卡。
- **网格**：去掉格线与卡片白底，课程是深藏青玻璃块（亮蓝细边 + 青色左沿），空格子全透明；今天整列是一条青色光柱，表头挂品红 `TODAY` 胶带。
- **表头**：一~五白、六青、日红，沿用日历页的规则；节次列只留「第 n 节 + 时间」。
- **近五周**：底部一条密度条，5 组 7×N 点阵，当前周白点、标签青色。
- **弹卡**：`week-p3-v2-card.png`，深藏青矩形对话框 + 「本周」徽标 + 「设置闹钟」。

和 v1（`week-p3.html`）的差别：v1 保留原版「周次大卡 + 表格」，只换颜色；v2 是整页重排，用来给 Android 端的 `PersonaWeekView` 做坐标参照。

改尺寸：所有数字都是 CSS px，数值即 dp；改完重跑下面的出图命令。

## 今天页（TODAY）· 重画样稿

`today-p3-v3.html`。画布 **393×800dp**，数据取内置 **B 型日模板 + 第 8 周周二**，时间 22:07。

这一版把今天页拆成「上半屏 HUD + 下半屏白纸」：

- **蓝色 HUD**：`10/27`（Impact 80px、深墨蓝描边）、P3 三角标记、`22:07` + 小月牙 `夜晚`、周次 / 日型；右上角是收成一颗胶囊的**常驻开关**。
- **白色日程纸**：斜边压住蓝底，纸上依次是
  - 24 小时色带（睡觉 / 自习 / 上课 / 吃饭 / 自由 / 事务 / 路上，七种颜色；现在位置一条品红线）；
  - **NOW 卡**：此刻这一格、还剩几分钟、进度、接下来是什么；
  - **时间线**：从 19:00 起 5 行，当前行青色高亮 + 品红左边，过去行压暗；
  - **明天票**：起床时间、日型、几节课、一键设闹钟。
- **弹卡**：`today-p3-v3-card.png`，标题是这一格的时间段，卡里是「这一格是什么 + 时间 / 规律 / 备注 + 设置闹钟」。

设计上的取舍：把「常驻开关」从一整行卡片收成胶囊、把「明天」做成一张票，首屏只留给**此刻 + 接下来**；完整时间线仍在白纸上滚动，只截了当前这一段。

> 形状规范（所有样稿都遵守）：**卡片 / 弹卡 / 按钮一律直角矩形**，不做缺角、不做斜切；
> 斜体 / 斜切只留给标题字、日期条、`TODAY` 胶带这些「字与胶带」，不用于承载内容的容器。
>
> **v3 的旧按钮**是历史例外：当时按「原设计不动」保留 `PersonaButtonBackground`
> 的斜切四边形；**v4 已解除这个约束**，新建按钮重新设计成矩形（左青块 + 深藏青长条）。

## 闹钟页（ALARM）· v4 滚动重制（已落地）

`alarm-p3-v4.html`。画布 **393×800dp**，首屏只有表盘和数字时钟；白纸在下面，通过滚动升上来。
Android 侧已按这张样稿实现：`AlarmFragment` + `fragment_alarm.xml`，版本 `2.1.2-alarmp3`。

- **首屏**：P3 表盘 + 84px 数字时钟 `22:07` + 日期 + 一条很细的「时间线」+ 下滑提示。
  表盘按 `ref/时间（影时间）.png` 重画：罗马数字 18px 粗体，落在内外圈正中
  （外圈 104 / 内圈 82 / 数字 93，外圈不再压字）；指针改成叶形粗针；
  12 根尖刺都是**三角形**（底边压在外圈、顶点朝外），12/3/6/9 更大。
  表盘 260，整体下移到页面中部（顶部留 120dp 呼吸），不再贴着状态栏。
- **上滑**：表盘 + 数字钟 + 日期作为一个组，**只向上平移**（0.32 × 滚动距离），不缩放、不淡出；
  白纸从下面盖上来 —— 动画见 `alarm-p3-v4-scroll.gif`。
- **「此刻」卡**：用上一代**闹钟记录卡的背景** —— 蓝色玻璃底（`#4884FF` 偏移一层）+ 深藏青面板
  + 白色时间条 `22:07` + 「下次响铃 06:40」。缺角改直角，但蓝底/深蓝面板/白条这三层保留。
- **列表**：4 张直角矩形卡（3 开 1 关），时间大字 + 状态 + 下次；关闭的那张整卡压暗。
- **新建按钮**：重新设计 —— 左边 54px 青色 `＋` 块，右边深藏青长条「新建闹钟 / 设置时间 · 重复 · 铃声」，
  最右一个小箭头；矩形，按下有 0.985 压缩反馈。加号用两条 CSS 矩形画（20×3 / 3×20），
  几何中心对齐，不再用字体的「＋」（那个字形视觉重心偏下）。**不再是上一代的斜切按钮。**
- **响铃弹窗**：去掉品红描边和霓虹紫。深藏青面板 + 细蓝边 + 2px 暗红顶线；
  「RINGING」只做小红字，停止按钮用暗红 `#B23A48`，贪睡留描边；整只表盘和数字钟转薄荷绿。
- **底部**：`ALARM LOG` 页脚 + 一条分隔线，把纸的底部收住。

## 闹钟页（ALARM）· v3（旧稿：按钮曾按原设计保留）

> v3 是上一版；这里保留作对照。它的「＋ 新建闹钟」按钮还是旧的斜切四边形，v4 已经改掉。

`alarm-p3-v3.html`。画布 **393×800dp**，取 22:07、下次 06:40（明天 06:40）。

**v3 历史：底部「＋ 新建闹钟」按钮卡。** 原设计的斜切四边形、`#4884FF` 实底、
`#4660DC` 1.4px 描边、白字、48dp 高度全部照旧（`PersonaAlarmStyle.button`，slant 7dp）；
样稿里用一条 SVG path 一比一复刻。**v4 已解除这个约束并重新设计按钮**，这段只留作对照。

其余部分是这次优化：

- **表盘**：P3 双圈 / 12 根尖刺 / 罗马数字 / 纺锤指针，白线；响铃时整只转绿（影时间）。
- **下次响铃**：从原来一行 12.5sp 小字，改成白纸上的 46px 大字 + 「明天 · 周三 / 还有 8 小时 33 分」。
- **列表**：直角矩形深蓝卡，白色圆角条装时间，下面两行说明（重复 / 下次），右侧开关。
- **响铃**：暗幕把页面压下去，绿表盘留在上面，浮层只给「停止 / 贪睡 10 分钟 / 解锁手机」。
- **新建 / 编辑对话框**：布局、字段、交互都不动，只把外轮廓从缺角改成直角；
  本轮样稿不展开它（避免误改）。

## 出图

Edge 无头模式（沙箱下浏览器起不来，得在完全权限下跑）：

```powershell
msedge --headless=new --window-size=800,400 --force-device-scale-factor=2 `
       --screenshot=raw.png "file:///.../p3web/today-hud.html"
# 再裁 (0,0,1172,366) 就是 2 倍图
python make_compare.py today-hud.png compare@2x.png 2
```

课表样稿（两张 393×800 并排 → 850×854 窗口）：

```powershell
msedge --headless=new --hide-scrollbars --window-size=850,854 `
       --force-device-scale-factor=2 `
       --screenshot=week-p3-v2.png "file:///.../p3web/week-p3-v2.html"
# 单屏裁切：左 (32,76,818,1676)，右 (870,76,1656,1676)
```

今天页样稿同样两张 393×800：

```powershell
msedge --headless=new --hide-scrollbars --window-size=850,854 `
       --force-device-scale-factor=2 `
       --screenshot=today-p3-v3.png "file:///.../p3web/today-p3-v3.html"
```

闹钟页样稿同样两张 393×800（列表 + 响铃）：

```powershell
msedge --headless=new --hide-scrollbars --window-size=850,854 `
       --force-device-scale-factor=2 `
       --screenshot=alarm-p3-v3.png "file:///.../p3web/alarm-p3-v3.html"
```

闹钟页 v4 按滚动位置出图（`?pos=0` 首屏、`?pos=0.35` 转场、`?pos=1` 列表、`?ring=1` 响铃）：

```powershell
msedge --headless=new --hide-scrollbars --window-size=425,854 `
       --force-device-scale-factor=2 `
       --screenshot=alarm-p3-v4-top.png "file:///.../p3web/alarm-p3-v4.html?pos=0"
# 单屏裁切 (32,76,818,1676)；GIF 由 pos=0..1 的 9 帧拼成
```

## 还和原图不一样的地方

- **背景是游戏的实景**（教室地板、窗外光），网页里只做了夜蓝底，没去仿那张照片；
- **字体都是系统字**（Impact / Arial Black / 微软雅黑），游戏里是自绘字形，`2` 的角和 `夜晚` 的笔画还有差距；
- 原图是视频帧，压缩糊过一遍，边缘的软硬没法逐像素还原。
