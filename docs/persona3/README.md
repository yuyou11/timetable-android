# Persona 3 彩蛋（P3 模式）

给 Time Machine 加的一层「换皮」外观，模仿 *Persona 3 Reload* 的界面。
**默认关闭**，在 设置 → 外观 → 主题 下面那个 `PERSONA 3 模式` 开关里打开。

打开后会 `recreate()` 整个 Activity —— 和换强调色走同一条路，因为这不是
「改几个色值」，而是整页换一种画法。

```
设置 → 外观
  ├─ 主题            ← 八套强调色（换的是 ?attr/accentColor）
  └─ PERSONA 3 模式   ← 本彩蛋（换的是整页外观）
```

## 现在做到哪一步

| 页面 | 状态 |
|---|---|
| 日历 | ✅ 已完成（[PersonaCalendarView](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaCalendarView.kt)）· 蓝底已换成和闹钟页同一张手绘天空（[真机截图](../../p3web/android-calendar-p3.png)） |
| 今天 | ✅ **v3 整页重画已落地**：蓝 HUD + 斜口白纸日程，见 [PersonaTodayView](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaTodayView.kt) · 样稿 [p3web/today-p3-v3.html](../../p3web/today-p3-v3.html) · [真机截图](../../p3web/android-today-p3.png) |
| 闹钟 | ✅ **v4 已落地**：首屏只有表盘 + 数字钟，上滑白纸；「此刻」卡用旧闹钟记录卡三层背景；新建按钮 / 矩形列表 / 低对比响铃，见 [p3web/alarm-p3-v4.html](../../p3web/alarm-p3-v4.html) · [列表截图](../../p3web/alarm-p3-v4-list.png) |
| 设置 | ✅ 首页换成 P3 主菜单（[PersonaMenuView](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaMenuView.kt)：01–04 编号 + 中文分类名 + 英文小标签）；**四个分类页整套换皮**（[PersonaSettingsSkin](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaSettingsSkin.kt)：深藏青直角卡片 / 青色勾选与开关 / 描边按钮），见 [首页](../../p3web/android-settings-home.png) · [数据页](../../p3web/android-settings-data.png) · [功能页](../../p3web/android-settings-features.png) |
| 底部导航 | ✅ **浅灰底 + 五个窄体粗斜标签**（选中墨蓝、未选中次要灰），没有线、没有装饰（[截图](../../p3web/android-nav-gray-bar.png)） |
| 课表 | ✅ **v2 整页重画已落地**：巨号 WEEK / 幽灵周次 / 青色今天光柱 / 底部近五周密度条，见 [PersonaWeekView](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaWeekView.kt) · 样稿 [p3web/week-p3-v2.html](../../p3web/week-p3-v2.html) · [真机截图](../../p3web/android-week-p3.png) |
| 点格弹卡 | ✅ **P3 皮肤**：黑卡 + 青字 + 描边按钮（[PersonaCardSkin](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaCardSkin.kt)），见 [截图](../../p3web/android-week-p3-card.png) |

到这一步，**五个页签和两种弹卡都在同一个世界里了**：日历、今天、课表是整页自绘，
闹钟和设置是刷皮肤，底部导航是自绘底板。日历页最早做，因为它最独立、又能把
「深蓝水彩 + 白纸 + 重体标题」这套观感一次定死；今天和课表的整页版后来
沿用同一套蓝、同一套矩形卡片语言和同一份调色板（[PersonaP3](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaPageView.kt)）。

## 视觉对照

参考图在 [`ref/`](ref/)，来自游戏内截图（`日历.png`）。效果图是
[`mock-calendar.png`](mock-calendar.png)，由 [`mock_calendar.py`](mock_calendar.py) 生成。

