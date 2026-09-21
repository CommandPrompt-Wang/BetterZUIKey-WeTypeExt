package moe.lovefirefly.bzk.wetypeext;

import android.view.inputmethod.InputConnection;

/**
 * 提交文本的规范化层（TASK 2 / 3 / 4 共用）。
 *
 * <p>挂点在 {@link CommitHook}（框架 IC 的 {@code commitText}），这里只做纯函数式的改写，
 * 方便以后把 TASK 3（全半角）与 TASK 4（中英标点）作为新的层加进来。
 */
final class TextNorm {

    /** 中文句号 U+3002。 */
    private static final char CN_FULL_STOP = '\u3002';
    /** 全角右括号 U+FF09。 */
    private static final char CN_RIGHT_PAREN = '\uFF09';

    private TextNorm() {}

    /**
     * TASK 2 · 智能编号（**仅物理键盘**）：数字后面的中文标点用半角 —— {@code 1。} → {@code 1.}、{@code 1）} → {@code 1)}。
     *
     * <p>判据（沿用隔壁 gb 组件验证过的口径，能覆盖"一起上屏"和"分两次上屏"两种输入）：
     * 提交文本的<b>末字符</b>是 {@code 。} 或 {@code ）}，且它前面那个字符是数字 ——
     * 前面的字符先在<b>同一次提交内</b>找，找不到就问 IC {@code getTextBeforeCursor(1)}。
     *
     * @return 改写后的文本；{@code null} = 不需要改（调用方原样放行）
     */
    static String smartNumber(String raw, Object ic, char lastShown) {
        if (!ExtConfig.get().smartNumber) return null;
        // 只改物理键盘输入：软键盘上点 。/） 是用户明确表达的输入，不该动
        if (!InputSource.isPhysical()) return null;
        if (raw == null || raw.isEmpty()) return null;
        final char tail = raw.charAt(raw.length() - 1);
        if (tail != CN_FULL_STOP && tail != CN_RIGHT_PAREN) return null;
        final char prev = raw.length() >= 2
                ? raw.charAt(raw.length() - 2)
                : beforeCursor(ic, lastShown);
        if (prev < '0' || prev > '9') return null;
        return raw.substring(0, raw.length() - 1) + (tail == CN_FULL_STOP ? "." : ")");
    }

    /**
     * 光标前一个字符：优先问 IC（最准），失败退回"我们记的上一次上屏的字符"。
     *
     * <p>问 IC 要放在 {@code proceed()} <b>之前</b>做 —— 提交之后光标就跑到后面去了。
     */
    private static char beforeCursor(Object ic, char lastShown) {
        try {
            if (ic instanceof InputConnection) {
                final CharSequence cs = ((InputConnection) ic).getTextBeforeCursor(1, 0);
                if (cs != null && cs.length() > 0) return cs.charAt(cs.length() - 1);
            }
        } catch (Throwable ignored) {
        }
        return lastShown;
    }
}
