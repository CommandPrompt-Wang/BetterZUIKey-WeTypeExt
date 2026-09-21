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
    static final String KEY_TRANSLATE = "subtypeTranslate";
    static final String KEY_TRANSLATE_ON_START = "subtypeStrictOnStart";

    // 默认值（界面、发送方、模块侧三处必须一致，否则会出现"界面显示开、实际是关"）
    static final boolean DEF_EN_NO_SUGGEST = true;
    static final boolean DEF_TRANSLATE = true;
    static final boolean DEF_TRANSLATE_ON_START = true;

    /** 英文键盘不显示候选/联想（两种都去：打字过程中的补全 + 上屏后的下一个词）。 */
    final boolean enNoSuggest;

    /** 让框架 subtype 驱动微信的中英切换。 */
    final boolean subtypeTranslate;

    /**
     * 每次进入输入框都按框架 subtype 对齐一次（"框架优先"）。
     * 关掉 = 只在框架 subtype 真正变化的时刻切。
     */
    final boolean subtypeStrictOnStart;

    ExtConfig(boolean enNoSuggest, boolean subtypeTranslate, boolean subtypeStrictOnStart) {
        this.enNoSuggest = enNoSuggest;
        this.subtypeTranslate = subtypeTranslate;
        this.subtypeStrictOnStart = subtypeStrictOnStart;
    }

    static ExtConfig defaults() {
        return new ExtConfig(DEF_EN_NO_SUGGEST, DEF_TRANSLATE, DEF_TRANSLATE_ON_START);
    }

    static ExtConfig load(SharedPreferences sp) {
        if (sp == null) return defaults();
        try {
            return new ExtConfig(
                    sp.getBoolean(KEY_EN_NO_SUGGEST, DEF_EN_NO_SUGGEST),
                    sp.getBoolean(KEY_TRANSLATE, DEF_TRANSLATE),
                    sp.getBoolean(KEY_TRANSLATE_ON_START, DEF_TRANSLATE_ON_START));
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
                    .apply();
        } catch (Throwable tr) {
            // 落盘失败只影响"重启后不回默认"，不影响本次生效
        }
    }

    String signature() {
        return "en" + (enNoSuggest ? 1 : 0)
                + "-tr" + (subtypeTranslate ? 1 : 0)
                + "-st" + (subtypeStrictOnStart ? 1 : 0);
    }

    @Override
    public String toString() {
        return "enNoSuggest=" + enNoSuggest
                + " translate=" + subtypeTranslate
                + " strictOnStart=" + subtypeStrictOnStart;
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
