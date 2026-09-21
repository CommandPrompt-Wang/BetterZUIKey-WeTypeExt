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

## 状态

`0.1.0-probe`：工程骨架 + 空桥接打点，功能未实现。
