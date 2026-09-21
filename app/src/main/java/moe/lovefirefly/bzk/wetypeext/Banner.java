package moe.lovefirefly.bzk.wetypeext;

import android.util.Log;
import android.view.View;

/**
 * 输入法窗口上的一行提示（热键切换后给反馈）。
 *
 * <p>为什么不用 Toast：搜狗那边实测 Toast 会被系统按应用通知设置拦掉
 * （{@code NotificationService: Suppressing toast ... by user request}），
 * 所以自己画在输入法窗口上。
 *
 * <h3>2026-09-21 改法：不再用 PopupWindow</h3>
 * 用户实测：物理键热键（会弹 Banner 的那几个）一按，<b>软键盘直接消失</b>，
 * 而"只切状态不弹提示"的路径（全角开关关着时的 Shift+Space）不会 ——
 * 怀疑是给输入法窗口再叠一个 PopupWindow 导致的。
 * 现在改成把提示 View <b>直接加进输入法窗口自己的 View 树</b>（根 view = decor，
 * 是 FrameLayout，加一个贴底居中的子 View 既不开新窗口、也不影响键盘本身的测量）。
 *
 * <p>视图由 {@link ServiceProbe} 在 {@code setInputView} 时塞进来。
 */
final class Banner {

    private static final String TAG = BridgeHook.TAG;

    private static volatile View sView;
    private static volatile android.widget.PopupWindow sShowing;
    /** 现在贴在输入法窗口里的那个提示 View（新版做法）。 */
    private static volatile android.widget.TextView sOverlay;

    private Banner() {}

    static void attachView(View v) {
        if (v != null) sView = v;
    }

    /** 主线程调用（按键钩子本来就在主线程）。 */
    static void show(String text) {
        final View anchor = sView;
        if (anchor == null) {
            Log.i(TAG, "banner(no view): " + text);
            return;
        }
        try {
            final android.content.Context ctx = anchor.getContext();
            final float density = ctx.getResources().getDisplayMetrics().density;
            final int padH = (int) (14 * density);
            final int padV = (int) (7 * density);

            final android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(text);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackgroundColor(0xCC202020);
            tv.setPadding(padH, padV, padH, padV);

            // 优先：直接塞进输入法窗口自己的 View 树（decor = FrameLayout）
            final View root = anchor.getRootView();
            if (root instanceof android.view.ViewGroup && root != anchor) {
                final android.view.ViewGroup vg = (android.view.ViewGroup) root;
                final android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
                lp.bottomMargin = (int) (24 * density);
                final android.widget.TextView old = sOverlay;
                if (old != null && old.getParent() == vg) vg.removeView(old);
                vg.addView(tv, lp);
                sOverlay = tv;
                tv.postDelayed(() -> {
                    try {
                        if (tv.getParent() == vg) vg.removeView(tv);
                    } catch (Throwable ignored) {
                    }
                    if (sOverlay == tv) sOverlay = null;
                }, 1200L);
                return;
            }

            // 兜底：老路（PopupWindow）
            final android.widget.PopupWindow pw = new android.widget.PopupWindow(tv,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false);
            pw.setOutsideTouchable(false);
            pw.setFocusable(false);
            final int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            pw.showAtLocation(anchor, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER,
                    0, (int) (screenH * 0.12f));
            if (sShowing != null) {
                try {
                    sShowing.dismiss();
                } catch (Throwable ignored) {
                }
            }
            sShowing = pw;
            anchor.postDelayed(() -> {
                try {
                    pw.dismiss();
                } catch (Throwable ignored) {
                }
                if (sShowing == pw) sShowing = null;
            }, 1200L);
        } catch (Throwable tr) {
            Log.w(TAG, "banner failed: " + tr);
        }
    }
}
