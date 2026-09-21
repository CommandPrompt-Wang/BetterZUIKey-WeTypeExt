package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 严格模式：<b>语言只由系统框架决定</b>，微信自己切语言的动作一律拒绝。
 *
 * <h3>为什么不拦按键（搜狗踩过的坑）</h3>
 * 搜狗组件当年在按键层吞 Ctrl+Shift 来挡它自己切语言，结果"按住 Ctrl 就吞 Shift"，
 * 用户所有 {@code Ctrl+Shift+X} 组合键都组不出来（表现为"先按 Shift 再按 Ctrl 行、反过来不行"）。
 * 后来他们的修法是<b>按键层只吞 Ctrl+Space</b>，把 Ctrl+Shift 交给命令级守卫。
 *
 * <p>微信这里可以做得更干净 —— 因为微信<b>自己</b>已经把按键变成了语义动作：
 * <pre>
 *   hardware/d.n/o(keyCode,event) → keyCode 59/60 (SHIFT) → k(isKeyUp)
 *       k(): 当前是英文(K1) → g.c(主键盘) → N.r3(中文)        ← 绕过 key.d.O
 *            否则          → key.d.O(7, ActionKeyData(HARDKEYBOARD))
 *   工具栏/软键盘中英键       → key.d.O(7 或 8/27, …)
 * </pre>
 * 而且修饰键判断它也做完了（{@code if (zIsShiftPressed) isShiftKeyEventConsumed = true;}
 * —— Shift 参与过打字符后抬起不触发切换）。
 *
 * <p>⚠️ 注意上面有<b>两条不同代码</b>：中文→英文走 {@code key.d.O}，英文→中文走 {@code g.c→N.r3}。
 * 只在 {@code key.d.O} 上拦会出现"能切过去、切不回来"的怪状态。
 * 所以本类挂在两条路的<b>总漏斗</b> {@code N.m3(...)} 上（{@code n3/r3→k3→l3→q3→m3} 全部汇入）。
 *
 * <h3>拦什么、不拦什么</h3>
 * 只拦「<b>会改变语言</b>的那些键盘切换」：
 * <ul>
 *   <li>目标必须是语言键盘：{@code S1(目标)}（中文类）或 {@code 100}（英语）；</li>
 *   <li>且目标语言与当前语言不同。</li>
 * </ul>
 * ⇒ 符号面板（101..108，{@code S1} 为 false）、数字、手写、以及中文内部换输入方案
 * （九键 ↔ 26 键，语言没变）都<b>照常放行</b>，不会把用户的正常操作也堵掉。
 *
 * <h3>自己人放行</h3>
 * 翻译层也要切语言，不能被自己拦。{@code m3} 是在<b>主线程协程</b>里执行的，
 * 所以不能用 ThreadLocal（跨线程会丢）—— 改成"目标值 + 到期时间"的短时标记。
 */
final class SubtypeGuard {

    private static final String TAG = BridgeHook.TAG;

    /** 自己发起的切换，认这么多毫秒（够协程切到主线程再执行）。 */
    private static final long OURS_TTL_MS = 1500L;

    private static volatile boolean sInstalled;
    private static volatile int sOursTarget = Integer.MIN_VALUE;
    private static volatile long sOursUntil;

    private SubtypeGuard() {}

    /** 翻译层切语言<b>之前</b>调用：声明"接下来这个目标是我们自己发起的"。 */
    static void noteOurs(int targetKeyboardValue) {
        sOursTarget = targetKeyboardValue;
        sOursUntil = System.currentTimeMillis() + OURS_TTL_MS;
    }

    private static boolean isOurs(int target) {
        return target == sOursTarget && System.currentTimeMillis() < sOursUntil;
    }

    static void install(XposedModule module, Class<?> nClass) {
        if (sInstalled || nClass == null) return;
        sInstalled = true;
        try {
            // m3(int keyboardValue, Bundle, boolean hasAnimation, boolean toUpKeyboard, W source)
            final Method m3 = nClass.getDeclaredMethod("m3", int.class, android.os.Bundle.class,
                    boolean.class, boolean.class,
                    Class.forName("com.tencent.wetype.plugin.hld.model.W", false,
                            nClass.getClassLoader()));
            m3.setAccessible(true);
            module.hook(m3).intercept(chain -> {
                try {
                    final Object a = chain.getArg(0);
                    if (a instanceof Integer && shouldBlock(((Integer) a).intValue())) {
                        Log.i(TAG, "strict: blocked native language switch -> " + a);
                        return null;   // m3 是 void：不 proceed = 这次切换不发生
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "strict guard err: " + tr);
                }
                return chain.proceed();
            });
            Log.i(TAG, "SubtypeGuard: hooked N.m3");
        } catch (Throwable tr) {
            Log.w(TAG, "SubtypeGuard: install failed: " + tr);
        }
    }

    /**
     * 严格模式开 且 目标会改变语言 且 不是我们自己发起的 ⇒ 拦。
     */
    private static boolean shouldBlock(int target) {
        if (!ExtConfig.get().strictFrameworkOnly) return false;

        final Boolean targetIsZh = WeTypeInternals.isChineseKeyboard(target);
        final boolean targetIsEn = target == WeTypeInternals.KB_ENGLISH_QWERTY;
        if (targetIsZh == null) return false;
        // 非语言键盘（符号/数字/手写/表情…）：不拦
        if (!targetIsZh.booleanValue() && !targetIsEn) return false;

        final Integer cur = WeTypeInternals.keyboardValue();
        if (cur == null) return false;
        final Boolean curIsZh = WeTypeInternals.isChineseKeyboard(cur);
        final boolean curIsEn = cur.intValue() == WeTypeInternals.KB_ENGLISH_QWERTY;
        // 当前在面板上：说不清语言，别拦（避免卡在面板里出不来）
        if (!curIsZh.booleanValue() && !curIsEn) return false;

        // 语言没变（例如中文九键 ↔ 中文26键、切输入方案）：不拦
        if (curIsEn == targetIsEn) return false;

        if (isOurs(target)) {
            Log.i(TAG, "strict: allow our own switch -> " + target);
            return false;
        }
        return true;
    }
}
