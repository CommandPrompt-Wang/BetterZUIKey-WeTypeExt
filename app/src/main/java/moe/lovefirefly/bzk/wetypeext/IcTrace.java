package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * TASK 1 侦察探针：把 IME 侧每一次 {@code InputConnection} 调用<b>原样打出来</b>。
 *
 * <h3>要回答的问题</h3>
 * 括号/引号配对到底发生在哪一层？手头三种可能（{@code HANDOVER.md} §4）：
 * <ol>
 *   <li>native 引擎（{@code libwxhld.so}）—— 提交时已经把成对的两个字拼好了；</li>
 *   <li>微信根本没有自动配对 —— 物理键上只提交一个字符；</li>
 *   <li>走 {@code sendKeyEvent} 而不是 {@code commitText}。</li>
 * </ol>
 * 判据很直接：按一个 {@code (} 键，看框架 IC 上出现的是
 * {@code commitText("（）",2)}（成对 ⇒ 方案 1，我们在本层就能拆）、
 * 还是 {@code commitText("（",1)}（单个 ⇒ 方案 2）、
 * 还是根本没有 {@code commitText}（⇒ 方案 3）。
 *
 * <p>顺带把 {@code setSelection} / {@code deleteSurroundingText} 一起打出来 ——
 * "跳过已有的右括号" 这种行为如果存在，只能靠这两个调用实现。
 *
 * <h3>纪律</h3>
 * 只打日志，<b>不碰参数、不碰返回值</b>（gb §3.2.1 的教训：包装 {@code proceed()} 的返回值
 * 会让字母上不了屏）。关掉 {@link BridgeHook#DEV_IC_TRACE} 即可整体停用。
 */
final class IcTrace {

    private static final String TAG = BridgeHook.TAG;

    /** 要打的 IC 方法名（按名字枚举，签名不写死：API 33/34 都有新重载）。 */
    private static final String[] NAMES = {
            "commitText",
            "setComposingText",
            "setComposingRegion",
            "setSelection",
            "deleteSurroundingText",
            "deleteSurroundingTextInCodePoints",
            "finishComposingText",
            "sendKeyEvent",
            "performEditorAction",
            "replaceText",
    };

    private static volatile boolean sInstalled;

    private IcTrace() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled || !BridgeHook.DEV_IC_TRACE) return;
        sInstalled = true;
        try {
            final Class<?> cls = Class.forName(
                    "android.inputmethodservice.RemoteInputConnection", false, cl);
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!wanted(m.getName())) continue;
                if (hook(module, m)) n++;
            }
            Log.i(TAG, "IcTrace: hooked " + n + " method(s)");
        } catch (Throwable tr) {
            Log.w(TAG, "IcTrace: install failed: " + tr);
        }
    }

    private static boolean wanted(String name) {
        for (String n : NAMES) {
            if (n.equals(name)) return true;
        }
        return false;
    }

    private static boolean hook(XposedModule module, Method m) {
        try {
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                try {
                    Log.i(TAG, "IC " + describe(m.getName(), chain.getArgs().toArray()));
                } catch (Throwable tr) {
                    Log.w(TAG, "IcTrace body err: " + tr);
                }
                return chain.proceed();   // 原样执行、原值返回
            });
            Log.i(TAG, "IcTrace: hooked " + m.getName() + "/" + m.getParameterCount());
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "IcTrace: hook " + m.getName() + " failed: " + tr);
            return false;
        }
    }

    private static String describe(String name, Object[] args) {
        final StringBuilder sb = new StringBuilder("IC ").append(name).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(args[i]));
        }
        return sb.append(')').toString();
    }

    private static String fmt(Object a) {
        if (a == null) return "null";
        if (a instanceof CharSequence) {
            final String s = a.toString();
            final StringBuilder q = new StringBuilder(s.length() + 2).append('"');
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (c == '\n') q.append("\\n");
                else if (c == '"') q.append("\\\"");
                else q.append(c);
            }
            return q.append('"').toString();
        }
        if (a instanceof KeyEvent) {
            final KeyEvent k = (KeyEvent) a;
            return "KeyEvent(kc=" + k.getKeyCode() + " action=" + k.getAction()
                    + " shift=" + k.isShiftPressed() + ")";
        }
        return String.valueOf(a);
    }
}
