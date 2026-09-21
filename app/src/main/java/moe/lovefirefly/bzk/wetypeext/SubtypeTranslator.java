package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.inputmethod.InputMethodSubtype;

/**
 * 目标 1 的翻译层：把<b>框架 subtype 变化</b>翻成微信内部的中英切换。
 *
 * <h3>为什么需要它</h3>
 * 微信输入法声明了 0 个 {@code <subtype>}，框架完全不知道它的中/英（WeType 也从不读写
 * {@code InputMethodSubtype}）。于是系统的语言切换、BetterZUIKey 的「切换到下一个输入法语言」
 * 对它毫无效果。{@link SubtypeInjector} 把 zh-CN/en-US 暴露给框架之后，还得有人把
 * 「框架说要中文/英文」翻译成微信自己的键盘切换。
 *
 * <h3>映射（都来自真机实测，见 local/static/subtype-switch-trace.md）</h3>
 * <ul>
 *   <li>框架 {@code zh-*} → 切到中文：目标优先取 {@code j1.K(false)}（{@code ime_current_keyboard}）。
 *       实测在<b>英文态下它仍是 1</b>（中文 26 键），说明它记的是「中文侧键盘」而不是当前键盘，
 *       正好拿来当"切回哪个中文键盘"的依据；兜底 {@code N.O0()}（英文态实测返回 1），
 *       再兜底 {@code ChineseT9 = 0}。</li>
 *   <li>框架 {@code en-*} → 切 {@code EnglishQwerty = 100}。</li>
 *   <li>当前停在数字/符号/手写等面板（既非中文也不是 100）→ <b>不动</b>，别打断标点输入。</li>
 *   <li>已经在目标语言 → <b>不切</b>（幂等；反复切会不断重建键盘视图）。</li>
 * </ul>
 *
 * <h3>是否会打架</h3>
 * 微信自己切中英（工具栏语言键 / functionCode 7·8·27）<b>不会</b>回写框架 subtype
 * （实测：内部已 100，框架仍是 zh-CN），所以不会形成"切了又被切回来"的循环。
 * 但反过来说，用户在微信里手动切到英文后，下一次 {@code onStartInput} 会被框架 subtype
 * 拉回中文 —— 这是"框架优先"的必然结果，也是搜狗 OEM 那边「严格模式」的同一套语义。
 * 如果只想响应"框架真的变了"，把设置页的「严格跟随系统语言」关掉。
 */
final class SubtypeTranslator {

    private static final String TAG = BridgeHook.TAG;

    private SubtypeTranslator() {}

    /** subtype 变化（框架回调）时调用。 */
    static void onSubtype(InputMethodSubtype st, String why) {
        if (!ExtConfig.get().subtypeTranslate) return;
        try {
            sync(st, why);
        } catch (Throwable tr) {
            Log.w(TAG, "SubtypeTranslator(" + why + ") err: " + tr);
        }
    }

    private static void sync(InputMethodSubtype st, String why) {
        if (st == null) return;
        final String locale = st.getLocale();
        if (locale == null || locale.isEmpty()) {
            // 注入前框架给的是空 locale 的 dummy；此时无信息，不动。
            return;
        }
        final Boolean wantEnglish;
        if (locale.startsWith("en")) {
            wantEnglish = Boolean.TRUE;
        } else if (locale.startsWith("zh")) {
            wantEnglish = Boolean.FALSE;
        } else {
            return; // 不认识的语言，别乱动
        }

        final Integer cur = WeTypeInternals.keyboardValue();
        if (cur == null) return;

        // 面板（数字/符号/手写/表情…）不动：既不是中文也不是英文
        final Boolean curIsZh = WeTypeInternals.isChineseKeyboard(cur);
        if (curIsZh == null) return;
        final boolean curIsEn = cur.intValue() == WeTypeInternals.KB_ENGLISH_QWERTY;
        if (!curIsZh.booleanValue() && !curIsEn) {
            Log.i(TAG, "translate[" + why + "] locale=" + locale
                    + " cur=" + cur + " 面板，不动");
            return;
        }

        if (curIsEn == wantEnglish.booleanValue()) {
            Log.i(TAG, "translate[" + why + "] locale=" + locale
                    + " cur=" + cur + " 已是目标语言，跳过");
            return;
        }

        final int target = wantEnglish ? WeTypeInternals.KB_ENGLISH_QWERTY : chineseTarget();
        final boolean ok = WeTypeInternals.switchKeyboard(target);
        Log.i(TAG, "translate[" + why + "] locale=" + locale
                + " cur=" + cur + " -> " + target + " " + (ok ? "ok" : "FAILED"));
    }

    /**
     * 「切回中文」切哪个键盘。
     *
     * <p>优先用偏好 {@code ime_current_keyboard}（实测英文态下仍是中文侧键盘），
     * 再退 {@code N.O0()}（微信自己语言键的目标），最后兜底中文九键。
     */
    private static int chineseTarget() {
        final Integer pref = WeTypeInternals.prefKeyboard();
        if (isChinese(pref)) return pref.intValue();
        final Integer toggle = WeTypeInternals.toggleTarget();
        if (isChinese(toggle)) return toggle.intValue();
        return WeTypeInternals.KB_CHINESE_T9;
    }

    private static boolean isChinese(Integer v) {
        return v != null && Boolean.TRUE.equals(WeTypeInternals.isChineseKeyboard(v));
    }
}
