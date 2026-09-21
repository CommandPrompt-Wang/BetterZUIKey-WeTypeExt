package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 对微信输入法内部键盘状态的<b>只读</b>探针。
 *
 * <p>⚠️ 这里的名字（{@code model.N} / {@code utils.j1} 及其方法）都是<b>混淆名</b>，
 * 只能用于开发期探针，<b>不许进生产逻辑</b>（见 local/static/subtype-switch-trace.md §6）。
 * 所以本类：优先按名字找，找不到就按<b>结构</b>兜底，全失败也只是返回 null，绝不影响输入法。
 *
 * <p>实证（3.5.4 / 56201，idx 索引确认过是真名）：
 * <ul>
 *   <li>{@code com.tencent.wetype.plugin.hld.model.N}：单例字段 {@code a}；
 *       {@code n0()} 当前一级键盘、{@code u0()} 当前键盘、{@code O0()} 语言键的目标、
 *       {@code S1(int)} 是否中文键盘（0/1/5/6 → true，100 → false）。</li>
 *   <li>{@code com.tencent.wetype.plugin.hld.utils.j1}：单例字段 {@code a}；
 *       {@code K(boolean)} 读偏好 {@code ime_current_keyboard}（默认 0 = 中文九键）。</li>
 * </ul>
 */
final class WeTypeInternals {

    private static final String TAG = BridgeHook.TAG;

    private static final String CLS_N = "com.tencent.wetype.plugin.hld.model.N";
    private static final String CLS_J1 = "com.tencent.wetype.plugin.hld.utils.j1";

    /** 中文九键 / 中文 26 键 / 英文 26 键 —— 来自 keyboard.t（jadx 名 EnumC2058t）。 */
    static final int KB_CHINESE_T9 = 0;
    static final int KB_CHINESE_QWERTY = 1;
    static final int KB_ENGLISH_QWERTY = 100;

    private static volatile boolean sTried;
    private static volatile Class<?> sNClass;
    private static volatile Object sN;
    private static volatile Object sJ1;
    private static volatile Method mN0;
    private static volatile Method mU0;
    private static volatile Method mO0;
    private static volatile Method mS1i;
    private static volatile Method mJ1K;

    private WeTypeInternals() {}

    /** 已解析出的键盘控制器类（{@code model.N}）；null = 还没解析成功。 */
    static Class<?> nClass() {
        return sNClass;
    }

    /**
     * Application 是否已经创建。微信在 {@code HldApplicationLike.onBaseContextAttached} 里
     * init MMKV，所以这个为真才说明可以安全触碰它的类。
     */
    static boolean applicationReady() {
        try {
            return Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null) != null;
        } catch (Throwable tr) {
            return false;
        }
    }

    /**
     * 幂等；失败只记一行日志。
     *
     * <p>⚠️ <b>只在 Application 起来之后调用</b>。本方法里 {@code Field.get(null)} 会触发
     * {@code N.<clinit>} → {@code j1.<clinit>} → MMKV；若 MMKV 尚未 init，类初始化失败会
     * <b>粘死</b>该 ClassLoader，导致微信进程启动即崩（2026-09-21 实测）。这里再加一道哨兵。
     */
    static synchronized void resolve(ClassLoader cl) {
        if (sTried) return;
        if (!applicationReady()) {
            Log.i(TAG, "internals: Application 未就绪，推迟解析");
            return;
        }
        sTried = true;
        try {
            final Class<?> n = Class.forName(CLS_N, false, cl);
            sNClass = n;
            sN = findSingleton(n);
            mN0 = findMethod(n, "n0");
            mU0 = findMethod(n, "u0");
            mO0 = findMethod(n, "O0");
            mS1i = findMethod(n, "S1", int.class);
            if (mS1i == null) mS1i = findChinesePredicate(n);
            Log.i(TAG, "internals N: singleton=" + (sN != null)
                    + " n0=" + (mN0 != null) + " u0=" + (mU0 != null)
                    + " O0=" + (mO0 != null) + " S1(int)=" + (mS1i != null));
        } catch (Throwable tr) {
            Log.w(TAG, "internals N unresolved: " + tr);
        }
        try {
            final Class<?> j1 = Class.forName(CLS_J1, false, cl);
            sJ1 = findSingleton(j1);
            mJ1K = findMethod(j1, "K", boolean.class);
            Log.i(TAG, "internals j1: singleton=" + (sJ1 != null) + " K(boolean)=" + (mJ1K != null));
        } catch (Throwable tr) {
            Log.w(TAG, "internals j1 unresolved: " + tr);
        }
    }

    /** 单例：优先名字 {@code a}，否则找「static 且类型等于自身」的那个字段。 */
    private static Object findSingleton(Class<?> c) {
        try {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!c.equals(f.getType())) continue;
                f.setAccessible(true);
                final Object v = f.get(null);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        try {
            final Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 结构兜底找「是否中文键盘」谓词：{@code (int) → boolean}，且 0 → true、100 → false。
     * 找不到就返回 null（调用方转成"未知"）。
     */
    private static Method findChinesePredicate(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != boolean.class) continue;
                final Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || ps[0] != int.class) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                try {
                    final Object self = sN;
                    if (self == null) continue;
                    if (Boolean.TRUE.equals(m.invoke(self, KB_CHINESE_T9))
                            && Boolean.FALSE.equals(m.invoke(self, KB_ENGLISH_QWERTY))) {
                        Log.i(TAG, "internals: S1(int) 由结构兜底命中 " + m.getName());
                        return m;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Integer callInt(Method m) {
        final Object self = sN;
        if (m == null || self == null) return null;
        try {
            return (Integer) m.invoke(self);
        } catch (Throwable tr) {
            return null;
        }
    }

    /** 当前一级键盘值（{@code N.n0()}）。 */
    static Integer keyboardValue() {
        return callInt(mN0);
    }

    /** 当前键盘值（{@code N.u0()}，任务栈栈顶）。 */
    static Integer currentValue() {
        return callInt(mU0);
    }

    /** 语言键要切去的目标（{@code N.O0()}）。 */
    static Integer toggleTarget() {
        return callInt(mO0);
    }

    /** 是否中文键盘（{@code N.S1(int)}）；null = 探针没解析出来。 */
    static Boolean isChineseKeyboard(Integer value) {
        final Object self = sN;
        if (mS1i == null || self == null || value == null) return null;
        try {
            return (Boolean) mS1i.invoke(self, value.intValue());
        } catch (Throwable tr) {
            return null;
        }
    }

    /** 偏好 {@code ime_current_keyboard}（{@code j1.a.K(false)}）。 */
    static Integer prefKeyboard() {
        final Object self = sJ1;
        if (mJ1K == null || self == null) return null;
        try {
            return (Integer) mJ1K.invoke(self, false);
        } catch (Throwable tr) {
            return null;
        }
    }
}
