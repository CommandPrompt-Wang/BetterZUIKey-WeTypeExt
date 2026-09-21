package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Field;

/**
 * Shift 切换修复：<b>Shift 参与过组合之后松开，不再被误判成"Shift 单击切语言"</b>。
 *
 * <h3>微信原版怎么判的</h3>
 * <pre>
 *   hardware/d.k(isKeyUp)  // Shift 专用
 *       isKeyUp &amp;&amp; !isShiftKeyEventConsumed → 切语言（Ctrl+Shift 那条 key.d.O(7) / g.c→N.r3）
 *       !isKeyUp                            → isShiftKeyEventConsumed = false   // 按下时清零
 *   hardware/d.p(keyCode,event)             // 出字符那条路
 *       if (event.isShiftPressed()) isShiftKeyEventConsumed = true;             // 只在这里补标记
 * </pre>
 * 也就是说"Shift+某键"这件事<b>只有出字符那条路会打标记</b>；方向键、以及若干不进
 * {@code p()} 的路径（引擎未就绪时 {@code j()} 直接返回、数字键被 {@code g.e()} 拦下等）
 * 都不打 ⇒ 松开 Shift 时标记还是 false ⇒ <b>语言被误切</b>（真机日志：Shift+方向键扩选之后
 * 内部真的切到了英文）。
 *
 * <h3>本类怎么补</h3>
 * 不看微信的字符逻辑，直接在<b>按键层</b>记状态（{@code hardware/d.n/o} 上已经有一个热键钩子，
 * 顺手喂进来，不额外挂方法）：
 * <ul>
 *   <li>Shift 按下 → 清"组合"标记；</li>
 *   <li>任何别的键按下时 Shift 是按下状态 → 置"组合"标记；</li>
 *   <li>Shift 抬起（{@code k(true)}）<b>之前</b>，如果标记为真，就把微信那个
 *       {@code isShiftKeyEventConsumed} 置 true —— 于是 {@code k()} 自己就不会切语言了。</li>
 * </ul>
 * 逻辑一点没改，只是把微信漏掉的标记补齐；开关关掉即完全不碰（默认开）。
 *
 * <h3>它和严格模式的分工</h3>
 * 本开关只修"<b>误判</b>"（组合键不该切却切了）；严格模式管的是"<b>该不该允许微信自己切语言</b>"。
 * 两者独立，互不依赖。
 */
final class ShiftFix {

    private static final String TAG = BridgeHook.TAG;

    /** 微信 hardware/d 里那个私有静态标记。 */
    private static volatile Field sFlag;
    /** 本次 Shift 按住期间，是否按过别的键。 */
    private static volatile boolean sCombo;
    private static volatile long sLastLog;

    private ShiftFix() {}

    /**
     * 反射拿那个标记。
     *
     * <p>⚠️ 别按名字找：{@code isShiftKeyEventConsumed} 这个名字只存在于 Kotlin
     * {@code @Metadata} 里（jadx 是按元数据反解出来的），dex 里的真实字段名是混淆过的
     * （本版本 = {@code b}，实测 {@code NoSuchFieldException} 踩过）。
     * 所以按<b>结构</b>找：{@code hardware/d} 里唯一的 {@code static boolean}。
     */
    static void install(ClassLoader cl) {
        try {
            final Class<?> d = Class.forName(
                    "com.tencent.wetype.plugin.hld.hardware.d", false, cl);
            Field found = null;
            final StringBuilder all = new StringBuilder();
            for (Field f : d.getDeclaredFields()) {
                final boolean stat = java.lang.reflect.Modifier.isStatic(f.getModifiers());
                all.append('\n').append(stat ? "static " : "").append(f.getType().getName())
                        .append(' ').append(f.getName());
                if (stat && f.getType() == boolean.class) {
                    if (found != null) {
                        Log.w(TAG, "ShiftFix: 不止一个 static boolean，放弃（" + all + "）");
                        return;
                    }
                    found = f;
                }
            }
            if (found == null) {
                Log.w(TAG, "ShiftFix: 找不到 static boolean 标记（" + all + "）");
                return;
            }
            found.setAccessible(true);
            sFlag = found;
            Log.i(TAG, "ShiftFix: 标记字段 = " + found.getName() + "（hardware/d 唯一 static boolean）");
        } catch (Throwable tr) {
            Log.w(TAG, "ShiftFix: 反射失败（修复将不可用）: " + tr);
        }
    }

    /** 由 {@link Hotkeys} 的硬件键钩子调用（每次物理键 n/o 都进这里）。 */
    static void noteKey(int keyCode, KeyEvent ev, boolean down) {
        try {
            if (!down || ev == null) return;
            if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
                sCombo = false;              // 新一轮 Shift：先清
                return;
            }
            if (ev.isShiftPressed()) {
                if (!sCombo) {
                    final long now = System.currentTimeMillis();
                    if (now - sLastLog > 500) {
                        sLastLog = now;
                        Log.i(TAG, "shiftFix: 记下 Shift 组合 kc=" + keyCode);
                    }
                }
                sCombo = true;
            }
        } catch (Throwable tr) {
            Log.w(TAG, "shiftFix noteKey err: " + tr);
        }
    }

    /** 由 {@link ShiftPassthrough} 在 {@code k()} <b>之前</b>调用。 */
    static void beforeShiftKeyUp() {
        try {
            final boolean combo = sCombo;
            sCombo = false;
            if (!combo) return;
            if (!ExtConfig.get().shiftSwitchFix) return;
            final Field f = sFlag;
            if (f == null) return;
            f.setBoolean(null, true);        // 补上微信漏掉的标记 ⇒ k() 不再切语言
            final long now = System.currentTimeMillis();
            if (now - sLastLog > 300) {
                sLastLog = now;
                Log.i(TAG, "shiftFix: 补齐标记，Shift 松开不切语言");
            }
        } catch (Throwable tr) {
            Log.w(TAG, "shiftFix beforeShiftKeyUp err: " + tr);
        }
    }

    /** 只给诊断用：当前是否记着"Shift 参与过组合"。 */
    static boolean comboPending() {
        return sCombo;
    }

    /** 供未来复用：把标记写回 false（例如我们自己要放行一次 Shift 单击）。 */
    @SuppressWarnings("unused")
    static void clearFlag() {
        try {
            final Field f = sFlag;
            if (f != null) f.setBoolean(null, false);
        } catch (Throwable ignored) {
        }
    }
}
