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

    static void send(Context ctx, SharedPreferences prefs) {
        try {
            final ExtConfig cfg = ExtConfig.load(prefs);
            final Intent i = new Intent(BroadcastConfig.ACTION);
            i.setPackage(BridgeHook.WXKB_PKG); // 显式指定包名，否则包可见性会把它丢掉
            i.putExtra(BroadcastConfig.EXTRA_EN_NO_SUGGEST, cfg.enNoSuggest);
            i.putExtra(BroadcastConfig.EXTRA_TRANSLATE, cfg.subtypeTranslate);
            i.putExtra(BroadcastConfig.EXTRA_TRANSLATE_ON_START, cfg.subtypeStrictOnStart);
            i.putExtra(BroadcastConfig.EXTRA_SYNC_BACK, cfg.syncBackToFramework);
            i.putExtra(BroadcastConfig.EXTRA_STRICT, cfg.strictFrameworkOnly);
            i.putExtra(BroadcastConfig.EXTRA_SHIFT_PASSTHRU, cfg.shiftPassThrough);
            i.putExtra(BroadcastConfig.EXTRA_SMART_NUMBER, cfg.smartNumber);
            ctx.sendBroadcast(i);
            Log.i(TAG, "config sent -> " + cfg);
        } catch (Throwable tr) {
            Log.w(TAG, "config send failed: " + tr);
        }
    }
}
