package moe.lovefirefly.bzk.wetypeext;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置页：三个开关，改完即生效（走 {@link ConfigSender} 的显式广播，不用重启微信进程）。
 *
 * <p>每次进入页面都补发一条配置：接收器只在微信进程活着时存在，微信没跑时广播会丢。
 */
public class MainActivity extends Activity {

    private SharedPreferences prefs;

    /** 「全角模式 / 中英文标点」两行的状态显示（由模块回传的镜像广播刷新）。 */
    private Switch fullwidthSwitch;
    private Switch enPunctSwitch;
    /** 最近一次从模块拿到的状态位；null = 还没收到。 */
    private Boolean stateFullwidth;
    private Boolean stateEnPunct;
    private android.content.BroadcastReceiver stateReceiver;

    /** 快捷键区：每个动作一行，点一下进入"按下组合键"录制。 */
    private final java.util.Map<HotkeyAction, TextView> hotkeyRows =
            new java.util.EnumMap<>(HotkeyAction.class);
    private HotkeyAction recording;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(ExtConfig.APP_PREFS, MODE_PRIVATE);
        final ExtConfig cfg = ExtConfig.load(prefs);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        addTitle(root, "微信输入法增强");
        addHint(root, "目标：把中/英语言暴露给系统框架，并只在英文键盘关闭联想。\n"
                + "LSPosed 作用域只需勾「微信输入法」，不需要系统框架。");

        addSwitch(root, "英文键盘不显示联想/补全",
                "英文键盘下清空候选栏：既不出现打字过程中的补全（hello → hellokitty），"
                        + "也不出现上屏一个词之后的下一个词。中文键盘完全不受影响。",
                ExtConfig.KEY_EN_NO_SUGGEST, cfg.enNoSuggest);

        addSwitch(root, "跟随系统语言切换",
                "把框架的 input subtype 变化翻成微信内部的中英切换，"
                        + "这样系统 / BetterZUIKey 的语言切换才能真正带动微信输入法。",
                ExtConfig.KEY_TRANSLATE, cfg.subtypeTranslate);

        addSwitch(root, "严格跟随系统语言",
                "开：每次进入输入框都按系统语言对齐一次（在微信里手动切到英文，换个输入框会被拉回）；"
                        + "关：只响应系统语言真正变化的时刻。",
                ExtConfig.KEY_TRANSLATE_ON_START, cfg.subtypeStrictOnStart);

        addSwitch(root, "微信内切换后回写系统",
                "在微信里用 Ctrl+Shift 或工具栏中英键切了语言后，把系统那边的语言状态也改一致。"
                        + "这样系统与 BetterZUIKey 看到的语言不会和实际脱节。\n"
                        + "本功能不拦按键（只在语言确实变了之后回写），Ctrl+Shift+P 之类的组合键不受影响。",
                ExtConfig.KEY_SYNC_BACK, cfg.syncBackToFramework);

        addSwitch(root, "只认系统语言（严格模式）",
                "拒绝微信自己切语言（Ctrl+Shift / 工具栏中英键），语言只跟着系统走。\n"
                        + "不会拦按键：我们拦的是微信已经判定为「切语言」的那个动作，"
                        + "所以 Ctrl+Shift+P 这类组合键不受影响；符号/数字/手写面板也照常能开。\n"
                        + "注意：打开后 Ctrl+Shift 不再切语言（这正是严格模式的意思）。",
                ExtConfig.KEY_STRICT, cfg.strictFrameworkOnly);

        addSwitch(root, "Shift 键放行（修物理键盘扩选）",
                "微信在硬件键盘模式下会独占 Shift 按下，导致原生输入框以为「没按 Shift」，"
                        + "Shift+方向键退化成普通移动。打开后微信不再独占 Shift（它自己的切语言逻辑不受影响），"
                        + "原生的逐字/按词扩选恢复正常。\n"
                        + "网页输入框本来就不受影响；若某个 App 对单独的 Shift 有反应，把它关掉即可。",
                ExtConfig.KEY_SHIFT_PASSTHRU, cfg.shiftPassThrough);

