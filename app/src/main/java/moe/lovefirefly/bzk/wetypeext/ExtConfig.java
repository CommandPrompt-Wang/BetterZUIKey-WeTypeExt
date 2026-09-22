package moe.lovefirefly.bzk.wetypeext;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 模块配置（App 侧与模块侧<b>共用</b>同一份读取逻辑，默认值只有这一处）。
 *
 * <h3>通道</h3>
 * 显式广播（见 {@link BroadcastConfig} / {@link ConfigSender}）。不用 ContentProvider：
 * 微信输入法 targetSdk=35，受 Android 11+ 包可见性过滤，它看不见我们 App 的包；
 * 而可见性只限制发起方 —— 由我们（能看见微信）发显式广播给它就能通。
 * 这条通道在隔壁 gb 组件的 targetSdk=36 上已验证过。
 *
 * <h3>为什么还要落盘</h3>
 * 接收器是模块在目标进程里<b>运行时注册</b>的：目标进程不在时广播直接丢掉。
 * 所以收到广播时把值写进<b>目标进程自己的</b> prefs，模块启动时先读它当初始值，
 * 否则每次微信进程重启都会悄悄回到编译期默认。
 */
final class ExtConfig {

    /** App 侧写配置用的 prefs。 */
    static final String APP_PREFS = "wetypeext_config";

    /** 目标进程里落盘的那份（键与 App 侧一致，方便对照）。 */
    static final String STATE_PREFS = "wetypeext_state";

    static final String KEY_EN_NO_SUGGEST = "enNoSuggest";
    /**
     * 正向：让框架 subtype 驱动微信内部的中英切换。
     *
     * <p>⚠️ 2026-09-22 用户口径：<b>这是固定行为，不给开关</b> —— 「把 subtype 暴露给框架」
     * 本身就是无条件做的，那"响应框架消息"自然也没什么可配的（搜狗那边同样只有严格模式一个开关）。
     * 键名保留只为兼容旧 prefs。
     *
     * <p>恒为 true（见 {@link #subtypeTranslate}）；键名保留只为兼容旧 prefs。
     */
    static final String KEY_TRANSLATE = "subtypeTranslate";
    /**
     * 进输入框时按框架 subtype 对齐一次（"框架优先"）。
     *
     * <p>⚠️ 同上：<b>固定行为，不给开关</b>。冷启动时框架<b>不会</b>主动把 subtype 告诉 IME
     * （见 {@link ServiceProbe} 里那段注释），只能自己读；少了这一步，微信会以中文起来、
     * 而框架以为还是上次那门语言，两边直接脱节。
     *
     * <p>恒为 true（见 {@link #subtypeStrictOnStart}）；键名保留只为兼容旧 prefs。
     */
    static final String KEY_TRANSLATE_ON_START = "subtypeStrictOnStart";
    /** 已并入严格模式（{@code syncBack = !strict}），只为兼容旧的 App prefs 保留键名。 */
    static final String KEY_SYNC_BACK = "syncBackToFramework";
    /**
     * <b>语言部分唯一保留下来的开关</b>：严格模式（只认系统语言）。
     *
     * <p>回答的是唯一一个真正需要用户决定的问题：<b>微信能不能自己切语言</b>。
     * 其余两件事（暴露 subtype、响应框架消息）都是固定行为，见上面两条。
     */
    static final String KEY_STRICT = "strictFrameworkOnly";
    static final String KEY_SHIFT_PASSTHRU = "shiftPassThrough";
    static final String KEY_SMART_NUMBER = "smartNumber";
    static final String KEY_FULLWIDTH_FEATURE = "fullwidthFeature";
    static final String KEY_EN_PUNCT_FEATURE = "enPunctFeature";
    /** TASK 1 括号/引号配对总开关（见 {@link PairGate}）。 */
    static final String KEY_AUTO_PAIR = "autoPair";
    /** TASK 1 补充：跳过已存在的闭合符号。 */
    static final String KEY_CLOSE_SKIP = "closeSkip";
    /** Shift 切换修复（见 {@link ShiftFix}）。 */
    static final String KEY_SHIFT_FIX = "shiftSwitchFix";
    /** 「原样输出斜杠」：0=关（/ 与 \ 都出 、）1=按 / 出 / 2=按 \ 出 \ —— 对齐搜狗。 */
    static final String KEY_SLASH_MODE = "slashMode";
    /** 可配置快捷键（格式见 {@link HotkeyConfig}；空 = 用默认值）。 */
    static final String KEY_HOTKEYS = "hotkeys";

