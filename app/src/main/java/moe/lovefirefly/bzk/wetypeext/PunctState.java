package moe.lovefirefly.bzk.wetypeext;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 运行期状态位（住在<b>微信进程</b>里，热键切换、跨微信重启保留）。
 *
 * <h3>为什么状态位不能放在 App 侧配置里</h3>
 * 两级模型（对齐 gb/搜狗）：
 * <ul>
 *   <li><b>功能开关</b>（{@link ExtConfig#fullwidthFeature}）—— 在设置页里，决定这个功能用不用；</li>
 *   <li><b>状态位</b>（本类）—— 由热键（Shift+Space）随时切换，必须是"进程内即时 + 重启保留"。</li>
 * </ul>
 * 转换只在 <b>功能开 && 状态为真</b> 时才做。
 *
 * <p>状态位落在微信进程自己的 prefs 里（App 物理上写不到），所以切完顺手发一条
 * {@link BroadcastConfig#ACTION_STATE} 给 App 显示"当前状态"。
 */
final class PunctState {

    private static final String TAG = BridgeHook.TAG;

    private static final String K_FULLWIDTH = "stateFullwidth";

    /** 全角态。 */
    private static volatile boolean sFullwidth;

    private PunctState() {}

    static boolean fullwidth() {
        return sFullwidth;
    }

    static void load(Context ctx) {
        if (ctx == null) return;
        try {
            sFullwidth = ctx.getSharedPreferences(ExtConfig.STATE_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(K_FULLWIDTH, false);
            Log.i(TAG, "PunctState 恢复: fullwidth=" + sFullwidth);
        } catch (Throwable tr) {
            Log.w(TAG, "PunctState load failed: " + tr);
        }
    }

    static void setFullwidth(Context ctx, boolean on) {
        sFullwidth = on;
        try {
            if (ctx != null) {
                ctx.getSharedPreferences(ExtConfig.STATE_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(K_FULLWIDTH, on).apply();
            }
        } catch (Throwable tr) {
            Log.w(TAG, "PunctState persist failed: " + tr);
        }
        mirror(ctx);
    }

    /** 把当前状态回传给设置页（显式指定包名，否则包可见性会把它丢掉）。 */
    private static void mirror(Context ctx) {
        if (ctx == null) return;
        try {
            final Intent i = new Intent(BroadcastConfig.ACTION_STATE);
            i.setPackage(BroadcastConfig.APP_PKG);
            i.putExtra(BroadcastConfig.EXTRA_ST_FULLWIDTH, sFullwidth);
            ctx.sendBroadcast(i);
        } catch (Throwable tr) {
            Log.w(TAG, "PunctState mirror failed: " + tr);
        }
    }
}
