package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

/**
 * 物理键热键路由 + "输入来源"标记。
 *
 * <p>挂在微信的物理键分发入口 {@code hardware/d.n(int, KeyEvent)}（按下）/
 * {@code o(...)}（抬起）—— 物理键进微信的必经之路，软键盘不走。
 *
 * <h3>快捷键是<b>可配置</b>的</h3>
 * 组合键不写死在代码里：登记在 {@link HotkeyAction}，用户配置存在
 * {@link ExtConfig#KEY_HOTKEYS}（格式见 {@link HotkeyConfig}），设置页的「快捷键」区可改。
 * 新功能要加快捷键 ⇒ 在 {@link HotkeyAction} 加一个条目 + 在 {@link #invoke} 加一个分支。
 *
 * <p>组合键按<b>完整组合</b>判定：键码 + Shift + Ctrl + Alt 四项全等才算命中
 * （所以 Ctrl+Shift+P 这类别人的组合键一个都不受影响）。
 *
 * <p>吞键用 {@code return Boolean.TRUE}（不 proceed），并弹一行 {@link Banner} 提示 ——
 * 不用 Toast（会被系统按通知设置拦掉，搜狗那边实测过）。
 *
 * <h3>同时维护 {@link InputSource} 的时间标记</h3>
 * 提交文本管线要判断"这次提交是不是物理键盘来的"，标记就打在这个钩子里 ——
 * 避免对同一个方法挂两个钩子（链式顺序不可控）。
 */
final class Hotkeys {

    private static final String TAG = BridgeHook.TAG;

    /**
     * 诊断（排查完置回 false）：把物理键路径上每个键事件的<b>来源信息</b>打出来。
     *
     * <p>要回答的问题：钩子收到的键，怎么区分「真物理键盘」和「系统注入」——
     * ZUXOS 的 {@code Win+L} / {@code Alt+Shift} / {@code Meta} 等都会 {@code injectKeyEvent}，
     * 它们走的是同一条路。看 {@code dev} 与 {@code src} 是否可分。
     */
    private static final boolean DEV_KEY_SOURCE_TRACE = false;

    private static volatile boolean sInstalled;

    /**
     * 设置页正在录制快捷键 —— 这期间<b>一个热键都不响应、也不吞键</b>，
     * 否则用户刚按下组合键，动作就先跑掉了（还会把按键吃掉让设置页录不到）。
     */
    private static volatile boolean sRecording;

    static void setRecording(boolean on) {
        sRecording = on;
    }

    /** 解析后的组合键缓存（按配置串做键，改配置自动重解析）。 */
    private static volatile String sCachedRaw;
    private static volatile Map<String, int[]> sCombos;

