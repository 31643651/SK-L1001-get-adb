package com.c10h.launcher.block;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;

/**
 * ADB 自愈守护（补充文档 D2/§4）：60s 查 init.svc.adbd，stopped → 属性摆渡拉回
 * （sys.usb.config none→mtp,adb，seq9 实证普通 uid 可写）+ BOOT+15s 补发。
 * 状态经广播带值回设置页；180s 陷阱单次击杀失效（闪断 ≤60s 属预期）。
 */
public final class AdbGuard {

    private static final String TAG = "C10HGuard";
    private static final String ACTION_TICK = "com.c10h.launcher.action.ADB_TICK";

    private AdbGuard() {
    }

    public static boolean enabled(Context c) {
        return RuleStore.p(c).getBoolean("guard", true);
    }

    public static void setEnabled(Context c, boolean on) {
        RuleStore.p(c).edit().putBoolean("guard", on).apply();
        if (on) {
            start(c);
        } else {
            stop(c);
        }
        RuleStore.broadcast(c);
    }

    public static void start(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 60000L, 60000L, tick(c));
        } catch (Throwable t) {
            Log.e(TAG, "start failed: " + t);
        }
    }

    public static void stop(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(tick(c));
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent tick(Context c) {
        Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_TICK);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, 7001, i, f);
    }

    public static String adbdState() {
        return sysGet("init.svc.adbd");
    }

    /** 60s 自检：adbd 非 running → 属性摆渡拉回一次并记账。 */
    public static void check(Context c) {
        if (!enabled(c)) {
            return;
        }
        try {
            if (!"running".equals(sysGet("init.svc.adbd"))) {
                sysSet("sys.usb.config", "none");
                Thread.sleep(300L);
                sysSet("sys.usb.config", "mtp,adb");
                appendLog(c);
                Log.i(TAG, "adbd revived by sys.usb.config cycle");
            }
        } catch (Throwable t) {
            Log.e(TAG, "check failed: " + t);
        }
        RuleStore.broadcast(c);
    }

    public static void appendLog(Context c) {
        try {
            JSONArray a = new JSONArray(RuleStore.p(c).getString("guard_log", "[]"));
            a.put(new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                    java.util.Locale.CHINA).format(new java.util.Date()));
            while (a.length() > 20) {
                a.remove(0);
            }
            RuleStore.p(c).edit().putString("guard_log", a.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String recentLog(Context c) {
        return RuleStore.p(c).getString("guard_log", "[]");
    }

    private static String sysGet(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void sysSet(String key, String val) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            c.getMethod("set", String.class, String.class).invoke(null, key, val);
        } catch (Throwable t) {
            Log.e(TAG, "setprop " + key + " failed: " + t);
        }
    }
}