    /**
     * 「状态位期望值」+ 序号（设置页长按那一行切全半角/中英标点用）。
     *
     * <p>这几个<b>不是本模型的一部分</b> —— 它们描述「用户想要的状态位」，由模块择机套用
     * （状态位本身住在微信进程里，App 写不到）。期望值随每条配置一起发；序号用来防止
     * 把用户刚在输入法里用热键切好的状态覆盖掉，语义见 {@link BroadcastConfig#EXTRA_WANT_SEQ}。
     */
    static final String KEY_WANT_FULLWIDTH = "wantFullwidth";
    static final String KEY_WANT_EN_PUNCT = "wantEnPunct";
    static final String KEY_WANT_SEQ = "wantSeq";

    /**
     * 「更宽松的键盘识别」级别：1=只认字母（默认，交给微信自己判断）2=可打印字符 3=任何操作。
     *
     * <p>微信只拿 A–Z 当「你在用物理键盘」，所以按标点/方向键时软键盘不会收起。
     * 详见 {@link KbdDetect}。
     */
    static final String KEY_KBD_DETECT = "kbdDetect";

    // 默认值（界面、发送方、模块侧三处必须一致，否则会出现"界面显示开、实际是关"）
    static final boolean DEF_EN_NO_SUGGEST = true;
    /** 固定行为，不再可配（键名只为兼容旧 prefs）。 */
    static final boolean DEF_TRANSLATE = true;
    /** 固定行为，不再可配（键名只为兼容旧 prefs）。 */
    static final boolean DEF_TRANSLATE_ON_START = true;
    static final boolean DEF_SYNC_BACK = true;   // 仅旧配置兼容用，见 syncBackToFramework
    /** 唯一的语言开关默认值：严格模式默认<b>关</b>（开了之后微信自己的 Ctrl+Shift 就不好使了）。 */
    static final boolean DEF_STRICT = false;
    /** Shift 键放行（TASK 5）：见 {@link ShiftPassthrough}。默认开。 */
    static final boolean DEF_SHIFT_PASSTHRU = true;
    /** TASK 2 智能编号：数字后的 。/） 用半角。默认开。 */
    static final boolean DEF_SMART_NUMBER = true;
    /**
     * 全角模式的**功能门**（状态位另见 {@link PunctState}，由 Shift+Space 切换）。
     *
     * <p>对齐 gb：门<b>默认开</b>，状态位默认"半角"；关掉门 = 完全恢复原生（热键也不吞）。
     */
    static final boolean DEF_FULLWIDTH_FEATURE = true;
    /**
     * 中英文标点的**功能门**（状态位另见 {@link PunctState}，由 Ctrl+. 切换）。
     *
     * <p>对齐 gb：门<b>默认开</b>，状态位默认"中文标点"。
     */
    static final boolean DEF_EN_PUNCT_FEATURE = true;
    /**
     * TASK 1 括号/引号配对（微信原生行为）：开 = 自动补全 + 选中自动包裹；关 = 只上屏你打的那个字符。
     *
     * <p>默认开 —— 微信这套做得比我们当年在搜狗上写的还好，模块的职责只是给一个总闸
     * （实现在 {@link PairGate}：关掉时在 IC 层把微信自动补上的那半截拆掉）。
     */
    static final boolean DEF_AUTO_PAIR = true;
    /**
     * TASK 1 补充：跳过已存在的闭合符号（搜狗 OEM Ext 同名功能）。
     *
     * <p>只对"微信刚刚自动补出来的那个闭合符"生效，不做任何推导：光标右边正好是它 ⇒ 只把光标
     * 移过去，不再多插一个。默认开。
     */
    static final boolean DEF_CLOSE_SKIP = true;
    /**
     * Shift 切换修复：Shift 参与过组合（大写、符号、扩选）后松开，不再被误判成"Shift 单击切语言"。
     *
     * <p>微信原版只在 {@code hardware/d.p()}（"打字符"那条路）里置了
     * {@code isShiftKeyEventConsumed}，方向键等路径会漏 ⇒ 松开 Shift 就切了语言。默认开。
     */
    static final boolean DEF_SHIFT_FIX = true;
    /** 原样输出斜杠：默认<b>关</b>（保持微信原生：/ 与 \ 都出 、）。 */
    static final int DEF_SLASH_MODE = 0;
    /** 键盘识别级别默认 1 = 只认字母（与加这个开关之前的行为一致）。 */
    static final int DEF_KBD_DETECT = 1;

