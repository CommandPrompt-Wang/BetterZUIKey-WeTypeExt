package moe.lovefirefly.bzk.wetypeext;

import android.content.Context;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 微信输入法（WeType）专用桥接模块入口。
 *
 * <p>当前目标（仅此两项）：
 * <ol>
 *   <li>在 {@code :hld} / 输入法自身 uid 下暴露 zh-CN / en-US subtype（对齐搜狗 OEM Ext）；</li>
 *   <li>摸清并关闭「强制英文联想」的隐藏开关。</li>
 * </ol>
 *
 * <p>LSPosed 作用域只需勾 {@code com.tencent.wetype}，不需要 system_server。
 */
public class BridgeHook extends XposedModule {

    static final String TAG = "BZK-WeTypeExt";
    private static final String SELF_PKG = "moe.lovefirefly.bzk.wetypeext";
    /** 微信输入法包名（APK badging / scope）。 */
    static final String WXKB_PKG = "com.tencent.wetype";

    private static final Set<String> sHandled = ConcurrentHashMap.newKeySet();

    public BridgeHook() {
        super();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        final ClassLoader cl = param.getClassLoader();
        if (pkg == null || SELF_PKG.equals(pkg)) return;
        if (!WXKB_PKG.equals(pkg)) return;
        if (!sHandled.add(pkg)) return;

        final String process = currentProcessName();
        Log.i(TAG, "package ready pkg=" + pkg + " process=" + process);

        Thread t = new Thread(() -> {
            try {
                final Context ctx = systemContext();
                if (ctx == null) {
                    Log.w(TAG, "no system context");
                    return;
                }
                // Phase 0：只打点。Subtype 注入 / 英文联想探针按分析进度往这里挂。
                Log.i(TAG, "probe stub online; subtype inject & en-suggest TBD");
                Log.i(TAG, "betterzuikey installed=" + hasBetterZUIKey(ctx));
            } catch (Throwable tr) {
                Log.w(TAG, "init failed: " + tr);
            }
        }, "wxkb-bridge");
        t.setDaemon(true);
        t.start();
    }

    private static boolean hasBetterZUIKey(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo("moe.lovefirefly.betterzuikey", 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String currentProcessName() {
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            if (at == null) return "?";
            Object name = at.getClass().getMethod("getProcessName").invoke(at);
            return name != null ? name.toString() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Context systemContext() throws Exception {
        Object at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null);
        if (at == null) return null;
        return (Context) at.getClass().getMethod("getSystemContext").invoke(at);
    }
}