| 游戏里的元素 | 这里的实现 |
|---|---|
| 深蓝水彩底 | 直接铺 `R.drawable.bg_alarm_page`（渐变 + 手绘天空 + 顶部压暗）—— **和闹钟页、今天页同一份资源**；取不到时才退回三段 `LinearGradient` + 两团 `RadialGradient` 柔光 |
| 右侧那片白色大弧 | 一条三次贝塞尔曲线切出来的白色区域 |
| 左上巨大斜体 `CALENDAR` + 年份 | `textSkewX = -0.22`，先描深墨蓝边再盖白字 |
| 压在网格后的巨号月份 | 150sp 的 `#010D64`，透明度 88 |
| `SUN` 红 / `SAT` 青 / 其余白 | 列号决定颜色，和游戏一致 |
| 日期之间没有格线 | 本来就不画线 —— 这是和原生日历最大的差别 |
| 今天：品红三角纸片 + `TODAY` | 三个顶点量自原图（`drawToday`）：(0.54,0) / (1.0,0.67) / (0.13,0.95)。**数字与同一行其他日期同字号、同基线**，纸片挂在数字下方 |
| 纸片一直在极轻微地飘 | 三个顶点各带一份 `jitter` 偏移：每 72ms 一拍、各有 32% 概率换 ±2.2dp 的新目标，再以 0.22 插值靠近（`jitterTick`）。慢、不跳、整体仍是三角形 |
| 选中日：金色细圆环 | `#908838`，1.4dp 描边 |
| 有节日的日子下面的小白点 | 换月时用 `Festivals` 算一次，存成集合（不在 `onDraw` 里跑 ICU） |
| 右侧白栏：青色日期条 + 一条说明 + `Guide` | 竖屏放不下侧栏，改成底部白区里的同款元素 |

### 竖屏适配做的取舍

游戏是 16:10 横屏，白色区域在**右侧**。手机竖屏照搬会让日历只剩 2/3 宽度，
所以改成**底部**一条白区，弧线方向保留（左低右高）。其余元素（标题、表头、
日期网格、今天的撕纸）都按原图的相对位置放。

## 文件清单

| 文件 | 作用 |
|---|---|
| `ui/persona/PersonaPageView.kt` | **两页共用的底座**：调色板 `PersonaP3`、k 比例、蓝底 / 布纹 / 潮汐光斑、描边标题、混色行、按宽度折行 |
| `ui/persona/PersonaWeekView.kt` | 课表页整页自绘：表头、星期行、十行网格、今日光柱、课块、密度条、点格 / 翻周手势 |
| `ui/persona/PersonaTodayView.kt` | 今天页整页自绘：HUD（日期 / 三角旗 / 此刻 / 日型）、白纸、24 小时色带、此刻卡、时间线、明天卡 |
| `ui/persona/PersonaTodayModel.kt` | 今天页的纯逻辑：色带分段、时间线可见窗口、时段配色（**有单测**） |
| `ui/persona/PersonaCardSkin.kt` | 点格弹卡的 P3 皮肤（黑卡 + 青字），字段和绑定逻辑不动 |
| `ui/WeekGrid.kt` | 「哪几格合成一块」+ 近五周点阵的数据，默认外观与 P3 外观共用（**有单测**） |
| `ui/persona/PersonaCalendarView.kt` | 日历整页自绘：背景、网格、今天、白栏、手势 |
| `ui/persona/PersonaDateHud.kt` | 旧的今天页 HUD 横幅。v3 整页版不再挂它，但 `phaseOf` / `moonLitFraction` 仍被新页面复用（有测试守着） |
| `ui/persona/PersonaTodayBackground.kt` | 旧今天页的整页背景（v3 不再用） |
| `ui/persona/PersonaDeepBlueBackground.kt` | 默认外观下半页的斜马赛克深蓝底 |
| `ui/persona/PersonaAlarmStyle.kt` | 闹钟页的皮肤：调色板、对话框卡、按钮、标题条、上下分屏背景、字体 |
| `ui/persona/PersonaClockView.kt` | P3 表盘（双圈 / 罗马数字 / 尖刺 / 纺锤指针；响铃转绿） |
| `ui/persona/PersonaMenuView.kt` | 设置首页的 P3 菜单：01–04 编号、中文分类名、英文小标签，点行进分类 |
| `ui/persona/PersonaSettingsSkin.kt` | 设置分类页的皮肤：卡片 / 勾选框 / 开关 / 按钮 / 文字，按颜色和 ConstantState 认，不认 id |
| `ui/persona/PersonaNavBubble.kt` | 底部导航的底板：白纸 + 栏顶墨线（不再画圆 / 方板 / 选中块） |
| `ui/CalendarFragment.kt` | 按 `Store.p3Mode` 二选一挂载；月份 / 农历 / 节日逻辑复用 |
| `ui/TodayFragment.kt` | 按 `Store.p3Mode` 二选一挂载；日期 / 此刻 / 明天 / 闹钟逻辑复用 |
| `ui/WeekFragment.kt` | 按 `Store.p3Mode` 二选一挂载；周次 / 课程 / 点格弹卡逻辑复用 |
| `ui/AlarmFragment.kt` | 表盘 / 卡片 / 按钮 / 分屏线 |
| `ui/SettingsFragment.kt` | `swPersona3` 开关 + 首页菜单 |
| `MainActivity.kt` | 导航字体、圆、水波纹 |
| `data/Store.kt` | `p3Mode` 偏好（键 `p3_mode`） |
| `res/layout/fragment_calendar.xml` / `fragment_today.xml` / `fragment_week.xml` | 外层套 `FrameLayout`，多一个 `p3Host` 宿主 |
| `res/layout/activity_main.xml` | `personaBackdrop` 兄弟层（画底板和圆） |
| `docs/persona3/mock_calendar.py` | 日历效果图生成器，坐标和 Kotlin 一一对应 |
| `docs/persona3/mock_*.py` | 表盘 / 闹钟 / 卡片的效果图生成器 |
| `p3web/` | 网页样稿（今天 v3 / 课表 v2 / 闹钟 v4）与真机截图 |
| `docs/persona3/ref/` | 游戏内截图，所有坐标的来源 |