    /** 英文键盘不显示候选/联想（两种都去：打字过程中的补全 + 上屏后的下一个词）。 */
    final boolean enNoSuggest;

    /**
     * 让框架 subtype 驱动微信的中英切换。
     *
     * <p><b>恒为 true（固定行为，不再可配）</b>：把 subtype 暴露给框架本身就是无条件做的，
     * 那"响应框架子类型变化"也没有关掉的道理。
     */
    final boolean subtypeTranslate;

    /**
     * 每次进入输入框都按框架 subtype 对齐一次（"框架优先"）。
     *
     * <p><b>恒为 true（固定行为，不再可配）</b>：冷启动时框架不会主动告诉 IME 当前 subtype，
     * 只能自己读，否则微信会以中文起来而框架以为还是上次那门语言。
     */
    final boolean subtypeStrictOnStart;

    /**
     * 微信<b>内部</b>切换语言后，把框架 subtype 回写成一致（反向同步）。
     *
     * <p>⚠️ 2026-09-22 用户口径：<b>它和严格模式是一件事的两面，不再单独给开关</b> ——
     * 严格模式开着时微信自己切不了，也就没什么可回写；关着时微信内部怎么切，我们就回写、
     * 让系统/框架跟它保持一致。所以这里直接取 {@code !strictFrameworkOnly}。
     *
     * <p>动机：微信自己切中英（Ctrl+Shift / 工具栏中英键）<b>不告诉框架</b>，
     * 于是框架以为还是中文、系统与 BetterZUIKey 的语言状态就与真实语言脱节。
     * 回写之后就双向一致，严格模式关着也不会有"不同步"的问题。
     *
     * <p>⚠️ 本功能<b>不拦按键</b>：只在"键盘语言确实变了"之后动作（观测 {@code N.k3}），
     * 所以 Ctrl+Shift+P 这类组合键一个都不受影响（搜狗组件曾在按键层吞 Ctrl+Shift，
     * 导致所有 Ctrl+Shift+X 热键时灵时不灵，这里从设计上避开）。
     */
    final boolean syncBackToFramework;

    /** 严格模式：拒绝微信自己切语言，只认框架 subtype。语言部分唯一的开关。 */
    final boolean strictFrameworkOnly;

    /** Shift 键放行：微信不再独占 Shift，宿主恢复修饰键跟踪（原生 Shift+方向键扩选）。 */
    final boolean shiftPassThrough;

    /** TASK 2 智能编号：{@code 1。}→{@code 1.}、{@code 1）}→{@code 1)}。 */
    final boolean smartNumber;

    /** TASK 3 全角模式**功能开关**。转换条件是 {@code fullwidthFeature && PunctState.fullwidth()}。 */
    final boolean fullwidthFeature;

    /** 中英文标点：true = 允许用 Ctrl+. 在中文标点/英文标点之间切（状态位在 {@link PunctState}）。 */
    final boolean enPunctFeature;

    /** TASK 1 括号/引号配对：true = 微信原生（自动补全 + 选中包裹）。 */
    final boolean autoPair;

