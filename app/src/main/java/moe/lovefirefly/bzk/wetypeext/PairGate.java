package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.inputmethod.InputConnection;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * TASK 1 · 括号/引号配对的<b>总开关</b>（关掉微信原生的自动补全 / 自动包裹）。
 *
 * <h3>为什么要在 IC 层"拆结果"</h3>
 * 微信的配对在 native（Java 侧既没有配对表也没有设置项，见 {@code local/static/ICON.md} 旁的
 * {@code plan.md} TASK 1 侦察记录）。真机实测它一次按键就提交成对的两个字：
 * <pre>
 *   无选区打 (   →  commitText("（）", 1)  然后  setSelection(光标+1)   ← 光标塞回中间
 *   选中 3 字打 ( →  commitText("("+选中+")", 1)  然后  setSelection(…)
 * </pre>
 * 关不掉源，所以在必经之路（框架 IC 的 {@code commitText}）把自动补的那半截拆掉：
 * <b>只留第一个字符（开括号）</b>。此时微信紧接着那个 {@code setSelection} 会把光标
 * 挪到"成对文本"的中间/末尾 —— 我们的文本已经短了，所以顺手把这一个 {@code setSelection}
 * <b>吃掉</b>（提交完光标本来就在开括号后面，正是我们要的位置），不需要任何位置推算。
 *
 * <h3>判据（只在开关关着时才动手）</h3>
 * 提交串首字符是本表里的开括号、末字符是它配对的闭括号：
 * <ul>
 *   <li>{@code （）} / {@code ()} / {@code “”} / {@code ""} / {@code 【】} …；</li>
 *   <li>包裹那次是 {@code 开 + 选中内容 + 闭}，同样命中（结果是"用开括号替换选区"，正是无配对时的正常行为）。</li>
 * </ul>
 * 单个字符、普通词、用户逐字敲出来的两次提交都不命中 ⇒ 一个字节不碰。
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li>开关<b>开着（默认）</b>时本类完全透明 —— 微信原生行为原样保留；</li>
 *   <li>{@code setSelection} 那一下只吃"刚刚发生过改写"之后紧接着的一次（400ms 窗口），
 *       避免误伤别处的光标设置；</li>
 *   <li>整段 try/catch：出错最多是"没拆掉"，不会影响上屏。</li>
 * </ul>
 */
final class PairGate {

    private static final String TAG = BridgeHook.TAG;

    /** 开括号（ASCII 与全角混排，索引与 {@link #CLOSE} 一一对应）。 */
    private static final String OPEN = "（(【[《<「『〖〔〈｛{“\"‘'";
    /** 对应的闭括号。 */
    private static final String CLOSE = "）)】]》>」』〗〕〉｝}”\"’'";

    /** 改写之后，微信紧接着那次 {@code setSelection} 认这么多毫秒。 */
    private static final long SWALLOW_MS = 400L;

    private static volatile long sSwallowUntil;
    private static volatile boolean sInstalled;

    /** 微信刚刚自动补出来的那个闭字符（0 = 没有）。closeSkip 只认它，不做推导。 */
    private static volatile char sJustPaired;

    private PairGate() {}

    /** 由 {@link CommitHook} 调用（commitText 的最终文本上）。@return 改写后的串；null = 不动 */
    static String stripAutoClose(String raw) {
        try {
            if (raw == null || raw.length() < 2) return null;
            if (ExtConfig.get().autoPair) return null;   // 开着 = 微信原生，不碰
            final char first = raw.charAt(0);
            final int i = OPEN.indexOf(first);
            if (i < 0) return null;
            if (CLOSE.indexOf(raw.charAt(raw.length() - 1)) != i) return null;
            sSwallowUntil = System.currentTimeMillis() + SWALLOW_MS;
            return String.valueOf(first);
        } catch (Throwable tr) {
            Log.w(TAG, "PairGate err: " + tr);
            return null;
        }
    }

    /**
     * 提交<b>之前</b>的判定（在 {@code commitText} 的钩子里、{@code proceed()} 之前）。
     *
     * @return true = 这次提交不要走（closeSkip 命中：闭字符已经在光标右边，只把光标移过去）
     */
    static boolean beforeCommit(String text, Object ic) {
        try {
            if (text == null || text.isEmpty()) return false;

            // ① 记下"微信刚自动补出来的闭字符"：只认正好一对的提交（（） “” 【】 …）
            if (ExtConfig.get().autoPair && text.length() == 2) {
                final int i = OPEN.indexOf(text.charAt(0));
                if (i >= 0 && CLOSE.indexOf(text.charAt(1)) == i) sJustPaired = text.charAt(1);
            }

            // ② closeSkip：这次要上屏的是一个闭字符，而且正是刚补出来的那个
            final char mark = sJustPaired;
            if (mark == 0 || text.length() != 1) return false;
            final char c = text.charAt(0);
            if (!(ic instanceof InputConnection)) return false;
            sJustPaired = 0;                       // 按闭字符**无条件**清位（搜狗同款纪律）
            if (!ExtConfig.get().closeSkip) return false;
            if (c != mark || CLOSE.indexOf(c) < 0) return false;

            final InputConnection conn = (InputConnection) ic;
            final CharSequence after = conn.getTextAfterCursor(1, 0);
            final int at = cursorOffset(conn);
            if (after != null && after.length() == 1 && after.charAt(0) == mark && at >= 0) {
                // ⚠️ 顺序不能反：先自己移光标，**再**武装"吃掉微信跟来的那一次 setSelection"。
                //    反过来写，我们这一次 setSelection 会被自己的钩子吃掉 ——
                //    真机现象就是"确实没多出 ） 了，但光标没动"。
                conn.setSelection(at + 1, at + 1);
                sSwallowUntil = System.currentTimeMillis() + SWALLOW_MS;
                Log.i(TAG, "closeSkip: 只移光标 -> " + (at + 1)
                        + " (U+" + Integer.toHexString(c) + ")");
                return true;                       // 原提交不要走
            }
            return false;
        } catch (Throwable tr) {
            Log.w(TAG, "PairGate.beforeCommit err: " + tr);
            return false;
        }
    }

    /** 光标偏移：拿"光标前全量文本"的长度（触顶就当问不到，跟搜狗那边同款保守做法）。 */
    private static int cursorOffset(InputConnection ic) {
        final int cap = 4096;
        try {
            final CharSequence before = ic.getTextBeforeCursor(cap, 0);
            if (before == null || before.length() >= cap) return -1;
            return before.length();
        } catch (Throwable tr) {
            return -1;
        }
    }

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            final Class<?> cls = Class.forName(
                    "android.inputmethodservice.RemoteInputConnection", false, cl);
            final Method m = cls.getDeclaredMethod("setSelection", int.class, int.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                try {
                    if (System.currentTimeMillis() < sSwallowUntil) {
                        sSwallowUntil = 0L;
                        Log.i(TAG, "pairGate: 吃掉配对后的 setSelection(" + chain.getArg(0)
                                + ", " + chain.getArg(1) + ")");
                        return Boolean.TRUE;
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "pairGate sel err: " + tr);
                }
                return chain.proceed();
            });
            Log.i(TAG, "PairGate: hooked setSelection");
        } catch (Throwable tr) {
            Log.w(TAG, "PairGate: install failed: " + tr);
        }
    }
}
