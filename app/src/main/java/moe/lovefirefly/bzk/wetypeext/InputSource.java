package moe.lovefirefly.bzk.wetypeext;

/**
 * 判断"这次提交是物理键盘敲的，还是软键盘点的"。
 *
 * <h3>为什么需要</h3>
 * TASK 2 智能编号（以及 TASK 3/4 的标点改写）<b>只该作用于物理键盘输入</b> ——
 * 软键盘上点 `。`/`）` 是用户明确表达的输入，不该被我们改成半角。
 * 但两条路最终都汇到同一个 {@code commitText}（框架 IC），提交文本本身不带来源信息。
 *
 * <h3>怎么区分</h3>
 * <b>入口不同</b>：物理键走 {@code hardware/d.n(int, KeyEvent)}（→ {@code p()} → 提交），
 * 软键盘走键盘视图的触摸处理。所以：
 * <ul>
 *   <li>挂 {@code hardware/d.n}，每次物理键按下记一个时间戳；</li>
 *   <li>{@link #isPhysical()} 判断"刚刚（{@value #WINDOW_MS} ms 内）有物理键"。</li>
 * </ul>
 *
 * <p>标记由 {@link Hotkeys} 在物理键入口打（同一个钩子，避免对同一方法挂两个钩子）。
 *
 * <p>为什么用时间窗而不是 ThreadLocal：物理键的提交可能经 `key/d` 的命令队列/协程走一跳，
 * 跨线程会丢标记。窗口按真机手感取 {@value #WINDOW_MS} ms —— 足够覆盖那一跳，
 * 又短到不会把随后软键盘的一次点击误判成物理键（那样最多是"该改的没改"，不会反向误伤）。
 */
final class InputSource {

    private static final String TAG = BridgeHook.TAG;

    /** 物理键标记的有效窗口。 */
    private static final long WINDOW_MS = 300L;

    private static volatile long sPhysKeyAt = -1L;
    private static volatile boolean sInstalled;

    /** 最后一个物理键**打出来的字符**（微信把 {@code /} 与 {@code \} 都映射成 {@code 、}，
     * 还原时要用它消歧）。 */
    private static volatile char sLastKeyChar;

    private InputSource() {}

    /** 物理键按下时调（由 {@link Hotkeys} 在同一个钩子里打点）。 */
    static void markPhysical(char keyChar) {
        if (keyChar > 0) sLastKeyChar = keyChar;
        markPhysical();
    }

    /** 最后一个物理键的字符（没记到就是 0）。 */
    static char lastKeyChar() {
        return sLastKeyChar;
    }

    static void markPhysical() {
        sPhysKeyAt = System.currentTimeMillis();
    }

    /** 这次提交是不是（刚刚的）物理键盘输入。 */
    static boolean isPhysical() {
        final long at = sPhysKeyAt;
        return at > 0 && System.currentTimeMillis() - at <= WINDOW_MS;
    }
}
