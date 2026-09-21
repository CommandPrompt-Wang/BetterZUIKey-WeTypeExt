package moe.lovefirefly.bzk.wetypeext;

import android.view.KeyEvent;

/**
 * 快捷键注册表：<b>一处登记所有可配置快捷键</b>。
 *
 * <p>设计意图（用户要求）：新功能要加快捷键时，只在这里加一个条目 + 在
 * {@link Hotkeys} 的 {@code invoke} 里加一个分支；设置页的「快捷键」区会自动出现这一项，
 * 用户也能自己改组合键。**不要**再往代码里写死某个组合键。
 *
 * <p>组合键的配置存在 {@link ExtConfig#KEY_HOTKEYS} 里（字符串，见
 * {@link HotkeyConfig#format()}），空 = 全部用这里的默认值。
 *
 * <p>修饰键只认 Shift / Ctrl / Alt（Meta 键在物理键盘上少用，先不做）。
 */
enum HotkeyAction {

    /**
     * 全角 / 半角切换。仅在 {@link ExtConfig#fullwidthFeature} 打开时生效 ——
     * 功能关着时<b>不吞键</b>（用户口径：关闭功能就该恢复正常）。
     */
    FULLWIDTH_SWITCH("fullwidth", "全角 / 半角切换",
            KeyEvent.KEYCODE_SPACE, true, false),

    // ---- 未来的功能在这里加，例如（TASK 6）：
    // VOICE_INPUT("voice", "语音输入", KeyEvent.KEYCODE_V, false, true),
    // CLIPBOARD("clipboard", "剪贴板", KeyEvent.KEYCODE_V, false, true),
    // EMOJI("emoji", "表情面板", KeyEvent.KEYCODE_E, false, true),
    ;

    /** 配置里的稳定 id（**不要改**，改了用户配置就失效）。 */
    final String id;
    /** 设置页显示名。 */
    final String label;
    /** 默认组合键。 */
    final int defKeyCode;
    final boolean defShift;
    final boolean defCtrl;

    HotkeyAction(String id, String label, int defKeyCode, boolean defShift, boolean defCtrl) {
        this.id = id;
        this.label = label;
        this.defKeyCode = defKeyCode;
        this.defShift = defShift;
        this.defCtrl = defCtrl;
    }
}
