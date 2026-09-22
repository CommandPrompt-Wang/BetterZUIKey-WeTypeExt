package moe.lovefirefly.bzk.wetypeext;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

/**
 * 设置页：三个开关，改完即生效（走 {@link ConfigSender} 的显式广播，不用重启微信进程）。
 *
 * <p>每次进入页面都补发一条配置：接收器只在微信进程活着时存在，微信没跑时广播会丢。
 */
public class MainActivity extends AppCompatActivity {

    /** BetterZUIKey 主程序包名（清单里已声明 queries，不受 Android 11 包可见性过滤影响）。 */
    private static final String BZK_PKG = "moe.lovefirefly.betterzuikey";

    /** 本模块服务的输入法（微信输入法）包名；用于判断它当前是不是生效的输入法。 */
    private static final String TARGET_IME_PKG = "com.tencent.wetype";

    private SharedPreferences prefs;

    /** 边距基准（16dp）：卡片圆角 / 描边 / 内边距都从它换算，和隔壁搜狗增强一致。 */
    private int pad;

    /**
     * 「全角模式 / 中英文标点」两行说明里那行「当前状态：xx」（由模块回传的镜像广播刷新）。
     *
     * <p>状态原来是拼在<b>行标题</b>里的（「全角模式（当前：全角）」），现在挪到说明的最后一行。
     * {@code ...Base} 是说明里不随状态变化的前半段。
     */
    private TextView fullwidthHint;
    private TextView enPunctHint;
    private String fullwidthHintBase;
    private String enPunctHintBase;

    /** 右下角「刷新状态」悬浮键 + 它那个会转的图标（进页面和点击都要转）。 */
    private com.google.android.material.floatingactionbutton.FloatingActionButton refreshFab;
    private android.graphics.drawable.RotateDrawable refreshSpinIcon;
    /** 动画进行中：忽略连点，也免得进页面那次和点击撞在一起。 */
    private boolean refreshSpinning;
    /** 最近一次从模块拿到的状态位；null = 还没收到。 */
    private Boolean stateFullwidth;
    private Boolean stateEnPunct;
    private android.content.BroadcastReceiver stateReceiver;

