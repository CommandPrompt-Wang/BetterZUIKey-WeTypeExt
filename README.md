<div align="center">

<h1>微信输入法增强</h1>
<img src="https://raw.githubusercontent.com/CommandPrompt-Wang/BetterZUIKey-WeTypeExt/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="微信输入法增强">

<p></p>
<p>简体中文</p>

[![Android](https://img.shields.io/badge/API-27%2B-green)](https://developer.android.com/about/versions/8.1) [![Xposed](https://img.shields.io/badge/Xposed-LSPosed-blue)](https://github.com/LSPosed/LSPosed) [![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/) [![License](https://img.shields.io/badge/License-GPL--3.0-orange)](https://github.com/CommandPrompt-Wang/BetterZUIKey-WeTypeExt/blob/main/LICENSE)

<p>把微信输入法的中/英语言暴露给系统框架，并给它的标点、配对、快捷键做一层可控的补强，
让 <a href="https://github.com/CommandPrompt-Wang/BetterZUIKey">BetterZUIKey</a> 那套输入法快捷键对微信输入法也能用</p>

</div>

微信输入法（`com.tencent.wetype` / WeType）的 LSPosed 组件，是
<a href="https://github.com/CommandPrompt-Wang/BetterZUIKey">BetterZUIKey</a> 的输入法适配之一。

IME 类 `com.tencent.wetype.plugin.hld.WxHldService`，跑在 `:hld` 进程，且**只有这一个**
`InputMethodService` 子类（直接继承框架，中间没有别的层）。

## 功能与状态

| # | 功能 | 默认 | 状态 |
|---|---|---|---|
| — | 暴露 subtype（`zh-CN` / `en-US`）+ 正向翻译（框架 → 微信内部） | 开 | ✅ 真机验证（往返） |
| — | 反向同步（微信内切语言 → 回写框架 subtype） | 开 | ✅ |
| — | 英文键盘不显示联想/补全（中文不受影响） | 开 | ✅ |
| — | 严格模式（只认系统语言，拒绝微信自切） | 关 | ✅ |
| 5 | Shift 键放行（修原生 Shift+方向键扩选） | 开 | ✅ build 16 |
| 1 | Shift 切换修复（Shift 参与过组合后松开不再误切语言） | 开 | ✅ build 28 |
| 2 | 智能编号（`1。`→`1.`、`1）`→`1)`，仅物理键盘） | 开 | ✅ build 17 |
| 4 | 中英文标点（**Ctrl+.** 切状态位，门默认开＝允许切） | 门开 / 状态=中文标点 | ✅ build 34 对齐 gb |
| 3 | 全角模式（**Shift+Space** 切状态位，门默认开） | 门开 / 状态=半角 | ✅ build 35 按搜狗口径（整段 ASCII + 空格） |
| 1 | 括号/引号自动配对**总开关**（关 = 只上屏你打的那个字符） | 开 | ✅ build 28 |
| 1 | 跳过已存在的闭合符号（只认微信刚补出来的那个） | 开 | ✅ build 28 |
| 6 | 物理键快捷键：语音输入 / 表情 / 剪贴板 | Alt+H / Alt+; / Alt+V | ⏳ 已实现（build 31），待真机复验 |
| 7 | 引号闭合翻转（开/闭引号状态机） | — | ⬜ 未开工，排最后 |

编号对应 `local/plan.md` 里的 TASK；那份还带每个 TASK 的侦察结论与踩坑记录（`local/` 不入库）。

## 使用方法

装好后在 LSPosed 里启用本模块，作用域勾**微信输入法**（`com.tencent.wetype`），然后重启一次
微信输入法进程（不用重启系统）。打开本 App 即可配置上面的每一项：

| 开关 | 默认 |
|---|---|
| 英文键盘不显示联想/补全 | 开 |
| 跟随系统语言切换 | 开 |
| 严格跟随系统语言 | 开 |
| 微信内切换后回写系统 | 开 |
| 只认系统语言（严格模式） | 关 |
| Shift 键放行（修物理键盘扩选） | 开 |
| Shift 切换修复 | 开 |
| 智能编号（数字后用半角标点） | 开 |
| 中英文标点（Ctrl+. 切换；行内显示 英文标点/中文标点） | 门开 |
| 全角模式（Shift+Space 切换；行内显示 全角/半角） | 门开 |
| 括号/引号自动配对 | 开 |
| 跳过已存在的闭合符号 | 开 |
| 快捷键：语音输入（开关） | Alt+H |
| 快捷键：表情面板 | Alt+; |
| 快捷键：剪贴板 | Alt+V |
| 快捷键：常用语 | Alt+Shift+V |
| 快捷键：全角/半角、中英标点 | Shift+Space、Ctrl+. |

改设置**即时生效**，不需要重启微信（模块收到广播后立刻换用新配置）。

## 分析对象与笔记

| 项 | 位置 |
|---|---|
| APK（3.5.4 / 56201） | `local/wxkb_1326_32.apk` |
| jadx 反汇编（13 174 个 `.java`） | `local/wxkb.disas/` |
| 可行性体检（第一轮） | `local/HANDOVER.md` |
| 静态分析（第一轮） | `local/static/ANALYSIS.md`、`assoc_ui_strings.md` |
| subtype 全链路（第二轮） | `local/static/subtype-switch-trace.md` |
| 英文键盘联想（第二轮） | `local/static/ANALYSIS-2-association.md` |

`local/` 不入库。

## 包名

- applicationId / namespace：`moe.lovefirefly.bzk.wetypeext`
- Xposed scope：`com.tencent.wetype`
- 入口：`moe.lovefirefly.bzk.wetypeext.BridgeHook`
- logcat 标签：`BZK-WeTypeExt`

## 构建

```bash
./gradlew --offline assembleDebug
```

产物：`app/build/outputs/apk/debug/BetterZUIKey-WeTypeExt-v<version>.apk`。

签名用仓库根目录的 `app-sign.keystore` + `keystore.properties`（都不入库，与其它 BZK 组件同一把），
debug 与 release 同签，便于设备上原地覆盖安装。

## 状态

`0.1.0-probe`：上表 ✅ 的项都已实现并在真机上验证（每项一个提交，最新 `fc9bea0`）。

- 暴露 subtype ✅ —— 框架里可见 `zh-CN(中文)` / `en-US(English)`，翻译往返实测通过；
- 英文键盘去联想 ✅ —— 两种英文建议都去掉，中文不受影响；
- 设置页 + 配置通道 ✅（广播 + 目标进程落盘，改完即时生效）；
- 严格模式 ✅、Shift 放行 ✅、Shift 切换修复 ✅；
- 智能编号 ✅、中英标点 ✅、全角模式 ✅；
- 括号/引号配对总开关 ✅、跳过已存在的闭合符号 ✅；
- 物理键快捷键 ✅ —— `Alt+H` 语音开关 / `Alt+;` 表情 / `Alt+V` 剪贴板 / `Alt+Shift+V` 常用语；
  入口和原生一致（函数码来自键盘布局 JSON），热键路由挂在 `WxHldService.onKeyDown`（微信自家闸门之前），
  并在动作前替微信补 `requestShowSelf(0)`；
- 快捷键设置：独立弹窗（文本框捕获按键 / Bksp 清除 / 恢复默认 / 校验与警告），文案与 UI 待明天再过一遍。

实现细节、真机验证日志与踩坑记录见 `local/static/IMPLEMENTATION.md` 与 `local/plan.md`
（后者带每个 TASK 的状态总表）；静态分析见 `local/static/` 下的其它几篇。
