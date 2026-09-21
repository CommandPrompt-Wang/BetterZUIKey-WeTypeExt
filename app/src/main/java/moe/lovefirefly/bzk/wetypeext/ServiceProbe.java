package moe.lovefirefly.bzk.wetypeext;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 第一轮：<b>只挂框架方法、只打日志</b>，不改任何行为。
 *
 * <p>要回答三个问题（静态分析给不出答案、只能真机看）：
 * <ol>
 *   <li>注入 subtype 之后，框架到底会不会回调 {@code onCurrentInputMethodSubtypeChanged}？
 *       本机 Android 16 上它<b>只有单参版</b> {@code (InputMethodSubtype)}，WeType 没覆盖它
 *       ⇒ 挂框架实现即可生效（旧的 {@code (int, InputMethodSubtype)} 两参版已不存在，但保留尝试）。</li>
 *   <li>回调来的时候，微信内部键盘值（{@code N.n0()/u0()/O0()}）动不动？
 *       —— 这决定翻译层要"推"还是要"拦"。</li>
 *   <li>中文九键 / 中文 26 键 / 英文 26 键三态下，这些值分别是什么。</li>
 * </ol>
 *
 * <p>钩子一律挂在 {@code android.inputmethodservice.InputMethodService} 这个框架类上：
 * 框架类名/方法名不混淆、跨版本稳定。抓实例用 {@code chain.getThisObject()}。
 *
 * <h3>⚠️ 血泪：绝不能在 {@code onPackageReady} 阶段碰微信自己的类（2026-09-21 已验证）</h3>
 * LSPosed 的 {@code onPackageReady} 早于 Application 创建。此时去读 {@code model.N} 的静态字段
 * （{@code Field.get(null)}）会触发 {@code N.<clinit>} → {@code j1.<clinit>} → MMKV，
 * 而 MMKV 还没 init ⇒ {@code Context} 为 null ⇒ NPE ⇒ <b>类初始化失败是粘性的</b>：
 * 之后微信自己在 RFix/Tinker 初始化里用同一个 MMKV 类就拿到 {@code NoClassDefFoundError}，
 * 结果 {@code com.tencent.wetype:hld} <b>每次启动即崩</b>（实测连崩 14 次）。
 *
 * <p>所以内部类的解析必须<b>推迟到 Application 起来之后的服务回调里</b>（见 {@link #ensureInternals()}）。
 */
final class ServiceProbe {

    private static final String TAG = BridgeHook.TAG;

    private static volatile boolean sInstalled;
    private static volatile Object sService;
    private static volatile ClassLoader sCl;
    private static volatile XposedModule sModule;
    private static volatile boolean sInternalsTried;

    private ServiceProbe() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        sCl = cl;
        sModule = module;

        // 注意：这里**不能**解析微信内部类，只能碰框架类。原因见类注释。

        final Class<?> svc;
        try {
            svc = Class.forName("android.inputmethodservice.InputMethodService", false, cl);
        } catch (Throwable tr) {
            Log.w(TAG, "InputMethodService not found: " + tr);
            return;
        }

        // setInputView 在 WeType 里**没有被覆盖**（框架实现是活的）⇒ 用它抓服务实例。
        try {
            final Method m = svc.getDeclaredMethod("setInputView", View.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                sService = chain.getThisObject();
                Log.i(TAG, "service instance = "
                        + (sService == null ? "?" : sService.getClass().getName()));
                dump("setInputView");
                return chain.proceed();
            });
        } catch (Throwable tr) {
            Log.w(TAG, "setInputView hook failed: " + tr);
        }

        // onStartInput：WeType 覆盖了但**调了 super** ⇒ 框架实现会执行，钩子会响（supercheck 实证 LIVE）。
        tryHook(module, svc, "onStartInput", EditorInfo.class, boolean.class);

        // 主目标：subtype 变化（本机只有单参版）。
        tryHook(module, svc, "onCurrentInputMethodSubtypeChanged", InputMethodSubtype.class);
        // 老设备的两参版；本机不存在，失败了只当没这回事。
        tryHook(module, svc, "onCurrentInputMethodSubtypeChanged", int.class, InputMethodSubtype.class);

        Log.i(TAG, "service probe installed");
    }

    private static void tryHook(XposedModule module, Class<?> svc, String name, Class<?>... params) {
        try {
            final Method m = svc.getDeclaredMethod(name, params);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                try {
                    InputMethodSubtype st = null;
                    for (Object a : chain.getArgs()) {
                        if (a instanceof InputMethodSubtype) {
                            st = (InputMethodSubtype) a;
                            break;
                        }
                    }
                    if (st != null) {
                        Log.i(TAG, "onSubtypeChanged " + fmt(st));
                    }
                    final Object self = chain.getThisObject();
                    if (self != null) sService = self;
                    dump(name);
                } catch (Throwable tr) {
                    Log.w(TAG, name + " hook body err: " + tr);
                }
                return chain.proceed();
            });
            Log.i(TAG, "hooked " + name + "(" + params.length + " args)");
        } catch (Throwable tr) {
            Log.i(TAG, "no hook for " + name + "/" + params.length + " (" + tr + ")");
        }
    }

    /**
     * 惰性解析微信内部类，且<b>只在 Application 已经创建之后</b>才动手。
     *
     * <p>判据用 {@code ActivityThread.currentApplication() != null}：微信的
     * {@code HldApplicationLike.onBaseContextAttached} 在这里面，MMKV 也是那时 init 的，
     * 所以它非空就说明可以安全触碰 {@code model.N} / {@code utils.j1} 了。
     */
    private static void ensureInternals() {
        if (sInternalsTried) return;
        if (!BridgeHook.DEV_INTERNALS) return;
        final ClassLoader cl = sCl;
        if (cl == null) return;
        if (!WeTypeInternals.applicationReady()) return;
        sInternalsTried = true;
        WeTypeInternals.resolve(cl);
        // 内部类拿到之后才装「英文键盘联想闸门」（它要 hook N.k2）
        EnAssocGate.install(sModule, WeTypeInternals.nClass());
        // 引擎侧那一刀：建会话时按语言改 SessionConfig（不依赖混淆类）
        SessionConfigGate.install(sModule, cl);
        // 候选探针（诊断用，已收工）：搞清英文补全候选身上的标记
        CandidateProbe.install(sModule, cl);
        // 目标 2 的落点：英文键盘清空候选栏
        EnCandidateFilter.install(sModule, cl);
    }

    /** 一次性把「框架看到的 subtype」与「微信内部键盘状态」打在一行，方便对照。 */
    private static void dump(String why) {
        try {
            ensureInternals();
            final InputMethodSubtype cur = currentSubtype();
            final Integer n0 = WeTypeInternals.keyboardValue();
            final Integer u0 = WeTypeInternals.currentValue();
            final Integer o0 = WeTypeInternals.toggleTarget();
            final Integer pref = WeTypeInternals.prefKeyboard();
            Log.i(TAG, "state[" + why + "] fw=" + fmt(cur)
                    + " | n0=" + n0 + "(zh=" + WeTypeInternals.isChineseKeyboard(n0) + ")"
                    + " u0=" + u0
                    + " O0=" + o0
                    + " pref=" + pref);
        } catch (Throwable tr) {
            Log.w(TAG, "dump(" + why + ") err: " + tr);
        }
    }

    /**
     * 框架眼里的「当前 subtype」。
     *
     * <p>{@code InputMethodService.getCurrentInputMethodSubtype()} 是隐藏 API（编译期不可见），
     * 所以：先反射问服务本体（最准），失败再退到公开的
     * {@code InputMethodManager.getCurrentInputMethodSubtype()}。
     */
    private static InputMethodSubtype currentSubtype() {
        final Object self = sService;
        if (self != null) {
            try {
                final Method m = self.getClass().getMethod("getCurrentInputMethodSubtype");
                m.setAccessible(true);
                final Object r = m.invoke(self);
                if (r instanceof InputMethodSubtype) return (InputMethodSubtype) r;
            } catch (Throwable ignored) {
            }
            if (self instanceof Context) {
                try {
                    final InputMethodManager imm = (InputMethodManager) ((Context) self)
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) return imm.getCurrentInputMethodSubtype();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String fmt(InputMethodSubtype st) {
        if (st == null) return "null";
        try {
            return st.getLocale() + "/" + st.getMode() + "#" + st.hashCode();
        } catch (Throwable tr) {
            return "?";
        }
    }
}
