package com.c10h.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * 当前屏幕截图（AOSP {@code SurfaceControl.screenshot}，需要 READ_FRAME_BUFFER）。
 * 平台签名后可截取当前应用/桌面画面，用于手势导航的缩小动画。
 */
public final class ScreenCapture {

    private ScreenCapture() {
    }

    public static Bitmap capture(Context c) {
        // 1) 直接调用系统 screencap（app 已持有 READ_FRAME_BUFFER，写自己的 cache 目录）。
        try {
            java.io.File f = new java.io.File(c.getCacheDir(), "c10h_shot.png");
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/screencap", "-p", f.getAbsolutePath()});
            int code = p.waitFor();
            Log.i("C10HCap", "screencap code=" + code + " exists=" + f.exists());
            if (code == 0 && f.exists()) {
                Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                f.delete();
                if (b != null) {
                    return b;
                }
            }
        } catch (Throwable t) {
            Log.w("C10HCap", "screencap failed: " + t);
        }
        int w = 0;
        int h = 0;
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            try {
                java.lang.reflect.Method m = sc.getMethod("screenshot",
                        Rect.class, int.class, int.class);
                Object b = m.invoke(null, new Rect(0, 0, w, h), w, h);
                Log.i("C10HCap", "screenshot(rect,w,h) -> " + b);
                if (b instanceof Bitmap) {
                    return (Bitmap) b;
                }
            } catch (Throwable t) {
                Log.w("C10HCap", "screenshot(rect,w,h) failed: " + t);
            }
            try {
                java.lang.reflect.Method m = sc.getMethod("screenshot", int.class, int.class);
                Object b = m.invoke(null, w, h);
                Log.i("C10HCap", "screenshot(w,h) -> " + b);
                if (b instanceof Bitmap) {
                    return (Bitmap) b;
                }
            } catch (Throwable t) {
                Log.w("C10HCap", "screenshot(w,h) failed: " + t);
            }
        } catch (Throwable t) {
            Log.w("C10HCap", "SurfaceControl missing: " + t);
        }
        return null;
    }
}
