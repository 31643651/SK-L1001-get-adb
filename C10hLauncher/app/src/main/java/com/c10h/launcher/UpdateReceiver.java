package com.c10h.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 服务端一键更新入口（无需在设备上点击）：
 *
 *   adb shell am broadcast -a com.c10h.launcher.action.UPDATE \
 *       -e url http://10.10.0.1:8000/C10hLauncher.apk
 *
 * 接收后后台下载 → 校验包名 → PackageInstaller 静默提交安装。
 * 平台签名 + INSTALL_PACKAGES 时不需要用户确认；安装自身会重启桌面。
 */
public class UpdateReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.c10h.launcher.action.UPDATE";
    private static final String TAG = "C10HAutoUpdate";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        String url = intent.getStringExtra("url");
        if (url == null || url.trim().isEmpty()) {
            Log.w(TAG, "missing url");
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        final String src = url.trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File apk = new File(app.getFilesDir(), "update_auto.apk");
                    download(src, apk);
                    PackageInfo pi = app.getPackageManager()
                            .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                    if (pi == null) {
                        Log.e(TAG, "downloaded file is not an APK");
                        return;
                    }
                    if (!app.getPackageName().equals(pi.packageName)) {
                        Log.e(TAG, "package mismatch: " + pi.packageName);
                        return;
                    }
                    Log.i(TAG, "installing " + pi.versionName
                            + " (" + pi.versionCode + ")");
                    install(app, apk, pi.packageName);
                } catch (Throwable t) {
                    Log.e(TAG, "update failed: " + t);
                } finally {
                    try {
                        pending.finish();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "c10h-auto-update").start();
    }

    private void download(String url, File out) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new java.io.IOException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                total += n;
            }
            fo.flush();
            fo.close();
            in.close();
            Log.i(TAG, "downloaded " + total + " bytes");
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void install(Context app, File apk, String pkg) throws Exception {
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            OutputStream out = session.openWrite("base.apk", 0, -1);
            InputStream in = new java.io.FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            session.fsync(out);
            out.close();
            Intent cb = new Intent(app, UpdateReceiver.class);
            cb.setAction("com.c10h.launcher.action.UPDATE_COMMITTED");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    app, 41, cb, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pi.getIntentSender());
            Log.i(TAG, "session committed");
        } finally {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