## 闹钟页的表盘

照 `ref/时间（影时间）.png`（游戏里的「影时间」那只钟）重建，细节按游戏截图逐条对过：
**正面视角**（不带透视）、双圈、圈内 12 个随角度旋转的罗马数字（**4 点是 IIII**，钟表写法）、
轴心下方一行 AM / PM、纺锤形时针（0.52R）与分针（0.84R，几乎顶到数字圈），另加一根细的秒针。

**内外两圈都有「角」，形状还不一样**：

- **外圈外侧**：12 根窄三角，底边钉在**外圈的外沿**（0.987R = 0.975R 圆 + 半个描边），
  整个三角形只在圈外、**不和圆相交**；尖顶 1.120R（12/3/6/9）/ 1.085R（其余），
  半宽 0.024R —— 正点只比普通点长三成多，不是长短两套。
- **内圈上**：12 个**筝形**（不是短线）—— 一条对角线正好落在内圈上（两个侧顶点贴在圆周），
  另一条沿半径一外一内，向圆心那侧更长（0.080R）。所以内圈不是一条干净的圆，每个整点都鼓出一小块。

两道圈也粗：外圈 0.975R / 描边 0.024R，内圈 0.80R / 描边 0.020R。细了就像刻度尺。

- **正常状态是白线**，因为表盘现在压在上半屏的深蓝底上；
- **响铃时整只钟转绿**（影时间），停了变回白色；
- 轴心那个小孔用底色画，白线表盘上才看得出「孔」。

## 闹钟页的皮肤（P3 模式）

| 部位 | 做法 |
|---|---|
| 整页背景 | 上半 46% 深蓝渐变（和日历页同一套蓝），下半卡片色。`PersonaAlarmBackground` 按实际高度算分界 |
| 表盘 | 白线（见上） |
| 卡片 | 对话框样式：深藏青 `#0C1A46` 底 + 亮蓝 `#6FA8FF` 描边 + 左上/右下斜切角（`PersonaDialogBackground`） |
| 文字 | 统一换成 `sans-serif-condensed` 粗体；深蓝底上的文字转白 / 浅蓝 |

分界线为什么不用 XML：`layer-list` 的 inset 不接受百分比，只能按 px 写死，
换台屏幕就错位。所以做成 Drawable 在 `onDraw` 里按 bounds 算。

---

## 尺寸自适应

整页自绘的代价是「所有尺寸都是写死的数」，换台屏幕就可能走样。所以有两层：

**① 全局比例 `k`**（`onSizeChanged` 里算）

```
k = min(宽 / 393dp, 高 / 800dp)，夹在 [0.78, 1.35]
```

