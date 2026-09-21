package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.inputmethod.InputConnection;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 提交文本管线：挂在 IME 进程里的 {@code android.inputmethodservice.RemoteInputConnection} 上。
 *
 * <h3>为什么挂这里（而不是微信自己的 IC）</h3>
 * 微信自己确实有一个手写的 InputConnection（静态分析里的 {@code plugin/hld/view/q}），
 * 但它<b>不在框架 IC 的父类链上</b>（super 是 {@code java.lang.Object}），而且
 * {@code WxHldService} 也没覆盖 {@code getCurrentInputConnection()}。
 * IME 进程真正用来把文本交给宿主的是框架的 {@code RemoteInputConnection}（binder 代理），
 * 所以挂它既 <b>IME 无关</b>、又是所有提交的必经之路 —— 隔壁 gb 组件在 Gboard 上用的就是这一条。
 *
 * <h3>为什么必须枚举全部重载</h3>
 * Android 13+（API 33）给 {@code commitText}/{@code setComposingText} 加了带 {@code TextAttribute}
 * 的三参版；只挂两参版会漏。这里按方法名枚举全部重载，签名不写死。
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li>只在<b>真要改写</b>时才用新参数重放（{@code chain.proceed(args)}），否则原样 {@code proceed()}；</li>
 *   <li>所有判断都在 {@code proceed()} <b>之前</b>做完（提交后光标/文本就变了）；</li>
 *   <li>整段 try/catch，出问题最多是"没改写"，不影响输入。</li>
 * </ul>
 */
final class CommitHook {

    private static final String TAG = BridgeHook.TAG;

    private static volatile boolean sInstalled;
    /** 我们记的"上一次真正上屏的最后一个字符"，给 {@link TextNorm} 当兜底。 */
    private static volatile char sLastShown;
    private static volatile long sLastLog;

    private CommitHook() {}

    static char lastShown() {
        return sLastShown;
    }

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            final Class<?> cls = Class.forName(
                    "android.inputmethodservice.RemoteInputConnection", false, cl);
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                final String nm = m.getName();
                if (!"commitText".equals(nm) && !"setComposingText".equals(nm)) continue;
                if (hook(module, m, nm)) n++;
            }
            Log.i(TAG, "CommitHook: hooked " + n + " method(s)");
        } catch (Throwable tr) {
            Log.w(TAG, "CommitHook: install failed: " + tr);
        }
    }

    private static boolean hook(XposedModule module, Method m, final String name) {
        try {
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                final Object a0 = chain.getArg(0);
                if (!(a0 instanceof CharSequence)) return chain.proceed();

                String out = null;
                boolean handled = false;
                try {
                    final String raw = a0.toString();
                    if ("commitText".equals(name)) {
                        String text = raw;
                        final String norm = TextNorm.normalizeCommit(raw, chain.getThisObject(),
                                sLastShown);
                        if (norm != null) text = norm;

                        // TASK 1：closeSkip（跳过已存在的闭合符）——命中则这次提交整个不走
                        handled = PairGate.beforeCommit(text, chain.getThisObject());

                        // TASK 1：配对总开关关着时，把微信自动补上的那半截拆掉
                        if (!handled) {
                            final String stripped = PairGate.stripAutoClose(text);
                            if (stripped != null) text = stripped;
                            if (!text.equals(raw)) out = text;
                            if (out != null) {
                                final long now = System.currentTimeMillis();
                                if (now - sLastLog > 200) {
                                    sLastLog = now;
                                    Log.i(TAG, "norm: \"" + raw + "\" -> \"" + out + "\"");
                                }
                            }
                            remember(text);
                        }
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "CommitHook body err: " + tr);
                }

                if (handled) return Boolean.TRUE;   // closeSkip：闭字符已在光标右，不上屏
                if (out == null) return chain.proceed();
                final Object[] args = chain.getArgs().toArray();
                args[0] = out;
                return chain.proceed(args);
            });
            Log.i(TAG, "CommitHook: hooked " + name + "/" + m.getParameterCount());
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "CommitHook: hook " + name + "/" + m.getParameterCount()
                    + " failed: " + tr);
            return false;
        }
    }

    private static void remember(String shown) {
        if (shown != null && !shown.isEmpty()) sLastShown = shown.charAt(shown.length() - 1);
    }

    /** 供将来的层复用（例如需要判断"光标前是不是数字/字母"）。 */
    @SuppressWarnings("unused")
    private static CharSequence beforeCursor(Object ic, int n) {
        try {
            if (ic instanceof InputConnection) {
                return ((InputConnection) ic).getTextBeforeCursor(n, 0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