    private Hotkeys() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        // ⚠️ 挂 <b>服务实例类</b>的 onKeyDown/onKeyUp，不是 hardware/d.n/o：
        //   微信的 onKeyDown 里有一道闸门 ——
        //     if (!keyboardShow && !(A–Z 触发的硬件模式)) return false;
        //   非字母键（; = . 这类）在"还没进硬件模式"时会被它直接丢掉，键漏给宿主
        //   （用户实测：没打字母时按 Alt+; 直接打出分号，输入法全程没收到）。
        //   挂在闸门之前才收得全；同时 InputSource 标记 / ShiftFix 也在这里喂。
        try {
            final Class<?> svc = Class.forName(
                    "com.tencent.wetype.plugin.hld.WxHldService", false, cl);
            hook(module, svc, "onKeyDown", true);
            hook(module, svc, "onKeyUp", false);
            Log.i(TAG, "Hotkeys: hooked WxHldService.onKeyDown / onKeyUp（闸门之前）");
        } catch (Throwable tr) {
            Log.w(TAG, "Hotkeys: install failed: " + tr);
        }
    }

    private static void hook(XposedModule module, Class<?> d, String name, final boolean down) {
        try {
            final Method m = d.getDeclaredMethod(name, int.class, KeyEvent.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                if (DEV_KEY_SOURCE_TRACE) {
                    try {
                        final Object evT = chain.getArg(1);
                        if (evT instanceof KeyEvent) {
                            final KeyEvent e = (KeyEvent) evT;
                            Log.i(TAG, "keysrc " + (down ? "down " : "up   ")
                                    + " kc=" + e.getKeyCode()
                                    + " dev=" + e.getDeviceId()
                                    + " src=0x" + Integer.toHexString(e.getSource())
                                    + " scan=" + e.getScanCode()
                                    + " flags=0x" + Integer.toHexString(e.getFlags())
                                    + " meta=0x" + Integer.toHexString(e.getMetaState())
                                    + " uni=" + e.getUnicodeChar()
                                    + " rep=" + e.getRepeatCount());
                        }
                    } catch (Throwable tr) {
                        Log.w(TAG, "keysrc err: " + tr);
                    }
                }
                // 任何物理键都记一笔（带字符，供 、 的 / \ 消歧）
                try {
                    final Object ev = chain.getArg(1);
                    final int uc = ev instanceof KeyEvent
                            ? ((KeyEvent) ev).getUnicodeChar() : 0;
                    InputSource.markPhysical(uc > 0 ? (char) uc : (char) 0);
                    // 更宽松的键盘识别：够格的键记一笔，等 hardware/d.b 那边借它进硬件模式
                    if (ev instanceof KeyEvent) KbdDetect.noteKey((KeyEvent) ev);
                } catch (Throwable tr) {
                    InputSource.markPhysical();
                }
                try {
                    // Shift 切换修复：按键盘这一层记"Shift 参与过组合"
                    final Object kcA = chain.getArg(0);
                    final Object evA = chain.getArg(1);
                    if (kcA instanceof Integer && evA instanceof KeyEvent) {
                        ShiftFix.noteKey((Integer) kcA, (KeyEvent) evA, down);
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "Hotkeys: shiftFix note err: " + tr);
                }
                try {
                    // 严格模式：Shift 是"物理键盘切语言"那条路的必经点，记一笔给 SubtypeGuard
                    // （它据此把物理键盘发起的切换与软键盘中英键区分开）
                    final Object kcP = chain.getArg(0);
                    if (kcP instanceof Integer) {
                        final int kc = ((Integer) kcP).intValue();
                        if (kc == KeyEvent.KEYCODE_SHIFT_LEFT
                                || kc == KeyEvent.KEYCODE_SHIFT_RIGHT) {
                            SubtypeGuard.notePhysicalShift();
                        }
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "Hotkeys: physical shift note err: " + tr);
                }
                if (route(chain, down)) return Boolean.TRUE;
                return chain.proceed();
            });
        } catch (Throwable tr) {
            Log.w(TAG, "Hotkeys: hook " + name + " failed: " + tr);
        }
    }

    private static Map<String, int[]> combos() {
        final String raw = ExtConfig.get().hotkeys == null ? "" : ExtConfig.get().hotkeys;
        Map<String, int[]> m = sCombos;
        if (m == null || !raw.equals(sCachedRaw)) {
            m = HotkeyConfig.parse(raw, null);
            sCombos = m;
            sCachedRaw = raw;
        }
        return m;
    }

    /** @return true = 已处理（吞掉这个键） */
    private static boolean route(io.github.libxposed.api.XposedInterface.Chain chain,
            boolean down) {
        try {
            if (sRecording) return false;    // 录制中：让路，不吞键也不动作
            final Object kcArg = chain.getArg(0);
            final Object evArg = chain.getArg(1);
            if (!(kcArg instanceof Integer) || !(evArg instanceof KeyEvent)) return false;
            final int kc = (Integer) kcArg;
            final KeyEvent ev = (KeyEvent) evArg;
            final int meta = ev.getMetaState();
            final boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
            final boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
            final boolean alt = (meta & KeyEvent.META_ALT_ON) != 0;

            final Map<String, int[]> map = combos();
            for (HotkeyAction a : HotkeyAction.values()) {
                final int[] c = HotkeyConfig.comboOf(map, a);
                if (c[0] != kc) continue;
                // 保底（用户口径）：单个键、一个修饰都没有的配置**一律忽略**
                // —— 就算配置被强行改成那样（手改 prefs / 广播塞值），也永不当快捷键，
                //    否则一个裸字母会被吞掉，整片打字都废。
                if (c[1] == 0 && c[2] == 0 && (c.length < 4 || c[3] == 0)) continue;
                if ((c[1] != 0) != shift) continue;
                if ((c[2] != 0) != ctrl) continue;
                if ((c.length >= 4 && c[3] != 0) != alt) continue;
                if (invoke(a, ev, down)) return true;
            }
            return false;
        } catch (Throwable tr) {
            Log.w(TAG, "Hotkeys.route err: " + tr);
            return false;
        }
    }

    /**
     * 面板类动作（语音 / 表情 / 剪贴板）的<b>正确顺序</b>。
     *
     * <h3>为什么顺序不能反（2026-09-21 真机实测）</h3>
     * <ul>
     *   <li>窗口<b>已经显示</b>时直接切面板 ⇒ 面板正常画出来（截屏验证过：表情网格、
     *       常用语/剪贴板页签都在）；</li>
     *   <li>窗口<b>没显示</b>时先切面板、再 {@code requestShowSelf} ⇒ 微信在
     *       "窗口显示"这一步会把键盘<b>恢复成默认</b>（实测 {@code kb: 504 -> 1}），
     *       刚切好的面板被顶掉 —— 现象就是"按了没反应 / 键盘一闪"。</li>
     * </ul>
     * 所以：先请窗口出来，等它稳定（{@code onWindowShown} 那套跑完）再切面板。
     */
    /**
     * 面板类动作（语音 / 表情 / 剪贴板）。
     *
     * <h3>为什么不在这里 requestShowSelf（2026-09-21 真机结论）</h3>
     * 面板要画在输入法窗口里，所以"窗口得是显示状态"。但实测：
     * <ul>
     *   <li>窗口已经在显示（普通 EditText 宿主 / 微信自己的设置页）：直接切面板即可，
     *       截屏验证过 emoji 网格与「剪贴板/常用语」页签；</li>
     *   <li>窗口没显示、我们主动 {@code requestShowSelf}：宿主可以拒绝 ——
     *       Edge/Chromium 会在 {@code PHASE_CLIENT_APPLY_ANIMATION} 直接
     *       {@code onCancelled}（flags 0/1/2 都试过，全部被取消），窗口停在 0×0，
     *       面板再切也画不出来。此时我们<b>什么也做不了</b>，只能静默（宿主自己按物理键盘
     *       模式收着软键盘，微信原生面板同样出不来）。</li>
     * </ul>
     * 另外微信自己的 {@code WxHldService.onKeyDown} 在"物理键 + 键盘没显示"时本来就会
     * {@code requestShowSelf(0)}（见其源码），所以这一刀轮不到我们补。
     */
    private static void runPanelAction(final HotkeyAction a) {
        // 键被我们在闸门之前吞了 ⇒ 微信自己那句 requestShowSelf(0) 也不会跑，
        // 于是动作在后台发生、窗口不出现。这里替它补上（顺序仍是"先请窗口、再动作"）。
        final Object svc = ServiceProbe.service();
        if (!(svc instanceof android.inputmethodservice.InputMethodService)) {
            doPanelAction(a);
            return;
        }
        final android.inputmethodservice.InputMethodService ims =
                (android.inputmethodservice.InputMethodService) svc;
        // 结束语音那一下 UI 本来就在，不用再请
        final boolean skipShow = a == HotkeyAction.VOICE_INPUT && WeTypeInternals.voiceActive();
        if (skipShow) {
            doPanelAction(a);
            return;
        }
        try {
            Log.i(TAG, "panel " + a.id + ": shown=" + ims.isInputViewShown()
                    + " -> requestShowSelf(0)");
            ims.requestShowSelf(0);
        } catch (Throwable tr) {
            Log.w(TAG, "panel " + a.id + ": requestShowSelf failed: " + tr);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                Log.i(TAG, "panel " + a.id + ": after show shown=" + ims.isInputViewShown());
            } catch (Throwable ignored) {
            }
            doPanelAction(a);
        }, 350L);
    }

    /** 真正执行面板动作（此时窗口应已显示）。 */
    private static void doPanelAction(HotkeyAction a) {
        final boolean ok;
        final String what;
        if (a == HotkeyAction.VOICE_INPUT) {
            // 开关式：正在语音里就结束，否则开始
            final boolean active = WeTypeInternals.voiceActive();
            Log.i(TAG, "hotkey voice: " + WeTypeInternals.voiceState() + " -> active=" + active);
            if (active) {
                what = "结束语音输入";
                ok = WeTypeInternals.endVoiceInput();
            } else {
                what = "语音输入";
                ok = WeTypeInternals.fireFunction(WeTypeInternals.FN_VOICE);
            }
        } else if (a == HotkeyAction.EMOJI) {
            what = "表情";
            ok = WeTypeInternals.fireFunction(WeTypeInternals.FN_EMOJI);
        } else if (a == HotkeyAction.PHRASE) {
            what = "常用语";
            ok = WeTypeInternals.openClipboardPanel(1);
        } else {
            what = "剪贴板";
            ok = WeTypeInternals.openClipboardPanel(0);
        }
        Log.i(TAG, "hotkey " + a.id + " -> " + what + " ok=" + ok);
        // 这四个动作本身就会弹出可见窗口 ⇒ 成功时不弹提示（用户口径：
        // 全角/中英标点要弹是因为它们的效果不是立即可见）；
        // 只有"入口不可用"这种失败才提示一下。
        if (!ok) Banner.show(what + "：入口不可用");
    }

    /**
     * 把输入法窗口<b>真正显示出来</b>。
     *
     * <p>为什么需要：物理键盘模式下微信把软键盘收着，界面上只有候选条，所以
     * "切到某个面板"（emoji / 常用语 / 语音）内部状态明明变了（{@code switchKeyboard -> 504}），
     * 用户却什么都看不到 —— 得再请系统把输入法窗口显示出来，那一屏才会画出来。
     *
     * <p>延后一点发：面板切换走的是主线程协程，紧接着调会被它自己的切换盖掉。
     */
    private static void showSelfSoon(final String what) {
        try {
            final Object svc = ServiceProbe.service();
            if (!(svc instanceof android.inputmethodservice.InputMethodService)) return;
            final android.inputmethodservice.InputMethodService ims =
                    (android.inputmethodservice.InputMethodService) svc;
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            // 试三次：收起软键盘/切面板都可能有延迟，一次请求容易被后面的收起盖掉
            for (long delay : new long[]{120L, 500L, 1000L}) {
                h.postDelayed(() -> {
                    try {
                        ims.requestShowSelf(0);
                        Log.i(TAG, "showSelf(" + what + "): requestShowSelf(0) shown="
                                + ims.isInputViewShown());
                    } catch (Throwable tr) {
                        Log.w(TAG, "showSelf failed: " + tr);
                    }
                }, delay);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "showSelfSoon err: " + tr);
        }
    }

    /** 执行动作。@return true = 吞键（false = 放行，例如功能开关关着） */
    private static boolean invoke(HotkeyAction a, KeyEvent ev, boolean down) {
        switch (a) {
            case FULLWIDTH_SWITCH: {
                // 功能开关关着 ⇒ 一个字节都不碰，空格照常（用户口径：关闭功能就该恢复正常）
                if (!ExtConfig.get().fullwidthFeature) return false;
                if (down && ev.getRepeatCount() == 0) {
                    final android.content.Context ctx = WeTypeInternals.appContext();
                    final boolean on = !PunctState.fullwidth();
                    PunctState.setFullwidth(ctx, on);
                    Log.i(TAG, "hotkey " + a.id + " -> fullwidth=" + on);
                    Banner.show("全角模式：" + (on ? "开" : "关"));
                }
                return true;
            }
            case PUNCT_SWITCH: {
                // 功能门关着 ⇒ 一个字节都不碰（对齐 gb：门只管"允不允许切"）
                if (!ExtConfig.get().enPunctFeature) return false;
                if (down && ev.getRepeatCount() == 0) {
                    final android.content.Context ctx = WeTypeInternals.appContext();
                    final boolean en = !PunctState.enPunct();
                    PunctState.setEnPunct(ctx, en);
                    Log.i(TAG, "hotkey " + a.id + " -> enPunct=" + en);
                    Banner.show("中英文标点：" + (en ? "英文标点" : "中文标点"));
                }
                return true;
            }
            // ---- TASK 6：微信功能入口（都"吞键"，否则那个字母会跟着上屏）----
            case VOICE_INPUT:
            case EMOJI:
            case CLIPBOARD:
            case PHRASE: {
                if (down && ev.getRepeatCount() == 0) runPanelAction(a);
                return true;
            }
            // ---- 未来的动作在这里加分支（对应 HotkeyAction 里的条目）----
            default:
                return false;
        }
    }
}
