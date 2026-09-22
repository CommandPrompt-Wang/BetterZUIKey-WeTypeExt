# 原理与实现（PRINCIPLE）

README 只留结论，这里记**测到了什么**和**怎么解**。

---

## §1 分析对象与锚点

| 项 | 值 |
|---|---|
| 包名 | `com.tencent.wetype` |
| IME 类 | `com.tencent.wetype.plugin.hld.WxHldService`，跑在 `:hld` 进程 |
| `InputMethodService` 子类 | **只有这一个**（直接继承框架，中间没有别的层） |
| 基线版本 | `3.5.4`（`56201`） |

微信内部一律**按结构定位**（方法签名、字段类型），不硬编码混淆名。

| 想做什么 | 挂哪条 |
|---|---|
| 抓输入法服务实例、进输入框、语言变化 | 框架 `InputMethodService`：`setInputView` / `onStartInput` / `onCurrentInputMethodSubtypeChanged` |
| 读/切微信内部键盘语言 | `N.n0()` / `N.u0()` / `N.O0()` / `N.S1(int)` / `j1.K(false)` |
| 联想展示门 | `N.k2(boolean)` |
| 键盘状态变化（回写用） | `N.k3` |
| 候选进候选栏的边界 | `ImeCandidateView.A(ArrayList,int,boolean,boolean)` |
| 提交文本 | `RemoteInputConnection.commitText` / `setComposingText` 系 |
| 物理按键 | `WxHldService.onKeyDown` / `onKeyUp`（微信自家闸门之前） |

### 两个会静默失败的坑

1. **`onPackageReady` 阶段碰微信自己的类 ⇒ `:hld` 启动即崩。** 为拿单例去 `Field.get(null)` 会触发类初始化，
   而那时 Application 还没建、MMKV 没 init ⇒ NPE；**类初始化失败是粘性的**（实测连崩 14 次）。
   判据：`ActivityThread.currentApplication() != null` 才动手。
2. **在按键层吞 `Ctrl+Shift` ⇒ 所有 `Ctrl+Shift+X` 热键时灵时不灵**（搜狗组件踩过）。
   要拦「切语言」就拦微信**已经判定为切语言**的那个动作，不碰按键。

---

## §2 语言

### 测到的三件事

- 微信输入法声明 **0 个 subtype**，框架完全看不见它的中/英
- `setAdditionalInputMethodSubtypes` 有 **uid 闸门**：只有输入法自己的 uid 能改自己的 subtype 列表
  ⇒ 只能注入进它进程、用它的身份调
- 它自己切中英（物理键盘 `Ctrl+Shift`、工具栏语言键）**完全不告诉框架**：
  实测内部已经 `n0=100`，框架那边还是 `zh-CN` ⇒ 系统与 BZK 看到的语言和实际脱节

注入后（`ime list -a` 实测，没有 dummy subtype；别把排在微信前面那个 IME 的 `count=3` 看成微信的）：

```
InputMethodSubtype array: count=2
  #0 mSubtypeNameOverride=中文    mSubtypeLocale=zh-CN  mode=keyboard  asciiCapable=false
  #1 mSubtypeNameOverride=English mSubtypeLocale=en-US  mode=keyboard  asciiCapable=true
```

内部语义（都进了代码）：

| 口 | 中文态 | 英文态 | 用途 |
|---|---|---|---|
| `N.n0()` / `N.u0()` | 1 | 100 | 判当前语言 |
| `N.O0()`（语言键目标） | 100 | 1 | 「切到另一边」的目标 |
| `j1.K(false)` | 1 | **1（仍是 1）** | 记的是**中文侧键盘**，正好当「切回哪个中文」的依据 |
| `N.S1(int)` | true | false | 中文判定（0/1/5/6） |

### 怎么解

**正向**（框架 → 微信）：框架回调 subtype 变化，或 `onStartInput` 时自己读 ⇒ 查内部语言 ⇒ 不一致才切。

```
onSubtypeChanged en-US → translate: locale=en-US cur=1 -> 100 ok
onSubtypeChanged zh-CN → translate: locale=zh-CN cur=100 -> 1 ok
state[onStartInput] fw=en-US | n0=100 → 已是目标语言，跳过   ← 幂等
```

> `onStartInput` 那一步不能省：冷启动时框架**不会**主动把 subtype 告诉 IME，只能自己读。

**反向**（微信 → 框架）：观测 `N.k3`（键盘状态确实变了）⇒ `switchToNextInputMethod(true)` 在**本输入法内**轮转 subtype。
**`setCurrentInputMethodSubtype` 对 IME 自己不可用**（搜狗那边也实测过）。

回写会回调正向翻译；那条路的判据是「内部键盘是否已是目标语言」，此刻必然一致 ⇒ 跳过，不来回切。

**严格模式**：拦微信**已经判定为切语言**的那个动作，语言只由框架推。

「切回中文」优先用 `j1.K(false)`，兜底 `O0()`，再兜底 `ChineseT9=0`。
数字/符号/手写等面板（非中文且非 100）**不动**，避免打断标点输入。

---

## §3 英文键盘去联想

### 三条「语言开关」路线全部证伪

| 路线 | 结果 |
|---|---|
| `ime_enable_associating` = false（MMKV 实测 `01 00`） | 英文补全**照样出** |
| 引擎 `SessionConfig.enable_auto_most_likely` = false | 英文补全**照样出** |
| `N.k2()` 联想展示门 BLOCK | 英文补全**照样出**（它走普通候选那条路） |

### 决定性证据：候选清一色 `flag = 0x40`

探针在 `i0.L3` 打 flag/kind：

