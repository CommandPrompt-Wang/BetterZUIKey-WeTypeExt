package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 诊断：<b>谁把输入法窗口藏了</b>（只打日志，不改行为）。
 *
 * <h3>为什么需要</h3>
 * 真机现象：软键盘开着时按 {@code Alt+V}/{@code Alt+H}（会切面板的那几个热键），
 * 软键盘<b>立刻消失</b>，面板也没出来；而按普通字母不会。日志证明面板状态确实切过去了
 * （{@code switchKeyboard -> 501/504}）—— 那就得知道"藏窗口"这一步是谁发起的：
 * <ul>
 *   <li>微信自己（{@code WxHldService.requestHideSelf}）—— 栈里能看到它的调用点；</li>
 *   <li>框架/宿主（宿主 App 调 {@code InputMethodManager.hideSoftInputFromWindow}）——
 *       那我们只会看到 {@code onWindowHidden}，没有 {@code requestHideSelf}。</li>
 * </ul>
 */
final class HideProbe {

    private static final String TAG = BridgeHook.TAG;
    private static volatile boolean sInstalled;

    private HideProbe() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        if (!BridgeHook.DEV_HIDE_PROBE) {
            Log.i(TAG, "HideProbe: 钩子未装（DEV_HIDE_PROBE=false），但 dumpTree 仍可用");
            sInstalled = true;
            return;
        }
        sInstalled = true;
        try {
            final Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService", false, cl);
            hookNoArg(module, svc, "requestHideSelf", "HIDE");
            hookNoArg(module, svc, "requestShowSelf", "SHOW");
            Log.i(TAG, "HideProbe: hooked requestHideSelf / requestShowSelf");
        } catch (Throwable tr) {
            Log.w(TAG, "HideProbe: install failed: " + tr);
        }
        // 微信自己的窗口事件（实例类）
        try {
            final Class<?> wx = Class.forName(
                    "com.tencent.wetype.plugin.hld.WxHldService", false, cl);
            for (String name : new String[]{"onWindowHidden", "onWindowShown"}) {
                try {
                    final Method m = wx.getDeclaredMethod(name);
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        Log.i(TAG, "HideProbe: " + name + " keyboardShow=" + keyboardShow());
                        return chain.proceed();
                    });
                    Log.i(TAG, "HideProbe: hooked " + name);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "HideProbe: wx hooks failed: " + tr);
        }
    }

    private static void hookNoArg(XposedModule module, Class<?> svc, String name, final String tag) {
        try {
            final Method m = svc.getDeclaredMethod(name, int.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                try {
                    Log.i(TAG, "HideProbe: " + tag + " flags=" + chain.getArg(0)
                            + " caller=" + caller());
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable tr) {
            Log.w(TAG, "HideProbe: hook " + name + " failed: " + tr);
        }
    }

    /** 微信自己的函数分发器入口埋点（证明链路真的走通、码是多少）。 */
    static void installFunctionProbe(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> kd = Class.forName(
                    "com.tencent.wetype.plugin.hld.key.d", false, cl);
            final Method m = kd.getDeclaredMethod("O", int.class, Object.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                try {
                    Log.i(TAG, "native onFunction(" + chain.getArg(0) + ", "
                            + chain.getArg(1) + ") from " + caller());
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            Log.i(TAG, "HideProbe: hooked key/d.O");
        } catch (Throwable tr) {
            Log.w(TAG, "HideProbe: function probe failed: " + tr);
        }
    }

    /** 更上层：WxHldService.onKeyDown 入口全量埋点（看闸门之前的键事件到底有没有）。 */
    static void installServiceKeyProbe(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> wx = Class.forName(
                    "com.tencent.wetype.plugin.hld.WxHldService", false, cl);
            for (String name : new String[]{"onKeyDown", "onKeyUp"}) {
                try {
                    final Method m = wx.getDeclaredMethod(name, int.class,
                            android.view.KeyEvent.class);
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        try {
                            final Object kc = chain.getArg(0);
                            final Object ev = chain.getArg(1);
                            final int meta = ev instanceof android.view.KeyEvent
                                    ? ((android.view.KeyEvent) ev).getMetaState() : -1;
                            Log.i(TAG, "svc " + name + " kc=" + kc + " meta=0x"
                                    + Integer.toHexString(meta) + " <- " + caller());
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    Log.i(TAG, "HideProbe: hooked WxHldService." + name);
                } catch (Throwable ignored) {
                }
            }
            // 顺便把"硬件模式"开关也打出来（谁在开/关它）
            final Class<?> hg = Class.forName(
                    "com.tencent.wetype.plugin.hld.hardware.g", false, cl);
            final Method l = hg.getDeclaredMethod("l", boolean.class);
            l.setAccessible(true);
            module.hook(l).intercept(chain -> {
                Log.i(TAG, "hardwareMode.l(" + chain.getArg(0) + ") <- " + caller());
                return chain.proceed();
            });
            Log.i(TAG, "HideProbe: hooked hardware.g.l");
        } catch (Throwable tr) {
            Log.w(TAG, "HideProbe: service key probe failed: " + tr);
        }
    }

    /** 面板切换入口埋点：把 N.n3(keyboardEnum, Bundle) / N.k3(int, Bundle) 的 bundle 全打出来。 */
    static void installPanelProbe(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> n = Class.forName(
                    "com.tencent.wetype.plugin.hld.model.N", false, cl);
            final Class<?> panelEnum = Class.forName(
                    "com.tencent.wetype.plugin.hld.keyboard.t", false, cl);
            final Method n3 = n.getDeclaredMethod("n3", panelEnum, android.os.Bundle.class);
            n3.setAccessible(true);
            module.hook(n3).intercept(chain -> {
                Log.i(TAG, "panel n3(" + chain.getArg(0) + ", " + dumpBundle(chain.getArg(1)) + ")");
                return chain.proceed();
            });
            final Method k3 = n.getDeclaredMethod("k3", int.class, android.os.Bundle.class);
            k3.setAccessible(true);
            module.hook(k3).intercept(chain -> {
                Log.i(TAG, "panel k3(" + chain.getArg(0) + ", " + dumpBundle(chain.getArg(1)) + ")");
                return chain.proceed();
            });
            Log.i(TAG, "HideProbe: hooked panel n3/k3");
        } catch (Throwable tr) {
            Log.w(TAG, "HideProbe: panel probe failed: " + tr);
        }
    }

    private static String dumpBundle(Object o) {
        if (!(o instanceof android.os.Bundle)) return "bundle=null";
        final android.os.Bundle b = (android.os.Bundle) o;
        final StringBuilder sb = new StringBuilder("bundle{");
        for (String k : b.keySet()) {
            sb.append(k).append('=').append(b.get(k)).append(' ');
        }
        return sb.append('}').toString();
    }

    /** 打印第一段非框架栈（谁调进来的）。 */
    private static String caller() {
        try {
            final StackTraceElement[] st = new Throwable().getStackTrace();
            final StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                final String c = e.getClassName();
                if (c.startsWith("java.lang.Thread") || c.startsWith("android.os.")) continue;
                if (c.equals(HideProbe.class.getName())) continue;
                if (c.startsWith("android.inputmethodservice.")) {
                    sb.append(c).append('.').append(e.getMethodName()).append(" <- ");
                    continue;
                }
                sb.append(c).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
                if (++n >= 4) break;
                sb.append(" <- ");
            }
            return sb.toString();
        } catch (Throwable tr) {
            return "?";
        }
    }

    /**
     * 把输入法窗口的 View 树打出来（类名 / 可见性 / 尺寸 / 位置）。
     *
     * <p>用来回答"面板到底画没画出来"：窗口没被藏、键盘那一屏却空了 ⇒
     * 要么面板 View 不在树上，要么在但尺寸是 0 / 被盖住。
     */
    static void dumpTree(String why) {
        try {
            final Object svc = ServiceProbe.service();
            if (!(svc instanceof android.inputmethodservice.InputMethodService)) {
                Log.i(TAG, "dumpTree(" + why + "): no service");
                return;
            }
            final android.inputmethodservice.InputMethodService ims =
                    (android.inputmethodservice.InputMethodService) svc;
            final android.app.Dialog dlg = ims.getWindow();   // IME 的窗口是个 Dialog
            final android.view.Window w = dlg == null ? null : dlg.getWindow();
            if (w == null) {
                Log.i(TAG, "dumpTree(" + why + "): no window");
                return;
            }
            final android.view.View decor = w.getDecorView();
            Log.i(TAG, "dumpTree(" + why + ") inputViewShown=" + ims.isInputViewShown()
                    + " kb=" + WeTypeInternals.currentValue()
                    + " decor=" + decor.getWidth() + "x" + decor.getHeight());
            sNodes = 0;
            walk(decor, 0);
        } catch (Throwable tr) {
            Log.w(TAG, "dumpTree failed: " + tr);
        }
    }

    private static int sNodes;

    private static void walk(android.view.View v, int depth) {
        if (v == null || depth > 16 || sNodes > 150) return;
        sNodes++;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName())
          .append(" vis=").append(v.getVisibility())
          .append(" ").append(v.getWidth()).append('x').append(v.getHeight())
          .append(" @").append((int) v.getX()).append(',').append((int) v.getY());
        Log.i(TAG, "  |" + sb);
        if (v instanceof android.view.ViewGroup) {
            final android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1);
        }
    }

    private static boolean keyboardShow() {
        try {
            final Object svc = ServiceProbe.service();
            return svc instanceof android.inputmethodservice.InputMethodService
                    && ((android.inputmethodservice.InputMethodService) svc).isInputViewShown();
        } catch (Throwable tr) {
            return false;
        }
    }
}
