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
    /** 键盘动作分发器（jadx 名 {@code key/d}）：全局函数码都汇到它的 {@code O(int,Object)}。 */
    private static final String CLS_KEY_D = "com.tencent.wetype.plugin.hld.key.d";
    /** 键盘/面板枚举（jadx 名 {@code keyboard/EnumC2058t}）。 */
    private static final String CLS_PANEL_ENUM = "com.tencent.wetype.plugin.hld.keyboard.t";

    /**
     * 函数码（TASK 6 用）—— 来自 APK 里键盘布局 JSON 的 {@code touchFunctionCode}
     * （{@code assets/keyboard/output/*.json}，键 id 一眼能认）：
     * <pre>
     *   1=删除 2=回车/发送 3=Shift 4=符号 5/15=数字 6=空格 7=切中文 8=切英文 9/24=返回
     *   19/20=手写全屏/半屏 22=表情(emoji) 23=拼音 25=语音(voice) 26=ABC 27=特殊→26 键
     * </pre>
     */
    static final int FN_EMOJI = 22;
    static final int FN_VOICE = 25;

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
    private static volatile Method mK3;
    private static volatile Method mY0;
    private static volatile Method mJ1K;
    private static volatile Object sKeyD;
    private static volatile Method mFireFn;
    private static volatile Method mR3;
    private static volatile Class<?> sPanelEnum;

    private WeTypeInternals() {}

    /** 已解析出的键盘控制器类（{@code model.N}）；null = 还没解析成功。 */
    static Class<?> nClass() {
        return sNClass;
    }

    /** 目标进程的 Application Context（注册接收器 / 落盘都要用它，别用 system context）。 */
    static android.content.Context appContext() {
        try {
            final Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return app instanceof android.content.Context ? (android.content.Context) app : null;
        } catch (Throwable tr) {
            return null;
        }
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
            mK3 = findMethod(n, "k3", int.class, android.os.Bundle.class);
            mY0 = findMethod(n, "y0");
            Log.i(TAG, "internals N: singleton=" + (sN != null)
                    + " n0=" + (mN0 != null) + " u0=" + (mU0 != null)
                    + " O0=" + (mO0 != null) + " S1(int)=" + (mS1i != null)
                    + " k3(int,Bundle)=" + (mK3 != null));
        } catch (Throwable tr) {
            Log.w(TAG, "internals N unresolved: " + tr);
        }
        try {
            final Class<?> kd = Class.forName(CLS_KEY_D, false, cl);
            sKeyD = findSingleton(kd);
            mFireFn = findMethod(kd, "O", int.class, Object.class);
            Log.i(TAG, "internals key.d: singleton=" + (sKeyD != null)
                    + " O(int,Object)=" + (mFireFn != null));
        } catch (Throwable tr) {
            Log.w(TAG, "internals key.d unresolved: " + tr);
        }
        try {
            sPanelEnum = Class.forName(CLS_PANEL_ENUM, false, cl);
            // ⚠️ r3 在 dex 里是**静态**方法，签名首参就是 N 自己：
            //    r3(N, 面板枚举, Bundle, 默认参数掩码, 标记) —— 按 jadx 的 N.r3(n10, …) 写法猜成
            //    实例方法会 getDeclaredMethod 找不到（实测 r3=false）。
            mR3 = findMethod(nClassOrNull(), "r3", nClassOrNull(), sPanelEnum,
                    android.os.Bundle.class, int.class, Object.class);
            Log.i(TAG, "internals panel: enum=" + sPanelEnum + " r3=" + (mR3 != null));
        } catch (Throwable tr) {
            Log.w(TAG, "internals panel unresolved: " + tr);
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

    private static Class<?> nClassOrNull() {
        return sNClass;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        if (c == null) return null;
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

    /**
     * 切键盘（{@code N.k3(int, Bundle)}，真实 dex 名）。
     *
     * <p>内部走 {@code l3 -> m3}，后者在<b>主线程协程</b>里执行（`N.java:6068`），
     * 所以从任意线程调用都安全；但我们仍在服务回调（主线程）里调，少一层不确定性。
     *
     * @return true = 已发起切换
     */
    static boolean switchKeyboard(int keyboardValue) {
        final Object self = sN;
        if (mK3 == null || self == null) return false;
        try {
            mK3.invoke(self, keyboardValue, null);
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "switchKeyboard(" + keyboardValue + ") failed: " + tr);
            return false;
        }
    }

    /**
     * 候选条可见性（{@code N.y0()} 拿到候选 View 再看 visibility）。
     *
     * <p>TASK 5 相关：候选条可见时，微信会把方向键交给候选条导航
     * （{@code hardware/d.n} 的 19–23 → {@code h()} → {@code P()}），这会影响 Shift+方向键。
     */
    static String candidateBar() {
        final Object self = sN;
        if (mY0 == null || self == null) return "?";
        try {
            final Object v = mY0.invoke(self);
            if (!(v instanceof android.view.View)) return "null";
            final int vis = ((android.view.View) v).getVisibility();
            return vis == android.view.View.VISIBLE ? "VISIBLE" : "vis" + vis;
        } catch (Throwable tr) {
            return "err";
        }
    }

    /**
     * 触发微信自己的一个"函数"（工具栏/键盘键共用的那条路 {@code key.d.O(int,Object)}）。
     *
     * <p>为什么走它而不是模拟按键：这些功能本来就是"函数码"驱动的（布局 JSON 里写着
     * {@code touchFunctionCode}），直接调等于替用户点了那个键，不依赖焦点/工具栏状态。
     *
     * @return true = 已发起
     */
    static boolean fireFunction(int code) {
        final Object d = sKeyD;
        final Method m = mFireFn;
        if (d == null || m == null) return false;
        try {
            m.invoke(d, code, null);
            Log.i(TAG, "fireFunction(" + code + ") ok");
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "fireFunction(" + code + ") failed: " + tr);
            return false;
        }
    }

    /**
     * 打开「常用语 / 剪贴板」面板（{@code EnumC2058t.CustomPhraseAndClipboard}）。
     *
     * <p>它没有函数码（键盘布局里没有这个键，是工具栏/候选区的入口），所以走面板切换
     * {@code N.r3(N, 面板枚举, Bundle, 默认参数掩码, 标记)}。枚举项按<b>名字</b>找
     * （Kotlin 枚举名保留了，找不到就扫一遍名字里带 Clipboard 的）。
     */
    static boolean openClipboardPanel() {
        final Object self = sN;
        final Method r3 = mR3;
        if (self == null || r3 == null || sPanelEnum == null) return false;
        try {
            Object panel = null;
            for (Object c : sPanelEnum.getEnumConstants()) {
                final String n = String.valueOf(c);
                if (n.contains("Clipboard")) { panel = c; break; }
            }
            if (panel == null) {
                Log.w(TAG, "openClipboardPanel: 没找到 Clipboard 面板项");
                return false;
            }
            r3.invoke(null, self, panel, null, 2, null);
            Log.i(TAG, "openClipboardPanel: " + panel);
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "openClipboardPanel failed: " + tr);
            return false;
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
