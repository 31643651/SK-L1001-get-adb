package com.c10h.launcher;

import android.graphics.PixelFormat;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 自研下拉面板（模块 F 案 B，规格 §9.5）：顶部 overlay 2×3 磁贴
 * （WLAN/蓝牙/亮度/音量/自动旋转/截屏）；每磁贴独立降级；6s 超时隐藏；
 * 入口 = 长按工作区顶部搜索栏。overlay 权限缺失 → 不建窗（桌面内不提供）。
 */
public class QsPanel {

    private static final long AUTO_HIDE_MS = 6000;

    private final MainActivity host;
    private final WindowManager wm;
    private final Handler h = new Handler();
    private LinearLayout panel;
    private boolean attached;

    private final Runnable hideTask = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    public QsPanel(MainActivity activity) {
        this.host = activity;
        this.wm = (WindowManager) activity.getSystemService(MainActivity.WINDOW_SERVICE);
    }

    public void toggle() {
        if (!android.provider.Settings.canDrawOverlays(host)) {
            Toast.makeText(host, "无悬浮窗权限：下拉面板不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!attached) {
            attach();
        }
        if (panel == null) {
            return;
        }
        if (panel.getVisibility() == View.VISIBLE) {
            hide();
        } else {
            refreshTiles();
            panel.setVisibility(View.VISIBLE);
            restartHide();
        }
    }

    private void attach() {
        try {
            panel = build();
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = host.dp(6);
            wm.addView(panel, p);
            attached = true;
        } catch (Throwable t) {
            detach();
            Toast.makeText(host, "下拉面板失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    public void detach() {
        try {
            h.removeCallbacks(hideTask);
        } catch (Throwable ignored) {
        }
        if (panel != null) {
            try {
                wm.removeViewImmediate(panel);
            } catch (Throwable t) {
                try {
                    wm.removeView(panel);
                } catch (Throwable ignored) {
                }
            }
        }
        panel = null;
        attached = false;
    }

    private LinearLayout build() {
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF016222E);
        int pad = host.dp(10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(host);
        title.setText("快捷开关");
        title.setTextColor(0xFF9FB3C8);
        title.setTextSize(12);
        root.addView(title);

        LinearLayout row1 = new LinearLayout(host);
        LinearLayout row2 = new LinearLayout(host);
        row1.addView(cell("WLAN", new Runnable() {
            @Override
            public void run() {
                toggleWifi();
            }
        }));
        row1.addView(cell("蓝牙", new Runnable() {
            @Override
            public void run() {
                toggleBt();
            }
        }));
        row1.addView(cell("亮度", new Runnable() {
            @Override
            public void run() {
                toggleAutoBrightness();
            }
        }));
        row2.addView(cell("音量", new Runnable() {
            @Override
            public void run() {
                toggleMute();
            }
        }));
        row2.addView(cell("自动旋转", new Runnable() {
            @Override
            public void run() {
                toggleRotate();
            }
        }));
        row2.addView(cell("截屏", new Runnable() {
            @Override
            public void run() {
                NavActions.injectKey(host, android.view.KeyEvent.KEYCODE_SYSRQ);
            }
        }));
        row2.addView(cell("息屏", new Runnable() {
            @Override
            public void run() {
                PowerActions.sleep(host);
            }
        }));
        root.addView(row1);
        root.addView(row2);
        refreshTiles();
        return root;
    }

    private int tileIndex = 0;
    private final TextView[] states = new TextView[7];

    private View cell(final String label, final Runnable action) {
        LinearLayout cell = new LinearLayout(host);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        int p = host.dp(12);
        cell.setPadding(p, p, p, p);
        cell.setMinimumHeight(host.dp(64));
        TextView name = new TextView(host);
        name.setText(label);
        name.setTextColor(0xFFE6EDF3);
        name.setTextSize(16);
        name.setGravity(Gravity.CENTER);
        cell.addView(name);
        TextView st = new TextView(host);
        st.setTextColor(0xFF3DDC97);
        st.setTextSize(12);
        st.setGravity(Gravity.CENTER);
        cell.addView(st);
        states[tileIndex++] = st;
        cell.setFocusable(true);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Throwable t) {
                    Toast.makeText(host, label + " 不可用", Toast.LENGTH_SHORT).show();
                }
                refreshTiles();
                restartHide();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = host.dp(4);
        lp.rightMargin = host.dp(4);
        cell.setLayoutParams(lp);
        return cell;
    }

    private void refreshTiles() {
        try {
            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager)
                    host.getApplicationContext().getSystemService(
                            android.content.Context.WIFI_SERVICE);
            setState(0, w != null && w.isWifiEnabled());
        } catch (Throwable t) {
            setUnavailable(0);
        }
        try {
            android.bluetooth.BluetoothAdapter a =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            setState(1, a != null && a.isEnabled());
        } catch (Throwable t) {
            setUnavailable(1);
        }
        try {
            setState(2, android.provider.Settings.System.getInt(
                    host.getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        } catch (Throwable t) {
            setUnavailable(2);
        }
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    host.getSystemService(android.content.Context.AUDIO_SERVICE);
            setState(3, am != null && am.getStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC) > 0);
        } catch (Throwable t) {
            setUnavailable(3);
        }
        try {
            setState(4, android.provider.Settings.System.getInt(
                    host.getContentResolver(),
                    android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) == 1);
        } catch (Throwable t) {
            setUnavailable(4);
        }
        if (states[5] != null) {
            states[5].setText("按下即截");
            states[5].setTextColor(0xFF9FB3C8);
        }
        if (states[6] != null) {
            states[6].setText("按下即灭");
            states[6].setTextColor(0xFF9FB3C8);
        }
    }

    private void setState(int i, boolean on) {
        if (states[i] == null) {
            return;
        }
        states[i].setText(on ? "开" : "关");
        states[i].setTextColor(0xFF3DDC97);
    }

    private void setUnavailable(int i) {
        if (states[i] == null) {
            return;
        }
        states[i].setText("不可用");
        states[i].setTextColor(0xFFFF6B6B);
    }

    private void toggleWifi() {
        android.net.wifi.WifiManager w = (android.net.wifi.WifiManager)
                host.getApplicationContext().getSystemService(
                        android.content.Context.WIFI_SERVICE);
        if (w == null || !w.setWifiEnabled(!w.isWifiEnabled())) {
            throw new IllegalStateException("wifi toggle rejected");
        }
    }

    private void toggleBt() {
        android.bluetooth.BluetoothAdapter a =
                android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (a == null) {
            throw new IllegalStateException("no bt");
        }
        if (a.isEnabled()) {
            a.disable();
        } else if (!a.enable()) {
            throw new IllegalStateException("enable rejected");
        }
    }

    private void toggleAutoBrightness() {
        boolean auto = android.provider.Settings.System.getInt(
                host.getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        android.provider.Settings.System.putInt(host.getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                auto ? android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        : android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
    }

    private void toggleMute() {
        android.media.AudioManager am = (android.media.AudioManager)
                host.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am == null) {
            throw new IllegalStateException("no audio");
        }
        int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                cur > 0 ? 0 : Math.max(1,
                        am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) / 2), 0);
    }

    private void toggleRotate() {
        boolean on = android.provider.Settings.System.getInt(
                host.getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
        android.provider.Settings.System.putInt(host.getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, on ? 0 : 1);
    }

    private void restartHide() {
        h.removeCallbacks(hideTask);
        h.postDelayed(hideTask, AUTO_HIDE_MS);
    }

    private void hide() {
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
    }
}
