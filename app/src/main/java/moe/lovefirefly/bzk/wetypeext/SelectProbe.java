package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * TASK 5 的诊断探针：<b>只打日志，不改任何行为</b>。
 *
 * <h3>要回答的问题</h3>
 * 真机现象（2026-09-21，用户实测）与搜狗当年<b>正好相反</b>：
 * <ul>
 *   <li>WebView 网页输入框（DSH/Lexical）：Shift+方向键 <b>能选中</b> ✅</li>
 *   <li>原生 {@code android.widget.EditText}（MT 管理器 `bin.mt.plus.canary`）：<b>只移光标、不选中</b> ❌</li>
 * </ul>
 * 原生 EditText 的 Shift+方向键选区本来是宿主自己的活，正常轮不到输入法 ⇒
 * 要么宿主把键漏给了微信、微信消费掉；要么微信把 Shift 弄丢了再转发。
 *
 * <h3>两个探针</h3>
 * <ol>
 *   <li>{@code hardware/d.n(int, KeyEvent)} / {@code o(...)} —— 微信的物理键分发入口。
 *       打 keyCode、metaState（**有没有 SHIFT 位**）、以及返回值（**有没有被消费**）；
 *       顺带打当时候选条是否可见（候选条可见时方向键会走 {@code h() → P()} 那条候选条导航）。</li>
 *   <li>{@code WxHldService.onUpdateSelection(6×int)} —— <b>必须挂实例类自己的声明</b>：
 *       框架那份是死的（微信覆盖了且不调 super，`supercheck.py` 实证 ★DEAD）。
 *       打宿主报回来的选区是<b>塌的</b>（start==end）还是<b>区间</b>。</li>
 * </ol>
 *
 * ⚠️ 纪律：按键路径上**不改返回值**（gb §3.2.1 血泪：包装 `proceed()` 的返回值会让字母
 * 上不了屏）。这里虽然要在 `proceed()` 之后读返回值来打日志，但**原值原样返回**；
 * 一旦发现异常，把 {@link BridgeHook#DEV_SELECT_PROBE} 关掉即可恢复。
 */
final class SelectProbe {

    private static final String TAG = BridgeHook.TAG;

    private static volatile boolean sInstalled;

    private SelectProbe() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled || !BridgeHook.DEV_SELECT_PROBE) return;
        sInstalled = true;

        // ① 微信物理键分发
        try {
            final Class<?> d = Class.forName(
                    "com.tencent.wetype.plugin.hld.hardware.d", false, cl);
            hookKey(module, d, "n");   // 按下
            hookKey(module, d, "o");   // 抬起
            Log.i(TAG, "SelectProbe: hooked hardware.d.n / o");
        } catch (Throwable tr) {
            Log.w(TAG, "SelectProbe: hardware.d hook failed: " + tr);
        }

        // ② 选区变化（挂在实例类自己的声明上）
        try {
            final Class<?> svc = Class.forName(
                    "com.tencent.wetype.plugin.hld.WxHldService", false, cl);
            final Method m = svc.getDeclaredMethod("onUpdateSelection",
                    int.class, int.class, int.class, int.class, int.class, int.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                try {
                    final int oS = (Integer) chain.getArg(0);
                    final int oE = (Integer) chain.getArg(1);
                    final int nS = (Integer) chain.getArg(2);
                    final int nE = (Integer) chain.getArg(3);
                    Log.i(TAG, "sel old=[" + oS + "," + oE + "] new=[" + nS + "," + nE + "] "
                            + (nS != nE ? "区间(" + (nE - nS) + "字)" : "塌的")
                            + " candBar=" + WeTypeInternals.candidateBar());
                } catch (Throwable tr) {
                    Log.w(TAG, "SelectProbe sel err: " + tr);
                }
                return chain.proceed();
            });
            Log.i(TAG, "SelectProbe: hooked WxHldService.onUpdateSelection");
        } catch (Throwable tr) {
            Log.w(TAG, "SelectProbe: onUpdateSelection hook failed: " + tr);
        }
    }

    private static void hookKey(XposedModule module, Class<?> cls, String name) {
        try {
            final Method m = cls.getDeclaredMethod(name, int.class, KeyEvent.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                // 入口埋点：先记原始信息（不改任何参数）
                final int kc = (Integer) chain.getArg(0);
                final KeyEvent ev = chain.getArg(1) instanceof KeyEvent
                        ? (KeyEvent) chain.getArg(1) : null;
                final int meta = ev == null ? -1 : ev.getMetaState();
                final String before = "key " + name + " kc=" + kc
                        + "(" + KeyEvent.keyCodeToString(kc) + ")"
                        + " shift=" + ((meta & KeyEvent.META_SHIFT_ON) != 0)
                        + " ctrl=" + ((meta & KeyEvent.META_CTRL_ON) != 0)
                        + " action=" + (ev == null ? "?" : ev.getAction())
                        + " candBar=" + WeTypeInternals.candidateBar();
                final Object r = chain.proceed();   // 原样执行
                Log.i(TAG, before + " consumed=" + Boolean.TRUE.equals(r));
                return r;                            // 原值返回（绝不改写）
            });
        } catch (Throwable tr) {
            Log.w(TAG, "SelectProbe: hook " + name + " failed: " + tr);
        }
    }
}
