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

    /** 快捷键区：每个动作一行 = 左标签 + 右组合键按钮（点按钮开弹窗改键）。 */
    private final java.util.Map<HotkeyAction, android.widget.Button> hotkeyButtons =
            new java.util.EnumMap<>(HotkeyAction.class);

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

        addSpinner(root, "原样输出斜杠",
                "微信原生把物理键盘的 / 和 \\ 都打成「、」。这里挑一个键原样输出"
                        + "（对齐搜狗 OEM Ext 的同名设置）：\n"
                        + "关 = 保持原生（两个键都出 、）；选「原样输出 /」= 按 / 出 /，按 \\ 仍出 、"
                        + "（反之亦然）。\n"
                        + "命中的那一格不再走「中英标点」层；全角态下它照样会被全角化（/ → ／）。",
                ExtConfig.KEY_SLASH_MODE,
                new String[]{"关", "原样输出 /", "原样输出 \\"},
                new int[]{0, 1, 2}, ExtConfig.DEF_SLASH_MODE);

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

    /** 「快捷键」区：每行 = 左标签 + 右组合键按钮；点按钮开「设置快捷键」弹窗。 */
    private void addHotkeySection(LinearLayout root) {
        addTitle(root, "\n快捷键");
        addHint(root, "点右边的按钮改键：弹窗里直接按组合键（至少要有一个修饰键），"
                + "退格 = 清除，Esc / 取消 = 放弃，确定 = 保存。");
        for (HotkeyAction a : HotkeyAction.values()) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            final TextView label = new TextView(this);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(lp);
            label.setText(a.label);
            row.addView(label);

            final android.widget.Button btn = new android.widget.Button(this);
            btn.setAllCaps(false);          // Android 默认给按钮文本 toUpperCase，这里要原样
            btn.setOnClickListener(v -> openHotkeyDialog(a));
            hotkeyButtons.put(a, btn);
            row.addView(btn);

            root.addView(row);
        }
        refreshHotkeyRows();
    }

    private void refreshHotkeyRows() {
        final java.util.Map<String, int[]> map = HotkeyConfig.parse(
                prefs.getString(ExtConfig.KEY_HOTKEYS, ""), null);
        for (java.util.Map.Entry<HotkeyAction, android.widget.Button> e : hotkeyButtons.entrySet()) {
            final int[] c = HotkeyConfig.comboOf(map, e.getKey());
            e.getValue().setText(HotkeyConfig.describe(c[0], c[1] != 0, c[2] != 0,
                    c.length >= 4 && c[3] != 0));
        }
    }

    /**
     * 「设置快捷键」弹窗：标题 + 提示 + 一个只用来捕获按键的文本框 + 取消/确定。
     *
     * <p>弹窗开着的时候会通知模块<b>临时不响应热键</b>（否则刚按下的组合键会先把动作跑掉，
     * 键还被吞掉、这里根本录不到）。
     */
    private void openHotkeyDialog(final HotkeyAction a) {
        final int[] cur = HotkeyConfig.comboOf(HotkeyConfig.parse(
                prefs.getString(ExtConfig.KEY_HOTKEYS, ""), null), a);
        final int[] combo = new int[]{cur[0], cur[1], cur[2], cur.length >= 4 ? cur[3] : 0};

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView hint = new TextView(this);
        hint.setText("使用 Bksp 删除，点击取消或按 Esc 放弃");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(Color.GRAY);
        box.addView(hint);

        final android.widget.EditText field = new android.widget.EditText(this) {
            @Override
            public boolean onKeyDown(int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    return false;              // 交给弹窗：Esc = 放弃
                }
                if (keyCode == KeyEvent.KEYCODE_DEL) {     // Bksp = 清除
                    combo[0] = combo[1] = combo[2] = combo[3] = 0;
                    setText("未设置");
                    return true;
                }
                if (HotkeyConfig.isModifierKey(keyCode)) return true;   // 修饰键本身不算
                final int meta = event.getMetaState();
                final boolean hasMod = (meta & (KeyEvent.META_SHIFT_ON | KeyEvent.META_CTRL_ON
                        | KeyEvent.META_ALT_ON)) != 0;
                if (!hasMod) return true;   // 单个键（没有修饰）：直接拒绝，不弹任何东西、也不改显示
                combo[0] = keyCode;
                combo[1] = (meta & KeyEvent.META_SHIFT_ON) != 0 ? 1 : 0;
                combo[2] = (meta & KeyEvent.META_CTRL_ON) != 0 ? 1 : 0;
                combo[3] = (meta & KeyEvent.META_ALT_ON) != 0 ? 1 : 0;
                setText(HotkeyConfig.describe(combo[0], combo[1] != 0, combo[2] != 0,
                        combo[3] != 0));
                return true;
            }
        };
        field.setSingleLine(true);
        field.setKeyListener(null);          // 不接收文本，只捕获按键
        field.setCursorVisible(false);
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        field.setText(HotkeyConfig.describe(combo[0], combo[1] != 0, combo[2] != 0,
                combo[3] != 0));
        final LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        field.setLayoutParams(flp);
        box.addView(field);

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("设置快捷键")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> applyCombo(a, combo))
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", (d, w) -> {
                    // 填回该动作的出厂组合键，再由「确定」保存（不直接落盘，免得手滑）
                    combo[0] = a.defKeyCode;
                    combo[1] = a.defShift ? 1 : 0;
                    combo[2] = a.defCtrl ? 1 : 0;
                    combo[3] = a.defAlt ? 1 : 0;
                    field.setText(HotkeyConfig.describe(combo[0], combo[1] != 0, combo[2] != 0,
                            combo[3] != 0));
                })
                .create();
        dlg.setOnDismissListener(d -> ConfigSender.sendRecording(this, false));
        dlg.show();
        // 校验没过时**不关弹窗**（默认点按钮就会 dismiss，所以自己接管一下）
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (applyCombo(a, combo)) dlg.dismiss();
        });
        ConfigSender.sendRecording(this, true);
        field.requestFocus();
    }

    /**
     * 确定：重复 → 孤立 Shift / 无修饰键警告 → 落盘。@return true = 可以关闭弹窗
     *
     * <p><b>空（未设置）是合法状态</b>：Bksp 清空后按确定就是解绑，直接保存，不再拦。
     */
    private boolean applyCombo(final HotkeyAction a, final int[] combo) {
        if (combo[0] == 0) {
            commitCombo(a, combo);        // 空 = 解绑（未设置），照存
            return true;
        }
        final boolean shift = combo[1] != 0;
        final boolean ctrl = combo[2] != 0;
        final boolean alt = combo[3] != 0;
        if (!shift && !ctrl && !alt) return false;       // 兜底：单个键不受理（正常进不到这里）
        final HotkeyAction dup = findDuplicate(a, combo[0], shift, ctrl, alt);
        if (dup != null) {
            ask("警告", "组合键 " + HotkeyConfig.describe(combo[0], shift, ctrl, alt)
                    + " 已经被「" + dup.label + "」占用。\n要把它改绑到「" + a.label
                    + "」吗？（对方会变成未设置）",
                    () -> { clearCombo(dup); commitCombo(a, combo); });
            return true;
        }
        if (shift && !ctrl && !alt
                && combo[0] >= KeyEvent.KEYCODE_A && combo[0] <= KeyEvent.KEYCODE_Z) {
            ask("警告", "shift是字母大小写切换按钮，您是否确实要这样做？",
                    () -> commitCombo(a, combo));
            return true;
        }
        commitCombo(a, combo);
        return true;
    }

    /** 只提示、不改变任何东西的警告弹窗（校验没过时用，主弹窗保持打开）。 */
    private void warn(String msg) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("警告")
                    .setMessage(msg)
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private HotkeyAction findDuplicate(HotkeyAction self, int kc, boolean shift, boolean ctrl,
            boolean alt) {
        final java.util.Map<String, int[]> map =
                HotkeyConfig.parse(prefs.getString(ExtConfig.KEY_HOTKEYS, ""), null);
        for (HotkeyAction a : HotkeyAction.values()) {
            if (a == self) continue;
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

    private void commitCombo(HotkeyAction a, int[] combo) {
        final String next = HotkeyConfig.withCombo(
                prefs.getString(ExtConfig.KEY_HOTKEYS, ""), a, combo[0], combo[1] != 0,
                combo[2] != 0, combo[3] != 0);
        prefs.edit().putString(ExtConfig.KEY_HOTKEYS, next).apply();
        refreshHotkeyRows();
        ConfigSender.send(this, prefs);
    }

    /** 「是 / 否」二选一弹窗。 */
    private void ask(String title, String msg, final Runnable onYes) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("是", (d, w) -> onYes.run())
                    .setNegativeButton("否", null)
                    .setCancelable(false)
                    .show();
        } catch (Throwable tr) {
            onYes.run();
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

    /**
     * 一行「标签 + 下拉框」（多态设置用，例如「原样输出斜杠」三态）。
     *
     * <p>对齐隔壁：搜狗 OEM Ext 那边就是个下拉（MaterialAutoCompleteTextView），这里用原生
     * {@link android.widget.Spinner}，语义一样 —— 选完立刻生效并广播，不写死成按钮弹窗。
     */
    private void addSpinner(LinearLayout root, String title, String desc, final String key,
            final String[] labels, final int[] values, final int defValue) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setText(title);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        final android.widget.Spinner sp = new android.widget.Spinner(this);
        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        final int cur = prefs.getInt(key, defValue);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == cur) sp.setSelection(i, false);
        }
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent,
                    android.view.View view, int position, long id) {
                if (prefs.getInt(key, defValue) == values[position]) return;   // 初始化那次别回写
                prefs.edit().putInt(key, values[position]).apply();
                ConfigSender.send(MainActivity.this, prefs);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        row.addView(sp);
        root.addView(row);
        addHint(root, desc);
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