```
打 q    → #0 q       kind=28    | #1 question 0x40 | #3 quite 0x40 | #4 a 0x40 | #5 we 0x40
打 qqqh → #0 qqqh    kind=65535 | #1 qualities 0x40 | #2 quantities 0x40 | #3 quiet 0x40
表情候选 0x10002；符号 0x1
```

英文词典候选清一色 `flag = 0x40 = CANDIDATE_FLAG_FULL_ENGLISH(64)`。

### 怎么解

挂在**候选进 View 的边界** `ImeCandidateView.A(ArrayList,int,boolean,boolean)`
（`i0.N5` 遍历 `mICandidateDataListeners` 时调的），入口按语言清空列表即可，
`i0` 的内部状态（`mCandidateList`、提交、pending input）一概不碰。

⚠️ **坑**：先在 `i0.L3` 的第 6 参（`copyCandidateList`）上清 —— 日志明确 `cleared 10 candidates`
但**候选栏照旧**：那份不是最终渲染用的列表，清早了会被后续步骤盖掉。
**要挂在「数据进 View」的边界，不是中间的传递参数上。**

英文键盘两种都去掉（打字过程中的补全 + 上屏一个词后的下一个词），字直接上屏。

---

## §4 标点与配对

功能门（`fullwidthFeature` / `enPunctFeature`）关 = 恢复原生行为；状态位（`PunctState`）由热键随时切，
只在「门开 **且** 状态为真」时转换。

- **全角**：整段 ASCII 转全角，空格转 `U+3000`；**只动符号，不动字母数字**，免得拼音/英文被全角化
- **中英标点**：切到英文那一侧时中文标点落成 ASCII（`，`→`,`、`。`→`.` …）
- **原样输出斜杠**：微信原生把物理键盘的 `/` 和 `\` 都打成 `、`，挑一个键原样输出（三态）
- **配对**：总开关关掉时在提交层把微信自动补上的那半截拆掉；闭字符已在光标后侧时只移光标

**测到的一件事**：配对后的光标由微信自己的 `setSelection` 定 —— 它那次是**排队执行、而且比模块返回还晚**，
所以只能改写它那次请求的参数，不能自己再设一遍。

---

## §5 快捷键

**测到的两件事**：

- 面板类动作（表情 / 常用语 / 语音）必须**先显示窗口、再切面板**，否则用户什么都看不到
  （物理键盘态下微信把软键盘收着，只剩候选条）
- 弹窗录制期间必须**通知模块临时不响应热键**，否则刚按下的组合键会先把动作跑掉，键还被吞

**怎么解**：热键路由挂在 `WxHldService.onKeyDown`，即**微信自家闸门之前**。

---

## §6 配置通道

| 项 | 做法 |
|---|---|
| 传输 | **显式广播**：App `setPackage("com.tencent.wetype")` → 模块在 `:hld` 运行时注册的接收器 |
| 落盘 | 模块收到就写进目标进程 prefs |
| 时机 | 变更即刻发 + 每次进设置页补发一次 |

**测到的三件事**：广播是**一次性**的（微信进程一重启就回编译期默认值，开关会悄悄回默认）；
改设置那一刻微信进程**常常没在跑**；钩子**装的时候不看配置、调用的时候才看**，所以改设置能即时生效。

```
config broadcast -> enNoSuggest=false                       ← 即时生效
（kill :hld 后新进程）config restored: enNoSuggest=false     ← 重启不回默认
```

### 「期望值 + 序号」

**测到的一件事**：App 每次进设置页都会推一遍完整配置。长按切状态位若只写个「期望值」、无条件套用，
就会把用户刚用热键切好的状态覆盖掉。

**怎么解**：期望值配一个**序号**（时间戳）一起发，模块只在**序号比上次套用过的更大**时才采纳。

---

## §7 状态位镜像与「输入法未启用」

**测到的一件事**：状态位住在**微信进程**的 prefs 里，App 物理上读不到，设置页显示不出「当前是哪一档」。

**怎么解**：

- 切换的那一刻由模块发一条**显式指定包名**的广播回来（`ACTION_STATE`）
- 设置页每次进来带 `wantState` 要一次，避免「先按键、后开 App」拿到旧值
- 状态行支持长按应急切换（写期望值 + 序号，见 §6）；右下角悬浮键重推一次配置（进页面自动走一次）

**判断当前输入法**：读 `Settings.Secure.DEFAULT_INPUT_METHOD`（公开 secure setting，不需要权限）。
不是微信输入法时两行状态直接显示「输入法未启用」，也别再空转去等一个永远不会来的镜像。

---

## §8 运维要点（都踩过，别再踩）

1. **`pidof com.tencent.wetype` 匹配不到 `com.tencent.wetype:hld`**（进程名带后缀）。
   要 `ps -A -o PID,NAME | grep com.tencent.wetype | awk '{print $1}'` 再杀；
   且 `$(...)` 的展开要放在 `su -c '...'` **内部**，否则外层 Termux 看不到 root 进程。
2. **日志缓冲开大**：`su -c "logcat -G 8M"`。默认 256K 在真机上几秒就被冲掉，会误判成「模块没输出」。
3. 别 `am force-stop` 当前输入法（IMMS 会改写 `default_input_method`）；用 `kill -9`。
4. `logcat -s BZK-WeTypeExt:*` 有时抓不到（缓冲 / 时机），直接 `logcat -d | grep BZK-WeTypeExt` 更可靠。

---

## §9 已知边界与待办

- 微信内部锚点（`N` / `ImeCandidateView` 等）随版本可能变；只按结构定位、认不出就**降级放行**，不崩
- 只针对 `3.5.4`（`56201`）实测；换版本后建议重跑一遍探针确认锚点还在