`dp()` 和 `sp()` 都乘 `k`，等于整套版式等比缩放。取宽高比例的**较小者**：
窄屏和矮屏都该整体缩小，只按宽度算会让矮屏竖向溢出。

⚠️ **基准高度不是所有页面都一样**：日历页是 393×800（它按整屏设计），
今天页和课表页是 **393×720**（网页样稿的 800 里含状态栏 22dp 和导航栏 56dp，
而 App 的页面容器本来就不含这两块）。改任何一页之前先确认它用的是哪个基准 ——
拿日历的 800 去套课表，整页会被压扁 10%。

**② 几个硬约束**（防止极端比例下散架）

| 约束 | 值 | 为什么 |
|---|---|---|
| 行高 | `[40, 84] × k` | 太矮不能把 6 行压成一条线；太高数字会漂在空地里 |
| 白纸下限 | 距底 `168 × k` | 保证日期条 + 说明 + Guide 有地方待 |
| 面板内容 | 面板 < `160 × k` 时省掉农历那行 | 先砍次要信息，保住日期条和 Guide |
| 大号日期 | 剩余空间 < `36dp` 时不画 | 宁可不画，也不要压住 Guide |
| 纸片 | `行高 × 1.06`，宽 `× 1.32` | 原图里纸片高度就是行距的 1.06 倍 |

实测各档（单位 dp，`纸片高` 和 `大字空间` 是算出来的）：

| 设备 | k | 行高 | 白纸左沿 | 面板高 | 纸片高 |
|---|---|---|---|---|---|
| 320×640 | 0.80 | 43.5 | 420.8 | 219.2 | 46.1 |
| 360×740 | 0.92 | 50.4 | 485.9 | 254.1 | 53.5 |
| 393×800 | 1.00 | 54.3 | 526.0 | 274.0 | 57.6 |
| 412×860 | 1.05 | 59.0 | 563.4 | 296.6 | 62.5 |
| 430×932 | 1.09 | 64.7 | 607.3 | 324.7 | 68.6 |
| 平板 800×1280 | 1.35（封顶） | 92.0 | 822.1 | 457.9 | 97.5 |

## 改版式怎么改

**两边一起改**：`mock_calendar.py` 是设计稿，`PersonaCalendarView.kt` 是实现。
脚本里所有尺寸都按 dp 写（`dp() = v * 2.75`），Kotlin 里同名常量直接对应。
先改脚本看效果图，觉得行再同步到 Kotlin —— 比反复装 APK 快得多。

⚠️ **底色两边对不上了**：效果图里的背景是脚本用渐变 + 柔光画的，而 App 里现在铺的是
`bg_alarm_page` 那张手绘天空。对版式时请只看**元素位置和字号**，不要拿背景色去比。

```powershell
python docs\persona3\mock_calendar.py docs\persona3\mock-calendar.png
```

~~还没做的一页：课表~~ → **已完成**（2026-10，见下）

## 课表页与今天页的整页版（v2 / v3）

这两页是同一批做的，因为它们是同一套语言：**上半屏蓝底 HUD、下半屏白纸或网格、
巨号标题压在幽灵数字上**。样稿分别是
[week-p3-v2.html](../../p3web/week-p3-v2.html) 和
[today-p3-v3.html](../../p3web/today-p3-v3.html)。

实现要点（代码在 [PersonaWeekView](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaWeekView.kt) /
[PersonaTodayView](../../app/src/main/java/com/yxz/timetable/ui/persona/PersonaTodayView.kt)）：

- **基准画布 393×720dp**，不是日历页的 393×800 —— 网页样稿的 800 里含 22dp 状态栏
  和 56dp 导航栏，而 App 的页面容器本来就不含这两块。`k` 仍取宽高比例的较小者。
- **日历页、今天页的蓝底直接铺闹钟页那张手绘天空**（`R.drawable.bg_alarm_page`：蓝色渐变 +
  一张 810×1800 的天空图 + 顶部压暗），不是自己画的渐变。三个页面的蓝必须是**同一块蓝**，
  否则在页签之间切来切去时底色会跳一下。课表页仍用自绘渐变（它满屏都是蓝底、
  上面还压着课块和光柱，铺图反而更花）；要统一的话改 `PersonaWeekView.onDraw` 那一行即可。
