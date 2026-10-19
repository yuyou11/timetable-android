# v2.0.1 改名 Time Machine、八套配色与色板（补上开关轨道的颜色）

三件事：**换名字**、**配色从两套变八套并做成色板**、**修好「换了色但有些控件不跟着变」**。

版本号从 1.13.x 直接跳到 **2.0.x**：名字换了，主视觉也从两套配色变成八套，
对用户来说这一版已经不太像同一个 App 了 —— 继续按 1.14 往下排，
反而会让人以为只是个小修补。功能上是 1.13.7 的超集，没有删掉任何东西。

**2.0.1 是 2.0.0 的补丁**：2.0.0 里开关的圆钮已经跟着主题变了，
但轨道（槽）还是 Material 默认的淡紫 —— 详见第三节。

## 一、应用名：Time Machine

桌面图标、最近任务、系统「通知管理」里的名字都是这三个词。

这是这个 App 的第三次改名（时间规划表 → 时光机 → Time Machine），
**包名 `com.yxz.timetable` 始终没动**。改包名在系统眼里就是另一个应用：
装上去变两个图标，原来的课表、闹钟、配色设置一个都带不过去。

顺带改的两处：一键设系统闹钟写进时钟应用的备注「起床 · Time Machine」；
权限引导里「设置 → 通知管理 → Time Machine → 此刻该做什么」这条路径。

## 二、配色：两套 → 八套，设置页变成色板

| 色 | 日间主色 | 夜间主色 |
|---|---|---|
| 蓝（默认） | `#1565C0` | `#6FB3F2` |
| 青 | `#00695C` | `#4DB6AC` |
| 绿 | `#2E7D32` | `#81C784` |
| **紫** | `#6A1B9A` | `#CE93D8` |
| 玫红 | `#AD1457` | `#F48FB1` |
| 橙 | `#E65100` | `#FFB74D` |
| 红 | `#C62828` | `#EF9A9A` |
| 靛蓝 | `#283593` | `#9FA8DA` |

选色规则两条：**明度压在 25%~45%**（太亮的压在浅灰底上发飘，白字也压不住）、
**饱和度不拉满**（荧光色盯久了累）。夜间一律调亮并略降饱和 —— 日间那批压在
`#101215` 的深底上等于看不见。

设置页原来是八行文字（「蓝色」「绿色」…），选之前得先在脑子里把名字翻译成颜色。
现在是一个**色板**：两行四列，每格一个圆点 + 色名，点哪个变哪个，选中那格
多一圈描边。色块用的是「这套主题的主色」而不是「当前主题的主色」，
所以八个格子是八个颜色（这点很容易写错 —— 一写 `?attr/accentColor` 就全同色了）。

## 三、修掉「换了色，开关的槽还是老颜色」

这是用户截图反馈的问题：选了橙色主题，**开关的圆钮是橙的、槽却是淡紫的**。

一开始我以为是「AppCompat 那套 colorControlActivated 没设」，设完圆钮确实变橙了，
**但槽还是淡紫** —— 因为 M3 的开关压根不走那两个槽。翻 Material 1.12 的资源才看清：

```
Base.Widget.Material3.CompoundButton.Switch
    thumbTint = @color/m3_switch_thumb_tint
    trackTint = @color/m3_switch_track_tint

m3_switch_thumb_tint:  state_checked → ?attr/colorPrimary          ← 圆钮
m3_switch_track_tint:  state_checked → ?attr/colorPrimaryContainer ← 轨道（槽）
```

截图里取色验证过：轨道是 `#EADDFF`，正是 Material 默认的 `colorPrimaryContainer`；
圆钮是 `#E1592x`，正是我们设的 `accent_orange`。**一个开关里两个颜色来自两个槽**，
所以才出现「一半跟着换、一半不换」的怪现象 —— 不是渲染 bug，是漏了一个槽。

各控件实际认的槽：

| 控件 | 认的主题槽位 |
|---|---|
| M3 开关的**圆钮**（打开时） | `colorPrimary` |
| M3 开关的**轨道**（打开时） | `colorPrimaryContainer` |
| 老式 CheckBox / RadioButton / SwitchCompat | `android:colorControlActivated` / `colorAccent` |
| TonalButton（「导入数据」这类浅底按钮） | `colorSecondaryContainer` |
| BottomNavigationView 的选中胶囊 | `colorSecondaryContainer` |
| 对话框按钮、输入框光标 | `colorPrimary` |

现在每套主题把这 **12 个槽**一次性设满：

```
accentColor / accentSoftColor
colorPrimary / colorOnPrimary / colorPrimaryContainer / colorOnPrimaryContainer
colorSecondary / colorOnSecondary
colorSecondaryContainer / colorOnSecondaryContainer
colorAccent
android:colorControlActivated
```

并且 `AppThemeTest` 里加了一条断言：**槽位数量必须 ≥ 主题数量** ——
以后再添配色时漏设一个，测试会直接红，而不是等用户发现某个控件颜色不对。

> 2.0.0 已经修了圆钮那一半（`colorPrimary` 一直在设），轨道这一半是 2.0.1 补上的。

## 四、顺带

- 设置页右侧那些箭头图标原来写死黑色，深色模式下压在 `#1B1E24` 的卡片上
  几乎看不见 —— 现在默认染成次要文字色（课表页的翻页按钮自己设了 tint，
  仍然是强调色，不受影响）。

## 升级说明

| | |
|---|---|
| versionCode | 32 |
| versionName | 2.0.1 |
| minSdk / targetSdk | 26 / 34 |
| 安装包 | `dist/Time-Machine-v2.0.1.apk` |
| SHA-256 | `78A1EA1DFEDAEE0380CAB649E59B357C5D2E3F2179BC5E5CD9A654F5A169C92E` |

签名与上一版相同，可直接覆盖安装，数据（课表 / 闹钟 / 设置）保留；
原来选的蓝 / 绿主题继续有效（存的是 `blue` / `green` 这两个 key）。

## 验证

- `testReleaseUnitTest`：**397** 条全过（新增 1 条：八个染色槽位必须每套主题都设满）；
- `lintVitalRelease`：0 error；`assembleRelease`（R8 + 资源压缩）通过；
- 反查打包产物：`resources.arsc` 里只有 `Time Machine`，旧名字都不在了；
  `classes.dex` 里能看到「紫色 / 玫红 / 靛蓝」和色板提示语。

真机重点：设置 → 外观 → 点色板里的紫/橙/红，看这几处是否**同时**变色 ——
标题与按钮文字、开关的轨道、单选圈、浅底按钮的底色、底部导航的选中胶囊、
课表格子里的课程名。深色模式下再看一遍（夜间色是另一套）。
