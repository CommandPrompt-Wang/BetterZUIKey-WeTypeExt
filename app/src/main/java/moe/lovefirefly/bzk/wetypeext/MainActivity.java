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
