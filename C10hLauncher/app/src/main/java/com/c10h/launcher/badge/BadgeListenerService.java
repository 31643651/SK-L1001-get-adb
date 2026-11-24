package com.c10h.launcher.badge;

import android.app.Notification;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 通知角标监听（规格 §6.9，:badge 进程）：
 *  - 维护每包未读计数（posted +1 / removed -1，下限 0），存自有 prefs（c10h_badge，
 *    :badge 进程单写者，评审 S4：主进程绝不读本文件）；
 *  - 变更即发显式广播 BADGE_UPDATE（extra: pkg/count），主进程 receiver 只做内存更新；
 *  - NLS 未启用 → 本服务不跑 → 无角标静默（§6.9）；授权入口 = 设置·通知访问页。
 * 铁律：本类禁止 import 桌面侧任何类（模块隔离 §3 规则 3）。
 */
public class BadgeListenerService extends NotificationListenerService {

    private static final String TAG = "C10HBadge";
    private static final String ACTION = "com.c10h.launcher.action.BADGE_UPDATE";
    private static final String PREFS = "c10h_badge";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "listener connected pid=" + android.os.Process.myPid());
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) {
                bump(sbn, 1, false);
            }
            syncAll();
        } catch (Throwable t) {
            Log.e(TAG, "reconnect sync failed: " + t);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            bump(sbn, 1, true);
        } catch (Throwable t) {
            Log.e(TAG, "posted failed: " + t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            bump(sbn, -1, true);
        } catch (Throwable t) {
            Log.e(TAG, "removed failed: " + t);
        }
    }

    private void bump(StatusBarNotification sbn, int delta, boolean broadcast) {
        if (sbn == null || sbn.getPackageName() == null) {
            return;
        }
        if (sbn.getPackageName().equals(getPackageName())) {
            return;
        }
        Notification n = sbn.getNotification();
        if (n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return;   // 常驻通知不计
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int cur = p.getInt("cnt_" + sbn.getPackageName(), 0);
        int next = Math.max(0, cur + delta);
        if (next != cur) {
            p.edit().putInt("cnt_" + sbn.getPackageName(), next).apply();
            if (broadcast) {
                send(sbn.getPackageName(), next);
            }
        }
    }

    /** 广播带值（S4）：extra 携带 pkg/count，接收方零 SP 读取。 */
    private void send(String pkg, int count) {
        try {
            Intent i = new Intent(ACTION);
            i.setPackage(getPackageName());
            i.putExtra("pkg", pkg);
            i.putExtra("count", count);
            sendBroadcast(i);
        } catch (Throwable t) {
            Log.e(TAG, "broadcast failed: " + t);
        }
    }

    private void syncAll() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (e.getKey().startsWith("cnt_") && e.getValue() instanceof Integer) {
                    send(e.getKey().substring(4), (Integer) e.getValue());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "syncAll failed: " + t);
        }
    }
}