- **文字不跟 fontScale**（和 [PersonaDateHud] 同一个判断）：这两页是「一张画」，
  系统字号放大到 1.5 倍会把课块和行高撑破，而缩放后的画布没地方重排。
- **网格行高按剩余空间算**，不写死 46dp：节数由 `periods` 决定（"445" 是 13 行），
  写死就会溢出屏幕。行高 = 「铺满可用高度」的 **2/3**（`ROW_FILL = 0.67`），
  夹在 `[24, 56]dp`。长屏上铺满会顶到 56dp 上限，10 行的表稀得发空；
  取 2/3 之后行高落在 30~38dp，一门连堂课（两行）仍有 60~76dp，放得下三行课名 + 教室。
  **密度条跟着网格走**、不再钉在屏幕最下面 —— 网格矮了它就往上收，底下留一片天。
- **今天页的时间线可以上下滑**：整天的格子铺在一条裁剪过的视口里，打开时把**当前格
  摆在正中**（`PersonaTodayModel.initialScroll`），视口右边一条细滚动条表示还剩多少；
  拖动超过 `touchSlop` 才算滑动，松手时不会再误触弹卡。一屏露几行仍按屏高算（2–6 行），
  但内容不再被截断。
- **课块合并走 `WeekGrid`**（和默认外观同一份判定），否则两套外观会显示成两张课表。
- **点击靠一张 `hits` 表**：自绘页没有「每格一个 View」，命中区在 `onDraw` 里登记、
  在 `onTouchEvent` 里倒序查（后画的在上）。**画出来但忘了登记 = 点不动**，
  这一条踩过：周次键帽最早就是画了没登记。
- **弹卡动画的圆心用屏幕坐标矩形**（`onSlotClick` / `onMomentClick` 的 `pivot`），
  因为 `popInFrom(card, anchor)` 那套要一个真的 View 去量位置。

- **设置页的皮肤是按「颜色 / 形状」认的，不是按 id**（见 `PersonaSettingsSkin`）：
  底色等于 `card_bg` 的 → 深藏青卡；文字等于 `text_primary` / `text_secondary` /
  `accentColor` 的 → 白 / 浅蓝 / 青。两条坑：
  ① **不能就地 `setColor`** —— `GradientDrawable` 的 ConstantState 是资源缓存里共享的，
  改了会把缓存也染黑，之后关掉 P3 再进设置页，白卡就变黑卡了；所以要**换一个** drawable。
  ② **`SwitchMaterial` / `CheckBox` 都是 `Button`**（CompoundButton 继承 Button），
  判断顺序必须先开关后按钮，否则每个开关外面会多出一圈描边方块。

## 已知的坑

- **卡片形状：一律直角矩形。** P3 的容器是硬边矩形，不做缺角、不做斜切 ——
  斜体 / 斜切只留给标题字、日期条、`TODAY` 胶带这些「字与胶带」。
  网页样稿（`p3web/week-p3-v2`、`p3web/today-p3-v3`、`p3web/alarm-p3-v3`）已统一；
  新写的两页和弹卡皮肤都已经是矩形。[PersonaDialogBackground] /
  [PersonaButtonBackground] 里那套缺角实现只剩闹钟页在用，改那边时一起换。
- **唯一例外已取消**：v3 曾要求闹钟页「＋ 新建闹钟」按钮卡原样保留；**v4 已重新设计**
  （左 54px 青色 `＋` 块 + 深藏青长条，矩形）。旧例外不再适用，其它按钮/卡片仍走直角矩形。
- **主题色在 P3 模式下不生效**（P3 用固定的蓝 / 红 / 青），设置页色板还没置灰，
  用户可能会以为选颜色没反应。
- **皮肤是运行时刷的，不进布局**。换页、`recreate()`、`onResume` 都要重刷；
  漏一处就是「P3 自己变回原样，再开一次又好了」。
- 整页自绘的 View 在 `onDestroyView` 里必须放掉（见 `CalendarFragment.p3 = null`、
  `WeekFragment.p3 = null`、`TodayFragment.p3 = null`），
  否则 Fragment 实例会一直攥着一个没人用的 View。