    /** TASK 1 补充：打闭字符时若光标右已是它（且是刚补出来的），只移光标。 */
    final boolean closeSkip;

    /** Shift 组合键之后松开 Shift 不切语言。 */
    final boolean shiftSwitchFix;

    /** 原样输出斜杠：0=关 1=/ 2=\ 。 */
    final int slashMode;

    /** 键盘识别级别：1=只认字母 2=可打印字符 3=任何操作（见 {@link KbdDetect}）。 */
    final int kbdDetect;

    /** 快捷键配置串（空 = 全默认）。 */
    final String hotkeys;

    ExtConfig(boolean enNoSuggest, boolean subtypeTranslate, boolean subtypeStrictOnStart,
            boolean strictFrameworkOnly,
            boolean shiftPassThrough, boolean smartNumber,
            boolean fullwidthFeature, boolean enPunctFeature, boolean autoPair,
            boolean closeSkip, boolean shiftSwitchFix, int slashMode, int kbdDetect,
            String hotkeys) {
        this.enNoSuggest = enNoSuggest;
        this.subtypeTranslate = subtypeTranslate;
        this.subtypeStrictOnStart = subtypeStrictOnStart;
        // 与严格模式互斥：严格=只认框架（没有可回写的）；非严格=微信内切换后回写框架
        this.syncBackToFramework = !strictFrameworkOnly;
        this.strictFrameworkOnly = strictFrameworkOnly;
        this.shiftPassThrough = shiftPassThrough;
        this.smartNumber = smartNumber;
        this.fullwidthFeature = fullwidthFeature;
        this.enPunctFeature = enPunctFeature;
        this.autoPair = autoPair;
        this.closeSkip = closeSkip;
        this.shiftSwitchFix = shiftSwitchFix;
        this.slashMode = slashMode;
        this.kbdDetect = kbdDetect;
        this.hotkeys = hotkeys == null ? "" : hotkeys;
    }

    static ExtConfig defaults() {
        return new ExtConfig(DEF_EN_NO_SUGGEST, DEF_TRANSLATE, DEF_TRANSLATE_ON_START,
                DEF_STRICT, DEF_SHIFT_PASSTHRU, DEF_SMART_NUMBER,
                DEF_FULLWIDTH_FEATURE, DEF_EN_PUNCT_FEATURE, DEF_AUTO_PAIR,
                DEF_CLOSE_SKIP, DEF_SHIFT_FIX, DEF_SLASH_MODE, DEF_KBD_DETECT, "");
    }

    static ExtConfig load(SharedPreferences sp) {
        if (sp == null) return defaults();
        try {
            return new ExtConfig(
                    sp.getBoolean(KEY_EN_NO_SUGGEST, DEF_EN_NO_SUGGEST),
                    // 前两个是固定行为，不再从 prefs 读（旧键留着只为兼容，见 KEY_TRANSLATE）
                    true,                                   // subtypeTranslate
                    true,                                   // subtypeStrictOnStart
                    sp.getBoolean(KEY_STRICT, DEF_STRICT),
                    sp.getBoolean(KEY_SHIFT_PASSTHRU, DEF_SHIFT_PASSTHRU),
                    sp.getBoolean(KEY_SMART_NUMBER, DEF_SMART_NUMBER),
                    sp.getBoolean(KEY_FULLWIDTH_FEATURE, DEF_FULLWIDTH_FEATURE),
                    sp.getBoolean(KEY_EN_PUNCT_FEATURE, DEF_EN_PUNCT_FEATURE),
                    sp.contains(KEY_AUTO_PAIR) ? sp.getBoolean(KEY_AUTO_PAIR, DEF_AUTO_PAIR)
                            : DEF_AUTO_PAIR,
                    sp.getBoolean(KEY_CLOSE_SKIP, DEF_CLOSE_SKIP),
                    sp.getBoolean(KEY_SHIFT_FIX, DEF_SHIFT_FIX),
                    sp.getInt(KEY_SLASH_MODE, DEF_SLASH_MODE),
                    sp.getInt(KEY_KBD_DETECT, DEF_KBD_DETECT),
                    sp.getString(KEY_HOTKEYS, ""));
        } catch (Throwable tr) {
            return defaults();
        }
    }

