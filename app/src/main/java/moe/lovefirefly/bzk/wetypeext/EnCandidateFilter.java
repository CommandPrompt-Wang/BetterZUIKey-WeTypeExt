package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;


import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 目标 2 的落点：<b>英文键盘下清空候选栏</b>（用户要的：不要任何联想/补全，字直接上屏）。
 *
 * <h3>为什么是"过滤候选"而不是"关引擎开关"</h3>
 * 实测（2026-09-21，日志见 local/static/ANALYSIS-2-association.md）：
 * <ul>
 *   <li>英文键盘敲 {@code q} → {@code question / quite / a / we}，敲 {@code qqqh} →
 *       {@code qualities / quantities / quiet} —— 这些补全候选<b>清一色带
 *       {@code flag = 0x40}（{@code Candidate.Flag.CANDIDATE_FLAG_FULL_ENGLISH = 64}）</b>，
 *       而表情候选是 {@code 0x10002}、符号是 {@code 0x1}；</li>
 *   <li>{@code ime_enable_associating} 当时已是 {@code false}、引擎侧
 *       {@code enable_auto_most_likely} 也已是 {@code false}，这些补全<b>照样出</b>；</li>
 *   <li>{@code N.k2()} 那个「联想展示门」也管不着它们（实测已 BLOCK，候选照出）。</li>
 * </ul>
 * ⇒ 语言层面的开关都不管用，只能在候选装配处按语言直接清。
 *
 * <h3>下手点</h3>
 * {@code ImeCandidateView.A(ArrayList, int, boolean, boolean)} —— 候选列表<b>送进候选栏的那个边界</b>
 * （{@code N5} 遍历 {@code mICandidateDataListeners} 时调的就是它）。挂这里清空入参列表：
 * 显示空了，而 {@code i0} 的内部状态（{@code mCandidateList}、提交逻辑、pending input）一概不碰，
 * 所以"字直接上屏"不受影响。
 *
 * <p>为什么不在 {@code i0.L3} 里清：实测那样清没有效果（日志显示 `cleared 10 candidates`
 * 但候选栏照旧）—— L3 的入参 ArrayList 不是最终渲染用的那份，清早了会被后续步骤盖掉。
 * 而且 {@code ImeCandidateView} 这个名字在 dex 里就是真名（未混淆，idx 可查）。
 *
 * <p>⚠️ 纪律同前：只在 Application 就绪后安装；整段 try/catch；出问题最多是"没过滤"。
 */
final class EnCandidateFilter {

    private static final String TAG = BridgeHook.TAG;

    private static final String CLS_VIEW = "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView";

    private static volatile boolean sInstalled;
    private static volatile long sLastLog;

    private EnCandidateFilter() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled || !BridgeHook.DEV_FILTER_EN_CAND) return;
        sInstalled = true;
        try {
            final Class<?> view = Class.forName(CLS_VIEW, false, cl);
            final Method target = view.getDeclaredMethod("A",
                    java.util.ArrayList.class, int.class, boolean.class, boolean.class);
            target.setAccessible(true);
            module.hook(target).intercept(chain -> {
                try {
                    apply(chain.getArg(0));
                } catch (Throwable tr) {
                    Log.w(TAG, "EnCandidateFilter err: " + tr);
                }
                return chain.proceed();
            });
            Log.i(TAG, "EnCandidateFilter: hooked ImeCandidateView.A(ArrayList,int,boolean,boolean)");
        } catch (Throwable tr) {
            Log.w(TAG, "EnCandidateFilter: install failed: " + tr);
        }
    }

    /** 英文键盘 → 清空这批发往候选栏的候选；其它键盘原样放行。 */
    private static void apply(Object listArg) {
        final Integer kb = WeTypeInternals.keyboardValue();
        if (kb == null || kb.intValue() != WeTypeInternals.KB_ENGLISH_QWERTY) return;
        if (!(listArg instanceof List)) return;
        final List<?> list = (List<?>) listArg;
        final int n = list.size();
        if (n == 0) return;
        try {
            list.clear();
        } catch (Throwable tr) {
            Log.w(TAG, "EnCandidateFilter: clear failed: " + tr);
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - sLastLog > 1000) {
            sLastLog = now;
            Log.i(TAG, "EnCandidateFilter: EN keyboard, cleared " + n + " candidates");
        }
    }
}
