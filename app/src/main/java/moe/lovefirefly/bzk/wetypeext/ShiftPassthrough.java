package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * TASK 5 的第一刀：<b>让 Shift 键事件放行</b>（微信自己的逻辑照跑，只是不再独占它）。
 *
 * <h3>病灶（2026-09-21 真机定位）</h3>
 * 现象与搜狗当年<b>正好相反</b>：WebView 宿主 Shift+方向键正常，原生 EditText
 * （MT 管理器 `bin.mt.plus.canary`）反而只移光标不选中。
 *
 * <p>链路：
 * <pre>
 * 敲几个字母 → hardware/d.b(A–Z) 判定"你在用物理键盘" → g.l(true) 进硬件模式
 *   → imeProxy 换成 hardware/a → WxHldService.onKeyDown → hardware/d.n → Shift 走 k()
 *   → k() **恒返回 true**：Shift 按下/抬起全被微信吃掉
 *   → 原生 EditText 判定 Shift 靠 MetaKeyKeyListener 的**按键跟踪状态**（不是事件 meta）
 *     ⇒ 它以为没按 Shift ⇒ 方向键退化成普通移动
 *   → WebView/Chromium 直接读事件 metaState（shift=true）⇒ 所以它反而正常
 * </pre>
 *
 * <h3>修法</h3>
 * 挂 {@code hardware/d.k(boolean)}：先 {@code proceed()}（微信的 Shift 单击切语言等逻辑照常执行），
 * 然后把返回值改成 {@code false} —— <b>逻辑不变，只是不再消费事件</b>，宿主因此恢复修饰键跟踪，
 * 原生扩选自己就回来了。
 *
 * <p>这也是为什么不需要照搬搜狗的 {@code ShiftArrowRepair}（那套是给"宿主把
 * {@code Selection.modify("extend")} 走坏"用的）：我们的病灶在输入法侧，改一行就够。
 *
 * <p>⚠️ 副作用评估：宿主现在会看到 Shift 的按下/抬起（含单独 Shift 单击）。微信的切语言逻辑
 * 在 {@code k()} 内部已经执行完，因此 Shift 单击/ Ctrl+Shift 切语言不受影响；
 * 字母输入也不受影响（字符键仍由微信消费并 commit）。若哪天发现某宿主对单独的 Shift 有反应，
 * 把设置里的开关关掉即可。
 */
final class ShiftPassthrough {

    private static final String TAG = BridgeHook.TAG;

    private static volatile boolean sInstalled;
    private static volatile long sLastLog;

    private ShiftPassthrough() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            final Class<?> d = Class.forName(
                    "com.tencent.wetype.plugin.hld.hardware.d", false, cl);
            final Method k = d.getDeclaredMethod("k", boolean.class);
            k.setAccessible(true);
            module.hook(k).intercept(chain -> {
                // 先跑微信自己的逻辑（Shift 单击切语言、硬件模式判定都在里面）
                final Object original = chain.proceed();
                if (!ExtConfig.get().shiftPassThrough) return original;
                final long now = System.currentTimeMillis();
                if (now - sLastLog > 500) {
                    sLastLog = now;
                    Log.i(TAG, "shiftPassthrough: 原返回=" + original + " → 放行(false)");
                }
                return Boolean.FALSE;   // 不消费：宿主才能看到 Shift，修饰键跟踪才正确
            });
            Log.i(TAG, "ShiftPassthrough: hooked hardware.d.k");
        } catch (Throwable tr) {
            Log.w(TAG, "ShiftPassthrough: install failed: " + tr);
        }
    }
}
