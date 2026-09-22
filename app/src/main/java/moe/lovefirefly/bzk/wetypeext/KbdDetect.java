package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 「更宽松的键盘识别」：让<b>任何物理键</b>都能让微信切进硬件键盘模式（收起软键盘），而不是只认 A–Z。
 *
 * <h3>测到的事</h3>
 * 微信判「你在用物理键盘」靠 {@code hardware/d.b(int)}：只有 {@code keyCode 29..54}（A–Z）才会调
 * {@code g.l(true)} 进硬件模式；其它键连 {@code WxHldService} 那道物理键闸门都过不去
 * （{@code if (… && d.b(keyCode) && !keyboardShow && keyCode != 4)}）。
 *
 * <h3>怎么解</h3>
 * 闸门前的 {@code onKeyDown} 我们已经挂了（{@link Hotkeys}），那里<b>拿得到 KeyEvent</b>：
 * 按级别判定这个键够不够格，记一笔；再挂 {@code hardware/d.b(int)}，够格时<b>借 A–Z 的键码问它一次</b>
 * —— 微信自己就会进硬件模式，不用碰它的混淆字段。
 *
 * <h3>怎么判断「真物理」</h3>
 * 闸门只看「有个键走到了硬件键路径」，<b>不等于</b>物理键盘：ZUXOS 的 {@code Win+L} / {@code Alt+Shift}
 * 等都会 {@code injectKeyEvent}，走同一条路。实测两者可分：
 * <pre>
 *   物理键盘   dev=18  src=0x101  scan=33  flags=0x8
 *   注入       dev=-1  src=0x0    scan=0   flags=0x0
 * </pre>
 * 所以要求 {@code deviceId > 0 && source == SOURCE_KEYBOARD}。
 */
final class KbdDetect {

    private static final String TAG = BridgeHook.TAG;

    /** 「够格」标记的有效窗口（按键分发到 {@code b()} 之间是同步的，给足余量即可）。 */
    private static final long WINDOW_MS = 300L;

    private static volatile long sQualifiedAt = -1L;
    private static volatile boolean sInstalled;
    /** 借 A–Z 键码调 {@code b()} 时的防重入（那次调用会再进本钩子）。 */
    private static volatile boolean sForcing;

    private KbdDetect() {}

    /** 按键钩子里调（跑在微信那道物理键闸门之前）。 */
    static void noteKey(KeyEvent e) {
        if (e == null || !qualifies(e)) return;
        sQualifiedAt = System.currentTimeMillis();
    }

    private static boolean fresh() {
        final long at = sQualifiedAt;
        return at > 0 && System.currentTimeMillis() - at <= WINDOW_MS;
    }

    /** 这个键够不够格触发硬件模式（按配置的级别 + 物理来源校验）。 */
    private static boolean qualifies(KeyEvent e) {
        final int level = ExtConfig.get().kbdDetect;
        // 1 = 字母：完全交给微信自己的 A–Z 判断，零回归
        if (level <= 1) return false;
        if (!fromRealKeyboard(e)) return false;
        if (level == 2) {
            final int uni = e.getUnicodeChar();
            // 要「可打印」：Enter(10)、Tab(9) 这类控制符不算；方向键 / 退格 / 音量键更是 uni=0
            return uni > 0 && !Character.isISOControl(uni);
        }
        return true;   // 3 = 任何操作
    }

    /**
     * 实测判据：物理键盘 {@code deviceId > 0}，且事件 source **含** {@code SOURCE_KEYBOARD}。
     *
     * <p>⚠️ 这里必须<b>按位测</b>，不能写 {@code == SOURCE_KEYBOARD}：键鼠一体的设备
     * （无线接收器、平板键盘保护套的触摸板）会把 source 或起来（{@code 0x101 | 0x2002}），
     * 用等号判会把整块键盘漏掉。
     *
     * <p>另外再排掉<b>虚拟设备</b>（{@code input} 命令与系统注入走的就是它）；
     * 设备查不到时不因此否决 —— 前两条已经够说明问题了，宁可宽松也别让功能静默失效。
     */
    private static boolean fromRealKeyboard(KeyEvent e) {
        try {
            if (e.getDeviceId() <= 0) return false;
            if ((e.getSource() & InputDevice.SOURCE_KEYBOARD) == 0) return false;
            final InputDevice dev = InputDevice.getDevice(e.getDeviceId());
            return dev == null || !dev.isVirtual();
        } catch (Throwable tr) {
            return false;
        }
    }

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        try {
            final Class<?> d = Class.forName(
                    "com.tencent.wetype.plugin.hld.hardware.d", false, cl);
            final Method b = d.getDeclaredMethod("b", int.class);
            b.setAccessible(true);
            module.hook(b).intercept(chain -> {
                if (!fresh() || sForcing) return chain.proceed();
                try {
                    // 借一个 A–Z 键码问它一次：微信会因此进硬件模式（软键盘随之收起）
                    sForcing = true;
                    b.invoke(chain.getThisObject(), KeyEvent.KEYCODE_A);
                } catch (Throwable tr) {
                    Log.w(TAG, "KbdDetect: force hardware mode failed: " + tr);
                } finally {
                    sForcing = false;
                }
                return chain.proceed();
            });
            Log.i(TAG, "KbdDetect: hooked hardware/d.b");
        } catch (Throwable tr) {
            Log.w(TAG, "KbdDetect: install failed: " + tr);
        }
    }
}