    /** 目标进程里落盘的那份；从没收到过广播时返回 {@code null}（让调用方决定用不用默认值）。 */
    static ExtConfig loadPersisted(Context ctx) {
        if (ctx == null) return null;
        try {
            final SharedPreferences sp =
                    ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            if (!sp.contains(KEY_EN_NO_SUGGEST)) return null;
            return load(sp);
        } catch (Throwable tr) {
            return null;
        }
    }

    static void persist(Context ctx, ExtConfig c) {
        if (ctx == null || c == null) return;
        try {
            ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_EN_NO_SUGGEST, c.enNoSuggest)
                    .putBoolean(KEY_TRANSLATE, c.subtypeTranslate)
                    .putBoolean(KEY_TRANSLATE_ON_START, c.subtypeStrictOnStart)
                    .putBoolean(KEY_STRICT, c.strictFrameworkOnly)
                    .putBoolean(KEY_SHIFT_PASSTHRU, c.shiftPassThrough)
                    .putBoolean(KEY_SMART_NUMBER, c.smartNumber)
                    .putBoolean(KEY_FULLWIDTH_FEATURE, c.fullwidthFeature)
                    .putBoolean(KEY_EN_PUNCT_FEATURE, c.enPunctFeature)
                    .putBoolean(KEY_AUTO_PAIR, c.autoPair)
                    .putBoolean(KEY_CLOSE_SKIP, c.closeSkip)
                    .putBoolean(KEY_SHIFT_FIX, c.shiftSwitchFix)
                    .putInt(KEY_SLASH_MODE, c.slashMode)
                    .putInt(KEY_KBD_DETECT, c.kbdDetect)
                    .putString(KEY_HOTKEYS, c.hotkeys)
                    .apply();
        } catch (Throwable tr) {
            // 落盘失败只影响"重启后不回默认"，不影响本次生效
        }
    }

    String signature() {
        return "en" + (enNoSuggest ? 1 : 0)
                + "-tr" + (subtypeTranslate ? 1 : 0)
                + "-st" + (subtypeStrictOnStart ? 1 : 0)
                + "-sb" + (syncBackToFramework ? 1 : 0)
                + "-sk" + (strictFrameworkOnly ? 1 : 0)
                + "-sp" + (shiftPassThrough ? 1 : 0)
                + "-sn" + (smartNumber ? 1 : 0)
                + "-fw" + (fullwidthFeature ? 1 : 0)
                + "-ep" + (enPunctFeature ? 1 : 0)
                + "-ap" + (autoPair ? 1 : 0)
                + "-cs" + (closeSkip ? 1 : 0)
                + "-sf" + (shiftSwitchFix ? 1 : 0)
                + "-sl" + slashMode + "-kd" + kbdDetect
                + "-hk" + hotkeys.hashCode();
    }

    @Override
    public String toString() {
        return "enNoSuggest=" + enNoSuggest
                + " translate=" + subtypeTranslate
                + " strictOnStart=" + subtypeStrictOnStart
                + " syncBack=" + syncBackToFramework
                + " strict=" + strictFrameworkOnly
                + " shiftPass=" + shiftPassThrough
                + " smartNumber=" + smartNumber
                + " fullwidthFeature=" + fullwidthFeature + " enPunctFeature=" + enPunctFeature
                + " autoPair=" + autoPair + " closeSkip=" + closeSkip
                + " shiftFix=" + shiftSwitchFix + " slashMode=" + slashMode + " kbdDetect=" + kbdDetect
                + " hotkeys=" + hotkeys;
    }

    // ------------------------------------------------------------------ 模块侧当前值

    private static volatile ExtConfig sCurrent = defaults();

    static ExtConfig get() {
        return sCurrent;
    }

    static void set(ExtConfig c) {
        if (c != null) sCurrent = c;
    }
}
