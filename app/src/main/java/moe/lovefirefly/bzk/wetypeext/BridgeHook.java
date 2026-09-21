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
 *   <li>只在<b>英文键盘</b>关闭联想（中文键盘不动）。</li>
 * </ol>
 *
 * <p>LSPosed 作用域只需勾 {@code com.tencent.wetype}，不需要 system_server。
 *
 * <p>静态分析见 {@code local/static/}；本阶段是「第一轮探针」——注入 subtype + 只打日志的框架钩子。
 */
public class BridgeHook extends XposedModule {

    static final String TAG = "BZK-WeTypeExt";

    private static final String SELF_PKG = "moe.lovefirefly.bzk.wetypeext";

    /** 微信输入法包名（APK badging / scope）。 */
    static final String WXKB_PKG = "com.tencent.wetype";

    /** IME 本体所在进程。 */
    private static final String IME_PROCESS_SUFFIX = ":hld";

    /** 第一轮：只观察，不改行为。 */
    static final boolean DEV_PROBE = true;

    /**
     * 是否解析微信内部类（{@code model.N} / {@code utils.j1}）来打内部状态。
     *
     * <p>留这个开关是因为踩过坑：内部类的静态初始化会拉起 MMKV，时机不对会把微信进程搞崩
     * （见 {@link ServiceProbe} 类注释）。出事时把它关掉即可恢复，不必卸载模块。
     */
    static final boolean DEV_INTERNALS = true;

    /**
     * 是否启用目标 2 的行为：<b>英文键盘不放行联想候选</b>（中文键盘不动）。
     *
     * <p>放在这里当开关是为了 A/B：关掉即恢复微信原生行为，便于对比"是不是真的只砍了英文联想"。
     */
    static final boolean DEV_GATE_EN_ASSOC = true;

    /** 候选探针：英文键盘时把候选的 flag/kind 打出来（诊断用，已收工）。 */
    static final boolean DEV_CAND_PROBE = false;

    /** 目标 2 的落点：英文键盘下清空候选栏（用户要的：不要任何联想/补全，字直接上屏）。 */
    static final boolean DEV_FILTER_EN_CAND = true;

    /**
     * 每次改探针就 +1：日志里能看到它，用来判断"这个进程加载的是不是最新那份模块"。
     *
     * <p>踩过的坑：重装 APK 后如果目标进程没重启，LSPosed 仍用它启动时加载的旧代码
     * （实测 :hld 一直跑着旧版，导致新加的闸门看起来"没生效"）。
     */
    static final int PROBE_BUILD = 8;

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
        Log.i(TAG, "package ready pkg=" + pkg + " process=" + process
                + " build=" + PROBE_BUILD);

        final Thread t = new Thread(() -> {
            try {
                final Context ctx = systemContext();
                if (ctx == null) {
                    Log.w(TAG, "no system context");
                    return;
                }

                // 1) 暴露 subtype：任何 WeType 进程都能过 uid 闸门（isSameApp 只看 appId），
                //    重复调用是幂等的。
                Log.i(TAG, "subtype inject: " + SubtypeInjector.apply(ctx, WXKB_PKG));

                // 2) 框架钩子只需要装在 IME 进程；进程名认不出来时 fail-open，照样装。
                final boolean isImeProcess = process == null
                        || process.endsWith(IME_PROCESS_SUFFIX)
                        || "?".equals(process);
                if (DEV_PROBE && isImeProcess) {
                    ServiceProbe.install(this, cl);
                } else {
                    Log.i(TAG, "skip service probe (process=" + process + ")");
                }

                Log.i(TAG, "betterzuikey installed=" + hasBetterZUIKey(ctx));
            } catch (Throwable tr) {
                Log.w(TAG, "init failed: " + tr);
            }
        }, "wetype-bridge");
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
