package moe.lovefirefly.bzk.wetypeext;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * App 侧唯一的配置发送入口（设置页每次改动 / 每次进页面都发一条）。
 *
 * <p>为什么"每次进页面都发"：接收器只在微信进程活着时存在，改设置时微信没跑广播就丢了；
 * 下次进设置页补一条，等于自动补齐。
 */
final class ConfigSender {

    private static final String TAG = BridgeHook.TAG;

    private ConfigSender() {}

    static void send(Context ctx) {
        if (ctx == null) return;
        try {
            send(ctx, ctx.getSharedPreferences(ExtConfig.APP_PREFS, Context.MODE_PRIVATE));
        } catch (Throwable tr) {
            Log.w(TAG, "config send failed: " + tr);
        }
    }

    /** 告诉模块"设置页正在/不在录制快捷键"（录制期间热键让路）。 */
    static void sendRecording(Context ctx, boolean on) {
        if (ctx == null) return;
        try {
            final Intent i = new Intent(BroadcastConfig.ACTION_RECORDING);
            i.setPackage(BridgeHook.WXKB_PKG);
            i.putExtra(BroadcastConfig.EXTRA_RECORDING_FLAG, on);
            ctx.sendBroadcast(i);
            Log.i(TAG, "recording sent -> " + on);
        } catch (Throwable tr) {
            Log.w(TAG, "recording send failed: " + tr);
        }
    }

    static void send(Context ctx, SharedPreferences prefs) {
        send(ctx, prefs, true);
    }

    /**
     * @param wantState 是否顺手向模块要一次当前状态位。当前生效的输入法不是微信输入法时没必要要
     *                  ——模块不会生效，要了也不会回来（调用方见
     *                  {@code MainActivity#isTargetImeActive()}）。
     */
    static void send(Context ctx, SharedPreferences prefs, boolean wantState) {
        try {
            final ExtConfig cfg = ExtConfig.load(prefs);
            final Intent i = new Intent(BroadcastConfig.ACTION);
            i.setPackage(BridgeHook.WXKB_PKG); // 显式指定包名，否则包可见性会把它丢掉
            i.putExtra(BroadcastConfig.EXTRA_EN_NO_SUGGEST, cfg.enNoSuggest);
            i.putExtra(BroadcastConfig.EXTRA_TRANSLATE, cfg.subtypeTranslate);
            i.putExtra(BroadcastConfig.EXTRA_TRANSLATE_ON_START, cfg.subtypeStrictOnStart);
            i.putExtra(BroadcastConfig.EXTRA_STRICT, cfg.strictFrameworkOnly);
            i.putExtra(BroadcastConfig.EXTRA_SHIFT_PASSTHRU, cfg.shiftPassThrough);
            i.putExtra(BroadcastConfig.EXTRA_SMART_NUMBER, cfg.smartNumber);
            i.putExtra(BroadcastConfig.EXTRA_FULLWIDTH, cfg.fullwidthFeature);
            i.putExtra(BroadcastConfig.EXTRA_EN_PUNCT, cfg.enPunctFeature);
            if (wantState) {
                i.putExtra(BroadcastConfig.EXTRA_WANT_STATE, true);   // 顺手要一次状态位
            }
            i.putExtra(BroadcastConfig.EXTRA_AUTO_PAIR, cfg.autoPair);
            i.putExtra(BroadcastConfig.EXTRA_CLOSE_SKIP, cfg.closeSkip);
            i.putExtra(BroadcastConfig.EXTRA_SHIFT_FIX, cfg.shiftSwitchFix);
            i.putExtra(BroadcastConfig.EXTRA_SLASH_MODE, cfg.slashMode);
            i.putExtra(BroadcastConfig.EXTRA_HOTKEYS, cfg.hotkeys);
            // 状态位期望值 + 序号：跟着每条配置一起发，模块择机套用（当时没跑就等下一次）
            i.putExtra(BroadcastConfig.EXTRA_WANT_FULLWIDTH,
                    prefs.getBoolean(ExtConfig.KEY_WANT_FULLWIDTH, false));
            i.putExtra(BroadcastConfig.EXTRA_WANT_EN_PUNCT,
                    prefs.getBoolean(ExtConfig.KEY_WANT_EN_PUNCT, false));
            i.putExtra(BroadcastConfig.EXTRA_WANT_SEQ,
                    prefs.getLong(ExtConfig.KEY_WANT_SEQ, 0L));
            ctx.sendBroadcast(i);
            Log.i(TAG, "config sent -> " + cfg + " wantSeq="
                    + prefs.getLong(ExtConfig.KEY_WANT_SEQ, 0L));
        } catch (Throwable tr) {
            Log.w(TAG, "config send failed: " + tr);
        }
    }
}
