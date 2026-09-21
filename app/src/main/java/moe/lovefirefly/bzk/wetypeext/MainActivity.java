package moe.lovefirefly.bzk.wetypeext;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** 占位页。后续：语言暴露、英文键盘联想开关、说明。 */
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
                + "2. 只在英文键盘关闭联想（中文不动）\n\n"
                + "当前阶段：0.1.0-probe 第一轮探针\n"
                + "（注入 subtype + 只打日志的框架钩子）\n\n"
                + "包名：com.tencent.wetype\n"
                + "IME：…plugin.hld.WxHldService（进程 :hld）\n\n"
                + "LSPosed 勾选微信输入法后看 logcat 标签 BZK-WeTypeExt。");
        setContentView(tv);
    }
}
