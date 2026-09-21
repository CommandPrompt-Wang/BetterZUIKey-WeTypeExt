package moe.lovefirefly.bzk.wetypeext;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
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

        addSwitch(root, "使用英文标点",
                "开：物理键盘打出的中文标点落成 ASCII（，→, 。→. ！→! ？→? ；→; ：→: （）→() 【】→[] “”→\"\" ‘'→''）。"
                        + "关（默认）：中文标点保持全角。软键盘上点的 ，/。 不受影响。",
                ExtConfig.KEY_EN_PUNCT, cfg.enPunct);

        addSwitch(root, "全角模式",
                "开：物理键盘打出的 ASCII 符号转全角（只动符号，不动字母数字，免得拼音被全角化）。"
                        + "关（默认）：什么都不做 —— 微信自己的符号本来就是半角。",
                ExtConfig.KEY_FULLWIDTH, cfg.fullWidth);

        addHint(root, "\n切换后立即生效，无需重启微信。\n"
                + "日志标签：BZK-WeTypeExt");

        final ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        // 进页面补发一次：微信进程当时没跑的话，这条会在它下次起来前一直缺失
        ConfigSender.send(this, prefs);
    }

    private void addSwitch(LinearLayout root, String title, String desc,
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
