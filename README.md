<div align="center">

<h1>微信输入法增强</h1>
<img src="https://raw.githubusercontent.com/CommandPrompt-Wang/BetterZUIKey-WeTypeExt/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="微信输入法增强">

<p></p>
<p>简体中文</p>

[![Android](https://img.shields.io/badge/API-27%2B-green)](https://developer.android.com/about/versions/8.1) [![Xposed](https://img.shields.io/badge/Xposed-LSPosed-blue)](https://github.com/LSPosed/LSPosed) [![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/) [![License](https://img.shields.io/badge/License-GPL--3.0-orange)](https://github.com/CommandPrompt-Wang/BetterZUIKey-WeTypeExt/blob/main/LICENSE)

<p>把微信输入法的中/英语言暴露给系统框架，并给它的标点、配对、快捷键做一层可控的补强，
让 <a href="https://github.com/CommandPrompt-Wang/BetterZUIKey">BetterZUIKey</a> 那套输入法快捷键对微信输入法也能用</p>

<p>针对微信输入法 <code>3.5.4</code>（<code>56201</code>）</p>
</div>

> 君ノ声ガ　聞コエルヨ。
>
> 你的声音，我能听到呀。

**声明**：本仓库主要部分均为 AIGC，可能有缺陷，欢迎审查和 PR。

这是一个 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) 的扩展组件，建议与本体搭配使用以达到最佳效果。

<p><sub>应用图标基于微信输入法自带图标二次创作；流萤像素画来源未知，如有侵权请联系删除</sub></p>

---

## 开发动机

微信输入法本身功能已经比较全面，但有三处让它和系统框架配合不起来：

### 其一：语言攥在自己手里

它声明了 **0 个 subtype**，框架完全看不见它的中/英，于是系统与 BZK 里那套「切换到下一个输入法语言」对它**完全无效**。

换言之，不论是系统请求还是内部切换，两边都无从得知。

~~真的有输入法完全支持这个接口吗？甚至亲儿子 Gboard 适配也不完全~~

### 其二：英文键盘的联想关不掉

英文联想无论场景都强制开启，没有有效的关闭项。在 Termux 等场景下候选框抢占编辑非常恼人。

很有意思的是，我们在遗留代码里面找到了**疑似被弃用的开关**。由于不确定是否能以字面意思理解，我们不多做评价。

### 其三：物理键盘的几处缺口

- 中文状态下，`+`、`=` 和 `{}` 以全角输出：`＋`、`＝`、`{}`。
  - 很有意思的是 `@#%&*` 又是正常半角，说明（在 so 层？）是有映射规则的。难道做微信输入法的人键盘上没有 `-={}` 吗？
- 它独占物理键盘的 `Shift` 按下事件，导致原生输入框以为 Shift 没有按下，`Shift+方向键` 的扩选退化成普通移动
- 它对按下 `Shift` 切换语言的判定过于宽松，以至于用 `Shift` 打大写字母都可能意外切换语言
- 在物理键盘下的 `/` 和 `\` 一律打成 `、`
- 它有剪贴板历史、语音输入、常用语等功能，但就是不提供快捷键通道
  - 或许平板用户不配使用？

本模块注入微信输入法进程，补出 subtype、接管语言的翻译与回写、在**候选进 View 的边界**按语言过滤，并给标点、配对、快捷键加一层可控开关。

## 功能特性

- **严格模式**：拒绝微信内置的语言切换快捷键，只接收框架的信息；**关掉时**微信内切换会回写框架，两边不脱节
  - 该功能需要配合 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) v1.6.x 以上使用
- **暴露 subtype**：向系统框架暴露出 `zh-CN` / `en-US`，让系统与 BZK 看得见它的中英状态
- **英文键盘不显示候选**：打字过程中的补全与上屏后的下一个词都去掉，中文完全不受影响
- **标点管线**：把「中英标点」与「全半角」拆成两个状态位，各有独立开关
- **配对**：括号/引号自动配对总开关 + 跳过已存在的闭合符号
- **更宽松的键盘识别**：让更多物理键盘操作（而不只是字母）都会收起软键盘，三档可调
- **快捷键**：注册表式可配置（语音 / 表情 / 剪贴板 / 常用语 / 全角 / 中英标点），设置页里可以随时修改
- **配置热生效**：广播推送 + 目标进程持久化，配置修改**无需重启输入法**

## 功能一览

| 功能 | 解释 | 默认值 |
|---|---|---|
| 只响应系统框架语言切换消息 | 拒绝微信自行切换语言，只接收框架切换信号 | 关 ¹ |
| 原样输出斜杠 | 微信把 `/` 和 `\` 都输出成 `、`，该配置可以选择想保持原样的字符 | 关 |
| 全角模式 | 允许在全角/半角之间切换　快捷键：`Shift+Space` | 开（状态默认**半角**） |
| 中英文标点 | 允许中文态在中英标点之间切换　快捷键：`Ctrl+.` | 开（状态默认**中文标点**） |
| 智能编号 | 数字后面的 `。` `）` 自动切换到半角，方便输入 `1.` `2)` | 开 |
| 关闭英文候选 | 英文输入时不显示候选栏和预测 | 开 |
| 引号/括号自动补全 | 关闭后输入引号、括号时不再自动关闭 | 开 |
| 跳过已存在的闭合符号 | 光标后侧已有闭字符时，只把光标移过去而不再多插一个 | 开 |
| Shift 选区修复 | 修复部分文本框 `Shift+方向键` 无法选中文字的问题 | 开 |
| Shift 切换修复 | 修复按住 `Shift` 输入大写字母时意外切换语言的问题 | 开 |
| 更宽松的键盘识别 | 让更多物理键盘操作（而不只是字母）都会收起软键盘（三档：字母 / 可打印字符 / 任何操作） | 字母 |

¹ 未检测到 BetterZUIKey 时此项**禁用**（严格模式要靠 BZK 接管语言切换，强行开启会导致没有有效的切换手段）。

## 工作原理

模块在微信输入法进程里做四件事：**补 subtype** / **翻译与回写语言** / **按语言过滤候选** / **改写提交内容**。

> dex 级逆向、实测数据与踩坑记录全部整理在 **[PRINCIPLE.md](https://github.com/CommandPrompt-Wang/BetterZUIKey-WeTypeExt/blob/main/PRINCIPLE.md)**

```
模块 App（MainActivity）
    ↕ 显式广播（ConfigSender · 变更即刻发 + 每次进设置页补发一次）
    ↕ 状态位反向广播（模块 → App，用于设置页显示「当前状态」）
