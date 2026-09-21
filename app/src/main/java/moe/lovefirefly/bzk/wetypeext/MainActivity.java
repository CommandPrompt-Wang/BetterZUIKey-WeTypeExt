package moe.lovefirefly.bzk.wetypeext;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** 占位页。后续：语言暴露、英文联想开关、说明。 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText("微信输入法增强\n\n"
                + "目标：\n"
                + "1. 暴露中/英 subtype（搜狗 OEM 同路）\n"
                + "2. 关闭强制英文联想（挖隐藏配置）\n\n"
                + "包名：com.tencent.wetype\n"
                + "IME：…plugin.hld.WxHldService（进程 :hld）\n\n"
                + "LSPosed 勾选微信输入法后看 logcat 标签 BZK-WeTypeExt。");
        setContentView(tv);
    }
}
