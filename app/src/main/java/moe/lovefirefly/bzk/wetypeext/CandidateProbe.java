package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 候选探针：英文键盘时把候选列表的「文本 + native flag + kind」打出来。
 *
 * <h3>为什么需要它</h3>
 * 实测结论（2026-09-21）：
 * <ul>
 *   <li>英文键盘敲 {@code hello} 会出 {@code hellokitty} 这类<b>前缀补全</b>，
 *       上屏一个词之后还会出下一个词（{@code nice → nickname}）；</li>
 *   <li>这些<b>不受 {@code ime_enable_associating} 影响</b>（该键现在是 false，
 *       引擎侧 {@code enable_auto_most_likely} 也已 false，英文建议照样出）；
 *   <li>{@code N.k2()} 那个「联想展示门」也管不着它们（实测已 BLOCK，候选照出）。</li>
 * </ul>
 * 所以必须看清楚它们身上带着什么标记，才能在 Java 侧按标记精准过滤。
 *
 * <p>候选类 {@code C1908d} 继承自 native proto 类
 * {@code com.tencent.wxhld.info.Candidate}，带这些公开字段：
 * {@code flag}(long) / {@code kind} / {@code most_likely_type} / {@code source} /
 * {@code text} / {@code origin_kind_if_from_user_dict}。
 * 其中 {@code Candidate.Flag.CANDIDATE_FLAG_FULL_ENGLISH = 64} 是本次重点怀疑对象。
 *
 * <p>挂在 {@code i0.L3(...)}（候选更新总入口，9 参）：第 4 参是引擎给的完整候选表，
 * 第 6 参是将要显示的列表，第 7 参是候选类型（1/3 = 联想）。
 */
final class CandidateProbe {

    private static final String TAG = BridgeHook.TAG;

    private static volatile long sLastLog;
    private static volatile boolean sInstalled;

    private CandidateProbe() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled || !BridgeHook.DEV_CAND_PROBE) return;
        sInstalled = true;
        try {
            final Class<?> i0 = Class.forName("com.tencent.wetype.plugin.hld.model.i0", false, cl);
            Method target = null;
            for (Method m : i0.getDeclaredMethods()) {
                if (!"L3".equals(m.getName())) continue;
                if (m.getParameterTypes().length == 9) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Log.w(TAG, "CandidateProbe: i0.L3(9 args) not found");
                return;
            }
            target.setAccessible(true);
            module.hook(target).intercept(chain -> {
                try {
                    dump(chain.getArg(4), chain.getArg(6), chain.getArg(7));
                } catch (Throwable tr) {
                    Log.w(TAG, "CandidateProbe dump err: " + tr);
                }
                return chain.proceed();
            });
            Log.i(TAG, "CandidateProbe: hooked i0.L3");
        } catch (Throwable tr) {
            Log.w(TAG, "CandidateProbe: install failed: " + tr);
        }
    }

    /** 只在英文键盘打，且限流（候选更新非常频繁）。 */
    private static void dump(Object allList, Object shownList, Object type) {
        final Integer kb = WeTypeInternals.keyboardValue();
        if (kb == null || kb.intValue() != WeTypeInternals.KB_ENGLISH_QWERTY) return;
        final long now = System.currentTimeMillis();
        if (now - sLastLog < 700) return;
        sLastLog = now;

        Log.i(TAG, "cand[en] type=" + type
                + " all=" + size(allList)
                + " shown=" + size(shownList));
        print("shown", shownList, 6);
    }

    private static int size(Object list) {
        return list instanceof List ? ((List<?>) list).size() : -1;
    }

    private static void print(String label, Object list, int max) {
        if (!(list instanceof List)) return;
        final List<?> l = (List<?>) list;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) {
            final Object c = l.get(i);
            if (c == null) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append('#').append(i)
              .append(" text=").append(str(c, "text"))
              .append(" flag=0x").append(Long.toHexString(num(c, "flag")))
              .append(" kind=").append(num(c, "kind"))
              .append(" mlType=").append(num(c, "most_likely_type"))
              .append(" src=").append(num(c, "source"))
              .append(" origKind=").append(num(c, "origin_kind_if_from_user_dict"));
        }
        Log.i(TAG, "cand[en] " + label + ": " + sb);
    }

    private static Field f(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                final Field fd = c.getDeclaredField(name);
                fd.setAccessible(true);
                return fd;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static long num(Object o, String name) {
        try {
            final Field fd = f(o, name);
            return fd == null ? -1 : fd.getLong(o);
        } catch (Throwable tr) {
            return -1;
        }
    }

    private static String str(Object o, String name) {
        try {
            final Field fd = f(o, name);
            final Object v = fd == null ? null : fd.get(o);
            return v == null ? "null" : String.valueOf(v);
        } catch (Throwable tr) {
            return "?";
        }
    }
}
