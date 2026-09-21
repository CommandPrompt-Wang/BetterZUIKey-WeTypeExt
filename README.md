# BetterZUIKey-WeTypeExt

BetterZUIKey 的微信输入法（`com.tencent.wetype` / WeType）组件。

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
| 4 | 中英标点（中文标点落成 ASCII，仅物理键盘） | 关 | ✅ build 19–20 |
| 3 | 全角模式（Shift+Space 切状态位，只动符号） | 关 | ✅ build 19–20 |
| 1 | 括号/引号自动配对**总开关**（关 = 只上屏你打的那个字符） | 开 | ✅ build 28 |
| 1 | 跳过已存在的闭合符号（只认微信刚补出来的那个） | 开 | ✅ build 28 |
| 6 | 物理键快捷键（语音输入 / 剪贴板…） | — | ⏳ 地基完成（注册表 + 可配置 + 录制 UI），动作本体未做 |
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
| 使用英文标点 | 关 |
| 启用全角模式（Shift+Space 切换） | 关 |
| 括号/引号自动配对 | 开 |
| 跳过已存在的闭合符号 | 开 |
| 快捷键（语音输入 / 剪贴板…） | 每项可录制，退格清除 |

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
- 物理键快捷键 ⏳ 只剩动作本体（语音输入 / 剪贴板入口待侦察）。

实现细节、真机验证日志与踩坑记录见 `local/static/IMPLEMENTATION.md` 与 `local/plan.md`
（后者带每个 TASK 的状态总表）；静态分析见 `local/static/` 下的其它几篇。
