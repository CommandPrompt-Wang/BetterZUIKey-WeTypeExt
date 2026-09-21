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
    static final String EXTRA_SYNC_BACK = ExtConfig.KEY_SYNC_BACK;
    static final String EXTRA_STRICT = ExtConfig.KEY_STRICT;
    static final String EXTRA_SHIFT_PASSTHRU = ExtConfig.KEY_SHIFT_PASSTHRU;
    static final String EXTRA_SMART_NUMBER = ExtConfig.KEY_SMART_NUMBER;
    static final String EXTRA_FULLWIDTH = ExtConfig.KEY_FULLWIDTH_FEATURE;
    /** 反向通道：模块 → App 回传当前状态位。 */
    static final String EXTRA_ST_FULLWIDTH = "stateFullwidth";
    static final String EXTRA_EN_PUNCT = ExtConfig.KEY_EN_PUNCT;
    static final String EXTRA_HOTKEYS = ExtConfig.KEY_HOTKEYS;

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
                            intent.getBooleanExtra(EXTRA_SYNC_BACK, ExtConfig.DEF_SYNC_BACK),
                            intent.getBooleanExtra(EXTRA_STRICT, ExtConfig.DEF_STRICT),
                            intent.getBooleanExtra(EXTRA_SHIFT_PASSTHRU,
                                    ExtConfig.DEF_SHIFT_PASSTHRU),
                            intent.getBooleanExtra(EXTRA_SMART_NUMBER,
                                    ExtConfig.DEF_SMART_NUMBER),
                            intent.getBooleanExtra(EXTRA_FULLWIDTH,
                                    ExtConfig.DEF_FULLWIDTH_FEATURE),
                            intent.getBooleanExtra(EXTRA_EN_PUNCT, ExtConfig.DEF_EN_PUNCT),
                            intent.getStringExtra(EXTRA_HOTKEYS) == null ? ""
                                    : intent.getStringExtra(EXTRA_HOTKEYS));
                    ExtConfig.set(cfg);
                    ExtConfig.persist(c == null ? ctx : c, cfg);
                    Log.i(TAG, "config broadcast -> " + cfg);
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
        } catch (Throwable tr) {
            Log.w(TAG, "config receiver failed: " + tr);
        }
    }
}
