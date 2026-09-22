package moe.lovefirefly.bzk.wetypeext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * 模块侧配置通道：在<b>目标进程</b>里运行时注册的显式广播接收器。
 *
 * <p>发起方必须是 App（它能看见微信），我们这边只收 —— 因为包可见性限制的是发起方。
 * 收不到也不会怎样：{@link ExtConfig} 里的编译期默认值兜底。
 *
 * <p>收到后做两件事：更新 {@link ExtConfig#get()}（<b>立刻</b>生效，不需要重启微信进程，
 * 因为各行为钩子都是"每次调用时读配置"而不是"装钩子时决定"），以及落盘到目标进程 prefs
 * （这样微信进程重启后不会悄悄回默认）。
 */
final class BroadcastConfig {

    private static final String TAG = BridgeHook.TAG;

    /** App 发过来的 action。 */
    static final String ACTION = "moe.lovefirefly.bzk.wetypeext.CONFIG";

    static final String EXTRA_EN_NO_SUGGEST = ExtConfig.KEY_EN_NO_SUGGEST;
    static final String EXTRA_TRANSLATE = ExtConfig.KEY_TRANSLATE;
    static final String EXTRA_TRANSLATE_ON_START = ExtConfig.KEY_TRANSLATE_ON_START;
    static final String EXTRA_STRICT = ExtConfig.KEY_STRICT;
    static final String EXTRA_SHIFT_PASSTHRU = ExtConfig.KEY_SHIFT_PASSTHRU;
    static final String EXTRA_SMART_NUMBER = ExtConfig.KEY_SMART_NUMBER;
    static final String EXTRA_FULLWIDTH = ExtConfig.KEY_FULLWIDTH_FEATURE;
    /** 反向通道：模块 → App 回传当前状态位。 */
    static final String EXTRA_ST_FULLWIDTH = "stateFullwidth";
    static final String EXTRA_ST_EN_PUNCT = "stateEnPunct";
    /** App 侧请求"把当前状态位回传一次"（设置页每次打开都要）。 */
    static final String EXTRA_WANT_STATE = "wantState";

    // ---- 开发期调试通道（只在本进程里生效，正式用途别用）----
    /** 切到某个面板（如 501 = 常用语/剪贴板、504 = 表情），切完打 View 树。 */
    static final String EXTRA_DBG_PANEL = "dbgPanel";
    /** 只打 View 树。 */
    static final String EXTRA_DBG_DUMP = "dbgDump";
    /** 请输入法把窗口显示出来（等价于热键里的补显示那一步）。 */
    static final String EXTRA_DBG_SHOW = "dbgShow";
    /** requestShowSelf 的 flags（0=implicit、1=forced、2=explicit），用来试哪种能压过宿主。 */
    static final String EXTRA_DBG_SHOW_FLAGS = "dbgShowFlags";
    /** 跑一个微信函数码（22=表情、25=语音…），跑完打 View 树。 */
    static final String EXTRA_DBG_FUNC = "dbgFunc";
    static final String EXTRA_EN_PUNCT = ExtConfig.KEY_EN_PUNCT_FEATURE;
    static final String EXTRA_AUTO_PAIR = ExtConfig.KEY_AUTO_PAIR;
    static final String EXTRA_CLOSE_SKIP = ExtConfig.KEY_CLOSE_SKIP;
    static final String EXTRA_SHIFT_FIX = ExtConfig.KEY_SHIFT_FIX;
    static final String EXTRA_SLASH_MODE = ExtConfig.KEY_SLASH_MODE;
    static final String EXTRA_HOTKEYS = ExtConfig.KEY_HOTKEYS;

    /** 设置页"正在录制快捷键"的专用 action（App → 模块）：录制期间热键临时不响应。 */
    static final String ACTION_RECORDING = "moe.lovefirefly.bzk.wetypeext.RECORDING";
    static final String EXTRA_RECORDING_FLAG = "recording";

    /** 回传状态用的 action（模块 → App，显式指定包名投递）。 */
    static final String ACTION_STATE = "moe.lovefirefly.bzk.wetypeext.STATE";
    static final String APP_PKG = "moe.lovefirefly.bzk.wetypeext";

    private static volatile boolean sStarted;

    private BroadcastConfig() {}

    static void start(final Context ctx) {
        if (sStarted || ctx == null) return;
        sStarted = true;

        // 1) 先用上次落盘的（微信进程重启后靠这条）
        final ExtConfig persisted = ExtConfig.loadPersisted(ctx);
        if (persisted != null) {
            ExtConfig.set(persisted);
            Log.i(TAG, "config restored: " + persisted);
        } else {
            Log.i(TAG, "config: no persisted value, using defaults " + ExtConfig.get());
        }

        // 2) 注册接收器（运行时注册，不要求微信清单声明任何东西）
        try {
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    // 缺 extra 时的兜底值必须与设置页默认值一致
                    final ExtConfig cfg = new ExtConfig(
                            intent.getBooleanExtra(EXTRA_EN_NO_SUGGEST, ExtConfig.DEF_EN_NO_SUGGEST),
                            intent.getBooleanExtra(EXTRA_TRANSLATE, ExtConfig.DEF_TRANSLATE),
                            intent.getBooleanExtra(EXTRA_TRANSLATE_ON_START,
                                    ExtConfig.DEF_TRANSLATE_ON_START),
                            intent.getBooleanExtra(EXTRA_STRICT, ExtConfig.DEF_STRICT),
                            intent.getBooleanExtra(EXTRA_SHIFT_PASSTHRU,
                                    ExtConfig.DEF_SHIFT_PASSTHRU),
                            intent.getBooleanExtra(EXTRA_SMART_NUMBER,
                                    ExtConfig.DEF_SMART_NUMBER),
                            intent.getBooleanExtra(EXTRA_FULLWIDTH,
                                    ExtConfig.DEF_FULLWIDTH_FEATURE),
                            intent.getBooleanExtra(EXTRA_EN_PUNCT, ExtConfig.DEF_EN_PUNCT_FEATURE),
                            intent.getBooleanExtra(EXTRA_AUTO_PAIR, ExtConfig.DEF_AUTO_PAIR),
                            intent.getBooleanExtra(EXTRA_CLOSE_SKIP, ExtConfig.DEF_CLOSE_SKIP),
                            intent.getBooleanExtra(EXTRA_SHIFT_FIX, ExtConfig.DEF_SHIFT_FIX),
                            intent.getIntExtra(EXTRA_SLASH_MODE, ExtConfig.DEF_SLASH_MODE),
                            intent.getStringExtra(EXTRA_HOTKEYS) == null ? ""
                                    : intent.getStringExtra(EXTRA_HOTKEYS));
                    ExtConfig.set(cfg);
                    ExtConfig.persist(c == null ? ctx : c, cfg);
                    Log.i(TAG, "config broadcast -> " + cfg);
                    // 设置页要当前状态位：回传一次（全角/半角、中文标点/英文标点）
                    if (intent.getBooleanExtra(EXTRA_WANT_STATE, false)) {
                        PunctState.mirrorNow(c == null ? ctx : c);
                    }
                    // 开发期调试：切面板 / 跑函数码 / 打 View 树（默认关，见 DEV_DEBUG_CHANNEL）
                    if (BridgeHook.DEV_DEBUG_CHANNEL) {
                        final int panel = intent.getIntExtra(EXTRA_DBG_PANEL, 0);
                        final boolean dump = intent.getBooleanExtra(EXTRA_DBG_DUMP, false);
                        if (panel != 0) {
                            Log.i(TAG, "dbg: switchKeyboard -> " + panel + " ok="
                                    + WeTypeInternals.switchKeyboard(panel));
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(() -> HideProbe.dumpTree("dbgPanel" + panel), 800L);
                        } else if (dump) {
                            HideProbe.dumpTree("dbgDump");
                        }
                        final int fn = intent.getIntExtra(EXTRA_DBG_FUNC, 0);
                        if (fn != 0) {
                            Log.i(TAG, "dbg: fireFunction(" + fn + ") ok="
                                    + WeTypeInternals.fireFunction(fn));
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(() -> HideProbe.dumpTree("dbgFunc" + fn), 800L);
                        }
                        if (intent.getBooleanExtra(EXTRA_DBG_SHOW, false)) {
                            final Object svc = ServiceProbe.service();
                            if (svc instanceof android.inputmethodservice.InputMethodService) {
                                final int flags = intent.getIntExtra(EXTRA_DBG_SHOW_FLAGS, 0);
                                ((android.inputmethodservice.InputMethodService) svc)
                                        .requestShowSelf(flags);
                                Log.i(TAG, "dbg: requestShowSelf(" + flags + ") done, shown="
                                        + ((android.inputmethodservice.InputMethodService) svc)
                                                .isInputViewShown());
                            }
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(() -> HideProbe.dumpTree("dbgShow"), 700L);
                        }
                    }
                }
            };
            final IntentFilter filter = new IntentFilter(ACTION);
            // targetSdk 34+ 起跨应用接收必须显式声明导出标志
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            Log.i(TAG, "config receiver registered");

            // 2b) 录制标记：设置页正在录快捷键时，热键要临时让路（否则一按组合键就把动作跑掉了）
            final BroadcastReceiver recReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    final boolean on = intent.getBooleanExtra(EXTRA_RECORDING_FLAG, false);
                    Hotkeys.setRecording(on);
                    Log.i(TAG, "recording -> " + on);
                }
            };
            final IntentFilter recFilter = new IntentFilter(ACTION_RECORDING);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(recReceiver, recFilter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(recReceiver, recFilter);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "config receiver failed: " + tr);
        }
    }
}
