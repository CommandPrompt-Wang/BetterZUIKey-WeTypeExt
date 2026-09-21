package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 目标 2：<b>只在英文键盘关闭联想</b>（中文键盘不动）。
 *
 * <h3>下手点为什么是 {@code model.N.k2(boolean)}</h3>
 * 静态分析（local/static/ANALYSIS-2-association.md §0.5）结论：
 * <ul>
 *   <li>「这次是联想」由引擎回传：{@code CandidateList.type ∈ {1,3}} → {@code i0.isAssociate}；</li>
 *   <li>「当前是不是英文键盘」也能拿到：{@code N.n0() == 100}（{@code EnglishQwerty}），
 *       引擎侧同一信息是 {@code SessionConfig.keyboard_type = 2 (FULL_ENGLISH)}；</li>
 *   <li><b>所有联想展示都过同一个闸</b> {@code N.k2(boolean)}（`N.java:6015`，中英共用）
 *       —— 于是"按键盘语言收窄"就落在这里最省事。</li>
 * </ul>
 *
 * <p>⚠️ 与探针同样的纪律：{@code model.N} 是混淆名，只在<b>结构解析成功之后</b>才挂，
 * 且整段 try/catch；出问题最坏是"没生效"，不会影响输入法本体。
 *
 * <p>⚠️ 尚未真机验证的一点：{@code k2} 返回 false 后走的是 {@code i0.L3} 的 {@code O5(...)}
 * 普通候选分支 —— 要确认英文键盘上「打字过程中的候选」（词形/大小写/拼写纠正）没被一起砍掉。
 */
final class EnAssocGate {

    private static final String TAG = BridgeHook.TAG;

    private static volatile boolean sInstalled;
    /** 上一次的决策，用来只在状态翻转时打日志，避免刷屏。 */
    private static volatile int sLastDecision = -1;

    private EnAssocGate() {}

    /**
     * @param nClass 已解析出的微信输入法控制器类（{@code com.tencent.wetype.plugin.hld.model.N}）
     */
    static void install(XposedModule module, Class<?> nClass) {
        if (sInstalled || nClass == null) return;
        sInstalled = true;

        // ① 联想展示门：英文键盘一律不放行
        try {
            final Method k2 = nClass.getDeclaredMethod("k2", boolean.class);
            k2.setAccessible(true);
            module.hook(k2).intercept(chain -> {
                final Integer n0 = WeTypeInternals.keyboardValue();
                final Integer u0 = WeTypeInternals.currentValue();
                final boolean english = n0 != null
                        && n0.intValue() == WeTypeInternals.KB_ENGLISH_QWERTY;
                note(english, n0, u0);
                if (ExtConfig.get().enNoSuggest && english) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
            Log.i(TAG, "EnAssocGate: hooked k2(boolean)");
        } catch (Throwable tr) {
            Log.w(TAG, "EnAssocGate: k2 hook failed: " + tr);
        }

        // ② 键盘切换观测：切到哪个键盘值（读日志用，不影响行为）
        try {
            final Method k3 = nClass.getDeclaredMethod("k3", int.class, android.os.Bundle.class);
            k3.setAccessible(true);
            module.hook(k3).intercept(chain -> {
                final Object v = chain.getArg(0);
                Log.i(TAG, "switchKeyboard -> " + v
                        + " (zh=" + WeTypeInternals.isChineseKeyboard(asInt(v)) + ")");
                return chain.proceed();
            });
            Log.i(TAG, "EnAssocGate: hooked k3(int, Bundle)");
        } catch (Throwable tr) {
            Log.w(TAG, "EnAssocGate: k3 hook failed: " + tr);
        }
    }

    private static Integer asInt(Object o) {
        return o instanceof Integer ? (Integer) o : null;
    }

    /**
     * 只在「键盘值变化」时打一行，避免 k2 每次候选刷新都刷屏。
     *
     * <p>把 n0/u0 的实际值打出来是刻意的：如果切到英文键盘时这里记的还是别的值，
     * 说明「当前键盘」不该读 {@code n0()}，得换 {@code u0()} 或别的口。
     */
    private static void note(boolean english, Integer n0, Integer u0) {
        final int key = (english ? 1 : 0) * 100000 + (n0 == null ? -1 : n0);
        if (sLastDecision == key) return;
        sLastDecision = key;
        final boolean on = ExtConfig.get().enNoSuggest;
        Log.i(TAG, "EnAssocGate: k2 called n0=" + n0 + " u0=" + u0
                + " english=" + english + " gate=" + on
                + " -> " + (on && english ? "BLOCK" : "pass"));
    }
}
