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

    /**
     * 「刚在物理键盘上按过 Shift」认这么多毫秒。
     *
     * <p>物理切语言那条路必经 Shift 按键（{@code hardware/d.n/o(59/60)}），而<b>软键盘的中英键、
     * 工具栏中英键都不经过它</b>。所以用这个标记把「物理键盘发起的切换」和「软键盘发起的切换」分开。
     */
    private static final long PHYSICAL_TTL_MS = 800L;

    private static volatile boolean sInstalled;
    private static volatile int sOursTarget = Integer.MIN_VALUE;
    private static volatile long sOursUntil;
    /** 最近一次在物理键盘上看到 Shift 按键的时刻。 */
    private static volatile long sPhysicalUntil;

    private SubtypeGuard() {}

    /** 翻译层切语言<b>之前</b>调用：声明"接下来这个目标是我们自己发起的"。 */
    static void noteOurs(int targetKeyboardValue) {
        sOursTarget = targetKeyboardValue;
        sOursUntil = System.currentTimeMillis() + OURS_TTL_MS;
    }

    /**
     * 物理键盘上看到了 Shift 按键（按下 / 抬起都算）——由 {@link Hotkeys} 的按键钩子喂。
     *
     * <p>⚠️ 严格模式<b>只该拦物理键盘发起的切换</b>：拦在 {@code N.m3} 这个总漏斗上时看不见来源，
     * 一刀切会把微信软键盘自己的中英键也拦掉（用户报过：软键盘切不了语言）。
     */
    static void notePhysicalShift() {
        sPhysicalUntil = System.currentTimeMillis() + PHYSICAL_TTL_MS;
    }

    /** 这次切换是不是物理键盘那条路发起的。 */
    private static boolean fromPhysicalKeyboard() {
        return System.currentTimeMillis() < sPhysicalUntil;
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
        // 严格模式只拦**物理键盘**发起的切换：软键盘的中英键、工具栏中英键都不经过 Shift，
        // 一刀切会把它们也拦掉（用户报过：软键盘切不了语言）。
        if (fromPhysicalKeyboard()) return true;
        if (!viaK3()) {
            Log.i(TAG, "strict: blocked session restore -> " + target);
            return true;
        }
        Log.i(TAG, "strict: allow soft switch -> " + target);
        return false;
    }

    /**
     * 这次 {@code m3} 是不是「用户按出来的」。
     *
     * <h3>测到的事</h3>
     * 微信<b>每次开新输入会话都按偏好 {@code ime_current_keyboard} 恢复上次的键盘</b>
     * ——实测切到英文后，每进一个新文本框内部语言就被拽回中文，此时框架还是 {@code en-US}，
     * 回写随后再把系统一起带成中文（表现成「切了英文，进第二个文本框就变中文」，全程没碰物理键盘）。
     *
     * <h3>怎么区分</h3>
     * 打调用栈比对过，两条路分得很干净（{@code N.m3} 是终点，看它上面有没有 {@code N.k3}）：
     * <pre>
     *   软键盘中英键   key.d.M -> N.p3  -> N.k3 -> N.l3 -> N.q3 -> m3
     *   翻译层切语言   SubtypeTranslator -> N.k3 -> N.l3 -> N.q3 -> m3
     *   进硬件模式恢复 N.r3 -> N.n3 -> N.k3 -> N.l3 -> N.q3 -> m3
     *   ────────────────────────────────────────────────────────────
     *   会话开始恢复   WxHldService.g2 -> K2 -> N.C3 -> N.l3 -> N.q3 -> m3   ← 绕过 k3
     * </pre>
     * 所以「栈上没有 {@code N.k3}」＝ 不是用户按出来的，是微信自己按偏好恢复会话 ⇒ 拦。
     *
     * <p>不能只看「有没有按过键」（时间窗口那种）：开新文本框常常就在刚敲完键之后，
     * 窗口判据会把这种恢复放过去。
     */
    private static boolean viaK3() {
        try {
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                if ("com.tencent.wetype.plugin.hld.model.N".equals(e.getClassName())
                        && e.getMethodName().startsWith("k3")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