        addSwitch(root, "智能编号（数字后用半角标点）",
                "数字后面的中文标点自动用半角：1。 → 1.、1） → 1)。"
                        + "判据是「末字符是 。/） 且它前面是数字」，一起上屏或分两次上屏都能命中。",
                ExtConfig.KEY_SMART_NUMBER, cfg.smartNumber);

        enPunctSwitch = addSwitch(root, "中英文标点",
                "允许中文模式下在中英标点之间切换（对齐搜狗/gb 的做法，不是「一律用英文标点」的大开关）："
                        + "物理键盘按 Ctrl+. 切换状态位，切到「英文标点」时中文标点落成 ASCII"
                        + "（，→, 。→. ！→! ？→? ；→; ：→: （）→() 【】→[] “”→\"\" ‘'→''）。\n"
                        + "快捷键可在下面的「快捷键」区改。关掉这个门 = 恢复原生（Ctrl+. 也不吞）。",
                ExtConfig.KEY_EN_PUNCT_FEATURE, cfg.enPunctFeature);

        fullwidthSwitch = addSwitch(root, "全角模式",
                "允许在全角/半角之间切换：物理键盘按 Shift+Space 切状态位，"
                        + "状态为「全角」时把 ASCII **符号**转全角（只动符号，不动字母数字，免得拼音/英文被全角化）。\n"
                        + "快捷键可在下面的「快捷键」区改。关掉这个门 = 完全恢复原生（Shift+Space 也不吞，空格照常）。",
                ExtConfig.KEY_FULLWIDTH_FEATURE, cfg.fullwidthFeature);

        addSwitch(root, "括号/引号自动配对",
                "微信原生行为（做得不错，建议保持打开）：打 （ 自动补出 （） 并把光标放中间；"
                        + "选中文字后打 （ 会自动用括号包起来。\n"
                        + "关掉 = 只上屏你打的那一个字符（选中时用该字符替换选区），"
                        + "在提交层把微信自动补上的那半截拆掉，中文英文、开关即时生效。",
                ExtConfig.KEY_AUTO_PAIR, cfg.autoPair);

        addSwitch(root, "跳过已存在的闭合符号",
                "打 ）、】、” 这类闭字符时，如果光标右边已经就是它（微信刚刚自动补出来的那个），"
                        + "只把光标移过去、不再多插一个。\n"
                        + "只认「微信刚补出来的那一个」，不做任何推导 —— 所以不会误伤你自己敲的括号。",
                ExtConfig.KEY_CLOSE_SKIP, cfg.closeSkip);

        addSwitch(root, "Shift 切换修复",
                "物理键盘上 Shift 参与过组合（Shift+字母打大写、Shift+符号、Shift+方向键扩选）之后，"
                        + "松开 Shift 不再被误判成「Shift 单击切语言」。\n"
                        + "微信原版只在「打字符」那条路上打了标记，方向键等路径会漏，于是松开 Shift 就切了语言；"
                        + "本开关在按键盘这一层把标记补齐。\n"
                        + "想彻底只认系统语言，再开上面的「严格模式」。",
                ExtConfig.KEY_SHIFT_FIX, cfg.shiftSwitchFix);

        addHotkeySection(root);

        addHint(root, "\n切换后立即生效，无需重启微信。\n"
                + "日志标签：BZK-WeTypeExt");

        final ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        registerStateReceiver();

