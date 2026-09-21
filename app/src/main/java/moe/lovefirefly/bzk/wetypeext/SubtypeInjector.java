package moe.lovefirefly.bzk.wetypeext;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.util.ArrayList;
import java.util.List;

/**
 * 给微信输入法补上语言层（zh-CN / en-US）。
 *
 * <p>微信输入法自己声明了 <b>0 个 {@code <subtype>}</b>，框架完全看不到它的中/英，
 * 所以系统与 BetterZUIKey 的语言切换对它无效。这里把两个语言做成 subtype 交给框架。
 *
 * <h3>为什么必须在输入法自己的进程里做</h3>
 * {@code setAdditionalInputMethodSubtypes} 最终走到 IMMS 的
 * {@code InputMethodSettings.getNewAdditionalSubtypeMap()}，那里有一道 uid 闸门：
 *
 * <pre>
 *   checkIfPackageBelongsToUid(pmInternal, callingUid, imi.getPackageName())
 *       → PackageManagerInternal.isSameApp(...)
 *   if (!belongsTo) return currentMap;        // 静默 no-op，不抛异常
 * </pre>
 *
 * <b>没有特权 UID 特判</b>：root(0) 与 system_server(1000) 一样被静默拒绝，
 * 只有 {@code com.tencent.wetype} 自己的 uid（= 本模块被注入它进程后的 uid）能过。
 * 所以不需要 system 作用域。
 *
 * <h3>两个 API 的分工</h3>
 * <ul>
 *   <li>{@code setAdditionalInputMethodSubtypes} —— 把 subtype 加进该 IME 的声明列表（持久化）。</li>
 *   <li>{@code setExplicitlyEnabledInputMethodSubtypes} —— additional subtype 默认<b>不启用</b>，
 *       必须显式启用才会出现在 enabled 列表里。</li>
 * </ul>
 *
 * <p>本类只负责「暴露 subtype」；真正把框架 subtype 翻成微信内部中英切换是
 * {@link ServiceProbe} 的事。
 */
final class SubtypeInjector {

    private static final String TAG = BridgeHook.TAG;

    private static final String SERVICE_ACTION = "android.view.InputMethod";
    private static final String IME_PERMISSION = "android.permission.BIND_INPUT_METHOD";

    private static final String LOCALE_ZH = "zh-CN";
    private static final String LOCALE_EN = "en-US";

    private SubtypeInjector() {}

    /** @return 简短结果串（日志用），永不抛异常。 */
    static String apply(Context ctx, String packageName) {
        try {
            return applyInternal(ctx, packageName);
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    private static String applyInternal(Context ctx, String packageName) {
        if (ctx == null || packageName == null) return "bad-args";

        final String imeId = findImeId(ctx, packageName);
        if (imeId == null) return "not-an-ime";

        final InputMethodManager imm =
                (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return "no-imm";

        final InputMethodInfo imi = findIme(imm, imeId);
        if (imi == null) return "ime-not-listed";

        // 已经有语言层的输入法不用我们插手（防止将来微信自己补了 subtype 后重复注入）
        if (imi.getSubtypeCount() >= 2) {
            return "skip: already " + imi.getSubtypeCount() + " subtypes";
        }

        final List<String> have = new ArrayList<>();
        for (int i = 0; i < imi.getSubtypeCount(); i++) {
            have.add(String.valueOf(imi.getSubtypeAt(i).getLocale()));
        }

        final List<InputMethodSubtype> add = new ArrayList<>();
        if (!have.contains(LOCALE_ZH)) add.add(build(LOCALE_ZH, "中文", false));
        if (!have.contains(LOCALE_EN)) add.add(build(LOCALE_EN, "English", true));
        if (add.isEmpty()) return "skip: locales already declared";

        imm.setAdditionalInputMethodSubtypes(imeId, add.toArray(new InputMethodSubtype[0]));

        // additional subtype 默认不启用 → 显式启用（保留该 IME 已有的显式启用项）
        final List<Integer> hashes = new ArrayList<>();
        for (InputMethodSubtype s : imm.getEnabledInputMethodSubtypeList(imi, false)) {
            hashes.add(s.hashCode());
        }
        for (InputMethodSubtype s : add) {
            if (!hashes.contains(s.hashCode())) hashes.add(s.hashCode());
        }
        final int[] arr = new int[hashes.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = hashes.get(i);
        imm.setExplicitlyEnabledInputMethodSubtypes(imeId, arr);

        return "injected " + imeId + " (+" + add.size() + " subtypes, enabled=" + arr.length + ")";
    }

    /** 在该包里找一个带 BIND_INPUT_METHOD 权限的 IME 服务，拼出 imeId。 */
    private static String findImeId(Context ctx, String packageName) {
        final PackageManager pm = ctx.getPackageManager();
        final List<ResolveInfo> services = pm.queryIntentServices(new Intent(SERVICE_ACTION), 0);
        if (services == null) return null;
        for (ResolveInfo ri : services) {
            final ServiceInfo si = ri.serviceInfo;
            if (si == null) continue;
            if (!packageName.equals(si.packageName)) continue;
            if (!IME_PERMISSION.equals(si.permission)) continue;
            return new ComponentName(si.packageName, si.name).flattenToShortString();
        }
        return null;
    }

    private static InputMethodInfo findIme(InputMethodManager imm, String imeId) {
        final List<InputMethodInfo> list = imm.getInputMethodList();
        if (list == null) return null;
        for (InputMethodInfo i : list) {
            if (imeId.equals(i.getId())) return i;
        }
        return null;
    }

    private static InputMethodSubtype build(String locale, String displayName, boolean asciiCapable) {
        final InputMethodSubtype.InputMethodSubtypeBuilder b =
                new InputMethodSubtype.InputMethodSubtypeBuilder()
                        .setSubtypeLocale(locale)
                        .setSubtypeMode("keyboard")
                        .setIsAsciiCapable(asciiCapable);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setSubtypeNameOverride(displayName);
        }
        Log.i(TAG, "build subtype " + locale + " ascii=" + asciiCapable);
        return b.build();
    }
}
