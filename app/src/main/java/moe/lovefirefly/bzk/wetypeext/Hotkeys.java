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
 * <p>吞键用 {@code return Boolean.TRUE}（不 proceed），并弹一行 {@link Banner} 提示 ——
 * 不用 Toast（会被系统按通知设置拦掉，搜狗那边实测过）。
 *
 * <h3>同时维护 {@link InputSource} 的时间标记</h3>
 * 提交文本管线要判断"这次提交是不是物理键盘来的"，标记就打在这个钩子里 ——
 * 避免对同一个方法挂两个钩子（链式顺序不可控）。
 */
final class Hotkeys {

    private static final String TAG = BridgeHook.TAG;

    private static volatile boolean sInstalled;

    /** 解析后的组合键缓存（按配置串做键，改配置自动重解析）。 */
    private static volatile String sCachedRaw;
    private static volatile Map<String, int[]> sCombos;

    private Hotkeys() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            final Class<?> d = Class.forName(
                    "com.tencent.wetype.plugin.hld.hardware.d", false, cl);
            hook(module, d, "n", true);
            hook(module, d, "o", false);
            Log.i(TAG, "Hotkeys: hooked hardware.d.n / o");
        } catch (Throwable tr) {
            Log.w(TAG, "Hotkeys: install failed: " + tr);
        }
    }

    private static void hook(XposedModule module, Class<?> d, String name, final boolean down) {
        try {
            final Method m = d.getDeclaredMethod(name, int.class, KeyEvent.class);
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                InputSource.markPhysical();          // 任何物理键都记一笔
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
            final Object kcArg = chain.getArg(0);
            final Object evArg = chain.getArg(1);
            if (!(kcArg instanceof Integer) || !(evArg instanceof KeyEvent)) return false;
            final int kc = (Integer) kcArg;
            final KeyEvent ev = (KeyEvent) evArg;
            final int meta = ev.getMetaState();
            final boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
            final boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
            final boolean alt = (meta & KeyEvent.META_ALT_ON) != 0;
            if (alt) return false;   // 带 Alt 的组合不参与（先不做）

            final Map<String, int[]> map = combos();
            for (HotkeyAction a : HotkeyAction.values()) {
                final int[] c = HotkeyConfig.comboOf(map, a);
                if (c[0] != kc) continue;
                if ((c[1] != 0) != shift) continue;
                if ((c[2] != 0) != ctrl) continue;
                if (invoke(a, ev, down)) return true;
            }
            return false;
        } catch (Throwable tr) {
            Log.w(TAG, "Hotkeys.route err: " + tr);
            return false;
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
            // ---- 未来的动作在这里加分支（对应 HotkeyAction 里的条目）----
            default:
                return false;
        }
    }
}