        // 进页面补发一次：微信进程当时没跑的话，这条会在它下次起来前一直缺失。
        // 顺带向模块要一次状态位（全角/半角、中文标点/英文标点）来回填两行的显示。
        ConfigSender.send(this, prefs);
    }

    /** 「快捷键」区：注册表里每个动作一行，点行进入录制（按 Back 取消）。 */
    private void addHotkeySection(LinearLayout root) {
        addTitle(root, "\n快捷键");
        addHint(root, "点一行，然后按下你想用的组合键（Shift / Ctrl / Alt 至少有一个）；"
                + "退格 = 清除这一项，Esc / 返回 = 取消。新功能加的快捷键会自动出现在这里。");
        for (HotkeyAction a : HotkeyAction.values()) {
            final TextView row = new TextView(this);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setPadding(0, dp(12), 0, dp(2));
            row.setOnClickListener(v -> setRecording(a));
            hotkeyRows.put(a, row);
            root.addView(row);
        }
        refreshHotkeyRows();
    }

    /**
     * 进入 / 退出录制。
     *
     * <p>同时通知模块"临时屏蔽热键响应"：否则刚按下的组合键会先把动作跑掉，
     * 而且键被模块吞了、设置页根本录不到（用户实测就是这个现象）。
     */
    private void setRecording(HotkeyAction a) {
        recording = a;
        refreshHotkeyRows();
        ConfigSender.sendRecording(this, a != null);
    }

    private void refreshHotkeyRows() {
        final String raw = prefs.getString(ExtConfig.KEY_HOTKEYS, "");
        final java.util.Map<String, int[]> map = HotkeyConfig.parse(raw, null);
        for (java.util.Map.Entry<HotkeyAction, TextView> e : hotkeyRows.entrySet()) {
            final HotkeyAction a = e.getKey();
            final int[] c = HotkeyConfig.comboOf(map, a);
            final String combo = HotkeyConfig.describe(c[0], c[1] != 0, c[2] != 0,
                    c.length >= 4 && c[3] != 0);
            e.getValue().setText(recording == a
                    ? a.label + "：请按下组合键…（退格清除 / Esc 取消）"
                    : a.label + "：" + combo);
        }
    }

    /**
     * 收模块回传的状态位镜像（{@link BroadcastConfig#ACTION_STATE}，显式指定本包名）。
     *
     * <p>为什么要走广播：状态位住在微信进程自己的 prefs 里，App 物理上读不到（targetSdk 35
     * 的包可见性也让它读不到），只能由模块推过来 —— 与 gb 的做法一致。
     */
    private void registerStateReceiver() {
        try {
            stateReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent intent) {
                    if (intent == null) return;
                    if (intent.hasExtra(BroadcastConfig.EXTRA_ST_FULLWIDTH)) {
                        stateFullwidth = intent.getBooleanExtra(
                                BroadcastConfig.EXTRA_ST_FULLWIDTH, false);
                    }
                    if (intent.hasExtra(BroadcastConfig.EXTRA_ST_EN_PUNCT)) {
                        stateEnPunct = intent.getBooleanExtra(
                                BroadcastConfig.EXTRA_ST_EN_PUNCT, false);
                    }
                    refreshStateText();
                }
            };
            final android.content.IntentFilter f =
                    new android.content.IntentFilter(BroadcastConfig.ACTION_STATE);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(stateReceiver, f);
            }
        } catch (Throwable tr) {
            stateReceiver = null;
        }
    }

    /** 把"当前：全角/半角、英文标点/中文标点"写回两行标题。 */
    private void refreshStateText() {
        if (fullwidthSwitch != null) {
            fullwidthSwitch.setText("全角模式（当前："
                    + (stateFullwidth == null ? "?" : (stateFullwidth ? "全角" : "半角")) + "）");
        }
        if (enPunctSwitch != null) {
            enPunctSwitch.setText("中英文标点（当前："
                    + (stateEnPunct == null ? "?" : (stateEnPunct ? "英文标点" : "中文标点")) + "）");
        }
    }

    @Override
    protected void onDestroy() {
        if (stateReceiver != null) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            stateReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (recording != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                final int kc = event.getKeyCode();
                // 返回 / Esc = 取消录制
                if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
                    setRecording(null);
                    return true;
                }
                // 退格 = 清除当前动作的绑定（设为"未设置"）
                if (kc == KeyEvent.KEYCODE_DEL) {
                    final String cleared = HotkeyConfig.withCombo(
                            prefs.getString(ExtConfig.KEY_HOTKEYS, ""), recording, 0, false, false,
                            false);
                    prefs.edit().putString(ExtConfig.KEY_HOTKEYS, cleared).apply();
                    setRecording(null);
                    ConfigSender.send(this, prefs);
                    return true;
                }
                if (!HotkeyConfig.isModifierKey(kc)) {
                    final int meta = event.getMetaState();
                    final boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
                    final boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
                    final boolean alt = (meta & KeyEvent.META_ALT_ON) != 0;
                    validateAndCommit(kc, shift, ctrl, alt);
                }
            }
            return true;   // 录制期间把按键都吃掉
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 录制的校验顺序（用户口径）：<b>非空？ → 重复？ → 放行</b>。
     *
     * <p>另外对"只有一个 Shift 修饰、而且按的是字母"的组合先弹一次警告 ——
     * 那种组合会把大写字母打不出来（Shift 被当成快捷键吃掉了）。
     */
    private void validateAndCommit(final int kc, final boolean shift, final boolean ctrl,
            final boolean alt) {
        // ① 非空：至少要有一个修饰键
        if (!shift && !ctrl && !alt) return;
        // ② 重复：别的动作已经占了这个组合
        final HotkeyAction dup = findDuplicate(kc, shift, ctrl, alt);
        if (dup != null) {
            ask("警告", "组合键 " + HotkeyConfig.describe(kc, shift, ctrl, alt)
                    + " 已经被「" + dup.label + "」占用。\n要把它改绑到「" + recording.label
                    + "」吗？（对方会变成未设置）", () -> {
                clearCombo(dup);
                commitCombo(kc, shift, ctrl, alt);
            });
            return;
        }
        // ③ 孤立 Shift + 字母：会顶掉大小写
        if (shift && !ctrl && !alt && kc >= KeyEvent.KEYCODE_A && kc <= KeyEvent.KEYCODE_Z) {
            ask("警告", "shift是字母大小写切换按钮，您是否确实要这样做？",
                    () -> commitCombo(kc, shift, ctrl, alt));
            return;
        }
        commitCombo(kc, shift, ctrl, alt);
    }

    private HotkeyAction findDuplicate(int kc, boolean shift, boolean ctrl, boolean alt) {
        final java.util.Map<String, int[]> map =
                HotkeyConfig.parse(prefs.getString(ExtConfig.KEY_HOTKEYS, ""), null);
        for (HotkeyAction a : HotkeyAction.values()) {
            if (a == recording) continue;
            final int[] c = HotkeyConfig.comboOf(map, a);
            if (c[0] != kc) continue;
            if ((c[1] != 0) != shift) continue;
            if ((c[2] != 0) != ctrl) continue;
            if (((c.length >= 4 && c[3] != 0)) != alt) continue;
            return a;
        }
        return null;
    }

    private void clearCombo(HotkeyAction a) {
        final String next = HotkeyConfig.withCombo(
                prefs.getString(ExtConfig.KEY_HOTKEYS, ""), a, 0, false, false, false);
        prefs.edit().putString(ExtConfig.KEY_HOTKEYS, next).apply();
    }

    private void commitCombo(int kc, boolean shift, boolean ctrl, boolean alt) {
        final HotkeyAction target = recording;
        if (target == null) return;
        final String next = HotkeyConfig.withCombo(
                prefs.getString(ExtConfig.KEY_HOTKEYS, ""), target, kc, shift, ctrl, alt);
        prefs.edit().putString(ExtConfig.KEY_HOTKEYS, next).apply();
        setRecording(null);
        ConfigSender.send(this, prefs);
    }

    /** 「是 / 否」二选一弹窗；选「否」= 放弃这次录制。 */
    private void ask(String title, String msg, final Runnable onYes) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("是", (d, w) -> onYes.run())
                    .setNegativeButton("否", (d, w) -> setRecording(null))
                    .setCancelable(false)
                    .show();
        } catch (Throwable tr) {
            onYes.run();   // 弹不出来就别卡住用户
        }
    }

    private Switch addSwitch(LinearLayout root, String title, String desc,
            final String key, boolean def) {
        final Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setPadding(0, dp(12), 0, dp(4));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
                ConfigSender.send(MainActivity.this, prefs);
            }
        });
        root.addView(sw);
        addHint(root, desc);
        return sw;
    }

    private void addTitle(LinearLayout root, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tv.setPadding(0, 0, 0, dp(8));
        root.addView(tv);
    }

    private void addHint(LinearLayout root, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.GRAY);
        tv.setPadding(0, 0, 0, dp(6));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        root.addView(tv);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
