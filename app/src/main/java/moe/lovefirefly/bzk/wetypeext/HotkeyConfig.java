package moe.lovefirefly.bzk.wetypeext;

import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 快捷键组合键的序列化/解析（App 侧设置页与模块侧共用同一份逻辑）。
 *
 * <p>格式：{@code id:keycode:shift:ctrl:alt} 用 {@code ;} 分隔，例如
 * {@code fullwidth:62:1:0:0}（= Shift+Space）、{@code voice:36:0:0:1}（= Alt+H）。
 * 空串 = 全部用 {@link HotkeyAction} 里的默认值。
 * 旧的四段写法（{@code id:kc:shift:ctrl}）仍能解析，alt 当 0。
 *
 * <p>为什么不存 JSON：只有一个短串，且要经过广播 extras 传递，越简单越不容易出问题。
 */
final class HotkeyConfig {

    private HotkeyConfig() {}

    /** 解析成 id → [keyCode, shift, ctrl, alt]。解析失败/缺项一律回退默认值。 */
    static Map<String, int[]> parse(String raw, Map<String, int[]> out) {
        if (out == null) out = new HashMap<>();
        out.clear();
        if (raw != null && !raw.isEmpty()) {
            for (String part : raw.split(";")) {
                if (part.isEmpty()) continue;
                final String[] f = part.split(":");
                if (f.length < 4) continue;
                try {
                    out.put(f[0], new int[]{
                            Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                            f.length >= 5 ? Integer.parseInt(f[4]) : 0});
                } catch (Throwable ignored) {
                }
            }
        }
        // 缺的条目补默认值
        for (HotkeyAction a : HotkeyAction.values()) {
            out.putIfAbsent(a.id, new int[]{
                    a.defKeyCode, a.defShift ? 1 : 0, a.defCtrl ? 1 : 0, a.defAlt ? 1 : 0});
        }
        return out;
    }

    /** 某个动作当前生效的组合键：[keyCode, shift, ctrl, alt]。 */
    static int[] comboOf(Map<String, int[]> map, HotkeyAction a) {
        final int[] v = map == null ? null : map.get(a.id);
        if (v != null && v.length >= 4) return v;
        return new int[]{a.defKeyCode, a.defShift ? 1 : 0, a.defCtrl ? 1 : 0, a.defAlt ? 1 : 0};
    }

    /** 把"某个动作"改成新组合键后的完整串（设置页录制时用）。 */
    static String withCombo(String raw, HotkeyAction target, int keyCode, boolean shift,
            boolean ctrl, boolean alt) {
        final Map<String, int[]> map = parse(raw, null);
        map.put(target.id, new int[]{keyCode, shift ? 1 : 0, ctrl ? 1 : 0, alt ? 1 : 0});
        return format(map);
    }

    /** 序列化。 */
    static String format(Map<String, int[]> map) {
        final StringBuilder sb = new StringBuilder();
        for (HotkeyAction a : HotkeyAction.values()) {
            final int[] v = comboOf(map, a);
            if (sb.length() > 0) sb.append(';');
            sb.append(a.id).append(':').append(v[0]).append(':').append(v[1]).append(':')
              .append(v[2]).append(':').append(v.length >= 4 ? v[3] : 0);
        }
        return sb.toString();
    }

    /** 组合键的人类可读形式（设置页显示）。 */
    static String describe(int keyCode, boolean shift, boolean ctrl, boolean alt) {
        if (keyCode == 0) return "未设置";
        final StringBuilder sb = new StringBuilder();
        if (ctrl) sb.append("Ctrl+");
        if (shift) sb.append("Shift+");
        if (alt) sb.append("Alt+");
        sb.append(keyName(keyCode));
        return sb.toString();
    }

    private static String keyName(int keyCode) {
        try {
            String n = KeyEvent.keyCodeToString(keyCode);
            if (n.startsWith("KEYCODE_")) n = n.substring("KEYCODE_".length());
            return n;
        } catch (Throwable tr) {
            return "Key" + keyCode;
        }
    }

    /** 修饰键本身不能单独当快捷键。 */
    static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return true;
            default:
                return false;
        }
    }
}
