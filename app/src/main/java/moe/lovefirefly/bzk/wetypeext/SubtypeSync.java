package moe.lovefirefly.bzk.wetypeext;

import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.inputmethod.InputMethodSubtype;

/**
 * 反向同步：微信<b>内部</b>切换语言之后，把框架 subtype 回写成一致。
 *
 * <h3>为什么需要</h3>
 * 微信自己切中英（物理键盘 Ctrl+Shift、工具栏中英键）<b>完全不告诉框架</b>
 * —— 实测内部已经 `n0=100`（英文），框架那边还是 `zh-CN`。后果是系统与 BetterZUIKey
 * 看到的语言与真实语言脱节：系统语言切换器的当前项是错的，下一次"切到下一个语言"
 * 也会从错的位置出发。回写之后就双向一致（{@link SubtypeTranslator} 负责正向）。
 *
 * <h3>⚚ 为什么不拦按键（搜狗踩过的坑）</h3>
 * 搜狗组件曾在按键层吞 Ctrl+Shift 来挡它自己切语言，结果**按住 Ctrl 时 Shift 被吞掉**，
 * 用户所有 `Ctrl+Shift+X` 组合键都组不出来（"反复按几次才行"）。
 * 本类<b>一个按键都不碰</b>：只观测"键盘语言确实变了"这个<b>结果</b>
 * （{@link EnAssocGate} 挂在 {@code N.k3} 上的那个钩子），然后回写框架。
 * 所以 Ctrl+Shift、Ctrl+Shift+P、Ctrl+Shift+任意键都不受影响。
 *
 * <h3>怎么回写</h3>
 * 用 IME 公开 API {@code InputMethodService.switchToNextInputMethod(true)}
 * （只在本输入法内轮转）。微信现在正好两个 subtype（zh-CN / en-US），轮一次就到位；
 * 仍然写成"读完再决定、最多试 3 次"的循环，且每次之间等一会儿让框架落定。
 * （{@code setCurrentInputMethodSubtype} 对 IME 自身不可用 —— 搜狗那边实测过。）
 *
 * <h3>不会打架</h3>
 * 回写会让框架回调 {@link SubtypeTranslator}；那个方向的判据是"内部键盘是否已是目标
 * 语言"，此刻必然已经一致 ⇒ 直接跳过，不形成来回切。
 */
final class SubtypeSync {

    private static final String TAG = BridgeHook.TAG;

    /** 一次回写尝试的总步数上限（两个 subtype 通常 1 步就够，留余量）。 */
    private static final int MAX_STEPS = 3;
    /** 每步之间等框架落定的时间。 */
    private static final long STEP_DELAY_MS = 250L;

    /** 同一时刻只允许一个回写流程；期间的请求只记最新目标，由流程自己收敛。 */
    private static volatile boolean sBusy;
    private static volatile boolean sPendingEn;
    private static volatile boolean sHasPending;

    private SubtypeSync() {}

    /**
     * 内部键盘变化后调用（来自 {@code N.k3} 钩子）。<b>绝不阻塞调用线程</b>。
     *
     * @param keyboardValue 切换后的键盘值（0/1/5/6 = 中文，100 = 英文，其余是面板）
     */
    static void onKeyboardChanged(int keyboardValue) {
        if (!ExtConfig.get().syncBackToFramework) return;

        final Boolean isZh = WeTypeInternals.isChineseKeyboard(keyboardValue);
        if (isZh == null) return;
        final boolean wantEn;
        if (isZh.booleanValue()) {
            wantEn = false;
        } else if (keyboardValue == WeTypeInternals.KB_ENGLISH_QWERTY) {
            wantEn = true;
        } else {
            return; // 数字/符号/手写等面板：与语言无关，不回写
        }

        if (sBusy) {
            sPendingEn = wantEn;
            sHasPending = true;
            return;
        }
        final Thread t = new Thread(() -> run(wantEn), "wetype-subtype-sync");
        t.setDaemon(true);
        t.start();
    }

    private static void run(boolean wantEn) {
        sBusy = true;
        try {
            boolean target = wantEn;
            while (true) {
                if (alignOnce(target)) return;          // 已一致，收工
                if (!sHasPending) return;               // 没有新的目标，收工
                sHasPending = false;
                target = sPendingEn;                    // 用户又切了，按最新的来
            }
        } finally {
            sBusy = false;
        }
    }

    /** @return true = 框架已经和目标一致（或无法判断，放弃）。 */
    private static boolean alignOnce(boolean wantEn) {
        for (int step = 0; step < MAX_STEPS; step++) {
            final InputMethodSubtype st = ServiceProbe.currentSubtype();
            final String locale = st == null ? null : st.getLocale();
            if (locale == null || locale.isEmpty()) {
                // 注入前框架给的是空 locale 的 dummy：没有语言信息，别乱推
                Log.i(TAG, "syncBack: 框架 subtype 无 locale，放弃");
                return true;
            }
            final boolean fwEn = locale.startsWith("en");
            final boolean fwZh = locale.startsWith("zh");
            if ((wantEn && fwEn) || (!wantEn && fwZh)) {
                Log.i(TAG, "syncBack: 已一致 (want=" + (wantEn ? "en" : "zh")
                        + " fw=" + locale + ")");
                return true;
            }
            if (!switchNext()) {
                Log.w(TAG, "syncBack: switchToNextInputMethod 不可用，放弃");
                return true;
            }
            Log.i(TAG, "syncBack: step " + (step + 1) + " push (fw=" + locale
                    + " -> want=" + (wantEn ? "en" : "zh") + ")");
            sleep(STEP_DELAY_MS);
        }
        Log.w(TAG, "syncBack: " + MAX_STEPS + " 步仍未一致，放弃");
        return true;
    }

    private static boolean switchNext() {
        final Object svc = ServiceProbe.service();
        if (!(svc instanceof InputMethodService)) return false;
        try {
            // true = 只在本输入法内轮转，别切到别的输入法
            return ((InputMethodService) svc).switchToNextInputMethod(true);
        } catch (Throwable tr) {
            Log.w(TAG, "switchToNextInputMethod failed: " + tr);
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
