package moe.lovefirefly.bzk.wetypeext;

import android.view.inputmethod.InputConnection;

/**
 * 提交文本的规范化层（TASK 2 / 3 / 4 共用）。
 *
 * <p>挂点在 {@link CommitHook}（框架 IC 的 {@code commitText}）。处理顺序与隔壁 gb 组件一致：
 * <pre>
 *   ① 智能编号（仅物理键盘）      1。 → 1.、1） → 1)
 *   ② 中英标点（开关）            切到"英文标点"时：，。！？… → ,.!?…
 *   ③ 全角模式（状态位）          开 = ASCII 符号转全角；
 *                                 关（默认）= **什么都不做**（微信自己的符号本来就是半角）
 * </pre>
 *
 * <h3>为什么只做"全角化"、不做"半角化"</h3>
 * 用户实测：微信自己的符号本来就是半角（{@code #-={}} 等都是），只有中文标点该全角 ——
 * 所以**不需要**"默认把全角拉回半角"那种修复（那是 gb 因为 Gboard 原生行为才需要的）。
 * 全角化仍按**区间规则**做（ASCII 符号区 {@code 0x21–0x2F / 0x3A–0x40 / 0x5B–0x60 / 0x7B–0x7E}
 * → {@code +0xFEE0}），而不是补表 —— 搜狗/gb 两代都吃过"补表永远会漏"的亏
 * （用户实测报过 {@code ＋＝} 漏掉），而 Gboard native 里那张表是覆盖整个 ASCII 可打印区的 1:1 全角表。
 *
 * <p>全角化刻意<b>不动字母数字</b>：拼音串与英文候选也走同一条 commit，若连字母一起全角化，
 * 中文态打字会变成全角拼音。
 */
final class TextNorm {

    /** 中文句号 U+3002。 */
    private static final char CN_FULL_STOP = '\u3002';
    /** 全角右括号 U+FF09。 */
    private static final char CN_RIGHT_PAREN = '\uFF09';

    /**
     * 中文标点 → ASCII（"中英标点"切到英文时那一侧；一一对应）。
     *
     * <p>不收 {@code 、}：物理键上分不出来，且中文里没有等价的单个 ASCII。
     */
    private static final String CN_PUNCT = "，。！？；：（）【】《》〈〉“”‘’";
    private static final String CN_PUNCT_ASCII = ",.!?;:()[]<><>\"\"''";

    private TextNorm() {}

    /**
     * 提交文本的总入口。
     *
     * @return 改写后的文本；{@code null} = 不需要改（调用方原样放行）
     */
    static String normalizeCommit(String raw, Object ic, char lastShown) {
        if (raw == null || raw.isEmpty()) return null;
        // ★ 三层一律**只作用于物理键盘输入**（用户口径）：
        //   软键盘上点 ，/。 是用户明确表达的输入；全半角对软键盘键面也是 no-op。
        if (!InputSource.isPhysical()) return null;
        String s = raw;

        // ① 智能编号
        final String n1 = smartNumber(s, ic, lastShown);
        if (n1 != null) s = n1;

        // ② 中英标点：切到"英文标点"时，中文标点落成 ASCII
        if (ExtConfig.get().enPunct) {
            final String n2 = toAsciiPunct(s);
            if (n2 != null) s = n2;
        }

        // ③ 全角模式：**功能开 && 状态位为真**才做（用户口径；状态位由 Shift+Space 切）
        if (ExtConfig.get().fullwidthFeature && PunctState.fullwidth()) {
            final String n3 = toFullWidth(s);
            if (n3 != null) s = n3;
        }

        return s.equals(raw) ? null : s;
    }

    /**
     * TASK 2 · 智能编号（**仅物理键盘**）：数字后面的中文标点用半角 ——
     * {@code 1。} → {@code 1.}、{@code 1）} → {@code 1)}。
     *
     * <p>判据（沿用 gb 验证过的口径，覆盖"一起上屏"和"分两次上屏"）：提交文本的末字符是
     * {@code 。}/{@code ）}，且它前面那个字符是数字 —— 前面的字符先在同一次提交内找，
     * 找不到就问 IC {@code getTextBeforeCursor(1)}。
     */
    static String smartNumber(String raw, Object ic, char lastShown) {
        if (!ExtConfig.get().smartNumber) return null;
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
     * TASK 4 · 中文标点 → ASCII（"中英标点"切到英文时用）。命中才返回新串，否则 {@code null}。
     */
    static String toAsciiPunct(CharSequence src) {
        if (src == null || src.length() == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            final int idx = CN_PUNCT.indexOf(c);
            if (idx < 0) {
                sb.append(c);
                continue;
            }
            sb.append(CN_PUNCT_ASCII.charAt(idx));
            changed = true;
        }
        return changed ? sb.toString() : null;
    }

    /**
     * TASK 3 · 全角化（全角模式开）：ASCII <b>符号区</b> → {@code FF01–FF5E}。
     *
     * <p>只动符号（{@code 0x21–0x2F}、{@code 0x3A–0x40}、{@code 0x5B–0x60}、{@code 0x7B–0x7E}），
     * <b>不动字母、数字与空格</b> —— 别把拼音/英文全角化。
     */
    static String toFullWidth(CharSequence src) {
        if (src == null || src.length() == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if ((c >= 0x21 && c <= 0x2F) || (c >= 0x3A && c <= 0x40)
                    || (c >= 0x5B && c <= 0x60) || (c >= 0x7B && c <= 0x7E)) {
                sb.append((char) (c + 0xFEE0));
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : null;
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
