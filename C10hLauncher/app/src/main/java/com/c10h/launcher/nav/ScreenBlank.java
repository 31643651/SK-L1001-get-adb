package com.c10h.launcher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 息屏遮罩（保持后台模式专用，不进入休眠）：
 * 全屏纯黑 + 窗口亮度 0（BRIGHTNESS_OFF），系统仍处于交互态，
 * CPU/网络/服务不中断；触摸或任意按键立即移除遮罩并恢复亮度。
 *
 * 只有在 ScreenKeepAlive.enabled() 时才使用；普通模式走 goToSleep。
 */
public final class ScreenBlank {

    private static final String TAG = "C10HPower";

    private static View overlay;
    private static Runnable onWake;

    private ScreenBlank() {
    }

    public static boolean isBlanked() {
        return overlay != null;
    }

    /** 由桌面设置，遮罩被点亮后回调（用于重启空闲计时）。 */
    public static void setOnWake(Runnable r) {
        onWake = r;
    }

    public static void blank(Context c) {
        if (overlay != null) {
            return;
        }
        if (c == null) {
            return;
        }
        try {
            Context app = c.getApplicationContext();
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            FrameLayout root = new FrameLayout(app);
            root.setBackgroundColor(0xFF000000);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    unblank();
                }
            });
            root.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    // 第一下任意键只负责点亮，不把按键透传给下层应用。
                    unblank();
                    return true;
                }
            });

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.OPAQUE);
            p.gravity = Gravity.TOP | Gravity.START;
            // 关键：窗口亮度 0 = 关背光；系统仍保持 awake，网络不断。
            p.screenBrightness = 0f;
            wm.addView(root, p);
            root.requestFocus();
            overlay = root;
            Log.i(TAG, "screen blanked (keep-alive mode)");
        } catch (Throwable t) {
            Log.w(TAG, "blank failed: " + t);
            overlay = null;
        }
    }

    public static void unblank() {
        final View v = overlay;
        overlay = null;
        if (v != null) {
            try {
                WindowManager wm = (WindowManager)
                        v.getContext().getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    wm.removeViewImmediate(v);
                }
                Log.i(TAG, "screen unblanked");
            } catch (Throwable ignored) {
            }
        }
        Runnable r = onWake;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
