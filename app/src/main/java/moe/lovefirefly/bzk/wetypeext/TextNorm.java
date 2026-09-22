package moe.lovefirefly.bzk.wetypeext;

import android.view.inputmethod.InputConnection;

/**
 * 提交文本的规范化层（TASK 2 / 3 / 4 共用）。
 *
 * <p>挂点在 {@link CommitHook}（框架 IC 的 {@code commitText}）。处理顺序与隔壁 gb 组件一致：
 * <pre>
 *   ① 智能编号（仅物理键盘）      1。 → 1.、1） → 1)
 *   ② 原样输出斜杠（三态）         / 或 \ 是否原样输出（不填则都出 、）
 *   ②.5 中英标点（功能门 && 状态位） 切到"英文标点"时：，。！？… → ,.!?…
 *   ③ 全角模式（功能门 && 状态位）  开 = ASCII 符号转全角；
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
    private static final String CN_PUNCT = "，。！？；：（）【】《》〈〉“”‘’－＝｛｝·｀";
    private static final String CN_PUNCT_ASCII = ",.!?;:()[]<><>\"\"''-={}``";

    /**
     * 半角化时<b>不许动</b>的中文标点（它们本来就该是全角，语义层管它们）。
     *
     * <p>与搜狗 OEM Ext 的 {@code PunctPipeline.PUNCT_OWNED} 完全一致 —— 否则"半角模式"会把
     * ！？；：，（） 一起拉成 ASCII，等于绕开"中英标点"那个状态位。
     */
    private static final String CN_PUNCT_OWNED = "！？；：，（）";

    private TextNorm() {}

    /**
     * 启动自检：两张表长度一致、且能双向对上（防手滑写错顺序/漏字符）。
     * 只在装钩子时调一次，日志里能直接看到。
     */
    static String selfCheck() {
        if (CN_PUNCT.length() != CN_PUNCT_ASCII.length()) {
            return "LENGTH MISMATCH cn=" + CN_PUNCT.length() + " ascii=" + CN_PUNCT_ASCII.length();
        }
        final StringBuilder bad = new StringBuilder();
        for (int i = 0; i < CN_PUNCT.length(); i++) {
            final String back = toAsciiPunct(String.valueOf(CN_PUNCT.charAt(i)));
            if (back == null || back.charAt(0) != CN_PUNCT_ASCII.charAt(i)) {
                bad.append(CN_PUNCT.charAt(i)).append("->").append(CN_PUNCT_ASCII.charAt(i))
                   .append(' ');
            }
        }
        return bad.length() == 0 ? "ok (" + CN_PUNCT.length() + " pairs)" : "BAD " + bad;
    }

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

        // ② 原样输出斜杠（对齐搜狗 OEM Ext 的「原样输出斜杠」三态）：
        //    0=关（微信原样：/ 与 \ 都出 、）   1=按 / 出 /     2=按 \ 出 \
        //    命中时**跳过下面的中英标点层**（搜狗同款：这一格交给斜杠规则管）。
        boolean slashHandled = false;
        final int slashMode = ExtConfig.get().slashMode;
        if (slashMode != 0 && s.indexOf('、') >= 0) {
            final char want = (slashMode == 1) ? '/' : '\\';
            slashHandled = true;
            if (InputSource.lastKeyChar() == want) {
                s = s.replace("、", want == '\\' ? "\\" : "/");
            }
            // 另一个斜杠键：保持 、
        }

        // ②.5 中英标点（状态位，Ctrl+. 切）：切到"英文标点"时，中文标点落成 ASCII
        if (!slashHandled && ExtConfig.get().enPunctFeature && PunctState.enPunct()) {
            final String n2 = toAsciiPunct(s);
            if (n2 != null) s = n2;
        }

        // ③ 全角模式（功能门 + 状态位，Shift+Space 切）：
        //    状态=全角 ⇒ ASCII 全角化；状态=半角 ⇒ 把微信**自己**映射成全角的那些拉回半角
        //    （－＝｛｝ 这类，见 hardware/d.unicodeHalfToFull）。门关着一律不碰。
        if (ExtConfig.get().fullwidthFeature) {
            final String n3 = PunctState.fullwidth() ? toFullWidth(s) : toHalfWidth(s);
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
        final char lastKey = InputSource.lastKeyChar();
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            // 微信把 / 和 \ 都映射成 、 ⇒ 还原时用"上一个物理键"消歧（搜狗同款做法）
            if (c == '、') {
                sb.append(lastKey == '\\' ? '\\' : '/');
                changed = true;
                continue;
            }
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
     * <p><b>范围 = 整段 ASCII 可打印区</b>（{@code 0x21–0x7E} 全部 {@code +0xFEE0}，
     * 空格 → {@code U+3000}）—— 与搜狗 OEM Ext 的 {@code PunctPipeline.toFullWidth} 完全一致。
     *
     * <p>⚠️ 2026-09-21 用户纠正：gb 那边"全角化只动符号、不动字母数字"是<b>做错了</b>
     * （我先前照它抄了一遍，也错）—— 全角就是**全部全角**：
     * {@code 123} → {@code １２３}、{@code abc} → {@code ａｂｃ}、{@code ,} → {@code ，}。
     * 拼音是 <b>composing</b>（走 {@code setComposingText}，本层不碰），
     * 所以不会出现"拼音被全角化到没法看"，只有真正上屏的 ASCII 才会全角。
     */
    static String toFullWidth(CharSequence src) {
        if (src == null || src.length() == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c >= 0x21 && c <= 0x7E) {
                sb.append((char) (c + 0xFEE0));
                changed = true;
            } else if (c == ' ') {
                sb.append((char) 0x3000);   // 空格 → 全角空格（搜狗同款）
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : null;
    }

    /**
     * 半角化：全角 ASCII 区（{@code FF01–FF5E}）→ ASCII，全角空格 → 普通空格。
     *
     * <p>为什么要它：微信自己会把 {@code - = { }} 映射成 {@code －＝｛｝}（物理键那条
     * {@code unicodeHalfToFull} 表），所以"半角"状态下必须反向还原，否则打出来还是全角。
     *
     * <p>{@link #CN_PUNCT_OWNED} 里那几个（！？；：，（））<b>不动</b> —— 它们是中文标点，
     * 归"中英标点"那个状态位管（搜狗同款口径）。
     */
    static String toHalfWidth(CharSequence src) {
        if (src == null || src.length() == 0) return null;
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E && CN_PUNCT_OWNED.indexOf(c) < 0) {
                sb.append((char) (c - 0xFEE0));
                changed = true;
            } else if (c == 0x3000) {
                sb.append(' ');
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
