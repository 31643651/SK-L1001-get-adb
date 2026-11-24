package com.c10h.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * 真息屏 + 后台保活（v2，系统 API 方案，2026-11-24）：
 *
 * 顺序是成败关键：
 *   ① 先 acquire(PARTIAL_WAKE_LOCK) 挡住 CPU suspend；
 *   ② 再 goToSleep() 让显示进入 Asleep（面板/背光真正断电）；
 *   ③ 2 秒后判定 pm.isScreenOn()，若仍为 true 说明本机 ROM 不认这套组合，
 *      立即释放锁并降级到黑遮罩（ScreenBlank），绝不留在“半休眠”；
 *   ④ 亮屏/解锁/桌面交互时释放锁，恢复系统默认休眠策略。
 *
 * 不使用任何 sysfs/root 写入。
 */
public final class ScreenOffController {

    private static final String TAG = "C10HPower";
    private static final long LOCK_TIMEOUT_MS = 8L * 60L * 60L * 1000L; // 8h 上限
    private static final long CONFIRM_MS = 2000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static PowerManager.WakeLock partial;
    private static boolean sleepRequested;
    private static boolean registered;

    private ScreenOffController() {
    }

    /** 由 ModuleManager 在主进程调用一次，随进程存活。 */
    public static synchronized void register(Context c) {
        if (registered) {
            return;
        }
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            c.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    String a = i == null ? null : i.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                        onScreenOff(ctx);
                    } else if (Intent.ACTION_SCREEN_ON.equals(a)
                            || Intent.ACTION_USER_PRESENT.equals(a)) {
                        onScreenOn();
                    }
                }
            }, f);
            registered = true;
            Log.i(TAG, "ScreenOffController registered");
        } catch (Throwable t) {
            Log.w(TAG, "register failed: " + t);
        }
    }

    public static boolean isSleepRequested() {
        return sleepRequested;
    }

    /**
     * 保持后台模式下的熄屏：先锁后 sleep，并在 2 秒后确认是否真的灭屏。
     */
    public static void sleepKeepAlive(final Context c) {
        final Context app = c.getApplicationContext();
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                fallback(app, "no PowerManager");
                return;
            }
            ensureLock(app, pm);
            sleepRequested = true;
            try {
                java.lang.reflect.Method m =
                        PowerManager.class.getMethod("goToSleep", long.class);
                m.invoke(pm, SystemClock.uptimeMillis());
                Log.i(TAG, "goToSleep issued with wakelock held");
            } catch (Throwable t) {
                Log.e(TAG, "goToSleep failed: " + t);
                release();
                fallback(app, "goToSleep failed");
                return;
            }
            H.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!sleepRequested) {
                        return;
                    }
                    boolean on = true;
                    try {
                        PowerManager p = (PowerManager)
                                app.getSystemService(Context.POWER_SERVICE);
                        on = p != null && p.isScreenOn();
                    } catch (Throwable ignored) {
                    }
                    if (on) {
                        Log.w(TAG, "screen still ON after goToSleep -> fallback overlay");
                        sleepRequested = false;
                        release();
                        ScreenBlank.blank(app);
                    } else {
                        Log.i(TAG, "screen off confirmed, keepalive lock held");
                    }
                }
            }, CONFIRM_MS);
        } catch (Throwable t) {
            Log.e(TAG, "sleepKeepAlive failed: " + t);
            release();
            fallback(app, "exception");
        }
    }

    /** 普通模式：真休眠（不持锁）。 */
    public static void sleepNormal(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            java.lang.reflect.Method m =
                    PowerManager.class.getMethod("goToSleep", long.class);
            m.invoke(pm, SystemClock.uptimeMillis());
        } catch (Throwable t) {
            Log.w(TAG, "sleepNormal failed: " + t);
            try {
                NavActions.injectKey(c, android.view.KeyEvent.KEYCODE_POWER);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void fallback(Context c, String why) {
        Log.w(TAG, "keepalive fallback: " + why);
        sleepRequested = false;
        release();
        ScreenBlank.blank(c);
    }

    private static synchronized void ensureLock(Context c, PowerManager pm) {
        try {
            if (partial == null) {
                partial = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "c10h:screenoff");
                partial.setReferenceCounted(false);
            }
            if (!partial.isHeld()) {
                partial.acquire(LOCK_TIMEOUT_MS);
                Log.i(TAG, "partial wakelock acquired (before goToSleep)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "acquire wakelock failed: " + t);
        }
    }

    /** SCREEN_OFF 确认：锁必须仍持有；若被系统清掉则补一次。 */
    private static synchronized void onScreenOff(Context c) {
        if (!sleepRequested) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && (partial == null || !partial.isHeld())) {
                ensureLock(c, pm);
                Log.i(TAG, "SCREEN_OFF re-acquired wakelock");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 亮屏/解锁：释放锁，恢复正常休眠。 */
    public static void onScreenOn() {
        sleepRequested = false;
        release();
        Log.i(TAG, "screen on -> wakelock released");
    }

    public static synchronized void release() {
        try {
            if (partial != null && partial.isHeld()) {
                partial.release();
                Log.i(TAG, "partial wakelock released");
            }
        } catch (Throwable ignored) {
        }
    }
}
