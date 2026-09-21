# BetterZUIKey-WeTypeExt

BetterZUIKey 的微信输入法（`com.tencent.wetype` / WeType）组件。

IME 类 `com.tencent.wetype.plugin.hld.WxHldService`，跑在 `:hld` 进程，且**只有这一个**
`InputMethodService` 子类（直接继承框架，中间没有别的层）。

## 目标（仅此两项）

1. **暴露 subtype**：微信输入法自己声明了 **0 个 `<subtype>`**，框架完全看不到它的中/英，
   所以系统与 [BetterZUIKey] 的语言切换对它无效。做法与 `BetterZUIKey-SogouOEMExt` 同构：
   在 IME 自身 uid / `:hld` 进程注入 `zh-CN` / `en-US`，再把框架 subtype 变化翻译成微信内部的中英切换。
2. **只在英文键盘关闭联想**（中文键盘不动；能做成独立开关更好）。

不做大范围标点、配对等搜狗 OEM 那类补强。

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

## 使用方法

装好后在 LSPosed 里启用本模块，作用域勾**微信输入法**（`com.tencent.wetype`），然后重启一次
微信输入法进程（不用重启系统）。打开本 App 可配置：

| 开关 | 默认 | 说明 |
|---|---|---|
| 英文键盘不显示联想/补全 | 开 | 英文键盘清空候选栏：打字过程中的补全（`hello → hellokitty`）与上屏一个词后的下一个词都不出现；中文键盘不受影响 |
| 跟随系统语言切换 | 开 | 把系统的 input subtype 变化翻成微信内部的中英切换 |
| 严格跟随系统语言 | 开 | 每次进入输入框都按系统语言对齐（关 = 只响应语言真正变化的时刻） |

改设置**即时生效**，不需要重启微信（模块收到广播后立刻换用新配置）。

## 状态

`0.1.0-probe`：两项能力均已实现并在真机上验证。

- 暴露 subtype ✅ —— 框架里可见 `zh-CN(中文)` / `en-US(English)`，翻译往返实测通过；
- 英文键盘去联想 ✅ —— 两种英文建议都去掉，中文不受影响；
- 设置页 + 配置通道 ✅（广播 + 目标进程落盘）；
- 严格模式（屏蔽微信自己切中英、只认框架）❌ 未做。

实现细节、真机验证日志与踩坑记录见 `local/static/IMPLEMENTATION.md`；静态分析见
`local/static/` 下的其它几篇。