    /** 快捷键区：每个动作一行 = 左标签 + 右组合键按钮（点按钮开弹窗改键）。 */
    private final java.util.Map<HotkeyAction, MaterialButton> hotkeyButtons =
            new java.util.EnumMap<>(HotkeyAction.class);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pad = dp(16);
        prefs = getSharedPreferences(ExtConfig.APP_PREFS, MODE_PRIVATE);
        final ExtConfig cfg = ExtConfig.load(prefs);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad * 2, 0, pad * 2, pad * 2);

        addPageTitle(root, "微信输入法增强");

        // 排列顺序对齐「搜狗增强」：按处理管线分组，而不是按开发先后堆。
        //   ① 语言  ② 标点（语义层 → 状态位）  ③ 输入行为  ④ 配对  ⑤ 键盘行为  ⑥ 快捷键
        // 同类的挨在一起；两个 Shift 项原来被隔在首尾，现在并排。

        // ── ① 语言 ──
        // 开关从三个并成一个（「跟随系统语言切换」「严格跟随系统语言」变成固定行为，见 ExtConfig）。
        // 剩下这一个也不是谁都该开：它拦掉微信自己的切语言，语言就只能靠框架推过来，
        // 而框架侧的轮转正是 BZK 在做 —— 所以没装 BZK 时灰掉并换一套提示（文案/行为对齐搜狗）。
        final boolean hasBzk = hasBetterZUIKey();
        addSwitch(root, "只响应系统框架语言切换消息",
                hasBzk
                        ? "检测到BetterZUIKey，建议在它的“输入法增强”中为“微信输入法”"
                          + "启用“framework”模式，然后打开此开关，"
                          + "以让BetterZUIKey完全接管此选项"
                        : "未检测到BetterZUIKey，建议安装以增强功能",
                ExtConfig.KEY_STRICT, cfg.strictFrameworkOnly, hasBzk);

        // ── ② 标点：先语义层（原样输出斜杠），再两个状态位（全角 / 中英标点）──
        addDropdown(root, "原样输出斜杠",
                "微信把 / 和 \\ 都输出成 、。在此选择想原样保留的字符。",
                ExtConfig.KEY_SLASH_MODE,
                new String[]{"关", "原样输出 /", "原样输出 \\"},
                new int[]{0, 1, 2}, ExtConfig.DEF_SLASH_MODE);

        fullwidthHintBase = "允许在全角/半角之间切换\n"
                + "请在下方修改快捷键\n"
                + "长按标题亦可切换全角/半角状态";
        fullwidthHint = addSwitchRow(root, "全角模式", fullwidthHintBase + "\n当前状态：?",
                ExtConfig.KEY_FULLWIDTH_FEATURE, cfg.fullwidthFeature, true,
                () -> toggleState(true)).hint;

        enPunctHintBase = "允许中文模式下在中英标点之间切换\n"
                + "请在下方修改快捷键\n"
                + "长按标题亦可切换中英标点状态";
        enPunctHint = addSwitchRow(root, "中英文标点", enPunctHintBase + "\n当前状态：?",
                ExtConfig.KEY_EN_PUNCT_FEATURE, cfg.enPunctFeature, true,
                () -> toggleState(false)).hint;

        // ── ③ 输入行为：改「打到屏幕上的是什么」的两条 ──
        addSwitch(root, "智能编号",
                "数字后面的 。和） 自动用半角 . 和 )，以方便输入 1.  2) 编号格式",
                ExtConfig.KEY_SMART_NUMBER, cfg.smartNumber);

        addSwitch(root, "关闭英文候选",
                "英文输入时不显示候选栏和预测。",
                ExtConfig.KEY_EN_NO_SUGGEST, cfg.enNoSuggest);

        // ── ④ 配对：自动补另一半 + 闭字符跳过 ──
        addSwitch(root, "引号/括号自动补全",
                "关闭后输入引号、括号时不再自动关闭。",
                ExtConfig.KEY_AUTO_PAIR, cfg.autoPair);

        addSwitch(root, "跳过已存在的闭合符号",
                "当光标后侧已有闭合符时，只移动光标而不额外产生闭合符。\n"
                        + "当手动移动光标位置后恢复正常闭合",
                ExtConfig.KEY_CLOSE_SKIP, cfg.closeSkip);

        // ── ⑤ 键盘行为：两个 Shift 相关的放一起 ──
        addSwitch(root, "Shift 选区修复",
                "修复部分文本框 Shift+方向键无法选中文字的问题",
                ExtConfig.KEY_SHIFT_PASSTHRU, cfg.shiftPassThrough);

        addSwitch(root, "Shift 切换修复",
                "修复按住 Shift 输入大写字母时意外切换语言的问题",
                ExtConfig.KEY_SHIFT_FIX, cfg.shiftSwitchFix);

        // ── ⑥ 快捷键 ──
        addHotkeySection(root);

        addGap(root, 24);
        addHint(root, "切换后立即生效，无需重启微信。");

        // 内容全部放进 ScrollView；外面再套一层 shell 只为挂 window insets，
        // 免得把 root 的左右页边距冲掉（applyInsets 会重写 padding）。
        final ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);

        // 「刷新状态」：浮在滚动区右下角的 Material FAB（对齐搜狗）。
        // 点一下 —— 以及每次进页面 —— 都转一圈并重新推一次配置。
        refreshFab = new com.google.android.material.floatingactionbutton.FloatingActionButton(this);
        // 只转图标：把图标包进 RotateDrawable，动它的 level（0..10000 映射 0..360°），
        // 这样 FAB 本体（背景/阴影）保持不动。
        refreshSpinIcon = new android.graphics.drawable.RotateDrawable();
        refreshSpinIcon.setDrawable(getResources().getDrawable(android.R.drawable.ic_popup_sync));
        refreshSpinIcon.setLevel(0);
        refreshFab.setImageDrawable(refreshSpinIcon);
        refreshFab.setContentDescription("刷新状态");
        refreshFab.setTooltipText("刷新状态");
        // 持久阴影：FAB 本来有默认 elevation，但父层若裁剪就看不出 ⇒ 显式给一层 + 关掉裁剪
        refreshFab.setCompatElevation(6f * getResources().getDisplayMetrics().density);
        refreshFab.setOnClickListener(v -> spinRefreshFab());

        // 滚动区 + 悬浮刷新键同放一层 FrameLayout ⇒ 键浮在列表上方，不占布局高度
        final android.widget.FrameLayout scrollWrap = new android.widget.FrameLayout(this);
        scrollWrap.setClipChildren(false);        // 别裁掉 FAB 的阴影
        scrollWrap.addView(sv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final android.widget.FrameLayout.LayoutParams refreshLp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshLp.gravity = Gravity.BOTTOM | Gravity.END;
        refreshLp.setMargins(0, 0, pad * 2, pad * 2);
        scrollWrap.addView(refreshFab, refreshLp);

        final LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.addView(scrollWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(shell);
        applyInsets(shell);

        registerStateReceiver();

        // 进页面的那次「补发配置 + 要一次状态位」在 onResume 里做（顺手让刷新键转一圈）。
        // onResume 必定跟在 onCreate 后面，所以不会漏发。
    }

    /** 「快捷键」区：每行 = 左标签 + 右组合键按钮；点按钮开「设置快捷键」弹窗。 */
    private void addHotkeySection(LinearLayout root) {
        addGap(root, 24);
        addSectionTitle(root, "快捷键");
        addHint(root, "在此处修改快捷键");
        for (HotkeyAction a : HotkeyAction.values()) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            final TextView label = new TextView(this);
            label.setText(a.label);
            label.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodyLarge);
            label.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 主题色文字、无底色（M3 TextButton，像超链接）。样式没有对应的 attr，
            // 只能从布局里 inflate（见 res/layout/hotkey_button.xml）。
            final MaterialButton btn = (MaterialButton) android.view.LayoutInflater.from(this)
                    .inflate(R.layout.hotkey_button, row, false);
            btn.setAllCaps(false);          // Android 默认给按钮文本 toUpperCase，这里要原样
            btn.setOnClickListener(v -> openHotkeyDialog(a));
            hotkeyButtons.put(a, btn);
            row.addView(btn);

            final LinearLayout box = newItemBox(root);
            box.addView(row);
        }
        refreshHotkeyRows();
    }

    private void refreshHotkeyRows() {
        final java.util.Map<String, int[]> map = HotkeyConfig.parse(
                prefs.getString(ExtConfig.KEY_HOTKEYS, ""), null);
        for (java.util.Map.Entry<HotkeyAction, MaterialButton> e : hotkeyButtons.entrySet()) {
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

        final androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
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
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
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
            new MaterialAlertDialogBuilder(this)
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
            new MaterialAlertDialogBuilder(this)
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

    /** 把「当前状态：全角/半角、英文标点/中文标点」写回两行说明的最后一行。 */
    private void refreshStateText() {
        final boolean ime = isTargetImeActive();
        if (fullwidthHint != null) {
            fullwidthHint.setText(fullwidthHintBase + "\n当前状态："
                    + stateWord(ime, stateFullwidth, "全角", "半角"));
        }
        if (enPunctHint != null) {
            enPunctHint.setText(enPunctHintBase + "\n当前状态："
                    + stateWord(ime, stateEnPunct, "英文标点", "中文标点"));
        }
    }

    /**
     * 状态词。
     *
     * <p>当前输入法不是微信输入法时，模块一项都不会生效、状态位也永远拿不回来 ——
     * 与其一直显示「?」，不如直接说清楚（这是用户口径）。
     */
    private static String stateWord(boolean targetImeActive, Boolean state,
            String onWord, String offWord) {
        if (!targetImeActive) return "输入法未启用";
        if (state == null) return "?";
        return state.booleanValue() ? onWord : offWord;
    }

    /**
     * 当前生效的输入法是不是微信输入法。
     *
     * <p>{@code Settings.Secure.DEFAULT_INPUT_METHOD} 是公开 secure setting，读它不需要权限
     * （App 与模块都能读；模块那边走 {@code systemContext()}）。
     *
     * <p>读不到时返回 {@code true}：宁可当成"在用"，也不要因为读不到就误报"未启用"。
     */
    private boolean isTargetImeActive() {
        try {
            final String cur = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            return cur == null || cur.startsWith(TARGET_IME_PKG);
        } catch (Throwable tr) {
            return true;
        }
    }

    /**
     * 「刷新状态」：右下角那个键转一圈 + 重新推一次配置。
     *
     * <p>点键和<b>进页面</b>（{@link #onResume()}）都走这里 —— 进页面也转一圈，是为了让
     * 「刚进来就已经自动刷过一次」这件事看得见。
     *
     * <p>为什么刷新 = 重新推配置：状态位住在微信进程里，App 读不到 —— 只能借这次推送里的
     * {@code wantState} 让模块把当前状态镜像回来（收到后 {@link #refreshStateText()} 刷新那两行）。
     * 顺带把「期望值 + 序号」再发一遍：微信进程上回没在跑的话，这次就补上了。
     */
    private void spinRefreshFab() {
        if (refreshFab == null || refreshSpinning) return;   // 动画期间忽略连点

        // 当前输入法不是微信输入法 ⇒ 模块一项都不生效、状态位也永远要不回来：
        // 别转了、也别白要状态（但配置照样推，免得设置丢了），把「输入法未启用」写上就完事。
        if (!isTargetImeActive()) {
            refreshStateText();
            ConfigSender.send(this, prefs, false);
            return;
        }

        refreshSpinning = true;
        final android.animation.ObjectAnimator anim =
                android.animation.ObjectAnimator.ofInt(refreshSpinIcon, "level", 0, 10000);
        anim.setDuration(600);
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                refreshSpinning = false;
                refreshSpinIcon.setLevel(0);
            }
        });
        anim.start();
        ConfigSender.send(this, prefs, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 进页面自动刷一次，并让右下角那个键转一圈
        spinRefreshFab();
    }

    /**
     * 长按状态位那一行 ⇒ 切换全角/半角（或中英标点）。
     *
     * <p>状态位住在<b>微信进程</b>里，App 物理上写不到，只能请模块代设：把「设为 X」跟完整配置
     * 一起广播过去（见 {@link ConfigSender#send}），模块改完会把新状态镜像回来
     * （{@link BroadcastConfig#ACTION_STATE}）。这里同时乐观地先把本地镜像改掉并刷新说明，
     * 省掉一个来回的延迟。
     *
     * <p>还没收到过镜像时（{@code now == null}）按「当前是关」处理，于是首次长按总是切成开。
     */
    private void toggleState(boolean fullwidth) {
        final Boolean now = fullwidth ? stateFullwidth : stateEnPunct;
        final boolean on = now == null || !now.booleanValue();
        if (fullwidth) {
            stateFullwidth = Boolean.valueOf(on);
        } else {
            stateEnPunct = Boolean.valueOf(on);
        }
        refreshStateText();
        // 期望值 + 序号一起落盘，再随配置广播发出去（模块择机套用；当时没跑就等下一次配置）。
        // 用时间戳当序号：不存在溢出；App 清数据后新值必然更大 ⇒ 不会永久失效。
        prefs.edit()
                .putBoolean(fullwidth ? ExtConfig.KEY_WANT_FULLWIDTH
                        : ExtConfig.KEY_WANT_EN_PUNCT, on)
                .putLong(ExtConfig.KEY_WANT_SEQ, System.currentTimeMillis())
                .apply();
        ConfigSender.send(this, prefs);
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
     * <p>对齐隔壁：搜狗 OEM Ext 那边就是个下拉（MaterialAutoCompleteTextView + TextInputLayout），
     * 语义一样 —— 选完立刻生效并广播，不写死成按钮弹窗。
     */
    private void addDropdown(LinearLayout root, String title, String desc, final String key,
            final String[] labels, final int[] values, final int defValue) {
        final TextView label = new TextView(this);
        label.setText(title);
        label.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        label.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));

        final TextView hint = new TextView(this);
        hint.setText(desc);
        hint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        hint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(0, 0, 0, pad / 4);

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label);
        texts.addView(hint);

        final TextInputLayout til = new TextInputLayout(this);
        til.setHintEnabled(false);
        til.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        til.setBoxBackgroundColor(themeColor(
                com.google.android.material.R.attr.colorSurfaceContainerHighest));
        til.setMinimumWidth((int) (160 * getResources().getDisplayMetrics().density));

        final MaterialAutoCompleteTextView field = new MaterialAutoCompleteTextView(this);
        field.setInputType(android.text.InputType.TYPE_NULL);
        field.setFocusable(true);
        field.setClickable(true);
        field.setDropDownWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
        field.setAdapter(new android.widget.ArrayAdapter<>(
                this, R.layout.dropdown_item_wrap, labels));
        final int cur = prefs.getInt(key, defValue);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == cur) field.setText(labels[i], false);
        }
        field.setOnItemClickListener((parent, view, position, id) -> {
            if (prefs.getInt(key, defValue) == values[position]) return;   // 初始化那次别回写
            prefs.edit().putInt(key, values[position]).apply();
            ConfigSender.send(MainActivity.this, prefs);
        });
        til.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(til);

        final LinearLayout box = newItemBox(root);
        box.addView(row);
    }

    private MaterialSwitch addSwitch(LinearLayout root, String title, String desc,
            final String key, boolean def) {
        return addSwitch(root, title, desc, key, def, true);
    }

    /**
     * 带「可用性」的开关行。
     *
     * <p>{@code enabled=false} 时灰掉且强制显示为关 —— 注意 {@code setChecked} 必须在挂监听器
     * <b>之前</b>做，否则会顺着监听器把 false 写回 prefs，等于悄悄把用户的设置清了
     * （搜狗那边是靠临时摘监听器绕开的，这里从构造顺序上直接避开）。
     */
    private MaterialSwitch addSwitch(LinearLayout root, String title, String desc,
            final String key, boolean def, boolean enabled) {
        return addSwitchRow(root, title, desc, key, def, enabled).sw;
    }

    /** 一行「开关 + 说明 View」。说明的末行是「当前状态：xx」时要拿得到它才能刷新。 */
    private static final class SwitchRow {
        final MaterialSwitch sw;
        final TextView hint;

        SwitchRow(MaterialSwitch sw, TextView hint) {
            this.sw = sw;
            this.hint = hint;
        }
    }

    /**
     * 一行「标题 + 说明」的开关卡片：左列标题在上、说明在下，右侧无文字开关垂直居中
     * —— 与隔壁搜狗增强的条目同构。
     */
    private SwitchRow addSwitchRow(LinearLayout root, String title, String desc,
            final String key, boolean def, boolean enabled) {
        return addSwitchRow(root, title, desc, key, def, enabled, null);
    }

    /**
     * 同上，{@code onLongPress != null} 时给整张卡片挂一个长按。
     *
     * <p>用来实现「长按标题亦可切换全角/半角状态」（对齐搜狗的状态位那一行）。
     *
     * <p>长按只挂<b>卡片 + 开关</b>：标题/说明本身不是 clickable，触摸会落到卡片上
     * （卡片进 pressed ⇒ 水波纹正常）；反过来直接给子 View 挂会让它变成触摸目标、
     * 卡片收不到 pressed，水波纹就没了。开关自己是 clickable 的会吃掉事件，
     * 所以它和它内部的子 View 要单独再挂一份。
     */
    private SwitchRow addSwitchRow(LinearLayout root, String title, String desc,
            final String key, boolean def, boolean enabled, final Runnable onLongPress) {
        final MaterialSwitch sw = new MaterialSwitch(this);
        sw.setPadding(pad / 2, 0, 0, 0);
        // setChecked 必须早于监听器：enabled=false 时强制显示为关但**不能**写回 prefs
        sw.setChecked(enabled && prefs.getBoolean(key, def));
        sw.setEnabled(enabled);
        sw.setAlpha(enabled ? 1f : 0.45f);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
            ConfigSender.send(MainActivity.this, prefs);
        });

        final TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        titleTv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));

        final TextView hintTv = new TextView(this);
        hintTv.setText(desc);
        hintTv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        hintTv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hintTv.setPadding(0, 0, 0, pad / 4);

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(titleTv);
        texts.addView(hintTv);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);

        final LinearLayout box = newItemBox(root);
        box.addView(row);
        if (onLongPress != null) {
            final android.view.View.OnLongClickListener l = v -> {
                onLongPress.run();
                return true;
            };
            ((android.view.View) box.getParent()).setOnLongClickListener(l);
            sw.setOnLongClickListener(l);
            walkView(sw, v -> v.setOnLongClickListener(l));
        }
        return new SwitchRow(sw, hintTv);
    }

    /** 深度优先遍历子树（给整张卡片挂长按/按压反馈用）。 */
    private void walkView(android.view.View v,
            java.util.function.Consumer<android.view.View> action) {
        action.accept(v);
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkView(g.getChildAt(i), action);
        }
    }

    /** 页面标题：M3 HeadlineSmall。 */
    private void addPageTitle(LinearLayout root, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_HeadlineSmall);
        tv.setPadding(0, pad * 2, 0, pad / 2);
        root.addView(tv);
    }

    /** 分区标题（如「快捷键」）：M3 TitleMedium + colorOnSurface。 */
    private void addSectionTitle(LinearLayout root, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        tv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));
        tv.setPadding(0, 0, 0, pad / 2);
        root.addView(tv);
    }

    /**
     * 竖直留白。
     *
     * <p>以前是靠给文案前面塞 {@code "\n"} 凑间距（{@code addTitle(root, "\n快捷键")}），
     * 那样文案本身就脏了 —— 导出文案时得连着换行一起搬，改字号/改间距也互相绑死。
     * 间距归布局管，文案保持干净。
     */
    private void addGap(LinearLayout root, int dpValue) {
        final android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(dpValue)));
        root.addView(v);
    }

    private TextView addHint(LinearLayout root, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        tv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(0, 0, 0, dp(6));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        root.addView(tv);
        return tv;
    }

    /**
     * 一个设置项 = 一整个卡片（与 BZK / 搜狗增强同款：圆角 pad*3/4、outline 描边、
     * 0 elevation、?attr/selectableItemBackground 水波纹）。
     *
     * <p>返回卡片里的竖直容器：调用方往里 addView 内容即可。
     */
    private LinearLayout newItemBox(LinearLayout parent) {
        final MaterialCardView card = new MaterialCardView(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad / 2, 0, 0);
        card.setLayoutParams(lp);
        card.setRadius(pad * 3 / 4f);
        card.setCardElevation(0f);
        card.setStrokeWidth(Math.max(1, pad / 16));
        card.setStrokeColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant));
        // 动画风格与 BZK 一致：ripple（?attr/selectableItemBackground），不做缩放
        final TypedValue rippleTv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleTv, true);
        if (rippleTv.resourceId != 0) {
            card.setForeground(ContextCompat.getDrawable(this, rippleTv.resourceId));
        }
        card.setClickable(true);
        card.setFocusable(true);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad * 3 / 4, pad / 2, pad * 3 / 4, pad / 2);
        card.addView(box);
        parent.addView(card);
        return box;
    }

    /**
     * BetterZUIKey 主程序是否安装。
     *
     * <p>为什么严格模式要看它：开了严格模式就把微信自己的切语言拦掉了，语言只能由框架推过来；
     * 而框架侧的语言轮转正是 BZK 在做（它给「微信输入法」内置了一条 {@code framework} 策略）。
     * 没装 BZK 还开严格模式 = 语言两边都切不动，所以没装时直接把这一行灰掉。
     */
    @SuppressWarnings("deprecation")
    private boolean hasBetterZUIKey() {
        try {
            getPackageManager().getPackageInfo(BZK_PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 从主题属性取颜色（M3 的 ?attr/colorXxx 不在 values 里，只能这样取）。 */
    private int themeColor(int attrRes) {
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) return ContextCompat.getColor(this, tv.resourceId);
        return tv.data;
    }

    /** Android 15+ 强制 edge-to-edge：把系统栏高度补成内边距。 */
    private static void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                final android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