微信输入法进程（BridgeHook · com.tencent.wetype:hld）
    ├── SubtypeInjector    用 IME 自身 uid 补出 zh-CN / en-US
    ├── SubtypeTranslator  正向：框架 subtype → 微信内部中英切换
    ├── SubtypeSync        反向：微信内切中英 → 回写框架 subtype
    ├── SubtypeGuard       严格模式：拒绝微信自己切语言
    ├── EnCandidateFilter  候选进 View 的边界：英文键盘清空候选栏
    │                 ├ EnAssocGate       英文键盘不放行联想展示门
    │                 └ SessionConfigGate 英文会话关掉自动联想
    ├── TextNorm / PairGate 标点与配对的提交层改写
    ├── Hotkeys            物理键热键路由（挂在微信自家闸门之前）
    └── Banner             热键反馈（不用 Toast：会被通知设置拦掉）
```

## 模块安装

0. **前置条件**：已安装 [LSPosed](https://github.com/LSPosed/LSPosed) + 微信输入法
   （严格模式另需 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey)，未安装时该开关禁用）

| 项 | 值 |
| --- | --- |
| 包名 | `com.tencent.wetype` |
| **IME 类** | `com.tencent.wetype.plugin.hld.WxHldService` |
| **基线版本** | 微信输入法 `3.5.4`（`56201`） |

> 微信内部我们的挂钩只按**结构**（方法签名、字段类型）定位、不硬编码混淆名，所以换版本时更可能是功能降级失效而不是崩溃。

1. 在 [Releases](https://github.com/CommandPrompt-Wang/BetterZUIKey-WeTypeExt/releases) 下载 APK 并安装
2. LSPosed Manager 里启用模块，检测微信输入法是否被选中
3. 杀掉微信输入法进程，然后进入文本编辑等待输入法重启
4. 打开模块 App 即可配置；严格模式需要先在 BZK 里给微信输入法启用 `framework` 模式

## 开发构建

```bash
git clone git@github.com:CommandPrompt-Wang/BetterZUIKey-WeTypeExt.git
cd BetterZUIKey-WeTypeExt
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/BetterZUIKey-WeTypeExt-v<versionName>.apk
```

需要 JDK 17 + Android SDK 37（`compileSdk 37` / `minSdk 27` / `targetSdk 36`），以及 [libxposed](https://github.com/libxposed/api) API 101。

- 请自备 `app-sign.keystore` 和 `keystore.properties`（与 BZK 同一套签名，都不入库）

## 日志

```bash
adb shell logcat -s BZK-WeTypeExt
```

```
subtype injected com.tencent.wetype (+2 subtypes, enabled=2)
config broadcast -> enNoSuggest=true ... strict=false
translate[...] locale=en-US cur=1 -> 100 ok
syncBack: 已一致 (want=en fw=en-US)
hotkey fullwidth -> fullwidth=true
strict: blocked language switch
```

> 开发期探针（`DEV_*`）默认**关**，排查时按需在对应类里打开。

## ⚠️ 免责声明

这是一个 LSPosed 模块，直接 hook 输入法的语言切换、按键与提交链路。使用前请：

- 理解每个开关的含义再操作
- 不当配置可能导致**切不到某个语言**、标点 / 配对行为异常，或严格模式下**无法切换语言**（此时先关掉严格模式）
- 只针对微信输入法 `3.5.4`（`56201`）实测；其它版本可以尝试，但不保证效果

开发者不承担因使用本模块造成的输入异常、数据丢失或设备故障的任何责任。

## 项目结构

```
app/src/main/java/moe/lovefirefly/bzk/wetypeext/
├── BridgeHook.java          # Xposed 入口：按包名过滤 + 后台线程装各 hook
├── SubtypeInjector.java     # 用 IME 自身 uid 补出 zh-CN / en-US
├── SubtypeTranslator.java   # 正向：框架 subtype → 微信内部中英切换
├── SubtypeSync.java         # 反向：微信内切中英 → 回写框架 subtype
├── SubtypeGuard.java        # 严格模式：拒绝微信自己切语言
├── ServiceProbe.java        # 输入法服务挂点：抓实例 / onStartInput / subtype 变化
├── WeTypeInternals.java     # 反射读微信内部键盘状态与切换（含结构兜底）
├── EnCandidateFilter.java   # 英文键盘在候选进 View 的边界清空候选栏
├── EnAssocGate.java         # 英文键盘不放行联想展示门
├── SessionConfigGate.java   # 英文会话关掉自动联想
├── TextNorm.java            # 标点归一：语义层 + 宽度层
├── PunctState.java          # 两个状态位（全角 / 中英标点），存在目标进程 + 镜像回 App
├── PairGate.java            # 括号/引号配对总开关 + 跳过已存在的闭字符
├── CommitHook.java          # 提交层挂点
├── ShiftPassthrough.java    # Shift 键放行（修原生 Shift+方向键扩选）
├── ShiftFix.java            # Shift 组合之后松开不再误切语言
├── Hotkeys.java             # 物理键热键路由（挂在微信自家闸门之前）
├── HotkeyAction.java        # 快捷键注册表：一处登记所有可配置动作
├── HotkeyConfig.java        # 组合键的序列化 / 解析 / 键名显示
├── Banner.java              # 输入法窗口上的一行提示（Toast 会被通知设置拦掉）
├── ExtConfig.java           # 配置结构 / 默认值 / 落盘
├── BroadcastConfig.java     # 配置通道：运行时注册的显式广播接收器
├── ConfigSender.java        # App 侧唯一发送入口
├── MainActivity.java        # 设置页：所有开关 + 快捷键区 + 「当前状态」显示
└── *Probe.java / IcTrace.java / InputSource.java   # 开发期探针与诊断，默认关
```

## 📄 许可证

GPL-3.0 © 2025–2026 [CommandPrompt-Wang](https://github.com/CommandPrompt-Wang)
