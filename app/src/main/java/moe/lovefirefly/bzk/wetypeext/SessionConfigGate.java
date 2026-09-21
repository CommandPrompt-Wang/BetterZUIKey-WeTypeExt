package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 目标 2 的<b>引擎侧</b>切口：建会话时，如果这次是英文键盘，就把联想相关的引擎开关关掉。
 *
 * <h3>为什么还要这一刀</h3>
 * 实测（2026-09-21）：{@link EnAssocGate} 的 {@code N.k2()} 门在英文键盘确实返回了 false
 * （日志 `k2 called n0=100 ... -> BLOCK`），但英文键盘敲 {@code hello} 时
 * 仍然出现 {@code hellokitty} 这种**补全候选** —— 说明它是由引擎直接产出的**普通候选**，
 * 走的是 {@code i0.L3} 里 {@code k2} 之外的 {@code O5(...)} 那条路，Java 展示门管不着。
 *
 * <p>所以往上游走一步：会话配置里的 {@code enable_auto_most_likely} 由全局
 * {@code j1.S1()} 决定（`i0.java:13953`），与键盘语言无关；这里把它改成
 * 「只在英文会话里为 false」。
 *
 * <h3>为什么挂在 {@code WxhldApi.create_session}</h3>
 * <ul>
 *   <li>它是 {@code com.tencent.wxhld} 包下的<b>真名</b>（不混淆），签名简单：
 *       {@code static native long create_session(SessionConfig)}；</li>
 *   <li>到这一步 {@code keyboard_type} 已经写好（`i0.java:13972`），配置对象是完整的；</li>
 *   <li>只改一个入参对象的字段，不碰返回值、不碰显示逻辑，失败也只是"没生效"。</li>
 * </ul>
 *
 * <p>⚠️ 同样只在 Application 就绪之后安装（那份"不许提前触发微信类初始化"的纪律）。
 */
final class SessionConfigGate {

    private static final String TAG = BridgeHook.TAG;

    private static final String CLS_WXHLD_API = "com.tencent.wxhld.WxhldApi";
    private static final String CLS_SESSION_CONFIG = "com.tencent.wxhld.info.SessionConfig";

    /** {@code SessionConfig.KeyBoardType.FULL_ENGLISH}。 */
    private static final int KB_TYPE_FULL_ENGLISH = 2;

    private static volatile boolean sInstalled;
    private static volatile long sLastLog;

    private SessionConfigGate() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            final Class<?> api = Class.forName(CLS_WXHLD_API, false, cl);
            final Class<?> cfg = Class.forName(CLS_SESSION_CONFIG, false, cl);
            final Method m = api.getDeclaredMethod("create_session", cfg);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                try {
                    mutate(chain.getArg(0));
                } catch (Throwable tr) {
                    Log.w(TAG, "session cfg mutate err: " + tr);
                }
                return chain.proceed();
            });
            Log.i(TAG, "SessionConfigGate: hooked create_session(SessionConfig)");
        } catch (Throwable tr) {
            Log.w(TAG, "SessionConfigGate: install failed: " + tr);
        }
    }

    /**
     * 英文会话：关掉「最可能/联想」类引擎开关。中文会话<b>一个字段都不动</b>。
     *
     * <p>只改 {@code enable_auto_most_likely} 这一个（最小改动、可回退）；旁边的值只记日志，
     * 用来判断下次该动谁。
     */
    private static void mutate(Object cfg) throws Exception {
        if (cfg == null) return;
        final int kbType = getInt(cfg, "keyboard_type");
        final boolean mostLikely = getBool(cfg, "enable_auto_most_likely");
        final boolean textRecommend = getBool(cfg, "enable_text_recommend");
        final boolean hotWord = getBool(cfg, "enable_user_hot_word_recommend");

        if (!ExtConfig.get().enNoSuggest) return;
        // 只在英文会话、且确实开着的时候改，顺便限流打日志
        if (kbType == KB_TYPE_FULL_ENGLISH && mostLikely) {
            setBool(cfg, "enable_auto_most_likely", false);
            Log.i(TAG, "SessionConfigGate: EN session -> enable_auto_most_likely=false"
                    + " (was mostLikely=" + mostLikely
                    + " textRecommend=" + textRecommend
                    + " hotWord=" + hotWord + ")");
        } else if (System.currentTimeMillis() - sLastLog > 5000) {
            sLastLog = System.currentTimeMillis();
            Log.i(TAG, "SessionConfigGate: kbType=" + kbType
                    + " mostLikely=" + mostLikely
                    + " textRecommend=" + textRecommend
                    + " hotWord=" + hotWord + " (untouched)");
        }
    }

    private static Field field(Object o, String name) throws NoSuchFieldException {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int getInt(Object o, String name) {
        try {
            return field(o, name).getInt(o);
        } catch (Throwable tr) {
            return Integer.MIN_VALUE;
        }
    }

    private static boolean getBool(Object o, String name) {
        try {
            return field(o, name).getBoolean(o);
        } catch (Throwable tr) {
            return false;
        }
    }

    private static void setBool(Object o, String name, boolean v) {
        try {
            field(o, name).setBoolean(o, v);
        } catch (Throwable tr) {
            Log.w(TAG, "setBool " + name + " failed: " + tr);
        }
    }
}
