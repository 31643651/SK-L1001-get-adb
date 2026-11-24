package com.c10h.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * 屏幕超时存储（1.52 回退版）。
 *
 * 2026-11-24 的「真息屏 + 后台保活」多次尝试（1.47 唤醒锁、1.48 黑遮罩、
 * 1.51 先锁后 goToSleep）在本机 ROM 上均无法同时满足“面板真灭 + 后台继续”：
 *   - 持 PARTIAL_WAKE_LOCK 时 goToSleep 不会关显示（实测 Display Power 仍 ON）；
 *   - 黑遮罩只压窗口亮度，背光仍亮。
 * 按用户要求回退为**纯锁屏/熄屏**：到点直接 goToSleep，网络与后台随系统休眠暂停。
 *
 * 本类现在只负责：
 *   - 保存用户选择的超时（blank_timeout_ms，默认 2 分钟）；
 *   - 把超时写入系统 SCREEN_OFF_TIMEOUT（“永不”用 ROM 接受的最大值）。
 */
public final class ScreenKeepAlive {

    private static final String TAG = "C10HPower";
    private static final String PREFS = "c10h_launcher";
    private static final String K_BLANK_MS = "blank_timeout_ms";
    public static final long DEFAULT_BLANK_MS = 120000L;

    private ScreenKeepAlive() {
    }

    private static SharedPreferences prefs(Context c) {
        // 设置页在 :settings 进程写，桌面在主进程读，保持与 WorkspaceStore 一致。
        return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    /** 息屏等待时间（毫秒）；-1 表示永不。 */
    public static long blankTimeout(Context c) {
        return prefs(c).getLong(K_BLANK_MS, DEFAULT_BLANK_MS);
    }

    public static void setBlankTimeout(Context c, long ms) {
        prefs(c).edit().putLong(K_BLANK_MS, ms).apply();
        applySystemTimeout(c);
    }

    /** 把超时同步到系统；"永不"写 ROM 接受的最大毫秒数。 */
    public static void applySystemTimeout(Context c) {
        try {
            long ms = blankTimeout(c);
            if (ms <= 0) {
                ms = Integer.MAX_VALUE;
            }
            Settings.System.putLong(c.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, ms);
            Log.i(TAG, "screen timeout=" + ms);
        } catch (Throwable t) {
            Log.w(TAG, "applySystemTimeout failed: " + t);
        }
    }
}
