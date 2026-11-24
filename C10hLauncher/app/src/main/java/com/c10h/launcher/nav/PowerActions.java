package com.c10h.launcher;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * 电源相关动作（2026-11-24）：
 *
 * 1) {@link #sleep}：真正熄屏。平台签名 + DEVICE_POWER 下反射调用隐藏 API
 *    PowerManager.goToSleep(uptimeMillis)，与按电源键等价；
 * 2) {@link #ensureSleepNotHijacked}：本机 ROM 把"休眠"接到了相册屏保
 *    （screensaver_activate_on_sleep=1 + screensaver_enabled=1），导致超时后
 *    屏幕只变暗、显示屏保而永不息屏。持有 WRITE_SECURE_SETTINGS 时，
 *    首次运行关闭一次该屏保，之后不再覆盖用户选择。
 */
public final class PowerActions {

    private static final String TAG = "C10HPower";
    private static final String PREFS = "c10h_launcher";

    private PowerActions() {
    }

    /** 立即熄屏（1.52 回退）：纯系统熄屏，不持锁、不做黑遮罩、不保后台。 */
    public static void sleep(Context c) {
        ScreenOffController.sleepNormal(c);
    }

    /**
     * 首次运行把默认息屏时间设为两分钟，并按当前模式同步系统超时。
     */
    public static boolean ensureScreenTimeoutDefault(Context c) {
        final String marker = "screen_timeout_v4";
        try {
            android.content.SharedPreferences p =
                    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getBoolean(marker, false)) {
                return false;
            }
            boolean changed = false;
            try {
                if (!p.contains("blank_timeout_ms")) {
                    p.edit().putLong("blank_timeout_ms", 120000L).apply();
                    changed = true;
                }
                ScreenKeepAlive.applySystemTimeout(c);
                p.edit().putBoolean(marker, true).apply();
            } catch (Throwable t) {
                // 写失败不记标记，下次启动再试。
            }
            return changed;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 关闭"休眠即屏保"的系统设置。
     *
     * 每次启动都校正（ROM/相册应用可能在开机后重新打开屏保，只做一次的标记
     * 会失效）；只有用户在本设置页显式开启过屏保时才不覆盖。
     * 返回值 = 本次是否改动了系统设置。
     */
    public static boolean ensureSleepNotHijacked(Context c) {
        try {
            android.content.SharedPreferences p =
                    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getBoolean("sc_user_enabled", false)) {
                return false;
            }
            boolean changed = false;
            try {
                if (Settings.Secure.getInt(c.getContentResolver(),
                        "screensaver_enabled", 0) != 0) {
                    changed = true;
                }
                Settings.Secure.putInt(c.getContentResolver(),
                        "screensaver_enabled", 0);
            } catch (Throwable ignored) {
            }
            try {
                if (Settings.Secure.getInt(c.getContentResolver(),
                        "screensaver_activate_on_sleep", 0) != 0) {
                    changed = true;
                }
                Settings.Secure.putInt(c.getContentResolver(),
                        "screensaver_activate_on_sleep", 0);
            } catch (Throwable ignored) {
            }
            return changed;
        } catch (Throwable t) {
            Log.w(TAG, "ensureSleepNotHijacked failed: " + t);
            return false;
        }
    }

    /** 当前是否启用了休眠屏保（设置页展示用）。 */
    public static boolean screensaverEnabled(Context c) {
        try {
            return Settings.Secure.getInt(c.getContentResolver(),
                    "screensaver_enabled", 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 设置页开关：同时控制屏保开关与"休眠时启动屏保"（并记住用户选择）。 */
    public static boolean setScreensaverEnabled(Context c, boolean on) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("sc_user_enabled", on).apply();
            Settings.Secure.putInt(c.getContentResolver(),
                    "screensaver_enabled", on ? 1 : 0);
            Settings.Secure.putInt(c.getContentResolver(),
                    "screensaver_activate_on_sleep", on ? 1 : 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
