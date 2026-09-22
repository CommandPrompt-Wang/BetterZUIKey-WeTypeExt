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
 *   <li><b>功能门</b>（{@link ExtConfig#fullwidthFeature} / {@link ExtConfig#enPunctFeature}）——
 *       在设置页里，决定"允不允许切"；关掉 = 恢复原生行为；</li>
 *   <li><b>状态位</b>（本类）—— 由热键随时切换（Shift+Space 全半角、Ctrl+. 中英标点），
 *       必须是"进程内即时 + 重启保留"。</li>
 * </ul>
 * 转换只在 <b>功能门开 && 状态为真</b> 时才做（对齐 gb 的两级模型）。
 *
 * <p>状态位落在微信进程自己的 prefs 里（App 物理上写不到），所以切完顺手发一条
 * {@link BroadcastConfig#ACTION_STATE} 给 App 显示"当前状态"。
 */
final class PunctState {

    private static final String TAG = BridgeHook.TAG;

    private static final String K_FULLWIDTH = "stateFullwidth";
    private static final String K_EN_PUNCT = "stateEnPunct";
    /** 上次套用过的「期望值」序号（见 {@link BroadcastConfig#EXTRA_WANT_SEQ}）。 */
    private static final String K_APPLIED_SEQ = "wantAppliedSeq";

    /** 全角态（Shift+Space 切）。 */
    private static volatile boolean sFullwidth;
    /** 英文标点态（Ctrl+. 切）；false = 中文标点。 */
    private static volatile boolean sEnPunct;

    private PunctState() {}

    static boolean fullwidth() {
        return sFullwidth;
    }

    /** 英文标点态（默认 false = 中文标点）。 */
    static boolean enPunct() {
        return sEnPunct;
    }

    static void load(Context ctx) {
        if (ctx == null) return;
        try {
            final android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(ExtConfig.STATE_PREFS, Context.MODE_PRIVATE);
            sFullwidth = sp.getBoolean(K_FULLWIDTH, false);
            sEnPunct = sp.getBoolean(K_EN_PUNCT, false);
            Log.i(TAG, "PunctState 恢复: fullwidth=" + sFullwidth + " enPunct=" + sEnPunct);
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

    static void setEnPunct(Context ctx, boolean on) {
        sEnPunct = on;
        try {
            if (ctx != null) {
                ctx.getSharedPreferences(ExtConfig.STATE_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(K_EN_PUNCT, on).apply();
            }
        } catch (Throwable tr) {
            Log.w(TAG, "PunctState persist enPunct failed: " + tr);
        }
        mirror(ctx);
    }

    /** 设置页每次打开会来要一次当前状态（模块侧收到广播时调）。 */
    static void mirrorNow(Context ctx) {
        mirror(ctx);
    }

    /**
     * 上次套用过的「期望值序号」。
     *
     * <p>App 每次进设置页都会把完整配置推一遍（含期望值），只有序号更大才该采纳，
     * 否则会把用户刚用热键切好的状态覆盖掉。
     */
    static long appliedSeq(Context ctx) {
        if (ctx == null) return 0L;
        try {
            return ctx.getSharedPreferences(ExtConfig.STATE_PREFS, Context.MODE_PRIVATE)
                    .getLong(K_APPLIED_SEQ, 0L);
        } catch (Throwable tr) {
            return 0L;
        }
    }

    static void markAppliedSeq(Context ctx, long seq) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences(ExtConfig.STATE_PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(K_APPLIED_SEQ, seq).apply();
        } catch (Throwable tr) {
            Log.w(TAG, "PunctState persist seq failed: " + tr);
        }
    }

    /** 把当前状态回传给设置页（显式指定包名，否则包可见性会把它丢掉）。 */
    private static void mirror(Context ctx) {
        if (ctx == null) return;
        try {
            final Intent i = new Intent(BroadcastConfig.ACTION_STATE);
            i.setPackage(BroadcastConfig.APP_PKG);
            i.putExtra(BroadcastConfig.EXTRA_ST_FULLWIDTH, sFullwidth);
            i.putExtra(BroadcastConfig.EXTRA_ST_EN_PUNCT, sEnPunct);
            ctx.sendBroadcast(i);
        } catch (Throwable tr) {
            Log.w(TAG, "PunctState mirror failed: " + tr);
        }
    }
}
